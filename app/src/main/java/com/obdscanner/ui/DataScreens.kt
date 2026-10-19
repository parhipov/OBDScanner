package com.obdscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdscanner.ObdManager
import com.obdscanner.VehicleInfo
import com.obdscanner.obd.Reading
import com.obdscanner.obd.pick
import com.obdscanner.screen.AboutScreen
import com.obdscanner.screen.Action
import com.obdscanner.screen.AllScreen
import com.obdscanner.screen.CodesScreen
import com.obdscanner.screen.DtcRef
import com.obdscanner.screen.InfoScreen
import com.obdscanner.tr

/** Every live value, grouped by block: a search over them, ☆ to put one on Main, a heading folds its group. */
@Composable
fun AllScreen(m: ObdManager, r: Map<String, Reading>, v: VehicleInfo) {
    var filter by rememberSaveable { mutableStateOf("") }
    val folds by m.folds.collectAsStateWithLifecycle()
    val pins by m.pins.collectAsStateWithLifecycle()
    val carPins by m.carPins.collectAsStateWithLifecycle()
    val carScreen by m.carScreen.collectAsStateWithLifecycle()
    val modules = m.scan.collectAsStateWithLifecycle().value.modules
    val names = remember(modules, v.extActive) { m.moduleNames() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        item(key = "search") {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text(tr("Поиск: обороты, масло, 22.1940…", "Search: rpm, oil, 22.1940…")) }, singleLine = true)
        }
        blocks(AllScreen.build(r, v, names, pins, filter, folds, carPins, carScreen), busy = null, onAction = {}, onDtc = {},
            onFold = m::toggleFold, onPin = m::togglePin, onCar = m::toggleCarPin, onMove = m::movePin)
    }
}

@Composable
fun DtcScreen(m: ObdManager, r: Map<String, Reading>, v: VehicleInfo, busy: String?) {
    val ctx = LocalContext.current
    val canStop by m.canStop.collectAsStateWithLifecycle()
    val folds by m.folds.collectAsStateWithLifecycle()
    val mil = r.pick("01.01.MIL")?.value?.let { it == 1.0 }
    var clear by remember { mutableStateOf<CodesScreen.ClearModulesView?>(null) }
    // Tapped code: its description dialog.
    var shown by remember { mutableStateOf<DtcRef?>(null) }
    shown?.let { DtcHelpDialog(it, v.dtcFamily) { shown = null } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        blocks(CodesScreen.build(v, busy, canStop, mil, folds), busy, onAction = {
            when (it) {
                Action.CLEAR_DTC -> clear = CodesScreen.clearModules(v)
                Action.COPY_DTC -> {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OBD Scanner", CodesScreen.text(v, mil)))
                    toast(ctx, tr("Список скопирован", "The list is copied"))
                }
                else -> m.run(it)
            }
        }, onDtc = { shown = it }, onFold = m::toggleFold)
    }
    clear?.let { view -> ClearModulesDialog(view, { clear = null }) { keys -> m.clearCodes(CodesScreen.OBD in keys, keys - CodesScreen.OBD) } }
}

/** «Стереть ошибки?»: the engine and gearbox, then the modules with codes, each with its tick; [onClear] gets the ticked ones. */
@Composable
private fun ClearModulesDialog(view: CodesScreen.ClearModulesView, onDismiss: () -> Unit, onClear: (List<String>) -> Unit) {
    val ticked = remember(view) { mutableStateMapOf<String, Boolean>().apply { view.items.forEach { put(it.key, it.checked) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(view.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (i in view.items) Row(Modifier.fillMaxWidth().clickable { ticked[i.key] = ticked[i.key] != true }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ticked[i.key] == true, { ticked[i.key] = it })
                    Text(i.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${i.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
                Text(view.text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onClear(view.items.filter { ticked[it.key] == true }.map { it.key }) },
                enabled = ticked.values.any { it }) { Text(view.yes) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(view.no) } },
    )
}

@Composable
fun InfoScreen(m: ObdManager, v: VehicleInfo, busy: String?) {
    var forget by remember { mutableStateOf(false) }
    val folds by m.folds.collectAsStateWithLifecycle()
    val scan = m.scan.collectAsStateWithLifecycle().value
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        blocks(InfoScreen.build(v, scan.modules + scan.sw?.modules.orEmpty(), folds), busy,
            onAction = { if (it == Action.FORGET) forget = true else m.run(it) }, onDtc = {}, onFold = m::toggleFold)
    }
    if (forget) AlertDialog(
        onDismissRequest = { forget = false },
        title = { Text(InfoScreen.FORGET_TITLE) },
        text = { Text(InfoScreen.FORGET_TEXT) },
        confirmButton = { TextButton(onClick = { forget = false; m.run(Action.FORGET) }) { Text(InfoScreen.FORGET_YES) } },
        dismissButton = { TextButton(onClick = { forget = false }) { Text(InfoScreen.FORGET_NO) } },
    )
}

/** The end of «Справка»: about the app, licenses, the disclaimer, the update check. */
fun LazyListScope.about(m: ObdManager) {
    item { AboutBlocks(m) }
}

@Composable
private fun AboutBlocks(m: ObdManager) {
    var licenses by remember { mutableStateOf(false) }
    var terms by remember { mutableStateOf(false) }
    val updateCheck by m.updates.enabled.collectAsStateWithLifecycle()
    Column {
        for (b in AboutScreen.build()) BlockView(b, busy = null, onAction = {
            when (it) {
                Action.LICENSES -> licenses = true
                Action.TERMS -> terms = true
                else -> Unit
            }
        }, onDtc = {})
        // The phone's own, the page has nothing to update.
        UpdateCheckLine(updateCheck, m.updates::setEnabled)
    }
    if (licenses) LicensesDialog { licenses = false }
    if (terms) TermsDialog { terms = false }
}
