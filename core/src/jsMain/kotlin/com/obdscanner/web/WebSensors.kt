package com.obdscanner.web

import com.obdscanner.session.SessionLog
import com.obdscanner.tr
import com.obdscanner.util.nowMs

/**
 * The browser's devicemotion events into sensors.csv, every value as the browser gives it: no unit change, no axes
 * put in the app's order. Their own `s` codes, so they don't pass for the app's a/g rows:
 *   a — accelerationIncludingGravity x, y, z
 *   l — acceleration (without gravity) x, y, z
 *   r — rotationRate alpha, beta, gamma
 * report.txt says where they came from (the browser, the event's interval); what they mean against the app's is
 * worked out later. The browser gives them only while the page is on screen.
 */
class WebSensors {
    private var running: Running? = null

    private class Running(val s: SessionLog) {
        lateinit var handler: (dynamic) -> Unit
        val rows = StringBuilder()
        var lastWrite = 0.0
        var first = 0.0
        var last = 0.0
        val count = IntArray(3)
        var interval: Any? = null
    }

    fun start(s: SessionLog) {
        stop()
        val w: dynamic = js("typeof window !== 'undefined' && typeof DeviceMotionEvent !== 'undefined' ? window : null")
        s.report(tr("Датчики телефона", "Phone sensors"),
            tr("Источник", "Source") + ": devicemotion " + tr("браузера, значения как есть", "of the browser, values as is") + "\n" +
                tr("Браузер", "Browser") + ": " + js("typeof navigator !== 'undefined' ? navigator.userAgent : ''") + "\n" +
                "sensors.csv: a — accelerationIncludingGravity x,y,z; l — acceleration x,y,z; r — rotationRate alpha,beta,gamma\n" +
                tr("t_ms — время события (performance.timeOrigin + event.timeStamp) от начала сессии",
                    "t_ms — the event's time (performance.timeOrigin + event.timeStamp) from the session start") +
                if (w == null) "\n" + tr("devicemotion в этом браузере нет", "this browser has no devicemotion") else "")
        // The user agent string is cut down (Android 10; K on every phone): the model and the real version are asked for.
        val uad: dynamic = js("typeof navigator !== 'undefined' && navigator.userAgentData ? navigator.userAgentData : null")
        uad?.getHighEntropyValues(arrayOf("model", "platformVersion"))?.then({ v: dynamic ->
            s.report(tr("Телефон", "Phone"), "${v.model} (${v.platform} ${v.platformVersion})")
        }, { _: dynamic -> })
        if (w == null) return
        val r = Running(s)
        r.handler = { e -> onMotion(r, e) }
        w.addEventListener("devicemotion", r.handler)
        running = r
    }

    fun stop() {
        val r = running ?: return
        running = null
        js("window").removeEventListener("devicemotion", r.handler)
        write(r)
        val sec = (r.last - r.first) / 1000
        r.s.report(tr("Датчики телефона, получено", "Phone sensors, received"),
            if (sec > 1) "a: ${hz(r.count[0], sec)}, l: ${hz(r.count[1], sec)}, r: ${hz(r.count[2], sec)} " + tr("в секунду", "per second") +
                "\nevent.interval: ${r.interval} ms"
            else tr("нет событий (страница не на экране или датчики движения запрещены сайту)",
                "no events (the page off screen, or motion sensors blocked for the site)"))
    }

    private fun onMotion(r: Running, e: dynamic) {
        val origin = js("typeof performance !== 'undefined' && performance.timeOrigin ? performance.timeOrigin : 0") as Double
        val wall = if (origin > 0 && e.timeStamp != undefined) origin + (e.timeStamp as Double) else nowMs().toDouble()
        val t = fixed(wall - r.s.startMs, 2)
        var got = false
        fun row(code: Char, i: Int, v: dynamic, k1: String, k2: String, k3: String) {
            if (v == null || v[k1] == null) return
            r.rows.append(t).append(',').append(code).append(',')
                .append(raw(v[k1])).append(',').append(raw(v[k2])).append(',').append(raw(v[k3])).append('\n')
            r.count[i]++
            got = true
        }
        row('a', 0, e.accelerationIncludingGravity, "x", "y", "z")
        row('l', 1, e.acceleration, "x", "y", "z")
        row('r', 2, e.rotationRate, "alpha", "beta", "gamma")
        if (!got) return
        if (r.first == 0.0) {
            r.first = wall
            r.interval = e.interval
        }
        r.last = wall
        if (wall - r.lastWrite >= WRITE_EVERY_MS) {
            r.lastWrite = wall
            write(r)
        }
    }

    private fun write(r: Running) {
        if (r.rows.isEmpty()) return
        r.s.sensors(r.rows)
        r.rows.clear()
    }

    /** The number as the browser gave it (Chrome's come in steps of 0.1); null (no such value) is an empty cell. */
    private fun raw(v: dynamic): String = if (v == null) "" else v.toString() as String

    /** A number with [dec] decimals; null is an empty cell. */
    private fun fixed(v: dynamic, dec: Int): String = if (v == null) "" else (v as Number).toDouble().asDynamic().toFixed(dec) as String

    private fun hz(n: Int, sec: Double): String = (n / sec).asDynamic().toFixed(0) as String

    private companion object {
        const val WRITE_EVERY_MS = 100.0
    }
}
