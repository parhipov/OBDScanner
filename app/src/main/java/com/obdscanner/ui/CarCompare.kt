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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.screen.CarCompare

/**
 * Only what differs, parameter by parameter, the same values grouped ([CarCompare] in the core). Reads top to
 * bottom for any number of cars (a table ran out of width at 4–5 on a phone).
 */
@Composable
fun CompareDialog(ids: List<String>, onDismiss: () -> Unit) {
    val c = remember(ids) { CarCompare.build(ids) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(c.names, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                c.empty?.let { Muted(it) }
                for ((r, groups) in c.rows) {
                    Text(r, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    for ((value, who) in groups) {
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        Text(who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
                c.same?.let { Muted(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(c.close) } },
    )
}
