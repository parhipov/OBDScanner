package com.obdscanner.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.screen.DtcHelp
import com.obdscanner.screen.DtcHelpView
import com.obdscanner.screen.DtcRef

/** What the app knows about one trouble code ([DtcHelp]), on a tap (like the main screen card help). */
@Composable
fun DtcHelpDialog(d: DtcRef, family: String?, onDismiss: () -> Unit) {
    val h = DtcHelp.build(d, family)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(DtcHelpView.CLOSE) } },
        title = { Text(h.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(h.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                h.untranslated?.let { Muted(it) }
                Muted(h.module)
                for ((title, text) in h.sections) Section(title, text)
                h.source?.let { Muted(it) }
            }
        },
    )
}

@Composable
private fun Section(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
