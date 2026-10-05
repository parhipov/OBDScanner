package com.obdscanner

import com.obdscanner.car.CarDb
import com.obdscanner.car.Dialect
import com.obdscanner.gm.GmModule
import com.obdscanner.obd.ObdModules
import com.obdscanner.obd.UdsDtcReader
import com.obdscanner.transport.MockTransport
import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blocks and their dialects: the database side, and a whole connection on a made-up car. */
class DialectTest {

    /** The module lists that were code until 1.26 (GmModules, VagModules, ObdModules.EXTRA) come out the same from the files. */
    @Test
    fun moduleListsAsInCode() {
        CarDbTest.load()
        fun cands(family: String) = ObdModules.addressing(CarDb.family(family), family).candidates
        fun fromDb(family: String) = CarDb.family(family)!!.commands.map { it.req to it.resp }.filter { it.first !in 0x7E0..0x7E7 }
        fun std(extra: List<Pair<Int, Int>>, family: String) = (ObdModules.candidates + extra + fromDb(family)).distinctBy { it.first }

        assertEquals((0x7E0..0x7E7).map { it to it + 8 } + (0x240..0x25F).map { it to it + 0x400 } + listOf(0x760 to 0x768), cands("gm"))
        assertEquals((0x7E0..0x7E7).map { it to it + 8 } + (0x700..0x775).map { it to it + 0x6A }, cands("vag"))
        val renault = listOf(0x740, 0x742, 0x743, 0x744, 0x745, 0x748, 0x752, 0x758, 0x79B, 0x707).map { it to it + 0x20 }
        for (f in listOf("renault", "nissan", "lada")) assertEquals(std(renault, f), cands(f))
        assertEquals(std(listOf(0x700, 0x701, 0x780, 0x791).map { it to it + 8 }, "toyota"), cands("toyota"))
        assertEquals(std(listOf(0x7D4, 0x7B3, 0x7B1, 0x7B7, 0x730, 0x7C5, 0x794, 0x770).map { it to it + 8 }, "hyundai"), cands("hyundai"))
        assertEquals(std(listOf(0x706, 0x726, 0x730, 0x732, 0x760, 0x764).map { it to it + 8 }, "ford"), cands("ford"))
        assertEquals(std(listOf(0x706, 0x730, 0x732, 0x760, 0x764).map { it to it + 8 }, "mazda"), cands("mazda"))
        assertEquals(std(listOf(0x7A2, 0x7A3, 0x746, 0x787).map { it to it + 8 }, "subaru"), cands("subaru"))
        assertEquals(std(listOf(0x763, 0x782, 0x787, 0x78B).map { it to it + 0x40 } +
            listOf(0x710, 0x724, 0x740, 0x745, 0x750, 0x760, 0x781, 0x784, 0x785).map { it to it + 8 }, "china"), cands("china"))
        assertEquals(std(emptyList(), "bmw"), cands("bmw"))
        assertEquals(ObdModules.candidates, ObdModules.addressing(null, "").candidates)

        assertEquals(listOf("1A90", "22F190", "3E00"), ObdModules.addressing(CarDb.family("gm"), "GM").probes)
        assertEquals(listOf("3E00", "22F187"), ObdModules.addressing(CarDb.family("vag"), "VAG").probes)
        assertEquals(listOf("2180", "22F190", "3E00"), ObdModules.addressing(CarDb.family("lada"), "Lada").probes)
        assertEquals(ObdModules.PROBES, ObdModules.addressing(CarDb.family("toyota"), "Toyota").probes)

        val gm = ObdModules.addressing(CarDb.family("gm"), "GM").name
        assertEquals(tr("ECM (двигатель)", "ECM (engine)"), gm(0x7E0))
        assertEquals("249", gm(0x249))
        assertEquals("7E1", gm(0x7E1))
        assertEquals(tr("02 КПП", "02 Transmission"), ObdModules.addressing(CarDb.family("vag"), "VAG").name(0x7E1))
        assertEquals(tr("17 Приборка", "17 Instruments"), ObdModules.addressing(CarDb.family("vag"), "VAG").name(0x714))
        assertEquals("ABS (760)", ObdModules.addressing(CarDb.family("ford"), "Ford").name(0x760))
        assertEquals(tr("Двигатель (7E0)", "Engine (7E0)"), ObdModules.addressing(CarDb.family("ford"), "Ford").name(0x7E0))
        assertEquals(tr("Приборная панель (743)", "Instrument cluster (743)"), ObdModules.addressing(CarDb.family("nissan"), "Nissan").name(0x743))
    }

    /** The GM parameters moved from GmKnown.kt decode as their formulas did. */
    @Test
    fun gmParametersAsInCode() {
        CarDbTest.load()
        val gm = CarDb.family("gm")!!.commands.associateBy { it.key }
        fun value(key: String, vararg d: Int) = gm.getValue(key).signals.single().fmt.decode(d)!!.first!!
        fun ab(d: IntArray) = d[0] * 256 + d[1]
        val d = intArrayOf(0xF4, 0x37)
        assertEquals(0xF4 - 40.0, value("7E2:22.1940", 0xF4), 1e-9)
        assertEquals(ab(d).let { if (it >= 32768) it - 65536 else it } / 8.0, value("7E2:22.1991", *d), 1e-9)
        assertEquals(ab(d) * 0.125, value("7E2:22.1941", *d), 1e-9)
        assertEquals(0x96 * 22.5 / 256, value("7E0:22.11A6", 0x96), 1e-9)
        assertEquals((0x96 * 1.83 - 15) * 6.895, value("7E0:22.1144", 0x96), 1e-9)
        assertEquals(ab(d) / 65.535, value("7E0:22.1193", *d), 1e-9)
        assertEquals((ab(d) - 32768) * 0.015625, value("7E0:22.162F", *d), 1e-9)
        assertEquals(0x96 - 3.0, value("7E2:22.1B30", 0x96), 1e-9)
        assertEquals(0x96 * 6.895, value("7E0:22.248E", 0x96), 1e-9)
        // Reading keys stay as they were ("7E2:22.1940", no signal id), roles and units as well.
        val tft = gm.getValue("7E2:22.1940")
        assertEquals("7E2:22.1940", tft.readingKey(tft.signals.single()))
        assertEquals("atf_temp", tft.signals.single().role)
        assertEquals(tr("об/мин", "rpm"), gm.getValue("7E2:22.1941").signals.single().unit)
        assertEquals(8..16, gm.getValue("7E0:22.11ED").signals.single().cyl)
        // OBDb values for the same request are not mixed in.
        assertEquals(1, gm.getValue("7E0:22.1154").signals.size)
    }

    @Test
    fun dialectsResolve() {
        CarDbTest.load()
        val jdm = CarDb.dialect("toyota_jdm")!!
        assertEquals("21", jdm.live)
        assertEquals(listOf("uds19", "kwp18", "kwp13"), jdm.dtc)
        assertEquals("uds_kwp", jdm.ident)
        assertTrue(jdm.commands.isEmpty())
        assertEquals(listOf("gm_a9"), CarDb.family("gm")!!.dialect.dtc)
        assertEquals("gm_1a", CarDb.family("gm")!!.dialect.ident)
        assertEquals("vag", CarDb.family("vag")!!.dialect.dtcFormat)
        assertEquals(Dialect.GENERIC.dtc, CarDb.family("bmw")!!.dialect.dtc)
        // A family's default dialect carries the family's requests.
        assertEquals(CarDb.family("ford")!!.commands, CarDb.dialect("ford")!!.commands)
        // Every block of every model names a dialect that exists (the loader drops the model otherwise).
        for (m in CarDb.models) for (b in m.allBlocks) assertNotNull("${m.title}: ${b.dialect}", CarDb.dialect(b.dialect))
    }

    /** The Crown S180 has no VIN: its engine ECU's \$21 C1 ("GRS18# 4GRFSE") says what it is, only when Mode 01 was refused. */
    @Test
    fun crownByTheEngineAnswer() {
        CarDbTest.load()
        val crown = CarDb.models.single { it.model == "Crown" && it.gen == "S180" }
        val rule = crown.match.single()
        assertTrue(rule.fits(6, "refused"))
        assertFalse(rule.fits(6, "ok"))
        assertFalse(rule.fits(4, "refused"))
        val answer = "GRS18# 4GRFSE".map { it.code }.toIntArray() + intArrayOf(0x06, 0x04, 0x40, 0x67, 0x01)
        assertTrue(rule.matches(answer))
        assertFalse(rule.matches("JZS17# 2JZGE".map { it.code }.toIntArray()))
        assertEquals(setOf(0x7E0, 0x7E1), crown.knownBlocks.keys)
        assertEquals("toyota_jdm", crown.knownBlocks.getValue(0x7E0).dialect)
    }

    /** An engine's block counts only when every engine of the model has the same one. */
    @Test
    fun engineBlocksOnlyWhenTheEnginesAgree() {
        val base = """{ "family": "tb", "title": "T", "brands": [{ "name": "Tb", "wmi": ["TBB"] }],
            "dialects": [{ "id": "tb_a" }, { "id": "tb_b" }], "models": [%s] }"""
        fun model(name: String, engines: String, blocks: String = "") =
            """{ "brand": "Tb", "model": "$name", "engines": [$engines] ${if (blocks.isEmpty()) "" else ", \"blocks\": [$blocks]"}, "src": ["x"] }"""
        fun engine(code: String, dialect: String?) =
            """{ "code": "$code" ${dialect?.let { ", \"blocks\": [{ \"role\": \"engine\", \"addr\": \"7E0\", \"dialect\": \"$it\" }]" } ?: ""} }"""
        val saved = CarDb.families
        try {
            CarDb.load(listOf(base.format(listOf(
                model("Same", engine("E1", "tb_a") + "," + engine("E2", "tb_a")),
                model("Differ", engine("E1", "tb_a") + "," + engine("E2", "tb_b")),
                model("Partly", engine("E1", "tb_a") + "," + engine("E2", null)),
                model("Own", engine("E1", "tb_a"), """{ "role": "engine", "addr": "7E0", "dialect": "tb_b" }"""),
                model("Missing", engine("E1", "no_such")),
            ).joinToString(","))))
            fun known(name: String) = CarDb.models.single { it.model == name }.knownBlocks[0x7E0]?.dialect
            assertEquals("tb_a", known("Same"))
            assertNull(known("Differ"))
            assertNull(known("Partly"))
            assertEquals("tb_b", known("Own"))
            assertTrue(CarDb.models.none { it.model == "Missing" })
            assertTrue(CarDb.problems.single().contains("no_such"))
        } finally {
            CarDbTest.load(force = true)
            assertTrue(saved.keys == CarDb.families.keys)
        }
    }

    /**
     * A made-up car: its own make's database (family "tst") and a Volvo-like engine (dialect "tst_volvo") —
     * the engine's requests come from the engine's dialect, the gearbox's from the make's; each block's codes
     * are read the way its dialect says.
     */
    @Test
    fun aBlockTalksItsDialect() {
        val db = """{
            "family": "tst", "title": "Test", "brands": [{ "name": "Testa", "wmi": ["TST"] }],
            "modules": { "replace": true, "addresses": [{ "req": "7E0-7E1", "rsp": "+8" }], "probes": ["3E00"] },
            "dialects": [{ "id": "tst_volvo", "dtc": ["kwp18"], "commands": [${cmd("7E0", "1234", "OIL")}] }],
            "models": [{ "brand": "Testa", "model": "X", "vin": ["^TSTX"], "protocol": 6, "src": ["x"],
              "engines": [{ "code": "B4204T", "cyl": 4, "fuel": "petrol", "blocks": [{ "role": "engine", "addr": "7E0", "dialect": "tst_volvo" }] }] }],
            "commands": [${cmd("7E0", "9999", "MAKE")}, ${cmd("7E1", "8888", "ATF")}]
        }"""
        val log = listOf(
            "ATZ" to "ELM327 v1.5", "ATSP6" to "OK",
            "0100" to "7E8 06 41 00 00 10 00 00", "ATDPN" to "6", "ATSH7DF" to "OK",
            "0902" to "7E8 10 14 49 02 01 54 53 54 | 7E8 21 58 31 32 33 34 35 36 | 7E8 22 37 38 39 30 31 32 33",
            "ATSH7E0" to "OK", "221234" to "7E8 04 62 12 34 5A", "229999" to "7E8 04 62 99 99 01",
            "3E00" to "7E8 02 7E 00", "1902AF" to "7E8 03 7F 19 11", "1802FF00" to "7E8 02 58 00",
            "ATSH7E1" to "OK", "228888" to "7E9 04 62 88 88 82", "3E00" to "7E9 02 7E 00",
            "1902AF" to "7E9 03 7F 19 11", "1802FF00" to "7E9 02 58 00",
            "ATSH7DF" to "OK", "0100" to "7E8 06 41 00 00 10 00 00",
        ).joinToString("\n") { (c, r) -> "12:00:00.000 > $c\n12:00:00.040 < $r" }
        try {
            CarDb.load(listOf(db))
            assertTrue(CarDb.problems.toString(), CarDb.problems.isEmpty())
            val r = replay(ReplayLog.parse(log), MapStore(), 0)
            assertNull(r.error)
            val sent = r.addressed
            val v = r.link.vehicle.value
            assertEquals("X", v.car?.model)
            assertTrue(sent.toString(), "7E0" to "221234" in sent)
            assertFalse("the make's request to a block with its own dialect", "7E0" to "229999" in sent)
            assertTrue("7E1" to "228888" in sent)
            assertEquals(setOf("7E0:22.1234", "7E1:22.8888"), v.extActive.map { it.key }.toSet())
            assertEquals(50.0, r.link.readings.value.getValue("7E0:22.1234.OIL").value!!, 1e-9)
            // The engine's dialect reads codes with KWP \$18 only; the gearbox has the make's (UDS, then KWP).
            assertFalse("7E0" to "1902AF" in sent)
            assertTrue("7E0" to "1802FF00" in sent)
            assertTrue("7E1" to "1902AF" in sent && "7E1" to "1802FF00" in sent)
            assertEquals(listOf(0x7E0, 0x7E1), v.gmDtcs.map { it.module.req })
        } finally {
            CarDbTest.load(force = true)
        }
    }

    /** A hand-written raw.log: each (command, reply) as the app logs them. */
    private fun log(vararg pairs: Pair<String, String>) = ReplayLog.parse(pairs.joinToString("\n") { (c, r) -> "12:00:00.000 > $c\n12:00:00.040 < $r" })

    /** No VIN, Lada picked by hand, but the engine ECU says it's a Crown S180: the car's answer wins, as a VIN would. */
    @Test
    fun theCarsAnswerBeatsThePickedMake() {
        CarDbTest.load()
        val r = replay(log(
            "ATZ" to "ELM327 v1.5", "ATSP6" to "OK", "0100" to "7E8 03 7F 01 11 | 7E9 03 7F 01 11", "ATDPN" to "6",
            "ATSH7DF" to "OK", "0100" to "7E8 03 7F 01 11 | 7E9 03 7F 01 11",
            "ATSH7E0" to "OK",
            "21C1" to "7E8 10 14 61 C1 47 52 53 31 | 7E8 21 38 23 20 34 47 52 46 | 7E8 22 53 45 06 04 40 67 01",
            "2100" to "7E8 06 61 00 18 00 00 00", "2104" to "7E8 03 61 04 45", "2105" to "7E8 03 61 05 5A",
        ), MapStore(), 2, pick = CarDb.choice("brand:Lada"))
        val v = r.link.vehicle.value
        assertEquals("Crown", v.car?.model)
        assertEquals(com.obdscanner.obd.Make.TOYOTA, v.make)
        assertFalse(v.makeByPick)
        assertEquals(0x7E0, v.ecus[0x7E8]?.pidReq)
        assertEquals(50.0, r.link.readings.value.getValue("7E8:01.05").value!!, 1e-9)
    }

    /**
     * No VIN, Cadillac picked by hand on a car that isn't a GM: the engine refuses GMLAN \$A9, so its codes are
     * read the standard way (\$19) — and the screen and the log say the make may be wrong.
     */
    @Test
    fun aWrongPickedMakeFallsBackToTheStandardWay() {
        CarDbTest.load()
        val r = replay(log(
            "ATZ" to "ELM327 v1.5", "ATSP6" to "OK", "0100" to "7E8 06 41 00 00 10 00 00", "ATDPN" to "6",
            "ATSH7DF" to "OK", "0100" to "7E8 06 41 00 00 10 00 00",
            "ATSH7E0" to "OK", "1A90" to "7E8 03 7F 1A 11",
            "ATCRAXE8" to "OK", "ATCAF0" to "OK", "03A9815A00000000" to "7E8 03 7F A9 11", "ATCAF1" to "OK", "ATAR" to "OK",
            "1902AF" to "7E8 07 59 02 FF 01 71 00 09",
        ), MapStore(), 0, pick = CarDb.choice("brand:Cadillac"))
        assertNull(r.error)
        val v = r.link.vehicle.value
        assertTrue(v.makeByPick)
        val ecm = v.gmDtcs.single { it.module.req == 0x7E0 }
        assertEquals(listOf("P0171"), ecm.codes.map { it.code })
        assertTrue(ecm.result, ecm.result.contains("GM \$A9"))
        assertTrue(v.gmDtcStatus, v.gmDtcStatus.contains("7E0"))
        assertTrue("A9 first, then 19", r.addressed.indexOf("7E0" to "03A9815A00000000") < r.addressed.indexOf("7E0" to "1902AF"))
    }

    /** Whatever a file says, recognising a car or probing modules only reads, and only one module at a time. */
    @Test
    fun refusesUnsafeMatchAndProbes() {
        fun model(match: String) = """{ "brand": "Tr", "model": "M", "match": [$match], "src": ["x"] }"""
        try {
            CarDb.load(listOf("""{ "family": "tr", "title": "T", "brands": [{ "name": "Tr", "wmi": ["TRR"] }], "models": [${listOf(
                model("""{ "hdr": "7E0", "svc": "2E", "did": "F190", "ascii": "x" }"""), // write
                model("""{ "hdr": "7E0", "svc": "31", "did": "01", "ascii": "x" }"""), // routine
                model("""{ "hdr": "7DF", "svc": "21", "did": "C1", "ascii": "x" }"""), // broadcast
                model("""{ "hdr": "7E0", "svc": "21", "did": "C1" }"""), // nothing to compare
                model("""{ "hdr": "7E0", "svc": "21", "did": "C1", "ascii": "x", "when": { "obd": "maybe" } }"""),
                model("""{ "hdr": "7E0", "svc": "1A", "did": "87", "ascii": "x" }"""), // the good one
            ).joinToString(",")}] }""",
                """{ "family": "tp", "title": "P", "modules": { "probes": ["2E F190"], "addresses": [{ "req": "7E0", "rsp": "+8" }] } }""",
                """{ "family": "tq", "title": "Q", "modules": { "addresses": [{ "req": "7DF", "rsp": "+8" }] } }""",
                """{ "family": "tu", "title": "U", "dialect": { "dtc": ["clear14"] } }"""))
            assertEquals(1, CarDb.models.count { it.match.isNotEmpty() })
            assertEquals(8, CarDb.problems.size)
        } finally {
            CarDbTest.load(force = true)
        }
    }

    private fun cmd(hdr: String, did: String, id: String) =
        """{ "hdr": "$hdr", "svc": "22", "did": "$did", "signals": [{ "id": "$id", "name": { "en": "$id" }, "unit": "°C", "fmt": { "len": 8, "add": -40 } }] }"""

    /** ISO 14230-3 \$13: count, then two bytes per code; zero codes are filler. */
    @Test
    fun kwp13Codes() {
        val elm = Elm327(MockTransport()) { _, _ -> }
        val reader = UdsDtcReader(Obd(elm)) { }
        val mod = GmModule(0x7E0, 0x7E8, "7E0", "")
        val r = reader.parseKwp13(mod, intArrayOf(0x53, 0x02, 0x01, 0x71, 0x03, 0x00))
        assertEquals(listOf("P0171", "P0300"), r.codes.map { it.code })
        assertTrue(r.complete)
        val odd = reader.parseKwp13(mod, intArrayOf(0x53, 0x03, 0x01, 0x71, 0x00, 0x00))
        assertEquals(listOf("P0171"), odd.codes.map { it.code })
        assertFalse(odd.complete)
        assertEquals("UDS \$19 02 / KWP \$18 02", UdsDtcReader.title(setOf("uds19", "kwp18")))
        assertEquals("UDS \$19 02 / KWP \$18 02 / KWP \$13", UdsDtcReader.title(setOf("uds19", "kwp18", "kwp13")))
    }
}
