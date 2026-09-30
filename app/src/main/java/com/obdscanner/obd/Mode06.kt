package com.obdscanner.obd

import com.obdscanner.tr

/** One on-board monitor test result (Mode 06, CAN format). */
data class TestResult(
    val ecu: Int,
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val rawValue: Int,
    val rawMin: Int,
    val rawMax: Int,
) {
    private val scale = Mode06.scaling(uasid)
    val value get() = scale.apply(rawValue)
    val min get() = scale.apply(rawMin)
    val max get() = scale.apply(rawMax)
    val unit get() = scale.unit
    /** All zeros = the ECU hasn't run this test since the last clear (common for catalyst/EVAP). */
    val notRun get() = rawValue == 0 && rawMin == 0 && rawMax == 0
    val status get() = when { notRun -> tr("не выполнялся", "not run"); passed -> "OK"; else -> tr("НЕ ПРОЙДЕН", "FAILED") }
    val passed get() = if (scale.signed) sgn(rawValue) in sgn(rawMin)..sgn(rawMax) else rawValue in rawMin..rawMax
    val midName get() = Mode06.midName(mid)
    val tidName get() = Mode06.tidName(mid, tid)
    val misfireCylinder get() = if (mid in 0xA2..0xAD) mid - 0xA1 else null

    private fun sgn(v: Int) = if (v >= 32768) v - 65536 else v
}

class Scaling(val factor: Double, val unit: String, val offset: Double = 0.0, val signed: Boolean = false) {
    fun apply(raw: Int): Double {
        val v = if (signed && raw >= 32768) raw - 65536 else raw
        return v * factor + offset
    }
}

object Mode06 {
    /** Unit And Scaling IDs (SAE J1979 / ISO 15031-5). Unknown ones fall back to raw counts. */
    private val UAS = mapOf(
        0x01 to Scaling(1.0, ""), 0x02 to Scaling(0.1, ""), 0x03 to Scaling(0.01, ""), 0x04 to Scaling(0.001, ""),
        0x05 to Scaling(0.0000305, ""), 0x06 to Scaling(0.000305, ""),
        0x07 to Scaling(0.25, tr("об/мин", "rpm")), 0x08 to Scaling(0.01, tr("км/ч", "km/h")), 0x09 to Scaling(1.0, tr("км/ч", "km/h")),
        0x0A to Scaling(0.000122, tr("В", "V")), 0x0B to Scaling(0.001, tr("В", "V")), 0x0C to Scaling(0.01, tr("В", "V")),
        0x0D to Scaling(0.00390625, tr("мА", "mA")), 0x0E to Scaling(0.001, tr("А", "A")), 0x0F to Scaling(0.01, tr("А", "A")),
        0x10 to Scaling(1.0, tr("мс", "ms")), 0x11 to Scaling(100.0, tr("мс", "ms")), 0x12 to Scaling(1.0, tr("с", "s")),
        0x13 to Scaling(1.0, tr("мОм", "mΩ")), 0x14 to Scaling(1.0, tr("Ом", "Ω")), 0x15 to Scaling(1.0, tr("кОм", "kΩ")),
        0x16 to Scaling(0.1, "°C", -40.0), 0x17 to Scaling(0.01, tr("кПа", "kPa")), 0x18 to Scaling(0.0117, tr("кПа", "kPa")),
        0x19 to Scaling(0.079, tr("кПа", "kPa")), 0x1A to Scaling(1.0, tr("кПа", "kPa")), 0x1B to Scaling(10.0, tr("кПа", "kPa")),
        0x1C to Scaling(0.01, "°"), 0x1D to Scaling(0.5, "°"), 0x1E to Scaling(0.0000305, "λ"),
        0x1F to Scaling(0.05, "AFR"), 0x20 to Scaling(0.0039062, ""), 0x21 to Scaling(0.001, tr("Гц", "Hz")),
        0x22 to Scaling(1.0, tr("Гц", "Hz")), 0x23 to Scaling(1000.0, tr("Гц", "Hz")), 0x24 to Scaling(1.0, tr("шт", "count")),
        0x25 to Scaling(1.0, tr("км", "km")), 0x26 to Scaling(0.1, tr("мВ/мс", "mV/ms")), 0x27 to Scaling(0.01, tr("г/с", "g/s")),
        0x28 to Scaling(1.0, tr("г/с", "g/s")), 0x29 to Scaling(0.25, tr("Па/с", "Pa/s")), 0x2A to Scaling(0.001, tr("кг/ч", "kg/h")),
        0x2B to Scaling(1.0, tr("перекл.", "switches")), 0x2C to Scaling(0.01, tr("г/цил", "g/cyl")), 0x2D to Scaling(0.01, tr("мг/такт", "mg/stroke")),
        0x2E to Scaling(1.0, tr("да/нет", "yes/no")), 0x2F to Scaling(0.01, "%"), 0x30 to Scaling(0.001526, "%"),
        0x31 to Scaling(0.001, tr("л", "L")), 0x32 to Scaling(0.0007747, tr("мм", "mm")), 0x33 to Scaling(0.00024414, "λ"),
        0x34 to Scaling(1.0, tr("мин", "min")), 0x35 to Scaling(10.0, tr("мс", "ms")), 0x36 to Scaling(0.01, tr("г", "g")),
        0x37 to Scaling(0.1, tr("г", "g")), 0x38 to Scaling(1.0, tr("г", "g")), 0x39 to Scaling(0.01, "%", -327.68),
        0x3A to Scaling(0.001, tr("г", "g")), 0x3B to Scaling(0.0001, tr("г", "g")), 0x3C to Scaling(0.1, tr("мкс", "µs")),
        0x3D to Scaling(0.01, tr("мА", "mA")), 0x3E to Scaling(0.00006103516, tr("мм²", "mm²")), 0x3F to Scaling(0.01, tr("л", "L")),
        0x40 to Scaling(1.0, "ppm"), 0x41 to Scaling(0.01, tr("мкА", "µA")),
        0x81 to Scaling(1.0, "", signed = true), 0x82 to Scaling(0.1, "", signed = true),
        0x83 to Scaling(0.01, "", signed = true), 0x84 to Scaling(0.001, "", signed = true),
        0x85 to Scaling(0.0000305, "", signed = true), 0x86 to Scaling(0.000305, "", signed = true),
        0x8A to Scaling(0.000122, tr("В", "V"), signed = true), 0x8B to Scaling(0.001, tr("В", "V"), signed = true),
        0x8C to Scaling(0.01, tr("В", "V"), signed = true), 0x8D to Scaling(0.00390625, tr("мА", "mA"), signed = true),
        0x8E to Scaling(0.001, tr("А", "A"), signed = true), 0x90 to Scaling(1.0, tr("мс", "ms"), signed = true),
        0x87 to Scaling(1.0, "ppm", signed = true), 0x99 to Scaling(0.1, tr("кПа", "kPa"), signed = true),
        0xAD to Scaling(0.01, tr("мг/такт", "mg/stroke"), signed = true), 0xAE to Scaling(0.1, tr("мг/такт", "mg/stroke"), signed = true),
        0xFC to Scaling(0.01, tr("кПа", "kPa"), signed = true),
        0x96 to Scaling(0.1, "°C", signed = true), 0x9C to Scaling(0.01, "°", signed = true),
        0x9D to Scaling(0.5, "°", signed = true), 0xA8 to Scaling(1.0, tr("г/с", "g/s"), signed = true),
        0xA9 to Scaling(0.25, tr("Па/с", "Pa/s"), signed = true), 0xAF to Scaling(0.01, "%", signed = true),
        0xB0 to Scaling(0.003052, "%", signed = true), 0xB1 to Scaling(2.0, tr("мВ/с", "mV/s"), signed = true),
        0xFD to Scaling(0.001, tr("кПа", "kPa"), signed = true), 0xFE to Scaling(0.25, tr("Па", "Pa"), signed = true),
    )

    /** Unknown IDs: raw counts — signed for 0x80 and up (J1979: the signed half of the table), or pass/fail breaks on negatives. */
    fun scaling(uasid: Int) = UAS[uasid] ?: Scaling(1.0, "raw", signed = uasid >= 0x80)

    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> tr("Датчик O2 ", "O2 sensor ") + sensor(mid - 1)
        0x21 -> tr("Катализатор Б1", "Catalyst B1")
        0x22 -> tr("Катализатор Б2", "Catalyst B2")
        0x23 -> tr("Катализатор Б3", "Catalyst B3")
        0x24 -> tr("Катализатор Б4", "Catalyst B4")
        0x31 -> tr("EGR Б1", "EGR B1")
        0x32 -> tr("EGR Б2", "EGR B2")
        0x33 -> tr("EGR Б3", "EGR B3")
        0x34 -> tr("EGR Б4", "EGR B4")
        0x35 -> tr("VVT Б1", "VVT B1")
        0x36 -> tr("VVT Б2", "VVT B2")
        0x37 -> tr("VVT Б3", "VVT B3")
        0x38 -> tr("VVT Б4", "VVT B4")
        0x39 -> tr("EVAP (утечка 0.150\")", "EVAP (0.150\" leak)")
        0x3A -> tr("EVAP (утечка 0.090\")", "EVAP (0.090\" leak)")
        0x3B -> tr("EVAP (утечка 0.040\")", "EVAP (0.040\" leak)")
        0x3C -> tr("EVAP (утечка 0.020\")", "EVAP (0.020\" leak)")
        0x3D -> tr("Поток продувки EVAP", "EVAP purge flow")
        in 0x41..0x50 -> tr("Подогрев O2 ", "O2 heater ") + sensor(mid - 0x41)
        0x61 -> tr("Подогрев катализатора Б1", "Catalyst heater B1")
        0x62 -> tr("Подогрев катализатора Б2", "Catalyst heater B2")
        0x63 -> tr("Подогрев катализатора Б3", "Catalyst heater B3")
        0x64 -> tr("Подогрев катализатора Б4", "Catalyst heater B4")
        0x71 -> tr("Вторичный воздух 1", "Secondary air 1")
        0x72 -> tr("Вторичный воздух 2", "Secondary air 2")
        0x73 -> tr("Вторичный воздух 3", "Secondary air 3")
        0x74 -> tr("Вторичный воздух 4", "Secondary air 4")
        0x81 -> tr("Топливная система Б1", "Fuel system B1")
        0x82 -> tr("Топливная система Б2", "Fuel system B2")
        0x83 -> tr("Топливная система Б3", "Fuel system B3")
        0x84 -> tr("Топливная система Б4", "Fuel system B4")
        0x85 -> tr("Давление наддува Б1", "Boost pressure control B1")
        0x86 -> tr("Давление наддува Б2", "Boost pressure control B2")
        0x90 -> tr("Накопитель NOx Б1", "NOx absorber B1")
        0x91 -> tr("Накопитель NOx Б2", "NOx absorber B2")
        0x98 -> tr("Катализатор NOx/SCR Б1", "NOx/SCR catalyst B1")
        0x99 -> tr("Катализатор NOx/SCR Б2", "NOx/SCR catalyst B2")
        0xA1 -> tr("Пропуски зажигания (общее)", "Misfire (general)")
        in 0xA2..0xAD -> tr("Пропуски, цилиндр ${mid - 0xA1}", "Misfire, cylinder ${mid - 0xA1}")
        0xB0, 0xB1 -> tr("Сажевый фильтр", "Particulate filter")
        else -> "MID %02X".format(mid)
    }

    fun tidName(mid: Int, tid: Int): String {
        if (mid in 0xA1..0xAD) return when (tid) {
            0x0B -> tr("Пропуски: среднее за 10 циклов", "Misfire: 10-cycle average")
            0x0C -> tr("Пропуски: последний/текущий цикл", "Misfire: last/current cycle")
            else -> "TID %02X".format(tid)
        }
        if (mid in 0x01..0x10) return when (tid) {
            0x01 -> tr("Порог богато→бедно", "Threshold rich→lean")
            0x02 -> tr("Порог бедно→богато", "Threshold lean→rich")
            0x03 -> tr("Низкое напряжение (для времени)", "Low voltage (for switch time)")
            0x04 -> tr("Высокое напряжение (для времени)", "High voltage (for switch time)")
            0x05 -> tr("Время богато→бедно", "Switch time rich→lean")
            0x06 -> tr("Время бедно→богато", "Switch time lean→rich")
            0x07 -> tr("Мин. напряжение", "Min. voltage")
            0x08 -> tr("Макс. напряжение", "Max. voltage")
            0x09 -> tr("Время между переходами", "Time between transitions")
            0x0A -> tr("Период", "Period")
            else -> tr("TID %02X (произв.)", "TID %02X (mfr)").format(tid)
        }
        return if (tid >= 0x80) tr("TID %02X (произв.)", "TID %02X (mfr)").format(tid) else "TID %02X".format(tid)
    }

    /** O2 sensor position by index 0..15: bank 1 sensor 1 … bank 4 sensor 4. */
    private fun sensor(i: Int) = tr("Б%dД%d", "B%dS%d").format(i / 4 + 1, i % 4 + 1)

    /** Reply to "06 MID": [46, (MID TID UAS vH vL minH minL maxH maxL)*]. */
    fun parse(data: IntArray, ecu: Int): List<TestResult> {
        val out = mutableListOf<TestResult>()
        var i = 1
        while (i + 8 < data.size) {
            out += TestResult(
                ecu, data[i], data[i + 1], data[i + 2],
                data[i + 3] * 256 + data[i + 4], data[i + 5] * 256 + data[i + 6], data[i + 7] * 256 + data[i + 8],
            )
            i += 9
        }
        return out
    }
}
