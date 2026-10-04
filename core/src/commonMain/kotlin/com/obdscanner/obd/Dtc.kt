package com.obdscanner.obd

import com.obdscanner.util.format
import com.obdscanner.L10n
import com.obdscanner.tr

enum class DtcKind(val title: String) {
    STORED(tr("Сохранённые", "Stored")), PENDING(tr("Ожидающие", "Pending")), PERMANENT(tr("Постоянные", "Permanent"))
}

data class DtcCode(val code: String, val ecu: Int, val kind: DtcKind) {
    val description get() = Dtc.describe(code)
    val fuelRelated get() = Dtc.isFuelRelated(code)
}

object Dtc {
    fun decode(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        return "%c%d%X%02X".format(letter, (a shr 4) and 3, a and 0x0F, b)
    }

    /** Mode 03/07/0A reply payload on CAN: [43, count, A, B, A, B, ...]. */
    fun parse(data: IntArray, ecu: Int, kind: DtcKind): List<DtcCode> {
        if (data.size < 2) return emptyList()
        // CAN puts a count byte after the service; tolerate replies without it (odd length).
        val start = if ((data.size - 1) % 2 == 1) 2 else 1
        val out = mutableListOf<DtcCode>()
        var i = start
        while (i + 1 < data.size) {
            if (data[i] != 0 || data[i + 1] != 0) out += DtcCode(decode(data[i], data[i + 1]), ecu, kind)
            i += 2
        }
        return out
    }

    fun isFuelRelated(code: String): Boolean {
        if (!code.startsWith("P0") && !code.startsWith("P2")) return false
        val n = code.substring(1).toIntOrNull(16) ?: return false
        return n in 0x0087..0x0093 || n in 0x0130..0x0175 || n in 0x0200..0x0206 || n in 0x0217..0x0217 ||
            n in 0x0230..0x0232 || n in 0x0300..0x0312 || n in 0x0325..0x0334 || n in 0x0420..0x0434 ||
            n in 0x0440..0x0457 || n in 0x0496..0x0499 || n in 0x0100..0x0104 || n in 0x0171..0x0175 ||
            n in 0x2195..0x2198 || n in 0x2270..0x2273 || n in 0x2096..0x2099 || n in 0x2177..0x2179 ||
            n in 0x2187..0x2189
    }

    /** Title: the make's database text, the hand-written one below, the SAE database, then just the system. */
    fun describe(code: String, family: String? = DtcDb.family): String = DtcDb.ofMake(code, family)?.title?.text
        ?: DESCRIPTIONS[code] ?: DtcDb.find(code, family)?.title?.text ?: when {
        code.startsWith("P030") -> tr("Пропуски зажигания в цилиндре ${code.last()}", "Cylinder ${code.last()} misfire detected")
        code.startsWith("P0") -> tr("Общий код двигателя/трансмиссии", "Generic powertrain code")
        code.startsWith("P1") || code.startsWith("P3") -> tr("Код производителя", "Manufacturer-specific code")
        code.startsWith("P2") -> tr("Общий код (расширенный)", "Generic powertrain code (extended)")
        code.startsWith("C") -> tr("Шасси (ABS/подвеска)", "Chassis (ABS/suspension)")
        code.startsWith("B") -> tr("Кузов", "Body")
        code.startsWith("U") -> tr("Сеть / связь между блоками", "Network / module communication")
        else -> ""
    }

    private val DESCRIPTIONS get() = if (L10n.ru) RU else EN

    private val RU = mapOf(
        "P0010" to "Фазовращатель впуска Б1 — цепь",
        "P0011" to "Фазы впуска Б1 — положение не соответствует",
        "P0013" to "Фазовращатель выпуска Б1 — цепь",
        "P0014" to "Фазы выпуска Б1 — положение не соответствует",
        "P0016" to "Коленвал/распредвал впуска Б1 — рассогласование",
        "P0017" to "Коленвал/распредвал выпуска Б1 — рассогласование",
        "P0018" to "Коленвал/распредвал впуска Б2 — рассогласование",
        "P0019" to "Коленвал/распредвал выпуска Б2 — рассогласование",
        "P0020" to "Фазовращатель впуска Б2 — цепь",
        "P0021" to "Фазы впуска Б2 — положение не соответствует",
        "P0024" to "Фазы выпуска Б2 — положение не соответствует",
        "P0030" to "Подогрев датчика O2 Б1Д1 — цепь",
        "P0036" to "Подогрев датчика O2 Б1Д2 — цепь",
        "P0050" to "Подогрев датчика O2 Б2Д1 — цепь",
        "P0056" to "Подогрев датчика O2 Б2Д2 — цепь",
        "P0068" to "MAP/MAF — несоответствие положению дросселя",
        "P0087" to "Давление топлива слишком низкое",
        "P0100" to "Датчик MAF — цепь",
        "P0101" to "Датчик MAF — вне диапазона",
        "P0102" to "Датчик MAF — низкий сигнал",
        "P0103" to "Датчик MAF — высокий сигнал",
        "P0106" to "Датчик MAP — вне диапазона",
        "P0107" to "Датчик MAP — низкий сигнал",
        "P0108" to "Датчик MAP — высокий сигнал",
        "P0112" to "Датчик температуры воздуха — низкий сигнал",
        "P0113" to "Датчик температуры воздуха — высокий сигнал",
        "P0117" to "Датчик температуры ОЖ — низкий сигнал",
        "P0118" to "Датчик температуры ОЖ — высокий сигнал",
        "P0121" to "Датчик дросселя A — вне диапазона",
        "P0128" to "Термостат: ОЖ ниже рабочей температуры",
        "P0130" to "Датчик O2 Б1Д1 — цепь",
        "P0131" to "Датчик O2 Б1Д1 — низкое напряжение",
        "P0132" to "Датчик O2 Б1Д1 — высокое напряжение",
        "P0133" to "Датчик O2 Б1Д1 — медленный отклик",
        "P0134" to "Датчик O2 Б1Д1 — нет активности",
        "P0137" to "Датчик O2 Б1Д2 — низкое напряжение",
        "P0138" to "Датчик O2 Б1Д2 — высокое напряжение",
        "P0140" to "Датчик O2 Б1Д2 — нет активности",
        "P0150" to "Датчик O2 Б2Д1 — цепь",
        "P0151" to "Датчик O2 Б2Д1 — низкое напряжение",
        "P0152" to "Датчик O2 Б2Д1 — высокое напряжение",
        "P0153" to "Датчик O2 Б2Д1 — медленный отклик",
        "P0154" to "Датчик O2 Б2Д1 — нет активности",
        "P0157" to "Датчик O2 Б2Д2 — низкое напряжение",
        "P0158" to "Датчик O2 Б2Д2 — высокое напряжение",
        "P0160" to "Датчик O2 Б2Д2 — нет активности",
        "P0171" to "Бедная смесь, банк 1",
        "P0172" to "Богатая смесь, банк 1",
        "P0174" to "Бедная смесь, банк 2",
        "P0175" to "Богатая смесь, банк 2",
        "P0201" to "Форсунка цил. 1 — цепь",
        "P0202" to "Форсунка цил. 2 — цепь",
        "P0203" to "Форсунка цил. 3 — цепь",
        "P0204" to "Форсунка цил. 4 — цепь",
        "P0205" to "Форсунка цил. 5 — цепь",
        "P0206" to "Форсунка цил. 6 — цепь",
        "P0230" to "Реле топливного насоса — цепь",
        "P0300" to "Случайные/множественные пропуски зажигания",
        "P0325" to "Датчик детонации 1 — цепь",
        "P0327" to "Датчик детонации 1 — низкий сигнал",
        "P0328" to "Датчик детонации 1 — высокий сигнал",
        "P0332" to "Датчик детонации 2 — низкий сигнал",
        "P0333" to "Датчик детонации 2 — высокий сигнал",
        "P0335" to "Датчик коленвала — цепь",
        "P0340" to "Датчик распредвала — цепь",
        "P0401" to "EGR — недостаточный поток",
        "P0420" to "Эффективность катализатора ниже порога, Б1",
        "P0430" to "Эффективность катализатора ниже порога, Б2",
        "P0442" to "EVAP — малая утечка",
        "P0443" to "Клапан продувки EVAP — цепь",
        "P0446" to "Клапан вентиляции EVAP — цепь",
        "P0449" to "Клапан вентиляции EVAP — цепь",
        "P0455" to "EVAP — большая утечка (крышка бака?)",
        "P0456" to "EVAP — очень малая утечка",
        "P0496" to "EVAP — поток продувки вне режима",
        "P0506" to "Обороты ХХ ниже нормы",
        "P0507" to "Обороты ХХ выше нормы",
        "P0521" to "Датчик давления масла — вне диапазона",
        "P0562" to "Низкое напряжение бортсети",
        "P0563" to "Высокое напряжение бортсети",
        "P0601" to "ЭБУ — ошибка контрольной суммы",
        "P0700" to "Неисправность АКПП (см. TCM)",
        "P0711" to "Датчик температуры масла АКПП — вне диапазона",
        "P0716" to "Датчик скорости входного вала — вне диапазона",
        "P0717" to "Датчик скорости входного вала — нет сигнала",
        "P0722" to "Датчик скорости выходного вала — нет сигнала",
        "P0741" to "Муфта гидротрансформатора — застревает выкл.",
        "P0742" to "Муфта гидротрансформатора — застревает вкл.",
        "P0751" to "Соленоид переключения A",
        "P0756" to "Соленоид переключения B",
        "P0842" to "Датчик давления масла АКПП — низкий",
        "P1101" to "GM: MAF/MAP/дроссель — несоответствие воздуха",
        "P1516" to "GM: дроссельный модуль — положение",
        "P2096" to "Коррекция по 2-му O2 — слишком бедно, Б1",
        "P2097" to "Коррекция по 2-му O2 — слишком богато, Б1",
        "P2098" to "Коррекция по 2-му O2 — слишком бедно, Б2",
        "P2099" to "Коррекция по 2-му O2 — слишком богато, Б2",
        "P2101" to "Привод дросселя — цепь",
        "P2135" to "Датчики дросселя A/B — рассогласование",
        "P2138" to "Датчики педали D/E — рассогласование",
        "P2177" to "Бедная смесь не на ХХ, Б1",
        "P2179" to "Бедная смесь не на ХХ, Б2",
        "P2187" to "Бедная смесь на ХХ, Б1",
        "P2189" to "Бедная смесь на ХХ, Б2",
        "P2270" to "Датчик O2 Б1Д2 — застрял в бедном",
        "P2271" to "Датчик O2 Б1Д2 — застрял в богатом",
        "P2272" to "Датчик O2 Б2Д2 — застрял в бедном",
        "P2273" to "Датчик O2 Б2Д2 — застрял в богатом",
        "U0100" to "Нет связи с ECM",
        "U0101" to "Нет связи с TCM",
        "U0121" to "Нет связи с ABS",
        "U0140" to "Нет связи с BCM",
        "U0151" to "Нет связи с блоком подушек (SDM)",
        "U0155" to "Нет связи с приборной панелью",
    )

    /** SAE J2012 wording; "A" camshaft = intake, "B" = exhaust. */
    private val EN = mapOf(
        "P0010" to "Intake camshaft position actuator circuit B1",
        "P0011" to "Intake camshaft timing over-advanced or system performance B1",
        "P0013" to "Exhaust camshaft position actuator circuit B1",
        "P0014" to "Exhaust camshaft timing over-advanced or system performance B1",
        "P0016" to "Crankshaft/camshaft position correlation B1 intake",
        "P0017" to "Crankshaft/camshaft position correlation B1 exhaust",
        "P0018" to "Crankshaft/camshaft position correlation B2 intake",
        "P0019" to "Crankshaft/camshaft position correlation B2 exhaust",
        "P0020" to "Intake camshaft position actuator circuit B2",
        "P0021" to "Intake camshaft timing over-advanced or system performance B2",
        "P0024" to "Exhaust camshaft timing over-advanced or system performance B2",
        "P0030" to "HO2S heater control circuit B1 S1",
        "P0036" to "HO2S heater control circuit B1 S2",
        "P0050" to "HO2S heater control circuit B2 S1",
        "P0056" to "HO2S heater control circuit B2 S2",
        "P0068" to "MAP/MAF - throttle position correlation",
        "P0087" to "Fuel rail/system pressure too low",
        "P0100" to "MAF circuit",
        "P0101" to "MAF circuit range/performance",
        "P0102" to "MAF circuit low",
        "P0103" to "MAF circuit high",
        "P0106" to "MAP/baro pressure circuit range/performance",
        "P0107" to "MAP/baro pressure circuit low",
        "P0108" to "MAP/baro pressure circuit high",
        "P0112" to "Intake air temperature sensor circuit low",
        "P0113" to "Intake air temperature sensor circuit high",
        "P0117" to "Engine coolant temperature sensor circuit low",
        "P0118" to "Engine coolant temperature sensor circuit high",
        "P0121" to "Throttle/pedal position sensor A circuit range/performance",
        "P0128" to "Coolant thermostat (coolant temp below regulating temp)",
        "P0130" to "O2 sensor circuit B1 S1",
        "P0131" to "O2 sensor circuit low voltage B1 S1",
        "P0132" to "O2 sensor circuit high voltage B1 S1",
        "P0133" to "O2 sensor circuit slow response B1 S1",
        "P0134" to "O2 sensor circuit no activity detected B1 S1",
        "P0137" to "O2 sensor circuit low voltage B1 S2",
        "P0138" to "O2 sensor circuit high voltage B1 S2",
        "P0140" to "O2 sensor circuit no activity detected B1 S2",
        "P0150" to "O2 sensor circuit B2 S1",
        "P0151" to "O2 sensor circuit low voltage B2 S1",
        "P0152" to "O2 sensor circuit high voltage B2 S1",
        "P0153" to "O2 sensor circuit slow response B2 S1",
        "P0154" to "O2 sensor circuit no activity detected B2 S1",
        "P0157" to "O2 sensor circuit low voltage B2 S2",
        "P0158" to "O2 sensor circuit high voltage B2 S2",
        "P0160" to "O2 sensor circuit no activity detected B2 S2",
        "P0171" to "System too lean B1",
        "P0172" to "System too rich B1",
        "P0174" to "System too lean B2",
        "P0175" to "System too rich B2",
        "P0201" to "Injector circuit - cylinder 1",
        "P0202" to "Injector circuit - cylinder 2",
        "P0203" to "Injector circuit - cylinder 3",
        "P0204" to "Injector circuit - cylinder 4",
        "P0205" to "Injector circuit - cylinder 5",
        "P0206" to "Injector circuit - cylinder 6",
        "P0230" to "Fuel pump primary circuit",
        "P0300" to "Random/multiple cylinder misfire detected",
        "P0325" to "Knock sensor 1 circuit",
        "P0327" to "Knock sensor 1 circuit low",
        "P0328" to "Knock sensor 1 circuit high",
        "P0332" to "Knock sensor 2 circuit low",
        "P0333" to "Knock sensor 2 circuit high",
        "P0335" to "Crankshaft position sensor circuit",
        "P0340" to "Camshaft position sensor circuit",
        "P0401" to "EGR flow insufficient detected",
        "P0420" to "Catalyst system efficiency below threshold B1",
        "P0430" to "Catalyst system efficiency below threshold B2",
        "P0442" to "EVAP system leak detected (small leak)",
        "P0443" to "EVAP purge control valve circuit",
        "P0446" to "EVAP vent control circuit performance",
        "P0449" to "EVAP vent valve/solenoid circuit",
        "P0455" to "EVAP system leak detected (large leak; fuel cap?)",
        "P0456" to "EVAP system leak detected (very small leak)",
        "P0496" to "EVAP system high purge flow",
        "P0506" to "Idle control system RPM lower than expected",
        "P0507" to "Idle control system RPM higher than expected",
        "P0521" to "Engine oil pressure sensor/switch range/performance",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0601" to "Internal control module memory checksum error",
        "P0700" to "Transmission control system (MIL request), see TCM",
        "P0711" to "Transmission fluid temperature sensor circuit range/performance",
        "P0716" to "Input/turbine speed sensor circuit range/performance",
        "P0717" to "Input/turbine speed sensor circuit no signal",
        "P0722" to "Output speed sensor circuit no signal",
        "P0741" to "Torque converter clutch circuit performance or stuck off",
        "P0742" to "Torque converter clutch circuit stuck on",
        "P0751" to "Shift solenoid A performance or stuck off",
        "P0756" to "Shift solenoid B performance or stuck off",
        "P0842" to "Transmission fluid pressure sensor/switch circuit low",
        "P1101" to "GM: intake airflow system performance (MAF/MAP/TP)",
        "P1516" to "GM: throttle actuator position performance",
        "P2096" to "Post catalyst fuel trim system too lean B1",
        "P2097" to "Post catalyst fuel trim system too rich B1",
        "P2098" to "Post catalyst fuel trim system too lean B2",
        "P2099" to "Post catalyst fuel trim system too rich B2",
        "P2101" to "Throttle actuator control motor circuit range/performance",
        "P2135" to "Throttle/pedal position sensor A/B voltage correlation",
        "P2138" to "Throttle/pedal position sensor D/E voltage correlation",
        "P2177" to "System too lean off idle B1",
        "P2179" to "System too lean off idle B2",
        "P2187" to "System too lean at idle B1",
        "P2189" to "System too lean at idle B2",
        "P2270" to "O2 sensor signal stuck lean B1 S2",
        "P2271" to "O2 sensor signal stuck rich B1 S2",
        "P2272" to "O2 sensor signal stuck lean B2 S2",
        "P2273" to "O2 sensor signal stuck rich B2 S2",
        "U0100" to "Lost communication with ECM/PCM",
        "U0101" to "Lost communication with TCM",
        "U0121" to "Lost communication with ABS control module",
        "U0140" to "Lost communication with body control module",
        "U0151" to "Lost communication with restraints control module (SDM)",
        "U0155" to "Lost communication with instrument panel cluster",
    )
}
