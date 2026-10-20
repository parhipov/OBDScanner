package com.obdscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.obdscanner.obd.Reading
import com.obdscanner.screen.Limits

/**
 * «Пороги» of one value for this car: four fields, amber below / above, red below / above. A field's placeholder is
 * the app's built-in rule for that side ([builtIn]), the owner's number replaces it; an empty field keeps the built-in
 * one. «Сбросить» forgets the owner's numbers for the value. A comma works as the decimal point.
 */
@Composable
fun LimitsDialog(label: String, r: Reading, builtIn: Limits?, current: Limits?, onSave: (Limits?) -> Unit, onDismiss: () -> Unit) {
    fun text(v: Double?) = v?.let { Reading.fmt(it, r.decimals) } ?: ""
    var warnBelow by rememberSaveable { mutableStateOf(text(current?.warnBelow)) }
    var warnAbove by rememberSaveable { mutableStateOf(text(current?.warnAbove)) }
    var badBelow by rememberSaveable { mutableStateOf(text(current?.badBelow)) }
    var badAbove by rememberSaveable { mutableStateOf(text(current?.badAbove)) }
    fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    val unit = r.unit.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Limits.TITLE}: $label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(Limits.NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp))
                Side(Limits.WARN_BELOW + unit, warnBelow, text(builtIn?.warnBelow)) { warnBelow = it }
                Side(Limits.WARN_ABOVE + unit, warnAbove, text(builtIn?.warnAbove)) { warnAbove = it }
                Side(Limits.BAD_BELOW + unit, badBelow, text(builtIn?.badBelow)) { badBelow = it }
                Side(Limits.BAD_ABOVE + unit, badAbove, text(builtIn?.badAbove)) { badAbove = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = Limits(num(warnBelow), num(warnAbove), num(badBelow), num(badAbove))
                onDismiss()
                onSave(l.takeIf { !it.isEmpty })
            }) { Text(Limits.SAVE) }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onDismiss(); onSave(null) }) { Text(Limits.RESET) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text(Limits.CANCEL) }
            }
        },
    )
}

@Composable
private fun Side(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else { { Text(placeholder) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
