package com.obdscanner

import com.obdscanner.gm.ScanDecode
import com.obdscanner.gm.ScanParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A block laid out the way Toyota \$21 lists are, polled with the standard PIDs of the same cycles: a constant
 * header byte, rpm × 4 in a byte pair, coolant + 40 in a byte, a flag byte that follows nothing, rpm / 13 in a byte.
 * The decoder has to name the first two with their formulas, leave the flags as «varies, no match», and not call
 * the last one rpm.
 */
class ScanDecodeTest {
    private fun samples(n: Int): List<ScanDecode.Sample> {
        val rnd = Random(7)
        return (0 until n).map { i ->
            val rpm = 750.0 + 2500 * ((i * 37) % 100) / 100.0
            val ect = 20.0 + i * 70.0 / n
            val raw = (rpm * 4).toInt()
            val bytes = intArrayOf(0x01, raw shr 8, raw and 0xFF, (ect + 40).toInt(), if (rnd.nextBoolean()) 0x80 else 0x00, (rpm / 13).toInt())
            ScanDecode.Sample(bytes, mapOf("01.0C" to rpm, "01.05" to (ect + 40).toInt() - 40.0, "01.0D" to rnd.nextDouble() * 100))
        }
    }

    @Test
    fun namesTheFieldsWithFormulas() {
        val res = ScanDecode.decode(samples(60), listOf("01.0C", "01.05", "01.0D"))
        assertNotNull(res)
        res!!
        val rpm = res.matched.single { it.ref == "01.0C" && it.offset == 1 }
        assertEquals(1, rpm.offset)
        assertEquals(2, rpm.size)
        assertTrue(rpm.r > 0.999)
        assertEquals("(B·256+C) × 1/4", rpm.formula())
        assertTrue(rpm.same)
        // rpm / 13 follows rpm perfectly, but 13 is no factor a maker uses: it moves with rpm, it isn't rpm.
        val along = res.matched.single { it.offset == 5 }
        assertEquals("01.0C", along.ref)
        assertTrue(!along.same)
        val ect = res.matched.single { it.ref == "01.05" }
        assertEquals(3, ect.offset)
        assertEquals("D − 40", ect.formula())
        assertEquals(listOf(4), res.varying.map { it.offset })
        assertEquals(1, res.constant)
        assertEquals(1000.0, rpm.value(intArrayOf(0x01, 0x0F, 0xA0, 0, 0))!!, 0.5)
    }

    @Test
    fun waitsForEnoughPolls() {
        assertNull(ScanDecode.decode(samples(ScanDecode.MIN_SAMPLES - 1), listOf("01.0C")))
    }

    @Test
    fun savedFieldSurvivesTheStore() {
        val p = ScanParam(0x7E0, 0x7E8, "21", 0x01, 1, 2, false, 0.25, 0.0, "Обороты · 7E0 21 01", "об/мин", 0)
        assertEquals(p, ScanParam.decode(p.encode()))
        assertEquals("7E0:21.0001@1.2", p.key)
    }
}
