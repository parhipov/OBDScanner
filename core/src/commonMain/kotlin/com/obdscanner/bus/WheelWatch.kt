package com.obdscanner.bus

import com.obdscanner.car.BusFrameSpec
import com.obdscanner.elm.Obd
import com.obdscanner.obd.Reading
import com.obdscanner.tr
import com.obdscanner.util.IOException
import com.obdscanner.util.format
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The wheels' own speeds in «Контроль безопасности движения». The ABS module sends them on the bus by itself (the
 * make's `frames`, [BusFrameSpec]); the app only listens: a filter that passes those frames, ATMA for a window
 * between polls, then the adapter back to OBD. Nothing is sent to the car.
 *
 * The same id means another thing on another make, and many cars don't let this traffic out to the socket at all, so
 * every car checks itself ([check]): the wheels' mean against the OBD speed while driving. Until they agree the values
 * are recorded (the report can check them over the whole drive) but not shown; when they don't, when nothing is heard
 * or the adapter can't listen, listening stops for the rest of the drive and the mode only polls. [known] — this car
 * agreed on an earlier drive: shown at once, still checked.
 */
class WheelWatch(private val frames: List<BusFrameSpec>, known: Boolean, private val note: (String) -> Unit) {
    enum class State { CHECKING, OK, OFF }

    var state = if (known) State.OK else State.CHECKING
        private set

    private val ids = frames.map { it.id }
    /** The bits every id shares, as the adapter's mask: 348 and 34A → 348 / 7FD, nothing else of the CTS passes. */
    private val mask = ids.fold(0x7FF) { m, id -> m and (id xor ids.first()).inv() } and 0x7FF
    private val filter = ids.first() and mask
    private val idsText = ids.joinToString("/") { "%03X".format(it) }

    private var windows = 0
    private var heardAny = false
    private var fullInRow = 0
    private val ratios = ArrayList<Double>()
    private var firstAt = 0L
    private var lastAt = 0L

    /** One window of [ms]: the frames heard, decoded (each at the time its line came); empty — nothing came or listening is off. */
    suspend fun listen(o: Obd, ms: Long): List<Reading> {
        if (state == State.OFF) return emptyList()
        // Unfiltered the whole bus comes: the CTS's clone overflowed every ~0.1 s and dropped the link in a 9 s ATMA
        // (2026-10-05) — no filter, no listening.
        var back = true
        val heard = try {
            val set = listOf("ATCAF0", "ATCF%03X".format(filter), "ATCM%03X".format(mask)).map { o.at(it) }
            if (set.all { it.isOk }) o.elm.listen("ATMA", ms) else null
        } catch (e: IOException) {
            // The link dropped while listening: the session reconnects and goes on (this watch stays, CarLink isn't
            // reset), so not again on this connection — else every window could take the link down once more.
            off("the link dropped while listening (${e.message})")
            throw e
        } finally {
            withContext(NonCancellable) { back = restore(o) }
        }
        heard ?: return off("the adapter refused the filter (ATCAF0 / ATCF / ATCM)")
        // The CTS's clone hung in a window after 176 good ones (2026-10-21): no prompt after the stop character, no
        // answer to ATCAF1, Bluetooth dropped 10 s later and it wasn't back in a minute. The window itself ends without
        // an error, so the drop comes later, in a poll — the same as a drop in the window: not again on this connection.
        val hung = when {
            heard.stuck -> "the adapter didn't come back after a window (no prompt after the stop character)"
            !back -> "the adapter didn't confirm ATCAF1 after a window"
            else -> null
        }
        windows++
        if (heard.lines.any { it.line == "?" }) return off("the adapter can't listen (ATMA: ?)")
        if (heard.ended) {
            if (++fullInRow >= FULL_IN_ROW) return off("the adapter stops by itself ($fullInRow windows in a row: ${heard.lines.lastOrNull()?.line})")
        } else fullInRow = 0
        val out = ArrayList<Reading>()
        for (h in heard.lines) {
            val f = BusSniffer.parse(listOf(h.line)).firstOrNull() ?: continue
            val spec = frames.firstOrNull { it.id == f.id } ?: continue
            for (s in spec.signals) {
                val v = s.fmt.decode(f.data)?.first ?: continue
                out += Reading(Reading.key(f.id, SOURCE + s.id), f.id, s.name, v, null, s.unit, s.decimals, time = h.atMs, role = s.role)
            }
        }
        if (hung != null) {
            off(hung)
            return out
        }
        if (out.isNotEmpty()) heardAny = true
        else if (!heardAny && windows >= SILENT_WINDOWS) return off("no $idsText in $windows windows")
        return out
    }

    /**
     * After a window: the wheels heard within [PAIR_MS] of the OBD speed [obd] against it. Counted only while both are
     * at least [CHECK_KMH] (slower, the OBD's 1 km/h steps and the speedometer's own error say nothing) — [CHECK_N]
     * pairs over [CHECK_SPAN_MS], then the median of wheels / OBD within [CHECK_TOL] of 1. True — the state changed.
     */
    fun check(wheels: List<Reading>, obd: Reading?): Boolean {
        val speed = obd?.value ?: return false
        if (state == State.OFF) return false
        val near = wheels.filter { abs(it.time - obd.time) <= PAIR_MS }.mapNotNull { it.value }
        if (near.isEmpty()) return false
        val mean = near.average()
        if (mean < CHECK_KMH || speed < CHECK_KMH) return false
        ratios += mean / speed
        if (firstAt == 0L) firstAt = obd.time
        lastAt = obd.time
        if (ratios.size < CHECK_N || lastAt - firstAt < CHECK_SPAN_MS) return false
        val med = ratios.sorted()[ratios.size / 2]
        val agree = abs(med - 1) <= CHECK_TOL
        return when {
            agree && state == State.CHECKING -> {
                state = State.OK
                note("wheels: $idsText agree with the OBD speed, wheels / OBD %.3f over %d pairs".format(med, ratios.size))
                true
            }
            !agree -> {
                off("$idsText don't match the OBD speed: wheels / OBD %.3f over %d pairs".format(med, ratios.size))
                true
            }
            else -> false
        }
    }

    /** Why listening stopped, in the session log. */
    private fun off(why: String): List<Reading> {
        state = State.OFF
        note("wheels: off — $why")
        return emptyList()
    }

    /**
     * Back to OBD: auto-formatting on and the automatic receive filter (ATAR clears ATCF / ATCM: the Focus II of
     * 2026-10-04 answered 7E8 right after one). A "?" to ATCAF1 — the stop character was left in the chip's line
     * buffer ([com.obdscanner.elm.Elm327.listen]): once more. Our filter is unknown to [Obd]: the next module
     * request sets its own again. False — ATCAF1 not confirmed.
     */
    private suspend fun restore(o: Obd): Boolean {
        var ok = false
        repeat(3) { if (!ok) ok = runCatching { o.at("ATCAF1").isOk }.getOrDefault(false) }
        runCatching { o.at("ATAR") }
        o.forgetRouting()
        if (!ok) note("wheels: WARNING — ATCAF1 not confirmed")
        return ok
    }

    companion object {
        /** A wheel's reading key: "348:bus.FL". */
        const val SOURCE = "bus."
        /** Windows with nothing heard before listening stops (a car whose socket doesn't carry this traffic). */
        const val SILENT_WINDOWS = 3
        /** Windows in a row the adapter stopped by itself ("BUFFER FULL") before listening stops. */
        const val FULL_IN_ROW = 3
        const val PAIR_MS = 800L
        const val CHECK_KMH = 20.0
        const val CHECK_N = 20
        const val CHECK_SPAN_MS = 15_000L
        /** Tyre wear and the speedometer's own offset are a few %; another make's frame is off by far more. */
        const val CHECK_TOL = 0.08

        /** «Задние быстрее передних», %: the rear axle's mean against the front's over [wheels]; null — not both, or too slow. */
        fun axleSlip(wheels: List<Reading>): Reading? {
            fun mean(roles: Set<String>) = wheels.filter { it.role in roles }.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.average()
            val front = mean(setOf("wheel_fl", "wheel_fr")) ?: return null
            val rear = mean(setOf("wheel_rl", "wheel_rr")) ?: return null
            if (front < 5) return null
            return Reading(Reading.key(0, "calc.axleSlip"), 0, tr("Задние быстрее передних", "Rear faster than front"),
                (rear / front - 1) * 100, null, "%", 0, time = wheels.maxOf { it.time })
        }
    }
}
