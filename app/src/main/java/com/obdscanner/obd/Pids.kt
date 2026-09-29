package com.obdscanner.obd

import com.obdscanner.L10n
import com.obdscanner.tr

class PidOut(val suffix: String, val name: String, val unit: String, val decimals: Int, val f: (IntArray) -> Double?)

/** Mode 01 / 02 PID. [len] — data bytes (needed to split multi-PID replies). */
class PidDef(
    val pid: Int,
    val len: Int,
    val name: String,
    val outs: List<PidOut>,
    val text: ((IntArray) -> String)? = null,
    /** Doesn't change while driving — read once. */
    val static: Boolean = false,
)

private fun ab(d: IntArray, i: Int = 0) = d[i] * 256 + d[i + 1]
private fun u32(d: IntArray, i: Int) = (d[i].toLong() shl 24) or (d[i + 1].toLong() shl 16) or (d[i + 2].toLong() shl 8) or d[i + 3].toLong()
private fun bit(d: IntArray, byte: Int, b: Int) = (d[byte] shr b) and 1 == 1

object Pids {
    private val list = mutableListOf<PidDef>()
    val byPid: Map<Int, PidDef> by lazy { list.associateBy { it.pid } }

    fun isBitmask(pid: Int) = pid % 0x20 == 0

    private fun num(pid: Int, len: Int, name: String, unit: String, dec: Int = 1, f: (IntArray) -> Double) {
        list += PidDef(pid, len, name, listOf(PidOut("", name, unit, dec, f)))
    }

    private fun multi(pid: Int, len: Int, name: String, vararg outs: PidOut) {
        list += PidDef(pid, len, name, outs.toList())
    }

    private fun txt(pid: Int, len: Int, name: String, static: Boolean = false, f: (IntArray) -> String) {
        list += PidDef(pid, len, name, emptyList(), f, static)
    }

    private val sensors13 =
        if (L10n.ru) listOf("Б1 Д1", "Б1 Д2", "Б1 Д3", "Б1 Д4", "Б2 Д1", "Б2 Д2", "Б2 Д3", "Б2 Д4")
        else listOf("B1 S1", "B1 S2", "B1 S3", "B1 S4", "B2 S1", "B2 S2", "B2 S3", "B2 S4")

    // Units, shared by many rows.
    private val kPa = tr("кПа", "kPa")
    private val gs = tr("г/с", "g/s")
    private val volt = tr("В", "V")
    private val mA = tr("мА", "mA")
    private val km = tr("км", "km")
    private val minutes = tr("мин", "min")
    private val pa = tr("Па", "Pa")
    private val hours = tr("ч", "h")

    init {
        list += PidDef(
            0x01, 4, tr("Статус мониторов с момента сброса", "Monitor status since DTCs cleared"),
            listOf(
                PidOut("MIL", "Check Engine", "", 0) { ((it[0] shr 7) and 1).toDouble() },
                PidOut("DTC", tr("Ошибок в памяти", "DTCs stored"), "", 0) { (it[0] and 0x7F).toDouble() },
            ),
            text = { Readiness.summary(it) },
        )
        txt(0x02, 2, tr("DTC стоп-кадра", "Freeze frame DTC")) { Dtc.decode(it[0], it[1]) }
        txt(0x03, 2, tr("Статус топливной системы", "Fuel system status")) {
            fuelSystem(it[0]) + if (it[1] != 0) tr(" / Б2: ", " / B2: ") + fuelSystem(it[1]) else ""
        }
        num(0x04, 1, tr("Расчётная нагрузка", "Calculated engine load"), "%") { it[0] / 2.55 }
        num(0x05, 1, tr("Температура ОЖ", "Engine coolant temperature"), "°C", 0) { it[0] - 40.0 }
        num(0x06, 1, tr("Кратк. коррекция топлива Б1", "Short term fuel trim B1"), "%") { it[0] / 1.28 - 100 }
        num(0x07, 1, tr("Долг. коррекция топлива Б1", "Long term fuel trim B1"), "%") { it[0] / 1.28 - 100 }
        num(0x08, 1, tr("Кратк. коррекция топлива Б2", "Short term fuel trim B2"), "%") { it[0] / 1.28 - 100 }
        num(0x09, 1, tr("Долг. коррекция топлива Б2", "Long term fuel trim B2"), "%") { it[0] / 1.28 - 100 }
        num(0x0A, 1, tr("Давление топлива", "Fuel pressure"), kPa, 0) { it[0] * 3.0 }
        num(0x0B, 1, tr("Давление во впуске (MAP)", "Intake pressure (MAP)"), kPa, 0) { it[0].toDouble() }
        num(0x0C, 2, tr("Обороты", "RPM"), tr("об/мин", "rpm"), 0) { ab(it) / 4.0 }
        num(0x0D, 1, tr("Скорость", "Vehicle speed"), tr("км/ч", "km/h"), 0) { it[0].toDouble() }
        num(0x0E, 1, tr("Опережение зажигания", "Timing advance"), "°", 1) { it[0] / 2.0 - 64 }
        num(0x0F, 1, tr("Температура воздуха на впуске", "Intake air temperature"), "°C", 0) { it[0] - 40.0 }
        num(0x10, 2, tr("Расход воздуха (MAF)", "Air flow rate (MAF)"), gs, 2) { ab(it) / 100.0 }
        num(0x11, 1, tr("Положение дросселя", "Throttle position"), "%") { it[0] / 2.55 }
        txt(0x12, 1, tr("Вторичный воздух", "Secondary air status")) { secondaryAir(it[0]) }
        txt(0x13, 1, tr("Установленные датчики O2", "O2 sensors present"), static = true) { d ->
            sensors13.filterIndexed { i, _ -> bit(d, 0, i) }.joinToString(", ").ifEmpty { tr("нет", "none") }
        }
        for (i in 0..7) multi(
            0x14 + i, 2, tr("Датчик O2 ${sensors13[i]}", "O2 sensor ${sensors13[i]}"),
            PidOut("V", tr("Датчик O2 ${sensors13[i]}, напряжение", "O2 sensor ${sensors13[i]} voltage"), volt, 3) { it[0] / 200.0 },
            PidOut("T", tr("Датчик O2 ${sensors13[i]}, коррекция", "O2 sensor ${sensors13[i]} fuel trim"), "%", 1) { if (it[1] == 0xFF) null else it[1] / 1.28 - 100 },
        )
        txt(0x1C, 1, tr("Стандарт OBD", "OBD standard"), static = true) { obdStandard(it[0]) }
        txt(0x1D, 1, tr("Датчики O2 (4 банка)", "O2 sensors (4 banks)"), static = true) { "0x%02X".format(it[0]) }
        txt(0x1E, 1, tr("Вход PTO", "PTO status")) { if (bit(it, 0, 0)) tr("активен", "active") else tr("нет", "off") }
        num(0x1F, 2, tr("Время с пуска двигателя", "Run time since engine start"), tr("с", "s"), 0) { ab(it).toDouble() }
        num(0x21, 2, tr("Пробег с горящим Check", "Distance with MIL on"), km, 0) { ab(it).toDouble() }
        num(0x22, 2, tr("Давление в рампе (отн. вакуума)", "Fuel rail pressure (rel. vacuum)"), kPa, 1) { ab(it) * 0.079 }
        num(0x23, 2, tr("Давление в рампе", "Fuel rail pressure"), kPa, 0) { ab(it) * 10.0 }
        for (i in 0..7) multi(
            0x24 + i, 4, tr("ШДК ${i + 1} (λ/напряжение)", "Wideband O2 ${i + 1} (λ/voltage)"),
            PidOut("L", tr("ШДК ${i + 1}, λ", "Wideband O2 ${i + 1} λ"), "λ", 3) { ab(it) * 2.0 / 65536 },
            PidOut("V", tr("ШДК ${i + 1}, напряжение", "Wideband O2 ${i + 1} voltage"), volt, 3) { ab(it, 2) * 8.0 / 65536 },
        )
        num(0x2C, 1, tr("EGR (команда)", "Commanded EGR"), "%") { it[0] / 2.55 }
        num(0x2D, 1, tr("Ошибка EGR", "EGR error"), "%") { it[0] / 1.28 - 100 }
        num(0x2E, 1, tr("Продувка адсорбера (команда)", "Commanded EVAP purge"), "%") { it[0] / 2.55 }
        num(0x2F, 1, tr("Уровень топлива", "Fuel level"), "%") { it[0] / 2.55 }
        num(0x30, 1, tr("Прогревов после сброса ошибок", "Warm-ups since DTCs cleared"), "", 0) { it[0].toDouble() }
        num(0x31, 2, tr("Пробег после сброса ошибок", "Distance since DTCs cleared"), km, 0) { ab(it).toDouble() }
        num(0x32, 2, tr("Давление паров EVAP", "EVAP vapor pressure"), pa, 1) { ab(it).let { v -> if (v >= 32768) v - 65536 else v } / 4.0 }
        num(0x33, 1, tr("Атмосферное давление", "Barometric pressure"), kPa, 0) { it[0].toDouble() }
        for (i in 0..7) multi(
            0x34 + i, 4, tr("ШДК ${i + 1} (λ/ток)", "Wideband O2 ${i + 1} (λ/current)"),
            PidOut("L", tr("ШДК ${i + 1}, λ", "Wideband O2 ${i + 1} λ"), "λ", 3) { ab(it) * 2.0 / 65536 },
            PidOut("I", tr("ШДК ${i + 1}, ток", "Wideband O2 ${i + 1} current"), mA, 2) { ab(it, 2) / 256.0 - 128 },
        )
        num(0x3C, 2, tr("Температура катализатора Б1 Д1", "Catalyst temperature B1 S1"), "°C", 0) { ab(it) / 10.0 - 40 }
        num(0x3D, 2, tr("Температура катализатора Б2 Д1", "Catalyst temperature B2 S1"), "°C", 0) { ab(it) / 10.0 - 40 }
        num(0x3E, 2, tr("Температура катализатора Б1 Д2", "Catalyst temperature B1 S2"), "°C", 0) { ab(it) / 10.0 - 40 }
        num(0x3F, 2, tr("Температура катализатора Б2 Д2", "Catalyst temperature B2 S2"), "°C", 0) { ab(it) / 10.0 - 40 }
        txt(0x41, 4, tr("Мониторы текущей поездки", "Monitor status this drive cycle")) { Readiness.summary(it, thisCycle = true) }
        num(0x42, 2, tr("Напряжение бортсети (ЭБУ)", "Control module voltage"), volt, 2) { ab(it) / 1000.0 }
        num(0x43, 2, tr("Абсолютная нагрузка", "Absolute load"), "%") { ab(it) * 100.0 / 255 }
        num(0x44, 2, tr("Заданная λ", "Commanded λ"), "λ", 3) { ab(it) * 2.0 / 65536 }
        num(0x45, 1, tr("Относит. положение дросселя", "Relative throttle position"), "%") { it[0] / 2.55 }
        num(0x46, 1, tr("Температура за бортом", "Ambient air temperature"), "°C", 0) { it[0] - 40.0 }
        num(0x47, 1, tr("Дроссель, датчик B", "Throttle position B"), "%") { it[0] / 2.55 }
        num(0x48, 1, tr("Дроссель, датчик C", "Throttle position C"), "%") { it[0] / 2.55 }
        num(0x49, 1, tr("Педаль газа, датчик D", "Accel pedal position D"), "%") { it[0] / 2.55 }
        num(0x4A, 1, tr("Педаль газа, датчик E", "Accel pedal position E"), "%") { it[0] / 2.55 }
        num(0x4B, 1, tr("Педаль газа, датчик F", "Accel pedal position F"), "%") { it[0] / 2.55 }
        num(0x4C, 1, tr("Привод дросселя (команда)", "Commanded throttle actuator"), "%") { it[0] / 2.55 }
        num(0x4D, 2, tr("Время с горящим Check", "Time with MIL on"), minutes, 0) { ab(it).toDouble() }
        num(0x4E, 2, tr("Время после сброса ошибок", "Time since DTCs cleared"), minutes, 0) { ab(it).toDouble() }
        multi(
            0x4F, 4, tr("Максимумы шкал", "Scale maximums"),
            PidOut("L", tr("Макс. λ", "Max λ"), "", 0) { it[0].toDouble() },
            PidOut("V", tr("Макс. напряжение O2", "Max O2 voltage"), volt, 0) { it[1].toDouble() },
            PidOut("I", tr("Макс. ток O2", "Max O2 current"), mA, 0) { it[2].toDouble() },
            PidOut("P", tr("Макс. MAP", "Max MAP"), kPa, 0) { it[3] * 10.0 },
        )
        num(0x50, 4, tr("Макс. MAF", "Max MAF"), gs, 0) { it[0] * 10.0 }
        txt(0x51, 1, tr("Тип топлива", "Fuel type"), static = true) { fuelType(it[0]) }
        num(0x52, 1, tr("Доля этанола", "Ethanol fuel %"), "%") { it[0] / 2.55 }
        num(0x53, 2, tr("Абс. давление паров EVAP", "Abs. EVAP vapor pressure"), kPa, 3) { ab(it) / 200.0 }
        num(0x54, 2, tr("Давление паров EVAP (шир.)", "EVAP vapor pressure (wide)"), pa, 0) { ab(it) - 32767.0 }
        multi(
            0x55, 2, tr("Кратк. коррекция по 2-му O2, Б1/Б3", "Short term secondary O2 trim B1/B3"),
            PidOut("A", tr("Кратк. коррекция по 2-му O2, Б1", "Short term secondary O2 trim B1"), "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", tr("Кратк. коррекция по 2-му O2, Б3", "Short term secondary O2 trim B3"), "%", 1) { it[1] / 1.28 - 100 },
        )
        multi(
            0x56, 2, tr("Долг. коррекция по 2-му O2, Б1/Б3", "Long term secondary O2 trim B1/B3"),
            PidOut("A", tr("Долг. коррекция по 2-му O2, Б1", "Long term secondary O2 trim B1"), "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", tr("Долг. коррекция по 2-му O2, Б3", "Long term secondary O2 trim B3"), "%", 1) { it[1] / 1.28 - 100 },
        )
        multi(
            0x57, 2, tr("Кратк. коррекция по 2-му O2, Б2/Б4", "Short term secondary O2 trim B2/B4"),
            PidOut("A", tr("Кратк. коррекция по 2-му O2, Б2", "Short term secondary O2 trim B2"), "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", tr("Кратк. коррекция по 2-му O2, Б4", "Short term secondary O2 trim B4"), "%", 1) { it[1] / 1.28 - 100 },
        )
        multi(
            0x58, 2, tr("Долг. коррекция по 2-му O2, Б2/Б4", "Long term secondary O2 trim B2/B4"),
            PidOut("A", tr("Долг. коррекция по 2-му O2, Б2", "Long term secondary O2 trim B2"), "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", tr("Долг. коррекция по 2-му O2, Б4", "Long term secondary O2 trim B4"), "%", 1) { it[1] / 1.28 - 100 },
        )
        num(0x59, 2, tr("Абс. давление в рампе", "Abs. fuel rail pressure"), kPa, 0) { ab(it) * 10.0 }
        num(0x5A, 1, tr("Относит. положение педали", "Relative accel pedal position"), "%") { it[0] / 2.55 }
        num(0x5B, 1, tr("Заряд гибридной батареи", "Hybrid battery charge"), "%") { it[0] / 2.55 }
        num(0x5C, 1, tr("Температура масла", "Engine oil temperature"), "°C", 0) { it[0] - 40.0 }
        num(0x5D, 2, tr("Угол начала впрыска", "Fuel injection timing"), "°", 2) { ab(it) / 128.0 - 210 }
        num(0x5E, 2, tr("Расход топлива (ЭБУ)", "Engine fuel rate (ECU)"), tr("л/ч", "L/h"), 2) { ab(it) / 20.0 }
        txt(0x5F, 1, tr("Экостандарт", "Emission standard"), static = true) { "0x%02X".format(it[0]) }
        num(0x61, 1, tr("Запрошенный момент", "Demanded torque"), "%", 0) { it[0] - 125.0 }
        num(0x62, 1, tr("Фактический момент", "Actual torque"), "%", 0) { it[0] - 125.0 }
        num(0x63, 2, tr("Номинальный момент", "Reference torque"), tr("Н·м", "N·m"), 0) { ab(it).toDouble() }
        multi(
            0x64, 5, tr("Точки крутящего момента", "Torque points"),
            PidOut("0", tr("Момент на ХХ", "Torque at idle"), "%", 0) { it[0] - 125.0 },
            PidOut("1", tr("Момент, точка 1", "Torque, point 1"), "%", 0) { it[1] - 125.0 },
            PidOut("2", tr("Момент, точка 2", "Torque, point 2"), "%", 0) { it[2] - 125.0 },
            PidOut("3", tr("Момент, точка 3", "Torque, point 3"), "%", 0) { it[3] - 125.0 },
            PidOut("4", tr("Момент, точка 4", "Torque, point 4"), "%", 0) { it[4] - 125.0 },
        )
        multi(
            0x66, 5, tr("MAF (расширенный)", "MAF (extended)"),
            PidOut("A", tr("MAF, датчик A", "MAF sensor A"), gs, 2) { if (bit(it, 0, 0)) ab(it, 1) / 32.0 else null },
            PidOut("B", tr("MAF, датчик B", "MAF sensor B"), gs, 2) { if (bit(it, 0, 1)) ab(it, 3) / 32.0 else null },
        )
        multi(
            0x67, 3, tr("Температура ОЖ (расширенная)", "Coolant temperature (extended)"),
            PidOut("1", tr("Температура ОЖ, датчик 1", "Coolant temperature sensor 1"), "°C", 0) { if (bit(it, 0, 0)) it[1] - 40.0 else null },
            PidOut("2", tr("Температура ОЖ, датчик 2", "Coolant temperature sensor 2"), "°C", 0) { if (bit(it, 0, 1)) it[2] - 40.0 else null },
        )
        multi(
            0x68, 7, tr("Температура воздуха (расширенная)", "Intake air temperature (extended)"),
            *Array(6) { k -> PidOut("$k", tr("Темп. воздуха, датчик ${k + 1}", "Intake air temp sensor ${k + 1}"), "°C", 0) { if (bit(it, 0, k)) it[k + 1] - 40.0 else null } },
        )
        multi(
            0x78, 9, tr("Температура выхлопа Б1", "Exhaust gas temperature B1"),
            *Array(4) { k -> PidOut("$k", tr("Темп. выхлопа Б1 Д${k + 1}", "Exhaust gas temp B1 S${k + 1}"), "°C", 0) { if (bit(it, 0, k)) ab(it, 1 + 2 * k) / 10.0 - 40 else null } },
        )
        multi(
            0x79, 9, tr("Температура выхлопа Б2", "Exhaust gas temperature B2"),
            *Array(4) { k -> PidOut("$k", tr("Темп. выхлопа Б2 Д${k + 1}", "Exhaust gas temp B2 S${k + 1}"), "°C", 0) { if (bit(it, 0, k)) ab(it, 1 + 2 * k) / 10.0 - 40 else null } },
        )
        multi(
            0x7F, 13, tr("Время работы двигателя", "Engine run time"),
            PidOut("T", tr("Моторесурс, всего", "Engine hours, total"), hours, 1) { u32(it, 1) / 3600.0 },
            PidOut("I", tr("Моторесурс, на ХХ", "Engine hours, idle"), hours, 1) { u32(it, 5) / 3600.0 },
        )
        num(0x8E, 1, tr("Момент трения", "Friction torque"), "%", 0) { it[0] - 125.0 }
        multi(
            0x9D, 4, tr("Расход топлива (г/с)", "Fuel rate (g/s)"),
            PidOut("E", tr("Расход топлива двигателем", "Engine fuel rate"), gs, 2) { ab(it) / 50.0 },
            PidOut("V", tr("Расход топлива автомобилем", "Vehicle fuel rate"), gs, 2) { ab(it, 2) / 50.0 },
        )
        num(0x9E, 2, tr("Расход выхлопных газов", "Exhaust flow rate"), tr("кг/ч", "kg/h"), 1) { ab(it) / 5.0 }
        num(0xA2, 2, tr("Цикловая подача топлива", "Cylinder fuel rate"), tr("мг/такт", "mg/stroke"), 2) { ab(it) / 32.0 }
        multi(
            0xA4, 4, tr("Передача", "Gear"),
            PidOut("G", tr("Передача", "Gear"), "", 0) { (it[1] shr 4).toDouble() },
            PidOut("R", tr("Передаточное число", "Gear ratio"), "", 3) { if (bit(it, 0, 1)) ab(it, 2) / 1000.0 else null },
        )
        num(0xA6, 4, tr("Одометр", "Odometer"), km, 1) { u32(it, 0) / 10.0 }
    }

    fun name(pid: Int) = byPid[pid]?.name ?: "PID %02X".format(pid)

    /**
     * Splits a multi-PID reply ([data] starts with 0x41) into (pid, bytes). The table length is
     * checked against what follows: the next byte must be another requested PID or the end.
     * PIDs 55–58 are two bytes by J1979, but a two-bank GM ECM sends one ("56 80 58 80").
     */
    fun splitMulti(data: IntArray, requested: Collection<Int>): List<Pair<Int, IntArray>> {
        val left = requested.toMutableSet()
        val out = mutableListOf<Pair<Int, IntArray>>()
        var i = 1
        while (i < data.size) {
            val pid = data[i]
            if (!left.remove(pid)) break
            val def = byPid[pid] ?: break
            val lens = if (pid in 0x55..0x58) listOf(def.len, 1) else listOf(def.len)
            val len = lens.firstOrNull { l ->
                val end = i + 1 + l
                end == data.size || (end < data.size && data[end] in left)
            } ?: break
            out += pid to data.copyOfRange(i + 1, i + 1 + len)
            i += 1 + len
        }
        return out
    }

    /** Decodes one PID's data bytes. Unknown PIDs come back as raw hex so nothing is lost. */
    fun decode(ecu: Int, mode: String, pid: Int, d: IntArray): List<Reading> {
        val base = "%s.%02X".format(mode, pid)
        val def = byPid[pid]
        if (def == null) {
            return listOf(Reading(Reading.key(ecu, base), ecu, tr("PID %02X (сырые данные)", "PID %02X (raw)").format(pid), null, hex(d), "", 0))
        }
        val out = mutableListOf<Reading>()
        for (o in def.outs) {
            val v = runCatching { o.f(d) }.getOrNull()
            if (v == null && def.outs.size > 1) continue
            val src = if (o.suffix.isEmpty()) base else "$base.${o.suffix}"
            out += Reading(Reading.key(ecu, src), ecu, o.name, v, if (v == null) hex(d) else null, o.unit, o.decimals)
        }
        def.text?.let { f ->
            val t = runCatching { f(d) }.getOrElse { hex(d) }
            val src = if (def.outs.isEmpty()) base else "$base.S"
            out += Reading(Reading.key(ecu, src), ecu, def.name, null, t, "", 0)
        }
        return out
    }

    fun hex(d: IntArray) = d.joinToString(" ") { "%02X".format(it) }

    fun fuelSystem(v: Int) = when (v) {
        0 -> "—"
        1 -> tr("разомкнутый (не прогрет)", "open loop (not warm)")
        2 -> tr("замкнутый (по O2)", "closed loop (O2)")
        4 -> tr("разомкнутый (нагрузка/торможение)", "open loop (load/decel)")
        8 -> tr("разомкнутый (неисправность)", "open loop (fault)")
        16 -> tr("замкнутый, неисправность O2", "closed loop, O2 fault")
        else -> "0x%02X".format(v)
    }

    private fun secondaryAir(v: Int) = when (v) {
        1 -> tr("до катализатора", "upstream of cat")
        2 -> tr("после катализатора", "downstream of cat")
        4 -> tr("в атмосферу/выкл.", "atmosphere/off")
        8 -> tr("по запросу диагностики", "on for diagnostics")
        else -> "0x%02X".format(v)
    }

    private fun obdStandard(v: Int) = when (v) {
        1 -> "OBD-II (CARB)"
        2 -> "OBD (EPA)"
        3 -> "OBD + OBD-II"
        4 -> "OBD-I"
        5 -> tr("не OBD", "not OBD")
        6 -> tr("EOBD (Европа)", "EOBD (Europe)")
        7 -> "EOBD + OBD-II"
        8 -> "EOBD + OBD"
        9 -> "EOBD, OBD, OBD-II"
        10 -> "JOBD"
        11 -> "JOBD + OBD-II"
        12 -> "JOBD + EOBD"
        13 -> "JOBD, EOBD, OBD-II"
        17 -> "EMD"
        18 -> "EMD+"
        19 -> "HD OBD-C"
        20 -> "HD OBD"
        21 -> "WWH OBD"
        23 -> "HD EOBD-I"
        25 -> "HD EOBD-II"
        28 -> tr("Бразилия OBD-1", "Brazil OBD-1")
        29 -> tr("Бразилия OBD-2", "Brazil OBD-2")
        30 -> tr("Корея KOBD", "Korea KOBD")
        31 -> tr("Индия OBD-I", "India OBD-I")
        32 -> tr("Индия OBD-II", "India OBD-II")
        33 -> "HD EOBD-IV"
        else -> tr("код $v", "code $v")
    }

    fun fuelType(v: Int) = when (v) {
        0 -> tr("не указан", "not available")
        1 -> tr("бензин", "gasoline")
        2 -> tr("метанол", "methanol")
        3 -> tr("этанол", "ethanol")
        4 -> tr("дизель", "diesel")
        5 -> tr("пропан (LPG)", "LPG")
        6 -> tr("метан (CNG)", "CNG")
        7 -> tr("пропан", "propane")
        8 -> tr("электро", "electric")
        9 -> tr("бензин/газ (бензин)", "bi-fuel (gasoline)")
        10 -> tr("двухтопл. метанол", "bi-fuel methanol")
        11 -> tr("двухтопл. этанол", "bi-fuel ethanol")
        12 -> tr("двухтопл. LPG", "bi-fuel LPG")
        13 -> tr("двухтопл. CNG", "bi-fuel CNG")
        14 -> tr("двухтопл. пропан", "bi-fuel propane")
        15 -> tr("двухтопл. электро", "bi-fuel electric")
        16 -> tr("двухтопл. бензин+электро", "bi-fuel gas+electric")
        17 -> tr("гибрид бензин", "hybrid gasoline")
        18 -> tr("гибрид этанол", "hybrid ethanol")
        19 -> tr("гибрид дизель", "hybrid diesel")
        20 -> tr("гибрид электро", "hybrid electric")
        else -> tr("код $v", "code $v")
    }
}
