package com.obdscanner.ui

import androidx.annotation.ArrayRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obdscanner.R
import com.obdscanner.car.CarDb
import com.obdscanner.obd.Reading
import com.obdscanner.obd.ecuName

/**
 * Help for the main screen cards. The texts are Android resources (res/values/help_*.xml,
 * one string-array per parameter: what it is / normal values / what a deviation means),
 * so a translation is just a values-en/ copy of those files.
 */
object CardHelp {
    private val bySource: Map<String, Int> = mapOf(
        "01.0C" to R.array.help_rpm,
        "01.0D" to R.array.help_speed,
        "01.05" to R.array.help_coolant,
        "01.04" to R.array.help_load,
        "01.11" to R.array.help_throttle,
        "01.49" to R.array.help_pedal,
        "01.0E" to R.array.help_timing,
        "01.10" to R.array.help_maf,
        "01.0B" to R.array.help_map,
        "01.0F" to R.array.help_iat,
        "01.1F" to R.array.help_runtime,
        "01.5C" to R.array.help_oil_temp,
        "22.1154" to R.array.help_oil_temp,
        "22.1470" to R.array.help_oil_pressure,
        "22.119F" to R.array.help_oil_life,
        "22.1940" to R.array.help_atf_temp,
        "22.199A" to R.array.help_gear,
        "calc.gearRatio" to R.array.help_gear_ratio,
        "22.1991" to R.array.help_tcc_slip,
        "22.1941" to R.array.help_input_shaft,
        "22.1942" to R.array.help_output_shaft,
        "01.2F" to R.array.help_fuel_level,
        "calc.l100" to R.array.help_l100,
        "calc.lph" to R.array.help_lph,
        "calc.trim1" to R.array.help_trim,
        "calc.trim2" to R.array.help_trim,
        "01.42" to R.array.help_ecu_voltage,
        "ATRV" to R.array.help_adapter_voltage,
        "01.46" to R.array.help_ambient,
        "01.33" to R.array.help_baro,
    )

    /** Manufacturer parameters of any make, by their role (tools/cars/SCHEMA.md). */
    private val byRole: Map<String, Int> = mapOf(
        "oil_temp" to R.array.help_oil_temp,
        "oil_pressure" to R.array.help_oil_pressure,
        "oil_life" to R.array.help_oil_life,
        "atf_temp" to R.array.help_atf_temp,
        "gear" to R.array.help_gear,
        "tc_slip" to R.array.help_tcc_slip,
        "input_rpm" to R.array.help_input_shaft,
        "output_rpm" to R.array.help_output_shaft,
    )

    @ArrayRes fun forSource(source: String): Int? = bySource[source]

    @ArrayRes fun help(r: Reading): Int? = bySource[r.source] ?: r.role?.let { byRole[it] }
}

@Composable
fun CardHelpDialog(label: String, r: Reading, sample: Boolean = false, onDismiss: () -> Unit) {
    val texts = CardHelp.help(r)?.let { stringArrayResource(it) }
    val source = when {
        r.source.startsWith("01.") -> stringResource(R.string.help_src_pid, r.source.substring(3, 5), ecuName(r.ecu))
        r.source.matches(Regex("22\\.[0-9A-F]{4}")) -> stringResource(R.string.help_src_gm, r.source.substring(3), CarDb.family("gm")?.modules?.names?.get(r.ecu) ?: "%03X".format(r.ecu))
        r.source.startsWith("22.") || r.source.startsWith("21.") ->
            stringResource(R.string.help_src_ext, r.source.substring(0, 2), r.source.substring(3).substringBefore('.'), ecuName(r.ecu + 8))
        r.source.startsWith("calc.") -> stringResource(R.string.help_src_calc)
        r.source == "ATRV" -> stringResource(R.string.help_src_adapter)
        else -> r.source
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_close)) } },
        title = { Text(label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // A sample: the typical value of an offline card, greyed out and labelled as such.
                if (r.value != null || r.text != null) Text(r.display() + if (r.value != null && r.unit.isNotEmpty()) " ${r.unit}" else "", fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold, color = if (sample) MaterialTheme.colorScheme.outline else Color.Unspecified)
                if (sample) {
                    Text(stringResource(R.string.help_sample), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                } else if (r.value != null && r.min != null && r.max != null) {
                    Text(stringResource(R.string.help_range, Reading.fmt(r.min, r.decimals), Reading.fmt(r.max, r.decimals)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
                Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                if (texts == null) {
                    Text(stringResource(R.string.help_none), modifier = Modifier.padding(top = 12.dp))
                } else {
                    val titles = listOf(R.string.help_what, R.string.help_norm, R.string.help_hint)
                    for ((i, t) in texts.withIndex()) {
                        if (t.isBlank()) continue
                        Text(stringResource(titles.getOrElse(i) { R.string.help_hint }), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                        Text(t, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}
