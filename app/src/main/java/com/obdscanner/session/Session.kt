package com.obdscanner.session

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One connection = one folder of the [SessionLog] files; the adapter exchange also goes to logcat. */
class Session(val dir: File) : SessionLog(dir.name) {
    private val files = linkedMapOf(
        "raw.log" to writer("raw.log"),
        "data.csv" to writer("data.csv"),
        "report.txt" to writer("report.txt"),
        "scan.csv" to writer("scan.csv"),
    )

    init {
        start()
    }

    private fun writer(name: String) = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, name), true), Charsets.UTF_8))

    override fun append(file: String, text: String) {
        files.getOrPut(file) { writer(file) }.write(text)
    }

    override fun echo(direction: Char, text: String) {
        if (direction == '#') Log.i(TAG, "# $text") else Log.d(TAG, "$direction $text")
    }

    override fun flushFiles() = files.values.forEach { it.flush() }

    override fun closeFiles() = files.values.forEach { it.close() }

    companion object {
        /** Logcat tag: `adb logcat OBD:D *:S` shows the adapter exchange live. */
        const val TAG = "OBD"
    }
}

class SessionStore(private val root: File, private val shareDir: File) {
    init { root.mkdirs() }

    fun create(): Session {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val dir = File(root, name).apply { mkdirs() }
        return Session(dir)
    }

    fun list(): List<File> = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun delete(dir: File) { dir.deleteRecursively() }

    fun size(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Packs a session folder for sharing. */
    fun zip(dir: File): File {
        shareDir.mkdirs()
        shareDir.listFiles()?.forEach { it.delete() }
        val out = File(shareDir, "obd_${dir.name}.zip")
        ZipOutputStream(FileOutputStream(out)).use { z ->
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                z.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }
}
