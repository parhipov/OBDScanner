package com.obdscanner

import com.obdscanner.gm.GmDtc
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import com.obdscanner.obd.DtcCode
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.ModuleClear
import com.obdscanner.obd.Reading
import com.obdscanner.screen.AllScreen
import com.obdscanner.screen.Block
import com.obdscanner.screen.CodesScreen
import com.obdscanner.screen.MainScreen
import com.obdscanner.screen.ModuleNames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Ошибки» by module (issue #4: six codes in the modules, «0» in the Check Engine groups above them), «Все данные»
 * under the modules' names, and «Стереть ошибки в блоках».
 */
class CodesTest {
    private val ecm = GmModule(0x7E0, 0x7E8, "ECM (двигатель)", "1A90", 0x11)
    private val tcm = GmModule(0x7E2, 0x7EA, "TCM (АКПП)", "1A90", 0x18)
    private val bcm = GmModule(0x241, 0x641, "BCM (кузов)", "1A90", 0x40)
    private val radio = GmModule(0x252, 0x652, "252", "1A90")

    private val v = VehicleInfo(
        dtcs = listOf(DtcCode("P0420", 0x7E8, DtcKind.STORED), DtcCode("P0171", 0x7E8, DtcKind.PENDING)),
        dtcTime = 1,
        gmDtcs = listOf(
            GmDtcResult(ecm, listOf(GmDtc("P0420", 0x00, 0x8A)), "кодов: 1", true),
            GmDtcResult(tcm, emptyList(), "нет кодов", true),
            GmDtcResult(bcm, listOf(GmDtc("B1517", 0x03, 0x0A), GmDtc("U0140", 0x00, 0x10)), "кодов: 2", true),
            GmDtcResult(radio, emptyList(), "нет ответа", false),
        ),
        gmDtcTime = 2,
        ecus = mapOf(0x7E8 to EcuInfo(0x7E8)),
    )

    @Test
    fun theCodesAreUnderTheirModules() {
        val blocks = CodesScreen.build(v, null, mil = true)
        val rows = blocks.filterIsInstance<Block.Row>().filter { it.dtc != null }
        // P0420 is in the engine's memory and in Mode 03: one row, said to be a Check Engine code.
        val p0420 = rows.filter { it.dtc!!.code == "P0420" }
        assertEquals(1, p0420.size)
        assertTrue(p0420.single().sub!!, tr("код Check Engine", "Check Engine code") in p0420.single().sub!!)
        // The pending one only Mode 07 has: a row of its own under the engine.
        assertTrue(rows.any { it.dtc!!.code == "P0171" && it.dtc!!.kind == DtcKind.PENDING })
        assertEquals(setOf("P0420", "P0171", "B1517", "U0140"), rows.map { it.dtc!!.code }.toSet())
        // The modules by name, no ids (issue #4: «излишне»).
        val folds = blocks.filterIsInstance<Block.Fold>().map { it.text }
        assertTrue(folds.toString(), "ECM (двигатель)" in folds && "BCM (кузов)" in folds && folds.none { "·" in it })
        // The engine's group first, the module without codes and the silent one in a line each.
        val notes = blocks.filterIsInstance<Block.Note>().map { it.text }
        assertTrue(notes.toString(), notes.any { it.startsWith(tr("Без ошибок: ", "No codes: ")) && "TCM" in it })
        assertTrue(notes.toString(), notes.any { it.startsWith(tr("Не ответили: ", "Didn't answer: ")) && "252" in it })
        assertTrue(blocks.any { it is Block.Banner && it.text == tr("Check Engine горит", "Check Engine is on") })
        // One button clears, at the top, next to «Проверить ошибки».
        val first = blocks.filterIsInstance<Block.Buttons>().first().buttons
        assertEquals(listOf(CodesScreen.CHECK, CodesScreen.CLEAR), first.map { it.label })
    }

    @Test
    fun newSinceTheLastCheck() {
        val prev = PrevCodes(1_700_000_000_000, mapOf("241:B1517" to "B1517 (BCM)", "241:B0000" to "B0000 (BCM)", "252:U0100" to "U0100 (радио)"))
        val blocks = CodesScreen.build(v.copy(dtcPrev = prev), null)
        val u0140 = blocks.filterIsInstance<Block.Row>().single { it.dtc?.code == "U0140" }
        assertTrue(u0140.sub!!, tr("новая", "new") in u0140.sub!!)
        val b1517 = blocks.filterIsInstance<Block.Row>().single { it.dtc?.code == "B1517" }
        assertFalse(b1517.sub!!, tr("новая", "new") in b1517.sub!!)
        // Gone from the BCM, which answered; not from the radio, which didn't.
        val gone = blocks.filterIsInstance<Block.Note>().single { it.text.startsWith(tr("С прошлой проверки", "Gone since")) }.text
        assertTrue(gone, "B0000" in gone && "U0100" !in gone)
    }

    /** «Стереть ошибки?»: the engine and gearbox first (the standard clear, their own memory counted in), then the other modules. */
    @Test
    fun theClearQuestion() {
        val q = CodesScreen.clearModules(v)
        assertEquals(listOf(CodesScreen.OBD to true, "241" to true), q.items.map { it.key to it.checked })
        assertEquals(2, q.items.first().count)
        val airbag = v.copy(gmDtcs = v.gmDtcs + GmDtcResult(GmModule(0x24C, 0x64C, "Подушки (SDM)", ""), listOf(GmDtc("B0001", 0, 0x10)), "кодов: 1", true))
        assertEquals(false, CodesScreen.clearModules(airbag).items.single { it.key == "24C" }.checked)
        val text = CodesScreen.text(v, mil = true)
        assertTrue(text, "B1517 03" in text && "P0171" in text && "VIN" !in text)
    }

    /** The standard PIDs (7E8) and the engine's manufacturer parameters (7E0) are one block on «Все данные». */
    @Test
    fun allDataByModule() {
        val r = listOf(
            Reading(Reading.key(0x7E8, "01.0C"), 0x7E8, "Обороты", 700.0, null, "об/мин", 0),
            Reading(Reading.key(0x7E0, "22.1154"), 0x7E0, "Масло", 90.0, null, "°C", 0),
            Reading(Reading.key(0x241, "22.4000"), 0x241, "Кузов", 1.0, null, "", 0),
        ).associateBy { it.key }
        val names = ModuleNames(mapOf(0x7E0 to "ECM (двигатель)", 0x241 to "BCM (кузов)"), mapOf(0x7E8 to 0x7E0, 0x641 to 0x241))
        val blocks = AllScreen.build(r, VehicleInfo(), names, pins = listOf(Reading.key(0x7E8, "01.0C")))
        val folds = blocks.filterIsInstance<Block.Fold>()
        // The favourites group first (the pinned value), then the modules.
        assertEquals(listOf(MainScreen.PINNED, "ECM (двигатель) · 7E0", "BCM (кузов) · 241"), folds.map { it.text })
        assertEquals("2", folds[1].count)
        assertTrue(blocks.filterIsInstance<Block.Value>().filter { it.reading.source == "01.0C" }.all { it.pin == true })
        // A folded group keeps its heading only; a search opens it again.
        val folded = AllScreen.build(r, VehicleInfo(), names, toggled = setOf("all:7E0"))
        assertTrue(folded.filterIsInstance<Block.Value>().none { it.reading.ecu == 0x7E0 || it.reading.ecu == 0x7E8 })
        val found = AllScreen.build(r, VehicleInfo(), names, filter = "масло", toggled = setOf("all:7E0"))
        assertEquals(listOf("22.1154"), found.filterIsInstance<Block.Value>().map { it.reading.source })
    }

    private val bcmLog = """
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
        12:00:01.020 > ATCRAX41
        12:00:01.030 < OK
        12:00:01.040 > ATCAF0
        12:00:01.050 < OK
        12:00:01.060 > 03A9815A00000000
        12:00:02.000 < 541 81 95 17 03 0A 00 00 00 | 541 81 00 00 00 FF 00 00 00
        12:00:03.000 > ATCAF1
        12:00:03.010 < OK
        12:00:03.020 > ATCRA641
        12:00:03.030 < OK
        12:00:03.040 > 04
        12:00:03.100 < 641 01 44
        12:00:04.000 > ATCRAX41
        12:00:04.010 < OK
        12:00:04.020 > ATCAF0
        12:00:04.030 < OK
        12:00:04.040 > 03A9815A00000000
        12:00:05.000 < 541 81 00 00 00 FF 00 00 00
    """.trimIndent()

    /** «Стереть ошибки в блоках»: GM \$04 to the BCM, which says 44, and its memory read again — empty now. */
    @Test
    fun aModuleIsClearedAndReadAgain() {
        CarDbTest.load()
        val store = MapStore().apply { putString("gm_modules_1G1JC5444R7252367", "241:641:40") }
        val r = runBlocking { replayRun(ReplayLog.parse(bcmLog), store, cycles = 1, after = { link, o ->
            assertEquals(listOf("B1517"), link.vehicle.value.gmDtcs.single().codes.map { it.code })
            link.clearCodes(o, obd = false, keys = listOf("241"))
        }) }
        assertNull(r.error)
        val v = r.link.vehicle.value
        assertTrue(v.gmDtcs.single().codes.isEmpty())
        assertEquals(1, v.clearResult.size)
        assertTrue(v.clearResult.single(), tr("стёрто", "cleared") in v.clearResult.single())
        assertTrue(r.addressed.toString(), "241" to "04" in r.addressed)
        assertTrue(r.reports.any { it.first == tr("Стирание ошибок", "Clearing codes") })
        // Not the standard clear: it wasn't ticked.
        assertFalse(r.addressed.toString(), "7DF" to "04" in r.addressed)
    }

    /** Part numbers are kept with the modules (issue #4: the main bus's were missing on «Машина»), and a missing one is asked once. */
    @Test
    fun partNumbersStayWithTheModules() {
        CarDbTest.load()
        val kept = MapStore().apply { putString("gm_modules_1G1JC5444R7252367", "241:641:40:13535989") }
        val r = runBlocking { replayRun(ReplayLog.parse(bcmLog), kept, cycles = 1) }
        assertEquals(13535989L, r.link.scan.value.modules.single().partNumber)
        assertFalse("1ACB" in r.requests.map { it.uppercase() })
        val none = MapStore().apply { putString("gm_modules_1G1JC5444R7252367", "241:641:40") }
        val again = runBlocking { replayRun(ReplayLog.parse(bcmLog), none, cycles = 1, after = { link, o -> link.readAllModulesDtc(o) }) }
        assertEquals(1, again.requests.count { it.uppercase() == "1ACB" })
    }

    /** Two modules of one name: their ids tell them apart. */
    @Test
    fun namesakesGetTheirIds() {
        val twins = VehicleInfo(gmDtcs = listOf(
            GmDtcResult(GmModule(0x24A, 0x64A, "HUD", ""), listOf(GmDtc("U0100", 0, 0x10)), "кодов: 1", true),
            GmDtcResult(GmModule(0x25A, 0x65A, "HUD", "", bus = "swcan"), listOf(GmDtc("U0101", 0, 0x10)), "кодов: 1", true),
        ), gmDtcTime = 1)
        val folds = CodesScreen.build(twins, null).filterIsInstance<Block.Fold>().map { it.text }
        assertTrue(folds.toString(), "HUD · 24A" in folds && folds.any { it.startsWith("HUD · 25A") })
    }

    @Test
    fun aRefusalInWords() {
        assertTrue(ModuleClear.refusal(0x22).isNotEmpty())
        assertEquals("04", ModuleClear.request(com.obdscanner.gm.DtcScheme.GM))
        assertEquals("14FFFFFF", ModuleClear.request(com.obdscanner.gm.DtcScheme.UDS))
        assertEquals("14FF00", ModuleClear.request(com.obdscanner.gm.DtcScheme.KWP))
    }
}
