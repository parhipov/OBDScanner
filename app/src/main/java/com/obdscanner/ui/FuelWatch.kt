package com.obdscanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obdscanner.R
import com.obdscanner.screen.MainScreen

/**
 * The trip's mode under «Поддержать проект» on the first screen: «Топливо» («Как бензин?») or «Безопасность»
 * («Контроль безопасности движения») or neither — a tap on the chosen one turns it off. Kept in the settings; each
 * «?» opens what the mode does.
 */
@Composable
fun ModeLine(fuel: Boolean, safety: Boolean, onFuel: (Boolean) -> Unit, onSafety: (Boolean) -> Unit) {
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    help?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { help = null },
            confirmButton = { TextButton(onClick = { help = null }) { Text(stringResource(R.string.help_close)) } },
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) } },
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(MainScreen.MODE_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
        ModeCard(MainScreen.FUEL_MODE, fuel, { onFuel(!fuel) }) { help = MainScreen.WATCH_LABEL to MainScreen.WATCH_HELP }
        ModeCard(MainScreen.SAFETY_MODE, safety, { onSafety(!safety) }) { help = MainScreen.SAFETY_LABEL to MainScreen.SAFETY_HELP }
    }
}

/** A mode: on — filled and framed in the theme's colour, as a picked source on this screen ([SourceCard]); «?» — what it does. */
@Composable
private fun RowScope.ModeCard(title: String, on: Boolean, onClick: () -> Unit, onHelp: () -> Unit) {
    Card(
        Modifier.weight(1f).padding(4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            HelpMark(onHelp)
        }
    }
}

@Composable
private fun HelpMark(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelLarge)
        }
    }
}
