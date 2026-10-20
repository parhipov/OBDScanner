package com.obdscanner

import com.obdscanner.bus.WheelWatch
import com.obdscanner.car.CarDb
import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.obd.Reading
import com.obdscanner.session.Recorder
import com.obdscanner.transport.MockTransport
import com.obdscanner.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** «Контроль безопасности движения»: the wheels heard on the bus ([WheelWatch]), on the demo CTS and in the check's logic. */
class WheelWatchTest {
    private fun open(): Pair<Elm327, Obd> {
        val elm = Elm327(MockTransport()) { _, _ -> }
        runBlocking { elm.open() }
        return elm to Obd(elm)
    }

    private fun gm() = CarDbTest.load().let { CarDb.family("gm")!!.frames }

    @Test
    fun theGmFramesAreInTheDatabase() {
        val f = gm()
        assertEquals(listOf(0x348, 0x34A), f.map { it.id })
        assertEquals(listOf("wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"), f.flatMap { it.signals }.map { it.role })
        // 1/32 km/h, big-endian 16 bits (opendbc gm_global_a_powertrain, LS1Tech on the Holden VF).
        assertEquals(40.0, f[0].signals[0].fmt.decode(intArrayOf(0x05, 0x00, 0x00, 0x00))!!.first!!, 1e-9)
        assertTrue(CarDb.problems.toString(), CarDb.problems.isEmpty())
    }

    @Test
    fun heardAndTheAdapterBackToObd() {
        val frames = gm()
        val (elm, o) = open()
        val notes = mutableListOf<String>()
        val w = WheelWatch(frames, known = false) { notes += it }
        val heard = runBlocking {
            o.request("0100")
            w.listen(o, 600)
        }
        assertTrue(notes.toString(), heard.isNotEmpty())
        assertEquals(setOf("348:bus.FL", "348:bus.FR", "34A:bus.RL", "34A:bus.RR"), heard.map { it.key }.toSet())
        assertTrue(heard.all { (it.value ?: -1.0) in 0.0..100.0 })
        // The adapter answers OBD again: ATCAF1 and ATAR after the window.
        val speed = runBlocking { o.request("010D") }
        elm.close()
        assertTrue(speed.messages.any { it.service == 0x41 })
        assertEquals(WheelWatch.State.CHECKING, w.state)
    }

    @Test
    fun aMakeWhoseFramesArentThereStopsListening() {
        CarDbTest.load()
        val (elm, o) = open()
        val notes = mutableListOf<String>()
        val w = WheelWatch(CarDb.family("toyota")!!.frames, known = false) { notes += it }
        runBlocking { repeat(WheelWatch.SILENT_WINDOWS) { w.listen(o, 300) } }
        elm.close()
        assertEquals(WheelWatch.State.OFF, w.state)
        assertTrue(notes.toString(), notes.any { "off" in it && "0AA" in it })
    }

    /** The link drops in a window (the CTS's clone dropped Bluetooth in a long ATMA): off for the connection, the error goes on to the reconnect. */
    @Test
    fun aLinkThatDropsWhileListeningStopsIt() {
        val frames = gm()
        val inner = MockTransport()
        val dropping = object : Transport by inner {
            override suspend fun write(data: ByteArray) {
                if (data.decodeToString().startsWith("ATMA")) inner.close() else inner.write(data)
            }
        }
        val elm = Elm327(dropping) { _, _ -> }
        runBlocking { elm.open() }
        val o = Obd(elm)
        val notes = mutableListOf<String>()
        val w = WheelWatch(frames, known = true) { notes += it }
        val failed = runCatching { runBlocking { w.listen(o, 600) } }.exceptionOrNull()
        assertTrue(failed.toString(), failed is java.io.IOException)
        assertEquals(WheelWatch.State.OFF, w.state)
        assertTrue(notes.toString(), notes.any { "link dropped" in it })
        assertTrue(runBlocking { w.listen(o, 600) }.isEmpty())
    }

    private fun wheel(t: Long, v: Double) = Reading(Reading.key(0x348, "bus.FL"), 0x348, "FL", v, null, "km/h", 1, time = t, role = "wheel_fl")
    private fun obd(t: Long, v: Double) = Reading(Reading.key(0x7E8, "01.0D"), 0x7E8, "Speed", v, null, "km/h", 0, time = t)

    /** [ratio] — wheels / OBD; 30 pairs a second apart at 40 km/h. */
    private fun run(w: WheelWatch, ratio: Double, start: Long = 0): Boolean {
        var changed = false
        for (i in 0 until 30) {
            val t = start + i * 1000L
            changed = w.check(listOf(wheel(t + 200, 40.0 * ratio)), obd(t, 40.0)) || changed
        }
        return changed
    }

    @Test
    fun wheelsThatAgreeAreShown() {
        val notes = mutableListOf<String>()
        val w = WheelWatch(gm(), known = false) { notes += it }
        assertTrue(run(w, 1.03))
        assertEquals(WheelWatch.State.OK, w.state)
        assertTrue(notes.toString(), notes.single().contains("agree"))
    }

    @Test
    fun anotherMakesFrameIsTurnedOff() {
        val w = WheelWatch(gm(), known = true) {}
        assertTrue(run(w, 1.6))
        assertEquals(WheelWatch.State.OFF, w.state)
    }

    @Test
    fun slowOrFarApartPairsDontCount() {
        val w = WheelWatch(gm(), known = false) {}
        for (i in 0 until 40) {
            val t = i * 1000L
            w.check(listOf(wheel(t + 200, 10.0)), obd(t, 10.0))          // too slow
            w.check(listOf(wheel(t + 5000, 40.0)), obd(t, 40.0))         // the wheels long after the OBD answer
        }
        assertEquals(WheelWatch.State.CHECKING, w.state)
    }

    @Test
    fun axleSlip() {
        fun r(role: String, v: Double) = Reading("x:$role", 0x348, role, v, null, "km/h", 1, time = 5, role = role)
        val s = WheelWatch.axleSlip(listOf(r("wheel_fl", 30.0), r("wheel_fr", 30.0), r("wheel_rl", 39.0), r("wheel_rr", 39.0)))!!
        assertEquals(30.0, s.value!!, 1e-9)
        assertEquals(null, WheelWatch.axleSlip(listOf(r("wheel_rl", 39.0))))
    }

    /** The mode through CarLink on the demo CTS: its own set polled, the wheels recorded but not shown until they agree. */
    @Test
    fun theModeOnTheDemoCar() {
        CarDbTest.load()
        val (elm, o) = open()
        val store = MapStore()
        val link = CarLink(store, pause = {})
        val rec = object : Recorder {
            val notes = mutableListOf<String>()
            val values = mutableListOf<Reading>()
            override fun note(text: String) { notes += text }
            override fun report(title: String, body: String) {}
            override fun value(r: Reading) { values += r }
            override fun scanHit(req: Int, resp: Int, service: String, id: String, data: IntArray) {}
            override fun busFrames(window: Int, frames: List<Pair<Int, IntArray>>, windowMs: Long) {}
            override fun flush() {}
        }
        link.session = rec
        link.reset(null)
        link.setFuelWatch(true)
        link.setSafetyWatch(true)
        assertFalse("one mode or the other", link.fuelWatch.value)
        runBlocking {
            link.initAdapter(o, "demo")
            link.discover(o)
            link.startPolling()
            repeat(3) { link.pollCycle(o) }
        }
        elm.close()
        assertTrue(rec.notes.toString(), "safety watch: on" in rec.notes)
        assertTrue(rec.notes.toString(), rec.notes.any { it.startsWith("wheels: listening to 348/34A") })
        val r = link.readings.value
        assertTrue(r.keys.toString(), "7E8:01.0C" in r && "7E8:01.0D" in r)
        // Not checked yet (the demo car isn't 15 s on the move): recorded, not shown.
        assertTrue(rec.values.any { it.key == "348:bus.FL" })
        assertFalse(r.keys.toString(), r.keys.any { "bus." in it })
        // The front wheels at the OBD speed.
        val fl = rec.values.last { it.key == "348:bus.FL" }.value!!
        val sp = r.getValue("7E8:01.0D").value!!
        assertTrue("$fl vs $sp", abs(fl - sp) < 3)
        // Remembered: a new link reads the mode back.
        assertTrue(CarLink(store, pause = {}).safetyWatch.value)
    }
}
