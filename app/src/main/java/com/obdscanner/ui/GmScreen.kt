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
import com.obdscanner.BusState
import com.obdscanner.ObdManager
import com.obdscanner.ScanState
import com.obdscanner.gm.ScanRanges
import com.obdscanner.obd.Reading
import com.obdscanner.screen.GmScreen
import com.obdscanner.screen.GmView

/** GM scan ([GmScreen] in the core): modules, their scans, the found IDs and the bus. */
@Composable
fun GmScreen(m: ObdManager, s: ScanState, bus: BusState, r: Map<String, Reading>, busy: String?, connected: Boolean) {
    var rangeIdx by remember { mutableIntStateOf(0) }
    var rangeMenu by remember { mutableStateOf(false) }
    var onlyWatched by remember { mutableStateOf(false) }
    val g = GmScreen.build(s, bus, r, onlyWatched)
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
                OutlinedButton(onClick = { rangeMenu = true }) { Text(g.ranges[rangeIdx]) }
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
            ModuleCard(mod, g, enabled = connected && busy == null,
                onScan1A = { module(mod.id)?.let { m.scanModule(it, "1A", 0x00..0xFF) } },
                onScan21 = { module(mod.id)?.let { m.scanModule(it, "21", 0x00..0xFF) } },
                onScan22 = { module(mod.id)?.let { m.scanModule(it, "22", ScanRanges.ranges22[rangeIdx].second) } })
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = h.watched, onCheckedChange = { hit(h.key)?.let(m::toggleWatch) })
                Column(Modifier.weight(1f)) {
                    Text(h.title, style = MaterialTheme.typography.labelLarge)
                    Text(h.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    h.partNumber?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    h.ascii?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    h.live?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
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

@Composable
private fun ModuleCard(mod: GmView.Module, g: GmView, enabled: Boolean, onScan1A: () -> Unit, onScan21: () -> Unit, onScan22: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text(mod.title, style = MaterialTheme.typography.titleSmall)
            Text(mod.answeredTo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.scanLabel, style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = onScan1A, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) { Text("\$1A") }
                OutlinedButton(onClick = onScan21, enabled = enabled, modifier = Modifier.padding(start = 6.dp)) { Text("\$21") }
                OutlinedButton(onClick = onScan22, enabled = enabled, modifier = Modifier.padding(start = 6.dp)) { Text("\$22") }
            }
            Text(g.scanNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
