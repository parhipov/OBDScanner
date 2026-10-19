package com.obdscanner

import com.obdscanner.car.CarDb
import com.obdscanner.elm.CanParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J1850 (old US GM/Chrysler on VPW, Ford on PWM): tried last in the search, first for a picked car of that protocol;
 * standard OBD only. No session from such a car yet: the car below is written by hand in the format of frames an
 * owner logged with an ELM327 (their CRC: J1850ParseTest).
 */
class J1850Test {
    /** A VPW message the way the ELM shows it with headers on: the bytes and their CRC. */
    private fun vpw(hex: String): String {
        val b = hex.split(' ').map { it.toInt(16) }
        return hex + " %02X".format(CanParser.j1850Crc(b))
    }

    private val log = """
        12:00:00.000 > ATSP2
        12:00:00.010 < OK
        12:00:00.020 > 0100
        12:00:00.150 < ${vpw("48 6B 10 41 00 BE 3E B8 11")}
        12:00:00.160 > ATDPN
        12:00:00.170 < 2
        12:00:00.180 > ATDP
        12:00:00.190 < SAE J1850 VPW
        12:00:00.200 > 0900
        12:00:00.300 < ${vpw("48 6B 10 49 00 01 40 00 00 00")}
        12:00:00.310 > 0902
        12:00:00.500 < ${vpw("48 6B 10 49 02 01 00 00 00 31")} | ${vpw("48 6B 10 49 02 02 47 4E 45 4B")} | ${vpw("48 6B 10 49 02 03 31 33 5A 30")} | ${vpw("48 6B 10 49 02 04 33 52 30 30")} | ${vpw("48 6B 10 49 02 05 30 30 30 30")}
        12:00:00.510 > 03
        12:00:00.600 < ${vpw("48 6B 10 43 01 33 00 00 00 00")}
        12:00:00.610 > 010C
        12:00:00.700 < ${vpw("48 6B 10 41 0C 0B B8")}
        12:00:00.710 > 010D
        12:00:00.800 < ${vpw("48 6B 10 41 0D 28")}
    """.trimIndent()

    /** Nothing answers on CAN or K-line: found at the end of the search, standard OBD read, nothing addressed. */
    @Test
    fun foundLastStandardObdOnly() {
        CarDbTest.load()
        val r = runBlocking { replayRun(ReplayLog.parse(log), MapStore(), cycles = 3, ops = true) }
        assertNull(r.error)
        val tried = r.requests.map { it.uppercase() }.filter { it.startsWith("ATSP") }.distinct()
        assertEquals(listOf("ATSP6", "ATSP7", "ATSP4", "ATSP5", "ATSP3", "ATSP8", "ATSP9", "ATSP2"), tried)
        assertTrue(r.requests.toString(), r.requests.none { it.uppercase().startsWith("ATSH") })
        assertTrue(r.requests.toString(), r.requests.none { it.uppercase().replace(" ", "").let { c -> c.startsWith("22") || c.startsWith("1A") || c.startsWith("3E") || c.startsWith("A9") } })
        val v = r.link.vehicle.value
        assertEquals("1GNEK13Z03R000000", v.vin)
        assertEquals(listOf("P0133"), v.dtcs.map { it.code })
        assertEquals(750.0, r.link.readings.value.values.first { it.key.endsWith("01.0C") }.value)
        assertEquals(40.0, r.link.readings.value.values.first { it.key.endsWith("01.0D") }.value)
        assertTrue(v.gmDtcStatus, v.gmDtcStatus.contains("J1850 VPW"))
        assertTrue(r.link.scan.value.status, r.link.scan.value.status.contains("J1850 VPW"))
    }

    /** A Tahoe GMT800 picked (J1850 VPW in the car base): its protocol is tried first. */
    @Test
    fun pickedCarsProtocolFirst() {
        CarDbTest.load()
        val tahoe = CarDb.models.single { it.model == "Tahoe" && it.protocol == 2 }
        val r = runBlocking { replayRun(ReplayLog.parse(log), MapStore(), cycles = 0, pick = CarDb.choice(tahoe.id)) }
        assertNull(r.error)
        assertEquals("ATSP2", r.requests.first { it.uppercase().startsWith("ATSP") }.uppercase())
    }
}
