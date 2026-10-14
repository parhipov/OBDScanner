package com.obdscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdscanner.ObdManager
import com.obdscanner.VehicleInfo
import com.obdscanner.obd.Reading
import com.obdscanner.screen.AboutScreen
import com.obdscanner.screen.Action
import com.obdscanner.screen.AllScreen
import com.obdscanner.screen.CodesScreen
import com.obdscanner.screen.DtcRef
import com.obdscanner.screen.InfoScreen

/** Every live value, grouped by ECU. */
@Composable
fun AllScreen(r: Map<String, Reading>, v: VehicleInfo) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        blocks(AllScreen.build(r, v), busy = null, onAction = {}, onDtc = {})
    }
}

@Composable
fun DtcScreen(m: ObdManager, v: VehicleInfo, busy: String?) {
    // What «Блоки на SW-CAN» read on «Скан блоков»: shown here with the other modules' codes.
    val sw = m.scan.collectAsStateWithLifecycle().value.sw
    var confirm by remember { mutableStateOf(false) }
    // Tapped code: its description dialog.
    var shown by remember { mutableStateOf<DtcRef?>(null) }
    shown?.let { DtcHelpDialog(it, v.dtcFamily) { shown = null } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        blocks(CodesScreen.build(v, busy, sw), busy, onAction = { if (it == Action.CLEAR_DTC) confirm = true else m.run(it) }, onDtc = { shown = it })
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(CodesScreen.CLEAR_TITLE) },
        text = { Text(CodesScreen.CLEAR_TEXT) },
        confirmButton = { TextButton(onClick = { confirm = false; m.clearDtc() }) { Text(CodesScreen.CLEAR_YES) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(CodesScreen.CLEAR_NO) } },
    )
}

@Composable
fun InfoScreen(m: ObdManager, v: VehicleInfo, busy: String?) {
    var forget by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        blocks(InfoScreen.build(v), busy, onAction = { if (it == Action.FORGET) forget = true else m.run(it) }, onDtc = {})
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
