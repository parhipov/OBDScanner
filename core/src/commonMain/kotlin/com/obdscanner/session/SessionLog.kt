package com.obdscanner.session

import com.obdscanner.obd.Reading
import com.obdscanner.tr
import com.obdscanner.util.clockText
import com.obdscanner.util.format
import com.obdscanner.util.nowMs
import kotlin.concurrent.Volatile
import com.obdscanner.util.Synchronized

/**
 * One connection's files — the same on the phone (a folder, [com.obdscanner.session.Session] in the app)
 * and in the browser (kept in memory), so the analysis scripts read both:
 *   raw.log    — every byte exchanged with the adapter, with timestamps (debugging)
 *   data.csv   — every decoded value (long format: one row per value)
 *   report.txt — discovery results: ECUs, supported PIDs, VIN, DTCs, Mode 06, GM scan
 *   scan.csv   — GM Mode 22 / 1A scan hits
 *   bus.csv    — passive bus listening (only if it was started)
 *   sensors.csv — phone accelerometer and gyroscope, every sample
 * A subclass creates its outputs, then calls [start].
 */
abstract class SessionLog(val name: String) : Recorder {
    /** Session start, wall clock ms: t_ms in every file counts from here. */
    val startMs = nowMs()
    private val t0 = startMs
    @Volatile var closed = false
        private set

    /** Appends [text] to the session's [file] ("raw.log", "data.csv"…); bus.csv and sensors.csv start with their first line. */
    protected abstract fun append(file: String, text: String)

    protected open fun flushFiles() {}

    protected open fun closeFiles() {}

    /** Every raw.log line as it is written (logcat on the phone): direction ('>', '<', '#') and text. */
    protected open fun echo(direction: Char, text: String) {}

    /** Writes the headers; call once the outputs exist. */
    protected fun start() {
        append("data.csv", "t_ms,time,ecu,key,name,value,text,unit\n")
        append("scan.csv", "time,module_req,module_resp,service,id,len,hex,ascii\n")
        append("report.txt", tr("OBD Scanner — сессия $name\n", "OBD Scanner — session $name\n"))
    }

    private fun now() = clockText(nowMs())

    @Synchronized fun raw(direction: Char, text: String) {
        if (closed) return
        echo(direction, text)
        append("raw.log", "${now()} $direction $text\n")
    }

    @Synchronized override fun note(text: String) {
        if (closed) return
        echo('#', text)
        append("raw.log", "${now()} # $text\n")
    }

    private val lastLogged = HashMap<String, Pair<Any?, Long>>()

    /** Logs a value when it changes, and unchanged values at most once per second. */
    @Synchronized override fun value(r: Reading) {
        if (closed) return
        val v: Any? = r.value ?: r.text
        val prev = lastLogged[r.key]
        if (prev != null && prev.first == v && r.time - prev.second < 1000) return
        lastLogged[r.key] = v to r.time
        val ecu = "%03X".format(r.ecu)
        append("data.csv", "${r.time - t0},${now()},$ecu,${q(r.source)},${q(r.name)},${r.value ?: ""},${q(r.text ?: "")},${q(r.unit)}\n")
    }

    @Synchronized override fun report(title: String, body: String) {
        if (closed) return
        append("report.txt", "\n=== $title ===\n$body\n")
        flushFiles()
    }

    @Synchronized override fun scanHit(req: Int, resp: Int, service: String, id: String, data: IntArray) {
        if (closed) return
        val hex = data.joinToString(" ") { "%02X".format(it) }
        val ascii = data.map { if (it in 0x20..0x7E) it.toChar() else '.' }.joinToString("")
        val ids = "%03X,%03X".format(req, resp)
        append("scan.csv", "${now()},$ids,$service,$id,${data.size},$hex,${q(ascii)}\n")
        flushFiles()
    }

    private var bus = false

    /** Frames from one listening window. ELM gives no per-frame time, so it's spread over the window. */
    @Synchronized override fun busFrames(window: Int, frames: List<Pair<Int, IntArray>>, windowMs: Long) {
        if (closed) return
        if (!bus) {
            append("bus.csv", "window,t_ms_approx,id,len,hex\n")
            bus = true
        }
        val start = nowMs() - windowMs - t0
        val sb = StringBuilder()
        frames.forEachIndexed { i, (id, d) ->
            val t = start + if (frames.size > 1) windowMs * i / (frames.size - 1) else 0
            sb.append("$window,$t,%03X,${d.size},".format(id) + d.joinToString(" ") { "%02X".format(it) } + "\n")
        }
        append("bus.csv", sb.toString())
        flushFiles()
    }

    private var sensors = false

    /** Ready rows `t_ms,s,x,y,z`: s — a (acceleration m/s², with gravity) or g (rotation °/s). */
    @Synchronized fun sensors(rows: CharSequence) {
        if (closed) return
        if (!sensors) {
            append("sensors.csv", "t_ms,s,x,y,z\n")
            sensors = true
        }
        append("sensors.csv", rows.toString())
    }

    @Synchronized override fun flush() {
        if (closed) return
        flushFiles()
    }

    @Synchronized fun close() {
        if (closed) return
        flush()
        closed = true
        runCatching { closeFiles() }
    }

    private fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace("\n", " / ") + "\"" else s
}
