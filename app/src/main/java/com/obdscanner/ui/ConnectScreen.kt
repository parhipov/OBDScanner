package com.obdscanner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdscanner.ConnState
import com.obdscanner.ObdManager
import com.obdscanner.SOURCE_DEMO
import com.obdscanner.SOURCE_SENSORS
import com.obdscanner.SOURCE_WIFI
import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarChoice
import com.obdscanner.transport.WifiTransport
import com.obdscanner.tr

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(m: ObdManager, conn: ConnState, v: VehicleInfo, picked: CarChoice?) {
    val ctx = LocalContext.current
    val needPerm = Build.VERSION.SDK_INT >= 31
    fun granted() = !needPerm || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var hasPerm by remember { mutableStateOf(granted()) }
    var refresh by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerm = granted()
        refresh++
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (needPerm) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(wanted.toTypedArray())
        }
    }

    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val devices: List<BluetoothDevice> = remember(hasPerm, refresh) {
        if (!hasPerm || adapter == null || !adapter.isEnabled) emptyList()
        else adapter.bondedDevices.orEmpty().sortedWith(compareByDescending<BluetoothDevice> { it.address == m.lastDevice }
            .thenByDescending { looksLikeObd(it.name) }.thenBy { it.name ?: "" })
    }
    // While a session runs the pick is locked: it shows what is running.
    val running = conn is ConnState.Connecting || conn is ConnState.Connected || conn is ConnState.Recording
    val source by m.source.collectAsStateWithLifecycle()
    val fuelWatch by m.fuelWatch.collectAsStateWithLifecycle()
    val usb by m.usb.collectAsStateWithLifecycle()
    fun pick(id: String) { if (!running) m.pickSource(id) }
    val device = devices.firstOrNull { it.address == source }
    val usbPicked = usb.firstOrNull { it.id == source }
    var wifiAddress by remember { mutableStateOf(m.wifiAddress) }
    val wifiOk = wifiAddress.isBlank() || WifiTransport.parse(wifiAddress) != null
    val canStart = device != null || usbPicked != null || (source == SOURCE_WIFI && wifiOk) || source == SOURCE_DEMO || source == SOURCE_SENSORS

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            item { SupportLine() }
            item { FuelWatchLine(fuelWatch, m::setFuelWatch) }
            item {
                when (conn) {
                    is ConnState.Connecting -> Card(Modifier.fillMaxWidth().padding(4.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 16.dp))
                            Column {
                                Text(conn.device, style = MaterialTheme.typography.titleMedium)
                                Text(conn.step)
                            }
                        }
                    }
                    is ConnState.Failed -> Hint(tr("Ошибка: ${conn.message}\n\nЛог попытки сохранён в сессии — отправьте его кнопкой ⇪ сверху на $SESSION_EMAIL.",
                        "Error: ${conn.message}\n\nThe attempt log is saved in the session — send it with the ⇪ button at the top to $SESSION_EMAIL."), Bad)
                    is ConnState.Connected -> Hint(tr("Подключено: ${conn.device}", "Connected: ${conn.device}"), Good)
                    ConnState.Recording -> Hint(tr("Идёт запись датчиков телефона. Держите приложение открытым, телефон закрепите неподвижно.",
                        "Recording phone sensors. Keep the app open and the phone fixed in place."), Good)
                    ConnState.Idle -> Unit
                }
            }
            item { CarCard(m, v, picked) }
            item { SectionTitle(tr("Адаптер ELM327", "ELM327 adapter")) }
            // Only while plugged in, and on top: a plugged-in cable is the adapter meant.
            items(usb, key = { it.id }) { a ->
                SourceCard(tr("USB-адаптер", "USB adapter"),
                    listOfNotNull("USB · ${a.chip}", a.product).joinToString(" · ") + if (a.id == m.lastDevice) tr(" · последний", " · last used") else "",
                    selected = a.id == source, locked = running) { pick(a.id) }
            }
            item {
                when {
                    adapter == null -> Muted(tr("На устройстве нет Bluetooth.", "This device has no Bluetooth."))
                    !hasPerm -> Button(onClick = { permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }, Modifier.padding(8.dp)) {
                        Text(tr("Разрешить доступ к Bluetooth", "Allow Bluetooth access"))
                    }
                    !adapter.isEnabled -> Button(onClick = { enableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, Modifier.padding(8.dp)) {
                        Text(tr("Включить Bluetooth", "Turn on Bluetooth"))
                    }
                    devices.isEmpty() -> Muted(tr("Нет спаренных устройств. Спарьте адаптер в настройках Bluetooth (PIN обычно 1234 или 0000).",
                        "No paired devices. Pair the adapter in the Bluetooth settings (PIN is usually 1234 or 0000)."))
                }
            }
            items(devices, key = { it.address }) { d ->
                SourceCard(d.name ?: tr("Без имени", "Unnamed"),
                    d.address + if (d.address == m.lastDevice) tr(" · последний", " · last used") else "",
                    selected = d.address == source, locked = running) { pick(d.address) }
            }
            item {
                Row(Modifier.padding(8.dp)) {
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text(tr("Настройки Bluetooth", "Bluetooth settings")) }
                    OutlinedButton(onClick = { refresh++ }, Modifier.padding(start = 8.dp)) { Text(tr("Обновить", "Refresh")) }
                }
                SourceCard(tr("Wi-Fi-адаптер", "Wi-Fi adapter"),
                    "Wi-Fi · " + (wifiAddress.trim().ifEmpty { null } ?: m.wifiLast ?: tr("адрес определяется сам", "address found automatically")) +
                        if (m.lastDevice == SOURCE_WIFI) tr(" · последний", " · last used") else "",
                    selected = source == SOURCE_WIFI, locked = running) { pick(SOURCE_WIFI) }
                if (source == SOURCE_WIFI) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = wifiAddress,
                            onValueChange = { wifiAddress = it; m.wifiAddress = it },
                            label = { Text(tr("Адрес адаптера", "Adapter address")) },
                            placeholder = { Text(tr("пусто — автоматически", "empty — automatic")) },
                            isError = !wifiOk,
                            singleLine = true,
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, Modifier.padding(start = 8.dp)) {
                            Text(tr("Настройки Wi-Fi", "Wi-Fi settings"))
                        }
                    }
                    Muted(tr("Подключите телефон к сети адаптера (обычно WiFi_OBDII, без пароля). Если Android спросит про сеть без интернета — оставайтесь в ней. Адрес вида 192.168.0.10:35000.",
                        "Connect the phone to the adapter's network (usually WiFi_OBDII, no password). If Android asks about a network without internet, stay connected. Address like 192.168.0.10:35000."))
                }
                Muted(tr("Старые клоны ELM327 работают по классическому Bluetooth, USB-адаптеры — через переходник OTG, Wi-Fi-адаптеры — через свою сеть Wi-Fi. Включите зажигание перед подключением.",
                    "Old ELM327 clones work over classic Bluetooth, USB adapters through an OTG adapter, Wi-Fi adapters through their own Wi-Fi network. Turn the ignition on before connecting."))
                SectionTitle(tr("Без адаптера", "No adapter"))
                SourceCard(tr("Демо-режим", "Demo mode"),
                    tr("Эмулятор ELM327 + CTS 2.8: ECM, TCM, ошибки, Mode 06, GM-модули", "ELM327 emulator + CTS 2.8: ECM, TCM, codes, Mode 06, GM modules"),
                    selected = source == SOURCE_DEMO, locked = running) { pick(SOURCE_DEMO) }
                SourceCard(tr("Датчики телефона", "Phone sensors"),
                    tr("Только акселерометр и гироскоп. Например, второй телефон в багажнике во время обычной сессии: записи сводятся по времени.",
                        "Accelerometer and gyroscope only. For example, a second phone in the boot during a normal session: the recordings are lined up by time."),
                    selected = source == SOURCE_SENSORS, locked = running) { pick(SOURCE_SENSORS) }
            }
        }
        Button(
            onClick = {
                when {
                    running -> m.disconnect()
                    device != null -> m.connectBluetooth(device)
                    usbPicked != null -> m.connectUsb(usbPicked.id)
                    source == SOURCE_WIFI -> m.connectWifi()
                    source == SOURCE_DEMO -> m.connectDemo()
                    source == SOURCE_SENSORS -> m.recordSensors()
                }
            },
            enabled = running || canStart,
            colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(56.dp),
        ) {
            Text(if (running) tr("Стоп", "Stop") else tr("Старт", "Start"), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** One pickable source: a frame and a filled radio when picked. */
@Composable
private fun SourceCard(title: String, sub: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(4.dp).clickable(enabled = !locked, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = !locked || selected, modifier = Modifier.padding(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun looksLikeObd(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return listOf("obd", "elm", "v-link", "vgate", "konnwei", "icar", "scan").any { it in n }
}
