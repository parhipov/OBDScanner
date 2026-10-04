package com.obdscanner.screen

import com.obdscanner.VehicleInfo
import com.obdscanner.obd.Reading
import com.obdscanner.obd.ecuName
import com.obdscanner.obd.pick
import com.obdscanner.tr
import com.obdscanner.util.format
import kotlin.math.abs

/** The Fuel screen: trims, mixture, misfires, ignition, consumption, catalysts, fuel codes — and what they suggest. */
object FuelScreen {
    fun build(r: Map<String, Reading>, v: VehicleInfo): List<Block> {
        fun p(k: String) = r.pick(k)
        val b = Blocks()
        val t1 = p("calc.trim1")?.value
        val t2 = p("calc.trim2")?.value
        // Bank 2 exists only on V and boxer engines: an inline ECU doesn't support PIDs 08/09 (Polo, RAV4).
        val twoBanks = p("01.08") != null || p("01.09") != null || v.supported01.any { it == 0x08 || it == 0x09 }
        val misfire = v.mode06.filter { it.misfireCylinder != null && it.tid == 0x0C }.sortedBy { it.misfireCylinder }
        val misfireAvg = v.mode06.filter { it.misfireCylinder != null && it.tid == 0x0B }.associateBy { it.misfireCylinder }
        val fuelDtcs = v.dtcs.filter { it.fuelRelated }
        fun rows(vararg keys: String) {
            for (k in keys) p(k)?.let { b.value(it) }
        }

        for ((text, level) in hints(t1, if (twoBanks) t2 else null, p("01.03")?.text, misfire.map { it.misfireCylinder!! to it.value }, fuelDtcs.map { it.code })) {
            b.banner(text, level)
        }

        b.title(tr("Топливные коррекции", "Fuel trims"))
        fun bank(title: String, st: Reading?, lt: Reading?, sum: Reading?) {
            val ltMin = lt?.min
            val ltMax = lt?.max
            b.bank(title, st, lt, sum, if (ltMin != null && ltMax != null)
                tr("Долгосрочная за сессию: ${Reading.fmt(ltMin, 1)}…${Reading.fmt(ltMax, 1)} %", "Long term this session: ${Reading.fmt(ltMin, 1)}…${Reading.fmt(ltMax, 1)} %") else null)
        }
        if (twoBanks) {
            // Bank 1 is the row with cylinder 1 (SAE); which others are in it depends on the maker.
            bank(tr("Банк 1 (ряд с цилиндром 1)", "Bank 1 (row with cylinder 1)"), p("01.06"), p("01.07"), p("calc.trim1"))
            bank(tr("Банк 2 (второй ряд)", "Bank 2 (other row)"), p("01.08"), p("01.09"), p("calc.trim2"))
            p("calc.trimDiff")?.let { b.value(it, trimLevel(it.value)) }
        } else {
            bank(tr("Все цилиндры (рядный мотор, один банк)", "All cylinders (inline engine, one bank)"), p("01.06"), p("01.07"), p("calc.trim1"))
        }
        listOf("01.55.A", "01.56.A", "01.57.A", "01.58.A").mapNotNull { p(it) }.forEach { b.value(it) }

        b.title(tr("Смесь и датчики кислорода", "Mixture & O2 sensors"))
        rows("01.03.S", "01.03", "01.44")
        r.values.filter { it.source.matches(O2) }.sortedBy { it.source }.forEach { b.value(it) }

        b.title(tr("Пропуски зажигания (Mode 06)", "Misfires (Mode 06)"))
        if (misfire.isEmpty()) b.note(tr("ЭБУ не отдаёт счётчики пропусков по цилиндрам (или ещё не прочитаны).", "The ECU does not report per-cylinder misfire counts (or they are not read yet)."))
        b.misfires(misfire.map { t ->
            val n = t.value
            Cylinder(tr("Ц${t.misfireCylinder}", "Cyl ${t.misfireCylinder}"), Reading.fmt(n, 0),
                if (n >= 20) Level.BAD else if (n > 0) Level.WARN else Level.GOOD,
                misfireAvg[t.misfireCylinder]?.let { tr("ср. ${Reading.fmt(it.value, 0)}", "avg ${Reading.fmt(it.value, 0)}") })
        })
        b.buttons(listOf(Button(Action.MODE06, tr("Обновить сейчас", "Refresh now"), whileBusy = true)),
            tr("Цикл = текущая/последняя поездка. На этой вкладке обновляется каждые 15 с.", "Cycle = current/last trip. Refreshed every 15 s on this tab."))

        b.title(tr("Зажигание и детонация", "Ignition & knock"))
        rows("01.0E", "22.11A6", "22.125D", "22.12D9", "22.125E", "22.119E")
        b.title(tr("Нагрузка и воздух", "Load & air"))
        rows("01.04", "01.43", "01.10", "01.0B", "01.0F", "01.0C", "01.33")
        b.title(tr("Дроссель и педаль", "Throttle & pedal"))
        b.note(tr("Два датчика дросселя и два датчика педали должны меняться вместе; команда — куда ЭБУ ставит заслонку.",
            "The two throttle sensors and the two pedal sensors should move together; commanded = where the ECU sets the throttle plate."))
        rows("01.11", "01.45", "01.47", "01.48", "01.4C", "01.49", "01.4A", "01.4B")

        val gmCyl = GM_CYL.map { row -> row to row.dids.map { r.pick(it) } }.filter { (_, v) -> v.any { it != null } }
        // As many columns as the engine has cylinders with data: a four-cylinder isn't asked for 5–6.
        val cols = gmCyl.maxOfOrNull { (_, v) -> v.indexOfLast { it != null } + 1 } ?: 0
        if (gmCyl.isNotEmpty()) {
            b.title(tr("По цилиндрам (GM)", "Per cylinder (GM)"))
            b.table("gm_cyl", (1..cols).map { c -> tr("Ц$c", "C$c") }, gmCyl.map { (row, vals) ->
                TableRow(row.label, vals.take(cols).map { v -> Cell(v?.display() ?: "—", if (row.misfire && (v?.value ?: 0.0) > 0) Level.WARN else null) })
            })
        }

        b.title(tr("Расход и топливо", "Consumption & fuel"))
        rows("calc.lph", "calc.l100", "01.5E", "01.9D.E", "01.A2", "01.2F", "01.51.S", "01.51", "01.52",
            "01.0A", "01.22", "01.23", "01.59", "01.5D")

        b.title(tr("Катализаторы и EVAP", "Catalysts & EVAP"))
        rows("01.3C", "01.3D", "01.3E", "01.3F", "01.2E", "01.32", "01.53", "01.54")
        for (t in v.mode06.filter { it.mid in 0x21..0x24 }) b.row("${t.midName}: ${t.tidName}", Reading.fmt(t.value, 3), t.unit,
            if (t.notRun) tr("не выполнялся", "not run") else tr("норма ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}", "limits ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}"),
            if (t.notRun) Level.MUTED else if (t.passed) Level.GOOD else Level.BAD)

        if (fuelDtcs.isNotEmpty()) {
            b.title(tr("Ошибки, связанные с топливом", "Fuel-related codes"))
            for (d in fuelDtcs) b.row(d.code, d.kind.title, "", d.description, Level.BAD, DtcRef(d.code, ecuName(d.ecu), kind = d.kind))
        }
        b.gap()
        return b.build()
    }

    private val O2 = Regex("01\\.(1[4-9A-B]|2[4-9A-B]|3[4-9A-B])\\..")

    /** A row of the per-cylinder GM table: DIDs for cylinders 1..8; [misfire] rows are highlighted when non-zero. */
    private class CylRow(val label: String, val dids: List<String>, val misfire: Boolean = false)

    private val GM_CYL = listOf(
        CylRow(tr("Пропуски сейчас", "Misfires now"), listOf("22.1206", "22.1205", "22.1207", "22.1208", "22.11EA", "22.11EB", "22.11EC", "22.11ED"), misfire = true),
        CylRow(tr("Пропуски история", "Misfire history"), listOf("22.1201", "22.1202", "22.1203", "22.1204", "22.11F8", "22.11F9", "22.11FA", "22.11FB"), misfire = true),
        CylRow(tr("Впрыск, мс", "Inj. pulse, ms"), (1..7).map { "22.%04X".format(0x1192 + it) } + "22.129A"),
        CylRow(tr("Баланс", "Balance"), (1..8).map { "22.%04X".format(0x162E + it) }),
    )

    /** What the trims, misfires and codes suggest, most important first. */
    fun hints(
        t1: Double?, t2: Double?, fuelStatus: String?,
        misfires: List<Pair<Int, Double>>, fuelDtcs: List<String>,
    ): List<Pair<String, Level>> {
        val out = mutableListOf<Pair<String, Level>>()
        if (fuelStatus != null && fuelStatus.contains(tr("разомкнутый", "open loop"))) {
            out += tr("Разомкнутый режим ($fuelStatus): по коррекциям сейчас ничего не понять.",
                "Open loop ($fuelStatus): the trims tell nothing right now.") to Level.WARN
        }
        // One bank (inline engine): the same checks without the bank comparison.
        if (t1 != null && t2 == null) when {
            t1 > 10 -> out += tr("Смесь обеднена (+${Reading.fmt(t1, 0)}%): подсос воздуха, слабый бензонасос/фильтр, грязный MAF, форсунки.",
                "Mixture lean (+${Reading.fmt(t1, 0)}%): vacuum leak, weak fuel pump/filter, dirty MAF, injectors.") to Level.BAD
            t1 < -10 -> out += tr("Смесь обогащена (${Reading.fmt(t1, 0)}%): давление топлива, подтекающие форсунки, MAF завышает, адсорбер, датчик O2.",
                "Mixture rich (${Reading.fmt(t1, 0)}%): fuel pressure, leaking injectors, MAF reading high, EVAP canister, O2 sensor.") to Level.BAD
            abs(t1) < 5 -> out += tr("Смесь в норме.", "Fuel trim normal — mixture is fine.") to Level.GOOD
        }
        if (t1 != null && t2 != null) {
            val lean1 = t1 > 10; val lean2 = t2 > 10
            val rich1 = t1 < -10; val rich2 = t2 < -10
            when {
                lean1 && lean2 -> out += tr("Оба банка обеднены (+${Reading.fmt(t1, 0)}% / +${Reading.fmt(t2, 0)}%): подсос воздуха, слабый бензонасос/фильтр, грязный MAF.",
                    "Both banks lean (+${Reading.fmt(t1, 0)}% / +${Reading.fmt(t2, 0)}%): vacuum leak, weak fuel pump/filter, dirty MAF.") to Level.BAD
                rich1 && rich2 -> out += tr("Оба банка обогащены: давление топлива, подтекающие форсунки, MAF завышает, адсорбер.",
                    "Both banks rich: fuel pressure, leaking injectors, MAF reading high, EVAP canister.") to Level.BAD
                lean1 || lean2 -> out += tr("Обеднён только банк ${if (lean1) 1 else 2}: подсос на этом банке (прокладка впуска), форсунка, датчик O2.",
                    "Only bank ${if (lean1) 1 else 2} lean: vacuum leak on that bank (intake gasket), injector, O2 sensor.") to Level.WARN
                rich1 || rich2 -> out += tr("Обогащён только банк ${if (rich1) 1 else 2}: форсунка льёт, датчик O2 этого банка.",
                    "Only bank ${if (rich1) 1 else 2} rich: leaking injector, that bank's O2 sensor.") to Level.WARN
                abs(t1 - t2) > 8 -> out += tr("Банки расходятся на ${Reading.fmt(abs(t1 - t2), 0)}% — стоит проверить впуск и форсунки.",
                    "Banks differ by ${Reading.fmt(abs(t1 - t2), 0)}% — check the intake and injectors.") to Level.WARN
                abs(t1) < 5 && abs(t2) < 5 -> out += tr("Смесь в норме.", "Fuel trims normal — mixture is fine.") to Level.GOOD
            }
        }
        val bad = misfires.filter { it.second > 0 }
        if (bad.isNotEmpty()) {
            out += tr("Пропуски зажигания: ", "Misfires: ") + bad.joinToString { tr("Ц${it.first}: ${Reading.fmt(it.second, 0)}", "Cyl ${it.first}: ${Reading.fmt(it.second, 0)}") } +
                tr(". Если по всем цилиндрам — топливо/давление; если по одному — свеча/катушка/форсунка.",
                    ". If on all cylinders — fuel/pressure; if on one — plug/coil/injector.") to if (bad.any { it.second >= 20 }) Level.BAD else Level.WARN
        }
        if (fuelDtcs.isNotEmpty()) out += tr("Коды по топливу/смеси: ${fuelDtcs.joinToString()}", "Fuel/mixture codes: ${fuelDtcs.joinToString()}") to Level.BAD
        return out
    }
}
