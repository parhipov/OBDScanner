package com.obdscanner

import com.obdscanner.car.CarDb
import com.obdscanner.screen.CarCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hand pick against the car that answered: a pick that doesn't fit, and a pick kept for one car. */
class PickTest {
    /** A hand-written raw.log: each (command, reply) as the app logs them. */
    private fun log(vararg pairs: Pair<String, String>) = ReplayLog.parse(pairs.joinToString("\n") { (c, r) -> "12:00:00.000 > $c\n12:00:00.040 < $r" })

    /** Polo 9N of 2026-10-13: no VIN, K-line KWP 5-baud, the engine's CALID 03C906014B 9970. */
    private val polo = log(
        "ATZ" to "ELM327 v1.5", "ATSP6" to "OK", "0100" to "NO DATA", "ATSP7" to "OK", "0100" to "NO DATA",
        "ATSP4" to "OK", "0100" to "86 F1 10 41 00 BE 3E B8 11 8D", "ATDPN" to "4",
        "0100" to "86 F1 10 41 00 BE 3E B8 11 8D",
        "0900" to "87 F1 10 49 00 01 30 00 00 00 02",
        "0904" to "87 F1 10 49 04 01 30 33 43 39 B5 | 87 F1 10 49 04 02 30 36 30 31 9E | 87 F1 10 49 04 03 34 42 20 20 8E | 87 F1 10 49 04 04 39 39 37 30 B2",
    )

    /** Kia Sportage QL of 2026-10-14: CAN 11/500 like the CTS, a Kia's VIN (Avtotor, XWE). */
    private val kia = log(
        "ATZ" to "ELM327 v2.1", "ATSP6" to "OK", "0100" to "7E9 06 41 00 80 00 00 01 AA | 7E8 06 41 00 BE 3E A8 13 AA", "ATDPN" to "6",
        "ATSH7DF" to "OK", "0100" to "7E9 06 41 00 80 00 00 01 AA | 7E8 06 41 00 BE 3E A8 13 AA",
        "0900" to "7E9 06 49 00 14 40 00 00 AA | 7E8 06 49 00 55 00 00 00 AA",
        "0902" to "7E8 10 14 49 02 01 58 57 45 | 7E8 21 50 48 38 31 41 44 48 | 7E8 22 30 30 31 32 35 39 36",
    )

    private val cts get() = CarDb.models.single { it.brand == "Cadillac" && it.model == "CTS" && it.gen == "2nd gen" }
    private val polo9n get() = CarDb.models.single { it.brand == "Volkswagen" && it.model == "Polo" && it.gen == "9N" }

    /** CTS picked (CAN 11/500), a Polo answered on K-line: not the CTS — the card says why, GM isn't asked. */
    @Test
    fun aPickOnAnotherProtocolDoesNotFit() {
        CarDbTest.load()
        val r = replay(polo, MapStore(), 0, pick = CarDb.choice(cts.id))
        val v = r.link.vehicle.value
        assertNull(v.car)
        assertEquals(cts, v.misfitPick?.model)
        assertNull(v.misfitMake)
        assertFalse(v.makeByPick)
        val card = CarCard.build(v, CarDb.choice(cts.id))
        assertEquals(tr("не определена", "unknown"), card.title)        // the CI runs in English
        assertTrue(card.status.contains(tr("не подходит", "doesn't fit")) && card.status.contains("CAN 11/500"), card.status)
    }

    /** Polo 9N picked while connected to it: the next connection to the same car takes it by its CALID. */
    @Test
    fun aPickMadeOnACarComesBackOnIt() {
        CarDbTest.load()
        val store = MapStore()
        val first = replay(polo, store, 0, pick = CarDb.choice(cts.id))
        first.link.pickCar(CarDb.choice(polo9n.id), connected = true)
        assertEquals(polo9n, first.link.vehicle.value.car)
        // The app-wide pick is still the CTS (picked anywhere else): this car keeps its own.
        val again = replay(polo, store, 0, pick = CarDb.choice(cts.id))
        val v = again.link.vehicle.value
        assertEquals(polo9n, v.car)
        assertTrue(v.pickForCar)
        assertNull(v.misfitPick)
        assertTrue(CarCard.build(v, CarDb.choice(cts.id)).status.startsWith(tr("выбрана для этой машины", "picked for this car")))
    }

    /** A pick made with no car connected stays app-wide only: nothing is kept for the last car seen. */
    @Test
    fun aPickWhileDisconnectedIsNotKeptForTheLastCar() {
        CarDbTest.load()
        val store = MapStore()
        replay(polo, store, 0, pick = CarDb.choice(cts.id)).link.pickCar(CarDb.choice(polo9n.id), connected = false)
        assertEquals(cts, replay(polo, store, 0, pick = CarDb.choice(cts.id)).link.vehicle.value.misfitPick?.model)
    }

    /** CTS picked, a Kia answered on the same CAN 11/500: its VIN says it isn't the CTS — the card and the report say so. */
    @Test
    fun aPickOfAnotherMakeThanTheVinDoesNotFit() {
        CarDbTest.load()
        val r = replay(kia, MapStore(), 0, pick = CarDb.choice(cts.id))
        val v = r.link.vehicle.value
        assertNull(v.car)
        assertEquals(cts, v.misfitPick?.model)
        assertEquals("Kia", v.misfitMake)
        val card = CarCard.build(v, CarDb.choice(cts.id))
        assertEquals("Kia", card.title)
        assertTrue(card.status.contains("VIN") && card.status.contains("Kia"), card.status)
        val car = r.reports.single { it.first == tr("Машина", "Car") }.second
        assertTrue(car.contains(tr("не подходит: по VIN это Kia", "doesn't fit: the VIN says Kia")), car)
    }

    /** The CTS picked while connected to the Kia: not applied, and the Kia doesn't bring it back next time. */
    @Test
    fun aPickOfAnotherMakeIsNotKeptForTheCar() {
        CarDbTest.load()
        val store = MapStore()
        val first = replay(kia, store, 0)
        first.link.pickCar(CarDb.choice(cts.id), connected = true)
        assertNull(first.link.vehicle.value.car)
        assertEquals("Kia", first.link.vehicle.value.misfitMake)
        val car = replay(kia, store, 0).reports.single { it.first == tr("Машина", "Car") }.second
        assertFalse(car.contains(tr("Выбрано для этой машины", "Picked for this car")), car)
    }
}
