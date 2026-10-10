package com.obdscanner

import java.io.File

/**
 * The session report, when the build has it: optional sources (core/build.gradle.kts adds private/report), reached
 * through the core's ReportLink — without them [available] is false and the app shows no report button.
 */
object ReportBridge {
    private val files = listOf("data.csv", "report.txt", "raw.log", "sensors.csv")

    val available: Boolean get() = ReportLink.available

    /**
     * The report page for a session folder. [fuelWatch] — «Как бензин?» is ticked now (its section even for a trip
     * made without it); [progress] — (0…1, stage), throwing from it stops the build.
     */
    fun build(dir: File, fuelWatch: Boolean, progress: (Double, String) -> Unit): String {
        val texts = files.mapNotNull { f -> File(dir, f).takeIf { it.isFile }?.let { f to it.readText(Charsets.UTF_8) } }.toMap()
        return ReportLink.build(dir.name, texts, fuelWatch, progress)
    }
}
