package com.obdscanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarChoice
import com.obdscanner.screen.Guide
import com.obdscanner.screen.GuideView

/** A resource the core names (guide.xml): the app's own, looked up by name. */
@Composable
private fun res(name: String, type: String): Int {
    val ctx = LocalContext.current
    @Suppress("DiscouragedApi")
    return ctx.resources.getIdentifier(name, type, ctx.packageName)
}

@Composable
fun GuideScreen(m: ObdManager, v: VehicleInfo, picked: CarChoice?) {
    val g = Guide.build(v, picked)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item { CarCard(m, v, picked, details = false) }
        item {
            val steps = stringArrayResource(res(g.steps, "array"))
            // Collapsed to the first lines: whoever has read it once doesn't scroll past it every time.
            var open by rememberSaveable { mutableStateOf(false) }
            Card(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { open = !open },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(steps.first(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    val shown = if (open) steps.drop(1) else steps.drop(1).take(GuideView.COLLAPSED)
                    for ((i, s) in shown.withIndex()) {
                        val last = i == steps.size - 2
                        Text(s, style = if (last) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            color = if (last) Warn else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = if (last) 10.dp else 6.dp))
                    }
                    if (steps.size - 1 > GuideView.COLLAPSED) Text(
                        if (open) GuideView.COLLAPSE else GuideView.SHOW_ALL,
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item {
            SectionTitle(stringResource(res(g.obdTitle, "string")))
            if (g.obdPicture != null) {
                g.obdCommon?.let { a -> Muted(stringResource(res("guide_obd_common", "string"), a[0], a[1].toInt(), a[2].toInt())) }
                ObdPlace(g.obdPicture, g.obdPlace)
            }
            Muted(stringResource(res(g.obdNote, "string")))
        }
        item {
            Text(stringResource(res(g.detailsTitle, "string")), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 6.dp, top = 18.dp))
        }
        for (s in g.sections) item { GuideSection(res(s, "array")) }
        g.features?.let { (title, items) ->
            item {
                SectionTitle(stringResource(res("guide_features", "string"), title))
                for (p in items) {
                    Text("• $p", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        for (s in g.tail) item { GuideSection(res(s, "array")) }
    }
}

@Composable
private fun GuideSection(id: Int) {
    val lines = stringArrayResource(id)
    SectionTitle(lines.first())
    for (p in lines.drop(1)) {
        Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
