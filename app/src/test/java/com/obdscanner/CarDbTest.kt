package com.obdscanner

import com.obdscanner.car.CarDb
import com.obdscanner.car.SignalFormat
import com.obdscanner.obd.Make
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class CarDbTest {
    companion object {
        /** The app's own assets (unit tests run in app/). */
        fun load(force: Boolean = false) {
            if (CarDb.families.isNotEmpty() && !force) return
            CarDb.load(texts())
        }

        fun texts(): List<String> {
            val dirs = listOf(File("src/main/assets/cars"), File("src/main/assets/cars/obdb"))
            return dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.map { it.readText() } }
        }

        @BeforeClass @JvmStatic
        fun setUp() = load()
    }

    /** 1.04 hung on startup: merging commands was quadratic. On a desktop JVM the whole database parses well under a second. */
    @Test
    fun loadsFast() {
        // Timed warm: the first parse in a test JVM also pays for class loading and the JIT, several times
        // more on a CI runner than here. Quadratic merging (1.04) took seconds even warm.
        val texts = texts()
        CarDb.load(texts)
        val t0 = System.currentTimeMillis()
        CarDb.load(texts)
        val ms = System.currentTimeMillis() - t0
        println("car database parsed in $ms ms (warm)")
        assertTrue("parse took $ms ms", ms < 1500)
    }

    @Test
    fun everyEntryLoads() {
        assertTrue("refused entries:\n" + CarDb.problems.joinToString("\n"), CarDb.problems.isEmpty())
        assertTrue(CarDb.models.isNotEmpty())
    }

    /** Whatever is in the files, the app only ever sends read requests to one module's physical id. */
    @Test
    fun onlyReadRequests() {
        val all = CarDb.families.values.flatMap { it.commands } + CarDb.dialects.values.flatMap { it.commands }
        for (c in all) {
            assertTrue(c.key, c.service == "21" || c.service == "22")
            assertTrue(c.key, c.req in 0x700..0x7FF && c.req != 0x7DF)
            assertTrue(c.key, c.resp in 0x700..0x7FF)
            assertTrue(c.key, if (c.service == "21") c.did in 0..0xFF else c.did in 0..0xFFFF)
        }
    }

    @Test
    fun refusesWritesAndBroadcast() {
        val saved = CarDb.families
        try {
            fun cmd(hdr: String, svc: String, did: String) =
                """{"hdr":"$hdr","svc":"$svc","did":"$did","signals":[{"id":"x","name":{"en":"x"},"fmt":{"len":8}}]}"""
            CarDb.load(listOf("""{"family":"t","brands":[],"commands":[${listOf(
                cmd("7E0", "2E", "F190"), // WriteDataByIdentifier
                cmd("7E0", "31", "0101"), // RoutineControl
                cmd("7E0", "10", "03"), // session
                cmd("7DF", "22", "F190"), // broadcast
                cmd("18DA10F1", "22", "F190"), // 29-bit
                cmd("7E0", "22", "F1"), // wrong DID length
                cmd("7E0", "22", "F190"), // the only good one
            ).joinToString(",")}]}"""))
            assertEquals(listOf("7E0:22.F190"), CarDb.family("t")!!.commands.map { it.key })
            assertEquals(6, CarDb.problems.size)
        } finally {
            CarDb.load(emptyList())
            load()
            assertTrue(saved.keys == CarDb.families.keys)
        }
    }

    @Test
    fun makeFromVin() {
        assertEquals(Make.GM, Make.fromVin("1G6DM577980123456"))
        assertEquals(Make.GM, Make.fromVin("W0L0AHL3575000000"))
        assertEquals(Make.VAG, Make.fromVin("XW8ZZZ61ZBG000000"))
        assertEquals(Make.VAG, Make.fromVin("wvwzzz6rzcy000000"))
        assertEquals(Make.TOYOTA, Make.fromVin("JTMZD33V300012345"))
        assertEquals(Make.LADA, Make.fromVin("XTAGFK330GY000000"))
        assertEquals(Make.HYUNDAI, Make.fromVin("Z94CT41DBBR000000"))
        assertEquals(Make.OTHER, Make.fromVin("SALLAAA1000000000"))
        assertEquals(Make.OTHER, Make.fromVin(null))
        assertEquals(Make.OTHER, Make.fromVin(""))
        assertEquals("Cadillac", CarDb.brandOf("1G6DM577980123456")?.name)
        // Hyundai Coupe 2003: the ECU keeps its VIN with 'x' placeholders.
        val coupe = CarDb.detect("KMHHxxxDx3Uxxxxxx")
        assertEquals("Coupe / Tiburon", coupe?.model)
        assertEquals(2003..2003, coupe?.years)
    }

    @Test
    fun modelYear() {
        assertEquals(2008, CarDb.modelYear("1G6DM577980123456"))
        assertEquals(2011, CarDb.modelYear("XW8ZZZ61ZBG000000"))
        assertEquals(2016, CarDb.modelYear("XTAGFK330GY000000"))
        assertNull(CarDb.modelYear("123"))
    }

    @Test
    fun signalBits() {
        val d = intArrayOf(0x12, 0x34, 0xF0)
        assertEquals(0x12L, SignalFormat(0, 8, 1.0, 1.0, 0.0, false, null).raw(d))
        assertEquals(0x1234L, SignalFormat(0, 16, 1.0, 1.0, 0.0, false, null).raw(d))
        assertEquals(0x3L, SignalFormat(10, 2, 1.0, 1.0, 0.0, false, null).raw(d)) // 0x34 = 0011 0100: bits 10-11
        assertEquals(-16L, SignalFormat(16, 8, 1.0, 1.0, 0.0, true, null).raw(d))
        assertNull(SignalFormat(16, 16, 1.0, 1.0, 0.0, false, null).raw(d))
        assertEquals(50.0, SignalFormat(0, 8, 1.0, 1.0, -40.0, false, null).decode(intArrayOf(90))!!.first!!, 1e-9)
        assertEquals(0.5, SignalFormat(0, 8, 1.0, 2.0, 0.0, false, null).decode(intArrayOf(1))!!.first!!, 1e-9)
        // VAG DSG 7E1 22 2104 → "7E9 62 21 04 34 00" = 52 °C, little-endian.
        assertEquals(52L, SignalFormat(0, 16, 1.0, 1.0, 0.0, true, null, le = true).raw(intArrayOf(0x34, 0x00)))
        assertEquals(-2L, SignalFormat(0, 16, 1.0, 1.0, 0.0, true, null, le = true).raw(intArrayOf(0xFE, 0xFF)))
    }

    /** A diesel-only DID (VAG 114F: soot on EDC17, misfires on a petrol ECU) is never sent to a car not known to be diesel. */
    @Test
    fun dieselOnlyNeedsDiesel() {
        val vag = CarDb.family("vag")!!
        val golf = CarDb.models.first { it.family == "vag" && it.model == "Golf" }
        assertTrue(vag.probeOrder(golf, null).none { "diesel" in it.fuel })
        assertTrue(vag.probeOrder(golf, "petrol").none { "diesel" in it.fuel })
        assertTrue(vag.probeOrder(golf, "diesel").any { "diesel" in it.fuel })
        // Known on another model only → probed last and always "(?)".
        val polo = CarDb.models.first { it.family == "vag" && it.model.startsWith("Polo") }
        val order = vag.probeOrder(polo, "petrol")
        val foreign = order.filter { c -> c.models.isNotEmpty() && c.models.none { it.equals(polo.model, true) } }
        assertTrue(foreign.all { c -> c.signals.all { it.confidence == "?" } })
    }

    /** CTS 2.8 (session 2026-09-30): the V8-only 22 119D answered a constant 248 "kPa" on the V6. */
    @Test
    fun cylinderFilter() {
        val gm = CarDb.family("gm")!!.commands
        fun keys(cyl: Int?) = gm.mapNotNull { it.forCylinders(cyl) }.map { it.key }.toSet()
        val v6 = keys(6)
        assertTrue("7E0:22.119D" !in v6) // barometric pressure, V8
        assertTrue("7E0:22.1251" in v6) // barometric pressure, V6
        assertTrue("7E0:22.11EC" !in v6 && "7E0:22.11ED" !in v6) // misfires, cylinders 7/8
        assertTrue("7E0:22.11EA" in v6 && "7E0:22.11EB" in v6) // misfires, cylinders 5/6
        val i4 = keys(4)
        assertTrue("7E0:22.11EA" !in i4 && "7E0:22.1206" in i4 && "7E0:22.1208" in i4)
        assertTrue("7E0:22.1251" !in keys(8) && "7E0:22.119D" in keys(8))
        assertEquals(gm.map { it.key }.toSet(), keys(null))
        // One answer carrying three sets of cylinders 1–6 (Hyundai 7E0 21 08): a four-cylinder drops 5–6 of each.
        val hy = CarDb.family("hyundai")!!.commands.first { it.key == "7E0:21.08" }
        val kept = hy.forCylinders(4)!!.signals
        assertEquals(hy.signals.size - 6, kept.size)
        assertTrue(kept.all { it.cyl == null || 4 in it.cyl!! })
    }

    /** Every value named after a cylinder (3 and up) or an engine layout carries `cyl`, curated or imported. */
    @Test
    fun cylinderNamesHaveCyl() {
        val perCyl = Regex("""\bcyl(?:inder)?\.?\s*#?\s*(\d+)\b""", RegexOption.IGNORE_CASE)
        val layout = Regex("""(?:^|[\s,(])V(6|8|10|12)(?:$|[\s,)])""")
        val missing = mutableListOf<String>()
        for (d in listOf(File("src/main/assets/cars"), File("src/main/assets/cars/obdb"))) {
            for (f in d.listFiles { x -> x.name.endsWith(".json") }.orEmpty()) {
                val cmds = org.json.JSONObject(f.readText()).optJSONArray("commands") ?: continue
                for (i in 0 until cmds.length()) {
                    val sigs = cmds.getJSONObject(i).getJSONArray("signals")
                    for (k in 0 until sigs.length()) {
                        val s = sigs.getJSONObject(k)
                        val en = s.getJSONObject("name").optString("en")
                        val n = perCyl.find(en)?.groupValues?.get(1)?.toInt()
                        if ((n != null && n >= 3 || layout.containsMatchIn(en)) && !s.has("cyl")) missing += "${f.name}: $en"
                    }
                }
            }
        }
        assertTrue("no cyl:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** The picker lists models by id: a repeated one crashed it (BMW E90, RAV4 XA20 split by years). */
    @Test
    fun modelIdsUnique() {
        val dup = CarDb.models.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate model ids: $dup", dup.isEmpty())
    }

    /** Renault-platform modules answer on +0x20 (743 → 763); an OBDb command without a reply id must follow that. */
    @Test
    fun renaultReplyOffset() {
        val cands = com.obdscanner.obd.ObdModules.addressing(CarDb.family("renault"), "Renault").candidates
        assertTrue(0x743 to 0x763 in cands)
        assertTrue(0x7E0 to 0x7E8 in cands)
        val renault = CarDb.family("renault")!!.commands.filter { it.req !in 0x7E0..0x7E7 }
        assertTrue(renault.isNotEmpty() && renault.all { it.resp == it.req + 0x20 })
        assertEquals("MG", CarDb.brandOf("LSJA24U90XX000000")?.name)
    }

    /** A pick is saved as an id: a make alone or a model, and comes back the same. */
    @Test
    fun pickRoundTrip() {
        val make = CarDb.choice("brand:Toyota")
        assertEquals("toyota", make?.family)
        assertNull(make?.model)
        assertEquals("brand:Toyota", make?.id)
        val rav4 = CarDb.models.first { it.model == "RAV4" }
        assertEquals(rav4, CarDb.choice(rav4.id)?.model)
        assertNull(CarDb.choice("brand:NoSuchMake"))
        assertTrue(CarDb.brandNames.containsAll(listOf("Cadillac", "Lada", "Haval")))
    }

    /** The demo car: the VIN finds the CTS, which the database knows by its protocol and socket. */
    @Test
    fun ctsByVin() {
        val car = CarDb.detect("1G6DM577980123456")
        assertNotNull(car)
        assertEquals("CTS", car!!.model)
        assertEquals(6, car.protocol)
    }
}
