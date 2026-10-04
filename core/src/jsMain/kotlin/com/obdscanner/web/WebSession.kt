package com.obdscanner.web

import com.obdscanner.session.SessionLog
import com.obdscanner.session.Store

/** A session's files in memory: the page saves them as the phone's zip (raw.log, report.txt, data.csv…). */
class MemorySession(name: String) : SessionLog(name) {
    private val files = linkedMapOf(
        "raw.log" to StringBuilder(),
        "data.csv" to StringBuilder(),
        "report.txt" to StringBuilder(),
        "scan.csv" to StringBuilder(),
    )

    init {
        start()
    }

    override fun append(file: String, text: String) {
        files.getOrPut(file) { StringBuilder() }.append(text)
    }

    fun files(): List<Pair<String, String>> = files.map { (k, v) -> k to v.toString() }
}

/** The connection's small settings (last protocol, what answered per car) in localStorage, under "obd:". */
class LocalStore : Store {
    private val ls: dynamic = js("typeof localStorage !== 'undefined' ? localStorage : null")

    private fun get(key: String): String? = try {
        ls?.getItem("obd:$key") as String?
    } catch (_: Throwable) {
        null
    }

    private fun set(key: String, value: String?) {
        try {
            if (value == null) ls?.removeItem("obd:$key") else ls?.setItem("obd:$key", value)
        } catch (_: Throwable) {
            // Private mode or full storage: the next connection just finds everything again.
        }
    }

    override fun getInt(key: String, default: Int) = get(key)?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) = set(key, value.toString())
    override fun getString(key: String) = get(key)
    override fun putString(key: String, value: String?) = set(key, value)
    override fun getStringSet(key: String): Set<String>? = get(key)?.let { s -> if (s.isEmpty()) emptySet() else s.split('\n').toSet() }
    override fun putStringSet(key: String, value: Set<String>) = set(key, value.joinToString("\n"))
}
