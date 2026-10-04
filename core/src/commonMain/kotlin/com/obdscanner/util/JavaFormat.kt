package com.obdscanner.util

import kotlin.math.abs

/**
 * java.util.Formatter for what the app's format strings use — %s %d %c %X %x %f and %%, the '-' and '0' flags,
 * width and precision — for the browser, where there is no String.format. %f rounds half up on the shortest
 * decimal form of the number, as Java does ("%.1f" of 0.15 is "0.2", not JavaScript's "0.1"). Common code so
 * the JVM tests can check it against the real String.format (JavaFormatTest).
 */
private val SPEC = Regex("""%([-0]*)(\d+)?(?:\.(\d+))?([sdcXxf%])""")

internal fun javaFormat(pattern: String, args: Array<out Any?>, point: Char): String {
    var next = 0
    return SPEC.replace(pattern) { m ->
        val flags = m.groupValues[1]
        val width = m.groupValues[2].toIntOrNull() ?: 0
        val precision = m.groupValues[3].toIntOrNull()
        val conv = m.groupValues[4]
        if (conv == "%") return@replace "%"
        val arg = args.getOrNull(next++)
        val text = when (conv) {
            "s" -> javaString(arg)
            "c" -> (arg as? Char)?.toString() ?: (arg as? Number)?.toInt()?.toChar()?.toString() ?: "null"
            "d" -> (arg as? Number)?.toLong()?.toString() ?: "null"
            "X", "x" -> {
                // Int first: in the browser every number but Long passes "is Byte" too (0x7E8 came out "0E8").
                val v = when (arg) {
                    is Long -> arg
                    is Int -> arg.toLong() and 0xFFFFFFFFL
                    is Short -> arg.toLong() and 0xFFFFL
                    is Byte -> arg.toLong() and 0xFFL
                    else -> null
                }
                val h = when {
                    v == null -> "null"
                    v < 0 -> v.toULong().toString(16)
                    else -> v.toString(16)
                }
                if (conv == "X") h.uppercase() else h
            }
            else -> (arg as? Number)?.toDouble()?.let { fixed(it, precision ?: 6, point) } ?: "null"
        }
        val pad = width - text.length
        when {
            pad <= 0 -> text
            '-' in flags -> text + " ".repeat(pad)
            '0' in flags && conv != "s" && conv != "c" ->
                if (text.startsWith("-")) "-" + "0".repeat(pad) + text.substring(1) else "0".repeat(pad) + text
            else -> " ".repeat(pad) + text
        }
    }
}

/**
 * String.valueOf: a whole Double keeps its ".0" like on the JVM. Whole numbers first: the browser can't tell
 * 5 from 5.0, and an Int is what the app passes to %s.
 */
private fun javaString(arg: Any?): String = when (arg) {
    null -> "null"
    is Int, is Long, is Short, is Byte -> arg.toString()
    is Double ->if (arg.isFinite() && arg == kotlin.math.floor(arg) && abs(arg) < 1e7) "${arg.toLong()}.0" else arg.toString()
    is Float -> javaString(arg.toDouble())
    else -> arg.toString()
}

/** [v] with [decimals] digits after the point, rounded half up on its shortest decimal form. */
internal fun fixed(v: Double, decimals: Int, point: Char): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    // Java keeps the sign of a negative number that rounds to zero ("-0.0").
    val neg = v < 0.0 || (v == 0.0 && 1.0 / v < 0.0)
    // The digits and where the point goes: value = 0.<digits> × 10^pointAt ("1.5e-7", "123.45", "1.0E7").
    val s = abs(v).toString().lowercase()
    val mant = s.substringBefore('e')
    val exp = if ('e' in s) s.substringAfter('e').removePrefix("+").toInt() else 0
    val ip = mant.substringBefore('.')
    val all = ip + mant.substringAfter('.', "")
    val lead = all.length - all.trimStart('0').length
    var digits = all.substring(lead)
    var pointAt = ip.length + exp - lead
    if (digits.isEmpty()) { digits = "0"; pointAt = 1 }
    // value × 10^decimals = 0.<digits> × 10^keep: its whole part and the digit that decides the rounding.
    val keep = pointAt + decimals
    val (whole, next) = when {
        keep < 0 -> "0" to '0'
        keep == 0 -> "0" to digits[0]
        keep >= digits.length -> digits + "0".repeat(keep - digits.length) to '0'
        else -> digits.substring(0, keep) to digits[keep]
    }
    val n = (if (next >= '5') increment(whole) else whole).padStart(decimals + 1, '0')
    val intPart = n.substring(0, n.length - decimals).trimStart('0').ifEmpty { "0" }
    val body = if (decimals > 0) intPart + point + n.substring(n.length - decimals) else intPart
    return if (neg) "-$body" else body
}

private fun increment(digits: String): String {
    val c = digits.toCharArray()
    var i = c.size - 1
    while (i >= 0) {
        if (c[i] == '9') {
            c[i] = '0'
            i--
        } else {
            c[i] = c[i] + 1
            return c.concatToString()
        }
    }
    return "1" + c.concatToString()
}
