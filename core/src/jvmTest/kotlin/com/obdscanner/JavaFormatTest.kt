package com.obdscanner

import com.obdscanner.util.javaFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/** The browser's String.format ([javaFormat]) against the real one, for every pattern shape the app uses. */
class JavaFormatTest {
    private fun same(pattern: String, vararg args: Any?) {
        assertEquals("$pattern ${args.toList()}", String.format(Locale.US, pattern, *args), javaFormat(pattern, args, '.'))
        assertEquals("$pattern ${args.toList()} (ru)", String.format(Locale("ru"), pattern, *args), javaFormat(pattern, args, ','))
    }

    @Test
    fun integersAndText() {
        val r = Random(1)
        repeat(2000) {
            val n = r.nextInt(-70000, 70000)
            same("%02X %03X %04X %X %d %05d %04d", n and 0xFF, n and 0xFFF, n and 0xFFFF, n, n, n, n)
            same("[%03X] TID %02X: %s", n and 0x7FF, n and 0xFF, "41 0C 1A F8")
        }
        same("%X", -1)
        same("%X", -1L)
        same("%c%d%X%02X", 'P', 0, 3, 0x01)
        same("  %s  %-8s  %s|%-28s|%-36s|%-44s|%-26s", "7E8", "abc", null, "Misfire", "x", "", "long text that is longer than the column")
        same("100%% %s", true)
        same("%s %s %s", 1.0, 0.5, 12345678.0)
    }

    @Test
    fun decimalsRoundLikeJava() {
        val r = Random(2)
        val tricky = listOf(0.0, -0.0, 0.05, 0.15, 0.25, 0.35, 0.45, 1.005, 2.675, 1.45, -1.45, 0.049999, 99.95, 999.995,
            1e-7, 1.5e-5, 123456789.125, 9.999999, -0.04, 0.5, 1.5, 2.5, -2.5, 1e21, 13.8, 14.35)
        for (v in tricky) for (p in 0..4) same("%.${p}f", v)
        repeat(20000) {
            val v = when (it % 4) {
                0 -> r.nextDouble(-200.0, 200.0)
                1 -> (r.nextInt(-100000, 100000) / 1000.0)
                2 -> (r.nextInt(-10000, 10000) / 100.0) + 0.005
                else -> r.nextDouble(0.0, 1.0) * Math.pow(10.0, r.nextInt(-6, 9).toDouble())
            }
            same("%.${it % 5}f", v)
        }
        same("%6.1f Hz %5.1f", 12.34, 1.05)
        same("%.1fV", 13.849999)
    }
}
