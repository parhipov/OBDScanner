package com.obdscanner

import com.obdscanner.util.systemLanguage

/**
 * App language: Russian on a Russian system, English on any other. Picked once per process: the
 * static tables (PID names, DTC texts, Mode 06) are built from it, so a language switch restarts
 * the app (MainActivity). XML resources follow the same rule by themselves
 * (res/values = English, res/values-ru = Russian).
 */
object L10n {
    val ru: Boolean = systemLanguage() == "ru"
}

/** The text in the app language, see [L10n]. */
fun tr(ru: String, en: String): String = if (L10n.ru) ru else en
