package com.obdscanner

import com.obdscanner.obd.Reading
import com.obdscanner.screen.AllScreen
import com.obdscanner.screen.Block
import com.obdscanner.screen.CarScreen
import com.obdscanner.screen.MainScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's screen (Android Auto): the chosen groups on «Все данные» and the fixed tiles. */
class CarScreenTest {
    private fun r(pid: String, name: String, value: Double, unit: String = "") = Reading(Reading.key(0x7E8, "01.$pid"), 0x7E8, name, value, null, unit)

    private val readings = listOf(
        r("0C", "Обороты", 800.0, "об/мин"), r("0D", "Скорость", 0.0, "км/ч"), r("05", "ОЖ", 90.0, "°C"), r("04", "Нагрузка", 20.0, "%"),
        r("11", "Дроссель", 12.0, "%"), r("0E", "Опережение", 10.0, "°"), r("0F", "Воздух на впуске", 25.0, "°C"), r("42", "Бортсеть", 14.1, "В"),
    ).associateBy { it.key }

    private fun key(pid: String) = Reading.key(0x7E8, "01.$pid")

    @Test
    fun chosenGroupsComeFirst() {
        val car = listOf("0C", "05", "0D", "04", "11", "0E", "42").map(::key)
        val blocks = AllScreen.build(readings, VehicleInfo(), pins = listOf(key("42"), key("0C")), carPins = car, carScreen = true)
        val folds = blocks.filterIsInstance<Block.Fold>()
        assertEquals(listOf(MainScreen.PINNED, CarScreen.TITLE), folds.take(2).map { it.text })
        assertEquals("7", folds[1].count)
        val rows = blocks.filterIsInstance<Block.Value>()
        // Favourites in their order, with the arrows; the first row has no «Выше», the last no «Ниже».
        val fav = rows.filter { it.order?.car == false }
        assertEquals(listOf("42", "0C"), fav.map { it.reading.source.removePrefix("01.") })
        assertTrue(fav.first().order!!.first && !fav.first().order!!.last)
        assertTrue(!fav.last().order!!.first && fav.last().order!!.last)
        // The car group: a line after the sixth of seven rows, none after the last; every row carries both toggles.
        val carRows = rows.filter { it.order?.car == true }
        assertEquals(car, carRows.map { it.reading.key })
        assertEquals(listOf(5), carRows.withIndex().filter { it.value.divider }.map { it.index })
        assertEquals(true, carRows.first().car)
        assertEquals(true, carRows.first().pin)
        // The same values in their module's group keep both icons, without arrows.
        val plain = rows.filter { it.order == null }
        assertEquals(readings.size, plain.size)
        assertEquals(true, plain.single { it.reading.source == "01.05" }.car)
        assertEquals(false, plain.single { it.reading.source == "01.05" }.pin)
    }

    @Test
    fun carScreenNeverRun_noCarIconAndNoGroup() {
        val blocks = AllScreen.build(readings, VehicleInfo(), pins = listOf(key("0C")), carPins = listOf(key("05")), carScreen = false)
        assertEquals(listOf(MainScreen.PINNED), blocks.filterIsInstance<Block.Fold>().filter { it.id.startsWith("all:") && !it.id.startsWith("all:7") }.map { it.text })
        assertTrue(blocks.filterIsInstance<Block.Value>().all { it.car == null })
    }

    @Test
    fun searchHidesTheGroups() {
        val blocks = AllScreen.build(readings, VehicleInfo(), pins = listOf(key("0C")), filter = "обороты", carPins = listOf(key("05")), carScreen = true)
        assertTrue(blocks.filterIsInstance<Block.Value>().none { it.order != null })
    }

    @Test
    fun slots_chosenElseMain() {
        val v = VehicleInfo()
        // Chosen values that are live, in their order, with the unit they keep.
        val chosen = CarScreen.slots(readings, v, false, listOf(key("05"), "7E8:01.FF", key("0C")), emptyList(), emptySet())
        assertEquals(listOf("ОЖ", "Обороты"), chosen.map { it.label })
        assertEquals("°C", chosen[0].unit)
        // Nothing chosen (or nothing of it live): Main's cards as the phone shows them, favourites first.
        val main = CarScreen.slots(readings, v, false, listOf("7E8:01.FF"), listOf(key("42")), emptySet())
        assertEquals(key("42"), main.first().key)
        assertEquals(readings.size, main.size)
        assertEquals(listOf(6, 2), CarScreen.pages(main).map { it.size })
        // No live values yet: nothing to fix.
        assertTrue(CarScreen.slots(emptyMap(), v, false, listOf(key("05")), emptyList(), emptySet()).isEmpty())
        // A tile: the value now, or nothing when the reading is not live.
        assertEquals(90.0, CarScreen.tile(chosen[0], readings)!!.reading.value)
        assertNull(CarScreen.tile(CarScreen.Slot("7E8:01.FF", "x", ""), readings))
    }
}
