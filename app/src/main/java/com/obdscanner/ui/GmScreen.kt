package com.obdscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.TextButton
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
import com.obdscanner.BusState
import com.obdscanner.ObdManager
import com.obdscanner.ScanState
import com.obdscanner.obd.Reading
import com.obdscanner.screen.GmScreen
import com.obdscanner.screen.GmView

/** «Скан блоков» ([GmScreen] in the core): blocks, their scans, the found IDs and what their bytes look like, the bus. */
@Composable
fun GmScreen(m: ObdManager, s: ScanState, bus: BusState, r: Map<String, Reading>, busy: String?, connected: Boolean) {
    var rangeIdx by remember { mutableIntStateOf(0) }
    var rangeMenu by remember { mutableStateOf(false) }
    var onlyWatched by remember { mutableStateOf(false) }
    val ctx = m.scanContext()
    val range = rangeIdx.coerceIn(0, ctx.ranges.lastIndex)
    val g = GmScreen.build(s, bus, r, onlyWatched, ctx)
    fun module(id: String) = s.modules.firstOrNull { it.id == id }
    fun hit(key: String) = s.hits.firstOrNull { it.key == key }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item {
            for (h in g.hints) Hint(h, Warn)
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { m.probeModules() }, enabled = connected && busy == null) { Text(g.find) }
                if (busy != null) OutlinedButton(onClick = { m.stopOp() }, Modifier.padding(start = 8.dp)) { Text(g.stop) }
            }
            g.scan?.let { Progress(it) }
            g.paramsTitle?.let { SectionTitle(it) }
        }
        items(g.params, key = { "p:" + it.key }) { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium)
                    Text(p.value, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = { m.removeParam(p.key) }) { Text(p.remove) }
            }
        }
        item {
            SectionTitle(g.busTitle)
            Muted(g.busNote)
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { m.sniffBus() }, enabled = connected && busy == null) { Text(g.busButton) }
            }
            g.bus?.let { Progress(it) }
            for (b in g.busIds) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(b.id, Modifier.weight(0.7f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(b.hz, Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                    Text(b.last, Modifier.weight(3f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionTitle(g.rangeTitle)
            Row(Modifier.padding(horizontal = 8.dp)) {
                OutlinedButton(onClick = { rangeMenu = true }) { Text(g.ranges[range]) }
                DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                    g.ranges.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { rangeIdx = i; rangeMenu = false })
                    }
                }
            }
            SectionTitle(g.modulesTitle)
            g.modulesEmpty?.let { Muted(it) }
        }
        items(g.modules, key = { it.id }) { mod ->
            ModuleCard(mod, g, enabled = connected && busy == null) { service ->
                module(mod.id)?.let { m.scanModule(it, service, if (service == "22") ctx.ranges[range].second else 0x00..0xFF) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(g.hitsTitle)
                Checkbox(checked = onlyWatched, onCheckedChange = { onlyWatched = it })
                Text(g.onlyWatched)
            }
            if (s.hits.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { m.watchAll(s.hits) }) { Text(g.tickAll) }
                OutlinedButton(onClick = { m.watchAll(emptyList()) }, modifier = Modifier.padding(start = 8.dp)) { Text(g.untick) }
            }
            g.hitsNote?.let { Muted(it) }
        }
        items(g.hits, key = { it.key }) { h ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                Checkbox(checked = h.watched, onCheckedChange = { hit(h.key)?.let(m::toggleWatch) })
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(h.title, style = MaterialTheme.typography.labelLarge)
                    Text(h.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    h.partNumber?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    h.ascii?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    h.live?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                    h.decodeNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp)) }
                    for (f in h.fields) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = if (f.matched) Good else MaterialTheme.colorScheme.onSurfaceVariant)
                        f.save?.let { TextButton(onClick = { m.saveParam(h.key, f.id) }) { Text(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(p: GmView.Progress) {
    if (p.running) LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(8.dp))
    Muted(p.status)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(mod: GmView.Module, g: GmView, enabled: Boolean, onScan: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text(mod.title, style = MaterialTheme.typography.titleSmall)
            Text(mod.answeredTo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                Text(g.scanLabel, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                for (sv in mod.services) OutlinedButton(onClick = { onScan(sv.id) }, enabled = enabled) { Text(sv.label) }
            }
            Text(g.scanNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
