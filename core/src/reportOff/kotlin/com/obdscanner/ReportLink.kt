package com.obdscanner

/** A build without the session report (no private/report, or -PnoPrivate): no «Отчёт» button. See core/src/reportOn. */
object ReportLink {
    val available = false

    fun build(name: String, files: Map<String, String>, fuelWatch: Boolean, progress: (Double, String) -> Unit): String =
        error("no report generator in this build")
}
