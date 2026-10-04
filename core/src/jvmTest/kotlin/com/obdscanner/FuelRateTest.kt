package com.obdscanner

import com.obdscanner.obd.FuelRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelRateTest {
    private fun rate(fuel: String?, ecu: Double? = null, gs: Double? = null, mg: Double? = null, maf: Double? = null,
                     map: Double? = null, iat: Double? = null, rpm: Double? = null, liters: Double? = null, cyl: Int? = null) =
        FuelRate.compute(fuel, ecu, gs, mg, maf, 1.0, map, iat, rpm, liters, cyl)

    @Test
    fun ecuRateWins() = assertEquals(1.2, rate("petrol", ecu = 1.2, maf = 30.0)!!.lph, 1e-9)

    @Test
    fun petrolFromMaf() {
        // 3.5 g/s at idle: 3.5 / 14.7 · 3600 / 745 = 1.15 L/h.
        assertEquals(1.15, rate("petrol", maf = 3.5)!!.lph, 0.01)
    }

    /** A diesel is lean: MAF with 14.7 would be several times too high — no figure rather than a wrong one. */
    @Test
    fun dieselNeverFromMaf() {
        assertNull(rate("diesel", maf = 20.0))
        // 10 mg/stroke, 4 cylinders, 800 rpm: 10·4·400·60 mg/h = 0.96 kg/h → 1.15 L/h at 832 g/L.
        assertEquals(1.154, rate("diesel", maf = 20.0, mg = 10.0, rpm = 800.0, cyl = 4)!!.lph, 0.01)
    }

    /** No MAF (Solaris, Polo): from MAP. 1.6 L at idle, 30 kPa, 30 °C, 800 rpm → ~3.1 g/s air → ~1 L/h. */
    @Test
    fun speedDensity() {
        val r = rate("petrol", map = 30.0, iat = 30.0, rpm = 800.0, liters = 1.6)!!
        assertEquals(1.03, r.lph, 0.05)
        assertNull(rate("petrol", map = 30.0, iat = 30.0, rpm = 800.0))
    }
}
