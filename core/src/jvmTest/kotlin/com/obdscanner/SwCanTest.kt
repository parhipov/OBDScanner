package com.obdscanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Блоки на SW-CAN»: an adapter that knows OBDLink's STP 63 goes to GM's single-wire bus, finds the modules there, reads
 * their codes and comes back to HS-CAN; one that refuses it is left alone. The logs are written by hand: a GM VIN, and
 * on SW-CAN a head-up display on 25A (address 99) with U18B4 (the Trailblazer's, issue #1).
 */
class SwCanTest {
    private val connect = """
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
        12:00:01.000 > ATAR
        12:00:01.010 < OK
    """.trimIndent()

    private val swCan = connect + "\n" + """
        12:00:02.000 > STP63
        12:00:02.010 < OK
        12:00:02.020 > STPR
        12:00:02.030 < 63
        12:00:02.040 > ATSH101
        12:00:02.050 < OK
        12:00:02.060 > ATCRA6XX
        12:00:02.070 < OK
        12:00:02.080 > ATCAF0
        12:00:02.090 < OK
        12:00:02.100 > FE021AB000000000
        12:00:02.900 < 65A 03 5A B0 99
        12:00:03.000 > ATCAF1
        12:00:03.010 < OK
        12:00:03.020 > ATAR
        12:00:03.030 < OK
        12:00:04.000 > ATSH25A
        12:00:04.010 < OK
        12:00:04.020 > ATCRA65A
        12:00:04.030 < OK
        12:00:04.040 > 1AB0
        12:00:04.100 < 65A 03 5A B0 99
        12:00:05.000 > ATCRAX5A
        12:00:05.010 < OK
        12:00:05.020 > ATCAF0
        12:00:05.030 < OK
        12:00:05.040 > 03A9815A00000000
        12:00:06.000 < 55A 81 D8 B4 00 09 00 00 00 | 55A 81 00 00 00 FF 00 00 00
    """.trimIndent()

    @Test
    fun modulesOnTheSingleWireBusAndBack() {
        CarDbTest.load()
        val r = runBlocking { replayRun(ReplayLog.parse(swCan), MapStore(), cycles = 1, after = { link, o -> link.swCanModules(o) }) }
        assertNull(r.error)
        val sw = r.link.scan.value.sw
        assertNotNull(r.requests.takeLast(60).toString(), sw)
        assertFalse(sw.toString(), sw!!.running)
        val hud = sw.modules.single()
        assertEquals(0x25A, hud.req)
        assertEquals(0x99, hud.diag)
        assertEquals(listOf("U18B4"), sw.dtcs.single().codes.map { it.code })
        // Kept apart: the HS-CAN list doesn't get it.
        assertTrue(r.link.scan.value.modules.none { it.req == 0x25A })
        // Back on HS-CAN the connection's way, and the engine answers there.
        val sent = r.requests.map { it.uppercase().replace(" ", "") }
        val after = sent.drop(sent.indexOf("03A9815A00000000"))
        assertTrue(after.toString(), "ATSP6" in after && "0100" in after.drop(after.indexOf("ATSP6")))
        assertTrue(r.reports.any { it.first.startsWith("GM SW-CAN: ") && "U18B4" in it.second })
        // «Ошибки» shows them too, under their own title (an owner looked there first, issue #1).
        val codes = com.obdscanner.screen.ScreenJson.blocks(com.obdscanner.screen.CodesScreen.build(r.link.vehicle.value, null, sw))
        assertTrue(codes, tr("На SW-CAN", "On SW-CAN") in codes && "U18B4" in codes)
    }

    /**
     * A module on both buses (the Trailblazer's BCM, camera, radio, cluster: issue #1) tells the same diagnostic address
     * on SW-CAN as on HS-CAN and refuses \$A9 there: it takes the HS module's name and isn't asked for codes on SW-CAN.
     */
    @Test
    fun aModuleOnBothBusesIsTheHsOne() {
        CarDbTest.load()
        val both = swCan.replace("< 65A 03 5A B0 99\n", "< 641 03 5A B0 40 | 65A 03 5A B0 99\n")
        // The BCM with its address from an earlier session on HS-CAN.
        val store = MapStore().apply { putString("gm_modules_1G1JC5444R7252367", "241:641:40") }
        val r = runBlocking { replayRun(ReplayLog.parse(both), store, cycles = 1, after = { link, o -> link.swCanModules(o) }) }
        assertNull(r.error)
        val sw = r.link.scan.value.sw!!
        val bcm = sw.modules.single { it.req == 0x241 }
        assertTrue(bcm.name, "241" in bcm.name && bcm.name.startsWith(r.link.scan.value.modules.single { it.req == 0x241 }.name))
        assertTrue(sw.dtcs.single { it.module.req == 0x241 }.codes.isEmpty())
        // On SW-CAN the codes are asked of the HUD only.
        val sent = r.requests.map { it.uppercase().replace(" ", "") }
        val onSw = sent.drop(sent.indexOf("STP63")).takeWhile { it != "ATSP6" }
        var header = ""
        val asked = onSw.mapNotNull { c -> if (c.startsWith("ATSH")) { header = c.removePrefix("ATSH"); null } else if (c.startsWith("03A981")) header else null }
        assertEquals(listOf("25A"), asked.distinct())
    }

    /** «Скан блоков» shows each module's part number once read — the one on its label (an owner asked, issue #1). */
    @Test
    fun partNumbersOnTheScanScreen() {
        val bcm = com.obdscanner.gm.GmModule(0x241, 0x641, "BCM", "1A90 → OK", 0x40, partNumber = 13535989)
        val hud = com.obdscanner.gm.GmModule(0x24A, 0x64A, "HUD", "1A B0", 0x62, partNumber = 60005023)
        val s = ScanState(modules = listOf(bcm), sw = SwCanState(modules = listOf(hud)))
        val g = com.obdscanner.screen.GmScreen.build(s, BusState(), emptyMap(), false,
            com.obdscanner.screen.ScanContext(true, listOf("" to 0..0)) { listOf("1A") })
        assertTrue(g.modules.single().answeredTo, "13535989" in g.modules.single().answeredTo)
        assertTrue(g.swRows.single(), "60005023" in g.swRows.single())
    }

    @Test
    fun anAdapterWithoutStpIsLeftAlone() {
        CarDbTest.load()
        val refuses = connect + "\n" + """
            12:00:02.000 > STP63
            12:00:02.010 < ?
        """.trimIndent()
        val r = runBlocking { replayRun(ReplayLog.parse(refuses), MapStore(), cycles = 1, after = { link, o -> link.swCanModules(o) }) }
        assertNull(r.error)
        val sent = r.requests.map { it.uppercase().replace(" ", "") }
        val after = sent.drop(sent.indexOf("STP63") + 1)
        assertEquals(after.toString(), emptyList<String>(), after.filter { it != "STPR" })
        val sw = r.link.scan.value.sw!!
        assertTrue(sw.status, sw.modules.isEmpty() && "STP 63" in sw.status)
    }
}
