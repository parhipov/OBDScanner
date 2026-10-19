package com.obdscanner.gm

import com.obdscanner.util.format
import com.obdscanner.bus.BusSniffer
import com.obdscanner.elm.CanReply
import com.obdscanner.elm.EcuMessage
import com.obdscanner.elm.Obd
import com.obdscanner.tr
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Read-only exploration of modules on HS-CAN: $1A ReadDataByIdentifier (GMLAN, KWP),
 * $22 ReadDataByParameterIdentifier and $21 ReadDataByLocalIdentifier (KWP on CAN — Toyota's
 * data lists). Nothing here writes, resets or changes sessions.
 */
class GmScanner(private val obd: Obd, private val note: (String) -> Unit) {

    suspend fun probeModules(
        all: List<Pair<Int, Int>>,
        probes: List<String>,
        name: (Int) -> String,
        onProgress: (Float, String) -> Unit,
    ): List<GmModule> {
        val found = mutableListOf<GmModule>()
        for ((i, pair) in all.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (req, resp) = pair
            onProgress(i.toFloat() / all.size, tr("Опрос %03X…", "Probing %03X…").format(req))
            obd.target(req, resp)
            for (p in probes) {
                val r = obd.request(p, timeoutMs = 400, expectOne = true)
                // "7F 1A 78" is only "wait": the ECM sends it and then the real answer in the same read.
                val msg = answer(r, resp) ?: r.from(resp).firstOrNull()
                if (msg != null) {
                    val what = if (msg.isNegative) tr("$p → отказ %02X", "$p → NRC %02X").format(msg.nrc) else "$p → OK"
                    found += GmModule(req, resp, name(req), what)
                    note("GM: module %03X answered: %s".format(req, what))
                    break
                }
            }
        }
        obd.broadcast()
        return found
    }

    /**
     * GMLAN functional request to every node: CAN id 101, extended address FE (all nodes), \$1A B0. Each module that
     * hears it — behind the gateway too, where the probe one id at a time finds nothing — answers from its own reply
     * id 6xx with its diagnostic address ("641 03 5A B0 40"); its request id is the reply's − 400. Read only. As for
     * \$A9 the adapter works with raw frames here (the request carries the FE byte, the replies come from many ids)
     * and is put back after. A clone without "X" in ATCRA can't let all 6xx through: nothing is sent then.
     */
    suspend fun functionalModules(name: (Int) -> String): List<GmModule> {
        obd.forgetRouting()
        val raw = try {
            obd.at("ATSH101")
            if (obd.at("ATCRA6XX").isUnknown) {
                note("GM: functional \$1A B0 skipped — the adapter has no ATCRA with X")
                return emptyList()
            }
            obd.at("ATCAF0")
            obd.at("ATAT0")
            obd.at("ATSTFF")
            obd.elm.send("FE021AB000000000", 5000)
        } finally {
            withContext(NonCancellable) {
                runCatching { obd.at("ATST32") }
                runCatching { obd.at(obd.adaptiveTiming) }
                var ok = false
                repeat(4) { if (!ok) ok = runCatching { obd.at("ATCAF1", 2000).isOk }.getOrDefault(false) }
                if (!ok) note("GM: WARNING — ATCAF1 not confirmed")
                runCatching { obd.at("ATAR") }
                // The header is 101 now: the next target or broadcast sets its own.
                obd.resetState()
            }
        }
        val found = BusSniffer.parse(raw.lines)
            .filter { f -> f.id in 0x640..0x6FF && f.data.size >= 4 && f.data[0] == 0x03 && f.data[1] == 0x5A && f.data[2] == DIAG_ADDRESS }
            .distinctBy { it.id }
            .map { f ->
                val req = f.id - 0x400
                GmModule(req, f.id, name(req), tr("1A B0 всем блокам → %02X", "1A B0 to all modules → %02X").format(f.data[3]), f.data[3])
            }
        note("GM: functional \$1A B0 — %d answered: %s".format(found.size, found.joinToString(" ") { "%03X=%02X".format(it.resp, it.diag ?: 0) }))
        return found
    }

    /**
     * Reads the identification \$1A DIDs of a module (name, programming date, software and part
     * numbers) — what's needed to look up bulletins and calibration updates.
     */
    suspend fun identify(module: GmModule, onHit: (ScanHit) -> Unit) {
        obd.target(module.req, module.resp)
        // The module's own diagnostic address names it on any platform (a CAN id means different modules on
        // different cars). Asked apart from the list: whatever it gets doesn't change how the list goes.
        read(module, "1A", DIAG_ADDRESS)?.first?.let { onHit(ScanHit(module.req, module.resp, "1A", DIAG_ADDRESS, it)) }
        var silent = 0
        for (did in IDENTITY) {
            currentCoroutineContext().ensureActive()
            val r = read(module, "1A", did)
            if (r == null) {
                if (++silent >= 3) return
                continue
            }
            silent = 0
            if (r.first == null && r.second == 0x11) return
            r.first?.let { onHit(ScanHit(module.req, module.resp, "1A", did, it)) }
        }
    }

    /** Detects whether this clone supports the response-count digit (big speedup for scans). */
    suspend fun detectCountDigit(module: GmModule) {
        obd.target(module.req, module.resp)
        obd.countDigit = true
        val fast = obd.request("1A90", timeoutMs = 800, expectOne = true)
        if (fast.errors.any { it.contains("?") } || fast.noData) obd.countDigit = false
        note("GM: response-count digit ${if (obd.countDigit) "supported" else "NOT supported"}")
    }

    /**
     * Scans [range] with service [service] ("1A", "21" or "22"). Stops the module early if the
     * service itself is rejected (NRC 11) many times in a row.
     */
    suspend fun scan(
        module: GmModule,
        service: String,
        range: IntRange,
        onHit: (ScanHit) -> Unit,
        onProgress: (Float, String) -> Unit,
    ) {
        obd.target(module.req, module.resp)
        val total = range.last - range.first + 1
        var serviceRejected = 0
        var silent = 0
        for ((n, did) in range.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (n % 8 == 0) onProgress(n.toFloat() / total, tr("%s %s %s: %d из %d", "%s %s %s: %d of %d").format(module.id, service, fmtDid(service, did), n, total))
            val r = read(module, service, did) ?: run {
                if (++silent >= 40) {
                    note("GM: %s service %s — 40 silent requests in a row, giving up".format(module.id, service))
                    return
                }
                null
            }
            if (r != null) silent = 0
            when {
                r == null -> Unit
                r.first == null && r.second == 0x11 -> {
                    if (++serviceRejected >= 5) {
                        note("GM: %s rejects service %s (NRC 11), stop".format(module.id, service))
                        return
                    }
                }
                r.first != null -> onHit(ScanHit(module.req, module.resp, service, did, r.first!!))
            }
        }
        onProgress(1f, tr("Готово", "Done"))
    }

    /**
     * Returns (data after the DID echo, NRC) or null when nobody answered. [option] — one more request byte some makes
     * want after the identifier (Toyota's KWP modules: 1A 88 01), echoed back and dropped from the data like it.
     */
    suspend fun read(module: GmModule, service: String, did: Int, option: Int? = null): Pair<IntArray?, Int>? {
        val req = service + fmtDid(service, did) + (option?.let { "%02X".format(it) } ?: "")
        // K-line: 10.4 kbit/s and P2 up to 50 ms per message — a long answer takes a few hundred ms.
        var reply: CanReply = obd.request(req, timeoutMs = if (obd.kline) 1500 else 600, expectOne = true)
        var msg = answer(reply, module.resp, service, did)
        // Truncated multi-frame with the count digit → retry without it.
        if (obd.countDigit && reply.errors.any { it.startsWith("ISO-TP") }) {
            reply = obd.request(req, timeoutMs = 1200)
            msg = answer(reply, module.resp, service, did)
        }
        // Only "7F xx 78" (response pending) arrived — the ECM on the car does this for $1A B4:
        // the real answer comes after the prompt and is lost. Ask again with a longer wait.
        if (msg == null && reply.from(module.resp).any { it.nrc == 0x78 }) {
            obd.at("ATAT0")
            obd.at("ATSTFF")
            try {
                reply = obd.request(req, timeoutMs = 3000)
                msg = answer(reply, module.resp, service, did)
            } finally {
                withContext(NonCancellable) {
                    runCatching { obd.at("ATST32") }
                    runCatching { obd.at(obd.adaptiveTiming) }
                }
            }
        }
        msg ?: return null
        if (msg.isNegative) return null to msg.nrc
        val from = (if (oneByteId(service)) 2 else 3) + (if (option != null && msg.data.getOrNull(2) == option) 1 else 0)
        return msg.data.copyOfRange(minOf(from, msg.data.size), msg.data.size) to 0
    }

    /** First real answer: "7F xx 78" only means "wait", the reply may follow in the same read. */
    private fun answer(reply: CanReply, resp: Int) = reply.from(resp).firstOrNull { it.nrc != 0x78 }

    /**
     * The answer to this very request: a positive reply must echo the DID, a refusal the service.
     * On a slow link a late reply to the previous request arrives instead (Ford 2017: 22 1172 got
     * 62 11 6B …) — taking it would put one value under another's name, so it counts as silence.
     */
    private fun answer(reply: CanReply, resp: Int, service: String, did: Int): EcuMessage? {
        val svc = service.toInt(16)
        val id = if (oneByteId(service)) intArrayOf(did) else intArrayOf(did shr 8, did and 0xFF)
        return reply.from(resp).firstOrNull { m ->
            if (m.isNegative) m.nrc != 0x78 && m.data.getOrNull(1) == svc
            else m.service == svc + 0x40 && m.data.size >= id.size + 1 && id.indices.all { m.data[it + 1] == id[it] }
        }
    }

    suspend fun readRaw(req: Int, resp: Int, service: String, did: Int): IntArray? {
        obd.target(req, resp)
        return read(GmModule(req, resp, "", ""), service, did)?.first
    }

    private fun fmtDid(service: String, did: Int) = if (oneByteId(service)) "%02X".format(did) else "%04X".format(did)

    companion object {
        /** \$1A and \$21 take a one-byte identifier, \$22 a two-byte one. */
        fun oneByteId(service: String) = service == "1A" || service == "21"

        val IDENTITY = listOf(0x97, 0x99, 0xB4, 0xC0) + (0xC1..0xC6) + listOf(0xCB, 0xCC)

        /** GMLAN \$1A B0: the module's diagnostic address, one byte (GMW3110). */
        const val DIAG_ADDRESS = 0xB0
    }
}
