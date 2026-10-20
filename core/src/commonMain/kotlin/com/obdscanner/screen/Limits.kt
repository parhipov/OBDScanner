package com.obdscanner.screen

import com.obdscanner.tr

/**
 * The thresholds that colour a value: amber below / above, red below / above; a null side has no rule. The app has
 * built-in ones for a few values ([MainScreen.builtIn]); the owner sets their own per car in the card's help
 * («Пороги»), field by field over the built-in ones ([over]). Kept by the value's source («01.05», «calc.trim1»),
 * so the same value on another ECU address of the same car keeps them.
 */
data class Limits(val warnBelow: Double? = null, val warnAbove: Double? = null, val badBelow: Double? = null, val badAbove: Double? = null) {
    val isEmpty get() = warnBelow == null && warnAbove == null && badBelow == null && badAbove == null

    /** The value's level under these rules: red first, then amber, else none. */
    fun level(v: Double): Level? = when {
        badBelow != null && v < badBelow -> Level.BAD
        badAbove != null && v > badAbove -> Level.BAD
        warnBelow != null && v < warnBelow -> Level.WARN
        warnAbove != null && v > warnAbove -> Level.WARN
        else -> null
    }

    /** These rules with [user]'s set sides on top: a side the owner left empty keeps the built-in one. */
    fun over(user: Limits?): Limits = if (user == null) this else Limits(
        user.warnBelow ?: warnBelow, user.warnAbove ?: warnAbove, user.badBelow ?: badBelow, user.badAbove ?: badAbove)

    /** One stored line: the source and the four sides, «01.05;;100;;105». */
    fun serialize(source: String) = listOf(source, warnBelow, warnAbove, badBelow, badAbove).joinToString(";") { it?.toString() ?: "" }

    companion object {
        val TITLE = tr("Пороги", "Thresholds")
        val WARN_BELOW = tr("Жёлтый ниже", "Amber below")
        val WARN_ABOVE = tr("Жёлтый выше", "Amber above")
        val BAD_BELOW = tr("Красный ниже", "Red below")
        val BAD_ABOVE = tr("Красный выше", "Red above")
        val NOTE = tr("Для этой машины. Пустое поле — правила нет; серым — встроенное правило приложения, своё число его заменяет.",
            "For this car. An empty field is no rule; grey is the app's built-in rule, your number replaces it.")
        val SAVE = tr("Сохранить", "Save")
        val RESET = tr("Сбросить", "Reset")
        val CANCEL = tr("Отмена", "Cancel")

        /** The stored lines back into rules by source; a line that doesn't parse is dropped. */
        fun parse(lines: Set<String>?): Map<String, Limits> = lines.orEmpty().mapNotNull { line ->
            val p = line.split(';')
            if (p.size != 5 || p[0].isEmpty()) return@mapNotNull null
            fun side(i: Int) = p[i].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            val l = Limits(side(1), side(2), side(3), side(4))
            if (l.isEmpty) null else p[0] to l
        }.toMap()

        fun serialize(all: Map<String, Limits>): Set<String> = all.entries.map { (s, l) -> l.serialize(s) }.toSet()
    }
}
