@file:OptIn(ExperimentalJsExport::class)

package com.obdscanner.web

import com.obdscanner.AppInfo
import com.obdscanner.CarLink
import com.obdscanner.ConnState
import com.obdscanner.L10n
import com.obdscanner.Tab
import com.obdscanner.car.CarDb
import com.obdscanner.screen.Guide
import com.obdscanner.screen.GmView
import com.obdscanner.screen.GmScreen
import com.obdscanner.screen.CarPicker
import com.obdscanner.screen.CarCompare
import com.obdscanner.screen.CarCard
import com.obdscanner.gm.ScanRanges
import com.obdscanner.car.CarChoice
import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.obd.DtcDb
import com.obdscanner.obd.DtcKind
import com.obdscanner.screen.Action
import com.obdscanner.screen.AllScreen
import com.obdscanner.screen.CodesScreen
import com.obdscanner.screen.DtcHelp
import com.obdscanner.screen.DtcHelpView
import com.obdscanner.screen.DtcRef
import com.obdscanner.screen.FuelScreen
import com.obdscanner.screen.InfoScreen
import com.obdscanner.screen.Licenses
import com.obdscanner.screen.MainScreen
import com.obdscanner.screen.ScreenJson
import com.obdscanner.screen.Support
import com.obdscanner.screen.Terms
import com.obdscanner.tr
import com.obdscanner.transport.MockTransport
import com.obdscanner.transport.Transport
import com.obdscanner.util.IOException
import com.obdscanner.util.nowMs
import com.obdscanner.util.stampText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The app's connection for a browser page: what ObdManager does on the phone — the adapter link with its retries,
 * discovery, modules, polling, the buttons — over Web Serial or the demo car, with the session kept in memory.
 * The page draws [main] / [screen] (JSON, see [ScreenJson]) and redraws on [onChange].
 */
@JsExport
class Scanner(appVersion: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conn = MutableStateFlow<ConnState>(ConnState.Idle)
    private val busy = MutableStateFlow<String?>(null)
    private var tab = Tab.Main
    private var listener: (() -> Unit)? = null
    private var dirty = false

    private val store = LocalStore()

    /** The hand pick (null = by VIN), kept in localStorage like the app keeps it in its settings. */
    private val picked = MutableStateFlow<CarChoice?>(null)

    private val link = CarLink(
        store = store,
        pickedCar = { picked.value },
        tab = { tab },
        onStep = { text -> (conn.value as? ConnState.Connecting)?.let { conn.value = it.copy(step = text) } },
    )

    private var session: MemorySession? = null
    private var obd: Obd? = null
    private var mainJob: Job? = null
    private var opJob: Job? = null

    init {
        AppInfo.versionName = appVersion
        for (f in listOf(link.readings, link.vehicle, link.scan, link.bus, link.fuelWatch, conn, busy, picked)) scope.launch { f.collect { dirty = true } }
        // At most five redraws a second, whatever the poll rate.
        scope.launch {
            while (true) {
                delay(200)
                if (dirty) {
                    dirty = false
                    listener?.invoke()
                }
            }
        }
    }

    /** "ru" or "en": the page's language, the same rule as the app (the browser's language). */
    val language: String get() = if (L10n.ru) "ru" else "en"

    /** The databases: every file of core/data/cars (with obdb/) and of core/data/dtc, as text. */
    fun loadDatabases(cars: Array<String>, dtc: Array<String>) {
        CarDb.load(cars.toList())
        DtcDb.load(dtc.toList())
        picked.value = CarDb.choice(store.getString("car"))
        link.pickLoaded(picked.value?.model)
        dirty = true
    }

    /** Problems the car database loader found (empty when every file parsed). */
    fun databaseProblems(): Array<String> = CarDb.problems.toTypedArray()

    fun onChange(callback: () -> Unit) {
        listener = callback
    }

    /** The screen on top ("Main", "Fuel", "All", "Dtc", "Info"): what is polled faster. */
    fun setTab(name: String) {
        tab = Tab.entries.firstOrNull { it.name == name } ?: Tab.Main
    }

    /**
     * A port from navigator.serial.requestPort(); [label] — how the session names it. A USB adapter's port speed is
     * found by itself and remembered per adapter (vendor:product), as the app does.
     */
    fun connectSerial(port: dynamic, label: String) {
        val info = port.getInfo()
        val key = if (info.usbVendorId != undefined) "usb_baud_${info.usbVendorId}:${info.usbProductId}" else null
        connect { s ->
            WebSerialTransport(port, label, key?.let { store.getInt(it, 0) }?.takeIf { it > 0 }, { s.note(it) }) { baud ->
                key?.let { store.putInt(it, baud) }
            }
        }
    }

    /** A Bluetooth LE adapter from navigator.bluetooth.requestDevice (with [bleServices] as its optionalServices). */
    fun connectBle(device: dynamic, label: String) = connect { s -> WebBluetoothTransport(device, label) { s.note(it) } }

    /** The services ELM327 BLE adapters use: the page asks the browser for them when the user picks the device. */
    fun bleServices(): Array<String> = WebBluetoothTransport.SERVICES

    fun connectDemo() = connect { MockTransport() }

    fun disconnect() {
        mainJob?.cancel()
    }

    /** The connection as JSON: state (idle / connecting / connected / failed), device, step, message, busy. */
    fun state(): String {
        val c = conn.value
        val (state, device, text) = when (c) {
            is ConnState.Idle -> Triple("idle", null, null)
            is ConnState.Connecting -> Triple("connecting", c.device, c.step)
            is ConnState.Connected -> Triple("connected", c.device, link.vehicle.value.step.ifEmpty { null })
            is ConnState.Recording -> Triple("recording", null, null)
            is ConnState.Failed -> Triple("failed", null, c.message)
        }
        val parts = mutableListOf("\"state\":\"$state\"")
        device?.let { parts += "\"device\":${quote(it)}" }
        text?.let { parts += "\"text\":${quote(it)}" }
        busy.value?.let { parts += "\"busy\":${quote(it)}" }
        session?.let { parts += "\"session\":${quote(it.name)}" }
        return "{" + parts.joinToString(",") + "}"
    }

    fun main(): String = ScreenJson.main(MainScreen.build(link.readings.value, link.vehicle.value, link.fuelWatch.value))

    /** The «Как бензин?» checkbox on the first screen, kept in localStorage; it also adds its section to the report. */
    val fuelWatch: Boolean get() = link.fuelWatch.value

    fun setFuelWatch(on: Boolean) = link.setFuelWatch(on)

    /** The checkbox's label and the text behind its «?». */
    fun fuelWatchTexts(): Array<String> = arrayOf(MainScreen.WATCH_LABEL, MainScreen.WATCH_HELP)

    /** "Fuel", "All", "Dtc" or "Info" as JSON blocks. */
    fun screen(name: String): String {
        val r = link.readings.value
        val v = link.vehicle.value
        return ScreenJson.blocks(when (name) {
            "Fuel" -> FuelScreen.build(r, v)
            "All" -> AllScreen.build(r, v)
            "Dtc" -> CodesScreen.build(v, busy.value)
            "Info" -> InfoScreen.build(v)
            else -> emptyList()
        })
    }

    /** The help of a code from a block's "dtc" object (JSON). */
    fun dtcHelp(ref: String): String {
        val o = Json.parseToJsonElement(ref).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val d = DtcRef(
            code = s("code").orEmpty(),
            module = s("module").orEmpty(),
            ftb = o["ftb"]?.jsonPrimitive?.intOrNull,
            gmFtb = o["gmFtb"]?.jsonPrimitive?.booleanOrNull ?: false,
            state = s("state"),
            kind = s("kind")?.let { k -> DtcKind.entries.firstOrNull { it.name == k } },
            label = s("label") ?: s("code").orEmpty(),
        )
        return ScreenJson.dtcHelp(DtcHelp.build(d, link.vehicle.value.dtcFamily))
    }

    /** The license list (data/licenses/licenses.json): load it once, then [licenses] are the screen's blocks. */
    fun loadLicenses(json: String) = Licenses.load(json)

    fun licenses(): String = ScreenJson.blocks(Licenses.build(web = true))

    /** One license as JSON: title, lines (license, copyright, link, changes), text — its file in data/licenses/. */
    fun license(id: String): String = Licenses.document(id)?.let { ScreenJson.license(it) } ?: "null"

    // ---------------------------------------------------------------- the car, the guide, GM scan

    fun carCard(): String = ScreenJson.carCard(CarCard.build(link.vehicle.value, picked.value))

    /** The picker's list: makes ([brand] null) or the make's models, filtered by [filter]; [compareCount] — ticked for comparison. */
    fun pickerRows(brand: String?, filter: String, compareCount: Int): String =
        ScreenJson.picker(CarPicker.rows(brand?.ifEmpty { null }, filter, picked.value), compareCount)

    /** A row's pick: a model id or "brand:Make"; "" — by VIN again. */
    fun pick(id: String) {
        val c = if (id.isEmpty()) null else CarDb.choice(id)
        store.putString("car", c?.id)
        picked.value = c
        link.pickCar(c)
    }

    fun compare(ids: Array<String>): String = ScreenJson.compare(CarCompare.build(ids.toList()))

    fun guide(): String = ScreenJson.guide(Guide.build(link.vehicle.value, picked.value), CarCard.build(link.vehicle.value, picked.value))

    fun gm(onlyWatched: Boolean): String = ScreenJson.gm(GmScreen.build(link.scan.value, link.bus.value, link.readings.value, onlyWatched))

    fun findModules() = launchOp(tr("Поиск модулей", "Module search")) { link.probeModules(it) }

    /** [service] "1A", "21" or "22"; [range] — the index of the $22 range ([GmView.ranges]). */
    fun scanModule(id: String, service: String, range: Int) {
        val m = link.scan.value.modules.firstOrNull { it.id == id } ?: return
        val r = if (service == "22") ScanRanges.ranges22.getOrElse(range) { ScanRanges.ranges22.first() }.second else 0x00..0xFF
        launchOp(tr("Скан ${m.id} $service", "Scan ${m.id} $service")) { link.scanModule(it, m, service, r) }
    }

    fun sniffBus() = launchOp(tr("Прослушка шины", "Bus listening")) { link.sniffBus(it) }

    fun stopOp() {
        opJob?.cancel()
    }

    /** Ticks every found ID that carries data; [on] false — unticks all. */
    fun watchAll(on: Boolean) = link.watchAll(if (on) link.scan.value.hits else emptyList())

    fun toggleWatch(key: String) {
        link.scan.value.hits.firstOrNull { it.key == key }?.let(link::toggleWatch)
    }

    /** The texts of the question before clearing codes: title, text, yes, no. */
    fun clearQuestion(): Array<String> = arrayOf(CodesScreen.CLEAR_TITLE, CodesScreen.CLEAR_TEXT, CodesScreen.CLEAR_YES, CodesScreen.CLEAR_NO)

    /** Whether the terms ([Terms]) are accepted: until then the page shows nothing but them. */
    fun termsAccepted(): Boolean = Terms.accepted(store)

    fun acceptTerms() = Terms.accept(store)

    /** The terms' texts: title, edition, lead, accept, decline, questions, then heading and text of each section. */
    fun terms(): Array<String> =
        (listOf(Terms.TITLE, Terms.EDITION, Terms.LEAD, Terms.ACCEPT, Terms.DECLINE, Terms.QUESTIONS) + Terms.SECTIONS.flatMap { listOf(it.first, it.second) }).toTypedArray()

    /** "Support the project" ([Support]): label, text, link (empty — no line), open, close. */
    fun support(): Array<String> = arrayOf(Support.LABEL, Support.TEXT, Support.URL, Support.OPEN, DtcHelpView.CLOSE)

    /** A screen's button ("READ_DTC", "CLEAR_DTC" — after the page asked —, "READ_ALL_MODULES", "RESCAN", "MODE06"). */
    fun action(name: String) {
        when (Action.entries.firstOrNull { it.name == name }) {
            Action.READ_DTC -> launchOp(tr("Чтение ошибок", "Reading codes")) { link.refreshDtc(it) }
            Action.CLEAR_DTC -> launchOp(tr("Сброс ошибок", "Clearing codes")) { link.clearDtc(it) }
            Action.READ_ALL_MODULES -> launchOp(tr("Ошибки всех блоков", "Codes in all modules")) { link.readAllModulesDtc(it) }
            Action.RESCAN -> launchOp(tr("Повторный опрос", "Rescan")) { link.rediscover(it) }
            Action.MODE06 -> launchOp("Mode 06") { link.refreshMode06(it) }
            // The page opens its own screen.
            Action.LICENSES, Action.TERMS, null -> {}
        }
    }

    /** The last session's name ("2026-10-04_21-00-07"), empty before the first. */
    fun sessionName(): String = session?.name.orEmpty()

    /** The last session's files: name, text, name, text… */
    fun sessionFiles(): Array<String> = session?.let { s ->
        s.flush()
        s.files().flatMap { listOf(it.first, it.second) }.toTypedArray()
    } ?: emptyArray()

    // ---------------------------------------------------------------- as ObdManager.connect on the phone

    /** The phone's motion sensors through the browser, into the session's sensors.csv, as long as it runs. */
    private val motion = WebSensors()

    private fun connect(make: (MemorySession) -> Transport) {
        if (mainJob?.isActive == true) return
        mainJob = scope.launch {
            val s = MemorySession(stampText(nowMs()))
            session = s
            link.session = s
            motion.start(s)
            link.reset(picked.value?.model)
            var elm: Elm327? = null
            var failure: String? = null
            try {
                // Some clones reboot while trying a protocol (the link just closes): reconnect without that protocol.
                val skip = mutableSetOf<Int>()
                var o: Obd
                var name: String
                while (true) {
                    val transport = make(s)
                    name = transport.name
                    conn.value = ConnState.Connecting(name, if (skip.isEmpty()) tr("Подключение к адаптеру…", "Connecting to adapter…") else CarLink.REBOOTING)
                    s.note("connect: $name (browser)")
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
                    // Clones miss the first command sent right after the link comes up.
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
                o.onAdapterReset = { link.adapterReset(o) }
                conn.value = ConnState.Connected(name)
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
                obd = null
                elm?.close()
                motion.stop()
                s.report(tr("Конец сессии", "End of session"), failure ?: tr("отключено пользователем", "disconnected by user"))
                s.close()
                link.stopped()
                busy.value = null
                conn.value = if (failure != null) ConnState.Failed(failure) else ConnState.Idle
            }
        }
    }

    private fun launchOp(title: String, block: suspend (Obd) -> Unit) {
        val o = obd ?: return
        if (opJob?.isActive == true) return
        opJob = scope.launch {
            busy.value = title
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
                busy.value = null
                link.opFinished()
            }
        }
    }

    private fun quote(s: String) = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(s))
}
