package com.obdscanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
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
import com.obdscanner.gm.GmKnown
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.GmModules
import com.obdscanner.gm.GmScanner
import com.obdscanner.gm.ScanHit
import com.obdscanner.obd.DtcCode
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
import com.obdscanner.session.Session
import com.obdscanner.session.SessionStore
import com.obdscanner.transport.BluetoothTransport
import com.obdscanner.transport.MockTransport
import com.obdscanner.transport.Transport
import com.obdscanner.vag.VagModules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val device: String, val step: String) : ConnState
    data class Connected(val device: String) : ConnState
    data class Failed(val message: String) : ConnState
}

enum class Tab(val title: String) {
    Connect(tr("Связь", "Connect")),
    Guide(tr("Как тестировать", "How to test")),
    Main(tr("Главная", "Main")),
    Fuel(tr("Топливо", "Fuel")),
    All(tr("Все данные", "All data")),
    Dtc(tr("Ошибки", "Codes")),
    Info(tr("Инфо", "Info")),
    Gm(tr("GM-скан", "GM scan")),
    Sessions(tr("Сессии", "Sessions"))
}

data class EcuInfo(
    val header: Int,
    val pids01: Set<Int> = emptySet(),
    val info09: Map<Int, String> = emptyMap(),
    val readiness: List<Monitor> = emptyList(),
    val mids06: Set<Int> = emptySet(),
)

data class VehicleInfo(
    val adapter: String = "",
    val adapterDesc: String = "",
    val protocol: String = "",
    val multiPid: Boolean = false,
    val step: String = "",
    val ecus: Map<Int, EcuInfo> = emptyMap(),
    val vin: String? = null,
    /** By VIN; GM-only steps run only for [Make.GM]. */
    val make: Make = Make.OTHER,
    val dtcs: List<DtcCode> = emptyList(),
    val dtcTime: Long = 0,
    val freezeDtc: String? = null,
    val freeze: List<Reading> = emptyList(),
    val mode06: List<TestResult> = emptyList(),
    val mode06Time: Long = 0,
    /** Manufacturer requests that answered (GmKnown on GM + the car database), polled with standard PIDs. */
    val extActive: List<ExtCommand> = emptyList(),
    /** The car: picked by hand or found by the VIN in [CarDb]. */
    val car: CarModel? = null,
    /** Brand by the VIN ("Cadillac"), null before the VIN or when unknown. */
    val brand: String? = null,
    /** \$A9 DTCs of every GM module found on HS-CAN. */
    val gmDtcs: List<GmDtcResult> = emptyList(),
    val gmDtcTime: Long = 0,
    val gmDtcStatus: String = "",
    /** ISO 9141-2 / ISO 14230: standard OBD only. */
    val kline: Boolean = false,
) {
    val supported01: Set<Int> get() = ecus.values.flatMap { it.pids01 }.toSet()
}

data class BusState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val ids: List<BusId> = emptyList(),
)

data class ScanState(
    val modules: List<GmModule> = emptyList(),
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val hits: List<ScanHit> = emptyList(),
    val watched: Set<String> = emptySet(),
)

class ObdManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sessions = SessionStore(File(context.filesDir, "sessions"), File(context.cacheDir, "share"))
    private val prefs = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()
    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    val readings: StateFlow<Map<String, Reading>> = _readings.asStateFlow()
    private val _vehicle = MutableStateFlow(VehicleInfo())
    val vehicle: StateFlow<VehicleInfo> = _vehicle.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private val _bus = MutableStateFlow(BusState())
    val bus: StateFlow<BusState> = _bus.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    /** Name of the running one-off operation (DTC read, scan...), null when idle. */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    val activeTab = MutableStateFlow(Tab.Connect)

    private val _picked = MutableStateFlow(CarDb.choice(prefs.getString("car", null)))
    /** The hand pick (Guide / Connect): a make or a make and model; null = by VIN. */
    val picked: StateFlow<CarChoice?> = _picked.asStateFlow()

    init {
        // The database loads in the background (CarDb.init): take the saved pick once it's there.
        scope.launch {
            CarDb.loaded.first { it }
            val pick = CarDb.choice(prefs.getString("car", null))
            _picked.value = pick
            pick?.model?.let { car -> _vehicle.update { if (it.car == null) it.copy(car = car) else it } }
        }
    }

    /** Hand pick (null = by VIN). While connected it shows at once; its parameters are probed on "Rescan". */
    fun pickCar(pick: CarChoice?) {
        prefs.edit().putString("car", pick?.id).apply()
        _picked.value = pick
        _vehicle.update { v ->
            val sameFamily = v.vin == null || CarDb.brandOf(v.vin)?.family.let { it == null || it == pick?.family }
            v.copy(car = pick?.model?.takeIf { sameFamily } ?: CarDb.detect(v.vin))
        }
    }

    @Volatile var session: Session? = null
        private set
    private var obd: Obd? = null
    private var mainJob: Job? = null
    private var opJob: Job? = null
    /** Poll steps and one-off operations never interleave (they switch CAN headers). */
    private val opMutex = Mutex()

    val lastDevice: String? get() = prefs.getString("last_device", null)

    @SuppressLint("MissingPermission")
    fun connectBluetooth(device: BluetoothDevice) {
        prefs.edit().putString("last_device", device.address).apply()
        val key = "bt_method_" + device.address
        connect { s ->
            BluetoothTransport(device, prefs.getString(key, null), { s.note(it) }) { prefs.edit().putString(key, it).apply() }
        }
    }

    fun connectDemo() = connect { MockTransport() }

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
            _readings.value = emptyMap()
            _vehicle.value = VehicleInfo(car = _picked.value?.model)
            _scan.value = ScanState()
            _bus.value = BusState()
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
                    _conn.value = ConnState.Connecting(name, if (skip.isEmpty()) tr("Подключение к адаптеру…", "Connecting to adapter…") else REBOOTING)
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
                            if (n == tries) throw if (skip.isEmpty()) ex else IOException(REPLUG, ex)
                            s.note("reconnect $n failed: ${ex.message}")
                            delay(2000)
                        }
                    }
                    ObdService.start(context, name)
                    // Clones miss the first command sent right after the link comes up (AndrOBD #233: Car Scanner waits ~500 ms).
                    delay(500)
                    try {
                        initAdapter(o, name, skip)
                        break
                    } catch (ex: IOException) {
                        val p = probingProtocol
                        if (e.alive || p == null) throw ex
                        if (skip.size >= 3) throw IOException(REPLUG, ex)
                        skip += p
                        s.note("adapter dropped the link while trying protocol $p — reconnecting without it")
                        e.close()
                        delay(3000)
                    }
                }
                obd = o
                // Mid-session reset (LV RESET when cranking, ERR94, a clone's banner): the adapter is back to
                // defaults and protocol auto — the search that dropped the Polo's clone. Put our settings back.
                o.onAdapterReset = {
                    s.note("adapter reset itself (LV RESET / ERR94 / boot banner) — settings and $protocolCmd again")
                    baseSetup(o)
                    o.at(o.adaptiveTiming)
                    o.at(protocolCmd)
                }
                _conn.value = ConnState.Connected(name)
                opMutex.withLock { discover(o) }
                if (o.kline) s.note("K-line: standard OBD only, module search and DTCs of all modules skipped")
                else opMutex.withLock { autoModules(o) }
                pollLoop(o)
            } catch (e: CancellationException) {
                s.note("disconnect requested")
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
                s.note("ERROR: ${e.stackTraceToString()}")
            } finally {
                opJob?.cancel()
                obd = null
                elm?.close()
                s.report(tr("Конец сессии", "End of session"), failure ?: tr("отключено пользователем", "disconnected by user"))
                s.close()
                ObdService.stop(context)
                _scan.update { it.copy(running = false) }
                _bus.update { it.copy(running = false) }
                _busy.value = null
                _conn.value = if (failure != null) ConnState.Failed(failure) else ConnState.Idle
            }
        }
    }

    private fun step(text: String) {
        session?.note("step: $text")
        _vehicle.update { it.copy(step = text) }
        (_conn.value as? ConnState.Connecting)?.let { _conn.value = it.copy(step = text) }
    }

    // ---------------------------------------------------------------- init

    /** Protocol that worked at init ("ATSP6") — to come back quickly after a re-init. */
    private var protocolCmd = "ATSP0"

    private suspend fun baseSetup(o: Obd) {
        for (c in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATCAF1", "ATAT1")) o.at(c)
    }

    /** Protocol being tried right now — to skip it if the adapter drops the link. */
    @Volatile private var probingProtocol: Int? = null

    /**
     * Finds the protocol by trying them one at a time: the last one that worked, CAN, K-line, the rest.
     * Never the adapter's own search (ATSP0): on a Polo (KWP 5-baud) the ELM327 v1.5 clone dropped the
     * Bluetooth link ~9.5 s into it every time, 6 of 6, and was unreachable for minutes after.
     * K-line gets a pause between inits and a second round: on the same Polo KWP 5-baud failed right
     * after the fast-init and ISO 9141 attempts once and answered with the same order another time.
     */
    private suspend fun initAdapter(o: Obd, name: String, skip: Set<Int> = emptySet()) {
        step(tr("Сброс адаптера (ATZ)…", "Resetting adapter (ATZ)…"))
        o.at("ATZ", 5000)
        delay(300)
        baseSetup(o)
        val ver = o.at("ATI").lines.lastOrNull().orEmpty()
        val desc = o.at("AT@1").lines.firstOrNull().orEmpty()
        val saved = prefs.getInt("last_protocol_$name", prefs.getInt("last_protocol", 0)).takeIf { it in PROTOCOLS }
        // The picked car's protocol goes first: no probing of the others (the Polo's clone drops the link on some).
        val carProto = _picked.value?.model?.protocol?.takeIf { it in PROTOCOLS }
        val order = ((listOfNotNull(carProto, saved) + PROTOCOL_ORDER).distinct() + KLINE_RETRY).filter { it !in skip }
        var first: com.obdscanner.elm.CanReply? = null
        var errors = emptyList<String>()
        var lastKline = false
        for ((i, p) in order.withIndex()) {
            val kline = p in 3..5
            // A K-line ECU needs the bus idle for a while after a failed init before it answers the next one.
            if (kline && lastKline) {
                o.at("ATPC")
                delay(KLINE_GAP)
            }
            lastKline = kline
            probingProtocol = p
            step(tr("Протокол ${PROTOCOLS[p]} (${i + 1} из ${order.size})…", "Protocol ${PROTOCOLS[p]} (${i + 1} of ${order.size})…"))
            o.at("ATSP$p")
            // Some clones (VLinker…) reset echo/spaces/headers/CAF on a protocol change (ddt4all notes this).
            baseSetup(o)
            val r = o.request("0100", when (p) { in 6..9 -> 4000L; in 3..5 -> 10000L; else -> 3000L })
            if (!r.noData) { first = r; break }
            errors = r.errors
            session?.note("protocol $p: no answer (${r.errors.joinToString().ifEmpty { tr("пусто", "empty") }})")
        }
        probingProtocol = null
        if (first == null) {
            throw IOException(
                tr("ЭБУ не отвечает (${errors.joinToString().ifEmpty { "нет ответа" }}). Зажигание включено?",
                    "ECU not responding (${errors.joinToString().ifEmpty { "no answer" }}). Is the ignition on?"),
            )
        }
        val dpn = o.at("ATDPN").lines.firstOrNull().orEmpty()
        val proto = dpn.trimStart('A', 'a').toIntOrNull(16) ?: 0
        o.headerChars = if (proto == 7 || proto == 9) 8 else 3
        o.kline = proto in 3..5
        if (proto != 0) {
            protocolCmd = "ATSP%X".format(proto)
            prefs.edit().putInt("last_protocol_$name", proto).apply()
        }
        val dp = o.at("ATDP").lines.firstOrNull().orEmpty()
        if (proto !in 3..9) session?.note("WARNING: protocol $dpn is neither CAN nor K-line — parsing may fail")
        val aggressive = o.at("ATAT2").isOk
        if (aggressive) o.adaptiveTiming = "ATAT2"
        o.broadcast()
        step(tr("Проверка мульти-PID запросов…", "Checking multi-PID requests…"))
        val multi = o.request("010C0D").messages.any { m ->
            m.data.size >= 6 && m.data[0] == 0x41 && m.data[1] == 0x0C && m.data[4] == 0x0D
        }
        val rv = o.at("ATRV").lines.firstOrNull().orEmpty()
        _vehicle.update { it.copy(adapter = ver, adapterDesc = desc, protocol = "$dp ($dpn)", multiPid = multi, kline = o.kline) }
        val yes = tr("да", "yes")
        val no = tr("нет", "no")
        session?.report(
            tr("Адаптер", "Adapter"),
            tr("Приложение: ${BuildConfig.VERSION_NAME}\nУстройство: $name\nВерсия: $ver\nОписание: $desc\nПротокол: $dp ($dpn)\n",
                "App: ${BuildConfig.VERSION_NAME}\nDevice: $name\nVersion: $ver\nDescription: $desc\nProtocol: $dp ($dpn)\n") +
                tr("Мульти-PID: ${if (multi) yes else no}\nATAT2: ${if (aggressive) yes else no}\nНапряжение (ATRV): $rv",
                    "Multi-PID: ${if (multi) yes else no}\nATAT2: ${if (aggressive) yes else no}\nVoltage (ATRV): $rv"),
        )
    }

    // ---------------------------------------------------------------- discovery

    private suspend fun discover(o: Obd, full: Boolean = false) {
        step(tr("Поддерживаемые PID Mode 01…", "Supported PIDs (Mode 01)…"))
        val pids = mutableMapOf<Int, MutableSet<Int>>()
        var base = 0
        while (base <= 0xE0) {
            val r = o.request("01%02X".format(base), 3000)
            var more = false
            for (m in r.messages) {
                if (m.data.size < 6 || m.data[0] != 0x41 || m.data[1] != base) continue
                val set = pids.getOrPut(m.header) { mutableSetOf() }
                for (i in 0 until 32) if ((m.data[2 + i / 8] shr (7 - i % 8)) and 1 == 1) set += base + i + 1
                if (base + 0x20 in set) more = true
            }
            if (!more) break
            base += 0x20
        }
        _vehicle.update { v -> v.copy(ecus = pids.mapValues { (h, p) -> EcuInfo(h, p) }) }
        session?.report(tr("Поддерживаемые PID (Mode 01)", "Supported PIDs (Mode 01)"), pids.entries.joinToString("\n\n") { (h, p) ->
            tr("${ecuName(h)} [%03X] — ${p.count { !Pids.isBitmask(it) }} шт.\n", "${ecuName(h)} [%03X] — ${p.count { !Pids.isBitmask(it) }} PIDs\n").format(h) +
                p.filter { !Pids.isBitmask(it) }.sorted().joinToString("\n") { "  01 %02X  %s".format(it, Pids.name(it)) }
        })

        step(tr("Статичные параметры и готовность…", "Static data and readiness…"))
        val statics = (pids.values.flatten().toSet()).filter { Pids.byPid[it]?.static == true || it == 0x01 }
        for (pid in statics.sorted()) {
            val r = o.request("01%02X".format(pid), 2000)
            val out = mutableListOf<Reading>()
            for (m in r.messages) {
                if (m.data.size < 3 || m.data[0] != 0x41 || m.data[1] != pid) continue
                val d = m.data.copyOfRange(2, m.data.size)
                out += Pids.decode(m.header, "01", pid, d)
                if (pid == 0x01) {
                    val mon = Readiness.monitors(d)
                    _vehicle.update { v -> v.copy(ecus = v.ecus.mapValues { (h, e) -> if (h == m.header) e.copy(readiness = mon) else e }) }
                }
            }
            publish(out)
        }

        step(tr("Информация об автомобиле (Mode 09)…", "Vehicle info (Mode 09)…"))
        readMode09(o)
        step(tr("Коды ошибок…", "Trouble codes…"))
        readDtcs(o)
        step(tr("Стоп-кадр…", "Freeze frame…"))
        readFreezeFrame(o)
        step(tr("Бортовые тесты (Mode 06)…", "On-board tests (Mode 06)…"))
        readMode06(o, onlyMisfire = false)
        if (!o.kline) {
            step(tr("Параметры производителя…", "Manufacturer parameters…"))
            probeExt(o, full)
        } else {
            session?.note("manufacturer parameters skipped: K-line")
        }
        step("")
    }

    private suspend fun readMode09(o: Obd) {
        val types = mutableMapOf<Int, MutableSet<Int>>()
        val r = o.request("0900", 2000)
        for (m in r.messages) {
            if (m.data.size < 6 || m.data[0] != 0x49 || m.data[1] != 0) continue
            val set = types.getOrPut(m.header) { mutableSetOf() }
            // K-line puts the message count before the bitmask: 49 00 01 xx xx xx xx.
            val at = if (o.kline && m.data.size >= 7) 3 else 2
            for (i in 0 until 32) if ((m.data[at + i / 8] shr (7 - i % 8)) and 1 == 1) set += i + 1
        }
        val wanted = types.values.flatten().toSet().ifEmpty { setOf(0x02, 0x04, 0x0A) }
        val info = mutableMapOf<Int, MutableMap<Int, String>>()
        for (t in wanted.filter { it in listOf(0x02, 0x04, 0x06, 0x08, 0x0A, 0x0B, 0x0D) }.sorted()) {
            val rr = o.request("09%02X".format(t), 4000)
            var msgs = rr.messages
            // ECM and TCM both stream long CALID lists at once and the clone overflows (BUFFER FULL,
            // lost frames): ask each ECU on its own id instead of taking the truncated text.
            if (rr.errors.isNotEmpty() && o.headerChars == 3 && !o.kline) {
                val heads = (types.keys + rr.messages.map { it.header }).filter { it in 0x7E8..0x7EF }.toSet()
                msgs = heads.sorted().mapNotNull { h ->
                    o.target(h - 8, h)
                    val p = o.request("09%02X".format(t), 4000)
                    p.from(h).firstOrNull()?.takeIf { p.errors.isEmpty() } ?: rr.from(h).firstOrNull()
                }
                o.broadcast()
            }
            for (m in msgs) {
                if (m.data.size < 3 || m.data[0] != 0x49 || m.data[1] != t) continue
                info.getOrPut(m.header) { mutableMapOf() }[t] = Mode09.decode(m.data)
            }
        }
        val vin = info.values.firstNotNullOfOrNull { it[0x02] }
        val brand = CarDb.brandOf(vin)
        val picked = _picked.value
        // A hand-picked car stays while the VIN agrees with its family (or there is no VIN); otherwise the VIN wins.
        val make = Make.fromVin(vin).takeIf { it != Make.OTHER } ?: picked?.let { Make.of(it.family) } ?: Make.OTHER
        val car = picked?.model?.takeIf { brand == null || it.family == brand.family } ?: CarDb.detect(vin)
        _vehicle.update { v ->
            val ecus = v.ecus.toMutableMap()
            for ((h, i) in info) ecus[h] = (ecus[h] ?: EcuInfo(h)).copy(info09 = i)
            v.copy(vin = vin, make = make, ecus = ecus, car = car, brand = brand?.name)
        }
        session?.report(tr("Машина", "Car"), listOf(
            tr("Марка (по VIN): ", "Make (by VIN): ") + (brand?.name ?: tr("не определена", "unknown")) + " — ${make.title}",
            tr("Год (по VIN): ", "Model year (by VIN): ") + (vin?.let { CarDb.modelYear(it.trim().uppercase()) }?.toString() ?: "—"),
            tr("Выбрано вручную: ", "Picked by hand: ") + (picked?.let { it.model?.title ?: tr("${it.brand} (только марка)", "${it.brand} (make only)") } ?: tr("нет", "nothing")),
            tr("Модель: ", "Model: ") + (car?.title?.let { it + if (car == picked?.model) tr(" (выбрана вручную)", " (picked by hand)") else tr(" (по VIN)", " (by VIN)") } ?: tr("не определена", "unknown")),
            tr("Двигатель: ", "Engine: ") + (car?.engines?.joinToString("; ") { it.title }?.ifEmpty { null } ?: "—"),
        ).joinToString("\n"))
        session?.report("Mode 09", info.entries.joinToString("\n\n") { (h, i) ->
            "${ecuName(h)} [%03X]\n".format(h) + i.entries.joinToString("\n") { (t, s) -> "  ${Mode09.name(t)}: ${s.replace("\n", "\n    ")}" }
        }.ifEmpty { tr("нет ответа", "no answer") })
    }

    private suspend fun readDtcs(o: Obd) {
        val all = mutableListOf<DtcCode>()
        for ((svc, kind) in listOf("03" to DtcKind.STORED, "07" to DtcKind.PENDING, "0A" to DtcKind.PERMANENT)) {
            val r = o.request(svc, 3000)
            val resp = svc.toInt(16) + 0x40
            for (m in r.messages) if (m.service == resp) all += Dtc.parse(m.data, m.header, kind)
        }
        _vehicle.update { it.copy(dtcs = all, dtcTime = System.currentTimeMillis()) }
        session?.report(tr("Коды ошибок", "Trouble codes"), all.joinToString("\n") { "${it.kind.title}: ${it.code} [%03X] ${it.description}".format(it.ecu) }
            .ifEmpty { tr("нет", "none") })
    }

    private suspend fun readFreezeFrame(o: Obd) {
        val r = o.request("020200", 2000)
        val m = r.messages.firstOrNull { it.data.size >= 5 && it.data[0] == 0x42 && it.data[1] == 0x02 }
        if (m == null || (m.data[3] == 0 && m.data[4] == 0)) {
            _vehicle.update { it.copy(freezeDtc = null, freeze = emptyList()) }
            session?.report(tr("Стоп-кадр", "Freeze frame"), tr("нет", "none"))
            return
        }
        val dtc = Dtc.decode(m.data[3], m.data[4])
        val out = mutableListOf<Reading>()
        val ecuPids = _vehicle.value.ecus[m.header]?.pids01.orEmpty()
            .filter { !Pids.isBitmask(it) && it != 0x01 && it != 0x02 && Pids.byPid[it]?.static != true }
            .sorted().take(48)
        for (pid in ecuPids) {
            val rr = o.request("02%02X00".format(pid), 1500)
            val mm = rr.from(m.header).firstOrNull { it.data.size > 3 && it.data[0] == 0x42 && it.data[1] == pid } ?: continue
            out += Pids.decode(m.header, "02", pid, mm.data.copyOfRange(3, mm.data.size))
        }
        _vehicle.update { it.copy(freezeDtc = dtc, freeze = out) }
        session?.report(tr("Стоп-кадр ($dtc)", "Freeze frame ($dtc)"), out.joinToString("\n") { "  ${it.name}: ${it.display()} ${it.unit}" })
    }

    private suspend fun readMode06(o: Obd, onlyMisfire: Boolean) {
        if (o.kline) {
            if (!onlyMisfire) readMode06Kline(o)
            return
        }
        val v = _vehicle.value
        var mids = v.ecus.mapValues { it.value.mids06 }
        if (mids.values.all { it.isEmpty() }) {
            val found = mutableMapOf<Int, MutableSet<Int>>()
            var base = 0
            while (base <= 0xE0) {
                val r = o.request("06%02X".format(base), 2000)
                var more = false
                for (m in r.messages) {
                    if (m.data.size < 6 || m.data[0] != 0x46 || m.data[1] != base) continue
                    val set = found.getOrPut(m.header) { mutableSetOf() }
                    for (i in 0 until 32) if ((m.data[2 + i / 8] shr (7 - i % 8)) and 1 == 1) set += base + i + 1
                    if (base + 0x20 in set) more = true
                }
                if (!more) break
                base += 0x20
            }
            mids = found
            _vehicle.update { vv ->
                val ecus = vv.ecus.toMutableMap()
                for ((h, s) in found) ecus[h] = (ecus[h] ?: EcuInfo(h)).copy(mids06 = s)
                vv.copy(ecus = ecus)
            }
        }
        val targets = mids.values.flatten().toSet().filter { !Pids.isBitmask(it) }
            .filter { !onlyMisfire || it in 0xA1..0xAD }.sorted()
        if (targets.isEmpty()) return
        val results = mutableListOf<TestResult>()
        for (mid in targets) {
            val r = o.request("06%02X".format(mid), 2000)
            for (m in r.messages) if (m.service == 0x46) results += Mode06.parse(m.data, m.header)
        }
        val now = System.currentTimeMillis()
        _vehicle.update { vv ->
            val merged = if (onlyMisfire) vv.mode06.filter { it.mid !in 0xA1..0xAD } + results else results
            vv.copy(mode06 = merged.sortedWith(compareBy({ it.ecu }, { it.mid }, { it.tid })), mode06Time = now)
        }
        publish(results.filter { it.misfireCylinder != null || it.mid == 0xA1 }.map { t ->
            Reading(Reading.key(t.ecu, "06.%02X.%02X".format(t.mid, t.tid)), t.ecu, "${t.midName}: ${t.tidName}",
                t.value, null, t.unit, 0)
        })
        if (!onlyMisfire) session?.report("Mode 06", results.joinToString("\n") {
            tr("[%03X] %-28s %-36s %s %s (мин %s, макс %s) %s", "[%03X] %-28s %-36s %s %s (min %s, max %s) %s").format(
                it.ecu, it.midName, it.tidName, Reading.fmt(it.value, 3), it.unit,
                Reading.fmt(it.min, 3), Reading.fmt(it.max, 3), it.status)
        })
    }

    /**
     * Mode 06 before CAN (ISO 9141/14230) is a different format: test id + component id + value and
     * one limit, with the meaning defined by the manufacturer. Recorded raw into report.txt.
     */
    private suspend fun readMode06Kline(o: Obd) {
        val r = o.request("0600", 3000)
        val m = r.messages.firstOrNull { it.service == 0x46 && it.data.size >= 6 && it.data[1] == 0 }
        val tids = if (m != null) (0 until 32).filter { (m.data[2 + it / 8] shr (7 - it % 8)) and 1 == 1 }.map { it + 1 }
            else (0x01..0x10).toList()
        val out = mutableListOf<String>()
        for (tid in tids.filter { it % 0x20 != 0 }) {
            val rr = o.request("06%02X".format(tid), 2000)
            for (mm in rr.messages) if (mm.service == 0x46) out += "[%03X] TID %02X: %s".format(mm.header, tid, mm.hex())
        }
        session?.report(tr("Mode 06 (K-line, без расшифровки)", "Mode 06 (K-line, raw)"), out.joinToString("\n").ifEmpty { tr("нет ответа", "no answer") })
        _vehicle.update { it.copy(mode06Time = System.currentTimeMillis()) }
    }

    /** What to probe: GmKnown on GM, then the car database for the make's family and the car's model. */
    private fun extCandidates(): List<ExtCommand> {
        val v = _vehicle.value
        val fam = CarDb.family(v.car?.family ?: v.make.id)
        val car = v.car?.takeIf { it.family == fam?.id }
        // A gas-converted car still has a petrol ECU.
        val db = fam?.probeOrder(car, fuelOf(car)?.let { if (it == "lpg") "petrol" else it }).orEmpty()
        val gm = if (v.make == Make.GM) GmKnown.commands else emptyList()
        val cyl = cylindersOf(car)?.first
        // Capped: a big family would mean hundreds of requests on the first connection.
        return (gm + db).distinctBy { it.key }.mapNotNull { it.forCylinders(cyl) }.take(MAX_PROBE)
    }

    /**
     * Cylinder count and where it comes from: the engine ECU's Mode 06 misfire tests (MID A2 = cylinder 1
     * … AD = 12, one per cylinder it has), else the car's engines when they all agree. Null — unknown.
     */
    private fun cylindersOf(car: CarModel?): Pair<Int, String>? {
        val byMode06 = _vehicle.value.ecus.values.maxOfOrNull { e -> e.mids06.filter { it in 0xA2..0xAD }.maxOrNull()?.minus(0xA1) ?: 0 } ?: 0
        if (byMode06 > 0) return byMode06 to "Mode 06"
        val byModel = car?.engines?.map { it.cyl }?.distinct()?.singleOrNull() ?: return null
        return byModel to tr("по моторам модели", "by the model's engines")
    }

    /**
     * "petrol" / "diesel" by the ECU (PID 51) or, without it, by the car's engines when they all agree;
     * null when unknown — then fuel-specific requests (a DPF DID means misfires on a petrol ECU) aren't sent.
     */
    private fun fuelOf(car: CarModel?): String? {
        val pid = _readings.value.pick("01.51")?.text
        return when {
            pid == Pids.fuelType(4) -> "diesel"
            pid == Pids.fuelType(1) -> "petrol"
            pid == Pids.fuelType(5) -> "lpg"
            else -> car?.engines?.mapNotNull { it.fuel }?.distinct()?.singleOrNull()?.takeIf { car.engines.all { e -> e.fuel != null } }
        }
    }

    /**
     * Asks every candidate request once; the ones that answer get polled. What answered is saved per VIN,
     * so the next connection asks only those ([full] — everything again, "Rescan"). A module that has
     * answered nothing after [SILENT_SKIP] requests is not asked the rest (no timeout per request).
     */
    private suspend fun probeExt(o: Obd, full: Boolean) {
        val all = extCandidates()
        val vin = _vehicle.value.vin
        // Per app version too: a new build may bring new requests in the database.
        val prefKey = "ext_ok_${BuildConfig.VERSION_CODE}_" + vin.orEmpty()
        val saved = if (vin != null) prefs.getStringSet(prefKey, null) else null
        val list = if (!full && saved != null) all.filter { it.key in saved } else all
        val sc = GmScanner(o) { session?.note(it) }
        val active = mutableListOf<ExtCommand>()
        val silent = mutableMapOf<Int, Int>()
        // A module that answered once (data or a refusal) stays: some ignore unknown DIDs instead of refusing them.
        val alive = mutableSetOf<Int>()
        val lines = mutableListOf<String>()
        for ((i, c) in list.sortedBy { it.req }.withIndex()) {
            if (c.req !in alive && (silent[c.req] ?: 0) >= SILENT_SKIP) {
                lines += "  ${c.key} — " + tr("блок молчит, пропущено", "module silent, skipped")
                continue
            }
            if (i % 10 == 0) step(tr("Параметры производителя ${i + 1} из ${list.size}…", "Manufacturer parameters ${i + 1} of ${list.size}…"))
            check(c.service in CarDb.READ_SERVICES) { "not a read request: ${c.key}" }
            o.target(c.req, c.resp)
            val r = sc.read(GmModule(c.req, c.resp, "", ""), c.service, c.did)
            if (r == null) silent[c.req] = (silent[c.req] ?: 0) + 1 else alive += c.req
            val data = r?.first
            if (data != null) {
                active += c
                val out = extReadings(c, data)
                publish(out)
                lines += "  ${c.key} [${c.source}] — " + out.joinToString("; ") { "${it.name} = ${it.display()} ${it.unit}".trim() }
            } else {
                lines += "  ${c.key} [${c.source}] ${c.signals.first().name}${if (c.signals.size > 1) " +${c.signals.size - 1}" else ""} — " +
                    if (r == null) tr("нет ответа", "no answer") else "NRC %02X".format(r.second)
            }
        }
        o.broadcast()
        if (vin != null && (full || saved == null)) prefs.edit().putStringSet(prefKey, active.map { it.key }.toSet()).apply()
        _vehicle.update { it.copy(extActive = active) }
        val cyl = cylindersOf(_vehicle.value.car)?.let { (n, by) -> tr("Цилиндров: $n ($by)", "Cylinders: $n ($by)") }
            ?: tr("Цилиндров: неизвестно — отбора по ним нет", "Cylinders: unknown — no filtering by them")
        session?.report(tr("Параметры производителя", "Manufacturer parameters"),
            "$cyl\n" + tr("Проверено ${list.size} из ${all.size} запросов", "Probed ${list.size} of ${all.size} requests") +
                (if (list.size < all.size) tr(" (только ответившие в прошлый раз; все — «Повторный опрос»)", " (only those that answered last time; all — \"Rescan\")") else "") +
                tr(", ответили ${active.size}\n", ", answered ${active.size}\n") + lines.joinToString("\n"))
    }

    /** Reads [EcuIdent] of one module; returns report lines, hits go to the scan list and scan.csv. */
    private suspend fun identifyGeneric(o: Obd, sc: GmScanner, mod: GmModule): List<String> {
        o.target(mod.req, mod.resp)
        val out = mutableListOf<String>()
        val skip = mutableSetOf<String>()
        val silent = mutableMapOf<String, Int>()
        for (item in EcuIdent.ALL) {
            if (item.service in skip) continue
            val r = sc.read(mod, item.service, item.did)
            if (r == null) {
                if (silent.merge(item.service, 1, Int::plus)!! >= 3) skip += item.service
                continue
            }
            silent[item.service] = 0
            val data = r.first
            if (data == null) {
                if (r.second == 0x11) skip += item.service
                continue
            }
            val hit = ScanHit(mod.req, mod.resp, item.service, item.did, data)
            session?.scanHit(hit.req, hit.resp, hit.service, hit.didHex, hit.data)
            _scan.update { st -> st.copy(hits = st.hits.filter { it.key != hit.key } + hit) }
            out += "  %s %s  %-44s %s".format(item.service, hit.didHex, item.name,
                if (hit.looksLikeText) "«${hit.ascii.trim('·', ' ')}»  (${hit.hex})" else hit.hex)
        }
        return out
    }

    /** The values of one manufacturer answer; a value that doesn't decode shows the raw bytes. */
    private fun extReadings(c: ExtCommand, data: IntArray): List<Reading> = c.signals.map { s ->
        val (v, text) = c.code?.let { f -> runCatching { f(data) }.getOrNull() to null } ?: (s.fmt.decode(data) ?: (null to null))
        Reading(c.readingKey(s), c.req, s.displayName, v, text ?: if (v == null) Pids.hex(data) else null, s.unit, s.decimals, role = s.role)
    }

    // ---------------------------------------------------------------- polling

    private var silentCycles = 0

    /**
     * The K-line ECU stopped answering (engine cranked, ignition cycled): the adapter keeps the old
     * session, so close it (ATPC), let the bus rest and init again with the known protocol (as AndrOBD does).
     */
    private suspend fun reinitKline(o: Obd) {
        session?.note("K-line: ECU silent for 3 cycles — ATPC and a new bus init")
        o.at("ATPC")
        baseSetup(o)
        delay(KLINE_GAP)
        val r = o.request("0100", 10000)
        session?.note("K-line re-init: ${if (r.noData) "no answer (${r.errors.joinToString()})" else "OK"}")
    }

    private suspend fun pollLoop(o: Obd) {
        silentCycles = 0
        var lastRv = 0L
        var lastFlush = 0L
        var lastMisfire = System.currentTimeMillis()
        var lastWatch = 0L
        while (true) {
            opMutex.withLock {
                val tab = activeTab.value
                val supported = _vehicle.value.supported01.filter { !Pids.isBitmask(it) && it != 0x02 && Pids.byPid[it]?.static != true }
                val onScreen = when (tab) {
                    Tab.Main -> MAIN_PIDS
                    Tab.Fuel -> FUEL_PIDS
                    else -> emptyList()
                }
                val pids = PollRate.due(supported, lastPoll, System.currentTimeMillis(), Int.MAX_VALUE, { "01.%02X".format(it) }) {
                    PollRate.of01(it).let { p -> if (it in onScreen) PollRate.onScreen(p) else p }
                }
                val answers = pollPids(o, pids)
                silentCycles = if (answers == 0) silentCycles + 1 else 0
                if (o.kline && silentCycles >= 3) {
                    reinitKline(o)
                    silentCycles = 0
                }
                publishDerived()

                val now = System.currentTimeMillis()
                if (now - lastRv > 5000) {
                    lastRv = now
                    val t = o.at("ATRV").lines.firstOrNull().orEmpty()
                    t.trimEnd('V', 'v').toDoubleOrNull()?.let {
                        publish(listOf(Reading(Reading.key(0, "ATRV"), 0, tr("Напряжение (адаптер)", "Voltage (adapter)"), it, null, tr("В", "V"), 1)))
                    }
                }
                if (tab == Tab.Fuel && !o.kline && now - lastMisfire > 15000) {
                    lastMisfire = now
                    readMode06(o, onlyMisfire = true)
                }
                val ext = _vehicle.value.extActive
                if (ext.isNotEmpty() && tab in listOf(Tab.Main, Tab.Fuel, Tab.All)) pollExt(o, ext, tab)
                val watched = _scan.value.watched
                if (watched.isNotEmpty() && (tab == Tab.Gm || now - lastWatch > 3000)) {
                    lastWatch = now
                    pollWatched(o, watched)
                }
                if (now - lastFlush > 2000) {
                    lastFlush = now
                    session?.flush()
                }
            }
            yield()
        }
    }

    /** Returns how many answers came, -1 if there was nothing to ask. */
    private suspend fun pollPids(o: Obd, pids: List<Int>): Int {
        if (pids.isEmpty()) {
            delay(200)
            return -1
        }
        val multi = _vehicle.value.multiPid
        val batchable = pids.filter { multi && (Pids.byPid[it]?.len ?: -1) > 0 }
        val single = pids - batchable.toSet()
        val out = mutableListOf<Reading>()
        for (chunk in batchable.chunked(6)) {
            val r = o.request("01" + chunk.joinToString("") { "%02X".format(it) }, POLL_TIMEOUT)
            for (m in r.messages) {
                if (m.service != 0x41) continue
                for ((pid, d) in Pids.splitMulti(m.data, chunk)) out += Pids.decode(m.header, "01", pid, d)
            }
        }
        for (pid in single) {
            val r = o.request("01%02X".format(pid), POLL_TIMEOUT)
            for (m in r.messages) {
                if (m.service != 0x41 || m.data.size < 3 || m.data[1] != pid) continue
                out += Pids.decode(m.header, "01", pid, m.data.copyOfRange(2, m.data.size))
            }
        }
        // A V6 has two banks: the "bank 3/4" halves of PIDs 55–58 are filler bytes.
        val twoBanks = 0x13 in _vehicle.value.supported01
        publish(if (twoBanks) out.filterNot { it.source.matches(Regex("""01\.5[5-8]\.B""")) } else out)
        return out.size
    }

    /** When each Mode 01 PID / GM parameter was last asked for, see [PollRate.due]. */
    private val lastPoll = mutableMapOf<String, Long>()

    /**
     * A few manufacturer requests per poll cycle (each one is a separate request) so standard PIDs keep
     * their pace; which ones — by [ExtCommand.periodMs], the current screen's slow ones lifted to MEDIUM.
     */
    private suspend fun pollExt(o: Obd, list: List<ExtCommand>, tab: Tab) {
        val group = when (tab) { Tab.Main -> "main"; Tab.Fuel -> "fuel"; else -> "" }
        val batch = PollRate.due(list, lastPoll, System.currentTimeMillis(), EXT_PER_CYCLE, { it.key }) {
            if (it.group == group) PollRate.onScreen(it.periodMs) else it.periodMs
        }.sortedBy { it.req }
        if (batch.isEmpty()) return
        val sc = GmScanner(o) { session?.note(it) }
        val out = batch.flatMap { c -> sc.readRaw(c.req, c.resp, c.service, c.did)?.let { extReadings(c, it) }.orEmpty() }
        o.broadcast()
        publish(out)
    }

    private suspend fun pollWatched(o: Obd, watched: Set<String>) {
        val sc = GmScanner(o) { session?.note(it) }
        val hits = _scan.value.hits.filter { it.key in watched }
        val out = hits.mapNotNull { h ->
            sc.readRaw(h.req, h.resp, h.service, h.did)?.let { d ->
                val v = when {
                    d.isEmpty() -> null
                    d.size == 1 -> d[0].toDouble()
                    else -> (d[0] * 256 + d[1]).toDouble()
                }
                Reading(h.key, h.req, "%03X %s %s".format(h.req, h.service, h.didHex), v, Pids.hex(d), "raw", 0)
            }
        }
        o.broadcast()
        publish(out)
    }

    private fun publishDerived() {
        val r = _readings.value
        val out = mutableListOf<Reading>()
        fun add(src: String, name: String, v: Double?, unit: String, dec: Int = 1) {
            if (v != null && !v.isNaN()) out += Reading(Reading.key(0, src), 0, name, v, null, unit, dec)
        }
        val st1 = r.pick("01.06")?.value
        val lt1 = r.pick("01.07")?.value
        val st2 = r.pick("01.08")?.value
        val lt2 = r.pick("01.09")?.value
        if (st1 != null && lt1 != null) add("calc.trim1", tr("Суммарная коррекция Б1", "Total fuel trim B1"), st1 + lt1, "%")
        if (st2 != null && lt2 != null) add("calc.trim2", tr("Суммарная коррекция Б2", "Total fuel trim B2"), st2 + lt2, "%")
        if (st1 != null && lt1 != null && st2 != null && lt2 != null) add("calc.trimDiff", tr("Разница банков (Б1−Б2)", "Bank difference (B1−B2)"), st1 + lt1 - st2 - lt2, "%")
        val speed = r.pick("01.0D")?.value
        // Engine size for the MAP estimate: only when every engine of the model agrees.
        val car = _vehicle.value.car
        val liters = car?.engines?.mapNotNull { it.liters }?.distinct()?.singleOrNull()
        val cyl = car?.engines?.mapNotNull { it.cyl }?.distinct()?.singleOrNull()
        val rate = FuelRate.compute(
            fuel = fuelOf(car),
            ecuLph = r.pick("01.5E")?.value,
            fuelGs = r.pick("01.9D.E")?.value,
            mgStroke = r.pick("01.A2")?.value,
            maf = r.pick("01.10")?.value,
            lambda = r.pick("01.44")?.value?.takeIf { it in 0.5..2.0 } ?: 1.0,
            map = r.pick("01.0B")?.value,
            iat = r.pick("01.0F")?.value,
            rpm = r.pick("01.0C")?.value,
            liters = liters,
            cyl = cyl,
        )
        val lph = rate?.lph
        add("calc.lph", tr("Расход топлива", "Fuel consumption") + rate?.how.orEmpty(), lph, tr("л/ч", "L/h"), 2)
        if (lph != null && speed != null && speed >= 10) add("calc.l100", tr("Мгновенный расход", "Instant fuel economy"), lph / speed * 100, tr("л/100км", "L/100km"), 1)
        // 6L50: 4.06 / 2.37 / 1.55 / 1.16 / 0.85 / 0.67 — a ratio drifting in a steady gear means slip.
        val input = r.pick("22.1941")?.value
        val output = r.pick("22.1942")?.value
        if (input != null && output != null && output >= 200) add("calc.gearRatio", tr("Передаточное отношение АКПП (вход/выход)", "Transmission gear ratio (in/out)"), input / output, "", 2)
        if (out.isNotEmpty()) publish(out)
    }

    private fun publish(list: List<Reading>) {
        if (list.isEmpty()) return
        _readings.update { m ->
            val n = m.toMutableMap()
            for (r in list) n[r.key] = r.merged(m[r.key])
            n
        }
        session?.let { s -> list.forEach(s::value) }
    }

    // ---------------------------------------------------------------- user actions

    private fun launchOp(title: String, block: suspend (Obd) -> Unit) {
        val o = obd ?: return
        if (opJob?.isActive == true) return
        opJob = scope.launch {
            _busy.value = title
            try {
                opMutex.withLock {
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
                _scan.update { it.copy(running = false) }
            }
        }
    }

    fun refreshDtc() = launchOp(tr("Чтение ошибок", "Reading codes")) { readDtcs(it); readFreezeFrame(it) }

    fun clearDtc() = launchOp(tr("Сброс ошибок", "Clearing codes")) {
        session?.note("USER: clear DTC (mode 04)")
        it.request("04", 5000)
        delay(500)
        readDtcs(it)
        readFreezeFrame(it)
    }

    fun refreshMode06() = launchOp("Mode 06") { readMode06(it, onlyMisfire = false) }

    fun rediscover() = launchOp(tr("Повторный опрос", "Rescan")) { discover(it, full = true) }

    fun probeModules() = launchOp(tr("Поиск модулей", "Module search")) { o ->
        if (o.kline) _scan.update { it.copy(status = tr("На K-line поиск модулей недоступен", "Module search is not available on K-line")) } else findModules(o)
    }

    /** Where a make keeps its diagnostic modules and how to ask them (all probes are read only). */
    private class Addressing(val tag: String, val candidates: List<Pair<Int, Int>>, val probes: List<String>, val name: (Int) -> String)

    private fun addressing() = when (_vehicle.value.make) {
        Make.GM -> Addressing("GM", GmModules.candidates, listOf("1A90", "22F190", "3E00"), GmModules::name)
        Make.VAG -> Addressing("VAG", VagModules.candidates, VagModules.PROBES, VagModules::name)
        else -> {
            val fam = _vehicle.value.make.id.ifEmpty { null }
            // Modules the family's database requests talk to are worth looking for too.
            val fromDb = CarDb.family(fam)?.commands.orEmpty().map { it.req to it.resp }.filter { it.first !in 0x7E0..0x7E7 }
            Addressing(if (fam == null) tr("Блоки", "Modules") else _vehicle.value.make.title,
                ObdModules.candidates(fam, fromDb), ObdModules.probes(fam)) { ObdModules.name(it, fam) }
        }
    }

    /**
     * Probes the make's diagnostic addresses, then reads each module's identification:
     * GM \$1A, everyone else UDS \$22 F1xx / KWP \$1A.
     */
    private suspend fun findModules(o: Obd, progress: (String) -> Unit = {}): List<GmModule> {
        _scan.update { it.copy(running = true, progress = 0f, status = tr("Поиск модулей…", "Searching for modules…")) }
        val sc = GmScanner(o) { session?.note(it) }
        val onProbe = { p: Float, s: String -> _scan.update { it.copy(progress = p * 0.8f, status = s) }; progress(s) }
        val a = addressing()
        val gm = _vehicle.value.make == Make.GM
        val found = sc.probeModules(a.candidates, a.probes, a.name, onProbe)
        _scan.update { it.copy(modules = found, status = tr("Найдено модулей: ${found.size}", "Modules found: ${found.size}")) }
        if (found.isNotEmpty()) prefs.edit().putString(modulesPrefKey(), found.joinToString(",") { "%03X:%03X".format(it.req, it.resp) }).apply()
        session?.report(tr("${a.tag}: найденные модули", "${a.tag}: modules found"), found.joinToString("\n") { "  %03X→%03X %s (%s)".format(it.req, it.resp, it.name, it.answeredTo) }
            .ifEmpty { tr("нет", "none") })
        val ids = mutableListOf<ScanHit>()
        val generic = mutableListOf<String>()
        for ((i, mod) in found.withIndex()) {
            val s = tr("Идентификация ${mod.name} (${i + 1} из ${found.size})…", "Identifying ${mod.name} (${i + 1} of ${found.size})…")
            _scan.update { it.copy(progress = 0.8f + 0.2f * i / found.size, status = s) }
            progress(s)
            if (!gm) {
                generic += "${mod.name} [${mod.id}→%03X]".format(mod.resp)
                generic += identifyGeneric(o, sc, mod).ifEmpty { listOf(tr("  нет ответа", "  no answer")) }
                continue
            }
            sc.identify(mod) { h ->
                ids += h
                session?.scanHit(h.req, h.resp, h.service, h.didHex, h.data)
                _scan.update { st -> st.copy(hits = (st.hits.filter { it.key != h.key } + h)) }
            }
        }
        o.broadcast()
        _scan.update { it.copy(running = false, status = tr("Найдено модулей: ${found.size}", "Modules found: ${found.size}")) }
        if (!gm) session?.report(tr("${a.tag}: идентификация модулей (UDS \$22 F1xx / KWP \$1A)", "${a.tag}: module identification (UDS \$22 F1xx / KWP \$1A)"), generic.joinToString("\n").ifEmpty { tr("нет модулей", "no modules") })
        else session?.report(tr("GM: идентификация модулей (\$1A)", "GM: module identification (\$1A)"), found.joinToString("\n\n") { mod ->
            "${mod.name} [${mod.id}]\n" + ids.filter { it.req == mod.req }.joinToString("\n") { h ->
                "  1A %s %-26s %s".format(h.didHex, h.label.orEmpty(), h.partNumber?.toString() ?: if (h.looksLikeText) "«${h.ascii}»" else h.hex)
            }.ifEmpty { tr("  нет ответа", "  no answer") }
        }.ifEmpty { tr("нет модулей", "no modules") })
        return found
    }

    /**
     * Full DTC memory of every module: GM \$A9, everyone else UDS \$19 / KWP \$18. Finds the modules
     * first if that wasn't done yet. Read only — nothing is cleared.
     */
    fun readAllModulesDtc() = launchOp(tr("Ошибки всех блоков", "Codes in all modules")) { o ->
        if (o.kline) {
            _vehicle.update { it.copy(gmDtcStatus = tr("На K-line доступны только стандартные ошибки OBD (вверху)", "On K-line only standard OBD codes are available (above)")) }
            return@launchOp
        }
        readModuleDtcs(o) { s -> _vehicle.update { it.copy(gmDtcStatus = s) } }
        ensureAdapter(o)
    }

    /**
     * Right after connecting: the same as the button, but it must never break the session —
     * any failure is only logged, and the adapter is checked (re-initialised if needed) afterwards.
     */
    private suspend fun autoModules(o: Obd) {
        try {
            readModuleDtcs(o) { s -> step(s); _vehicle.update { it.copy(gmDtcStatus = s) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session?.note("module DTC (auto) failed: ${e.stackTraceToString()}")
            _vehicle.update { it.copy(gmDtcStatus = tr("Не удалось прочитать: ${e.message}", "Could not read: ${e.message}")) }
        } finally {
            step("")
        }
        ensureAdapter(o)
    }

    /** Normal OBD must answer after the raw-frame trick; if not — full re-init of the adapter. */
    private suspend fun ensureAdapter(o: Obd) {
        o.broadcast()
        if (!o.request("0100", 3000).noData) return
        session?.note("adapter does not answer after module DTC read — re-init")
        o.at("ATZ", 5000)
        delay(300)
        baseSetup(o)
        o.at(o.adaptiveTiming)
        o.at(protocolCmd)
        o.resetState()
        o.broadcast()
        if (o.request("0100", 5000).noData) throw IOException(tr("Адаптер не восстановился после чтения ошибок блоков — переподключитесь", "Adapter did not recover after reading module codes — reconnect"))
        session?.note("adapter re-init OK")
    }

    private fun modulesPrefKey() = "gm_modules_" + (_vehicle.value.vin ?: "")

    /** Modules found in an earlier session of this car — saves a minute of probing on every connect. */
    private fun savedModules(): List<GmModule> = prefs.getString(modulesPrefKey(), null).orEmpty()
        .split(',').mapNotNull { p ->
            val (req, resp) = p.split(':').takeIf { it.size == 2 }?.map { it.toIntOrNull(16) } ?: return@mapNotNull null
            if (req == null || resp == null) null else GmModule(req, resp, addressing().name(req), tr("из прошлой сессии", "from an earlier session"))
        }

    private suspend fun readModuleDtcs(o: Obd, status: (String) -> Unit) {
        var modules = _scan.value.modules
        if (modules.isEmpty()) {
            modules = savedModules()
            if (modules.isNotEmpty()) {
                session?.note("modules from an earlier session: ${modules.joinToString { it.id }}")
                _scan.update { it.copy(modules = modules) }
            }
        }
        if (modules.isEmpty()) modules = findModules(o, status)
        if (modules.isEmpty()) {
            status(tr("Модули не найдены", "No modules found"))
            return
        }
        val gm = _vehicle.value.make == Make.GM
        val gmReader = GmDtcReader(o) { session?.note(it) }
        val udsReader = UdsDtcReader(o, vagNumbers = _vehicle.value.make == Make.VAG) { session?.note(it) }
        val out = mutableListOf<GmDtcResult>()
        for ((i, mod) in modules.withIndex()) {
            status(tr("Ошибки ${mod.name} (${i + 1} из ${modules.size})…", "Codes: ${mod.name} (${i + 1} of ${modules.size})…"))
            out += if (gm) gmReader.read(mod) else udsReader.read(mod)
            _vehicle.update { it.copy(gmDtcs = out.toList()) }
        }
        o.broadcast()
        val total = out.sumOf { it.codes.size }
        _vehicle.update { it.copy(gmDtcs = out, gmDtcTime = System.currentTimeMillis(), gmDtcStatus = tr("Блоков: ${out.size}, кодов: $total", "Modules: ${out.size}, codes: $total")) }
        val title = if (gm) tr("GM: ошибки всех блоков (\$A9 81 %02X)", "GM: DTCs of all modules (\$A9 81 %02X)").format(GmDtcReader.MASK)
            else tr("${addressing().tag}: ошибки всех блоков (UDS \$19 02 / KWP \$18 02)", "${addressing().tag}: DTCs of all modules (UDS \$19 02 / KWP \$18 02)")
        session?.report(title, out.joinToString("\n\n") { r ->
            "${r.module.name} [${r.module.id}] — ${r.result}" + r.codes.joinToString("") { c ->
                tr("\n  %s  статус %02X (%s)  %s", "\n  %s  status %02X (%s)  %s").format(c.full, c.status, c.flags, c.description)
            }
        })
    }

    fun scanModule(module: GmModule, service: String, range: IntRange) = launchOp(tr("Скан ${module.id} $service", "Scan ${module.id} $service")) { o ->
        if (o.kline) return@launchOp
        _scan.update { it.copy(running = true, progress = 0f, status = tr("Подготовка…", "Preparing…")) }
        val sc = GmScanner(o) { session?.note(it) }
        sc.detectCountDigit(module)
        session?.note("GM scan ${module.id} service $service range %04X-%04X".format(range.first, range.last))
        var count = 0
        sc.scan(module, service, range,
            onHit = { h ->
                count++
                session?.scanHit(h.req, h.resp, h.service, h.didHex, h.data)
                _scan.update { s -> s.copy(hits = (s.hits.filter { it.key != h.key } + h)) }
            },
            onProgress = { p, s -> _scan.update { it.copy(progress = p, status = s) } },
        )
        _scan.update { it.copy(status = tr("Готово: ${module.id} $service — найдено $count", "Done: ${module.id} $service — found $count")) }
    }

    /** Passive listening of the regular module traffic — nothing is sent to the modules. */
    fun sniffBus() = launchOp(tr("Прослушка шины", "Bus listening")) { o ->
        if (o.kline) {
            _bus.update { it.copy(status = tr("Прослушка — только на CAN", "Bus listening is CAN only")) }
            return@launchOp
        }
        _bus.update { it.copy(running = true, progress = 0f, status = tr("Подготовка…", "Preparing…")) }
        try {
            session?.note("BUS: listening started")
            BusSniffer(o, { session?.note(it) }) { window, frames, ms ->
                session?.busFrames(window, frames.map { it.id to it.data }, ms)
            }.run(
                onProgress = { p, s -> _bus.update { it.copy(progress = p, status = s) } },
                onSummary = { ids -> _bus.update { it.copy(ids = ids) } },
            )
        } finally {
            val ids = _bus.value.ids
            session?.report(tr("Шина: услышанные ID (${ids.size})", "Bus: IDs heard (${ids.size})"), ids.joinToString("\n") {
                tr("  %s  %6.1f Гц  %-8s  %s", "  %s  %6.1f Hz  %-8s  %s").format(it.idHex, it.hz, it.changing, it.lastHex)
            }.ifEmpty { tr("ничего", "nothing") })
            _bus.update { it.copy(running = false) }
        }
    }

    fun stopOp() {
        opJob?.cancel()
    }

    /** Watches every hit that carries data (at most [MAX_WATCHED], so polling keeps its pace); empty list clears. */
    fun watchAll(hits: List<ScanHit>) {
        _scan.update { s -> s.copy(watched = hits.filter { it.data.isNotEmpty() && !it.looksLikeText }.take(MAX_WATCHED).map { it.key }.toSet()) }
    }

    fun toggleWatch(hit: ScanHit) {
        _scan.update { s -> s.copy(watched = if (hit.key in s.watched) s.watched - hit.key else s.watched + hit.key) }
    }

    companion object {
        private const val MAX_WATCHED = 24
        val PROTOCOLS = mapOf(6 to "CAN 11/500", 7 to "CAN 29/500", 8 to "CAN 11/250", 9 to "CAN 29/250",
            4 to "KWP2000 5-baud", 5 to "KWP2000 fast", 3 to "ISO 9141-2")
        /**
         * CAN first (answers or fails at once), then K-line with its slow bus init, then CAN 250k.
         * No J1850 (old US Ford/GM only): nothing to gain on these cars, and a suspect for the clone's crash in ATSP0.
         */
        private val PROTOCOL_ORDER = listOf(6, 7, 4, 5, 3, 8, 9)
        /** Second round for K-line only. */
        private val KLINE_RETRY = listOf(4, 3, 5)
        private const val KLINE_GAP = 3000L
        private val REBOOTING = tr(
            "Адаптер перезагрузился, переподключаюсь… Если долго — выньте его из разъёма на 5 секунд и вставьте снова.",
            "Adapter rebooted, reconnecting… If it takes long, unplug it for 5 seconds and plug it back in.",
        )
        private val REPLUG = tr(
            "Адаптер отключился и не отвечает по Bluetooth. Выньте его из разъёма на 5 секунд, вставьте и подключитесь снова.",
            "Adapter disconnected and does not answer over Bluetooth. Unplug it for 5 seconds, plug it back in and connect again.",
        )
        private const val POLL_TIMEOUT = 1000L
        private const val EXT_PER_CYCLE = 5
        /** Requests in a row a module may leave unanswered before the probe skips it. */
        private const val SILENT_SKIP = 3
        /** At most this many manufacturer requests are probed on connect. */
        private const val MAX_PROBE = 200
        /** Shown on the Main / Fuel screen: read at least every [PollRate.MEDIUM] while it's open. */
        val MAIN_PIDS = listOf(0x0C, 0x0D, 0x05, 0x0F, 0x04, 0x11, 0x42, 0x10, 0x0B, 0x0E, 0x2F, 0x5C, 0x46, 0x33, 0x1F, 0x43, 0x45, 0x49, 0x03, 0x06, 0x07, 0x08, 0x09)
        val FUEL_PIDS = listOf(0x03, 0x04, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10) +
            (0x14..0x1B) + listOf(0x22, 0x23) + (0x24..0x2B) + listOf(0x2E, 0x2F, 0x32) + (0x34..0x3F) +
            listOf(0x43, 0x44, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5D, 0x5E, 0x9D, 0xA2) +
            listOf(0x11, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C)
    }
}
