package com.obdscanner

import com.obdscanner.elm.Elm327
import com.obdscanner.elm.ElmReply
import com.obdscanner.elm.Obd
import com.obdscanner.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Clone behaviour seen in other open-source ELM327 tools (AndrOBD, ddt4all), replayed on the app's driver. */
class ElmQuirksTest {
    /** An adapter that answers each command with [reply]; everything sent is kept in [sent]. */
    private class ScriptTransport(val reply: (String) -> String) : Transport {
        val sent: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val rx = LinkedBlockingQueue<Int>()
        private val line = StringBuilder()
        override val name = "script"
        override val input = object : InputStream() {
            override fun read(): Int = rx.take()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b[off] = rx.take().toByte()
                var n = 1
                // Not "b[off + n++] = (rx.poll() ?: break)": the index (and n++) goes first, so a break
                // there returned one stale byte — a '>' from an earlier reply shifted every answer (CI, v1.16).
                while (n < len) {
                    val x = rx.poll() ?: break
                    b[off + n++] = x.toByte()
                }
                return n
            }
        }
        override val output = object : OutputStream() {
            @Synchronized override fun write(b: Int) {
                val c = b.toChar()
                if (c != '\r') { line.append(c); return }
                val cmd = line.toString().also { line.clear() }
                sent += cmd
                for (ch in reply(cmd) + "\r\r>") rx.put(ch.code)
            }
        }
        override fun open() {}
        override fun close() {}
    }

    @Test
    fun chatterAndGluedReplies() {
        assertEquals(listOf("7E8 06 41 00 BE 3F A8 13"), ElmReply("0100", "SEARCHING...\r7E8 06 41 00 BE 3F A8 13", false).lines)
        // A clone that puts the first K-line reply on the BUS INIT line.
        assertEquals(listOf("48 6B 10 41 00 BE 3F A8 13 00"), ElmReply("0100", "BUS INIT: ...OK48 6B 10 41 00 BE 3F A8 13 00", false).lines)
        assertEquals(listOf("BUS INIT: ...ERROR"), ElmReply("0100", "BUS INIT: ...ERROR", false).lines)
        // The Bluetooth module's own message.
        assertEquals(listOf("NO DATA"), ElmReply("0100", "+CONNECTING<<94:65:2D:11:22:33\rNO DATA", false).lines)
    }

    @Test
    fun resetMarkers() {
        assertTrue(ElmReply("010C", "\rELM327 v1.5\r", false).adapterReset)
        assertTrue(ElmReply("010C", "LV RESET", false).adapterReset)
        assertTrue(ElmReply("22F190", "ERR94", false).adapterReset)
        // ATZ/ATI answer with the banner by design.
        assertFalse(ElmReply("ATZ", "ELM327 v1.5", false).adapterReset)
        assertFalse(ElmReply("010C", "7E8 04 41 0C 0B B8", false).adapterReset)
    }

    /** The adapter reboots in the middle of a poll: settings and protocol go back, the request is asked again. */
    @Test
    fun recoversAfterMidSessionReset() = runBlocking {
        // The driver sends from Dispatchers.IO, a different thread each time: the flag must be thread-safe.
        val rebooted = AtomicBoolean(false)
        val t = ScriptTransport { cmd ->
            when {
                cmd == "010C" && rebooted.compareAndSet(false, true) -> "\rELM327 v1.5"
                cmd == "010C" -> "7E8 04 41 0C 0B B8"
                cmd.startsWith("AT") -> "OK"
                else -> "NO DATA"
            }
        }
        val elm = Elm327(t) { _, _ -> }
        elm.open()
        val o = Obd(elm)
        var hooked = 0
        o.onAdapterReset = {
            hooked++
            o.at("ATE0"); o.at("ATH1"); o.at("ATSP6")
        }
        o.target(0x7E0, 0x7E8)
        val r = o.request("010C")
        assertEquals(1, hooked)
        assertEquals(listOf(0x41, 0x0C, 0x0B, 0xB8), r.messages.single().data.toList())
        // Protocol set again before the retry, and the module target restored (ATSH7E0 twice: before and after).
        val after = t.sent.dropWhile { it != "ATSP6" }
        assertTrue(t.sent.toString(), "ATSH7E0" in after && after.last() == "010C")
        elm.close()
    }

    /** KWP2000 on K-line (Hyundai Coupe 2003): a module by its physical address, then back to functional. */
    @Test
    fun klineKwpPhysicalAddressing() = runBlocking {
        val t = ScriptTransport { cmd ->
            when {
                cmd == "1A90" -> "83 F1 11 7F 1A 12 30"
                cmd.startsWith("AT") -> "OK"
                else -> "NO DATA"
            }
        }
        val elm = Elm327(t) { _, _ -> }
        elm.open()
        val o = Obd(elm)
        o.kline = true
        o.protocol = 5
        assertTrue(o.canTarget)
        o.broadcast()
        assertTrue("untouched header needs no ATSH: ${t.sent}", t.sent.none { it.startsWith("ATSH") })
        o.target(0x11, 0x11)
        val r = o.request("1A90")
        assertEquals(0x11, r.messages.single().header)
        assertEquals(0x12, r.messages.single().nrc)
        o.broadcast()
        assertEquals(listOf("ATSH8111F1", "ATSHC133F1"), t.sent.filter { it.startsWith("ATSH") })
        // Never the functional address, the tester or a CAN id on K-line.
        for (bad in listOf(0x33 to 0x33, 0xF1 to 0xF1, 0x7E0 to 0x7E8, 0x11 to 0x18)) {
            assertTrue(bad.toString(), runCatching { o.target(bad.first, bad.second) }.isFailure)
        }
        o.protocol = 3
        assertFalse(o.canTarget)
        assertTrue(runCatching { o.target(0x11, 0x11) }.isFailure)
        elm.close()
    }
}
