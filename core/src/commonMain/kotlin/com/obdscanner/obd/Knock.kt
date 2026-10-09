package com.obdscanner.obd

import com.obdscanner.car.ExtCommand

/**
 * Knock retard for «Как бензин?»: a parameter of any make with the knock_retard role, or the GM ECM DIDs read
 * on the CTS (formulas unverified, gm.json). The first one of [main] that the car gives is shown on Main and
 * recorded as calc.knock, so the report finds it the same way on every make.
 */
object Knock {
    const val ROLE = "knock_retard"

    /** GM ECM: retard, retard 2, total retard — the order [main] prefers them in. */
    private val GM = listOf("22.11A6", "22.125D", "22.12D9")

    /** A request that gives knock retard; [gm] — the car is a GM (the DIDs mean something else elsewhere). */
    fun isKnock(c: ExtCommand, gm: Boolean) = c.signals.any { it.role == ROLE } || gm && c.service == "22" && "22.${c.didHex}" in GM

    fun isKnock(r: Reading, gm: Boolean) = r.role == ROLE || gm && r.source in GM

    /** The retard shown and recorded: the role first, then the GM DIDs in their order. */
    fun main(list: Collection<Reading>, gm: Boolean): Reading? =
        list.firstOrNull { it.role == ROLE && it.value != null }
            ?: if (gm) GM.firstNotNullOfOrNull { s -> list.firstOrNull { it.source == s && it.value != null } } else null
}
