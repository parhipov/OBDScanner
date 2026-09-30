package com.obdscanner.ui

import androidx.annotation.ArrayRes
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.R
import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarDb
import com.obdscanner.car.CarChoice
import com.obdscanner.obd.Make
import com.obdscanner.tr

/** Lines of the short instruction shown before it is expanded. */
private const val STEPS_COLLAPSED = 3

/** The make's own section of the guide (res/values/guide.xml), for the families that have one. */
private val SECTIONS = mapOf(
    Make.GM to R.array.guide_gm,
    Make.VAG to R.array.guide_vag,
    Make.TOYOTA to R.array.guide_toyota,
    Make.LADA to R.array.guide_lada,
    Make.HYUNDAI to R.array.guide_hyundai,
)

@Composable
fun GuideScreen(m: ObdManager, v: VehicleInfo, picked: CarChoice?) {
    val car = v.car ?: picked?.model
    val brand = v.brand ?: picked?.brand
    val make = if (v.make != Make.OTHER) v.make else Make.of(car?.family ?: picked?.family)
    val family = CarDb.family(make.id)
    // Without a model: the make's most common socket location.
    val common = if (car?.obd == null && brand != null) family?.commonObd(brand) else null
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item { CarCard(m, v, picked, details = false) }
        item {
            val steps = stringArrayResource(R.array.guide_steps)
            // Collapsed to the first lines: whoever has read it once doesn't scroll past it every time.
            var open by rememberSaveable { mutableStateOf(false) }
            Card(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { open = !open },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(steps.first(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    val shown = if (open) steps.drop(1) else steps.drop(1).take(STEPS_COLLAPSED)
                    for ((i, s) in shown.withIndex()) {
                        val last = i == steps.size - 2
                        Text(s, style = if (last) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            color = if (last) Warn else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = if (last) 10.dp else 6.dp))
                    }
                    if (steps.size - 1 > STEPS_COLLAPSED) Text(
                        if (open) tr("Свернуть ▴", "Collapse ▴") else tr("Показать всё ▾", "Show all ▾"),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.guide_obd_title))
            val loc = car?.obd ?: common?.first
            if (obdPicture(loc) != null) {
                if (car?.obd == null && common != null) Muted(stringResource(R.string.guide_obd_common, brand!!, common.second, common.third))
                ObdPlace(loc)
                Muted(stringResource(R.string.guide_obd_note))
            } else {
                Muted(stringResource(if (car == null && brand == null) R.string.guide_obd_pick else R.string.guide_obd_unknown))
            }
        }
        item {
            Text(stringResource(R.string.guide_details), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 6.dp, top = 18.dp))
        }
        item { GuideSection(R.array.guide_more_steps) }
        SECTIONS[make]?.let { id -> item { GuideSection(id) } }
        if (family != null && family.features.isNotEmpty()) item {
            SectionTitle(stringResource(R.string.guide_features, family.title))
            for (p in family.features) {
                Text("• $p", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        item { GuideSection(R.array.guide_safety) }
        item { GuideSection(R.array.guide_files) }
    }
}

@Composable
private fun GuideSection(@ArrayRes id: Int) {
    val lines = stringArrayResource(id)
    SectionTitle(lines.first())
    for (p in lines.drop(1)) {
        Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
