package com.obdscanner.vag

import com.obdscanner.bus.BusSniffer
import com.obdscanner.elm.CanReply
import com.obdscanner.elm.EcuMessage
import com.obdscanner.elm.Obd
import com.obdscanner.tr
import com.obdscanner.util.format
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * VW TP 2.0: how VAG cars until about 2012 (PQ platforms: Golf 5/6, Passat B6/B7, Octavia A5…) reach the modules
 * behind the gateway on the 500k diagnostic CAN; only the engine and gearbox answer ISO-TP there. KWP2000 runs inside.
 * Nothing official is published: the frames are as Jared Wiltshire described them (jazdw.net/tp20, the "I CAN Hack"
 * knowledge base repeats it) and as OVMS (vehicle_poller_vwtp.cpp, MIT) speaks it to a Seat Mii.
 *
 *  - channel setup: to 200 "<module> C0 00 10 00 03 01", the module answers from 200+module "00 D0 <rx> <tx> 01":
 *    it sends on rx (300 — as asked), listens on tx (740…); only one channel at a time on a VW gateway;
 *  - parameters: "A0 0F 8A FF 32 FF" → "A1 <block> <T1> FF <T3> FF" (T: bits 7–6 the unit 0.1/1/10/100 ms, 5–0 the count);
 *  - data: byte 0 = op (high nibble) and sequence (low): 0 more follow, ACK at block end; 1 last, wants an ACK; 2 more
 *    follow; 3 last, no ACK. The first frame of a message carries its length in 2 bytes. ACK "B<next seq>";
 *  - A3 channel test (answered A1), A4 break, A8 disconnect.
 *
 * A plain ELM327 has no TP 2.0: it works here in raw CAN mode (CAF0, no ISO-TP flow control) and this class does the
 * ACKs. The chip only hears the bus while a command waits for its answer, so a reply still on its way when the prompt
 * came is caught by the next command — an ACK if one is due, else A3, which the module answers and which keeps the
 * channel alive. Read only: what goes inside are the reads the rest of the app asks for (the VW diagnostic session
 * 10 89 is opened first, as VCDS does). Every frame is in raw.log.
 */
class Tp20(private val obd: Obd) {
    /** The adapter is in raw CAN mode for TP 2.0 (CAF0, no flow control, own timing): [leave] puts it back. */
    var active = false
        private set
    /** Logical address of the module whose channel is open; null — none. */
    private var dest: Int? = null
    private var txId = 0
    private var rxId = 0
    private var txSeq = 0
    private var rxSeq = 0
    /** Frames the module takes before it wants an ACK (its A1). */
    private var block = 15
    /** ATST for the data exchange: below the module's ACK wait, so that an ACK is in time ([setup]). */
    private var dataSt = ST_DATA
    private var variableDlc = false
    /** What the adapter is set to now (-1 / "" — not known): repeated AT commands are skipped. */
    private var st = -1
    private var sh = -1
    private var filter = ""

    private fun note(text: String) = obd.elm.note("TP2.0: $text")

    /** The adapter reset itself in the middle (LV RESET, ERR94): its settings are the defaults again. */
    class AdapterReset : Exception("adapter reset during TP 2.0")

    /** A module that answered: [vcds] — its address as VCDS numbers it (19 gateway), known from the gateway's list. */
    class Found(val addr: Int, val vcds: Int?, val how: String)

    /**
     * One KWP2000 request to module [addr] ([hex] — "1A9B"), as the rest of the app gets an answer from a CAN module:
     * a message from [BASE] + [addr] or nothing. A lost channel (no ACK, a lost frame, A8 from the module) is opened
     * again once.
     */
    suspend fun request(addr: Int, hex: String): CanReply {
        val header = BASE + addr
        val kwp = hex.takeIf { it.length >= 2 && it.length % 2 == 0 && it.all { c -> c in "0123456789ABCDEFabcdef" } }
            ?.chunked(2)?.map { it.toInt(16) }?.toIntArray()
            ?: return reply(header, null, "TP 2.0: not a request: $hex")
        for (attempt in 0..1) {
            if (!connect(addr)) return reply(header, null, "TP 2.0: no channel to %02X".format(addr))
            val g = transfer(kwp)
            if (g.data != null) return reply(header, g.data, null)
            note("%02X %s: %s".format(addr, hex, g.error))
            if (!g.lost) return reply(header, null, "TP 2.0: ${g.error}")
            close()
        }
        return reply(header, null, "TP 2.0: the channel to %02X was lost twice".format(addr))
    }

    /**
     * Modules on TP 2.0: the gateway's list of installed modules (KWP \$1A 9F, as vag-blocks reads it), each checked
     * by opening its channel; no list — every logical address 01–7F is offered a channel. Neither the gateway nor
     * the engine answering means the car has no TP 2.0: nothing more is sent then.
     */
    suspend fun findModules(progress: (Float, String) -> Unit): List<Found> {
        enter()
        close()
        val out = mutableListOf<Found>()
        progress(0f, tr("TP 2.0: шлюз…", "TP 2.0: gateway…"))
        val gateway = connect(GATEWAY)
        var listed: List<Pair<Int, Int>>? = null
        if (gateway) {
            out += Found(GATEWAY, 0x19, "TP 2.0")
            val g = transfer(intArrayOf(0x1A, 0x9F))
            val d = g.data
            note("gateway 1A 9F: " + (d?.joinToString(" ") { "%02X".format(it) } ?: g.error.orEmpty()))
            listed = if (d != null && d.size > 2 && d[0] == 0x5A) gatewayList(d.copyOfRange(2, d.size)) else null
            note("gateway list: " + (listed?.joinToString(" ") { (n, a) -> "%02X@%02X".format(n, a) } ?: "not read"))
            close()
        }
        val list = listed?.filter { it.second != GATEWAY }
        if (!list.isNullOrEmpty()) {
            for ((i, p) in list.withIndex()) {
                currentCoroutineContext().ensureActive()
                val (vcds, addr) = p
                progress((i + 1f) / (list.size + 1), tr("TP 2.0: блок %02X…", "TP 2.0: module %02X…").format(vcds))
                if (probe(addr)) out += Found(addr, vcds, tr("TP 2.0, в списке шлюза", "TP 2.0, in the gateway's list"))
                else note("%02X (VCDS %02X) is in the gateway's list but opened no channel".format(addr, vcds))
            }
            return out
        }
        // No list: the engine first — if it is silent too, there is no TP 2.0 here.
        val engine = probe(ENGINE)
        if (engine) out += Found(ENGINE, 0x01, "TP 2.0")
        if (!gateway && !engine) {
            note("neither the gateway (1F) nor the engine (01) opened a channel — no TP 2.0 on this car")
            return out
        }
        val rest = (0x02..0x7F).filter { it != GATEWAY }
        for ((i, addr) in rest.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (i % 4 == 0) progress(i.toFloat() / rest.size, tr("TP 2.0: адрес %02X…", "TP 2.0: address %02X…").format(addr))
            if (probe(addr)) out += Found(addr, KNOWN[addr], "TP 2.0")
        }
        return out
    }

    /** Back to the adapter's normal mode: the channel closed, formatting, flow control and timing as before. */
    suspend fun leave() {
        if (!active) return
        withContext(NonCancellable) {
            runCatching { close() }
            active = false
            runCatching { obd.at("ATST32") }
            runCatching { obd.at(obd.adaptiveTiming) }
            if (variableDlc) runCatching { obd.at("ATV0") }
            runCatching { obd.at("ATCFC1") }
            var ok = false
            repeat(4) { if (!ok) ok = runCatching { obd.at("ATCAF1", 2000).isOk }.getOrDefault(false) }
            if (!ok) note("WARNING — ATCAF1 not confirmed")
            runCatching { obd.at("ATAR") }
            st = -1
            sh = -1
            filter = ""
            // The header is a TP id now: the next target or broadcast sets its own.
            obd.resetState()
        }
    }

    /** After the adapter reset itself: nothing of ours is set any more. */
    fun forget() {
        active = false
        dest = null
        st = -1
        sh = -1
        filter = ""
    }

    private suspend fun enter() {
        if (active) return
        // Set first: whatever happens on the way, [leave] undoes it.
        active = true
        obd.at("ATCAF0")
        obd.at("ATCFC0")
        variableDlc = obd.at("ATV1").isOk
        obd.at("ATAT0")
        note(if (variableDlc) "raw CAN mode (CAF0, CFC0, V1)" else "raw CAN mode (CAF0, CFC0); no ATV1 — every frame goes out padded to 8 bytes")
    }

    /** The channel to [addr] with the VW diagnostic session; the open one is closed first (one channel at a time). */
    private suspend fun connect(addr: Int): Boolean {
        if (dest == addr) return true
        enter()
        close()
        if (!setup(addr)) return false
        // A refusal doesn't stop the reads; a dead channel does.
        val s = transfer(intArrayOf(0x10, 0x89))
        note("%02X: 10 89 → %s".format(addr, s.data?.joinToString(" ") { "%02X".format(it) } ?: s.error))
        if (s.data == null && s.lost) {
            close()
            return false
        }
        return true
    }

    /** Opens the channel to [addr] and agrees the timing; false — the module is not there or refused. */
    private suspend fun setup(addr: Int, probing: Boolean = false): Boolean {
        timing(ST_SETUP)
        header(0x200)
        if (probing) listenSetups() else listen(0x200 + addr)
        val d = tx(listOf(addr, 0xC0, 0x00, 0x10, 0x00, 0x03, 0x01), 0x200 + addr).firstOrNull { it.size >= 7 && it[1] in 0xD0..0xDF }
            ?: return false
        if (d[1] != 0xD0) {
            note("%02X refused the channel (%02X)".format(addr, d[1]))
            return false
        }
        rxId = ((d[3] and 0x0F) shl 8) or d[2]
        txId = ((d[5] and 0x0F) shl 8) or d[4]
        txSeq = 0
        rxSeq = 0
        dest = addr
        header(txId)
        listen(rxId)
        val p = tx(listOf(0xA0, 0x0F, 0x8A, 0xFF, 0x32, 0xFF)).firstOrNull { it.size >= 6 && it[0] == 0xA1 }
        if (p == null) {
            note("%02X: channel %03X/%03X, no answer to the parameters".format(addr, txId, rxId))
            close()
            return false
        }
        block = p[1].coerceIn(1, 15)
        val t1 = ms(p[2])
        // ATST counts 4 ms: half the module's ACK wait, 24–100 ms.
        dataSt = (t1 / 2 / 4).toInt().coerceIn(6, 25)
        note("%02X: channel open, tester → %03X, module → %03X, block %d, T1 %s ms, T3 %s ms".format(addr, txId, rxId, block, fmtMs(t1), fmtMs(ms(p[4]))))
        return true
    }

    /** Is a module at [addr]? Its channel is opened and closed at once. */
    private suspend fun probe(addr: Int): Boolean {
        if (!setup(addr, probing = true)) return false
        close()
        return true
    }

    private suspend fun close() {
        val d = dest ?: return
        dest = null
        timing(dataSt)
        header(txId)
        listen(rxId)
        // The module answers A8 too; nothing to wait for if it doesn't.
        runCatching { tx(listOf(0xA8)) }
        note("%02X: channel closed".format(d))
    }

    private class Got(val data: IntArray?, val error: String?, val lost: Boolean = false)

    /** Sends one KWP message on the open channel and collects the answer ("7F xx 78" — busy — is waited out). */
    private suspend fun transfer(kwp: IntArray): Got {
        timing(dataSt)
        val chunks = (listOf(kwp.size shr 8, kwp.size and 0xFF) + kwp.toList()).chunked(7)
        val inbox = mutableListOf<IntArray>()
        for ((i, c) in chunks.withIndex()) {
            val last = i == chunks.lastIndex
            val op = when {
                last -> 0x10
                (i + 1) % block == 0 -> 0x00
                else -> 0x20
            }
            val got = tx(listOf(op or txSeq) + c)
            txSeq = (txSeq + 1) and 0x0F
            if (op == 0x00 && got.none { isAck(it) }) return Got(null, "no ACK in the middle of the request", lost = true)
            inbox += got
        }
        return receive(inbox)
    }

    private suspend fun receive(first: List<IntArray>): Got {
        var inbox = first
        val msg = mutableListOf<Int>()
        var length = -1
        var acked = false
        var quiet = 0
        var busy = 0
        var pong = false
        while (true) {
            var ack = false
            var progress = false
            var done: IntArray? = null
            for (f in inbox) {
                if (f.isEmpty()) continue
                val b = f[0]
                val op = b and 0xF0
                when {
                    b == 0xA8 -> return Got(null, "the module closed the channel (A8)", lost = true)
                    b == 0xA4 -> return Got(null, "the module broke the transfer (A4)", lost = true)
                    b == 0xA3 -> pong = true
                    op == 0xA0 -> Unit
                    op == 0xB0 || op == 0x90 -> {
                        acked = true
                        progress = true
                    }
                    op <= 0x30 && done == null -> {
                        val seq = b and 0x0F
                        if (seq != rxSeq) {
                            // The same frame again: our ACK came late and the module repeated it.
                            if (seq == ((rxSeq - 1) and 0x0F)) {
                                if (op and 0x20 == 0) ack = true
                                continue
                            }
                            return Got(null, "frame %X where %X was due — a frame was lost".format(seq, rxSeq), lost = true)
                        }
                        rxSeq = (rxSeq + 1) and 0x0F
                        progress = true
                        acked = true
                        val body = f.drop(1)
                        if (length < 0) {
                            if (body.size < 2) return Got(null, "first frame without a length", lost = true)
                            // OVMS saw 80 in the high byte with "7F xx 78": 12 bits of length.
                            length = ((body[0] and 0x0F) shl 8) or body[1]
                            msg += body.drop(2)
                        } else msg += body
                        if (op and 0x20 == 0) ack = true
                        if (op and 0x10 != 0) {
                            val m = msg.take(length).toIntArray()
                            msg.clear()
                            length = -1
                            if (m.size >= 3 && m[0] == 0x7F && m[2] == 0x78) {
                                if (++busy > MAX_BUSY) return Got(null, "busy (7F %02X 78) for too long".format(m[1]))
                            } else done = m
                        }
                    }
                }
            }
            if (done != null) {
                if (ack) tx(listOf(0xB0 or rxSeq))
                if (pong) tx(PARAMS)
                return Got(done, null)
            }
            if (progress) quiet = 0 else if (++quiet > MAX_QUIET) {
                return if (acked) Got(null, "no answer") else Got(null, "no ACK from the module", lost = true)
            }
            inbox = when {
                ack -> tx(listOf(0xB0 or rxSeq))
                pong -> tx(PARAMS).also { pong = false }
                else -> tx(listOf(0xA3))
            }
        }
    }

    /** Sends one frame; what came back from [from] (the module's id by default). */
    private suspend fun tx(bytes: List<Int>, from: Int = rxId): List<IntArray> {
        val r = obd.elm.send(bytes.joinToString("") { "%02X".format(it) }, 1500)
        if (r.adapterReset) {
            forget()
            throw AdapterReset()
        }
        return BusSniffer.parse(r.lines).filter { it.id == from }.map { it.data }
    }

    private suspend fun timing(v: Int) {
        if (st == v) return
        obd.at("ATST%02X".format(v))
        st = v
    }

    private suspend fun header(id: Int) {
        if (sh == id) return
        obd.at("ATSH%03X".format(id))
        sh = id
    }

    /** Receive filter for one id; a clone without ATCRA gets filter and mask. */
    private suspend fun listen(id: Int) {
        val f = "%03X".format(id)
        if (filter == f) return
        if (obd.at("ATCRA$f").isUnknown) {
            obd.at("ATCF$f")
            obd.at("ATCM7FF")
        }
        filter = f
    }

    /** Every channel setup answer (200–2FF) while probing. */
    private suspend fun listenSetups() {
        if (filter == "2XX") return
        if (obd.at("ATCRA2XX").isUnknown) {
            obd.at("ATCF200")
            obd.at("ATCM700")
        }
        filter = "2XX"
    }

    private fun isAck(f: IntArray) = f.isNotEmpty() && (f[0] and 0xF0).let { it == 0xB0 || it == 0x90 }

    private fun reply(header: Int, data: IntArray?, error: String?) =
        CanReply(listOfNotNull(data?.let { EcuMessage(header, it) }), listOfNotNull(error), "", false)

    companion object {
        /** A TP 2.0 module is addressed in the app as BASE + its logical address (CAN ids end at 7FF). */
        const val BASE = 0x1000
        const val GATEWAY = 0x1F
        const val ENGINE = 0x01
        /** ATST (4 ms steps) while setting a channel up: 100 ms. */
        private const val ST_SETUP = 0x19
        /** ATST for data until the module tells its ACK wait: 48 ms. */
        private const val ST_DATA = 0x0C
        /** A3 in a row with nothing new before giving up on an answer. */
        private const val MAX_QUIET = 6
        private const val MAX_BUSY = 30
        /** Our side of the parameters, also the answer to the module's A3 (as OVMS answers it). */
        private val PARAMS = listOf(0xA1, 0x0F, 0x8A, 0xFF, 0x32, 0xFF)

        /** Logical addresses known from more than one source: 01 engine (jazdw.net, I CAN Hack), 1F gateway (OVMS, vag-blocks). */
        val KNOWN = mapOf(ENGINE to 0x01, GATEWAY to 0x19)

        fun isTp(req: Int) = req in BASE + 1..BASE + 0xFF

        /** "TP1F" for a TP 2.0 module, the CAN id for the rest. */
        fun label(req: Int) = if (isTp(req)) "TP%02X".format(req - BASE) else "%03X".format(req)

        /** Unit (bits 7–6: 0.1, 1, 10, 100 ms) times count (bits 5–0). */
        fun ms(b: Int): Double = (b and 0x3F) * when (b shr 6) {
            0 -> 0.1
            1 -> 1.0
            2 -> 10.0
            else -> 100.0
        }

        private fun fmtMs(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        /**
         * The gateway's 5A 9F answer (after 5A 9F) → (VCDS address, logical address) of the modules marked present.
         * As vag-blocks reads it: length-prefixed parts, the first one 4-byte records (VCDS address, logical address,
         * ?, status: bit 0 present); logical address 13 is skipped — modules 00 and 04 report it and aren't there.
         * Not checked on a car here: [findModules] opens a channel to each before listing it.
         */
        fun gatewayList(d: IntArray): List<Pair<Int, Int>>? {
            val table = d.firstOrNull()?.takeIf { it in 2..d.size && (it - 1) % 4 == 0 }?.let { d.copyOfRange(1, it) }
                ?: d.takeIf { it.size % 4 == 0 } ?: return null
            return table.toList().chunked(4).filter { it.size == 4 && it[3] and 0x01 == 1 && it[1] in 0x01..0x7F && it[1] != 0x13 }
                .map { it[0] to it[1] }.distinctBy { it.second }
        }
    }
}
