package com.obdscanner.screen

import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarDb
import com.obdscanner.obd.Reading
import com.obdscanner.obd.pick
import com.obdscanner.tr

/** A block of the main screen: its own accent (title) and tile background, ARGB; items — reading key → label. */
class SectionDef(val title: String, val accent: Long, val tile: Long, val items: List<Pair<String, String>>)

/** A tile: [sample] — an offline card with a typical value, [note] replacing its min/max line. */
class Tile(val label: String, val reading: Reading, val level: Level?, val sample: Boolean, val note: String?)

class MainSection(val title: String, val accent: Long, val tile: Long, val tiles: List<Tile>)

/** The main screen: the lines above the cards ([Block.Note] / [Block.Banner]) and the sections of cards. */
class MainView(val offline: Boolean, val top: List<Block>, val sections: List<MainSection>)

object MainScreen {
    val SECTIONS = listOf(
        SectionDef(tr("Двигатель", "Engine"), 0xFF7AB8FF, 0xFF16233A, listOf(
            "01.0C" to tr("Обороты", "RPM"),
            "01.0D" to tr("Скорость", "Speed"),
            "01.05" to tr("ОЖ", "Coolant"),
            "01.04" to tr("Нагрузка", "Load"),
            "01.11" to tr("Дроссель", "Throttle"),
            "01.49" to tr("Педаль газа", "Accel pedal"),
            "01.0E" to tr("Опережение", "Timing advance"),
            "01.10" to "MAF",
            "01.0B" to "MAP",
            "01.0F" to tr("Воздух на впуске", "Intake air"),
            "01.1F" to tr("С момента пуска", "Run time"),
            "role:boost" to tr("Наддув", "Boost"),
            "role:knock_retard" to tr("Откат по детонации", "Knock retard"),
        )),
        SectionDef(tr("Масло", "Oil"), 0xFFFFC857, 0xFF2E2614, listOf(
            "01.5C" to tr("Масло двигателя", "Engine oil"),
            "22.1154" to tr("Масло двигателя", "Engine oil"),
            "22.1470" to tr("Давление масла", "Oil pressure"),
            "22.119F" to tr("Ресурс масла", "Oil life"),
            "role:oil_temp" to tr("Масло двигателя", "Engine oil"),
            "role:oil_pressure" to tr("Давление масла", "Oil pressure"),
            "role:oil_life" to tr("Ресурс масла", "Oil life"),
            "role:oil_level" to tr("Уровень масла", "Oil level"),
            "role:service_km" to tr("До ТО, км", "Service in, km"),
            "role:service_days" to tr("До ТО, дней", "Service in, days"),
        )),
        SectionDef(tr("АКПП", "Transmission"), 0xFFB39DFF, 0xFF271D38, listOf(
            "22.1940" to tr("Масло АКПП", "Trans fluid"),
            "22.199A" to tr("Передача", "Gear"),
            "calc.gearRatio" to tr("Передаточное отношение", "Gear ratio"),
            "22.1991" to tr("Проскальз. ГТ", "TC slip"),
            "22.1941" to tr("Входной вал", "Input shaft"),
            "22.1942" to tr("Выходной вал", "Output shaft"),
            "role:atf_temp" to tr("Масло АКПП", "Trans fluid"),
            "role:cvt_temp" to tr("Масло вариатора", "CVT fluid"),
            "role:clutch_temp" to tr("Сцепление", "Clutch"),
            "role:cvt_wear" to tr("Износ масла CVT", "CVT fluid wear"),
            "role:gear" to tr("Передача", "Gear"),
            "role:tc_slip" to tr("Проскальз. ГТ", "TC slip"),
            "role:input_rpm" to tr("Входной вал", "Input shaft"),
            "role:output_rpm" to tr("Выходной вал", "Output shaft"),
        )),
        SectionDef(tr("Топливо", "Fuel"), 0xFF6FD58E, 0xFF16291E, listOf(
            "01.2F" to tr("Топливо в баке", "Fuel level"),
            "calc.l100" to tr("Расход", "Fuel economy"),
            "calc.lph" to tr("Расход, л/ч", "Fuel rate, L/h"),
            "calc.trim1" to tr("Коррекция Б1", "Fuel trim B1"),
            "calc.trim2" to tr("Коррекция Б2", "Fuel trim B2"),
            "role:fuel_level_l" to tr("Топливо в баке, л", "Fuel level, L"),
            "role:dpf_soot" to tr("Сажа в DPF", "DPF soot"),
        )),
        SectionDef(tr("Электрика и среда", "Electrical & ambient"), 0xFF5ED1D9, 0xFF14282C, listOf(
            "01.42" to tr("Бортсеть (ЭБУ)", "Voltage (ECU)"),
            "000:ATRV" to tr("Бортсеть (адаптер)", "Voltage (adapter)"),
            "01.46" to tr("За бортом", "Ambient"),
            "01.33" to tr("Атм. давление", "Baro pressure"),
            "role:battery_soc" to tr("Заряд АКБ", "Battery charge"),
            "role:battery_temp" to tr("Температура АКБ", "Battery temp"),
            "role:hv_soc" to tr("Заряд ВВ батареи", "HV battery charge"),
            "role:hv_soh" to tr("Здоровье ВВ батареи", "HV battery health"),
            "role:odometer" to tr("Пробег", "Odometer"),
        )),
    )

    fun build(r: Map<String, Reading>, v: VehicleInfo): MainView {
        val mil = r.pick("01.01.MIL")?.value
        val dtcCount = r.pick("01.01.DTC")?.value?.toInt()
        val gmCodes = v.gmDtcs.flatMap { it.codes }
        // Before the first value arrives (no connection yet) show every card empty, so the help can be read offline.
        val offline = r.isEmpty()
        val sections = SECTIONS.map { s ->
            s to if (offline) s.items.filter { !it.first.startsWith(ROLE) }.distinctBy { it.second }.map { (k, label) -> label to placeholder(k, label) }
            else s.items.mapNotNull { (k, label) ->
                val reading = when {
                    // Manufacturer parameter of any make; GM ones are also listed by their DID above.
                    k.startsWith(ROLE) -> k.removePrefix(ROLE).let { role -> r.values.filter { it.role == role }.minByOrNull { it.key } }
                    k.contains(':') -> r[k]
                    else -> r.pick(k)
                }
                reading?.let { label to it }
            }.distinctBy { it.second.key }
        }.filter { it.second.isNotEmpty() }
        val top = Blocks()
        when {
            offline -> top.note(tr("Нет данных — подключитесь к адаптеру на вкладке «Связь». Нажмите на карточку, чтобы прочитать, что это за параметр.",
                "No data — connect to the adapter on the \"Connect\" tab. Tap a card to read what it shows."))
            mil == 1.0 -> top.banner(tr("Check Engine горит · ошибок в памяти: ${dtcCount ?: "?"} — см. вкладку «Ошибки»",
                "Check Engine is on · stored codes: ${dtcCount ?: "?"} — see the \"Codes\" tab"), Level.BAD)
            (dtcCount ?: 0) > 0 || v.dtcs.isNotEmpty() -> top.banner(tr("Есть коды ошибок: ${v.dtcs.size} — см. вкладку «Ошибки»",
                "Trouble codes: ${v.dtcs.size} — see the \"Codes\" tab"), Level.WARN)
            v.step.isNotEmpty() -> top.banner(tr("Опрос автомобиля: ${v.step}", "Scanning the car: ${v.step}"), Level.GOOD)
        }
        if (gmCodes.isNotEmpty()) {
            val active = gmCodes.count { it.current }
            top.banner(tr("Ошибки всех блоков: ${gmCodes.size}", "Codes in all modules: ${gmCodes.size}") +
                (if (active > 0) tr(", активных $active", ", $active active") else "") + tr(" — см. вкладку «Ошибки»", " — see the \"Codes\" tab"),
                if (active > 0) Level.BAD else Level.WARN)
        }
        val sample = tr("пример · нет связи", "sample · offline")
        return MainView(offline, top.build(), sections.map { (s, tiles) ->
            MainSection(s.title, s.accent, s.tile, tiles.map { (label, reading) ->
                if (offline) Tile(label, reading, Level.MUTED, true, sample) else Tile(label, reading, tileLevel(reading), false, null)
            })
        })
    }

    private const val ROLE = "role:"

    private val U_RPM = tr("об/мин", "rpm")
    private val U_KPA = tr("кПа", "kPa")
    private val U_V = tr("В", "V")

    /**
     * Typical values of a warm car idling in Park — shown greyed out on the offline cards, so the dashboard
     * looks like itself before the first connection: value, unit, decimals (units match the live readings).
     */
    private val SAMPLES: Map<String, Triple<Double, String, Int>> = mapOf(
        "01.0C" to Triple(700.0, U_RPM, 0),
        "01.0D" to Triple(0.0, tr("км/ч", "km/h"), 0),
        "01.05" to Triple(90.0, "°C", 0),
        "01.04" to Triple(22.0, "%", 1),
        "01.11" to Triple(15.7, "%", 1),
        "01.49" to Triple(16.1, "%", 1),
        "01.0E" to Triple(12.0, "°", 1),
        "01.10" to Triple(3.5, tr("г/с", "g/s"), 2),
        "01.0B" to Triple(30.0, U_KPA, 0),
        "01.0F" to Triple(30.0, "°C", 0),
        "01.1F" to Triple(600.0, tr("с", "s"), 0),
        "01.5C" to Triple(95.0, "°C", 0),
        "22.1154" to Triple(95.0, "°C", 0),
        "22.1470" to Triple(250.0, U_KPA, 0),
        "22.119F" to Triple(70.0, "%", 0),
        "22.1940" to Triple(80.0, "°C", 0),
        "22.199A" to Triple(1.0, "", 0),
        "calc.gearRatio" to Triple(4.06, "", 2),
        "22.1991" to Triple(15.0, U_RPM, 0),
        "22.1941" to Triple(690.0, U_RPM, 0),
        "22.1942" to Triple(0.0, U_RPM, 0),
        "01.2F" to Triple(50.0, "%", 1),
        "calc.l100" to Triple(11.0, tr("л/100км", "L/100km"), 1),
        "calc.lph" to Triple(1.1, tr("л/ч", "L/h"), 2),
        "calc.trim1" to Triple(1.6, "%", 1),
        "calc.trim2" to Triple(-0.8, "%", 1),
        "01.42" to Triple(14.2, U_V, 2),
        "000:ATRV" to Triple(14.1, U_V, 1),
        "01.46" to Triple(20.0, "°C", 0),
        "01.33" to Triple(100.0, U_KPA, 0),
    )

    /** An offline card for [key] (a main-screen source or "ecu:source"), keyed like the live reading it stands in for. */
    private fun placeholder(key: String, label: String): Reading {
        // The GM parameters checked on the CTS keep keys without a signal id ("22.1940").
        val gm = CarDb.family("gm")?.commands?.firstOrNull { c -> "${c.service}.${c.didHex}" == key && c.signals.singleOrNull()?.id == "" }
        val full = when {
            key.contains(':') -> key
            gm != null -> gm.key
            key.startsWith("calc.") -> Reading.key(0, key)
            else -> Reading.key(0x7E8, key)
        }
        val sample = SAMPLES[key]
        return Reading(full, full.substringBefore(':').toInt(16), gm?.signals?.first()?.name ?: label, sample?.first, null,
            sample?.second ?: "", sample?.third ?: 1)
    }

    fun tileLevel(reading: Reading): Level? = when {
        reading.source == "calc.trim1" || reading.source == "calc.trim2" -> trimLevel(reading.value)
        reading.source == "01.05" -> reading.value?.let { if (it > 105) Level.BAD else if (it < 70) Level.WARN else null }
        reading.source == "22.1940" || reading.role == "atf_temp" -> reading.value?.let { if (it > 110) Level.BAD else if (it > 95) Level.WARN else null }
        reading.source == "22.1154" || reading.role == "oil_temp" -> reading.value?.let { if (it > 135) Level.BAD else if (it > 120) Level.WARN else null }
        reading.source == "22.119F" || reading.role == "oil_life" -> reading.value?.let { if (it < 10) Level.BAD else if (it < 25) Level.WARN else null }
        else -> null
    }
}
