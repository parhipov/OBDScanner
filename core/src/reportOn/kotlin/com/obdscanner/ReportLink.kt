package com.obdscanner

import com.obdscanner.report.ReportEntry

/**
 * The session report in this build (core/build.gradle.kts adds this folder when private/report is there): a direct
 * call, so a generator whose signature moved fails the build instead of hiding the app's «Отчёт» button.
 * The other build gets core/src/reportOff.
 */
object ReportLink {
    val available = true

    /** [files] — name → text (data.csv, report.txt, raw.log, sensors.csv); [fuelWatch], [progress] as in ReportEntry. */
    fun build(name: String, files: Map<String, String>, fuelWatch: Boolean, progress: (Double, String) -> Unit): String =
        ReportEntry.build(name, "", files, fuelWatch, progress)
}
