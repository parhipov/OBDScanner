package com.obdscanner

import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * The session report, when the build has it: optional sources (core/build.gradle.kts adds private/report)
 * with com.obdscanner.report.ReportEntry. Found by reflection, so the public build compiles and runs without it —
 * then [available] is false and the app shows no report button.
 */
object ReportBridge {
    private const val ENTRY = "com.obdscanner.report.ReportEntry"
    private val files = listOf("data.csv", "report.txt", "raw.log", "sensors.csv")

    private val entry: Pair<Any, java.lang.reflect.Method>? by lazy {
        runCatching {
            val cls = Class.forName(ENTRY)
            cls.getField("INSTANCE").get(null) to cls.getMethod("build", String::class.java, String::class.java,
                Map::class.java, Function2::class.java)
        }.getOrNull()
    }

    val available: Boolean get() = entry != null

    /** The report page for a session folder. [progress] — (0…1, stage); throwing from it stops the build. */
    fun build(dir: File, progress: (Double, String) -> Unit): String {
        val (obj, method) = entry ?: error("no report generator in this build")
        val texts = files.mapNotNull { f -> File(dir, f).takeIf { it.isFile }?.let { f to it.readText(Charsets.UTF_8) } }.toMap()
        val cb: (Double, String) -> Unit = progress
        try {
            return method.invoke(obj, dir.name, "", texts, cb) as String
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
