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
import androidx.compose.ui.platform.LocalContext
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

/** The card's help texts: the string-array the core names ([com.obdscanner.screen.CardHelp]). */
@Composable
@ArrayRes
private fun helpArray(r: Reading): Int? {
    val name = com.obdscanner.screen.CardHelp.forReading(r) ?: return null
    val ctx = LocalContext.current
    // The resource names are the core's keys; an unknown one means no help, as before.
    @Suppress("DiscouragedApi")
    return ctx.resources.getIdentifier(name, "array", ctx.packageName).takeIf { it != 0 }
}

@Composable
fun CardHelpDialog(label: String, r: Reading, sample: Boolean = false, onDismiss: () -> Unit) {
    val texts = helpArray(r)?.let { stringArrayResource(it) }
    val (res, args) = com.obdscanner.screen.CardHelp.source(r)
    val source = when (res) {
        "help_src_pid" -> stringResource(R.string.help_src_pid, *args.toTypedArray())
        "help_src_gm" -> stringResource(R.string.help_src_gm, *args.toTypedArray())
        "help_src_ext" -> stringResource(R.string.help_src_ext, *args.toTypedArray())
        "help_src_calc" -> stringResource(R.string.help_src_calc)
        "help_src_adapter" -> stringResource(R.string.help_src_adapter)
        else -> args.first()
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
                    Text(stringResource(R.string.help_range, Reading.fmt(r.min!!, r.decimals), Reading.fmt(r.max!!, r.decimals)),
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
