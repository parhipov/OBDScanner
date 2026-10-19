package com.obdscanner

import com.obdscanner.obd.RoleValues
import com.obdscanner.obd.RoleValues.Value
import org.junit.Assert.assertEquals
import org.junit.Test

/** Several values of one role: the dead one out, OBDb's duplicate of the same bytes folded, the checked one first. */
class RoleValuesTest {
    private fun keys(vararg v: Value) = RoleValues.order(v.toList()).map { it.key }

    @Test
    fun theDeadOneIsLeftOut() = assertEquals(listOf("7E0:22.2345.BLAZER_EOP"), keys(
        Value("7E0:22.1470", "Давление масла", true, 0.0, 0.0),
        Value("7E0:22.2345.BLAZER_EOP", "Давление масла ДВС", false, 136.0, 296.0)))

    @Test
    fun allAtZeroBeforeTheEngineRuns() = assertEquals(listOf("7E0:22.1470", "7E0:22.2345.BLAZER_EOP"), keys(
        Value("7E0:22.2345.BLAZER_EOP", "Давление масла ДВС", false, 0.0, 0.0),
        Value("7E0:22.1470", "Давление масла", true, 0.0, 0.0)))

    @Test
    fun theSameBytesUnderTwoNamesAreOne() = assertEquals(listOf("7E0:22.2345.BLAZER_EOP", "7E0:22.115C.BLAZER_EOP_2"), keys(
        Value("7E0:22.2345.CAMARO_EOP_ACT", "Давление масла ДВС, факт.", false, 136.0, 296.0),
        Value("7E0:22.115C.BLAZER_EOP_2", "Давление масла ДВС 2", false, 128.0, 314.0),
        Value("7E0:22.2345.BLAZER_EOP", "Давление масла ДВС", false, 136.0, 296.0)))

    @Test
    fun theCheckedOneFirstThenTheShorterName() = assertEquals(listOf("7E0:22.1470", "7E0:22.2345.BLAZER_EOP", "7E0:22.1C1B.BLAZER_EOP_CALC"), keys(
        Value("7E0:22.1C1B.BLAZER_EOP_CALC", "Расчётное давление масла ДВС", false, 152.0, 156.0),
        Value("7E0:22.2345.BLAZER_EOP", "Давление масла ДВС", false, 136.0, 296.0),
        Value("7E0:22.1470", "Давление масла", true, 100.0, 450.0)))

    @Test
    fun differentValuesOfOneRequestStay() = assertEquals(listOf("7E0:22.2344.BLAZER_EOP_ABS", "7E0:22.2344.CAMARO_EOP_GAUGE"), keys(
        Value("7E0:22.2344.CAMARO_EOP_GAUGE", "Давление масла ДВС, избыточное", false, 136.0, 296.0),
        Value("7E0:22.2344.BLAZER_EOP_ABS", "Абсолютное давление масла ДВС", false, 228.0, 420.0)))
}
