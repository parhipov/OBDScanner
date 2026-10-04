package com.obdscanner.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.R
import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.screen.CarCard
import com.obdscanner.screen.CarCardView
import com.obdscanner.screen.CarPicker
import kotlinx.coroutines.delay

/** The core's socket pictures ([com.obdscanner.screen.CarText.obdPicture], art/obd/make_obd_art.py) → the drawables. */
@DrawableRes
fun obdPicture(name: String?): Int? = when (name) {
    "obd_loc_left_door" -> R.drawable.obd_loc_left_door
    "obd_loc_under_column" -> R.drawable.obd_loc_under_column
    "obd_loc_right_of_column" -> R.drawable.obd_loc_right_of_column
    "obd_loc_left_cover" -> R.drawable.obd_loc_left_cover
    "obd_loc_console" -> R.drawable.obd_loc_console
    "obd_loc_passenger" -> R.drawable.obd_loc_passenger
    else -> null
}

/** The car the app works with ([CarCard] in the core): found by the VIN or picked by hand. */
@Composable
fun CarCard(m: ObdManager, v: VehicleInfo, picked: CarChoice?, details: Boolean = true) {
    var dialog by remember { mutableStateOf(false) }
    // Ticked for comparison ("brand:X" or model ids): kept while the screen lives, not only while the dialog is open.
    val compare = remember { mutableStateListOf<String>() }
    if (dialog) CarPickerDialog(picked, compare, onPick = { m.pickCar(it); dialog = false }) { dialog = false }
    val c = CarCard.build(v, picked)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    Text(c.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                OutlinedButton(onClick = { dialog = true }) { Text(c.pick) }
            }
            // Collapsed by default: on "Connect" the adapters are below it.
            var more by rememberSaveable { mutableStateOf(false) }
            if (details) c.reads?.let { (k, v) -> Line(k, v) }
            if (details && more) {
                for ((k, v) in c.details) Line(k, v)
                c.features?.let { (title, items) ->
                    Text(title, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    for (f in items) Text("• $f", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (details && c.hasMore) Text(
                if (more) CarCardView.LESS else CarCardView.MORE,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp).clickable { more = !more })
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    Text(v, style = MaterialTheme.typography.bodyMedium)
}

/** Make → "make only" or a model/generation ([CarPicker] in the core). "By VIN" clears the hand pick. */
@Composable
fun CarPickerDialog(current: CarChoice?, compare: MutableList<String>, onPick: (CarChoice?) -> Unit, onDismiss: () -> Unit) {
    var brand by remember { mutableStateOf<String?>(null) }
    var comparing by remember { mutableStateOf(false) }
    // The dialog focuses the search field by itself and the keyboard covers the list and the buttons:
    // take the focus back, the keyboard comes when the field is tapped.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(100)
        focus.clearFocus()
        keyboard?.hide()
    }
    if (comparing) CompareDialog(compare.toList()) { comparing = false }
    fun tick(id: String): (Boolean) -> Unit = { on -> if (on) { if (id !in compare) compare += id } else compare -= id }
    var filter by remember { mutableStateOf("") }
    val loaded by CarDb.loaded.collectAsState()
    val rows = remember(loaded, brand, filter, current) { CarPicker.rows(brand, filter, current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(brand ?: CarPicker.TITLE) },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(CarPicker.SEARCH) })
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 6.dp)) {
                    items(rows.rows, key = { r -> r.pick ?: ("open:" + r.open) }) { r ->
                        PickRow(r.title, r.sub, r.selected, r.compare?.let { it in compare }, r.compare?.let(::tick)) {
                            when {
                                r.pick != null -> onPick(if (r.pick == "") null else CarDb.choice(r.pick))
                                r.open != null -> { brand = r.open; filter = "" }
                            }
                        }
                    }
                    if (rows.nothing) item { Muted(CarPicker.NOTHING) }
                }
            }
        },
        confirmButton = {
            Row {
                if (brand != null) TextButton(onClick = { brand = null; filter = "" }) { Text(CarPicker.BACK) }
                if (compare.size > 1) {
                    TextButton(onClick = { compare.clear() }) { Text(CarPicker.RESET) }
                    TextButton(onClick = { comparing = true }) { Text(CarPicker.compare(compare.size)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(CarPicker.CLOSE) } },
    )
}

@Composable
private fun PickRow(title: String, sub: String, selected: Boolean, checked: Boolean? = null, onCheck: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        // "Add to comparison".
        if (checked != null && onCheck != null) Checkbox(checked, onCheck)
    }
    HorizontalDivider()
}

/** "Where the OBD port is": the shared picture for the car's socket location and its description. */
@Composable
fun ObdPlace(picture: String?, place: String?) {
    val pic = obdPicture(picture) ?: return
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Image(painterResource(pic), contentDescription = place, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
    place?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
}
