package com.obdscanner.screen

import com.obdscanner.Tab
import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarDb
import com.obdscanner.obd.Knock
import com.obdscanner.obd.Reading
import com.obdscanner.obd.RoleValues
import com.obdscanner.obd.pick
import com.obdscanner.tr
import com.obdscanner.util.nowMs

/** A block of the main screen: its own accent (title) and tile background, ARGB; items — reading key → label. */
class SectionDef(val title: String, val accent: Long, val tile: Long, val items: List<Pair<String, String>>)

/** A tile: [sample] — an offline card with a typical value, [note] replacing its min/max line. */
class Tile(val label: String, val reading: Reading, val level: Level?, val sample: Boolean, val note: String?)

/** [note] — a line under the section's title (no cards to show and why). */
class MainSection(val title: String, val accent: Long, val tile: Long, val tiles: List<Tile>, val note: String? = null)

/**
 * The main screen: the lines above the cards ([Block.Note] / [Block.Banner]), the sections of cards, [bottom] — what
 * comes after them (the hidden cards back).
 */
class MainView(val offline: Boolean, val top: List<Block>, val sections: List<MainSection>, val bottom: List<Block> = emptyList())

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

    val WATCH_LABEL = tr("Как бензин?", "How's the fuel?")

    val WATCH_HELP = tr(
        "Включайте, когда сомневаетесь в заправке.\n\n" +
            "На плохом бензине мотор чаще детонирует, и блок управления в ответ уменьшает угол зажигания. Пока режим включён, " +
            "приложение чаще обычного следит за этим откатом, показывает его сверху на главном экране и пишет первым разделом в отчёте.\n\n" +
            "Сам бензин не измеряется, виден только откат зажигания. Он бывает и от жары, и от нагрузки в горку, так что " +
            "сравнивайте поездки в похожих условиях.\n\n" +
            "Бывает, что машина не отдаёт эти данные или старый адаптер не успевает — тогда секция останется пустой.\n\n" +
            "Выключите — всё станет как раньше.",
        "Turn it on when you doubt the last fill-up.\n\n" +
            "On bad fuel the engine knocks more often, and the engine computer answers by pulling the ignition timing back. While " +
            "the mode is on the app watches that retard more often than usual, shows it at the top of the main screen and puts it first in the report.\n\n" +
            "The fuel itself isn't measured, only the ignition retard. Heat and climbing a hill under load cause it too, so " +
            "compare trips made in similar conditions.\n\n" +
            "Some cars don't give this data, or an old adapter can't keep up — then the section stays empty.\n\n" +
            "Turn it off and everything is as before.",
    )

    /** «Как бензин?»: first on Main while ticked; the Engine section keeps its own cards. */
    private val WATCH = SectionDef(WATCH_LABEL, 0xFFFF8A65, 0xFF35201A, listOf(
        "calc.knock" to tr("Откат по детонации", "Knock retard"),
        "22.12D9" to tr("Суммарный откат", "Total retard"),
        "calc.knockLoad" to tr("Откат на нагрузке", "Retard at high load"),
        "calc.knockLast" to tr("Последний откат", "Last retard"),
        "calc.misfire06" to tr("Пропуски за цикл", "Misfires this cycle"),
        "calc.misfireGm" to tr("Пропуски сейчас", "Misfires now"),
        "01.0E" to tr("Опережение", "Timing advance"),
        "01.0F" to tr("Воздух на впуске", "Intake air"),
    ))

    /** The trip's mode on the first screen ([com.obdscanner.TripMode]): one or none, kept in the settings. */
    val MODE_LABEL = tr("Режим", "Mode")
    val FUEL_MODE = tr("Топливо", "Fuel")
    val SAFETY_MODE = tr("Безопасность", "Safety")

    val SAFETY_LABEL = tr("Контроль безопасности движения", "Driving safety check")

    val SAFETY_HELP = tr(
        "Включайте на поездку, где важна динамика: разгоны, повороты, заносы.\n\n" +
            "Приложение опрашивает только обороты, скорость, дроссель и педаль — в несколько раз чаще обычного, остальное редко.\n\n" +
            "Датчики телефона пишутся как обычно: по ним отчёт находит заносы.\n\n" +
            "Коррекции, детонация и параметры производителя в этом режиме почти не читаются. Выключите — всё станет как раньше.",
        "Turn it on for a trip where the dynamics matter: launches, corners, slides.\n\n" +
            "The app polls only rpm, speed, throttle and pedal — several times more often than usual, the rest rarely.\n\n" +
            "The phone's sensors are recorded as usual: the report finds slides from them.\n\n" +
            "Fuel trims, knock and manufacturer parameters are hardly read in this mode. Turn it off and everything is as before.",
    )

    /** «Безопасность+» ([com.obdscanner.TripMode.SAFETY_PLUS]): «Безопасность» with the wheels heard on the bus, a test mode. */
    val SAFETY_PLUS_MODE = tr("Безопасность+", "Safety+")
    val SAFETY_PLUS_LABEL = tr("Безопасность+: скорости колёс с шины", "Safety+: wheel speeds from the bus")
    val SAFETY_PLUS_HELP = tr(
        "Режим «Безопасность», и ещё между опросами приложение слушает скорости колёс, которые блок ABS сам передаёт по шине " +
            "(GM, Toyota; машине ничего не отправляется), или спрашивает их у блока ABS (Subaru), и сверяет их со скоростью по OBD. " +
            "Совпали — они видны на главном экране, " +
            "а по разнице передних и задних видна пробуксовка.\n\n" +
            "Дешёвые клоны ELM327 на этом зависают посреди поездки: связь возвращается, только если вынуть адаптер и вставить обратно.",
        "The «Safety» mode, and between the polls the app also listens to the wheel speeds the ABS module sends on the bus by " +
            "itself (GM, Toyota; nothing is sent to the car), or asks the ABS module for them (Subaru), and checks them against the " +
            "OBD speed. If they agree, they show on the " +
            "main screen, and the difference between front and rear shows wheelspin.\n\n" +
            "Cheap ELM327 clones hang on it in the middle of a drive: the link comes back only when the adapter is unplugged and plugged back in.",
    )

    /** «Машина»: the test modes' section ([com.obdscanner.TripMode.test]). */
    val TEST_MODES = tr("Тестовые режимы", "Test modes")
    /** Under a test mode's help on the first screen: where it is turned on. */
    val TEST_MODE_NOTE = tr("Тестовый режим: включается на вкладке «Машина», в разделе «Тестовые режимы».",
        "A test mode: turned on on the «Car» tab, under «Test modes».")

    /** «Контроль безопасности движения»: first on Main while on; the wheels (in «Безопасность+») only once they agreed with the OBD speed. */
    private val SAFETY = SectionDef(SAFETY_LABEL, 0xFF80CBC4, 0xFF142A28, listOf(
        "01.0D" to tr("Скорость", "Speed"),
        "01.0C" to tr("Обороты", "RPM"),
        "01.11" to tr("Дроссель", "Throttle"),
        "01.49" to tr("Педаль газа", "Accelerator pedal"),
        "role:wheel_fl" to tr("Колесо ПЛ", "Wheel FL"),
        "role:wheel_fr" to tr("Колесо ПП", "Wheel FR"),
        "role:wheel_rl" to tr("Колесо ЗЛ", "Wheel RL"),
        "role:wheel_rr" to tr("Колесо ЗП", "Wheel RR"),
        "calc.axleSlip" to tr("Задние быстрее передних", "Rear faster than front"),
    ))

    /** «Избранное»: the values put on Main from «Все данные», first. */
    val PINNED = tr("Избранное", "Favourites")
    /** In a card's help: off Main (a pinned one unpinned, one of Main's own hidden). */
    val REMOVE = tr("Убрать с главной", "Remove from Main")
    /** The question before it, with the card's name, and how the card comes back (its own / a pinned one). */
    val REMOVE_ASK = tr("Убрать «%s» с Главной?", "Remove «%s» from Main?")
    val REMOVE_BACK_OWN = tr("Вернуть можно кнопкой «Вернуть скрытые карточки» внизу Главной.",
        "It comes back with «Bring back the hidden cards» at the bottom of Main.")
    val REMOVE_BACK_PIN = tr("Это карточка из избранного: вернуть можно звёздочкой ☆ у значения во «Все данные».",
        "This is a favourite: it comes back with the ☆ next to the value in «All data».")
    val REMOVE_YES = tr("Убрать", "Remove")
    val CANCEL = tr("Отмена", "Cancel")

    /**
     * [pins] — reading keys put on Main from «Все данные», [hidden] — Main's own cards taken off it, [limits] — the
     * owner's thresholds for this car by source.
     */
    fun build(readings: Map<String, Reading>, v: VehicleInfo, watch: Boolean = false, pins: List<String> = emptyList(), hidden: Set<String> = emptySet(),
              limits: Map<String, Limits> = emptyMap(), safety: Boolean = false): MainView {
        // Before the first value arrives (no connection yet) show every card empty, so the help can be read offline.
        val offline = readings.isEmpty()
        val r = if (watch && !offline) readings + misfires(readings, v) else readings
        val mil = r.pick("01.01.MIL")?.value
        val dtcCount = r.pick("01.01.DTC")?.value?.toInt()
        val gmCodes = v.gmDtcs.flatMap { it.codes }
        val sections = (when { safety -> listOf(SAFETY) + SECTIONS; watch -> listOf(WATCH) + SECTIONS; else -> SECTIONS }).map { s ->
            s to if (offline) s.items.filter { !it.first.startsWith(ROLE) }.distinctBy { it.second }.map { (k, label) -> label to placeholder(k, label) }
            else s.items.mapNotNull { (k, label) ->
                val reading = when {
                    // One oil pressure card: the CTS's 22.1470 or another make's (or OBDb's) value, whichever moves.
                    k == "22.1470" -> oilPressure(r)
                    k == ROLE + "oil_pressure" -> null
                    // Manufacturer parameter of any make; GM ones are also listed by their DID above. The app's own
                    // (checked) value before an OBDb one of the same role: the Elantra's 21 A0 byte 33 isn't the Kia's ATF.
                    k.startsWith(ROLE) -> k.removePrefix(ROLE).let { role -> first(r.values.filter { it.role == role }) }
                    k.contains(':') -> r[k]
                    else -> r.pick(k)
                }
                reading?.takeIf { it.key !in hidden && it.key !in pins }?.let { label to it }
            }.distinctBy { it.second.key }
        }.filter { it.second.isNotEmpty() || it.first === WATCH || it.first === SAFETY }
        val pinned = if (offline) emptyList() else pins.mapNotNull { r[it] }.map { it.name to it }
        val top = Blocks()
        // What the app is doing right now (finding the modules, reading their codes): above everything, it can take a minute.
        if (!offline && v.step.isNotEmpty()) top.banner(tr("Опрос машины: ${v.step}", "Scanning the car: ${v.step}"), Level.GOOD, Tab.Dtc)
        when {
            offline -> top.note(tr("Нет данных — подключитесь к адаптеру на вкладке «Связь». Нажмите на карточку, чтобы прочитать, что это за параметр.",
                "No data — connect to the adapter on the \"Connect\" tab. Tap a card to read what it shows."))
            mil == 1.0 -> top.banner(tr("Check Engine горит · ошибок в памяти: ${dtcCount ?: "?"} — см. вкладку «Ошибки»",
                "Check Engine is on · stored codes: ${dtcCount ?: "?"} — see the \"Codes\" tab"), Level.BAD, Tab.Dtc)
            gmCodes.isEmpty() && ((dtcCount ?: 0) > 0 || v.dtcs.isNotEmpty()) -> top.banner(tr("Есть коды ошибок: ${v.dtcs.size} — см. вкладку «Ошибки»",
                "Trouble codes: ${v.dtcs.size} — see the \"Codes\" tab"), Level.WARN, Tab.Dtc)
        }
        // The modules' memory isn't read on connecting when «Связь» says so (or the check was stopped): where to read it.
        if (!offline && v.moduleAccess && v.gmDtcTime == 0L && v.dtcTime > 0 && v.step.isEmpty())
            top.banner(tr("Память блоков не проверена — вкладка «Ошибки»", "The modules' memory isn't checked — the \"Codes\" tab"), Level.MUTED, Tab.Dtc)
        if (gmCodes.isNotEmpty()) {
            val active = gmCodes.count { it.current }
            val modules = v.gmDtcs.count { it.codes.isNotEmpty() }
            top.banner(tr("Ошибки в блоках: ${gmCodes.size} (блоков: $modules)", "Codes in the modules: ${gmCodes.size} ($modules modules)") +
                (if (active > 0) tr(", активных $active", ", $active active") else "") + tr(" — см. вкладку «Ошибки»", " — see the \"Codes\" tab"),
                if (active > 0) Level.BAD else Level.WARN, Tab.Dtc)
        }
        val knock = r.pick("calc.knock")
        val now = knock?.value
        if (watch && now != null && now > 0 && nowMs() - knock.time < 10_000)
            top.banner(tr("Откат по детонации сейчас: ${knock.display()}${knock.unit}", "Knock retard now: ${knock.display()}${knock.unit}"), Level.WARN)
        val gm = (v.car?.family ?: v.make.id) == "gm"
        val noKnock = !offline && v.step.isEmpty() && knock == null && v.extActive.none { Knock.isKnock(it, gm) && it.key !in v.knockRejected }
        val sample = tr("пример · нет связи", "sample · offline")
        val bottom = Blocks()
        if (pins.isEmpty()) bottom.note(tr("Свои показатели можно вынести на главную, в «Избранное»: ☆ у значения на вкладке «Все данные».",
            "Your own values can go on the main screen, into «Favourites»: ☆ next to a value on the «All data» tab."))
        if (!offline && hidden.isNotEmpty()) bottom.buttons(listOf(Button(Action.RESTORE_MAIN,
            tr("Вернуть скрытые карточки (${hidden.size})", "Bring back the hidden cards (${hidden.size})"), whileBusy = true)))
        val own = sections.map { (s, tiles) ->
            MainSection(s.title, s.accent, s.tile, tiles.map { (label, reading) ->
                if (offline) Tile(label, reading, Level.MUTED, true, sample) else Tile(label, reading, tileLevel(reading, limits), false, tileNote(reading))
            }, if (s === WATCH && noKnock) tr("Машина не отдаёт откат по детонации. Остались опережение и пропуски.",
                "The car doesn't give knock retard. Timing advance and misfires are left.") else null)
        }
        val fav = if (pinned.isEmpty()) emptyList() else listOf(MainSection(PINNED, 0xFFFF8FB1, 0xFF2E1824,
            pinned.map { (label, reading) -> Tile(label, reading, tileLevel(reading, limits), false, tileNote(reading)) }))
        return MainView(offline, top.build(), fav + own, bottom.build())
    }

    /**
     * The oil pressure card's value: a pressure (not the valve command of the same role), [RoleValues]' first — the
     * CTS's 22.1470 while it moves; on a Trailblazer it stays at 0 and an OBDb request carries the pressure (issue #4).
     */
    private fun oilPressure(r: Map<String, Reading>): Reading? =
        first(r.values.filter { it.role == "oil_pressure" && (it.unit.endsWith("Па") || it.unit.endsWith("Pa")) })

    /** A card's value among the readings of one role: [RoleValues.order]'s first. */
    private fun first(rs: List<Reading>): Reading? {
        val by = rs.associateBy { it.key }
        return RoleValues.order(rs.map { RoleValues.Value(it.key, it.name, it.curated, it.min ?: 0.0, it.max ?: 0.0) }).firstOrNull()?.let { by[it.key] }
    }

    /** «Как бензин?» misfires: Mode 06 of the cycle (any make) and the GM current ones, each summed over the cylinders. */
    private fun misfires(r: Map<String, Reading>, v: VehicleInfo): Map<String, Reading> {
        val out = mutableListOf<Reading>()
        val m06 = v.mode06.filter { it.misfireCylinder != null && it.tid == 0x0C }
        if (m06.isNotEmpty()) out += Reading(Reading.key(0, "calc.misfire06"), 0, tr("Пропуски за цикл (Mode 06), все цилиндры",
            "Misfires this cycle (Mode 06), all cylinders"), m06.sumOf { it.value }, null, "", 0)
        val gm = FuelScreen.MISFIRE_NOW.mapNotNull { r.pick(it)?.value }
        if (gm.isNotEmpty()) out += Reading(Reading.key(0, "calc.misfireGm"), 0, tr("Пропуски сейчас (GM), все цилиндры",
            "Misfires now (GM), all cylinders"), gm.sum(), null, "", 0)
        return out.associateBy { it.key }
    }

    /** The line under a «Как бензин?» card: how many samples, and when the last retard was. */
    private fun tileNote(x: Reading): String? = when (x.source) {
        "calc.knockLoad" -> x.text
        "calc.knockLast" -> (listOfNotNull(x.text?.ifEmpty { null }) + ago(x.time)).joinToString(" · ")
        else -> null
    }

    private fun ago(t: Long): String {
        val s = (nowMs() - t) / 1000
        return when {
            s < 60 -> tr("$s с назад", "$s s ago")
            s < 3600 -> tr("${s / 60} мин назад", "${s / 60} min ago")
            else -> tr("${s / 3600} ч назад", "${s / 3600} h ago")
        }
    }

    private const val ROLE = "role:"
    private val WATCH_LEVELS = setOf("calc.knock", "calc.knockLoad", "calc.misfire06", "calc.misfireGm")

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
        "calc.knock" to Triple(0.0, "°", 1),
        "22.12D9" to Triple(0.0, "°", 1),
        "calc.knockLoad" to Triple(0.0, "%", 0),
        "calc.misfire06" to Triple(0.0, "", 0),
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

    /** The app's own thresholds for a value, the ones the owner's [Limits] go over; null for a value without any. */
    fun builtIn(reading: Reading): Limits? = when {
        reading.source == "01.05" -> Limits(warnBelow = 70.0, badAbove = 105.0)
        reading.source == "22.1940" || reading.role == "atf_temp" -> Limits(warnAbove = 95.0, badAbove = 110.0)
        reading.source == "22.1154" || reading.role == "oil_temp" -> Limits(warnAbove = 120.0, badAbove = 135.0)
        reading.source == "22.119F" || reading.role == "oil_life" -> Limits(warnBelow = 25.0, badBelow = 10.0)
        else -> null
    }

    /**
     * The card's colour: the owner's thresholds for this car ([limits], by source) over the built-in ones; a value
     * with neither falls to the special rules («Как бензин?», trims) or none.
     */
    fun tileLevel(reading: Reading, limits: Map<String, Limits> = emptyMap()): Level? {
        val user = limits[reading.source]
        val rule = builtIn(reading)?.over(user) ?: user
        if (rule != null && !rule.isEmpty) return reading.value?.let(rule::level)
        return when {
            // «Как бензин?»: no retard, no misfires — green; any — amber (how much is too much we don't know).
            reading.source in WATCH_LEVELS -> reading.value?.let { if (it > 0) Level.WARN else Level.GOOD }
            reading.source == "calc.trim1" || reading.source == "calc.trim2" -> trimLevel(reading.value)
            else -> null
        }
    }
}
