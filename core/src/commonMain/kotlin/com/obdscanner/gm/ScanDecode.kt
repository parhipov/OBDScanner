package com.obdscanner.gm

import com.obdscanner.util.format
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * What the bytes of a polled block are. Every candidate field — a byte, signed or not, or a big-endian byte pair —
 * is set against the standard parameters read in the same poll cycle; a near-straight line «parameter = a·x + b»
 * gives both the meaning and the formula (the app's take on utils/scan_decode.py). A close match is a hint, not
 * a proof: oil temperature follows coolant just as closely, so the screen says «похоже на».
 */
object ScanDecode {
    /** One poll of a block: its bytes after the identifier, and the standard parameters then (key → value). */
    class Sample(val bytes: IntArray, val refs: Map<String, Double>)

    /**
     * A field at [offset], [size] 1 or 2 bytes. Matched: [ref] = [a]·x + [b] with correlation [r] over [n]
     * samples; null [ref] — the byte varies and nothing matches it. x runs [min]…[max] with [distinct] values.
     */
    class Field(val offset: Int, val size: Int, val signed: Boolean, val ref: String?, val r: Double, val a: Double, val b: Double,
                val n: Int, val min: Int, val max: Int, val distinct: Int) {
        val id get() = "$offset.$size${if (signed) "s" else ""}"

        /**
         * The field *is* [ref], scaled: the factor is a round one (1/4, 0.1, 100/255…) and the line near-perfect. Otherwise
         * the field only moves with [ref] — rpm, load, MAF and pedal all rise together — and must not take its name.
         */
        val same get() = ref != null && abs(r) >= SAME_R && nice(a) != null

        /** The value from a block's bytes; null — the answer is too short. */
        fun value(bytes: IntArray): Double? = raw(bytes, offset, size, signed)?.let { a * it + b }

        /** «(C·256+D) × 1/4 − 40»: the bytes by letter (A — the first after the identifier), the factor rounded when close. */
        fun formula(): String {
            val x = if (size == 1) letter(offset) else "(${letter(offset)}·256+${letter(offset + 1)})"
            val sx = if (signed) "±$x" else x
            if (ref == null) return sx
            val k = nice(a)
            val factor = when {
                k == null -> " × " + num(a, 5)
                k.second == "1" -> ""
                else -> " × " + k.second
            }
            val bb = if (abs(b) < 0.005 * maxOf(1.0, abs(a) * maxOf(abs(min), abs(max)))) "" else (if (b < 0) " − " else " + ") + num(abs(b), 3)
            return sx + factor + bb
        }
    }

    class Result(val matched: List<Field>, val varying: List<Field>, val constant: Int, val samples: Int) {
        fun field(id: String) = (matched + varying).firstOrNull { it.id == id }
    }

    /** A byte of the answer by letter, as formulas write it: A — the first byte after the identifier. */
    fun letter(i: Int) = if (i < 26) ('A' + i).toString() else "b$i"

    /** Polls before a block is judged: fewer give chance lines. */
    const val MIN_SAMPLES = 20
    /** |r| for a match at all: below it two parameters merely drift together (warm-up, speed and rpm). */
    const val MIN_R = 0.95
    /** |r| for «this is that parameter» (with a round factor, [Field.same]). */
    const val SAME_R = 0.98

    private val FIELDS = listOf(1 to false, 1 to true, 2 to false, 2 to true)

    fun raw(bytes: IntArray, offset: Int, size: Int, signed: Boolean): Int? {
        if (offset + size > bytes.size) return null
        var v = if (size == 1) bytes[offset] else bytes[offset] * 256 + bytes[offset + 1]
        if (signed && v >= 1 shl (8 * size - 1)) v -= 1 shl (8 * size)
        return v
    }

    fun decode(samples: List<Sample>, refKeys: Collection<String>): Result? {
        if (samples.size < MIN_SAMPLES) return null
        val size = samples.maxOf { it.bytes.size }
        if (size == 0) return null
        val constant = (0 until size).filter { off -> samples.mapNotNull { it.bytes.getOrNull(off) }.toSet().size <= 1 }.toSet()
        // The parameters as columns (NaN — not read in that cycle): up to 24 blocks × hundreds of polls, every few seconds.
        val n = samples.size
        val refs = refKeys.filter { k -> samples.count { k in it.refs } >= MIN_SAMPLES }
            .associateWith { k -> DoubleArray(n) { samples[it].refs[k] ?: Double.NaN } }
        val cands = ArrayList<Field>()
        val xs = DoubleArray(n)
        for ((sz, signed) in FIELDS) for (off in 0..size - sz) {
            // A pair with a constant byte follows the other byte alone: the byte is the field.
            if ((off until off + sz).any { it in constant }) continue
            val seen = HashSet<Int>()
            var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
            for (i in 0 until n) {
                val x = raw(samples[i].bytes, off, sz, signed)
                xs[i] = x?.toDouble() ?: Double.NaN
                if (x != null) { seen += x; if (x < lo) lo = x; if (x > hi) hi = x }
            }
            if (seen.size < 4) continue
            var best: Field? = null
            for ((k, ys) in refs) {
                val f = fit(xs, ys) ?: continue
                if (best == null || abs(f.r) > abs(best.r)) best = Field(off, sz, signed, k, f.r, f.a, f.b, f.n, lo, hi, seen.size)
            }
            best?.let { cands += it }
        }
        // The surest first; a field whose bytes a surer one explained already is left out. A tie (within 0.005) goes to
        // the byte pair: a 16-bit value whose high byte barely moves fits its low byte read as signed just as well.
        val covered = HashSet<Int>()
        val matched = ArrayList<Field>()
        for (f in cands.sortedWith(compareBy<Field>({ -floor(abs(it.r) * 200) }, { -it.size }, { it.signed }, { -abs(it.r) }))) {
            if (abs(f.r) < MIN_R) break
            val span = (f.offset until f.offset + f.size).toSet()
            if (span.any { it in covered }) continue
            covered += span
            matched += f
        }
        val varying = (0 until size).filter { it !in covered && it !in constant }.mapNotNull { off ->
            val vals = samples.mapNotNull { it.bytes.getOrNull(off) }
            if (vals.isEmpty()) null else Field(off, 1, false, null, 0.0, 1.0, 0.0, vals.size, vals.min(), vals.max(), vals.toSet().size)
        }
        return Result(matched.sortedBy { it.offset }, varying, constant.size, samples.size)
    }

    private class Fit(val r: Double, val a: Double, val b: Double, val n: Int)

    /** y = a·x + b over the samples where both are there (NaN — missing); null when too few or either side doesn't vary. */
    private fun fit(xs: DoubleArray, ys: DoubleArray): Fit? {
        var n = 0; var sx = 0.0; var sy = 0.0
        for (i in xs.indices) if (!xs[i].isNaN() && !ys[i].isNaN()) { n++; sx += xs[i]; sy += ys[i] }
        if (n < MIN_SAMPLES) return null
        val mx = sx / n; val my = sy / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in xs.indices) if (!xs[i].isNaN() && !ys[i].isNaN()) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        if (sxx == 0.0 || syy == 0.0) return null
        val a = sxy / sxx
        return Fit(sxy / sqrt(sxx * syy), a, my - a * mx, n)
    }

    /** The round factor a fitted one stands for (1/4, 1/128, 100/255…) when within 4 %, as (value, text). */
    private fun nice(a: Double): Pair<Double, String>? {
        val cands = listOf(1.0 to "1", 2.0 to "2", 4.0 to "4", 10.0 to "10", 0.5 to "1/2", 0.25 to "1/4", 0.125 to "1/8",
            1.0 / 16 to "1/16", 1.0 / 32 to "1/32", 1.0 / 64 to "1/64", 1.0 / 128 to "1/128", 1.0 / 256 to "1/256",
            0.1 to "0.1", 0.01 to "0.01", 0.001 to "0.001", 100.0 / 255 to "100/255", 100.0 / 128 to "100/128", 3.6 to "3.6", 1 / 3.6 to "1/3.6")
        val (v, t) = cands.minBy { abs(abs(a) - it.first) / it.first }
        if (abs(abs(a) - v) / v >= 0.04) return null
        return if (a > 0) v to t else -v to "−$t"
    }

    /** [v] with [digits] significant digits, trailing zeros dropped (no %g: the browser's format has none). */
    private fun num(v: Double, digits: Int): String {
        if (v == v.roundToLong().toDouble()) return v.roundToLong().toString()
        val mag = floor(log10(abs(v))).toInt()
        val decimals = (digits - 1 - mag).coerceIn(0, 6)
        val s = "%.${decimals}f".format(v)
        return if (decimals == 0) s else s.trimEnd('0').trimEnd('.', ',')
    }
}
