package com.obdscanner

import com.obdscanner.elm.Elm327
import com.obdscanner.transport.SerialLink
import com.obdscanner.transport.UsbTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A USB-serial chip in front of the demo ELM327 listening at [adapterBaud]. At any other port speed what goes
 * out reaches the adapter as junk and its answer comes back as junk, like on a real chip.
 */
private class FakeChip(private val adapterBaud: Int?) : SerialLink {
    override val name = "USB · FAKE"
    private val elm = BlockingMock()
    private val rx = LinkedBlockingQueue<Int>()
    private val random = Random(1)
    var baud = 0
        private set
    val bauds = mutableListOf<Int>()
    var opened = false
        private set
    @Volatile private var gone = false

    override fun open() {
        elm.open()
        opened = true
        thread(isDaemon = true) {
            val buf = ByteArray(256)
            while (true) {
                val n = elm.input.read(buf)
                if (n < 0) break
                for (i in 0 until n) rx.put(buf[i].toInt() and 0xFF)
            }
        }
    }

    override fun setBaud(baud: Int) {
        this.baud = baud
        bauds += baud
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (gone) throw IOException("USB device gone")
        val first = (if (timeoutMs == 0) rx.take() else rx.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) ?: return 0
        if (first < 0) throw IOException("Connection closed")
        buf[0] = first.toByte()
        var n = 1
        while (n < buf.size) {
            val next = rx.peek() ?: break
            if (next < 0) break
            buf[n++] = rx.take().toByte()
        }
        return n
    }

    override fun write(data: ByteArray) {
        if (gone) throw IOException("USB device gone")
        if (baud == adapterBaud) {
            elm.output.write(data)
            elm.output.flush()
        } else if (adapterBaud != null) {
            // The adapter hears junk and answers "?" at its own speed: at ours that's a few high bytes.
            rx.put(0xF8)
            repeat(data.size / 4) { rx.put(listOf(0x00, 0x80, 0xF8, 0xFE, 0x3E)[random.nextInt(5)]) }
        }
    }

    override fun close() {
        opened = false
        rx.put(-1)
        elm.close()
    }

    /** Cable pulled out mid-session. */
    fun unplug() {
        gone = true
        rx.put(-1)
    }
}

class UsbTransportTest {
    private fun transport(chip: FakeChip, preferred: Int? = null, found: (Int) -> Unit = {}) =
        UsbTransport(chip, preferred, { println(it) }, found)

    @Test
    fun findsTheAdapterSpeed() {
        for (speed in listOf(38400, 9600, 115200)) {
            val chip = FakeChip(speed)
            var found = 0
            val elm = Elm327(transport(chip) { found = it }) { d, t -> println("$d $t") }
            runBlocking { elm.open() }
            assertEquals(speed, found)
            runBlocking {
                assertTrue(elm.send("ATZ").lines.any { it.startsWith("ELM327") })
                // A whole request to the car goes through, not only the probe.
                // The demo car searches 1.5 s before its first answer: more than the default timeout.
                assertTrue(elm.send("0100", 4000).lines.any { "41 00" in it })
            }
            elm.close()
        }
    }

    @Test
    fun triesTheRememberedSpeedFirst() {
        val chip = FakeChip(9600)
        transport(chip, preferred = 9600).connect()
        assertEquals(listOf(9600), chip.bauds)
    }

    @Test
    fun rememberedSpeedThatNoLongerWorksFallsBack() {
        val chip = FakeChip(38400)
        var found = 0
        transport(chip, preferred = 115200) { found = it }.connect()
        assertEquals(listOf(115200, 38400), chip.bauds)
        assertEquals(38400, found)
    }

    @Test
    fun silentAdapterFailsAndClosesThePort() {
        val chip = FakeChip(null)
        try {
            transport(chip).connect()
            fail("opened a port nobody answers on")
        } catch (e: IOException) {
            assertEquals(UsbTransport.BAUDS, chip.bauds)
            assertFalse(chip.opened)
        }
    }

    @Test
    fun junkEndingInPromptIsNotAnAdapter() {
        assertFalse(UsbTransport.isElmReply(byteArrayOf(0x00, 0xF8.toByte(), 0x3E)))
        assertFalse(UsbTransport.isElmReply(">".toByteArray()))
        assertFalse(UsbTransport.isElmReply("ELM327 v1.5\r\r".toByteArray()))
        assertTrue(UsbTransport.isElmReply("ELM327 v1.5\r\r>".toByteArray()))
        assertTrue(UsbTransport.isElmReply("?\r\r>".toByteArray()))
    }

    @Test
    fun closeEndsTheBlockedRead() {
        val chip = FakeChip(38400)
        val t = transport(chip)
        t.connect()
        var got = 0
        val reader = thread { got = t.input.read(ByteArray(512)) }
        Thread.sleep(200)
        assertTrue(reader.isAlive)
        t.close()
        reader.join(2000)
        assertFalse(reader.isAlive)
        assertEquals(-1, got)
    }

    @Test
    fun unplugIsALostLink() {
        val chip = FakeChip(38400)
        val elm = Elm327(transport(chip)) { d, t -> println("$d $t") }
        runBlocking { elm.open() }
        assertTrue(elm.alive)
        chip.unplug()
        val end = System.currentTimeMillis() + 2000
        while (elm.alive && System.currentTimeMillis() < end) Thread.sleep(20)
        assertFalse(elm.alive)
        try {
            runBlocking { elm.send("0100") }
            fail("sent to an unplugged adapter")
        } catch (_: IOException) {
        }
    }
}
