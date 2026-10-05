package com.obdscanner

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.obdscanner.bus.BusId
import com.obdscanner.bus.BusSniffer
import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.car.CarModel
import com.obdscanner.car.ExtCommand
import com.obdscanner.gm.GmDtcReader
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.GmScanner
import com.obdscanner.gm.ScanHit
import com.obdscanner.obd.DtcCode
import com.obdscanner.obd.DtcDb
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.EcuIdent
import com.obdscanner.obd.ObdModules
import com.obdscanner.obd.UdsDtcReader
import com.obdscanner.obd.Make
import com.obdscanner.obd.Dtc
import com.obdscanner.obd.FuelRate
import com.obdscanner.obd.Mode06
import com.obdscanner.obd.Mode09
import com.obdscanner.obd.Monitor
import com.obdscanner.obd.Pids
import com.obdscanner.obd.PollRate
import com.obdscanner.obd.Reading
import com.obdscanner.obd.Readiness
import com.obdscanner.obd.TestResult
import com.obdscanner.obd.ecuName
import com.obdscanner.obd.pick
import com.obdscanner.screen.Action
import com.obdscanner.session.PhoneSensors
import com.obdscanner.session.Session
import com.obdscanner.session.SessionStore
import com.obdscanner.session.Store
import com.obdscanner.transport.BluetoothTransport
import com.obdscanner.transport.MockTransport
import com.obdscanner.transport.Transport
import com.obdscanner.transport.UsbAdapter
import com.obdscanner.transport.UsbAdapters
import com.obdscanner.transport.UsbSerialLink
import com.obdscanner.transport.UsbTransport
import com.obdscanner.transport.WifiNet
import com.obdscanner.transport.WifiTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException

const val SOURCE_DEMO = "demo"
const val SOURCE_SENSORS = "sensors"
const val SOURCE_WIFI = "wifi"

class ObdManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sessions = SessionStore(File(context.filesDir, "sessions"), File(context.cacheDir, "share"))
    private val prefs = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    /** Name of the running one-off operation (DTC read, scan...), null when idle. */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    val activeTab = MutableStateFlow(Tab.Connect)

    private val _picked = MutableStateFlow(CarDb.choice(prefs.getString("car", null)))
    /** The hand pick (Guide / Connect): a make or a make and model; null = by VIN. */
    val picked: StateFlow<CarChoice?> = _picked.asStateFlow()

    private val link = CarLink(
        store = PrefsStore(prefs),
        pickedCar = { _picked.value },
        tab = { activeTab.value },
        onStep = { text -> (_conn.value as? ConnState.Connecting)?.let { _conn.value = it.copy(step = text) } },
    )
    val readings: StateFlow<Map<String, Reading>> get() = link.readings
    val vehicle: StateFlow<VehicleInfo> get() = link.vehicle
    val scan: StateFlow<ScanState> get() = link.scan
    val bus: StateFlow<BusState> get() = link.bus

    init {
        // The database loads in the background (CarDb.init): take the saved pick once it's there.
        scope.launch {
            CarDb.loaded.first { it }
            val pick = CarDb.choice(prefs.getString("car", null))
            _picked.value = pick
            link.pickLoaded(pick?.model)
        }
    }

    /** Hand pick (null = by VIN). While connected it shows at once; its parameters are probed on "Rescan". */
    fun pickCar(pick: CarChoice?) {
        prefs.edit().putString("car", pick?.id).apply()
        _picked.value = pick
        link.pickCar(pick)
    }

    @Volatile var session: Session? = null
        private set
    private val phone = PhoneSensors(context)
    private var obd: Obd? = null
    private var mainJob: Job? = null
    private var opJob: Job? = null

    val lastDevice: String? get() = prefs.getString("last_device", null)

    private val _source = MutableStateFlow(prefs.getString("source", null) ?: lastDevice)
    /** The source picked on Connect: a Bluetooth adapter's address, a USB id ([UsbAdapters]), [SOURCE_WIFI], [SOURCE_DEMO] or [SOURCE_SENSORS]. */
    val source: StateFlow<String?> = _source.asStateFlow()

    /** While a session runs the pick is locked: it shows what is running. */
    fun pickSource(id: String) {
        if (mainJob?.isActive == true) return
        prefs.edit().putString("source", id).apply()
        _source.value = id
    }

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val _usb = MutableStateFlow(UsbAdapters.list(usbManager))
    /** Plugged-in USB adapters, kept up to date on plug and unplug. */
    val usb: StateFlow<List<UsbAdapter>> = _usb.asStateFlow()

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbAttached() else _usb.value = UsbAdapters.list(usbManager)
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * An adapter was plugged in (broadcast, or the app opened by Android for it): it's the one to use, so it gets
     * picked. Stays picked until the next plug-in even if Bluetooth is chosen instead.
     */
    fun usbAttached() {
        val list = UsbAdapters.list(usbManager)
        val added = list.filter { a -> _usb.value.none { it.id == a.id } }
        _usb.value = list
        (added.firstOrNull() ?: list.firstOrNull()?.takeIf { _source.value == null })?.let { pickSource(it.id) }
    }

    @SuppressLint("MissingPermission")
    fun connectBluetooth(device: BluetoothDevice) {
        prefs.edit().putString("last_device", device.address).apply()
        val key = "bt_method_" + device.address
        connect { s ->
            BluetoothTransport(device, prefs.getString(key, null), { s.note(it) }) { prefs.edit().putString(key, it).apply() }
        }
    }

    @Volatile private var usbAsking = false

    /** USB adapter [id] from [usb]. Without access yet, Android asks first and the connect goes on once allowed. */
    fun connectUsb(id: String) {
        if (mainJob?.isActive == true || usbAsking) return
        val um = usbManager
        val a = UsbAdapters.list(um).firstOrNull { it.id == id }
        if (um == null || a == null) {
            _conn.value = ConnState.Failed(tr("USB-адаптер не найден. Проверьте переходник OTG.", "USB adapter not found. Check the OTG adapter."))
            return
        }
        if (!um.hasPermission(a.device)) {
            askUsb(um, a.device) { ok ->
                if (ok) connectUsb(id)
                else _conn.value = ConnState.Failed(tr("Нет доступа к USB-адаптеру.", "No access to the USB adapter."))
            }
            return
        }
        prefs.edit().putString("last_device", id).apply()
        val key = "usb_baud_$id"
        connect { s ->
            // Looked up again on every try: after a reconnect the device object may be a new one.
            val cur = UsbAdapters.list(um).firstOrNull { it.id == id }
                ?: throw IOException(tr("USB-адаптер отключён", "USB adapter unplugged"))
            s.note("USB: ${cur.chip} $id ${cur.product ?: ""}".trimEnd())
            UsbTransport(UsbSerialLink(um, cur), prefs.getInt(key, 0).takeIf { it > 0 }, { s.note(it) }) { prefs.edit().putInt(key, it).apply() }
        }
    }

    private fun askUsb(um: UsbManager, device: UsbDevice, done: (Boolean) -> Unit) {
        usbAsking = true
        val action = context.packageName + ".USB_PERMISSION"
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                context.unregisterReceiver(this)
                usbAsking = false
                done(um.hasPermission(device) || i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Android puts the device into the intent: it has to be mutable (explicit, so Android 14 allows that).
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        um.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags))
    }

    /** Address typed on Connect for the Wi-Fi adapter ("host:port"); empty — found by itself. */
    var wifiAddress: String
        get() = prefs.getString("wifi_address", null).orEmpty()
        set(v) = prefs.edit().putString("wifi_address", v.trim()).apply()

    /** The address that answered last time, "host:port". */
    val wifiLast: String? get() = prefs.getString("wifi_last", null)

    /** Wi-Fi adapter: the typed address, else the last one that worked, the Wi-Fi gateway and the usual clone addresses. */
    fun connectWifi() {
        prefs.edit().putString("last_device", SOURCE_WIFI).apply()
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        connect { s ->
            val net = WifiNet.find(cm)
            s.note("Wi-Fi: network ${net.network ?: "none"} (${net.iface ?: "?"}), gateway ${net.gateway ?: "none"}" + if (net.vpn) ", VPN on" else "")
            val typed = wifiAddress.takeIf { it.isNotEmpty() }
            val candidates = if (typed != null) listOf(typed) else listOfNotNull(wifiLast) +
                listOfNotNull(net.gateway).flatMap { listOf("$it:${WifiTransport.DEFAULT_PORT}", "$it:23") } + WifiTransport.DEFAULTS
            WifiTransport(candidates, net.factory, net.vpn, { s.note(it) }) { prefs.edit().putString("wifi_last", it).apply() }
        }
    }

    fun connectDemo() = connect { MockTransport() }

    /** Phone sensors only, no adapter: a second phone elsewhere in the car, or a car without an adapter. */
    fun recordSensors() {
        if (mainJob?.isActive == true) return
        mainJob = scope.launch {
            val s = sessions.create()
            session = s
            s.report(tr("Режим", "Mode"), tr("Только датчики телефона, без адаптера", "Phone sensors only, no adapter"))
            phone.start(s)
            ObdService.start(context, tr("Датчики телефона", "Phone sensors"), sensorsOnly = true)
            _conn.value = ConnState.Recording
            try {
                awaitCancellation()
            } finally {
                phone.stop()
                s.report(tr("Конец сессии", "End of session"), tr("остановлено пользователем", "stopped by user"))
                s.close()
                ObdService.stop(context)
                _conn.value = ConnState.Idle
            }
        }
    }

    fun disconnect() {
        mainJob?.cancel()
    }

    private fun connect(make: (Session) -> Transport) {
        if (mainJob?.isActive == true) return
        mainJob = scope.launch {
            // The picked car's protocol and the manufacturer parameters come from it.
            CarDb.loaded.first { it }
            val s = sessions.create()
            session = s
            link.session = s
            phone.start(s)
            link.reset(_picked.value?.model)
            var elm: Elm327? = null
            var failure: String? = null
            try {
                // Some clones reboot while trying a protocol (the Bluetooth link just closes):
                // reconnect and go on without that protocol.
                val skip = mutableSetOf<Int>()
                var o: Obd
                var name: String
                while (true) {
                    val transport = make(s)
                    name = transport.name
                    _conn.value = ConnState.Connecting(name, if (skip.isEmpty()) tr("Подключение к адаптеру…", "Connecting to adapter…") else CarLink.REBOOTING)
                    s.note("connect: $name")
                    val e = Elm327(transport) { d, t -> s.raw(d, t) }
                    elm = e
                    o = Obd(e)
                    // After a drop the clone is still rebooting: give it a few tries.
                    val tries = if (skip.isEmpty()) 1 else 3
                    for (n in 1..tries) {
                        try {
                            e.open()
                            break
                        } catch (ex: IOException) {
                            if (n == tries) throw if (skip.isEmpty()) ex else IOException(CarLink.REPLUG, ex)
                            s.note("reconnect $n failed: ${ex.message}")
                            delay(2000)
                        }
                    }
                    ObdService.start(context, name, usb = transport is UsbTransport, wifi = transport is WifiTransport)
                    // Clones miss the first command sent right after the link comes up (AndrOBD #233: Car Scanner waits ~500 ms).
                    delay(500)
                    try {
                        link.initAdapter(o, name, skip)
                        break
                    } catch (ex: IOException) {
                        val p = link.probingProtocol
                        if (e.alive || p == null) throw ex
                        if (skip.size >= 3) throw IOException(CarLink.REPLUG, ex)
                        skip += p
                        s.note("adapter dropped the link while trying protocol $p — reconnecting without it")
                        e.close()
                        delay(3000)
                    }
                }
                obd = o
                // Mid-session reset (LV RESET when cranking, ERR94, a clone's banner): the adapter is back to
                // defaults and protocol auto — the search that dropped the Polo's clone. Put our settings back.
                o.onAdapterReset = { link.adapterReset(o) }
                _conn.value = ConnState.Connected(name)
                link.opMutex.withLock { link.discover(o) }
                if (!o.canTarget) s.note("${if (o.can29) "29-bit CAN" else "ISO 9141-2"}: standard OBD only, module search and DTCs of all modules skipped")
                else link.opMutex.withLock { link.autoModules(o) }
                link.pollLoop(o)
            } catch (e: CancellationException) {
                s.note("disconnect requested")
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
                s.note("ERROR: ${e.stackTraceToString()}")
            } finally {
                opJob?.cancel()
                phone.stop()
                obd = null
                elm?.close()
                s.report(tr("Конец сессии", "End of session"), failure ?: tr("отключено пользователем", "disconnected by user"))
                s.close()
                ObdService.stop(context)
                link.stopped()
                _busy.value = null
                _conn.value = if (failure != null) ConnState.Failed(failure) else ConnState.Idle
            }
        }
    }

    // ---------------------------------------------------------------- user actions

    private fun launchOp(title: String, block: suspend (Obd) -> Unit) {
        val o = obd ?: return
        if (opJob?.isActive == true) return
        opJob = scope.launch {
            _busy.value = title
            try {
                link.opMutex.withLock {
                    try {
                        block(o)
                    } finally {
                        withContext(NonCancellable) { runCatching { o.broadcast() } }
                    }
                }
            } catch (e: CancellationException) {
                session?.note("op '$title' cancelled")
            } catch (e: Exception) {
                session?.note("op '$title' failed: ${e.stackTraceToString()}")
            } finally {
                _busy.value = null
                link.opFinished()
            }
        }
    }

    /** A screen's button ([com.obdscanner.screen.Block.Buttons]); the Codes screen asks before clearing. */
    fun run(a: Action) = when (a) {
        Action.READ_DTC -> refreshDtc()
        Action.CLEAR_DTC -> clearDtc()
        Action.READ_ALL_MODULES -> readAllModulesDtc()
        Action.RESCAN -> rediscover()
        Action.MODE06 -> refreshMode06()
        // The screen opens it (Info → Licenses).
        Action.LICENSES -> Unit
    }

    fun refreshDtc() = launchOp(tr("Чтение ошибок", "Reading codes")) { link.refreshDtc(it) }

    fun clearDtc() = launchOp(tr("Сброс ошибок", "Clearing codes")) { link.clearDtc(it) }

    fun refreshMode06() = launchOp("Mode 06") { link.refreshMode06(it) }

    fun rediscover() = launchOp(tr("Повторный опрос", "Rescan")) { link.rediscover(it) }

    fun probeModules() = launchOp(tr("Поиск модулей", "Module search")) { link.probeModules(it) }

    /** Full DTC memory of every module; finds the modules first if that wasn't done yet. Read only. */
    fun readAllModulesDtc() = launchOp(tr("Ошибки всех блоков", "Codes in all modules")) { link.readAllModulesDtc(it) }

    fun scanModule(module: GmModule, service: String, range: IntRange) =
        launchOp(tr("Скан ${module.id} $service", "Scan ${module.id} $service")) { link.scanModule(it, module, service, range) }

    /** Passive listening of the regular module traffic — nothing is sent to the modules. */
    fun sniffBus() = launchOp(tr("Прослушка шины", "Bus listening")) { link.sniffBus(it) }

    fun stopOp() {
        opJob?.cancel()
    }

    /** Watches every hit that carries data (a limited number, so polling keeps its pace); empty list clears. */
    fun watchAll(hits: List<ScanHit>) = link.watchAll(hits)

    fun toggleWatch(hit: ScanHit) = link.toggleWatch(hit)
}

/** The app's settings as the connection's [Store]. */
private class PrefsStore(private val prefs: SharedPreferences) : Store {
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    override fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)
    override fun putStringSet(key: String, value: Set<String>) = prefs.edit().putStringSet(key, value).apply()
}
