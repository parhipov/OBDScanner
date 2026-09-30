package com.obdscanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.VehicleInfo
import com.obdscanner.gm.DtcScheme
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.Make
import com.obdscanner.obd.Mode09
import com.obdscanner.obd.Pids
import com.obdscanner.obd.Reading
import com.obdscanner.obd.ecuName
import com.obdscanner.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every live value, grouped by ECU. */
@Composable
fun AllScreen(r: Map<String, Reading>, v: VehicleInfo) {
    val groups = r.values.groupBy { it.ecu }.toSortedMap(compareBy { if (it == 0) Int.MAX_VALUE else it })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            Muted(tr("Опрашиваются все поддерживаемые PID: ${v.supported01.count { !Pids.isBitmask(it) }} шт. Всего значений: ${r.size}.",
                "Polling all supported PIDs: ${v.supported01.count { !Pids.isBitmask(it) }}. Values in total: ${r.size}."))
        }
        for ((ecu, list) in groups) {
            item(key = "h$ecu") { SectionTitle("${ecuName(ecu)} · %03X".format(ecu)) }
            items(list.sortedBy { it.source }, key = { it.key }) { ReadingRow(it) }
        }
    }
}

@Composable
fun DtcScreen(m: ObdManager, v: VehicleInfo, busy: String?) {
    var confirm by remember { mutableStateOf(false) }
    // Tapped code: its description dialog.
    var shown by remember { mutableStateOf<DtcShown?>(null) }
    shown?.let { DtcHelpDialog(it, v.dtcFamily) { shown = null } }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            Row(Modifier.padding(8.dp)) {
                Button(onClick = { m.refreshDtc() }, enabled = busy == null) { Text(tr("Прочитать", "Read")) }
                OutlinedButton(onClick = { confirm = true }, enabled = busy == null, modifier = Modifier.padding(start = 8.dp)) { Text(tr("Сбросить…", "Clear…")) }
            }
            if (busy != null) Muted(tr("Выполняется: $busy", "In progress: $busy"))
            if (v.dtcTime > 0) Muted(tr("Прочитано в ${fmt.format(Date(v.dtcTime))}", "Read at ${fmt.format(Date(v.dtcTime))}"))
        }
        for (kind in DtcKind.entries) {
            val list = v.dtcs.filter { it.kind == kind }
            item(key = kind.name) {
                SectionTitle("${kind.title} (${list.size})")
                if (list.isEmpty()) Muted(tr("нет", "none"))
                for (d in list) Box(Modifier.clickable { shown = DtcShown(d.code, ecuName(d.ecu), kind = kind) }) {
                    ValueRow(d.code, ecuName(d.ecu), "", d.description, if (kind == DtcKind.PENDING) Warn else Bad)
                }
            }
        }
        item {
            SectionTitle(tr("Стоп-кадр", "Freeze frame") + (v.freezeDtc?.let { " — $it" } ?: ""))
            if (v.freeze.isEmpty()) Muted(tr("нет", "none"))
            for (f in v.freeze) ReadingRow(f)
        }
        item {
            if (v.kline) {
                SectionTitle(tr("Все блоки", "All modules"))
                Muted(tr("Машина на K-line: доступны только стандартные ошибки OBD (выше). Память ошибок отдельных блоков ELM327 на K-line не читает.",
                    "The car is on K-line: only the standard OBD codes (above) are available. ELM327 cannot read the code memory of individual modules on K-line."))
            } else if (v.make == Make.VAG) {
                SectionTitle(tr("Все блоки VW", "All VW modules"))
                Muted(tr("Полная память ошибок каждого блока, который отвечает на OBD-разъёме: двигатель, КПП, на новых машинах — ABS, подушки, приборка и др. " +
                    "Только чтение. Сначала ищутся блоки (~2 мин), дальше несколько секунд на блок. Зажигание включено, машина стоит.",
                    "The full code memory of every module that answers on the OBD port: engine, transmission, on newer cars also ABS, airbags, cluster etc. " +
                    "Read only. The modules are found first (~2 min), then a few seconds per module. Ignition on, car parked."))
            } else if (v.make != Make.GM) {
                SectionTitle(tr("Все блоки", "All modules"))
                Muted(tr("Полная память ошибок блоков на стандартных адресах OBD (7E0–7E7): обычно двигатель и КПП, включая коды без Check. " +
                    "Только чтение, несколько секунд на блок. Зажигание включено, машина стоит.",
                    "The full code memory of the modules on the standard OBD addresses (7E0–7E7): usually engine and transmission, including codes without Check Engine. " +
                    "Read only, a few seconds per module. Ignition on, car parked."))
            } else {
                SectionTitle(tr("Все блоки GM", "All GM modules"))
                Muted(tr("Полная память ошибок каждого блока на HS-CAN: ECM, TCM, ABS, BCM и др., включая коды без Check и тип отказа. " +
                    "Только чтение. Сначала ищутся модули (~1 мин), дальше несколько секунд на блок. Зажигание включено, машина стоит.",
                    "The full code memory of every module on HS-CAN: ECM, TCM, ABS, BCM etc., including codes without Check Engine, and the failure type. " +
                    "Read only. The modules are found first (~1 min), then a few seconds per module. Ignition on, car parked."))
            }
            Row(Modifier.padding(8.dp)) {
                Button(onClick = { m.readAllModulesDtc() }, enabled = busy == null) { Text(tr("Прочитать все блоки", "Read all modules")) }
            }
            if (v.gmDtcStatus.isNotEmpty()) Muted(v.gmDtcStatus)
            if (v.gmDtcTime > 0) Muted(tr("Прочитано в ${fmt.format(Date(v.gmDtcTime))}", "Read at ${fmt.format(Date(v.gmDtcTime))}"))
        }
        for (r in v.gmDtcs) {
            item(key = "gm${r.module.id}") {
                SectionTitle("${r.module.name} · ${r.module.id}")
                Muted(r.result)
                for (c in r.codes) Box(Modifier.clickable {
                    shown = DtcShown(c.code, "${r.module.name} · ${r.module.id}", c.failureType.takeIf { it >= 0 }, c.scheme == DtcScheme.GM, c.flags, label = c.full)
                }) {
                    ValueRow(c.full, if (c.current) tr("активна", "active") else tr("история", "history"), "", "${c.description} · ${c.flags}",
                        if (c.current || c.mil) Bad else Warn)
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Сбросить ошибки?", "Clear trouble codes?")) },
        text = { Text(tr("Будут стёрты коды, стоп-кадр и готовность мониторов (Mode 04). Зажигание включено, двигатель заглушен. Перед сбросом все коды уже записаны в сессию.",
            "Codes, freeze frame and readiness monitors will be erased (Mode 04). Ignition on, engine off. All codes are already saved to the session.")) },
        confirmButton = { TextButton(onClick = { confirm = false; m.clearDtc() }) { Text(tr("Сбросить", "Clear")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Отмена", "Cancel")) } },
    )
}

@Composable
fun InfoScreen(m: ObdManager, v: VehicleInfo, busy: String?) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            SectionTitle(tr("Адаптер", "Adapter"))
            ValueRow(tr("Версия", "Version"), v.adapter.ifEmpty { "—" })
            ValueRow(tr("Описание", "Description"), v.adapterDesc.ifEmpty { "—" })
            ValueRow(tr("Протокол", "Protocol"), v.protocol.ifEmpty { "—" })
            ValueRow(tr("Мульти-PID запросы", "Multi-PID requests"), if (v.multiPid) tr("да", "yes") else tr("нет", "no"))
            SectionTitle(tr("Автомобиль", "Vehicle"))
            ValueRow("VIN", v.vin ?: "—")
            if (v.adapter.isNotEmpty()) ValueRow(tr("Марка (по VIN)", "Make (from VIN)"), listOfNotNull(v.brand, v.make.title.takeIf { v.make != Make.OTHER || v.brand == null }).distinct().joinToString(" · "))
            v.car?.let { ValueRow(tr("Модель", "Model"), it.title, sub = it.engines.joinToString("; ") { e -> e.title }.ifEmpty { null }) }
            if (v.adapter.isNotEmpty()) ValueRow(tr("Параметры производителя", "Manufacturer parameters"),
                tr("ответили ${v.extActive.size}", "${v.extActive.size} answered"),
                sub = tr("список — в report.txt", "the list is in report.txt"))
            Row(Modifier.padding(8.dp)) {
                OutlinedButton(onClick = { m.rediscover() }, enabled = busy == null) { Text(tr("Опросить заново", "Rescan")) }
                OutlinedButton(onClick = { m.refreshMode06() }, enabled = busy == null, modifier = Modifier.padding(start = 8.dp)) { Text("Mode 06") }
            }
        }
        for (e in v.ecus.values.sortedBy { it.header }) {
            item(key = "ecu${e.header}") {
                SectionTitle("${ecuName(e.header)} · %03X".format(e.header))
                ValueRow("PID Mode 01", "${e.pids01.count { !Pids.isBitmask(it) }}")
                for ((t, s) in e.info09) ValueRow(Mode09.name(t), if (t == 0x08 || t == 0x0B) "" else s, sub = if (t == 0x08 || t == 0x0B) s else null)
                if (e.readiness.isNotEmpty()) {
                    Muted(tr("Готовность мониторов:", "Readiness monitors:"))
                    for (mon in e.readiness.filter { it.available }) {
                        ValueRow(mon.name, if (mon.complete) tr("готов", "complete") else tr("не готов", "not complete"), color = if (mon.complete) Good else Warn)
                    }
                }
            }
        }
        if (v.mode06.isNotEmpty()) {
            item { SectionTitle(tr("Бортовые тесты (Mode 06): ${v.mode06.size}", "On-board tests (Mode 06): ${v.mode06.size}")) }
            items(v.mode06, key = { "%03X.%02X.%02X".format(it.ecu, it.mid, it.tid) }) { t ->
                ValueRow("${t.midName}", "${Reading.fmt(t.value, 3)}", t.unit,
                    if (t.notRun) tr("${t.tidName} · не выполнялся", "${t.tidName} · not run") else tr("${t.tidName} · норма ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}", "${t.tidName} · limits ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}"),
                    if (t.notRun) MaterialTheme.colorScheme.outline else if (t.passed) Good else Bad)
                HorizontalDivider()
            }
        }
    }
}
