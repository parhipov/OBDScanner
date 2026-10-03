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
 * shifts and braking with data.csv, and to look at engine and wheel vibration afterwards. Nothing on screen.
 *
 * Every sample is written as is, at up to [TARGET_HZ] (or as fast as the phone's sensor goes, if slower).
 * The accelerometer is raw (with gravity): the phone's mount angle is worked out from it later.
 * Axes are the phone's own: x — to the right of the screen, y — up the screen, z — out of the screen.
 */
class PhoneSensors(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var running: Triple<HandlerThread, Handler, Recorder>? = null

    @Synchronized fun start(s: Session) {
        stop()
        fun line(x: Sensor?) = x?.let { "${it.vendor} ${it.name}, " + tr("до", "up to") + " %.0f ".format(1e6 / periodUs(it)) + tr("Гц", "Hz") }
            ?: tr("нет", "none")
        s.report(tr("Датчики телефона", "Phone sensors"),
            tr("Акселерометр", "Accelerometer") + ": " + line(acc) + "\n" + tr("Гироскоп", "Gyroscope") + ": " + line(gyro))
        if (sm == null || (acc == null && gyro == null)) return
        val t = HandlerThread("sensors").apply { start() }
        val h = Handler(t.looper)
        val l = Recorder(s)
        acc?.let { sm.registerListener(l, it, periodUs(it), h) }
        gyro?.let { sm.registerListener(l, it, periodUs(it), h) }
        running = Triple(t, h, l)
    }

    @Synchronized fun stop() {
        running?.let { (t, h, l) ->
            sm?.unregisterListener(l)
            h.post { l.finish() }
            t.quitSafely()
            t.join(2000)
        }
        running = null
    }

    /** The requested sampling period: [TARGET_HZ], or the sensor's own limit if it is slower. */
    private fun periodUs(x: Sensor) = maxOf(1_000_000 / TARGET_HZ, x.minDelay)

    /**
     * Holds samples for [HOLD_MS] and writes them in time order: the two sensors' events come in
     * separate batches, so one sensor's sample may arrive after the other's later ones. Lives on its own thread.
     */
    private class Recorder(private val s: Session) : SensorEventListener {
        /** Sensor clock → wall clock, ns. Sensor timestamps are normally elapsedRealtimeNanos. */
        private val offsetNs = System.currentTimeMillis() * 1_000_000 - SystemClock.elapsedRealtimeNanos()
        private val t0Ns = s.startMs * 1_000_000
        private val pending = ArrayList<Sample>()
        private var lastWriteNs = 0L
        private var newestNs = 0L
        private val count = IntArray(2)
        private var firstNs = 0L

        private class Sample(val tNs: Long, val gyro: Boolean, val x: Float, val y: Float, val z: Float)

        override fun onSensorChanged(e: SensorEvent) {
            val g = e.sensor.type == Sensor.TYPE_GYROSCOPE
            val k = if (g) RAD_TO_DEG else 1f
            val t = wallNs(e)
            pending += Sample(t, g, e.values[0] * k, e.values[1] * k, e.values[2] * k)
            if (firstNs == 0L) firstNs = t
            count[if (g) 1 else 0]++
            if (t > newestNs) newestNs = t
            if (newestNs - lastWriteNs >= WRITE_EVERY_MS * 1_000_000) {
                lastWriteNs = newestNs
                write(newestNs - HOLD_MS * 1_000_000)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

        /** Everything left, plus the rates the phone actually gave. */
        fun finish() {
            write(Long.MAX_VALUE)
            val sec = (newestNs - firstNs) / 1e9
            if (sec > 1) s.report(tr("Датчики телефона, получено", "Phone sensors, received"),
                tr("Акселерометр", "Accelerometer") + ": %.0f ".format(count[0] / sec) + tr("Гц", "Hz") + "\n" +
                tr("Гироскоп", "Gyroscope") + ": %.0f ".format(count[1] / sec) + tr("Гц", "Hz"))
        }

        /** Writes samples up to [untilNs] in time order and keeps the rest. */
        private fun write(untilNs: Long) {
            pending.sortBy { it.tNs }
            val n = pending.indexOfFirst { it.tNs > untilNs }.let { if (it < 0) pending.size else it }
            if (n == 0) return
            val sb = StringBuilder(n * 36)
            for (i in 0 until n) {
                val p = pending[i]
                sb.fixed((p.tNs - t0Ns) / 1e6, 2).append(if (p.gyro) ",g," else ",a,")
                val dec = if (p.gyro) 2 else 3
                sb.fixed(p.x.toDouble(), dec).append(',').fixed(p.y.toDouble(), dec).append(',').fixed(p.z.toDouble(), dec).append('\n')
            }
            pending.subList(0, n).clear()
            s.sensors(sb)
        }

        /** The sample's own time when the clock base is the usual one, otherwise the time it arrived. */
        private fun wallNs(e: SensorEvent): Long {
            val age = SystemClock.elapsedRealtimeNanos() - e.timestamp
            return if (age in -1_000_000_000L..5_000_000_000L) e.timestamp + offsetNs else System.currentTimeMillis() * 1_000_000
        }
    }

    companion object {
        /** ~800 rows/s for both sensors: ~80 MB per hour, ~30 MB zipped. */
        private const val TARGET_HZ = 400
        private const val HOLD_MS = 200L
        private const val WRITE_EVERY_MS = 100L
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    }
}

/** Fixed-point without String.format: ~3000 numbers a second go through here. */
private fun StringBuilder.fixed(v: Double, dec: Int): StringBuilder {
    var scale = 1L
    repeat(dec) { scale *= 10 }
    val m = Math.round(v * scale)
    if (m < 0) append('-')
    val a = Math.abs(m)
    append(a / scale).append('.')
    val frac = (a % scale).toString()
    repeat(dec - frac.length) { append('0') }
    return append(frac)
}
