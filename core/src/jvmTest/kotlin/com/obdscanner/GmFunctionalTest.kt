package com.obdscanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GM modules behind the gateway: they don't answer the probe one id at a time, only the GMLAN request to all
 * nodes (101, FE, \$1A B0) — from their own reply id with their diagnostic address. A Trailblazer finds 10 modules
 * by the probe where OPL Monitor lists 19 (issue #1). The log is written by hand: a GM VIN, the BCM answering the
 * probe on 241, and to the request to all the BCM again (not listed twice) and a module on 65A with address 99.
 */
class GmFunctionalTest {
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
        12:00:01.000 > ATSH241
        12:00:01.010 < OK
        12:00:01.020 > ATCRA641
        12:00:01.030 < OK
        12:00:01.040 > 1A90
        12:00:01.100 < 641 03 7F 1A 31
        12:00:02.000 > ATSH101
        12:00:02.010 < OK
        12:00:02.020 > ATCRA6XX
        12:00:02.030 < OK
        12:00:02.040 > ATCAF0
        12:00:02.050 < OK
        12:00:02.060 > FE021AB000000000
        12:00:02.900 < 641 03 5A B0 40 | 65A 03 5A B0 99 | 641 03 5A B0 40
    """.trimIndent()

    @Test
    fun modulesBehindTheGatewayAnswerTheRequestToAll() {
        CarDbTest.load()
        val r = runBlocking { replayRun(ReplayLog.parse(log), MapStore(), cycles = 1) }
        assertNull(r.error)
        assertTrue(r.addressed.toString(), "101" to "FE021AB000000000" in r.addressed)
        val mods = r.link.scan.value.modules
        assertEquals(mods.toString(), listOf(0x241, 0x25A), mods.map { it.req }.filter { it in 0x241..0x25F })
        val behind = mods.single { it.req == 0x25A }
        assertEquals(0x65A, behind.resp)
        assertEquals(0x99, behind.diag)
        assertEquals(0x40, mods.single { it.req == 0x241 }.diag)
        // The adapter is back to formatted replies after the raw exchange.
        val sent = r.requests.map { it.uppercase().replace(" ", "") }
        val after = sent.drop(sent.indexOf("FE021AB000000000"))
        assertTrue(after.toString(), "ATCAF1" in after && "ATAR" in after)
    }
}
