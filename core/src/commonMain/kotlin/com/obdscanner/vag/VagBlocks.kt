package com.obdscanner.vag

import com.obdscanner.obd.Reading
import com.obdscanner.tr
import com.obdscanner.util.format
import kotlin.math.abs

/**
 * VAG measuring blocks: the answer to KWP \$21 <group> is 3 bytes a value — formula type, a, b — and the type alone
 * says how to get the number and its unit (what the value IS — rpm of what, which temperature — is in the label files
 * of VCDS, not published: the app shows the group and field number). Only the formulas two sources agree on, or the
 * one marked checked on a car: blafusel.de (KW1281 table, √ — checked) and vag-blocks (kwp2000.cpp, read on TP 2.0).
 * Any other type is shown as it came: "type NN: a b".
 */
object VagBlocks {
    class Field(val type: Int, val a: Int, val b: Int, val value: Double?, val text: String, val unit: String, val decimals: Int)

    private class F(val unit: String, val decimals: Int, val calc: (Int, Int) -> Double)

    private val FORMULAS: Map<Int, F> = mapOf(
        1 to F(tr("об/мин", "rpm"), 0) { a, b -> 0.2 * a * b },
        5 to F("°C", 1) { a, b -> 0.1 * a * b - 10.0 * a },
        6 to F(tr("В", "V"), 2) { a, b -> 0.001 * a * b },
        7 to F(tr("км/ч", "km/h"), 1) { a, b -> 0.01 * a * b },
        18 to F(tr("мбар", "mbar"), 0) { a, b -> 0.04 * a * b },
        19 to F(tr("л", "l"), 1) { a, b -> 0.01 * a * b },
        21 to F(tr("В", "V"), 3) { a, b -> 0.001 * a * b },
        22 to F(tr("мс", "ms"), 2) { a, b -> 0.001 * a * b },
        23 to F("%", 1) { a, b -> b / 256.0 * a },
        26 to F("°C", 0) { a, b -> (b - a).toDouble() },
        33 to F("%", 1) { a, b -> if (a == 0) 100.0 * b else 100.0 * b / a },
        34 to F(tr("кВт", "kW"), 1) { a, b -> (b - 128) * 0.01 * a },
        35 to F(tr("л/ч", "l/h"), 2) { a, b -> 0.01 * a * b },
        36 to F(tr("км", "km"), 0) { a, b -> a * 2560.0 + b * 10.0 },
        39 to F(tr("мг/ход", "mg/str"), 2) { a, b -> b / 256.0 * a },
        49 to F(tr("мг/ход", "mg/str"), 1) { a, b -> b / 4.0 * a * 0.1 },
        51 to F(tr("мг/ход", "mg/str"), 2) { a, b -> (b - 128) / 255.0 * a },
        54 to F("", 0) { a, b -> a * 256.0 + b },
        55 to F(tr("с", "s"), 2) { a, b -> a * b / 200.0 },
        64 to F(tr("Ом", "Ohm"), 0) { a, b -> (a + b).toDouble() },
    )

    /** The fields of a \$21 answer (data after "61 <group>"); null — not 3-byte values. */
    fun decode(d: IntArray): List<Field>? {
        if (d.isEmpty() || d.size % 3 != 0) return null
        return d.toList().chunked(3).map { (t, a, b) -> field(t, a, b) }
    }

    private fun field(t: Int, a: Int, b: Int): Field {
        FORMULAS[t]?.let { f ->
            val v = f.calc(a, b)
            return Field(t, a, b, v, Reading.fmt(v, f.decimals) + if (f.unit.isNotEmpty()) " ${f.unit}" else "", f.unit, f.decimals)
        }
        return when (t) {
            // Both sources: ignition angle, a·|b−127|/100, after TDC above 127.
            4 -> {
                val v = abs(b - 127) * 0.01 * a
                Field(t, a, b, if (b > 127) -v else v, Reading.fmt(v, 1) + if (b > 127) tr("° после ВМТ", "° ATDC") else tr("° до ВМТ", "° BTDC"), "°", 1)
            }
            16 -> Field(t, a, b, null, tr("биты", "bits") + " %s %s".format(bits(a), bits(b)), "", 0)
            17 -> Field(t, a, b, null, "«" + listOf(a, b).joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "·" } + "»", "", 0)
            44 -> Field(t, a, b, null, "%02d:%02d".format(a, b), "", 0)
            else -> Field(t, a, b, null, tr("тип %d: %02X %02X", "type %d: %02X %02X").format(t, a, b), "", 0)
        }
    }

    private fun bits(x: Int) = (7 downTo 0).joinToString("") { if (x shr it and 1 == 1) "1" else "0" }

    /** "1) 820 об/мин · 2) 92.0 °C · 3) тип 37: 0A 3C"; null — not a measuring block. */
    fun describe(d: IntArray): String? = decode(d)?.withIndex()?.joinToString(" · ") { (i, f) -> "${i + 1}) ${f.text}" }

    /** The printable runs of 4+ characters of an identification answer (1A 9B: part number, system name…). */
    fun textRuns(d: IntArray): String = Regex("[\\x20-\\x7E]{4,}").findAll(d.map { it.toChar() }.joinToString(""))
        .map { it.value.trim() }.filter { it.length >= 4 }.joinToString(" · ")

    /** VCDS address → module name (the same names as the make's module list). */
    private val NAMES: Map<Int, Pair<String, String>> = mapOf(
        0x01 to ("Двигатель" to "Engine"),
        0x02 to ("КПП" to "Transmission"),
        0x03 to ("ABS" to "ABS"),
        0x05 to ("Доступ и запуск" to "Access and start"),
        0x06 to ("Сиденье пассажира" to "Passenger seat"),
        0x07 to ("Блок на передней панели" to "Front info/control"),
        0x08 to ("Климат" to "Climate"),
        0x09 to ("Бортовая электроника" to "Central electrics"),
        0x10 to ("Парктроник 2" to "Parking aid 2"),
        0x11 to ("Двигатель 2" to "Engine 2"),
        0x13 to ("Адаптивный круиз" to "Distance regulation"),
        0x14 to ("Подвеска" to "Suspension"),
        0x15 to ("Подушки" to "Airbags"),
        0x16 to ("Подрулевой блок" to "Steering wheel"),
        0x17 to ("Приборка" to "Instruments"),
        0x18 to ("Автономный отопитель" to "Aux heater"),
        0x19 to ("Шлюз" to "Gateway"),
        0x20 to ("Ассистент дальнего света" to "High beam assist"),
        0x22 to ("Полный привод" to "All wheel drive"),
        0x25 to ("Иммобилайзер" to "Immobiliser"),
        0x26 to ("Крыша кабриолета" to "Convertible top"),
        0x29 to ("Левая фара" to "Left headlight"),
        0x31 to ("Диагностический интерфейс" to "Diagnostic interface"),
        0x34 to ("Регулировка уровня кузова" to "Level control"),
        0x35 to ("Центральный замок" to "Central locking"),
        0x36 to ("Сиденье водителя" to "Driver seat"),
        0x37 to ("Навигация" to "Navigation"),
        0x39 to ("Правая фара" to "Right headlight"),
        0x42 to ("Дверь водителя" to "Driver door"),
        0x44 to ("Усилитель руля" to "Power steering"),
        0x45 to ("Контроль салона" to "Interior monitoring"),
        0x46 to ("Комфорт" to "Comfort system"),
        0x47 to ("Аудиосистема" to "Sound system"),
        0x52 to ("Дверь пассажира" to "Passenger door"),
        0x53 to ("Стояночный тормоз" to "Parking brake"),
        0x55 to ("Фары" to "Headlights"),
        0x56 to ("Радио" to "Radio"),
        0x57 to ("ТВ-тюнер" to "TV tuner"),
        0x61 to ("Батарея" to "Battery"),
        0x62 to ("Задняя левая дверь" to "Rear left door"),
        0x65 to ("Давление в шинах" to "Tyre pressure"),
        0x67 to ("Голосовое управление" to "Voice control"),
        0x68 to ("Стеклоочистители" to "Wipers"),
        0x69 to ("Прицеп" to "Trailer"),
        0x72 to ("Задняя правая дверь" to "Rear right door"),
        0x75 to ("Телематика" to "Telematics"),
        0x76 to ("Парктроник" to "Parking aid"),
        0x77 to ("Телефон" to "Telephone"),
    )

    /** "19 Шлюз" by the VCDS address; without one — the logical address. */
    fun moduleName(vcds: Int?, addr: Int): String = when {
        vcds == null -> tr("Блок TP 2.0, адрес %02X", "TP 2.0 module, address %02X").format(addr)
        else -> "%02X ".format(vcds) + (NAMES[vcds]?.let { tr(it.first, it.second) } ?: tr("Блок", "Module"))
    }
}
