package com.obdscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.obdscanner.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SessionsScreen(m: ObdManager) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val list = remember(refresh) { m.sessions.list() }
    val current = m.session?.takeIf { !it.closed }?.dir
    // Ticked sessions (by folder name): while any is ticked, Send and Delete act on all of them.
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val chosen = list.filter { it.name in picked }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = chosen.isNotEmpty()) { picked = emptySet() }
    // The report being built (when the build has the generator): session, share done 0…1, stage.
    var making by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(0.0) }
    var stage by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    // The report built: open it or send it (Telegram, mail…) — a browser can't pass on the page it shows.
    var ready by remember { mutableStateOf<File?>(null) }

    fun report(dir: File) {
        if (dir == current) m.session?.flush()
        making = dir.name; done = 0.0; stage = tr("Подготовка", "Preparing")
        job = scope.launch {
            try {
                val html = withContext(Dispatchers.Default) {
                    val work = this
                    ReportBridge.build(dir) { d, st -> work.ensureActive(); done = d; stage = st }
                }
                val file = withContext(Dispatchers.IO) {
                    File(ctx.cacheDir, "share").apply { mkdirs() }.resolve("report_${dir.name}.html").apply { writeText(html) }
                }
                ready = file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(ctx, tr("Отчёт не собрался: %s", "The report failed: %s").format(e.message ?: e.javaClass.simpleName))
            } finally {
                making = null
            }
        }
    }

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
                if (chosen.all { it == current }) toast(ctx, tr("Сессия ещё пишется", "Session is still recording")) else confirm = true
            }) { Icon(Icons.Default.Delete, tr("Удалить", "Delete")) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            item { Muted(tr("Каждое подключение — отдельная сессия: raw.log (обмен с адаптером), data.csv (все значения), report.txt (сводка), scan.csv (GM-скан).",
                "Each connection is a separate session: raw.log (adapter traffic), data.csv (all values), report.txt (summary), scan.csv (GM scan).")) }
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
                            Text(tr("%.1f КБ", "%.1f KB").format(m.sessions.size(dir) / 1024.0), style = MaterialTheme.typography.bodySmall)
                        }
                        // One session's own buttons: hidden while picking, so the bar on top is the only Send / Delete.
                        if (chosen.isEmpty()) {
                            if (ReportBridge.available) IconButton(onClick = { report(dir) }, enabled = making == null) {
                                Icon(painterResource(R.drawable.ic_report), tr("Отчёт", "Report"))
                            }
                            IconButton(onClick = { send(listOf(dir)) }) { Icon(Icons.Default.Share, tr("Отправить", "Send")) }
                            IconButton(onClick = {
                                if (dir == current) toast(ctx, tr("Сессия ещё пишется", "Session is still recording")) else { m.sessions.delete(dir); refresh++ }
                            }) { Icon(Icons.Default.Delete, tr("Удалить", "Delete")) }
                        }
                    }
                }
            }
            if (list.isEmpty()) item { Muted(tr("Сессий пока нет.", "No sessions yet.")) }
        }
    }

    making?.let { name ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Отчёт", "Report")) },
            text = {
                Column {
                    Text(name, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { done.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text(stage, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel(); making = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }

    ready?.let { file ->
        AlertDialog(
            onDismissRequest = { ready = null },
            title = { Text(tr("Отчёт готов", "The report is ready")) },
            text = { Text(file.name, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Row {
                    TextButton(onClick = { ready = null; shareReport(ctx, file) }) { Text(tr("Отправить", "Send")) }
                    TextButton(onClick = { ready = null; openReport(ctx, file) }) { Text(tr("Открыть", "Open")) }
                }
            },
            dismissButton = { TextButton(onClick = { ready = null }) { Text(tr("Закрыть", "Close")) } },
        )
    }

    if (confirm) {
        val gone = chosen.filter { it != current }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Удалить выбранные сессии?", "Delete the selected sessions?")) },
            text = {
                Text(tr("Сессий: %d. Восстановить их будет нельзя.", "Sessions: %d. They can't be restored.").format(gone.size) +
                    if (current in chosen) tr(" Сессия, которая сейчас пишется, останется.", " The session being recorded stays.") else "")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; gone.forEach(m.sessions::delete); picked = emptySet(); refresh++ }) { Text(tr("Удалить", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}
