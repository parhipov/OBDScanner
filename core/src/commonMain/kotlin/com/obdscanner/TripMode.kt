package com.obdscanner

import com.obdscanner.screen.MainScreen

/**
 * The trip's mode: one at a time or none, kept in the settings ([CarLink.mode]). The main ones are on the first screen
 * («Режим: Топливо / Безопасность»); a [test] one is turned on on «Машина», under «Тестовые режимы», and shows on the
 * first screen too while it is on. What a mode changes in the polling: [fuel] — «Как бензин?» (knock read every cycle,
 * its section on Main and first in the report); [safety] — «Контроль безопасности движения» (its own set every cycle,
 * the rest rarely); [wheels] — the wheels heard on the bus between the polls ([com.obdscanner.bus.WheelWatch]).
 */
enum class TripMode(
    /** In the settings and raw.log ("trip mode: safety"). */
    val id: String,
    /** On its card on the first screen and its tick on «Машина». */
    val title: String,
    /** The help's title. */
    val label: String,
    val help: String,
    val test: Boolean = false,
    val fuel: Boolean = false,
    val safety: Boolean = false,
    val wheels: Boolean = false,
) {
    FUEL("fuel", MainScreen.FUEL_MODE, MainScreen.WATCH_LABEL, MainScreen.WATCH_HELP, fuel = true),
    SAFETY("safety", MainScreen.SAFETY_MODE, MainScreen.SAFETY_LABEL, MainScreen.SAFETY_HELP, safety = true),
    /**
     * «Безопасность» with the wheels. A test: the CTS's ELM327 v1.5 clone hung in a window on each of three drives of
     * 2026-10-21 (windows 176, 47 and 237) and came back only when it was replugged, so the rest of each drive was lost.
     */
    SAFETY_PLUS("safety_plus", MainScreen.SAFETY_PLUS_MODE, MainScreen.SAFETY_PLUS_LABEL, MainScreen.SAFETY_PLUS_HELP,
        test = true, safety = true, wheels = true);

    companion object {
        fun byId(id: String?): TripMode? = entries.firstOrNull { it.id == id }

        /** On the first screen: the main modes, and a test one while it is on. */
        fun shown(on: TripMode?): List<TripMode> = entries.filter { !it.test || it == on }

        val tests: List<TripMode> get() = entries.filter { it.test }
    }
}
