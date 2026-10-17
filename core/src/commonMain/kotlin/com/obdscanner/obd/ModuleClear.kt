package com.obdscanner.obd

import com.obdscanner.util.format
import com.obdscanner.elm.CanReply
import com.obdscanner.elm.Obd
import com.obdscanner.gm.DtcScheme
import com.obdscanner.gm.GmModule
import com.obdscanner.tr
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Clears one module's code memory — only the user's «Стереть ошибки в блоках», never by itself. The request is the one
 * of the way the module gave its codes: GM \$04 (ClearDiagnosticInformation, GMW3110: the whole memory, a code can't be
 * named), UDS \$14 FF FF FF (all groups), KWP2000 \$14 FF 00. No session change, no security access: a module that wants
 * them refuses, and that is what the user is told.
 */
class ModuleClear(private val obd: Obd, private val note: (String) -> Unit) {
    /** [ok] — the module confirmed; [text] — what it said, for the screen. */
    class Result(val ok: Boolean, val text: String)

    suspend fun clear(module: GmModule, scheme: DtcScheme): Result {
        obd.target(module.req, module.resp)
        val req = request(scheme)
        val service = req.substring(0, 2).toInt(16)
        var reply = obd.request(req, 3000)
        var msg = answer(reply, module.resp, service)
        // "7F xx 78": still working (clearing a big memory takes a while) — wait for the real answer.
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
        val r = when {
            msg == null -> Result(false, tr("нет ответа", "no answer"))
            !msg.isNegative && msg.service == service + 0x40 -> Result(true, tr("стёрто", "cleared"))
            msg.isNegative -> Result(false, refusal(msg.nrc))
            else -> Result(false, tr("непонятный ответ", "an unexpected answer"))
        }
        note("CLEAR %s: %s → %s".format(module.key, req, if (msg == null) "no answer" else msg.data.joinToString(" ") { "%02X".format(it) }))
        return r
    }

    private fun answer(reply: CanReply, resp: Int, service: Int) =
        reply.from(resp).firstOrNull { it.nrc != 0x78 && (it.service == service + 0x40 || (it.isNegative && it.data.getOrNull(1) == service)) }

    companion object {
        /** The clearing request of a module that gave its codes the [scheme] way. */
        fun request(scheme: DtcScheme): String = when (scheme) {
            DtcScheme.GM -> "04"
            DtcScheme.UDS -> "14FFFFFF"
            DtcScheme.KWP, DtcScheme.KWP13 -> "14FF00"
        }

        /** Why a module refused, in words (ISO 14229 / 14230 negative response codes). */
        fun refusal(nrc: Int?): String = when (nrc) {
            0x22 -> tr("блок не дал стереть: заглушите двигатель, машина должна стоять", "the module refused: switch the engine off, the car must stand still")
            0x11, 0x12, 0x31 -> tr("блок не стирает коды таким запросом", "the module doesn't clear codes with this request")
            0x33 -> tr("блок стирает коды только с доступом по ключу", "the module clears codes only with security access")
            0x7F, 0x7E -> tr("блок стирает коды только в особом режиме диагностики", "the module clears codes only in a special diagnostic session")
            null -> tr("отказ", "refused")
            else -> tr("отказ %02X", "refused, %02X").format(nrc)
        }
    }
}
