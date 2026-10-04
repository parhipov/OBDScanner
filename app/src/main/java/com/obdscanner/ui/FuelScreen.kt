package com.obdscanner.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obdscanner.ObdManager
import com.obdscanner.VehicleInfo
import com.obdscanner.obd.Reading
import com.obdscanner.screen.DtcRef
import com.obdscanner.screen.FuelScreen

@Composable
fun FuelScreen(m: ObdManager, r: Map<String, Reading>, v: VehicleInfo) {
    var shown by remember { mutableStateOf<DtcRef?>(null) }
    shown?.let { DtcHelpDialog(it, v.dtcFamily) { shown = null } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        blocks(FuelScreen.build(r, v), busy = null, onAction = { m.run(it) }, onDtc = { shown = it })
    }
}
