package com.obdscanner

import com.obdscanner.obd.Reading
import com.obdscanner.screen.Block
import com.obdscanner.screen.MainScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Main's oil pressure is one card, the value that moves (issue #4: on a Trailblazer the CTS's 22.1470 stays at 0, an
 * OBDb request carries the pressure); and Main says how to put a value of one's own on it.
 */
class MainOilTest {
    private fun r(source: String, now: Double, lo: Double, hi: Double, unit: String) =
        Reading(Reading.key(0x7E0, source), 0x7E0, source, now, null, unit, 0, lo, hi, role = "oil_pressure")

    private fun oil(vararg r: Reading) = MainScreen.build(r.associateBy { it.key } + Reading(Reading.key(0x7E8, "01.0C"), 0x7E8, "RPM", 800.0, null, "", 0)
        .let { mapOf(it.key to it) }, VehicleInfo()).sections.single { it.title == tr("Масло", "Oil") }.tiles

    @Test
    fun theMovingOneOnATrailblazer() {
        val tiles = oil(r("22.1470", 0.0, 0.0, 0.0, "кПа"), r("22.115C.BLAZER_EOP_2", 250.0, 0.0, 310.0, "kPa"), r("22.2345.BLAZER_EOP", 0.0, 0.0, 0.0, "kPa"))
        assertEquals(listOf("7E0:22.115C.BLAZER_EOP_2"), tiles.map { it.reading.key })
    }

    @Test
    fun theCtsKeepsItsOwn() {
        val tiles = oil(r("22.1470", 420.0, 0.0, 450.0, "кПа"), r("22.115C.BLAZER_EOP_2", 294.0, 0.0, 300.0, "kPa"))
        assertEquals(listOf("7E0:22.1470"), tiles.map { it.reading.key })
    }

    @Test
    fun beforeTheEngineRuns() {
        val tiles = oil(r("22.1470", 0.0, 0.0, 0.0, "кПа"), r("22.115C.BLAZER_EOP_2", 0.0, 0.0, 0.0, "kPa"))
        assertEquals(listOf("7E0:22.1470"), tiles.map { it.reading.key })
    }

    @Test
    fun favouritesAreSaidUntilThereAreSome() {
        val bare = MainScreen.build(emptyMap(), VehicleInfo())
        assertTrue(bare.bottom.any { it is Block.Note && "☆" in it.text })
        val pinned = MainScreen.build(emptyMap(), VehicleInfo(), pins = listOf("7E8:01.0C"))
        assertTrue(pinned.bottom.none { it is Block.Note && "☆" in it.text })
    }
}
