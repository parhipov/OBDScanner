package com.obdscanner.screen

import com.obdscanner.obd.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The owner's thresholds over the built-in ones, and how they survive the store. */
class LimitsTest {
    private fun coolant(v: Double) = Reading("7E8:01.05", 0x7E8, "Coolant", v, null, "°C", 0)
    private fun rpm(v: Double) = Reading("7E8:01.0C", 0x7E8, "RPM", v, null, "rpm", 0)

    @Test
    fun builtInCoolantRuleAlone() {
        assertNull(MainScreen.tileLevel(coolant(96.0)))
        assertEquals(Level.WARN, MainScreen.tileLevel(coolant(60.0)))
        assertEquals(Level.BAD, MainScreen.tileLevel(coolant(106.0)))
    }

    @Test
    fun ownersAmberAt100KeepsBuiltInRedAt105() {
        val limits = mapOf("01.05" to Limits(warnAbove = 100.0))
        assertNull(MainScreen.tileLevel(coolant(96.0), limits))
        assertEquals(Level.WARN, MainScreen.tileLevel(coolant(101.0), limits))
        assertEquals(Level.BAD, MainScreen.tileLevel(coolant(106.0), limits))
        assertEquals(Level.WARN, MainScreen.tileLevel(coolant(60.0), limits))
    }

    @Test
    fun rpmHasNoRuleUntilTheOwnerSetsOne() {
        assertNull(MainScreen.tileLevel(rpm(6000.0)))
        val limits = mapOf("01.0C" to Limits(warnAbove = 4500.0, badAbove = 5500.0))
        assertNull(MainScreen.tileLevel(rpm(3000.0), limits))
        assertEquals(Level.WARN, MainScreen.tileLevel(rpm(5000.0), limits))
        assertEquals(Level.BAD, MainScreen.tileLevel(rpm(5600.0), limits))
    }

    @Test
    fun storeRoundTrip() {
        val all = mapOf("01.05" to Limits(warnAbove = 100.0), "01.0C" to Limits(warnAbove = 4500.0, badAbove = 5500.0))
        assertEquals(all, Limits.parse(Limits.serialize(all)))
        assertEquals(setOf("01.05;;100;;"), Limits.serialize(mapOf("01.05" to Limits(warnAbove = 100.0))))
        // What 4.12–4.13 wrote on the phone reads back the same.
        assertEquals(mapOf("01.05" to Limits(warnAbove = 100.0)), Limits.parse(setOf("01.05;;100.0;;")))
        assertEquals(emptyMap(), Limits.parse(setOf("garbage", "01.05;;;;", "01.05;x;;;")))
    }
}
