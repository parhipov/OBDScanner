package com.obdscanner

import com.obdscanner.obd.DtcAnatomy
import com.obdscanner.obd.DtcDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class DtcDbTest {
    companion object {
        @BeforeClass @JvmStatic
        fun setUp() {
            val files = File("src/main/assets/dtc").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            DtcDb.load(files.map { it.readText() })
        }
    }

    @Test
    fun loadsEverySet() {
        assertTrue("codes: ${DtcDb.size}", DtcDb.size > 9000)
        val p0171 = DtcDb.find("P0171", null)
        assertNotNull(p0171)
        assertEquals("generic", p0171!!.set)
        assertTrue(p0171.causes.isNotEmpty() && p0171.causes.all { it.en != null })
    }

    /** A P1 code means something different on every make: never the generic text, the make's own when known. */
    @Test
    fun manufacturerCodesByMake() {
        assertNull(DtcDb.find("P1031", null))
        assertNotNull(DtcDb.find("P1031", "gm"))
        assertTrue(DtcDb.others("P1031").isNotEmpty())
        // Generic codes still resolve with a make picked.
        assertEquals("generic", DtcDb.find("P0300", "gm")?.set)
    }

    /** Escalade 2011 suspension module: GM redefines C0585/C0590 (Magnetic Ride damper circuits), the generic text is wrong there. */
    @Test
    fun gmHandWrittenChassisCodes() {
        val c = DtcDb.find("C0585", "gm")
        assertEquals("gm", c?.set)
        assertEquals("Left Rear Damper Actuator Circuit", c?.title?.en)
        assertTrue(c!!.causes.isNotEmpty() && c.symptoms.isNotEmpty())
        assertEquals("generic", DtcDb.find("C0585", null)?.set)
        // The imported GM set still answers for its own codes.
        assertNotNull(DtcDb.find("P1031", "gm"))
    }

    @Test
    fun anatomy() {
        assertTrue(DtcAnatomy.isGeneric("P0300") && DtcAnatomy.isGeneric("P2096") && DtcAnatomy.isGeneric("P3400") && DtcAnatomy.isGeneric("U0100"))
        assertFalse(DtcAnatomy.isGeneric("P1031") || DtcAnatomy.isGeneric("P3000") || DtcAnatomy.isGeneric("B1517") || DtcAnatomy.isGeneric("U1000"))
        assertNotNull(DtcAnatomy.subsystem("P0301"))
        assertNotNull(DtcAnatomy.subsystem("U0186"))
    }

    /** CTS BCM: "B1517 03" (GM symptom byte), "C0035 5A" — unknown value, the category still explains it. */
    @Test
    fun failureTypes() {
        assertEquals("Voltage below threshold", DtcDb.failureType(0x03, gm = true)?.en)
        assertTrue(DtcDb.failureType(0x5A, gm = true)?.en!!.startsWith("Category: Algorithm"))
        assertEquals("Circuit short to ground", DtcDb.failureType(0x11, gm = false)?.en)
        assertTrue(DtcDb.failureType(0x1F, gm = false) != null)
    }
}
