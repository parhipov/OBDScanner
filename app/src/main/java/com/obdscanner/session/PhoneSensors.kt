package com.obdscanner.session

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.obdscanner.tr

/**
 * Phone accelerometer and gyroscope into sensors.csv for the whole session — to line up jerks,
 * shifts and braking with data.csv afterwards. Nothing on screen.
 *
 * The accelerometer is raw (with gravity): the phone's mount angle is worked out from it later.
 * Axes are the phone's own: x — to the right of the screen, y — up the screen, z — out of the screen.
 */
class PhoneSensors(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var running: Pair<HandlerThread, SensorEventListener>? = null

    @Synchronized fun start(s: Session) {
        stop()
        fun name(x: Sensor?) = x?.let { "${it.vendor} ${it.name}" } ?: tr("нет", "none")
        s.report(tr("Датчики телефона", "Phone sensors"),
            tr("Акселерометр", "Accelerometer") + ": " + name(acc) + "\n" + tr("Гироскоп", "Gyroscope") + ": " + name(gyro))
        if (sm == null || (acc == null && gyro == null)) return
        val t = HandlerThread("sensors").apply { start() }
        val l = Recorder(s)
        val h = Handler(t.looper)
        acc?.let { sm.registerListener(l, it, PERIOD_US, h) }
        gyro?.let { sm.registerListener(l, it, PERIOD_US, h) }
        running = t to l
    }

    @Synchronized fun stop() {
        running?.let { (t, l) ->
            sm?.unregisterListener(l)
            t.quitSafely()
        }
        running = null
    }

    /** Averages samples over [BIN_MS] and writes one row per bin. Lives on its own thread. */
    private class Recorder(private val s: Session) : SensorEventListener {
        private val a = DoubleArray(3)
        private val g = DoubleArray(3)
        private var na = 0
        private var ng = 0
        private var bin = -1L

        override fun onSensorChanged(e: SensorEvent) {
            val b = wallMs(e) / BIN_MS
            if (b != bin) {
                if (bin >= 0 && (na > 0 || ng > 0)) {
                    s.sensors(bin * BIN_MS, if (na > 0) DoubleArray(3) { a[it] / na } else null, if (ng > 0) DoubleArray(3) { g[it] / ng } else null)
                }
                bin = b
                a.fill(0.0); g.fill(0.0); na = 0; ng = 0
            }
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> { for (i in 0..2) a[i] += e.values[i].toDouble(); na++ }
                Sensor.TYPE_GYROSCOPE -> { for (i in 0..2) g[i] += Math.toDegrees(e.values[i].toDouble()); ng++ }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

        /** The sample's own time when the clock base is the usual one, otherwise the time it arrived. */
        private fun wallMs(e: SensorEvent): Long {
            val now = System.currentTimeMillis()
            val age = (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
            return if (age in 0..5000) now - age else now
        }
    }

    companion object {
        /** 50 Hz from the sensors, 20 rows/s in the file (~3–4 MB per hour). */
        private const val PERIOD_US = 20_000
        private const val BIN_MS = 50L
    }
}
