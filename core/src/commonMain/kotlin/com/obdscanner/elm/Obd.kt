package com.obdscanner.elm

import com.obdscanner.util.format
import com.obdscanner.tr

/** OBD/CAN request layer on top of the raw ELM327 driver. */
class Obd(val elm: Elm327) {
    /** 3 for 11-bit CAN, 8 for 29-bit. */
    var headerChars = 3
    /** ISO 9141-2 / ISO 14230: no CAN headers or filters; physical addressing only on KWP (see [canTarget]). */
    var kline = false
    /** ELM protocol number (ATDPN) once known: 3 ISO 9141-2, 4/5 ISO 14230 (KWP2000), 6–9 CAN. */
    var protocol = 0
    /**
     * Whether single modules can be addressed. On KWP2000 (ISO 14230) a module answers its physical address
     * too (ELM327 datasheet, "SH xx yy zz"): header 81 <address> F1, the ELM inserts the length. ISO 9141-2
     * has no physical addressing in OBD.
     */
    val canTarget get() = !kline || protocol == 4 || protocol == 5
    var currentHeader: Int? = null
        private set
    var responseFilter: Int? = null
        private set
    /** Clone supports the "expected responses" digit after the request ("22F190 1"). */
    var countDigit = false
    /** Adaptive timing command that worked at init — to restore after a fixed-timeout operation. */
    var adaptiveTiming = "ATAT1"

    suspend fun at(cmd: String, timeoutMs: Long = 1500) = elm.send(cmd, timeoutMs)

    /**
     * Brings the adapter back after it reset itself ([ElmReply.adapterReset]): format settings, timing and
     * the known protocol — set by the owner, it knows them. Routing is restored here afterwards.
     */
    var onAdapterReset: (suspend () -> Unit)? = null
    private var recovering = false
    /** Physical target of the last [target] call, null = broadcast. */
    private var lastTarget: Pair<Int, Int>? = null

    suspend fun request(hex: String, timeoutMs: Long = 1500, expectOne: Boolean = false): CanReply {
        val cmd = if (expectOne && countDigit) hex + "1" else hex
        var raw = elm.send(cmd, timeoutMs)
        if (raw.adapterReset && !recovering) {
            recover()
            raw = elm.send(cmd, timeoutMs)
        }
        val first = CanParser.parse(raw, headerChars)
        if (!first.garbled) return first
        val second = CanParser.parse(elm.send(cmd, timeoutMs), headerChars)
        return if (second.garbled && second.messages.size < first.messages.size) first else second
    }

    private suspend fun recover() {
        val hook = onAdapterReset ?: return
        recovering = true
        try {
            val target = lastTarget
            hook()
            resetState()
            if (target != null && canTarget) target(target.first, target.second) else broadcast()
        } finally {
            recovering = false
        }
    }

    /** Header/filter/flow-control are non-default (needed only for GM USDT 0x24x → 0x64x). */
    private var customRouting = false

    /** Physical addressing to one module. Tolerates clones that don't know ATCRA. */
    suspend fun target(req: Int, resp: Int) {
        if (kline) return targetKline(req, resp)
        lastTarget = req to resp
        if (req in 0x7E0..0x7E7 && resp == req + 8) {
            // Standard OBD ids: the default receive filter and automatic flow control already fit.
            if (customRouting) resetRouting()
            if (currentHeader != req) {
                at("ATSH%03X".format(req))
                currentHeader = req
            }
            return
        }
        customRouting = true
        if (currentHeader != req) {
            at("ATSH%03X".format(req))
            at("ATFCSH%03X".format(req))
            at("ATFCSD300000")
            at("ATFCSM1")
            currentHeader = req
        }
        if (responseFilter != resp) {
            val r = at("ATCRA%03X".format(resp))
            if (r.isUnknown) {
                at("ATCF%03X".format(resp))
                at("ATCM7FF")
            }
            responseFilter = resp
        }
    }

    /**
     * KWP2000 on K-line: header 81 <module> F1 (physical, length in the format byte — the ELM fills it in).
     * The module is its own source address in the reply, so [req] == [resp]. The wakeup messages keep the
     * functional header they got when the protocol started (datasheet), only requests go to the module.
     */
    private suspend fun targetKline(req: Int, resp: Int) {
        check(canTarget) { tr("на ISO 9141-2 адресации блоков нет", "ISO 9141-2 has no module addressing") }
        // Only a real module address: never the functional OBD one (33), the tester (F1) or a CAN id.
        check(req == resp && req in 0x01..0xEF && req != KWP_FUNCTIONAL) { "bad K-line address %X".format(req) }
        lastTarget = req to resp
        if (currentHeader != req) {
            at("ATSH81%02XF1".format(req))
            currentHeader = req
        }
    }

    /** After ATZ the adapter is back to defaults (header 7DF, no filters). */
    fun resetState() {
        currentHeader = null
        responseFilter = null
        customRouting = false
    }

    /** Someone changed the adapter's filters behind our back: the next target/broadcast sets everything again. */
    fun forgetRouting() {
        customRouting = true
        responseFilter = null
    }

    /** Back to functional OBD broadcast (7DF, all ECUs answer). */
    suspend fun broadcast() {
        lastTarget = null
        if (kline) {
            // Untouched since the protocol started (null) — the ELM's own default header is still in place.
            if (currentHeader != null && currentHeader != KWP_FUNCTIONAL) {
                at("ATSHC133F1")
                currentHeader = KWP_FUNCTIONAL
            }
            return
        }
        if (customRouting) resetRouting()
        if (currentHeader == 0x7DF) return
        at("ATSH7DF")
        currentHeader = 0x7DF
    }

    private suspend fun resetRouting() {
        at("ATFCSM0")
        at("ATAR")
        responseFilter = null
        customRouting = false
    }

    companion object {
        /** ISO 14230-4 functional OBD request address: header C1 33 F1 (the ELM327 default for KWP). */
        const val KWP_FUNCTIONAL = 0x33
    }
}
