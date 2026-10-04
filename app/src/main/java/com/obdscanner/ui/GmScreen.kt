package com.obdscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.BusState
import com.obdscanner.ScanState
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.ScanRanges
import com.obdscanner.obd.Reading
import com.obdscanner.tr

@Composable
fun GmScreen(m: ObdManager, s: ScanState, bus: BusState, r: Map<String, Reading>, busy: String?, connected: Boolean) {
    var rangeIdx by remember { mutableIntStateOf(0) }
    var rangeMenu by remember { mutableStateOf(false) }
    var onlyWatched by remember { mutableStateOf(false) }
    val hits = if (onlyWatched) s.hits.filter { it.key in s.watched } else s.hits

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            Hint(tr("Старый ELM327 видит только HS-CAN: ECM, TCM, ABS, BCM и др. Подушки, приборка, радио, климат, TPMS — на однопроводной GMLAN (пин 1), для них нужен OBDLink MX+.",
                "An old ELM327 sees HS-CAN only: ECM, TCM, ABS, BCM etc. Airbags, cluster, radio, climate, TPMS are on single-wire GMLAN (pin 1) and need an OBDLink MX+."), Warn)
            Hint(tr("Только чтение (сервисы \$1A и \$22). Сканировать на стоящей машине: зажигание включено или двигатель на ХХ. " +
                "Найденные DID-ы пишутся в scan.csv сессии. Отметьте ☑ интересные — они будут опрашиваться, и по логу можно понять, что это за параметр.",
                "Read only (services \$1A and \$22). Scan with the car parked: ignition on or engine at idle. " +
                "Found DIDs are written to the session's scan.csv. Tick ☑ the interesting ones: they will be polled, and the log shows what the parameter is."))
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { m.probeModules() }, enabled = connected && busy == null) { Text(tr("Найти модули", "Find modules")) }
                if (busy != null) OutlinedButton(onClick = { m.stopOp() }, Modifier.padding(start = 8.dp)) { Text(tr("Стоп", "Stop")) }
            }
            if (s.running || s.status.isNotEmpty()) {
                if (s.running) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                Muted(s.status)
            }
            SectionTitle(tr("Прослушка шины", "Bus sniffing"))
            Muted(tr("Слушает обычный обмен между блоками (ничего не отправляет в блоки). ~10 с обзор, потом по 1.5 с на каждый ID — обычно 1–2 минуты. Лучше с заведённым двигателем; можно погазовать. Пишется в bus.csv.",
                "Listens to the normal traffic between modules (sends nothing to them). ~10 s overview, then 1.5 s per ID — usually 1–2 minutes. Best with the engine running; revving helps. Written to bus.csv."))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { m.sniffBus() }, enabled = connected && busy == null) { Text(tr("Слушать шину", "Sniff bus")) }
            }
            if (bus.running || bus.status.isNotEmpty()) {
                if (bus.running) LinearProgressIndicator(progress = { bus.progress }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                Muted(bus.status)
            }
            for (b in bus.ids) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(b.idHex, Modifier.weight(0.7f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(if (b.hz > 0) tr("%.0f Гц", "%.0f Hz").format(b.hz) else "—", Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                    Text(b.lastHex, Modifier.weight(3f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionTitle(tr("Диапазон для \$22", "Range for \$22"))
            Row(Modifier.padding(horizontal = 8.dp)) {
                OutlinedButton(onClick = { rangeMenu = true }) { Text(ScanRanges.ranges22[rangeIdx].first) }
                DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                    ScanRanges.ranges22.forEachIndexed { i, (label, _) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { rangeIdx = i; rangeMenu = false })
                    }
                }
            }
            SectionTitle(tr("Модули на HS-CAN (${s.modules.size})", "Modules on HS-CAN (${s.modules.size})"))
            if (s.modules.isEmpty()) Muted(tr("Нажмите «Найти модули». Опрос ~40 адресов, около минуты.", "Tap \"Find modules\". Polls ~40 addresses, about a minute."))
        }
        items(s.modules, key = { it.id }) { mod ->
            ModuleCard(mod, enabled = connected && busy == null,
                onScan1A = { m.scanModule(mod, "1A", 0x00..0xFF) },
                onScan21 = { m.scanModule(mod, "21", 0x00..0xFF) },
                onScan22 = { m.scanModule(mod, "22", ScanRanges.ranges22[rangeIdx].second) })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("Найдено DID: ${s.hits.size}", "DIDs found: ${s.hits.size}"))
                Checkbox(checked = onlyWatched, onCheckedChange = { onlyWatched = it })
                Text(tr("только отмеченные", "ticked only"))
            }
            if (s.hits.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { m.watchAll(s.hits) }) { Text(tr("Отметить все с данными", "Tick all with data")) }
                OutlinedButton(onClick = { m.watchAll(emptyList()) }, modifier = Modifier.padding(start = 8.dp)) { Text(tr("Снять", "Untick")) }
            }
            if (s.hits.isNotEmpty()) Muted(tr("Отмеченные опрашиваются и пишутся в data.csv: на этой вкладке каждый цикл, на других раз в 3 с.",
                "Ticked DIDs are polled and written to data.csv: every cycle on this tab, every 3 s on the others. So they can be decoded later from the recording (utils/scan_decode.py)."))
        }
        items(hits, key = { it.key }) { h ->
            val live = r[h.key]
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = h.key in s.watched, onCheckedChange = { m.toggleWatch(h) })
                Column(Modifier.weight(1f)) {
                    Text(tr("%03X  %s %s  (%d байт)", "%03X  %s %s  (%d bytes)").format(h.req, h.service, h.didHex, h.data.size) + (h.label?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelLarge)
                    Text(live?.text ?: h.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    h.partNumber?.let { Text(tr("№ $it", "P/N $it"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    if (h.looksLikeText) Text(tr("«${h.ascii}»", "\"${h.ascii}\""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    if (live?.value != null) Text(tr("A/AB = ${live.display()} (мин ${live.min?.let { Reading.fmt(it, 0) }}, макс ${live.max?.let { Reading.fmt(it, 0) }})",
                        "A/AB = ${live.display()} (min ${live.min?.let { Reading.fmt(it, 0) }}, max ${live.max?.let { Reading.fmt(it, 0) }})"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(mod: GmModule, enabled: Boolean, onScan1A: () -> Unit, onScan21: () -> Unit, onScan22: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text("${mod.name}  ·  %03X → %03X".format(mod.req, mod.resp), style = MaterialTheme.typography.titleSmall)
            Text(mod.answeredTo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Скан:", "Scan:"), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = onScan1A, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) { Text("\$1A") }
                OutlinedButton(onClick = onScan21, enabled = enabled, modifier = Modifier.padding(start = 6.dp)) { Text("\$21") }
                OutlinedButton(onClick = onScan22, enabled = enabled, modifier = Modifier.padding(start = 6.dp)) { Text("\$22") }
            }
            Text(tr("\$1A и \$21 — по 256 номеров, ~20–40 с; \$21 — данные блока у Toyota (KWP). \$22 — выбранный диапазон.",
                "\$1A and \$21: 256 IDs each, ~20–40 s; \$21 is Toyota block data (KWP). \$22: the selected range."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
