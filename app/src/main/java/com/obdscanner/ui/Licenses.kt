package com.obdscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.obdscanner.screen.DtcHelpView
import com.obdscanner.screen.Licenses

/** What the app is made of and under which terms ([Licenses], core/data/licenses): full screen, a row opens its license. */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember {
        if (!Licenses.loaded) Licenses.load(ctx.assets.open("licenses/licenses.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        Licenses.build(web = false)
    }
    var open by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Licenses.TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, DtcHelpView.CLOSE) }
                }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    blocks(list, busy = null, onAction = {}, onDtc = {}, onDoc = { open = it })
                }
            }
        }
    }
    open?.let { id -> LicenseText(id) { open = null } }
}

/** One license: the notice (license, copyright, link, changes) and its full text. */
@Composable
private fun LicenseText(id: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val doc = remember(id) { Licenses.document(id) } ?: return
    val text = remember(id) { doc.text?.let { f -> ctx.assets.open("licenses/$f").bufferedReader(Charsets.UTF_8).use { it.readText() } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(DtcHelpView.CLOSE) } },
        title = { Text(doc.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (l in doc.lines) Text(l, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                text?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
    )
}
