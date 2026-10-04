package com.obdscanner.gm

import com.obdscanner.car.CarDb
import com.obdscanner.car.ExtCommand
import com.obdscanner.car.ExtSignal
import com.obdscanner.car.SignalFormat
import com.obdscanner.obd.PollRate
import com.obdscanner.tr

/**
 * GM enhanced parameters (Mode $22) with known decoding. Each one is probed after connecting;
 * only those that answer get polled.
 *
 * Sources (collected 2026-09): Holden VE/VF extended PIDs (same HFV6 + 6L gearbox era),
 * GM Class 2 PID lists, OBDb Chevrolet/Cadillac, live tests on GM C1XX. Confidence:
 *   OK  — several sources agree / seen on a live car
 *   ?   — single source or conflicting formulas: compare against a known value before trusting
 * Read rate (`every`): FAST — follows the pedal and shifts, MEDIUM (default), SLOW — counters and wear.
 */
object GmKnown {
    private const val ECM = 0x7E0
    private const val TCM = 0x7E2
    private const val OK = "OK"
    private const val MAYBE = "?"

    private fun ab(d: IntArray) = d[0] * 256 + d[1]
    private fun s16(d: IntArray) = ab(d).let { if (it >= 32768) it - 65536 else it }

    private const val FAST = PollRate.FAST
    private const val SLOW = PollRate.SLOW

    private fun did(req: Int, did: Int, name: String, unit: String, dec: Int, conf: String, group: String, every: Long = PollRate.MEDIUM, cyl: IntRange? = null, f: (IntArray) -> Double?) =
        GmDid(req, "22", did, name, unit, dec, conf, group, every, cyl, f)

    /** Cylinder [n] is on engines with n or more cylinders; 1–2 are on every engine. */
    private fun cylFrom(n: Int) = if (n >= 3) n..CarDb.MAX_CYL else null

    val all: List<GmDid> = buildList {
        // ---- TCM (6L50)
        add(did(TCM, 0x1940, tr("Температура масла АКПП", "Transmission fluid temp"), "°C", 0, OK, "main") { it[0] - 40.0 })
        add(did(TCM, 0x295A, tr("Темп. масла АКПП (фильтр.)", "Trans. fluid temp (filtered)"), "°C", 0, MAYBE, "other") { it[0] - 40.0 })
        add(did(TCM, 0x1991, tr("Проскальзывание гидротрансформатора", "TCC slip speed"), tr("об/мин", "rpm"), 0, OK, "main", every = FAST) { s16(it) / 8.0 })
        // ×0.125 checked on the car: 580 at idle in P with engine 615 and TCC slip 30.
        add(did(TCM, 0x1941, tr("Обороты входного вала АКПП", "Trans. input shaft speed"), tr("об/мин", "rpm"), 0, OK, "main", every = FAST) { ab(it) * 0.125 })
        add(did(TCM, 0x1942, tr("Обороты выходного вала АКПП", "Trans. output shaft speed"), tr("об/мин", "rpm"), 0, MAYBE, "main", every = FAST) { ab(it) * 0.125 })
        add(did(TCM, 0x199A, tr("Передача АКПП", "Current gear"), "", 0, MAYBE, "main", every = FAST) { it[0].toDouble() })
        add(did(TCM, 0x199E, tr("Ток соленоида давления (факт)", "Pressure control solenoid actual"), tr("А", "A"), 2, MAYBE, "other", every = FAST) { it[0] * 0.0195 })
        add(did(TCM, 0x199F, tr("Ток соленоида давления (задан.)", "Pressure control solenoid desired"), tr("А", "A"), 2, MAYBE, "other", every = FAST) { it[0] * 0.0195 })

        // ---- ECM (HFV6 2.8 LP1)
        add(did(ECM, 0x1154, tr("Температура масла двигателя", "Engine oil temp"), "°C", 0, OK, "main") { it[0] - 40.0 })
        add(did(ECM, 0x1470, tr("Давление масла", "Oil pressure"), tr("кПа", "kPa"), 0, MAYBE, "main") { it[0] * 4.0 })
        add(did(ECM, 0x119F, tr("Остаток ресурса масла", "Oil life remaining"), "%", 0, MAYBE, "main", every = SLOW) { it[0] * 100.0 / 255 })
        add(did(ECM, 0x11A6, tr("Откат зажигания по детонации", "Knock retard"), "°", 1, MAYBE, "fuel", every = FAST) { it[0] * 22.5 / 256 })
        add(did(ECM, 0x125D, tr("Откат по детонации 2", "Knock retard 2"), "°", 1, MAYBE, "fuel", every = FAST) { it[0] * 22.5 / 256 })
        add(did(ECM, 0x125E, tr("Счётчик детонации", "Knock counter"), "", 0, MAYBE, "fuel") { it[0].toDouble() })
        add(did(ECM, 0x12D9, tr("Суммарный откат по детонации", "Total knock retard"), "°", 1, MAYBE, "fuel", every = FAST) { it[0] * 0.45 })
        add(did(ECM, 0x119E, tr("Заданное соотношение воздух/топливо", "Desired air/fuel ratio"), "AFR", 1, MAYBE, "fuel", every = FAST) { it[0] / 10.0 })
        add(did(ECM, 0x1141, tr("Напряжение зажигания (IGN1)", "Ignition voltage (IGN1)"), tr("В", "V"), 1, OK, "other", every = SLOW) { it[0] / 10.0 })
        add(did(ECM, 0x1161, tr("Температура за бортом (ECM)", "Ambient air temp (ECM)"), "°C", 0, MAYBE, "other", every = SLOW) { it[0] - 40.0 })
        add(did(ECM, 0x11A1, tr("Время работы двигателя (ECM)", "Engine run time (ECM)"), tr("с", "s"), 0, OK, "other", every = SLOW) { ab(it).toDouble() })
        // Forum formula (A·2.02 psi) gave 394 psi on the car — unknown scaling, keep the raw byte.
        add(did(ECM, 0x1564, tr("Датчик давления кондиционера (сырое)", "A/C pressure sensor (raw)"), "", 0, MAYBE, "other") { it[0].toDouble() })
        // Torque Pro lists (GM trucks): "AC Hi Side Pressure" = A·1.83 − 15 psi. Not yet seen on the car.
        add(did(ECM, 0x1144, tr("Давление кондиционера (выс. сторона)", "A/C high side pressure"), tr("кПа", "kPa"), 0, MAYBE, "other") { (it[0] * 1.83 - 15) * 6.895 })
        // Current misfires — note GM's odd order: 1206 = cyl 1, 1205 = cyl 2. Cylinders 7–8 (OBDb) answered on
        // the Escalade 6.2 V8 (2026-10-04): one byte, like cylinders 1–6.
        for ((d, cyl) in listOf(0x1206 to 1, 0x1205 to 2, 0x1207 to 3, 0x1208 to 4, 0x11EA to 5, 0x11EB to 6, 0x11EC to 7, 0x11ED to 8)) {
            add(did(ECM, d, tr("Пропуски сейчас, цил. $cyl", "Misfires current, cyl $cyl"), "", 0, OK, "fuel", cyl = cylFrom(cyl)) { it[0].toDouble() })
        }
        for ((d, cyl) in listOf(0x1201 to 1, 0x1202 to 2, 0x1203 to 3, 0x1204 to 4, 0x11F8 to 5, 0x11F9 to 6, 0x11FA to 7, 0x11FB to 8)) {
            add(did(ECM, d, tr("Пропуски история, цил. $cyl", "Misfires history, cyl $cyl"), "", 0, MAYBE, "fuel", every = SLOW, cyl = cylFrom(cyl)) { ab(it).toDouble() })
        }
        // Cylinder 8 breaks the run (OBDb: 129A, not 119A).
        for ((d, cyl) in (1..7).map { 0x1192 + it to it } + (0x129A to 8)) {
            add(did(ECM, d, tr("Длительность впрыска, цил. $cyl", "Injector pulse width, cyl $cyl"), tr("мс", "ms"), 2, MAYBE, "fuel", every = SLOW, cyl = cylFrom(cyl)) { ab(it) / 65.535 })
        }
        for (cyl in 1..8) {
            add(did(ECM, 0x162E + cyl, tr("Баланс цилиндра $cyl", "Cylinder balance, cyl $cyl"), "", 2, MAYBE, "fuel", every = SLOW, cyl = cylFrom(cyl)) { (ab(it) - 32768) * 0.015625 })
        }

        // ---- Candidates from OBDb Chevrolet-Traverse (3.6 LLT, same HFV6 family). Not yet seen on
        // the car: logged to compare against known values; several are newer-ECM only.
        add(did(TCM, 0x1B30, tr("Передача АКПП (OBDb)", "Current gear (OBDb)"), "", 0, MAYBE, "other") { it[0] - 3.0 })
        add(did(ECM, 0x13AF, tr("Вентилятор охлаждения (команда)", "Cooling fan commanded"), "%", 0, MAYBE, "other") { it[0] * 100.0 / 255 })
        add(did(ECM, 0x3812, tr("Вентилятор охлаждения (команда 2)", "Cooling fan commanded 2"), "%", 0, MAYBE, "other") { it[0] * 100.0 / 255 })
        add(did(ECM, 0x1200, tr("Пропуски всего", "Misfires total"), "", 0, MAYBE, "other") { it[0].toDouble() })
        add(did(ECM, 0x12C3, tr("Впрыск, банк 1", "Injector pulse width B1"), tr("мс", "ms"), 2, MAYBE, "other") { ab(it) * 0.015 })
        add(did(ECM, 0x12C4, tr("Впрыск, банк 2", "Injector pulse width B2"), tr("мс", "ms"), 2, MAYBE, "other") { ab(it) * 0.015 })
        add(did(ECM, 0x153E, tr("Температура масла двигателя (2)", "Engine oil temp (2)"), "°C", 0, MAYBE, "other") { it[0] - 40.0 })
        add(did(ECM, 0x36A7, tr("Ресурс воздушного фильтра", "Air filter life"), "%", 0, MAYBE, "other", every = SLOW) { it[0].toDouble() })
        for ((d, wheel) in listOf(0x248E to tr("ПЛ", "FL"), 0x248F to tr("ПП", "FR"), 0x2490 to tr("ЗП", "RR"), 0x2491 to tr("ЗЛ", "RL"))) {
            add(did(ECM, d, tr("Давление в шине $wheel", "Tire pressure $wheel"), tr("кПа", "kPa"), 0, MAYBE, "other", every = SLOW) { it[0] * 6.895 })
        }
    }

    fun responseFor(req: Int) = if (req in 0x7E0..0x7E7) req + 8 else req + 0x400

    /** Main-screen roles (see tools/cars/SCHEMA.md `role`). */
    private val ROLES = mapOf(
        0x1940 to "atf_temp", 0x199A to "gear", 0x1991 to "tc_slip", 0x1941 to "input_rpm", 0x1942 to "output_rpm",
        0x1154 to "oil_temp", 0x1470 to "oil_pressure", 0x119F to "oil_life",
    )

    /** The same parameters as manufacturer requests for the poll loop; reading keys stay "7E2:22.1940". */
    val commands: List<ExtCommand> by lazy {
        all.map { d ->
            val signal = ExtSignal("", d.name, d.unit, d.decimals, d.confidence, d.group, ROLES[d.did], SignalFormat(0, 8, 1.0, 1.0, 0.0, false, null), cyl = d.cyl)
            ExtCommand(d.req, responseFor(d.req), d.service, d.did, d.periodMs, listOf(signal), source = "code", code = d.f)
        }
    }
}
