package com.obdscanner.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.ReportBridge
import com.obdscanner.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A session's report being built (when the build has the generator): the progress dialog with Cancel, then «Отчёт
 * готов» to open it or send it (Telegram, mail…) — a browser can't pass on the page it shows. «Сессии» and the
 * button after «Стоп» on «Связь» share it; [ReportDialogs] shows its dialogs.
 */
class ReportRun(private val m: ObdManager, private val ctx: Context, private val scope: CoroutineScope) {
    /** The session being built, share done 0…1, stage. */
    var making by mutableStateOf<String?>(null); private set
    var done by mutableStateOf(0.0); private set
    var stage by mutableStateOf(""); private set
    var ready by mutableStateOf<File?>(null)
    private var job: Job? = null

    fun start(dir: File) {
        if (making != null) return
        if (dir == m.session?.takeIf { !it.closed }?.dir) m.session?.flush()
        making = dir.name; done = 0.0; stage = tr("Подготовка", "Preparing")
        job = scope.launch {
            try {
                val html = withContext(Dispatchers.Default) {
                    val work = this
                    ReportBridge.build(dir, m.fuelWatch.value) { d, st -> work.ensureActive(); done = d; stage = st }
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

    fun cancel() { job?.cancel(); making = null }

    companion object {
        private var shared: ReportRun? = null

        /**
         * One for the process, on its own scope: a build takes minutes, and in a composable's scope a rotation, a theme
         * or language change recreated the activity, cancelled the job and left nothing — no report, no message.
         */
        fun of(m: ObdManager, ctx: Context): ReportRun =
            shared ?: ReportRun(m, ctx.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { shared = it }
    }
}

@Composable
fun rememberReportRun(m: ObdManager): ReportRun {
    val ctx = LocalContext.current
    return remember(m) { ReportRun.of(m, ctx) }
}

@Composable
fun ReportDialogs(run: ReportRun) {
    val ctx = LocalContext.current
    run.making?.let { name ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Отчёт", "Report")) },
            text = {
                Column {
                    Text(name, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { run.done.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text(run.stage, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = run::cancel) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    run.ready?.let { file ->
        AlertDialog(
            onDismissRequest = { run.ready = null },
            title = { Text(tr("Отчёт готов", "The report is ready")) },
            text = { Text(file.name, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Row {
                    TextButton(onClick = { run.ready = null; shareReport(ctx, file) }) { Text(tr("Отправить", "Send")) }
                    TextButton(onClick = { run.ready = null; openReport(ctx, file) }) { Text(tr("Открыть", "Open")) }
                }
            },
            dismissButton = { TextButton(onClick = { run.ready = null }) { Text(tr("Закрыть", "Close")) } },
        )
    }
}
