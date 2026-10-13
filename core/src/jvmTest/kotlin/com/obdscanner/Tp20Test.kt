package com.obdscanner

import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.transport.Transport
import com.obdscanner.vag.Tp20
import com.obdscanner.vag.VagBlocks
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VW TP 2.0 through a plain ELM327 in raw CAN mode, against modules emulated after jazdw.net/tp20: channel setup on
 * 200, parameters, ACKs, answers longer than a block, "busy" (7F 78), an answer that comes after the prompt, a lost
 * frame, the gateway's module list. No car here speaks TP 2.0: what a real one does differently shows in raw.log.
 */
class Tp20Test {
    /** One emulated module: [listen] — its id for the tester's frames; [answers] — KWP request hex → reply. */
    private class Module(val addr: Int, val listen: Int, val answers: Map<String, List<Int>>)

    /**
     * An ELM327 with modules behind it. Frames go out on the header (ATSH); what comes back passes the receive filter
     * (ATCRA, with X). [blockSize] — frames a module sends before it wants an ACK; [late] — requests answered only on
     * the tester's next frame (after the prompt); [busy] — requests first answered 7F xx 78; [dropOnce] — a request
     * whose answer loses its second frame the first time.
     */
    private class FakeElm(
        val modules: List<Module>,
        val blockSize: Int = 15,
        val late: Set<String> = emptySet(),
        val busy: Set<String> = emptySet(),
        var dropOnce: String? = null,
    ) : Transport {
        override val name = "fake"
        val sent = mutableListOf<String>()
        private val queue = Channel<Int>(Channel.UNLIMITED)
        private val cmd = StringBuilder()
        private var caf = true
        private var sh = 0x7DF
        private var cra = ""
        private var open: Module? = null
        private var rxSeq = 0
        private var txSeq = 0
        private val incoming = mutableListOf<Int>()
        private var length = -1
        /** Frames of the answer not sent yet (after a block's ACK) and a whole answer held for the next command. */
        private val pending = ArrayDeque<List<Int>>()
        private var held: List<Int>? = null
        private val busyDone = mutableSetOf<String>()

        override suspend fun read(buf: ByteArray): Int {
            val first = queue.receive()
            if (first < 0) return -1
            buf[0] = first.toByte()
            var n = 1
            while (n < buf.size) {
                val next = queue.tryReceive().getOrNull() ?: break
                buf[n++] = next.toByte()
            }
            return n
        }

        override suspend fun write(data: ByteArray) {
            for (b in data) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '\r') {
                    val line = cmd.toString().trim().uppercase()
                    cmd.clear()
                    if (line.isEmpty()) continue
                    sent += line
                    val out = if (line.startsWith("AT")) at(line) else frame(line)
                    for (ch in out + "\r\r>") queue.trySend(ch.code)
                } else cmd.append(c)
            }
        }

        override suspend fun open() {}
        override fun close() { queue.trySend(-1) }

        /** The adapter is in raw CAN mode: frames go to the modules. */
        val raw get() = !caf

        fun at(c: String): String {
            when {
                c.startsWith("ATCAF") -> caf = c.endsWith("1")
                c.startsWith("ATSH") -> sh = c.removePrefix("ATSH").toInt(16)
                c.startsWith("ATCRA") -> cra = c.removePrefix("ATCRA")
                c == "ATAR" -> cra = ""
            }
            return "OK"
        }

        private fun passes(id: Int) = cra.isEmpty() || "%03X".format(id).zip(cra).all { (a, f) -> f == 'X' || a == f }

        fun frame(hex: String): String {
            if (caf) return "NO DATA"
            val d = hex.chunked(2).map { it.toInt(16) }
            val out = mutableListOf<Pair<Int, List<Int>>>()
            held?.let { h ->
                held = null
                out += send(h)
            }
            val m = open
            if (sh == 0x200) {
                val mod = modules.firstOrNull { it.addr == d[0] }
                if (mod != null && d[1] == 0xC0) {
                    open = mod
                    rxSeq = 0
                    txSeq = 0
                    out += (0x200 + mod.addr) to listOf(0x00, 0xD0, 0x00, 0x03, mod.listen and 0xFF, mod.listen shr 8, 0x01)
                }
            } else if (m != null && sh == m.listen) {
                val b = d[0]
                when {
                    b == 0xA0 || b == 0xA3 -> out += 0x300 to listOf(0xA1, blockSize, 0x8A, 0xFF, 0x4A, 0xFF)
                    b == 0xA8 -> { out += 0x300 to listOf(0xA8); open = null }
                    b and 0xF0 == 0xB0 -> out += nextBlock()
                    b and 0xF0 <= 0x30 -> {
                        check(b and 0x0F == rxSeq) { "tester sent seq ${b and 0x0F}, module expected $rxSeq" }
                        rxSeq = (rxSeq + 1) and 0x0F
                        val body = d.drop(1)
                        if (length < 0) { length = (body[0] shl 8) or body[1]; incoming += body.drop(2) } else incoming += body
                        if (b and 0x20 == 0) out += 0x300 to listOf(0xB0 or rxSeq)
                        if (b and 0x10 != 0) {
                            val req = incoming.take(length).joinToString("") { "%02X".format(it) }
                            incoming.clear()
                            length = -1
                            // A service the module has, asked something it doesn't know: out of range; else not supported.
                            val svc = req.take(2).toInt(16)
                            val answer = m.answers[req] ?: listOf(0x7F, svc, if (m.answers.keys.any { it.startsWith(req.take(2)) }) 0x31 else 0x11)
                            when {
                                req in busy && busyDone.add(req) -> {
                                    out += send(listOf(0x7F, req.take(2).toInt(16), 0x78))
                                    held = answer
                                }
                                req in late -> held = answer
                                else -> out += send(answer, drop = req == dropOnce)
                            }
                            if (req == dropOnce) dropOnce = null
                        }
                    }
                }
            }
            val lines = out.filter { passes(it.first) }.map { (id, bytes) -> "%03X ".format(id) + bytes.joinToString(" ") { "%02X".format(it) } }
            return if (lines.isEmpty()) "NO DATA" else lines.joinToString("\r")
        }

        /** The frames of [msg] up to the first one that wants an ACK; the rest wait in [pending]. */
        private fun send(msg: List<Int>, drop: Boolean = false): List<Pair<Int, List<Int>>> {
            val chunks = (listOf(msg.size shr 8, msg.size and 0xFF) + msg).chunked(7)
            pending.clear()
            for ((i, c) in chunks.withIndex()) {
                val last = i == chunks.lastIndex
                val op = when { last -> 0x10; (i + 1) % blockSize == 0 -> 0x00; else -> 0x20 }
                pending += listOf(op) + c
            }
            return nextBlock(drop)
        }

        private fun nextBlock(drop: Boolean = false): List<Pair<Int, List<Int>>> {
            val out = mutableListOf<Pair<Int, List<Int>>>()
            var n = 0
            while (pending.isNotEmpty()) {
                val f = pending.removeFirst()
                val frame = listOf(f[0] or txSeq) + f.drop(1)
                txSeq = (txSeq + 1) and 0x0F
                if (!(drop && n == 1)) out += 0x300 to frame
                n++
                if (f[0] and 0x20 == 0) break
            }
            return out
        }
    }

    private val ident = listOf(0x5A, 0x9B) + "1K0907115AA ".map { it.code } + listOf(0x00, 0x03, 0x10, 0x00, 0x22) + "MED9.5.10   G   0050".map { it.code }
    private fun engine() = Module(0x01, 0x740, mapOf(
        "1089" to listOf(0x50, 0x89),
        "1A9B" to ident,
        "2101" to listOf(0x61, 0x01, 0x01, 0xC8, 0x14, 0x05, 0x0A, 0xC5, 0x25, 0x0A, 0x3C, 0x06, 0xC8, 0x48),
        "1802FF00" to listOf(0x58, 0x01, 0x41, 0x2C, 0x24),
    ))

    private fun run(fake: FakeElm, block: suspend (Obd) -> Unit) = runBlocking {
        val elm = Elm327(fake) { _, _ -> }
        elm.open()
        val o = Obd(elm)
        o.protocol = 6
        try { block(o) } finally { elm.close() }
    }

    @Test
    fun answerLongerThanABlockIsAcknowledgedAndAssembled() {
        val fake = FakeElm(listOf(engine()), blockSize = 4)
        run(fake) { o ->
            o.target(Tp20.BASE + 1, Tp20.BASE + 1)
            val r = o.request("1A9B")
            assertTrue(r.errors.toString(), r.errors.isEmpty())
            assertArrayEquals(ident.toIntArray(), r.from(Tp20.BASE + 1).single().data)
            o.broadcast()
        }
        val s = fake.sent
        // Channel to 01, parameters, the diagnostic session, the request; the answer took ACKs mid-way and at the end.
        assertTrue(s.toString(), "01C00010000301" in s && "A00F8AFF32FF" in s)
        assertTrue(s.toString(), s.any { it.startsWith("B") })
        // Back to normal: channel closed, formatting and flow control as before.
        val after = s.drop(s.indexOf("A8"))
        assertTrue(after.toString(), listOf("ATCAF1", "ATCFC1", "ATAR").all { it in after })
    }

    @Test
    fun busyAndLateAnswersAreWaitedOut() {
        val fake = FakeElm(listOf(engine()), busy = setOf("1A9B"), late = setOf("2101"))
        run(fake) { o ->
            o.target(Tp20.BASE + 1, Tp20.BASE + 1)
            assertArrayEquals(ident.toIntArray(), o.request("1A9B").messages.single().data)
            val g = o.request("2101").messages.single().data
            assertEquals(0x61, g[0])
            // An answer after the prompt is caught by a channel test (A3).
            assertTrue(fake.sent.toString(), "A3" in fake.sent)
        }
    }

    @Test
    fun lostFrameReopensTheChannelOnce() {
        val fake = FakeElm(listOf(engine()), dropOnce = "1A9B")
        run(fake) { o ->
            o.target(Tp20.BASE + 1, Tp20.BASE + 1)
            assertArrayEquals(ident.toIntArray(), o.request("1A9B").messages.single().data)
        }
        assertEquals(fake.sent.toString(), 2, fake.sent.count { it == "01C00010000301" })
    }

    @Test
    fun refusalAndSilence() {
        val fake = FakeElm(listOf(engine()))
        run(fake) { o ->
            o.target(Tp20.BASE + 1, Tp20.BASE + 1)
            // A group the engine hasn't: the refusal comes through as the module sent it.
            val m = o.request("2199").messages.single()
            assertTrue(m.isNegative)
            assertEquals(0x31, m.nrc)
            assertEquals(0x11, o.request("22F190").messages.single().nrc)
            // No module at 03: no channel, no answer, nothing thrown.
            o.target(Tp20.BASE + 3, Tp20.BASE + 3)
            assertTrue(o.request("1A9B").noData)
        }
    }

    @Test
    fun gatewayListsTheModules() {
        // 5A 9F, then a part: length 9 = 2 records (VCDS 01 at 01 present, VCDS 17 at 07 present), end FF.
        val gw = Module(0x1F, 0x741, mapOf("1089" to listOf(0x50, 0x89), "1A9F" to listOf(0x5A, 0x9F, 0x09, 0x01, 0x01, 0x00, 0x03, 0x17, 0x07, 0x00, 0x01, 0xFF)))
        val dash = Module(0x07, 0x742, mapOf("1089" to listOf(0x50, 0x89)))
        val fake = FakeElm(listOf(gw, engine(), dash))
        run(fake) { o ->
            val found = o.tp.findModules { _, _ -> }
            assertEquals(listOf(0x1F to 0x19, 0x01 to 0x01, 0x07 to 0x17), found.map { it.addr to it.vcds })
            o.broadcast()
        }
        assertTrue(VagBlocks.moduleName(0x19, 0x1F).startsWith("19 "))
        assertTrue(VagBlocks.moduleName(null, 0x2C).contains("2C"))
    }

    @Test
    fun noTpCarIsLeftAloneAfterTwoQuestions() {
        val fake = FakeElm(emptyList())
        run(fake) { o ->
            assertTrue(o.tp.findModules { _, _ -> }.isEmpty())
            o.broadcast()
        }
        assertEquals(fake.sent.toString(), 2, fake.sent.count { it.length == 14 && it.substring(2, 4) == "C0" })
    }

    @Test
    fun measuringBlockFields() {
        val f = VagBlocks.decode(intArrayOf(0x01, 0xC8, 0x14, 0x05, 0x0A, 0xC5, 0x25, 0x0A, 0x3C, 0x06, 0xC8, 0x48))!!
        assertEquals(800.0, f[0].value!!, 1e-9)
        assertEquals(97.0, f[1].value!!, 1e-9)
        // Type 37: no formula two sources agree on — shown as it came.
        assertNull(f[2].value)
        assertTrue(f[2].text, f[2].text.contains("37") && f[2].text.contains("0A 3C"))
        assertEquals(14.4, f[3].value!!, 1e-9)
        assertNull(VagBlocks.decode(intArrayOf(1, 2)))
    }

    /** The recorded log for everything but TP 2.0; the emulated modules while the adapter is in raw CAN mode. */
    private class WithTp(val log: ReplayLog, val clock: VirtualClock, val tp: FakeElm) : Transport {
        override val name = "replay+tp"
        private val queue = Channel<Int>(Channel.UNLIMITED)
        private val cmd = StringBuilder()
        private val state = ElmState()

        override suspend fun read(buf: ByteArray): Int {
            val first = queue.receive()
            if (first < 0) return -1
            buf[0] = first.toByte()
            var n = 1
            while (n < buf.size) {
                val next = queue.tryReceive().getOrNull() ?: break
                buf[n++] = next.toByte()
            }
            return n
        }

        override suspend fun write(data: ByteArray) {
            for (b in data) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '\r') {
                    val line = cmd.toString().trim().uppercase()
                    cmd.clear()
                    if (line.isEmpty()) continue
                    state.apply(line)
                    val text = when {
                        line.startsWith("AT") -> { tp.at(line); log.answer(state.key(line))?.first ?: "OK" }
                        tp.raw -> tp.frame(line)
                        else -> log.answer(state.key(line))?.first ?: "NO DATA"
                    }
                    clock.now += 50
                    for (ch in text + "\r\r>") queue.trySend(ch.code)
                } else if (c != '\n') cmd.append(c)
            }
        }

        override suspend fun open() {}
        override fun close() { queue.trySend(-1) }
    }

    /**
     * A Golf 5 by its VIN (WVW…1K): only the engine answers ISO-TP (7E0), so the module search asks TP 2.0 — the gateway
     * lists the engine and the dash — identifies them (1A 9B), reads their codes (KWP \$18, VAG numbers), and the
     * adapter is back to normal OBD after it.
     */
    @Test
    fun golf5FindsItsModulesBehindTheGateway() {
        val log = """
            12:00:00.000 > ATSP6
            12:00:00.010 < OK
            12:00:00.020 > 0100
            12:00:00.090 < 7E8 06 41 00 BE 3F A8 13
            12:00:00.100 > ATDPN
            12:00:00.110 < 6
            12:00:00.120 > ATSH7DF
            12:00:00.130 < OK
            12:00:00.140 > 0100
            12:00:00.200 < 7E8 06 41 00 BE 3F A8 13
            12:00:00.210 > 0902
            12:00:00.300 < 7E8 10 14 49 02 01 57 56 57 | 7E8 21 5A 5A 5A 31 4B 5A 36 | 7E8 22 57 30 30 30 30 30 31
            12:00:01.000 > ATSH7E0
            12:00:01.010 < OK
            12:00:01.020 > 3E00
            12:00:01.100 < 7E8 02 7E 00
        """.trimIndent()
        val gw = Module(0x1F, 0x741, mapOf("1089" to listOf(0x50, 0x89), "1A9F" to listOf(0x5A, 0x9F, 0x09, 0x01, 0x01, 0x00, 0x03, 0x17, 0x07, 0x00, 0x01, 0xFF),
            "1A9B" to listOf(0x5A, 0x9B) + "1K0907530F  Gateway KCAN".map { it.code }, "1802FF00" to listOf(0x58, 0x00)))
        val dash = Module(0x07, 0x742, mapOf("1089" to listOf(0x50, 0x89), "1A9B" to listOf(0x5A, 0x9B) + "1K0920852D  KOMBI".map { it.code },
            "1802FF00" to listOf(0x58, 0x00)))
        val fake = FakeElm(listOf(gw, engine(), dash))
        CarDbTest.load()
        val r = runBlocking { replayRun(ReplayLog.parse(log), MapStore(), cycles = 1, transport = { l, c -> WithTp(l, c, fake) }) }
        assertNull(r.error)
        val mods = r.link.scan.value.modules
        assertEquals(mods.toString(), listOf(0x7E0, Tp20.BASE + 0x1F, Tp20.BASE + 0x01, Tp20.BASE + 0x07), mods.map { it.req })
        assertEquals(listOf("TP1F", "TP01", "TP07"), mods.drop(1).map { it.id })
        // The engine's code over TP 2.0, numbered the VAG way (16684 = P0300).
        val engineCodes = r.link.vehicle.value.gmDtcs.single { it.module.req == Tp20.BASE + 1 }.codes
        assertEquals(listOf(16684), engineCodes.map { it.vag })
        assertTrue(r.reports.toString(), r.reports.any { (_, body) -> "MED9.5.10" in body })
        // After the TP 2.0 exchange: formatted CAN again, and normal OBD answers.
        val sent = r.requests.map { it.uppercase() }
        val lastFrame = sent.indexOfLast { it == "A8" }
        assertTrue(sent.toString(), lastFrame > 0 && "ATCAF1" in sent.drop(lastFrame))
        assertTrue(sent.drop(lastFrame).toString(), "0100" in sent.drop(lastFrame))
    }

    @Test
    fun gatewayListShapes() {
        assertEquals(listOf(0x01 to 0x01), Tp20.gatewayList(intArrayOf(0x05, 0x01, 0x01, 0x00, 0x03, 0xFF)))
        // Without the length byte: whole records; 13 and absent ones skipped.
        assertEquals(listOf(0x02 to 0x02), Tp20.gatewayList(intArrayOf(0x02, 0x02, 0x00, 0x01, 0x00, 0x13, 0x00, 0x01, 0x03, 0x03, 0x00, 0x00)))
        assertNull(Tp20.gatewayList(intArrayOf(0x01, 0x02, 0x03)))
    }
}
