package com.obdscanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A GM module named by its own diagnostic address (\$1A B0), not by its CAN id: GM reuses an id for different
 * modules on different cars (a newer car keeps its brake module where an older one has nothing named). The log
 * is written by hand: a GM VIN, and one module on 24F that refuses the VIN probe but tells its address 28 (EBCM).
 */
class GmDiagAddressTest {
    private val log = """
        12:00:00.000 > ATSP6
        12:00:00.010 < OK
        12:00:00.020 > 0100
        12:00:00.090 < 7E8 06 41 00 BE 3F A8 13
        12:00:00.100 > ATDPN
        12:00:00.110 < 6
        12:00:00.120 > ATSH7DF
        12:00:00.130 < OK
        12:00:00.140 > 0100
        12:00:00.200 < 7E8 06 41 00 BE 3F A8 13
        12:00:00.210 > 0902
        12:00:00.300 < 7E8 10 14 49 02 01 31 47 31 | 7E8 21 4A 43 35 34 34 34 52 | 7E8 22 37 32 35 32 33 36 37
        12:00:01.000 > ATSH24F
        12:00:01.010 < OK
        12:00:01.020 > ATCRA64F
        12:00:01.030 < OK
        12:00:01.040 > 1A90
        12:00:01.100 < 64F 03 7F 1A 31
        12:00:01.110 > 1AB0
        12:00:01.170 < 64F 03 5A B0 28
    """.trimIndent()

    @Test
    fun moduleIsNamedByItsDiagnosticAddress() {
        CarDbTest.load()
        val store = MapStore()
        val r = runBlocking { replayRun(ReplayLog.parse(log), store, cycles = 1) }
        assertNull(r.error)
        assertEquals("1G1JC5444R7252367", r.link.vehicle.value.vin)
        // Asked once per GM module, at the module's own id.
        assertTrue(r.addressed.toString(), "24F" to "1AB0" in r.addressed)
        val m = r.link.scan.value.modules.single { it.req == 0x24F }
        assertEquals(0x28, m.diag)
        assertEquals(tr("EBCM (ABS)", "EBCM (ABS)"), m.name)
        // The report shows the address with the identification.
        val ident = r.reports.single { it.first.startsWith("GM: ") && "\$1A" in it.first }.second
        assertTrue(ident, ident.contains("[24F]") && ident.contains("1A B0"))

        // Next connection: the modules come from the saved list, the name with them.
        val again = runBlocking { replayRun(ReplayLog.parse(log), store, cycles = 1) }
        assertNull(again.error)
        val saved = again.link.scan.value.modules.single { it.req == 0x24F }
        assertEquals(0x28, saved.diag)
        assertEquals(tr("EBCM (ABS)", "EBCM (ABS)"), saved.name)
    }
}
