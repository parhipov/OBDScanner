package com.obdscanner.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.R
import com.obdscanner.ReportBridge
import com.obdscanner.session.SessionExport
import com.obdscanner.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SessionsScreen(m: ObdManager) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val export by m.export.state.collectAsStateWithLifecycle()
    val list = remember(refresh, export) { m.sessions.list() }
    val current = m.session?.takeIf { !it.closed }?.dir
    // Ticked sessions (by folder name): while any is ticked, Send and Delete act on all of them.
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val chosen = list.filter { it.name in picked }
    // Sessions waiting for «Удалить?»: one by its own button or the ticked ones — always asked.
    var asked by remember { mutableStateOf<List<File>?>(null) }
    BackHandler(enabled = chosen.isNotEmpty()) { picked = emptySet() }
    val report = rememberReportRun(m)

    fun send(dirs: List<File>) {
        if (current in dirs) m.session?.flush()
        scope.launch { shareFiles(ctx, withContext(Dispatchers.IO) { m.sessions.zip(dirs) }) }
    }

    Column(Modifier.fillMaxSize()) {
        if (chosen.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picked = emptySet() }) { Icon(Icons.Default.Close, tr("Снять выбор", "Clear selection")) }
            Text(tr("Выбрано: %d", "Selected: %d").format(chosen.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (chosen.size < list.size) TextButton(onClick = { picked = list.map { it.name }.toSet() }) { Text(tr("Все", "All")) }
            IconButton(onClick = { send(chosen) }) { Icon(Icons.Default.Share, tr("Отправить", "Send")) }
            IconButton(onClick = {
                if (chosen.all { it == current }) toast(ctx, tr("Сессия ещё пишется", "Session is still recording")) else asked = chosen
            }) { Icon(Icons.Default.Delete, tr("Удалить", "Delete")) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            item { ExportCard(m.export, export) }
            item { Muted(tr("Каждое подключение — отдельная сессия.", "Each connection is a separate session.")) }
            item { Muted(tr("Сессию с ошибкой подключения или странными значениями пришлите на $SESSION_EMAIL — почтовое приложение подставит адрес само. В письме укажите машину и что пошло не так.",
                "Send a session with a connection error or odd values to $SESSION_EMAIL — a mail app fills the address in. Name the car and what went wrong in the email.")) }
            items(list, key = { it.name }) { dir ->
                val on = dir.name in picked
                val toggle = { picked = if (on) picked - dir.name else picked + dir.name }
                Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(
                    containerColor = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, onCheckedChange = { toggle() })
                        Column(Modifier.weight(1f)) {
                            Text(dir.name + if (dir == current) tr("  · запись", "  · recording") else "", style = MaterialTheme.typography.titleSmall)
                            Text(tr("%.1f КБ", "%.1f KB").format(m.sessions.size(dir) / 1024.0) +
                                if (export.folder != null && m.export.isExported(dir)) tr(" · в папке ✓", " · in the folder ✓") else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        // One session's own buttons: hidden while picking, so the bar on top is the only Send / Delete.
                        if (chosen.isEmpty()) {
                            if (ReportBridge.available) IconButton(onClick = { report.start(dir) }, enabled = report.making == null) {
                                Icon(painterResource(R.drawable.ic_report), tr("Отчёт", "Report"))
                            }
                            IconButton(onClick = { send(listOf(dir)) }) { Icon(Icons.Default.Share, tr("Отправить", "Send")) }
                            IconButton(onClick = {
                                if (dir == current) toast(ctx, tr("Сессия ещё пишется", "Session is still recording")) else asked = listOf(dir)
                            }) { Icon(Icons.Default.Delete, tr("Удалить", "Delete")) }
                        }
                    }
                }
            }
            if (list.isEmpty()) item { Muted(tr("Сессий пока нет.", "No sessions yet.")) }
        }
    }

    ReportDialogs(report)

    asked?.let { dirs ->
        val gone = dirs.filter { it != current }
        val one = dirs.size == 1
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(if (one) tr("Удалить сессию?", "Delete the session?") else tr("Удалить выбранные сессии?", "Delete the selected sessions?")) },
            text = {
                Text(if (one) tr("%s. Восстановить её будет нельзя.", "%s. It can't be restored.").format(dirs[0].name)
                    else tr("Сессий: %d. Восстановить их будет нельзя.", "Sessions: %d. They can't be restored.").format(gone.size) +
                        if (current in dirs) tr(" Сессия, которая сейчас пишется, останется.", " The session being recorded stays.") else "")
            },
            confirmButton = {
                TextButton(onClick = { asked = null; gone.forEach(m.sessions::delete); picked = emptySet(); refresh++ }) { Text(tr("Удалить", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { asked = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/**
 * On top of «Сессии»: the folder a session's zip goes to by itself — off, on, or gone.
 * A pick with sessions already recorded asks once whether they go there too.
 */
@Composable
private fun ExportCard(export: SessionExport, state: SessionExport.State) {
    // The picker's folder waiting for «и уже записанные?», with how many those are.
    var asked by remember { mutableStateOf<Pair<Uri, Int>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val old = export.notIn(tree)
        if (old == 0) export.setFolder(tree, all = true) else asked = tree to old
    }
    val problem = state.problem
    Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(
        containerColor = if (problem != null) Bad.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), null, tint = if (problem != null) Bad else MaterialTheme.colorScheme.outline)
                Text(state.folder?.let { tr("Архивы → %s", "Zips → %s").format(it) } ?: tr("Класть архивы в папку", "Put zips into a folder"),
                    Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                if (state.folder == null) TextButton(onClick = { pick.launch(null) }) { Text(tr("Выбрать", "Choose")) }
            }
            when {
                problem != null -> {
                    Text(problem, Modifier.padding(start = 36.dp, end = 8.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pick.launch(null) }) { Text(tr("Выбрать заново", "Choose again")) }
                        TextButton(onClick = { export.off() }) { Text(tr("Выкл.", "Off")) }
                    }
                }
                state.folder != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { pick.launch(null) }) { Text(tr("Сменить", "Change")) }
                    TextButton(onClick = { export.off() }) { Text(tr("Выкл.", "Off")) }
                }
                else -> Text(tr("После каждой сессии ZIP сам ляжет в выбранную папку.", "After each session its zip goes into the chosen folder by itself."),
                    Modifier.padding(start = 36.dp, end = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    asked?.let { (tree, old) ->
        // Dismissed = only new: the folder itself was chosen in the picker.
        val onlyNew = { asked = null; export.setFolder(tree, all = false) }
        AlertDialog(
            onDismissRequest = onlyNew,
            title = { Text(tr("Уже записанные сессии", "Sessions already recorded")) },
            text = { Text(tr("Положить туда и уже записанные сессии (%d)?", "Put the sessions already recorded there too (%d)?").format(old)) },
            confirmButton = { TextButton(onClick = { asked = null; export.setFolder(tree, all = true) }) { Text(tr("Все", "All")) } },
            dismissButton = { TextButton(onClick = onlyNew) { Text(tr("Только новые", "Only new")) } },
        )
    }
}
