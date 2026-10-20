package com.obdscanner.screen

import com.obdscanner.obd.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The car's tile: the drive's span under the title and the value's place in it, nothing while the value hasn't moved. */
class CarScreenTest {
    private fun reading(value: Double?, min: Double? = value, max: Double? = value, decimals: Int = 0) =
        Reading("7E0:01.0C", 0x7E0, "RPM", value, null, "rpm", decimals, min, max)

    @Test
    fun freshValueHasNoSpan() {
        val r = reading(800.0)
        assertEquals(" ", CarScreen.range(r))
        assertNull(CarScreen.position(r))
    }

    @Test
    fun movedValueShowsSpanAndPlace() {
        val r = reading(1315.0, 650.0, 3200.0)
        assertEquals("650 … 3200", CarScreen.range(r))
        assertEquals((1315.0 - 650) / (3200 - 650), CarScreen.position(r)!!, 1e-9)
    }

    @Test
    fun spanKeepsTheReadingsDecimals() {
        assertEquals("${Reading.fmt(-3.1, 1)} … ${Reading.fmt(4.7, 1)}", CarScreen.range(reading(1.0, -3.1, 4.7, decimals = 1)))
    }

    @Test
    fun deadOrTextReadingHasNoSpan() {
        assertEquals(" ", CarScreen.range(null))
        assertEquals(" ", CarScreen.range(Reading("7E0:x", 0x7E0, "Gear", null, "D", "", 0, 1.0, 5.0)))
    }
}
