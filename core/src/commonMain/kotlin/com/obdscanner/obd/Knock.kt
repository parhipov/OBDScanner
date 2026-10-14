package com.obdscanner.obd

import com.obdscanner.car.ExtCommand
import kotlin.math.abs

/**
 * Knock retard for «Как бензин?»: a parameter of any make with the knock_retard role, or the GM ECM DIDs read
 * on the CTS (formulas unverified, gm.json). The first one of [main] that the car gives is shown on Main and
 * recorded as calc.knock, so the report finds it the same way on every make.
 */
object Knock {
    const val ROLE = "knock_retard"

    /** A retard beyond this on a cylinder isn't one: another ECU's layout (a Kia's 21 16 read as an Elantra's: 172°). */
    const val MAX_DEG = 20.0

    /** A retard this big at idle isn't one either: the engine computer doesn't pull timing there. */
    const val IDLE_DEG = 5.0

    /** The knock value of [out] that can't be real, null if none: one that doesn't decode, or beyond ±[MAX_DEG]. */
    fun implausible(out: Collection<Reading>): Reading? =
        out.firstOrNull { it.role == ROLE && (it.value == null || abs(it.value) > MAX_DEG) }

    /** The knock value of [out] that is a big retard at idle ([rpm], [load] — Mode 01), null if none or not idling. */
    fun atIdle(out: Collection<Reading>, rpm: Double?, load: Double?): Reading? {
        if (rpm == null || load == null || rpm !in 300.0..1100.0 || load >= 40) return null
        return out.firstOrNull { it.role == ROLE && abs(it.value ?: 0.0) >= IDLE_DEG }
    }

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
