package com.obdscanner

import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.obd.Reading
import com.obdscanner.session.Recorder
import com.obdscanner.transport.MockCar
import com.obdscanner.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An engine ECU that answers only after the discovery: the engine started after the connect (the Mazda CX-5 of
 * 2026-10-20 — the gearbox at once, the engine 48 s later; the whole trip went on the gearbox's PIDs). The demo CTS
 * with its engine asleep: the 7E8 lines cut out of every answer until it wakes.
 */
class LateEcuTest {
    private class SleepyEngine : Transport {
        override val name = "sleepy engine"
        var awake = false
        private val car = MockCar()
        private val queue = Channel<Int>(Channel.UNLIMITED)
        private val cmd = StringBuilder()

        override suspend fun open() {}

        override suspend fun read(buf: ByteArray): Int {
            if (buf.isEmpty()) return 0
            val first = queue.receive()
            if (first < 0) return -1
            buf[0] = first.toByte()
            var n = 1
            while (n < buf.size) {
                val next = queue.tryReceive().getOrNull() ?: break
                if (next < 0) {
                    queue.trySend(next)
                    break
                }
                buf[n++] = next.toByte()
            }
            return n
        }

        override suspend fun write(data: ByteArray) {
            for (b in data) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '\r') {
                    val line = cmd.toString().trim()
                    cmd.clear()
                    if (line.isEmpty()) continue
                    val text = car.handle(line).first
                    val out = if (awake) text else text.split('\r').filterNot { it.startsWith("7E8") }.joinToString("\r").ifEmpty { "NO DATA" }
                    for (ch in out + "\r\r>") queue.trySend(ch.code)
                } else if (c != '\n') {
                    cmd.append(c)
                }
            }
        }

        override fun close() {
            queue.trySend(-1)
        }
    }

    private class Rec : Recorder {
        val notes = mutableListOf<String>()
        val reports = mutableListOf<Pair<String, String>>()
        override fun note(text: String) { notes += text }
        override fun report(title: String, body: String) { reports += title to body }
        override fun value(r: Reading) {}
        override fun scanHit(req: Int, resp: Int, service: String, id: String, data: IntArray) {}
        override fun busFrames(window: Int, frames: List<Pair<Int, IntArray>>, windowMs: Long) {}
        override fun flush() {}
    }

    private class Run(val link: CarLink, val rec: Rec, val discovered: Set<Int>)

    /** Connect and discover with the engine [asleep], one cycle, the engine wakes, [cycles] more; in [mode], if given. */
    private fun drive(asleep: Boolean, cycles: Int = 4, mode: TripMode? = null): Run {
        CarDbTest.load()
        val t = SleepyEngine().also { it.awake = !asleep }
        val elm = Elm327(t) { _, _ -> }
        val o = Obd(elm)
        // Virtual time, a second per cycle: the PIDs come due again.
        var now = 1_000_000L
        val link = CarLink(MapStore(), clock = { now }, pause = { now += it })
        val rec = Rec()
        link.session = rec
        link.reset(null)
        mode?.let { link.setMode(it, true) }
        var discovered = emptySet<Int>()
        runBlocking {
            elm.open()
            link.initAdapter(o, "sleepy")
            link.discover(o)
            discovered = link.vehicle.value.ecus.keys
            link.startPolling()
            link.pollCycle(o)
            t.awake = true
            repeat(cycles) {
                now += 1000
                link.pollCycle(o)
            }
        }
        elm.close()
        return Run(link, rec, discovered)
    }

    @Test
    fun anEngineThatAnswersAfterTheDiscoveryIsPickedUp() {
        val r = drive(asleep = true)
        assertFalse(r.discovered.toString(), 0x7E8 in r.discovered)
        assertTrue(r.discovered.toString(), 0x7EA in r.discovered)
        val v = r.link.vehicle.value
        // Discovered again once, the gearbox kept
        assertEquals(r.rec.notes.toString(), 1, r.rec.notes.count { it.startsWith("late ECU: 7E8") })
        assertTrue(r.rec.reports.map { it.first }.toString(), r.rec.reports.any { it.first == tr("Блок ответил позже", "A module answered later") && "7E8" in it.second })
        assertTrue(v.ecus.keys.toString(), 0x7E8 in v.ecus && 0x7EA in v.ecus)
        // The engine's own PIDs polled: MAF isn't the gearbox's
        assertTrue(v.functional01.toString(), 0x10 in v.functional01)
        assertNotNull(r.link.readings.value.keys.toString(), r.link.readings.value["7E8:01.10"])
        // Its VIN and its manufacturer parameters: the engine was asleep at the first probe
        assertEquals("1G6DP5ED0B0123456".length, v.vin?.length)
        assertTrue(v.extActive.map { it.key }.toString(), v.extActive.any { it.req == 0x7E0 })
    }

    @Test
    fun theSameInSafetyMode() {
        val r = drive(asleep = true, mode = TripMode.SAFETY)
        assertEquals(r.rec.notes.toString(), 1, r.rec.notes.count { it.startsWith("late ECU: 7E8") })
        assertTrue(0x7E8 in r.link.vehicle.value.ecus)
    }

    /** Awake from the start: nothing found later, no second discovery. */
    @Test
    fun anEcuFoundAtTheDiscoveryIsNotLate() {
        val r = drive(asleep = false)
        assertTrue(r.discovered.toString(), 0x7E8 in r.discovered)
        assertTrue(r.rec.notes.toString(), r.rec.notes.none { it.startsWith("late ECU") })
        assertEquals(1, r.rec.reports.count { it.first == tr("Поддерживаемые PID (Mode 01)", "Supported PIDs (Mode 01)") })
    }
}
