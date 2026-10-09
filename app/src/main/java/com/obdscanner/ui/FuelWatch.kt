package com.obdscanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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

/** The «Как бензин?» checkbox under «Поддержать проект» on the first screen; «?» opens what it does. */
@Composable
fun FuelWatchLine(watch: Boolean, onWatch: (Boolean) -> Unit) {
    var help by remember { mutableStateOf(false) }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.help_close)) } },
        title = { Text(MainScreen.WATCH_LABEL) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(MainScreen.WATCH_HELP) } },
    )
    Row(Modifier.fillMaxWidth().clickable { onWatch(!watch) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = watch, onCheckedChange = onWatch)
        Text(MainScreen.WATCH_LABEL, style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { help = true }) {
            Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                Text("?", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
