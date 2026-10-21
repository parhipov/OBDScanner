package com.obdscanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.obdscanner.R
import com.obdscanner.TripMode
import com.obdscanner.screen.MainScreen

/**
 * Trip modes as cards: on the first screen under «Поддержать проект» ([TripMode.shown]: the main ones, and a test one
 * while it is on, after «Режим»), and the test ones on «Машина» ([com.obdscanner.screen.Block.Modes], no [label]).
 * One at a time or none — a tap on the chosen one turns it off. Kept in the settings; each «?» opens what the mode
 * does, a test mode's [where] it is turned on too. The cards are as wide as their names and go on to the next line
 * when the row is full: an equal share broke «Безопасность» in the middle of the word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeLine(modes: List<TripMode>, mode: TripMode?, onMode: (TripMode, Boolean) -> Unit, label: String? = MainScreen.MODE_LABEL, where: Boolean = true) {
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    help?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { help = null },
            confirmButton = { TextButton(onClick = { help = null }) { Text(stringResource(R.string.help_close)) } },
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) } },
        )
    }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        label?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp, end = 4.dp))
        }
        for (m in modes) ModeCard(m.title, mode == m, { onMode(m, mode != m) }) {
            help = m.label to if (m.test && where) m.help + "\n\n" + MainScreen.TEST_MODE_NOTE else m.help
        }
    }
}

/** A mode: on — filled and framed in the theme's colour, as a picked source on this screen ([SourceCard]); «?» — what it does. */
@Composable
private fun ModeCard(title: String, on: Boolean, onClick: () -> Unit, onHelp: () -> Unit) {
    Card(
        Modifier.padding(4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
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
