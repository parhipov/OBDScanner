package com.obdscanner.screen

import com.obdscanner.AppInfo
import com.obdscanner.VehicleInfo
import com.obdscanner.gm.DtcScheme
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.Make
import com.obdscanner.obd.Mode09
import com.obdscanner.obd.Pids
import com.obdscanner.obd.Reading
import com.obdscanner.obd.ecuName
import com.obdscanner.tr
import com.obdscanner.util.format
import com.obdscanner.util.timeText

/** Every live value, grouped by ECU (the computed ones last). */
object AllScreen {
    fun build(r: Map<String, Reading>, v: VehicleInfo): List<Block> {
        val b = Blocks()
        b.note(tr("Опрашиваются все поддерживаемые PID: ${v.supported01.count { !Pids.isBitmask(it) }} шт. Всего значений: ${r.size}.",
            "Polling all supported PIDs: ${v.supported01.count { !Pids.isBitmask(it) }}. Values in total: ${r.size}."))
        val groups = r.values.groupBy { it.ecu }.entries.sortedBy { if (it.key == 0) Int.MAX_VALUE else it.key }
        for ((ecu, list) in groups) {
            b.title("${ecuName(ecu)} · %03X".format(ecu))
            for (x in list.sortedBy { it.source }) b.value(x)
        }
        return b.build()
    }
}

/** Trouble codes: Mode 03/07/0A, the freeze frame, every module's own memory. [busy] — the operation running now. */
object CodesScreen {
    fun build(v: VehicleInfo, busy: String?): List<Block> {
        val b = Blocks()
        b.buttons(listOf(Button(Action.READ_DTC, tr("Прочитать", "Read"), primary = true), Button(Action.CLEAR_DTC, tr("Сбросить…", "Clear…"))))
        if (busy != null) b.note(tr("Выполняется: $busy", "In progress: $busy"))
        if (v.dtcTime > 0) b.note(tr("Прочитано в ${timeText(v.dtcTime)}", "Read at ${timeText(v.dtcTime)}"))
        for (kind in DtcKind.entries) {
            val list = v.dtcs.filter { it.kind == kind }
            b.title("${kind.title} (${list.size})")
            if (list.isEmpty()) b.note(if (kind == DtcKind.STORED && v.dtcNoAnswer) tr("нет ответа", "no answer") else tr("нет", "none"))
            for (d in list) b.row(d.code, ecuName(d.ecu), "", d.description, if (kind == DtcKind.PENDING) Level.WARN else Level.BAD,
                DtcRef(d.code, ecuName(d.ecu), kind = kind))
        }
        b.title(tr("Стоп-кадр", "Freeze frame") + (v.freezeDtc?.let { " — $it" } ?: ""))
        if (v.freeze.isEmpty()) b.note(tr("нет", "none"))
        for (f in v.freeze) b.value(f)
        if (v.kline) {
            b.title(tr("Все блоки", "All modules"))
            b.note(tr("Машина на K-line: доступны только стандартные ошибки OBD (выше). Память ошибок отдельных блоков ELM327 на K-line не читает.",
                "The car is on K-line: only the standard OBD codes (above) are available. ELM327 cannot read the code memory of individual modules on K-line."))
        } else if (v.make == Make.VAG) {
            b.title(tr("Все блоки VW", "All VW modules"))
            b.note(tr("Полная память ошибок каждого блока, который отвечает на OBD-разъёме: двигатель, КПП, на новых машинах — ABS, подушки, приборка и др. " +
                "Только чтение. Сначала ищутся блоки (~2 мин), дальше несколько секунд на блок. Зажигание включено, машина стоит.",
                "The full code memory of every module that answers on the OBD port: engine, transmission, on newer cars also ABS, airbags, cluster etc. " +
                    "Read only. The modules are found first (~2 min), then a few seconds per module. Ignition on, car parked."))
        } else if (v.make != Make.GM) {
            b.title(tr("Все блоки", "All modules"))
            b.note(tr("Полная память ошибок блоков на стандартных адресах OBD (7E0–7E7): обычно двигатель и КПП, включая коды без Check. " +
                "Только чтение, несколько секунд на блок. Зажигание включено, машина стоит.",
                "The full code memory of the modules on the standard OBD addresses (7E0–7E7): usually engine and transmission, including codes without Check Engine. " +
                    "Read only, a few seconds per module. Ignition on, car parked."))
        } else {
            b.title(tr("Все блоки GM", "All GM modules"))
            b.note(tr("Полная память ошибок каждого блока на HS-CAN: ECM, TCM, ABS, BCM и др., включая коды без Check и тип отказа. " +
                "Только чтение. Сначала ищутся модули (~1 мин), дальше несколько секунд на блок. Зажигание включено, машина стоит.",
                "The full code memory of every module on HS-CAN: ECM, TCM, ABS, BCM etc., including codes without Check Engine, and the failure type. " +
                    "Read only. The modules are found first (~1 min), then a few seconds per module. Ignition on, car parked."))
        }
        b.buttons(listOf(Button(Action.READ_ALL_MODULES, tr("Прочитать все блоки", "Read all modules"), primary = true)))
        if (v.gmDtcStatus.isNotEmpty()) b.note(v.gmDtcStatus)
        if (v.gmDtcTime > 0) b.note(tr("Прочитано в ${timeText(v.gmDtcTime)}", "Read at ${timeText(v.gmDtcTime)}"))
        for (m in v.gmDtcs) {
            val where = "${m.module.name} · ${m.module.id}"
            b.title(where)
            b.note(m.result)
            for (c in m.codes) b.row(c.full, if (c.current) tr("активна", "active") else tr("история", "history"), "", "${c.description} · ${c.flags}",
                if (c.current || c.mil) Level.BAD else Level.WARN,
                DtcRef(c.code, where, c.failureType.takeIf { it >= 0 }, c.scheme == DtcScheme.GM, c.flags, label = c.full))
        }
        return b.build()
    }

    /** The question before Mode 04. */
    val CLEAR_TITLE = tr("Сбросить ошибки?", "Clear trouble codes?")
    val CLEAR_TEXT = tr("Будут стёрты коды, стоп-кадр и готовность мониторов (Mode 04). Зажигание включено, двигатель заглушен. Перед сбросом все коды уже записаны в сессию.",
        "Codes, freeze frame and readiness monitors will be erased (Mode 04). Ignition on, engine off. All codes are already saved to the session.")
    val CLEAR_YES = tr("Сбросить", "Clear")
    val CLEAR_NO = tr("Отмена", "Cancel")
}

/** The adapter, the car, every ECU with its Mode 09 data and readiness, the Mode 06 tests. */
object InfoScreen {
    fun build(v: VehicleInfo): List<Block> {
        val b = Blocks()
        b.title(tr("Адаптер", "Adapter"))
        b.row(tr("Версия", "Version"), v.adapter.ifEmpty { "—" })
        b.row(tr("Описание", "Description"), v.adapterDesc.ifEmpty { "—" })
        b.row(tr("Протокол", "Protocol"), v.protocol.ifEmpty { "—" })
        b.row(tr("Мульти-PID запросы", "Multi-PID requests"), if (v.multiPid) tr("да", "yes") else tr("нет", "no"))
        b.title(tr("Автомобиль", "Vehicle"))
        b.row("VIN", v.vin ?: "—")
        if (v.adapter.isNotEmpty()) b.row(tr("Марка (по VIN)", "Make (from VIN)"), listOfNotNull(v.brand, v.make.title.takeIf { v.make != Make.OTHER || v.brand == null }).distinct().joinToString(" · "))
        v.car?.let { b.row(tr("Модель", "Model"), it.title, sub = it.engines.joinToString("; ") { e -> e.title }.ifEmpty { null }) }
        if (v.adapter.isNotEmpty()) b.row(tr("Параметры производителя", "Manufacturer parameters"),
            tr("ответили ${v.extActive.size}", "${v.extActive.size} answered"),
            sub = tr("список — в report.txt", "the list is in report.txt"))
        b.buttons(listOf(Button(Action.RESCAN, tr("Опросить заново", "Rescan")), Button(Action.MODE06, "Mode 06")))
        for (e in v.ecus.values.sortedBy { it.header }) {
            b.title("${ecuName(e.header)} · %03X".format(e.header))
            b.row("PID Mode 01", "${e.pids01.count { !Pids.isBitmask(it) }}", key = "pids:${e.header}")
            for ((t, s) in e.info09) b.row(Mode09.name(t), if (t == 0x08 || t == 0x0B) "" else s, sub = if (t == 0x08 || t == 0x0B) s else null, key = "09:${e.header}:$t")
            if (e.readiness.isNotEmpty()) {
                b.note(tr("Готовность мониторов:", "Readiness monitors:"))
                for (mon in e.readiness.filter { it.available }) {
                    b.row(mon.name, if (mon.complete) tr("готов", "complete") else tr("не готов", "not complete"),
                        level = if (mon.complete) Level.GOOD else Level.WARN, key = "mon:${e.header}:${mon.name}")
                }
            }
        }
        if (v.mode06.isNotEmpty()) {
            b.title(tr("Бортовые тесты (Mode 06): ${v.mode06.size}", "On-board tests (Mode 06): ${v.mode06.size}"))
            for (t in v.mode06) b.row("${t.midName}", "${Reading.fmt(t.value, 3)}", t.unit,
                if (t.notRun) tr("${t.tidName} · не выполнялся", "${t.tidName} · not run") else tr("${t.tidName} · норма ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}", "${t.tidName} · limits ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}"),
                if (t.notRun) Level.MUTED else if (t.passed) Level.GOOD else Level.BAD, divider = true, key = "%03X.%02X.%02X".format(t.ecu, t.mid, t.tid))
        }
        b.title(tr("О приложении", "About"))
        b.note("OBD Scanner ${AppInfo.versionName} · MIT · github.com/parhipov/OBDScanner")
        b.buttons(listOf(Button(Action.LICENSES, Licenses.TITLE, whileBusy = true)))
        return b.build()
    }
}
