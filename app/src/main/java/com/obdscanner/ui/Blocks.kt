package com.obdscanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.Tab
import com.obdscanner.obd.Reading
import com.obdscanner.screen.Action
import com.obdscanner.screen.Block
import com.obdscanner.screen.DtcRef
import com.obdscanner.screen.Level
import com.obdscanner.screen.trimLevel

/** The phone's colour for a [Level]. */
@Composable
fun Level.color(): Color = when (this) {
    Level.GOOD -> Good
    Level.WARN -> Warn
    Level.BAD -> Bad
    Level.MUTED -> MaterialTheme.colorScheme.outline
}

@Composable
private fun Level?.colorOrNull(): Color? = this?.color()

/** A screen of [Block]s in a LazyColumn, one item per block; [onDoc] — a row's license ([Block.Row.doc]); [onTab] — a banner's tab. */
fun LazyListScope.blocks(list: List<Block>, busy: String?, onAction: (Action) -> Unit, onDtc: (DtcRef) -> Unit, onDoc: (String) -> Unit = {}) {
    items(list, key = { it.key }) { BlockView(it, busy, onAction, onDtc, onDoc) }
}

@Composable
fun BlockView(b: Block, busy: String?, onAction: (Action) -> Unit, onDtc: (DtcRef) -> Unit, onDoc: (String) -> Unit = {}, onTab: (Tab) -> Unit = {}) {
    when (b) {
        is Block.Title -> SectionTitle(b.text)
        is Block.Note -> Muted(b.text)
        is Block.Banner -> {
            val tab = b.tab
            Hint(b.text, b.level.color(), if (tab != null) { { onTab(tab) } } else null)
        }
        is Block.Row -> {
            val row = @Composable { ValueRow(b.name, b.value, b.unit, b.sub, b.level.colorOrNull()) }
            val dtc = b.dtc
            val doc = b.doc
            when {
                dtc != null -> Box(Modifier.clickable { onDtc(dtc) }) { row() }
                doc != null -> Box(Modifier.clickable { onDoc(doc) }) { row() }
                else -> row()
            }
            if (b.divider) HorizontalDivider()
        }
        is Block.Value -> ReadingRow(b.reading, b.level.colorOrNull())
        is Block.Bank -> BankCard(b)
        is Block.Misfires -> Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                for (c in b.cylinders) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.labelMedium)
                        Text(c.value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = c.level.color())
                        c.avg?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        is Block.Table -> Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(8.dp)) {
                Row { Text("", Modifier.weight(1.6f)); for (h in b.header) Text(h, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium) }
                for (row in b.rows) Row {
                    Text(row.label, Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall)
                    for (c in row.cells) Text(c.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = c.level.colorOrNull() ?: MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        is Block.Buttons -> {
            val note = b.note
            if (note != null) Row {
                // A button with its explanation beside it (Mode 06 on Fuel).
                for (btn in b.buttons) OutlinedButton(onClick = { onAction(btn.action) }, Modifier.padding(8.dp), enabled = busy == null || btn.whileBusy) { Text(btn.label) }
                Muted(note)
            } else Row(Modifier.padding(8.dp)) {
                for ((i, btn) in b.buttons.withIndex()) {
                    val m = if (i > 0) Modifier.padding(start = 8.dp) else Modifier
                    val enabled = busy == null || btn.whileBusy
                    if (btn.primary) Button(onClick = { onAction(btn.action) }, m, enabled = enabled) { Text(btn.label) }
                    else OutlinedButton(onClick = { onAction(btn.action) }, m, enabled = enabled) { Text(btn.label) }
                }
            }
        }
        is Block.Gap -> Gap()
    }
}

@Composable
private fun BankCard(b: Block.Bank) {
    Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(b.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { TrimRow(Block.Bank.SHORT, b.short) }
                Column(Modifier.weight(1f)) { TrimRow(Block.Bank.LONG, b.long) }
                Column(Modifier.weight(1f)) { TrimRow(Block.Bank.TOTAL, b.total) }
            }
            TrimBar(b.total?.value)
            b.longRange?.let { Muted(it) }
        }
    }
}

@Composable
private fun TrimRow(name: String, r: Reading?) = ValueRow(name, r?.display() ?: "—", "%", color = trimLevel(r?.value).colorOrNull())
