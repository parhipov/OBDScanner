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
import com.obdscanner.car.CarFamily
import com.obdscanner.car.CarModel
import com.obdscanner.gm.GmKnown
import com.obdscanner.obd.Make
import com.obdscanner.tr
import kotlinx.coroutines.delay

/** Socket location ids of the car database (tools/cars/SCHEMA.md `obd`) → picture from art/obd/make_obd_art.py. */
@DrawableRes
fun obdPicture(loc: String?): Int? = when (loc) {
    "left_door" -> R.drawable.obd_loc_left_door
    "under_column" -> R.drawable.obd_loc_under_column
    "right_of_column" -> R.drawable.obd_loc_right_of_column
    "left_cover" -> R.drawable.obd_loc_left_cover
    "console" -> R.drawable.obd_loc_console
    "passenger" -> R.drawable.obd_loc_passenger
    else -> null
}

fun obdPlace(loc: String?): String? = when (loc) {
    "left_door" -> tr("Под панелью слева от рулевой колонки, ближе к двери / ручке капота.", "Under the dash left of the steering column, towards the door / hood release.")
    "under_column" -> tr("Прямо под рулевой колонкой.", "Right under the steering column.")
    "right_of_column" -> tr("Под панелью между рулевой колонкой и центральной консолью.", "Under the dash between the steering column and the centre console.")
    "left_cover" -> tr("Слева под рулём за крышкой блока предохранителей или накладкой — снимите её.", "Left under the wheel behind the fuse box cover or a trim panel — remove it.")
    "console" -> tr("В центральной консоли: под пепельницей / нишей или за накладкой тоннеля.", "In the centre console: under the ashtray / cubby or behind the tunnel trim.")
    "passenger" -> tr("Со стороны пассажира: в ногах или у бардачка.", "On the passenger side: footwell or glovebox.")
    else -> null
}

fun protocolName(p: Int?): String? = when (p) {
    null -> null
    1 -> "J1850 PWM"
    2 -> "J1850 VPW"
    else -> ObdManager.PROTOCOLS[p]
}

/** Main-screen roles → what the card calls them (for "what is read on this make"). */
fun roleName(role: String): String? = when (role) {
    "oil_temp" -> tr("температура масла", "oil temperature")
    "oil_pressure" -> tr("давление масла", "oil pressure")
    "oil_life" -> tr("ресурс масла", "oil life")
    "oil_level" -> tr("уровень масла", "oil level")
    "atf_temp" -> tr("масло АКПП", "transmission fluid")
    "cvt_temp" -> tr("масло вариатора", "CVT fluid")
    "clutch_temp" -> tr("температура сцепления", "clutch temperature")
    "cvt_wear" -> tr("износ масла вариатора", "CVT fluid wear")
    "gear" -> tr("передача", "gear")
    "tc_slip" -> tr("проскальзывание ГТ", "TC slip")
    "fuel_level_l" -> tr("литры в баке", "fuel in litres")
    "odometer" -> tr("пробег", "odometer")
    "battery_soc" -> tr("заряд АКБ", "battery charge")
    "knock_retard" -> tr("детонация", "knock")
    "boost" -> tr("наддув", "boost")
    "dpf_soot" -> tr("сажа DPF", "DPF soot")
    "service_km", "service_days" -> tr("до ТО", "service due")
    "hv_soc", "hv_soh" -> tr("ВВ батарея", "HV battery")
    else -> null
}

/**
 * The car the app works with: found by the VIN or picked by hand — a model, or only a make (then its
 * family's parameters are probed and the VIN finds the model when it can).
 */
@Composable
fun CarCard(m: ObdManager, v: VehicleInfo, picked: CarChoice?, details: Boolean = true) {
    var dialog by remember { mutableStateOf(false) }
    // Ticked for comparison ("brand:X" or model ids): kept while the screen lives, not only while the dialog is open.
    val compare = remember { mutableStateListOf<String>() }
    if (dialog) CarPickerDialog(picked, compare, onPick = { m.pickCar(it); dialog = false }) { dialog = false }
    val car = v.car ?: picked?.model
    val brand = v.brand ?: picked?.brand
    val family = CarDb.family(car?.family ?: v.make.id.ifEmpty { null } ?: picked?.family)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Машина", "Car"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text(car?.title ?: brand ?: tr("не выбрана", "not picked"), style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        car != null && car == picked?.model -> tr("выбрана вручную", "picked by hand")
                        car != null -> tr("по VIN", "by VIN")
                        picked != null && picked.brand == brand -> tr("выбрана марка; модель — по VIN, или выберите", "make picked; the model by the VIN, or pick it")
                        brand != null -> tr("марка по VIN, модель не определилась — можно выбрать", "make by VIN, model unknown — you can pick it")
                        else -> tr("определится по VIN при подключении, или выберите", "found by the VIN on connect, or pick it")
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                OutlinedButton(onClick = { dialog = true }) { Text(tr("Выбрать", "Pick")) }
            }
            // Collapsed by default: on "Connect" the adapters are below it.
            var more by rememberSaveable { mutableStateOf(false) }
            if (details && family != null) MakeReads(family, if (car == null) brand else null, car)
            if (details && more) {
                if (car != null) CarDetails(car)
                if (family != null) MakeDetails(family, if (car == null) brand else null, car)
            }
            if (details && (car != null || family != null)) Text(
                if (more) tr("Свернуть ▴", "Less ▴") else tr("Подробнее ▾", "More ▾"),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp).clickable { more = !more })
        }
    }
}

@Composable
private fun CarDetails(car: CarModel) {
    val lines = buildList {
        if (car.engines.isNotEmpty()) add(tr("Двигатель", "Engine") to car.engines.joinToString("\n") { it.title })
        protocolName(car.protocol)?.let { add(tr("Протокол", "Protocol") to it + if (car.protocol in 1..2) tr(" — ELM327-клоны его часто не умеют", " — ELM327 clones often can't do it") else "") }
        car.buses?.let { add(tr("Шины", "Buses") to it) }
        car.note?.let { add(tr("Заметка", "Note") to it) }
        add(tr("Данные", "Data") to (if (car.verified) tr("проверено на этой машине", "checked on this car") else tr("по открытым источникам, на машине не проверено", "from public sources, not checked on a car")) +
            if (car.src.isNotEmpty()) tr(" · источников: ${car.src.size}", " · sources: ${car.src.size}") else "")
    }
    for ((k, v) in lines) {
        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What is special about the make: what the app reads on it beyond standard OBD (from the database),
 * how many requests, and — with only a make known — how many models and where the socket usually is.
 */
/**
 * Requests known on the model, else on the make, else the whole family (what the card describes).
 * On GM also the enhanced parameters kept in code (GmKnown, checked on the CTS).
 */
private fun knownOn(family: CarFamily, brand: String?, car: CarModel?) = when {
    car != null -> family.commandsFor(car)
    brand != null -> family.commandsOfBrand(brand)
    else -> family.commands
} + if (family.id == Make.GM.id) GmKnown.commands else emptyList()

@Composable
private fun MakeReads(family: CarFamily, brand: String?, car: CarModel?) {
    val cmds = knownOn(family, brand, car)
    val roles = cmds.flatMap { c -> c.signals.mapNotNull { it.role } }.mapNotNull(::roleName).distinct()
    Text(tr("Что читается сверх OBD", "Read beyond standard OBD"), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    Text(roles.joinToString(", ").ifEmpty { tr("в базе ничего — только стандартный OBD", "nothing in the database — standard OBD only") },
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MakeDetails(family: CarFamily, brand: String?, car: CarModel?) {
    val cmds = knownOn(family, brand, car)
    val signals = cmds.sumOf { it.signals.size }
    val sure = cmds.sumOf { c -> c.signals.count { it.confidence == "OK" } }
    val lines = buildList {
        if (brand != null) {
            val models = family.models.count { it.brand == brand }
            if (models > 0) add(tr("Моделей в базе", "Models in the database") to "$models")
            family.commonObd(brand)?.let { (loc, n, all) ->
                obdPlace(loc)?.let { add(tr("Разъём обычно", "Socket usually") to it.trimEnd('.') + tr(" (у $n из $all моделей)", " ($n of $all models)")) }
            }
        }
        if (signals > 0) add(tr("Параметры производителя", "Manufacturer parameters") to
            tr("$signals в базе ($sure подтверждены); при подключении проверяется, какие отвечают", "$signals in the database ($sure confirmed); checked on connect which ones answer"))
        else add(tr("Параметры производителя", "Manufacturer parameters") to tr("в базе нет — только стандартный OBD", "none in the database — standard OBD only"))
    }
    for ((k, v) in lines) {
        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
    if (family.features.isNotEmpty()) {
        Text(tr("Особенности ${family.title}", "About ${family.title}"), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        for (f in family.features) Text("• $f", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Make → "make only" or a model/generation. "By VIN" clears the hand pick. */
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
    if (comparing) CompareDialog(compare.mapNotNull { CarDb.choice(it) }) { comparing = false }
    fun tick(id: String): (Boolean) -> Unit = { on -> if (on) { if (id !in compare) compare += id } else compare -= id }
    var filter by remember { mutableStateOf("") }
    val loaded by CarDb.loaded.collectAsState()
    val models = remember(loaded) { CarDb.models }
    val brandNames = remember(loaded) { CarDb.brandNames }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(brand ?: tr("Марка", "Make")) },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(tr("Поиск", "Search")) })
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 6.dp)) {
                    val q = filter.trim()
                    if (brand == null && q.isEmpty()) {
                        item { PickRow(tr("Определять по VIN", "Find by VIN"), tr("без ручного выбора", "no hand pick"), current == null) { onPick(null) } }
                        val counts = models.groupingBy { it.brand }.eachCount()
                        items(brandNames) { b ->
                            PickRow(b, modelsText(counts[b] ?: 0) + familyTag(b), current?.brand == b, BRAND + b in compare, tick(BRAND + b)) { brand = b }
                        }
                    } else {
                        val b = brand
                        if (b != null && q.isEmpty()) item {
                            val fam = CarDb.familyOfBrand(b)
                            PickRow(tr("Только марка: $b", "Make only: $b"),
                                tr("модель определится по VIN; проверяются все параметры марки", "the VIN finds the model; all of the make's parameters are checked"),
                                current != null && current.model == null && current.brand == b, BRAND + b in compare, tick(BRAND + b)) { if (fam != null) onPick(CarChoice(b, fam)) }
                        }
                        // Search also finds the family: "GM" → Cadillac, Chevrolet, Opel…
                        val makes = if (brand != null) emptyList() else
                            brandNames.filter { b -> b.contains(q, ignoreCase = true) || GROUPS[CarDb.familyOfBrand(b)]?.contains(q, ignoreCase = true) == true }
                        items(makes, key = { "make:$it" }) { b -> PickRow(b, tr("марка", "make") + familyTag(b), current?.brand == b, BRAND + b in compare, tick(BRAND + b)) { brand = b; filter = "" } }
                        val list = models.filter { (brand == null || it.brand == brand) && (q.isEmpty() || it.title.contains(q, ignoreCase = true)) }
                            .sortedWith(compareBy({ it.brand }, { it.model }, { it.years?.first ?: 0 }))
                        items(list, key = { it.id }) { c ->
                            PickRow(if (brand == null) "${c.brand} ${c.shortTitle}" else c.shortTitle,
                                c.engines.mapNotNull { it.code }.distinct().joinToString(", ") + if (c.verified) tr(" · проверено", " · checked") else "",
                                c == current?.model, c.id in compare, tick(c.id)) { onPick(CarChoice(c.brand, c.family, c)) }
                        }
                        if (list.isEmpty() && makes.isEmpty()) item { Muted(tr("Ничего не нашлось", "Nothing found")) }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (brand != null) TextButton(onClick = { brand = null; filter = "" }) { Text(tr("Назад", "Back")) }
                if (compare.size > 1) {
                    TextButton(onClick = { compare.clear() }) { Text(tr("Сбросить", "Reset")) }
                    TextButton(onClick = { comparing = true }) { Text(tr("Сравнить (${compare.size})", "Compare (${compare.size})")) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
    )
}

/** Group names people search by; families without one (Chinese makes, Lada…) get no tag. */
private val GROUPS = mapOf("gm" to "GM", "vag" to "VAG", "toyota" to "Toyota", "hyundai" to "Hyundai", "nissan" to "Nissan", "renault" to "Renault", "bmw" to "BMW")

/** " · GM" after a make of a group named otherwise (Cadillac → GM, Skoda → VAG, Lexus → Toyota). */
private fun familyTag(brand: String) = GROUPS[CarDb.familyOfBrand(brand)]?.takeIf { !it.equals(brand, ignoreCase = true) }?.let { " · $it" }.orEmpty()

private fun modelsText(n: Int) = if (n == 0) tr("моделей в базе нет — можно выбрать марку", "no models in the database — pick the make")
    else tr("моделей: $n", "models: $n")

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

private const val BRAND = CarChoice.BRAND

/** "Where the OBD port is": the shared picture for the car's socket location and its description. */
@Composable
fun ObdPlace(loc: String?) {
    val pic = obdPicture(loc) ?: return
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Image(painterResource(pic), contentDescription = obdPlace(loc), modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
    obdPlace(loc)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
}
