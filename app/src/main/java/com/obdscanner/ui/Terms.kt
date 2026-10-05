package com.obdscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.obdscanner.screen.DtcHelpView
import com.obdscanner.screen.Terms

/** The first screen until the terms ([Terms]) are accepted: the button on top, the full text under it, the button again at its end. */
@Composable
fun TermsScreen(onAccept: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("OBD Scanner", style = MaterialTheme.typography.titleMedium)
            Text(Terms.TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Text(Terms.LEAD, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAccept, modifier = Modifier.padding(top = 12.dp)) { Text(Terms.ACCEPT) }
            Text(Terms.DECLINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { TermsText() }
            }
            Button(onClick = onAccept, modifier = Modifier.padding(top = 12.dp)) { Text(Terms.ACCEPT) }
        }
    }
}

/** The same text from Info → About, once accepted. */
@Composable
fun TermsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Terms.TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, DtcHelpView.CLOSE) }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) { TermsText() }
            }
        }
    }
}

@Composable
private fun TermsText() {
    Terms.SECTIONS.forEachIndexed { i, (title, text) ->
        Text("${i + 1}. $title", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    Text(Terms.QUESTIONS + SESSION_EMAIL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 16.dp))
}
