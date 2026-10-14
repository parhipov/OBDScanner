package com.obdscanner

import com.obdscanner.obd.Knock
import com.obdscanner.obd.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When a knock retard read with another model's layout can't be real (the Kia Sportage QL of 2026-10-14, 21 16 as an Elantra's). */
class KnockTest {
    private fun cyl(n: Int, v: Double?, text: String? = null) =
        Reading("7E0:21.16.CYL_$n", 0x7E0, "Cylinder $n", v, text, "°", role = Knock.ROLE)

    @Test
    fun theKiasCylinder4IsNoRetard() {
        val first = listOf(cyl(1, 12.0), cyl(2, 0.0), cyl(3, 0.0), cyl(4, 52.5))
        assertEquals("7E0:21.16.CYL_4", Knock.implausible(first)?.key)
        // A sample where cylinder 4 happens to read 0 would pass on its own: the request is rejected for the whole connection.
        assertNull(Knock.implausible(listOf(cyl(1, 11.25), cyl(2, 0.0), cyl(3, 0.0), cyl(4, 0.0))))
    }

    @Test
    fun aValueThatDoesntDecodeIsNoRetard() {
        assertEquals("7E0:21.16.CYL_1", Knock.implausible(listOf(cyl(1, null, "FF 01 00 00 52"), cyl(2, 0.0)))?.key)
    }

    @Test
    fun aRealRetardPasses() {
        // The CTS's worst under load: 6.7°; a signed layout may give a negative retard.
        assertNull(Knock.implausible(listOf(cyl(1, 6.7), cyl(2, 0.0), cyl(3, -3.0))))
    }

    @Test
    fun aBigRetardAtIdleIsNone() {
        val kia = listOf(cyl(1, 11.25), cyl(2, 0.0))
        assertEquals("7E0:21.16.CYL_1", Knock.atIdle(kia, rpm = 800.0, load = 25.0)?.key)
        assertNull(Knock.atIdle(kia, rpm = 3200.0, load = 90.0))                 // under load it may be real
        assertNull(Knock.atIdle(kia, rpm = null, load = null))                   // no Mode 01: nothing to tell by
        assertNull(Knock.atIdle(listOf(cyl(1, 0.75)), rpm = 800.0, load = 25.0)) // a small one at idle isn't counted
    }
}
