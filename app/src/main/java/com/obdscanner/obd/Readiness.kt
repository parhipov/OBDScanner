package com.obdscanner.obd

import com.obdscanner.L10n
import com.obdscanner.tr

data class Monitor(val name: String, val available: Boolean, val complete: Boolean)

/** PID 01 / 41 — MIL, DTC count and readiness monitors. */
object Readiness {
    private val SPARK =
        if (L10n.ru) listOf("Катализатор", "Подогрев катализатора", "EVAP (испарения)", "Вторичный воздух",
            "Кондиционер (хладагент)", "Датчики O2", "Подогрев датчиков O2", "EGR/VVT")
        else listOf("Catalyst", "Heated catalyst", "EVAP system", "Secondary air",
            "A/C refrigerant", "O2 sensors", "O2 sensor heaters", "EGR/VVT")
    private val COMPRESSION =
        if (L10n.ru) listOf("NMHC катализатор", "NOx/SCR", "—", "Наддув", "—", "Датчик выхлопа",
            "Сажевый фильтр", "EGR/VVT")
        else listOf("NMHC catalyst", "NOx/SCR", "—", "Boost pressure", "—", "Exhaust gas sensor",
            "PM filter", "EGR/VVT")

    fun monitors(d: IntArray): List<Monitor> {
        if (d.size < 4) return emptyList()
        val b = d[1]
        val out = mutableListOf(
            Monitor(tr("Пропуски зажигания", "Misfire"), b and 0x01 != 0, b and 0x10 == 0),
            Monitor(tr("Топливная система", "Fuel system"), b and 0x02 != 0, b and 0x20 == 0),
            Monitor(tr("Компоненты", "Components"), b and 0x04 != 0, b and 0x40 == 0),
        )
        val names = if (b and 0x08 == 0) SPARK else COMPRESSION
        for (i in 0..7) {
            if (names[i] == "—") continue
            out += Monitor(names[i], (d[2] shr i) and 1 == 1, (d[3] shr i) and 1 == 0)
        }
        return out
    }

    fun summary(d: IntArray, thisCycle: Boolean = false): String {
        val m = monitors(d)
        val notReady = m.filter { it.available && !it.complete }.map { it.name }
        val mil = if (d[0] and 0x80 != 0) tr("ГОРИТ", "ON") else tr("нет", "off")
        val head = if (thisCycle) "" else tr("Check: $mil, ошибок: ${d[0] and 0x7F}; ", "Check: $mil, DTCs: ${d[0] and 0x7F}; ")
        return head + if (notReady.isEmpty()) tr("все мониторы готовы", "all monitors ready")
        else tr("не готовы: ", "not ready: ") + notReady.joinToString(", ")
    }
}
