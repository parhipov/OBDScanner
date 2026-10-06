package com.obdscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obdscanner.Tab
import com.obdscanner.VehicleInfo
import com.obdscanner.obd.Reading
import com.obdscanner.screen.MainScreen
import com.obdscanner.screen.MainSection

@Composable
fun MainScreen(r: Map<String, Reading>, v: VehicleInfo, onTab: (Tab) -> Unit) {
    // Card help: (label, reading) — the dialog shows the live value when there is one.
    var help by remember { mutableStateOf<Pair<String, Reading>?>(null) }
    help?.let { (label, h) -> CardHelpDialog(label, r[h.key] ?: h, sample = r[h.key] == null) { help = null } }
    val view = MainScreen.build(r, v)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(165.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
    ) {
        items(view.top, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { BlockView(it, null, {}, {}, onTab = onTab) }
        for (s in view.sections) {
            item(key = "h:${s.title}", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s) }
            items(s.tiles, key = { it.reading.key }) { t ->
                val container = Color(s.tile)
                if (t.sample) ValueTile(t.label, t.reading, color = MaterialTheme.colorScheme.outline, container = container,
                    note = t.note, onClick = { help = t.label to t.reading })
                else ValueTile(t.label, t.reading, color = t.level?.color(), container = container, onClick = { help = t.label to t.reading })
            }
        }
    }
}

@Composable
private fun SectionHeader(s: MainSection) {
    Row(Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).background(Color(s.accent), RoundedCornerShape(2.dp)))
        Text(s.title, style = MaterialTheme.typography.titleMedium, color = Color(s.accent), modifier = Modifier.padding(start = 8.dp))
    }
}
