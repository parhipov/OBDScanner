package com.obdscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.car.ExtCommand
import com.obdscanner.gm.GmKnown
import com.obdscanner.obd.Make
import com.obdscanner.tr

/**
 * Diagnostics of a make or a model, for comparing: how the app talks to it (protocol, buses, socket,
 * module steps, services and addresses) and what it reads beyond standard OBD. No years or engines —
 * this is about what the scanner can do, not a spec sheet.
 */
private fun diagnostics(c: CarChoice): Map<String, String> {
    val family = CarDb.family(c.family)
    val car = c.model
    val models = family?.models.orEmpty().filter { it.brand == c.brand }
    val cmds: List<ExtCommand> = when {
        family == null -> emptyList()
        car != null -> family.commandsFor(car)
        else -> family.commandsOfBrand(c.brand)
    } + if (c.family == Make.GM.id) GmKnown.commands else emptyList()
    val no = "—"
    val out = linkedMapOf<String, String>()
    out[tr("Семейство", "Family")] = family?.title ?: no
    out[tr("Протокол", "Protocol")] = if (car != null) protocolName(car.protocol) ?: no
    else models.mapNotNull { protocolName(it.protocol) }.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }.joinToString(", ") { "${it.key} (${it.value})" }.ifEmpty { no }
    out[tr("Шины", "Buses")] = (car?.buses ?: models.mapNotNull { it.buses }.distinct().singleOrNull()) ?: no
    out[tr("Разъём", "Socket")] = (car?.obd ?: family?.commonObd(c.brand)?.first)?.let { obdPlace(it)?.trimEnd('.') } ?: no
    out[tr("Блоки и их ошибки", "Modules and their codes")] = when (Make.of(c.family)) {
        Make.GM -> tr("поиск модулей HS-CAN, \$A9 — ошибки всех блоков", "HS-CAN module search, \$A9 — codes of all modules")
        Make.VAG -> tr("поиск блоков VAG (7E0–7E7, 700–775), UDS \$19 / KWP \$18", "VAG module search (7E0–7E7, 700–775), UDS \$19 / KWP \$18")
        else -> tr("7E0–7E7 и частые адреса, UDS \$19 / KWP \$18", "7E0–7E7 and common addresses, UDS \$19 / KWP \$18")
    }
    out[tr("Сервисы производителя", "Manufacturer services")] = cmds.map { "\$" + it.service }.distinct().sorted().joinToString(" ").ifEmpty { no }
    out[tr("Адреса запросов", "Request ids")] = cmds.map { "%03X".format(it.req) }.distinct().sorted().joinToString(" ").ifEmpty { no }
    val signals = cmds.sumOf { it.signals.size }
    val sure = cmds.sumOf { x -> x.signals.count { it.confidence == "OK" } }
    out[tr("Параметров производителя", "Manufacturer parameters")] = if (signals == 0) no else tr("$signals (подтверждено $sure)", "$signals ($sure confirmed)")
    // One row per thing read beyond OBD: which of the compared ones have it.
    val roles = cmds.flatMap { x -> x.signals.mapNotNull { it.role } }.mapNotNull(::roleName).toSet()
    for (r in ALL_ROLES.mapNotNull(::roleName).distinct()) out[r.replaceFirstChar { it.uppercase() }] = if (r in roles) "✓" else no
    return out
}

private val ALL_ROLES = listOf("oil_temp", "oil_pressure", "oil_life", "oil_level", "atf_temp", "cvt_temp", "clutch_temp", "cvt_wear",
    "gear", "tc_slip", "fuel_level_l", "odometer", "battery_soc", "knock_retard", "boost", "dpf_soot", "service_km", "hv_soc")

fun compareTitle(c: CarChoice) = c.model?.let { "${it.brand} ${it.shortTitle}" } ?: c.brand

/**
 * Only what differs, parameter by parameter, the same values grouped: "Protocol: CAN 11/500 — CTS, RAV4;
 * K-line — Polo". Reads top to bottom for any number of cars (a table ran out of width at 4–5 on a phone).
 */
@Composable
fun CompareDialog(items: List<CarChoice>, onDismiss: () -> Unit) {
    val cols = items.map { diagnostics(it) }
    val names = items.map(::compareTitle)
    val rows = cols.first().keys.filter { k -> cols.map { it[k] }.distinct().size > 1 }
    val same = cols.first().size - rows.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Сравнение", "Comparison")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(names.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (rows.isEmpty()) Muted(tr("Отличий нет", "No differences"))
                for (r in rows) {
                    Text(r, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    val groups = cols.indices.groupBy { cols[it][r].orEmpty() }
                    for ((value, who) in groups) {
                        val shown = when (value) { "✓" -> tr("есть", "yes"); "—" -> tr("нет", "no"); else -> value }
                        Text(shown, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        Text(who.joinToString(", ") { names[it] }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
                if (same > 0) Muted(tr("Совпадает ещё $same", "$same more are the same"))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
    )
}
