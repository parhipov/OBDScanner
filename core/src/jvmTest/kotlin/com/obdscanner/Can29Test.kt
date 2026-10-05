package com.obdscanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A car on 29-bit CAN (ATSP7): its ECUs answer only the adapter's own functional header (18 DB 33 F1), as in a
 * mail session from app 2.0 where "ATSH7DF" after 0100 silenced both ECUs. The log is written by hand: what such
 * a car says when asked the standard way, nothing to any other header. A GM VIN on purpose — the make with the
 * most module and parameter requests, all of them to 11-bit ids.
 */
class Can29Test {
    private val log = """
        12:00:00.000 > ATSP7
        12:00:00.010 < OK
        12:00:00.020 > 0100
        12:00:00.090 < 18 DA F1 10 06 41 00 BE 3F A8 13 | 18 DA F1 18 06 41 00 98 18 80 03
        12:00:00.100 > ATDPN
        12:00:00.110 < 7
        12:00:00.120 > ATDP
        12:00:00.130 < ISO 15765-4 (CAN 29/500)
        12:00:00.140 > 0900
        12:00:00.200 < 18 DA F1 10 06 49 00 54 40 00 00
        12:00:00.210 > 0902
        12:00:00.300 < 18 DA F1 10 10 14 49 02 01 31 47 31 | 18 DA F1 10 21 4A 43 35 34 34 34 52 | 18 DA F1 10 22 37 32 35 32 33 36 37
        12:00:00.310 > 03
        12:00:00.380 < 18 DA F1 10 02 43 00
        12:00:00.390 > 010C
        12:00:00.450 < 18 DA F1 10 04 41 0C 1A F8 | 18 DA F1 18 04 41 0C 1A F8
        12:00:00.460 > 010D
        12:00:00.520 < 18 DA F1 10 03 41 0D 2A | 18 DA F1 18 03 41 0D 2A
    """.trimIndent()

    @Test
    fun standardObdWorksAndNoHeaderIsSet() {
        CarDbTest.load()
        val r = runBlocking { replayRun(ReplayLog.parse(log), MapStore(), cycles = 3, ops = true) }
        assertNull(r.error)
        // Every request went out with the protocol's own header: no ATSH at all.
        assertTrue(r.requests.filter { it.uppercase().startsWith("ATSH") }.toString(), r.requests.none { it.uppercase().startsWith("ATSH") })
        assertTrue(r.addressed.toString(), r.addressed.all { it.first == "-" })
        // Module search, the manufacturer parameters and the answer-based recognition ask 11-bit ids: none sent.
        assertTrue(r.requests.toString(), r.requests.none { it.uppercase().replace(" ", "").let { c -> c.startsWith("22") || c.startsWith("1A") || c.startsWith("3E") || c.startsWith("19") || c.startsWith("A9") } })
        val v = r.link.vehicle.value
        assertEquals("1G1JC5444R7252367", v.vin)
        assertTrue(v.ecus.keys.map { "%X".format(it) }.toString(), 0x18DAF110 in v.ecus && 0x18DAF118 in v.ecus)
        assertEquals(1726.0, r.link.readings.value["18DAF110:01.0C"]?.value)
        assertEquals(42.0, r.link.readings.value["18DAF110:01.0D"]?.value)
        // The buttons say why, instead of breaking the adapter's header.
        assertTrue(v.gmDtcStatus, v.gmDtcStatus.contains("29"))
        assertTrue(r.link.scan.value.status, r.link.scan.value.status.contains("29"))
    }
}
