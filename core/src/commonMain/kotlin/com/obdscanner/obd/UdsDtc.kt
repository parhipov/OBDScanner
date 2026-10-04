package com.obdscanner.obd

import com.obdscanner.util.format
import com.obdscanner.elm.CanReply
import com.obdscanner.elm.Obd
import com.obdscanner.gm.DtcScheme
import com.obdscanner.gm.GmDtc
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import com.obdscanner.tr
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Full DTC memory of one module, read only, by the ways its dialect names (com.obdscanner.car.Dialect.dtc),
 * tried in order until one answers: UDS \$19 02 (reportDTCByStatusMask), KWP2000 \$18 02 FF00
 * (readDTCByStatus, all groups), KWP2000 \$13 (readDiagnosticTroubleCodes). Nothing is cleared.
 */
class UdsDtcReader(private val obd: Obd, private val note: (String) -> Unit) {

    suspend fun read(module: GmModule, chain: List<String> = DEFAULT, vagNumbers: Boolean = false): GmDtcResult {
        obd.target(module.req, module.resp)
        val tried = mutableListOf<Pair<String, Int?>>()
        var result: GmDtcResult? = null
        for (way in chain) {
            val (data, nrc) = when (way) {
                "uds19" -> request(module, "1902FF").let { r ->
                    // Some ECUs refuse mask bits they don't support instead of masking them.
                    if (r.second == 0x31) request(module, "19020D") else r
                }
                "kwp18" -> request(module, "1802FF00")
                "kwp13" -> request(module, "13")
                else -> continue
            }
            tried += way to nrc
            if (data == null) continue
            result = when (way) {
                "uds19" -> parseUds(module, data)
                "kwp18" -> parseKwp(module, data, vagNumbers)
                else -> parseKwp13(module, data)
            }
            break
        }
        val r = result ?: GmDtcResult(module, emptyList(), when {
            tried.all { it.second == null } -> tr("нет ответа", "no answer")
            else -> tr("не отдаёт ошибки (%s)", "does not report DTCs (%s)").format(tried.joinToString(", ") { (w, nrc) -> "${label(w)}: ${nrcText(nrc)}" })
        }, false)
        note("DTC %s: %s %s".format(module.id, r.result, r.codes.joinToString(" ") { it.full }))
        return r
    }

    private fun nrcText(nrc: Int?) = if (nrc == null) tr("нет ответа", "no answer") else tr("отказ %02X", "NRC %02X").format(nrc)

    /** [59 02 availMask (DTC_hi DTC_mid FTB status)*] */
    internal fun parseUds(module: GmModule, d: IntArray): GmDtcResult {
        val codes = mutableListOf<GmDtc>()
        var i = 3
        while (i + 3 < d.size) {
            val c = GmDtc(Dtc.decode(d[i], d[i + 1]), d[i + 2], d[i + 3], DtcScheme.UDS)
            if (c !in codes) codes += c
            i += 4
        }
        val tail = (d.size - 3) % 4
        return GmDtcResult(module, codes, (if (codes.isEmpty()) tr("нет кодов", "no codes") else tr("кодов: ${codes.size}", "codes: ${codes.size}")) + " (UDS)" +
            if (tail != 0) tr(", лишних байт в конце: $tail", ", extra bytes at end: $tail") else "", tail == 0)
    }

    /** [58 count (DTC_hi DTC_lo status)*]. VAG numbers the codes by the two bytes as a 5-digit decimal; others use SAE. */
    internal fun parseKwp(module: GmModule, d: IntArray, vagNumbers: Boolean = false): GmDtcResult {
        val codes = mutableListOf<GmDtc>()
        var i = 2
        while (i + 2 < d.size) {
            val c = if (vagNumbers) {
                val n = d[i] * 256 + d[i + 1]
                // VAG 16384 + N is SAE P0N (16684 = P0300, 16555 = P0171); other VAG numbers have no SAE form.
                val code = if (n in 16384..17383) "P%04d".format(n - 16384) else "%05d".format(n)
                GmDtc(code, -1, d[i + 2], DtcScheme.KWP, vag = n)
            } else GmDtc(Dtc.decode(d[i], d[i + 1]), -1, d[i + 2], DtcScheme.KWP)
            if (c !in codes) codes += c
            i += 3
        }
        val count = d.getOrElse(1) { -1 }
        return GmDtcResult(module, codes, (if (codes.isEmpty()) tr("нет кодов", "no codes") else tr("кодов: ${codes.size}", "codes: ${codes.size}")) + " (KWP)" +
            if (count != codes.size) tr(", блок сообщил $count", ", module reported $count") else "", count == codes.size)
    }

    /**
     * [53 count (DTC_hi DTC_lo)*] — ISO 14230-3 readDiagnosticTroubleCodes: codes without a status byte.
     * A zero code is filler. The raw answer goes to the log: no car has answered it to this app yet.
     */
    internal fun parseKwp13(module: GmModule, d: IntArray): GmDtcResult {
        note("DTC %s: \$13 answer %s".format(module.id, d.joinToString(" ") { "%02X".format(it) }))
        val codes = mutableListOf<GmDtc>()
        var i = 2
        while (i + 1 < d.size) {
            if (d[i] != 0 || d[i + 1] != 0) GmDtc(Dtc.decode(d[i], d[i + 1]), -1, 0, DtcScheme.KWP13).let { if (it !in codes) codes += it }
            i += 2
        }
        val count = d.getOrElse(1) { -1 }
        val whole = (d.size - 2) % 2 == 0 && count == (d.size - 2) / 2
        return GmDtcResult(module, codes, (if (codes.isEmpty()) tr("нет кодов", "no codes") else tr("кодов: ${codes.size}", "codes: ${codes.size}")) + " (KWP \$13)" +
            if (!whole) tr(", блок сообщил $count", ", module reported $count") else "", whole)
    }

    /** Returns (reply data, NRC); both null = silence. "7F xx 78" (wait) → ask again with a long timeout. */
    private suspend fun request(module: GmModule, req: String): Pair<IntArray?, Int?> {
        val service = req.substring(0, 2).toInt(16)
        var reply: CanReply = obd.request(req, 3000)
        var msg = answer(reply, module.resp, service)
        if (msg == null && reply.from(module.resp).any { it.nrc == 0x78 }) {
            obd.at("ATAT0")
            obd.at("ATSTFF")
            try {
                reply = obd.request(req, 5000)
                msg = answer(reply, module.resp, service)
            } finally {
                withContext(NonCancellable) {
                    runCatching { obd.at("ATST32") }
                    runCatching { obd.at(obd.adaptiveTiming) }
                }
            }
        }
        msg ?: return null to null
        if (msg.isNegative) return null to msg.nrc
        if (msg.service != service + 0x40) return null to -1
        return msg.data to null
    }

    private fun answer(reply: CanReply, resp: Int, service: Int) =
        reply.from(resp).firstOrNull { it.nrc != 0x78 && (it.service == service + 0x40 || (it.isNegative && it.data.getOrNull(1) == service)) }

    companion object {
        /** A block with no dialect of its own: UDS, then KWP \$18. */
        val DEFAULT = listOf("uds19", "kwp18")

        private fun label(way: String) = when (way) {
            "uds19" -> "UDS"
            "kwp18" -> "KWP"
            "kwp13" -> "KWP \$13"
            else -> way
        }

        /** The report title's list of the ways used: "UDS \$19 02 / KWP \$18 02" (+ " / KWP \$13", " / GM \$A9"). */
        fun title(used: Set<String>): String = listOfNotNull(
            "UDS \$19 02".takeIf { "uds19" in used || used.isEmpty() },
            "KWP \$18 02".takeIf { "kwp18" in used || used.isEmpty() },
            "KWP \$13".takeIf { "kwp13" in used },
            "GM \$A9".takeIf { "gm_a9" in used },
        ).joinToString(" / ")
    }
}
