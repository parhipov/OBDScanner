package com.obdscanner

import com.obdscanner.util.addTo
import com.obdscanner.util.putIfMissing
import kotlin.concurrent.Volatile
import com.obdscanner.util.nowMs
import com.obdscanner.util.format
import com.obdscanner.bus.BusSniffer
import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.car.CarFamily
import com.obdscanner.car.CarModel
import com.obdscanner.car.Dialect
import com.obdscanner.car.ExtCommand
import com.obdscanner.car.MatchRule
import com.obdscanner.elm.Obd
import com.obdscanner.gm.GmDtcReader
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.GmScanner
import com.obdscanner.gm.ScanHit
import com.obdscanner.obd.Dtc
import com.obdscanner.obd.DtcCode
import com.obdscanner.obd.DtcDb
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.EcuIdent
import com.obdscanner.obd.FuelRate
import com.obdscanner.obd.Make
import com.obdscanner.obd.Mode06
import com.obdscanner.obd.Mode09
import com.obdscanner.obd.ObdModules
import com.obdscanner.obd.Pids
import com.obdscanner.obd.PollRate
import com.obdscanner.obd.Readiness
import com.obdscanner.obd.Reading
import com.obdscanner.obd.TestResult
import com.obdscanner.obd.UdsDtcReader
import com.obdscanner.obd.ecuName
import com.obdscanner.obd.pick
import com.obdscanner.session.Recorder
import com.obdscanner.session.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import com.obdscanner.util.IOException

/**
 * One connection to a car through an ELM327, without Android: protocol search, discovery (supported PIDs,
 * VIN, codes, Mode 06, manufacturer parameters), modules and their codes, polling. [ObdManager] owns the
 * adapter link and the screens; the unit tests replay recorded sessions through this class.
 */
class CarLink(
    private val store: Store,
    /** The hand pick (Guide / Connect), null = by VIN. */
    private val pickedCar: () -> CarChoice? = { null },
    /** The screen on top: what to poll faster. */
    private val tab: () -> Tab = { Tab.Main },
    /** Wall clock, ms; the replay runs on its own clock. */
    private val clock: () -> Long = ::nowMs,
    /** Waiting (bus rest, adapter reset); the replay only moves its clock. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** A step of the connection for the Connect screen. */
    private val onStep: (String) -> Unit = {},
) {
    @Volatile var session: Recorder? = null

    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    val readings: StateFlow<Map<String, Reading>> = _readings.asStateFlow()
    private val _vehicle = MutableStateFlow(VehicleInfo())
    val vehicle: StateFlow<VehicleInfo> = _vehicle.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private val _bus = MutableStateFlow(BusState())
    val bus: StateFlow<BusState> = _bus.asStateFlow()

    /** Poll cycles and one-off operations never interleave (they switch CAN headers). */
    val opMutex = Mutex()

    /** A new connection: nothing known yet but the picked car. */
    fun reset(car: CarModel?) {
        _readings.value = emptyMap()
        _vehicle.value = VehicleInfo(car = car)
        _scan.value = ScanState()
        _bus.value = BusState()
        lastPoll.clear()
    }

    /** The session ended: nothing runs any more. */
    fun stopped() {
        _scan.update { it.copy(running = false) }
        _bus.update { it.copy(running = false) }
    }

    fun opFinished() {
        _scan.update { it.copy(running = false) }
    }

    /** Hand pick (null = by VIN). While connected it shows at once; its parameters are probed on "Rescan". */
    fun pickCar(pick: CarChoice?) {
        _vehicle.update { v ->
            val sameFamily = v.vin == null || CarDb.brandOf(v.vin)?.family.let { it == null || it == pick?.family }
            v.copy(car = pick?.model?.takeIf { sameFamily } ?: CarDb.detect(v.vin))
        }
        syncDtcFamily()
    }

    /** The database finished loading after the app started: the saved pick shows if nothing else is known. */
    fun pickLoaded(car: CarModel?) {
        car?.let { c -> _vehicle.update { if (it.car == null) it.copy(car = c) else it } }
        syncDtcFamily()
    }

    /** Manufacturer trouble codes are read in this make's words (DtcDb), for the report as well as the screen. */
    private fun syncDtcFamily() {
        DtcDb.family = _vehicle.value.dtcFamily ?: pickedCar()?.family
    }

    // ---------------------------------------------------------------- blocks and their dialects

    /** The car's family in the database: by the model (picked, VIN or recognised), else by the make. */
    private fun family(): CarFamily? = _vehicle.value.let { v -> CarDb.family(v.car?.family ?: v.make.id) }

    /**
     * How the block at [req] talks: named by the car's model (or its engine), recognised by its own answer,
     * else the family's default — and with no make known, standard OBD / UDS / KWP.
     */
    private fun dialectOf(req: Int): Dialect {
        val v = _vehicle.value
        return v.car?.knownBlocks?.get(req)?.let { CarDb.dialect(it.dialect) }
            ?: v.dialects[req]?.let { CarDb.dialect(it) }
            ?: family()?.dialect ?: Dialect.GENERIC
    }

    /** Blocks whose dialect the car names or that were recognised: request id → reply id. */
    private fun namedBlocks(): Map<Int, Int> {
        val v = _vehicle.value
        val out = linkedMapOf<Int, Int>()
        v.car?.knownBlocks?.values?.forEach { out[it.req] = it.resp }
        for (req in v.dialects.keys) out.getOrPut(req) { req + 8 }
        return out
    }

    private fun step(text: String) {
        session?.note("step: $text")
        _vehicle.update { it.copy(step = text) }
        onStep(text)
    }

    // ---------------------------------------------------------------- one-off operations

    suspend fun refreshDtc(o: Obd) {
        readDtcs(o)
        readFreezeFrame(o)
    }

    suspend fun clearDtc(o: Obd) {
        session?.note("USER: clear DTC (mode 04)")
        o.request("04", 5000)
        pause(500)
        readDtcs(o)
        readFreezeFrame(o)
    }

    suspend fun refreshMode06(o: Obd) = readMode06(o, onlyMisfire = false)

    suspend fun rediscover(o: Obd) = discover(o, full = true)

    suspend fun probeModules(o: Obd) {
        if (!o.canTarget) _scan.update { it.copy(status = tr("На ISO 9141-2 поиск модулей недоступен", "Module search is not available on ISO 9141-2")) } else findModules(o)
    }

    suspend fun scanModule(o: Obd, module: GmModule, service: String, range: IntRange) {
        if (!o.canTarget) return
        _scan.update { it.copy(running = true, progress = 0f, status = tr("Подготовка…", "Preparing…")) }
        val sc = GmScanner(o) { session?.note(it) }
        // The count digit is a CAN speed-up; on K-line every request waits for its answer anyway.
        if (o.kline) o.countDigit = false else sc.detectCountDigit(module)
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
    suspend fun sniffBus(o: Obd) {
        if (o.kline) {
            _bus.update { it.copy(status = tr("Прослушка — только на CAN", "Bus listening is CAN only")) }
            return
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

    // ---------------------------------------------------------------- init

    /** Protocol that worked at init ("ATSP6") — to come back quickly after a re-init. */
    private var protocolCmd = "ATSP0"

    /** The adapter reset itself mid-session: it is back to defaults and protocol auto, so put our settings back. */
    suspend fun adapterReset(o: Obd) {
        session?.note("adapter reset itself (LV RESET / ERR94 / boot banner) — settings and $protocolCmd again")
        baseSetup(o)
        o.at(o.adaptiveTiming)
        o.at(protocolCmd)
    }

    private suspend fun baseSetup(o: Obd) {
        for (c in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATCAF1", "ATAT1")) o.at(c)
    }

    /** Protocol being tried right now — to skip it if the adapter drops the link. */
    @Volatile var probingProtocol: Int? = null
        private set

    /**
     * Finds the protocol by trying them one at a time: the last one that worked, CAN, K-line, the rest.
     * Never the adapter's own search (ATSP0): on a Polo (KWP 5-baud) the ELM327 v1.5 clone dropped the
     * Bluetooth link ~9.5 s into it every time, 6 of 6, and was unreachable for minutes after.
     * K-line gets a pause between inits and a second round: on the same Polo KWP 5-baud failed right
     * after the fast-init and ISO 9141 attempts once and answered with the same order another time.
     */
    suspend fun initAdapter(o: Obd, name: String, skip: Set<Int> = emptySet()) {
        step(tr("Сброс адаптера (ATZ)…", "Resetting adapter (ATZ)…"))
        o.at("ATZ", 5000)
        pause(300)
        baseSetup(o)
        val ver = o.at("ATI").lines.lastOrNull().orEmpty()
        val desc = o.at("AT@1").lines.firstOrNull().orEmpty()
        val saved = store.getInt("last_protocol_$name", store.getInt("last_protocol", 0)).takeIf { it in PROTOCOLS }
        // The picked car's protocol goes first: no probing of the others (the Polo's clone drops the link on some).
        val carProto = pickedCar()?.model?.protocol?.takeIf { it in PROTOCOLS }
        val order = ((listOfNotNull(carProto, saved) + PROTOCOL_ORDER).distinct() + KLINE_RETRY).filter { it !in skip }
        var first: com.obdscanner.elm.CanReply? = null
        var errors = emptyList<String>()
        var lastKline = false
        for ((i, p) in order.withIndex()) {
            val kline = p in 3..5
            // A K-line ECU needs the bus idle for a while after a failed init before it answers the next one.
            if (kline && lastKline) {
                o.at("ATPC")
                pause(KLINE_GAP)
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
        o.protocol = proto
        if (proto != 0) {
            protocolCmd = "ATSP%X".format(proto)
            store.putInt("last_protocol_$name", proto)
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
            tr("Приложение: ${AppInfo.versionName}\nУстройство: $name\nВерсия: $ver\nОписание: $desc\nПротокол: $dp ($dpn)\n",
                "App: ${AppInfo.versionName}\nDevice: $name\nVersion: $ver\nDescription: $desc\nProtocol: $dp ($dpn)\n") +
                tr("Мульти-PID: ${if (multi) yes else no}\nATAT2: ${if (aggressive) yes else no}\nНапряжение (ATRV): $rv",
                    "Multi-PID: ${if (multi) yes else no}\nATAT2: ${if (aggressive) yes else no}\nVoltage (ATRV): $rv"),
        )
    }

    // ---------------------------------------------------------------- discovery

    suspend fun discover(o: Obd, full: Boolean = false) {
        step(tr("Поддерживаемые PID Mode 01…", "Supported PIDs (Mode 01)…"))
        val pids = mutableMapOf<Int, MutableSet<Int>>()
        var base = 0
        // Only "not supported" to 0100 (Toyota JDM: 7F 01 11 from every ECU) — the ECUs are there, without Mode 01.
        var refused = false
        while (base <= 0xE0) {
            val r = o.request("01%02X".format(base), 3000)
            if (base == 0) refused = r.messages.isNotEmpty() && r.messages.all { it.isNegative }
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
        obdState = when {
            pids.isNotEmpty() -> "ok"
            refused -> "refused"
            else -> null
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
        reportBlocks(o)
        val mirrored = mirrorBlocks(o)
        if (mirrored.isNotEmpty()) {
            step(tr("Поддерживаемые PID через \$21…", "Supported PIDs via \$21…"))
            discoverMirror(o, mirrored)
        }
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
        // No CVN (06) on K-line: a Priora's ECU answered "7F 09 78" (busy) and then nothing for 7+ s, a bus
        // re-init included. The ELM waits out 78 by itself only on CAN.
        val asked = listOf(0x02, 0x04, 0x06, 0x08, 0x0A, 0x0B, 0x0D).let { if (o.kline) it - 0x06 else it }
        for (t in wanted.filter { it in asked }.sorted()) {
            val rr = o.request("09%02X".format(t), 4000)
            if (o.kline && rr.messages.any { it.nrc == 0x78 } && rr.messages.none { it.service == 0x49 }) waitKlineBusy(o, "09%02X".format(t))
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
        val picked = pickedCar()
        // A hand-picked car stays while the VIN agrees with its family (or there is no VIN); otherwise the VIN wins.
        val known = picked?.model?.takeIf { brand == null || it.family == brand.family } ?: CarDb.detect(vin)
        // Neither: the engine ECU's own answer may still say which model it is (a JDM car has no VIN).
        val recognised = if (known == null) recogniseModel(o, brand?.name) else null
        val car = known ?: recognised
        // What the car says beats the hand pick: the VIN, then a model recognised by the engine ECU's answer.
        val vinMake = Make.fromVin(vin).takeIf { it != Make.OTHER }
        val make = vinMake ?: recognised?.let { Make.of(it.family) } ?: picked?.let { Make.of(it.family) } ?: Make.OTHER
        val byPick = vinMake == null && recognised == null && picked != null
        _vehicle.update { v ->
            val ecus = v.ecus.toMutableMap()
            for ((h, i) in info) ecus[h] = (ecus[h] ?: EcuInfo(h)).copy(info09 = i)
            v.copy(vin = vin, make = make, ecus = ecus, car = car, brand = brand?.name, makeByPick = byPick)
        }
        syncDtcFamily()
        recogniseDialects(o)
        session?.report(tr("Машина", "Car"), listOf(
            tr("Марка (по VIN): ", "Make (by VIN): ") + (brand?.name ?: tr("не определена", "unknown")) + " — ${make.title}",
            tr("Год (по VIN): ", "Model year (by VIN): ") + (vin?.let { CarDb.modelYear(it.trim().uppercase()) }?.toString() ?: "—"),
            tr("Выбрано вручную: ", "Picked by hand: ") + (picked?.let { it.model?.title ?: tr("${it.brand} (только марка)", "${it.brand} (make only)") } ?: tr("нет", "nothing")),
            tr("Модель: ", "Model: ") + (car?.title?.let {
                it + when (car) {
                    picked?.model -> tr(" (выбрана вручную)", " (picked by hand)")
                    recognised -> tr(" (по ответу блока двигателя)", " (by the engine ECU's answer)")
                    else -> tr(" (по VIN)", " (by VIN)")
                }
            } ?: tr("не определена", "unknown")),
            tr("Двигатель: ", "Engine: ") + (car?.engines?.joinToString("; ") { it.title }?.ifEmpty { null } ?: "—"),
        ).joinToString("\n"))
        session?.report("Mode 09", info.entries.joinToString("\n\n") { (h, i) ->
            "${ecuName(h)} [%03X]\n".format(h) + i.entries.joinToString("\n") { (t, s) -> "  ${Mode09.name(t)}: ${s.replace("\n", "\n    ")}" }
        }.ifEmpty { tr("нет ответа", "no answer") })
    }

    suspend fun readDtcs(o: Obd) {
        val all = mutableListOf<DtcCode>()
        // Every ECU in the sessions answers 03 ("43 00" with no codes); 0A often gets NO DATA on older cars.
        var noAnswer = false
        // Every ECU said "not supported" to 03 (Toyota JDM): no codes said nothing about the codes either.
        var refused = false
        for ((svc, kind) in listOf("03" to DtcKind.STORED, "07" to DtcKind.PENDING, "0A" to DtcKind.PERMANENT)) {
            val r = o.request(svc, 3000)
            if (svc == "03") {
                noAnswer = r.noData
                refused = !r.noData && r.messages.all { it.isNegative }
            }
            val resp = svc.toInt(16) + 0x40
            for (m in r.messages) if (m.service == resp) all += Dtc.parse(m.data, m.header, kind)
        }
        _vehicle.update { it.copy(dtcs = all, dtcTime = clock(), dtcNoAnswer = noAnswer || refused) }
        session?.report(tr("Коды ошибок", "Trouble codes"), all.joinToString("\n") { "${it.kind.title}: ${it.code} [%03X] ${it.description}".format(it.ecu) }
            .ifEmpty {
                when {
                    noAnswer -> tr("нет ответа", "no answer")
                    refused -> tr("Mode 03 не поддерживается (ошибки — во «Всех блоках»)", "Mode 03 not supported (codes — in \"All modules\")")
                    else -> tr("нет", "none")
                }
            })
    }

    suspend fun readFreezeFrame(o: Obd) {
        val r = o.request("020200", 2000)
        val m = r.messages.firstOrNull { it.data.size >= 5 && it.data[0] == 0x42 && it.data[1] == 0x02 }
        if (m == null || (m.data[3] == 0 && m.data[4] == 0)) {
            _vehicle.update { it.copy(freezeDtc = null, freeze = emptyList()) }
            session?.report(tr("Стоп-кадр", "Freeze frame"), if (r.noData) tr("нет ответа", "no answer") else tr("нет", "none"))
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

    suspend fun readMode06(o: Obd, onlyMisfire: Boolean) {
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
        val now = clock()
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
        _vehicle.update { it.copy(mode06Time = clock()) }
    }

    /**
     * What to probe: the family's requests for the car's model, except at the blocks whose dialect the car
     * names or that were recognised — those get their own dialect's requests (a Volvo engine in another make
     * answers Volvo's, not the make's).
     */
    private fun extCandidates(): List<ExtCommand> {
        val v = _vehicle.value
        val fam = family()
        val car = v.car?.takeIf { it.family == fam?.id }
        // A gas-converted car still has a petrol ECU.
        val db = fam?.probeOrder(car, fuelOf(car)?.let { if (it == "lpg") "petrol" else it }).orEmpty()
        val own = namedBlocks().keys.map { it to dialectOf(it) }.filter { (_, d) -> d.id != fam?.id }
        val mine = db.filter { c -> own.none { it.first == c.req } } + own.flatMap { it.second.commands }
        val cyl = cylindersOf(car)?.first
        // Capped: a big family would mean hundreds of requests on the first connection.
        return mine.distinctBy { it.key }.mapNotNull { it.forCylinders(cyl) }.take(MAX_PROBE)
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
        val vin = carKey()
        // Per app version too: a new build may bring new requests in the database.
        val prefKey = "ext_ok_${AppInfo.versionCode}_" + vin.orEmpty()
        val saved = if (vin != null) store.getStringSet(prefKey) else null
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
                lines += "  ${c.key} [${c.source}]${if (c.signals.any { it.confidence != "OK" }) " (?)" else ""} — " + out.joinToString("; ") { "${it.name} = ${it.display()} ${it.unit}".trim() }
            } else {
                lines += "  ${c.key} [${c.source}] ${c.signals.first().name}${if (c.signals.size > 1) " +${c.signals.size - 1}" else ""} — " +
                    if (r == null) tr("нет ответа", "no answer") else "NRC %02X".format(r.second)
            }
        }
        o.broadcast()
        if (vin != null && (full || saved == null)) store.putStringSet(prefKey, active.map { it.key }.toSet())
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
                if (silent.addTo(item.service, 1) >= 3) skip += item.service
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
        val (v, text) = s.fmt.decode(data) ?: (null to null)
        Reading(c.readingKey(s), c.req, s.name, v, text ?: if (v == null) Pids.hex(data) else null, s.unit, s.decimals, role = s.role)
    }

    // ---------------------------------------------------------------- polling

    private var silentCycles = 0

    /**
     * The K-line ECU stopped answering (engine cranked, ignition cycled): the adapter keeps the old
     * session, so close it (ATPC), let the bus rest and init again with the known protocol (as AndrOBD does).
     */
    private suspend fun reinitKline(o: Obd, why: String = "ECU silent for 3 cycles"): Boolean {
        session?.note("K-line: $why — ATPC and a new bus init")
        o.at("ATPC")
        baseSetup(o)
        pause(KLINE_GAP)
        val r = o.request("0100", 10000)
        session?.note("K-line re-init: ${if (r.noData) "no answer (${r.errors.joinToString()})" else "OK"}")
        return !r.noData
    }

    /**
     * A K-line ECU said "busy, the answer comes later" (7F xx 78). The ELM doesn't wait for that answer, and
     * the ECU ignores requests while busy: wait until it answers OBD again, else a new bus init.
     */
    private suspend fun waitKlineBusy(o: Obd, what: String) {
        session?.note("K-line: $what — ECU busy (78), waiting for it")
        val until = clock() + KLINE_BUSY_WAIT
        while (clock() < until) {
            pause(1000)
            if (!o.request("0100", 2000).noData) {
                session?.note("K-line: ECU answers again")
                return
            }
        }
        reinitKline(o, "ECU still busy")
    }

    private var lastRv = 0L
    private var lastFlush = 0L
    private var lastMisfire = 0L
    private var lastWatch = 0L

    /** Polls until cancelled; one-off operations get the adapter between cycles. */
    suspend fun pollLoop(o: Obd) {
        startPolling()
        while (true) {
            opMutex.withLock { pollCycle(o) }
            yield()
        }
    }

    fun startPolling() {
        silentCycles = 0
        lastRv = 0L
        lastFlush = 0L
        lastMisfire = clock()
        lastWatch = 0L
    }

    /** One poll cycle: the due Mode 01 PIDs, voltage, misfires on Fuel, manufacturer and watched values. */
    suspend fun pollCycle(o: Obd) {
        val tab = tab()
        val supported = _vehicle.value.functional01.filter { !Pids.isBitmask(it) && it != 0x02 && Pids.byPid[it]?.static != true }
        val onScreen = when (tab) {
            Tab.Main -> MAIN_PIDS
            Tab.Fuel -> FUEL_PIDS
            else -> emptyList()
        }
        val pids = PollRate.due(supported, lastPoll, clock(), Int.MAX_VALUE, { "01.%02X".format(it) }) {
            PollRate.of01(it).let { p -> if (it in onScreen) PollRate.onScreen(p) else p }
        }
        var answers = pollPids(o, pids)
        // Blocks that give the standard PIDs through \$21 (Toyota JDM): one request per PID, to the block.
        for (e in _vehicle.value.ecus.values.filter { it.pidReq != null }) {
            val due = PollRate.due(e.pids01.filter { !Pids.isBitmask(it) && it != 0x01 && it != 0x02 && Pids.byPid[it]?.static != true }.sorted(),
                lastPoll, clock(), Int.MAX_VALUE, { "21.%03X.%02X".format(e.pidReq, it) }) {
                PollRate.of01(it).let { p -> if (it in onScreen) PollRate.onScreen(p) else p }
            }
            if (due.isNotEmpty()) answers = maxOf(answers, 0) + pollMirror(o, e, due)
        }
        silentCycles = if (answers == 0) silentCycles + 1 else 0
        if (o.kline && silentCycles >= 3) {
            reinitKline(o)
            silentCycles = 0
        }
        publishDerived()

        val now = clock()
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

    /** Asks [pids] of a \$21 block ([EcuInfo.pidReq]); returns how many answered. */
    private suspend fun pollMirror(o: Obd, e: EcuInfo, pids: List<Int>): Int {
        val req = e.pidReq ?: return 0
        val sc = GmScanner(o) { session?.note(it) }
        o.target(req, e.header)
        val out = mutableListOf<Reading>()
        for (pid in pids) mirrorValue(sc, req, e.header, pid)?.let { out += it }
        o.broadcast()
        publish(out)
        return out.size
    }

    /**
     * One standard PID through \$21 ("21 0C" → "61 0C 0D F7" = 894 rpm). An answer of another length than
     * the PID has is not decoded: on a block that only looks like this, it would be some other value.
     */
    private suspend fun mirrorValue(sc: GmScanner, req: Int, resp: Int, pid: Int): List<Reading>? {
        val d = sc.read(GmModule(req, resp, "", ""), "21", pid)?.first ?: return null
        val len = Pids.byPid[pid]?.len ?: -1
        if (len > 0 && d.size != len) return null
        return Pids.decode(resp, "01", pid, d)
    }

    /** Where a block's dialect comes from, for the report and the log. */
    private fun dialectSource(req: Int): String {
        val v = _vehicle.value
        return when {
            v.car?.knownBlocks?.get(req)?.let { CarDb.dialect(it.dialect) } != null -> tr("названа моделью", "named by the model")
            v.dialects[req]?.let { CarDb.dialect(it) } != null -> tr("узнана по ответу блока", "recognised by the block's answer")
            family() != null && v.makeByPick -> tr("по умолчанию для выбранной вручную марки", "the hand-picked make's default")
            family() != null -> tr("по умолчанию для марки", "the make's default")
            else -> tr("марка неизвестна — стандарт", "make unknown — standard")
        }
    }

    /** "data Mode 01, codes UDS \$19 → KWP \$18, identification UDS \$22 / KWP \$1A". */
    private fun ways(d: Dialect, kline: Boolean): String {
        val dtc = d.dtc.filter { it != "gm_a9" || !kline }.ifEmpty { Dialect.GENERIC.dtc }.joinToString(" → ") {
            when (it) {
                "gm_a9" -> "GM \$A9"
                "uds19" -> "UDS \$19"
                "kwp18" -> "KWP \$18"
                "kwp13" -> "KWP \$13"
                else -> it
            }
        }
        val ident = if (d.ident == "gm_1a" && !kline) "GMLAN \$1A" else "UDS \$22 / KWP \$1A"
        return tr("данные %s, ошибки %s, идентификация %s", "data %s, codes %s, identification %s")
            .format(if (d.live == "21" && !kline) "\$21" else "Mode 01", dtc, ident) +
            if (d.dtcFormat == "vag") tr(", номера VAG", ", VAG numbers") else ""
    }

    /**
     * Report: how the car was recognised, the make's default dialect and, for every block known so far (the
     * ones the model names, the recognised ones, the OBD responders), which dialect it talks and why.
     */
    private fun reportBlocks(o: Obd) {
        val v = _vehicle.value
        val fam = family()
        val blocks = linkedMapOf<Int, String?>()
        v.car?.knownBlocks?.values?.forEach { blocks[it.req] = it.role }
        for (req in v.dialects.keys) blocks.putIfMissing(req, null)
        for (h in v.ecus.keys.sorted()) {
            val req = if (h in 0x7E8..0x7EF) h - 8 else if (h in 0x01..0xEF) h else continue
            blocks.putIfMissing(req, null)
        }
        val default = fam?.dialect ?: Dialect.GENERIC
        val lines = mutableListOf(
            tr("Семейство: ", "Family: ") + (fam?.let { "${it.id} (${it.title})" } ?: tr("неизвестно", "unknown")),
            tr("По умолчанию: ", "Default: ") + "${default.id} — " + ways(default, o.kline),
        )
        for ((req, role) in blocks) {
            val d = dialectOf(req)
            lines += "  %s%s — %s (%s): %s".format(if (req > 0xFF) "%03X".format(req) else "%02X".format(req),
                role?.let { " ${roleName(it)}" }.orEmpty(), d.id, dialectSource(req), ways(d, o.kline))
        }
        session?.note("blocks: " + blocks.keys.joinToString { "%03X=%s".format(it, dialectOf(it).id) })
        session?.report(tr("Блоки и диалекты", "Blocks and dialects"), lines.joinToString("\n"))
    }

    private fun roleName(role: String) = when (role) {
        "engine" -> tr("двигатель", "engine")
        "gearbox" -> tr("КПП", "gearbox")
        "abs" -> "ABS"
        "airbag" -> tr("подушки", "airbags")
        "body" -> tr("кузов", "body")
        "cluster" -> tr("приборка", "cluster")
        "climate" -> tr("климат", "climate")
        "steering" -> tr("руль", "steering")
        "gateway" -> tr("шлюз", "gateway")
        else -> role
    }

    /** Blocks that read the standard PIDs with \$21 by their dialect (CAN only): request id → reply id. */
    private fun mirrorBlocks(o: Obd): Map<Int, Int> =
        if (o.kline) emptyMap() else namedBlocks().filter { (req, _) -> req > 0xFF && dialectOf(req).live == "21" }

    /**
     * Supported PIDs of the \$21 blocks: "21 00" answers the same bitmap as "01 00" would ("61 00 BF 9F A8 93"),
     * then 20, 40… The PIDs are polled like Mode 01 ones, under the same names and keys.
     */
    private suspend fun discoverMirror(o: Obd, blocks: Map<Int, Int>) {
        val sc = GmScanner(o) { session?.note(it) }
        val found = linkedMapOf<Int, Pair<Int, Set<Int>>>()
        for ((req, resp) in blocks) {
            o.target(req, resp)
            val set = mutableSetOf<Int>()
            var base = 0
            while (base <= 0xE0) {
                // A bitmap is exactly 4 bytes; anything else means the block's \$21 isn't this.
                val d = sc.read(GmModule(req, resp, "", ""), "21", base)?.first
                if (d == null || d.size != 4) break
                for (i in 0 until 32) if ((d[i / 8] shr (7 - i % 8)) and 1 == 1) set += base + i + 1
                if (base + 0x20 !in set) break
                base += 0x20
            }
            session?.note("\$21 PIDs %03X: %s".format(req, if (set.isEmpty()) "none" else set.sorted().joinToString(" ") { "%02X".format(it) }))
            // Past the standard ones the bitmap goes on with the maker's own ids (Crown: B2…F9, C1 = model name):
            // only the PIDs the app knows are read as such.
            val known = set.filter { Pids.byPid[it] != null }.toSet()
            if (known.isNotEmpty()) found[resp] = req to known
        }
        _vehicle.update { v ->
            val ecus = v.ecus.toMutableMap()
            for ((resp, p) in found) ecus[resp] = (ecus[resp] ?: EcuInfo(resp)).copy(pids01 = p.second, pidReq = p.first)
            v.copy(ecus = ecus)
        }
        session?.report(tr("Поддерживаемые PID через \$21", "Supported PIDs via \$21"), found.entries.joinToString("\n\n") { (h, p) ->
            val list = p.second.filter { !Pids.isBitmask(it) }
            tr("${ecuName(h)} [%03X] — ${list.size} шт.\n", "${ecuName(h)} [%03X] — ${list.size} PIDs\n").format(h) +
                list.sorted().joinToString("\n") { "  21 %02X  %s".format(it, Pids.name(it)) }
        }.ifEmpty { tr("нет ответа", "no answer") })
        // The values that don't change, once (PID 01 is left out: the readiness bytes don't come this way).
        for ((resp, p) in found) {
            o.target(p.first, resp)
            val out = mutableListOf<Reading>()
            for (pid in p.second.filter { Pids.byPid[it]?.static == true }.sorted()) mirrorValue(sc, p.first, resp, pid)?.let { out += it }
            publish(out)
        }
        o.broadcast()
    }

    // ---------------------------------------------------------------- recognising the car and its blocks

    /** Standard OBD on this car: "ok" (Mode 01 PIDs), "refused" (only negative answers), null (silence). */
    private var obdState: String? = null

    /**
     * A model whose rules the engine ECU's answers fit ([CarModel.match]): for a car without a VIN (JDM)
     * or with one that doesn't say the model. Each request is sent once, whatever number of rules use it.
     */
    private suspend fun recogniseModel(o: Obd, brand: String?): CarModel? {
        if (o.kline) return null
        val models = CarDb.matchable.filter { m -> brand == null || m.brand == brand }
        val rules = models.flatMap { m -> m.match.filter { it.fits(o.protocol, obdState) }.map { m to it } }
        if (rules.isEmpty()) return null
        val answers = ask(o, rules.map { it.second })
        val hit = rules.firstOrNull { (_, r) -> answers[r.key]?.let(r::matches) == true }?.first
        session?.note(if (hit != null) "model recognised: ${hit.title}"
            else "model not recognised: ${rules.size} rules of ${rules.map { it.first.title }.distinct().size} models, answers ${answers.keys}")
        return hit
    }

    /**
     * Dialects of the OBD blocks the car doesn't name, by their answers ([Dialect.match]) — the engine of
     * another make in a car (an unknown Chinese car with a Volvo engine).
     */
    private suspend fun recogniseDialects(o: Obd) {
        if (o.kline) return
        val v = _vehicle.value
        val named = v.car?.knownBlocks?.keys.orEmpty()
        val rules = CarDb.dialects.values.flatMap { d -> d.match.filter { it.fits(o.protocol, obdState) && it.req !in named }.map { d to it } }
        if (rules.isEmpty()) return
        val answers = ask(o, rules.map { it.second })
        val found = linkedMapOf<Int, String>()
        for ((d, r) in rules) if (r.req !in found && answers[r.key]?.let(r::matches) == true) found[r.req] = d.id
        if (found.isEmpty()) return
        session?.note("dialects recognised: " + found.entries.joinToString { "%03X %s".format(it.key, it.value) })
        _vehicle.update { it.copy(dialects = it.dialects + found) }
    }

    /** Sends each distinct request of [rules] once; the answers (data after the echo) go to scan.csv too. */
    private suspend fun ask(o: Obd, rules: List<MatchRule>): Map<String, IntArray> {
        val sc = GmScanner(o) { session?.note(it) }
        val out = linkedMapOf<String, IntArray>()
        for (r in rules.distinctBy { it.key }) {
            o.target(r.req, r.resp)
            val d = sc.read(GmModule(r.req, r.resp, "", ""), r.service, r.did)?.first ?: continue
            out[r.key] = d
            session?.scanHit(r.req, r.resp, r.service, r.didHex, d)
        }
        o.broadcast()
        return out
    }

    /** Returns how many answers came, -1 if there was nothing to ask. */
    private suspend fun pollPids(o: Obd, pids: List<Int>): Int {
        if (pids.isEmpty()) {
            pause(200)
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
        val batch = PollRate.due(list, lastPoll, clock(), EXT_PER_CYCLE, { it.key }) {
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

    /** Where the car's make keeps its diagnostic modules and how to ask them (all probes are read only). */
    private fun addressing(): ObdModules.Addressing {
        val fam = family()
        val tag = when {
            fam?.modules?.tag != null -> fam.modules!!.tag!!
            fam == null -> tr("Блоки", "Modules")
            else -> Make.of(fam.id).title
        }
        return ObdModules.addressing(fam, tag, namedBlocks().filterKeys { it > 0xFF }.toList())
    }

    /**
     * Probes the make's diagnostic addresses, then reads each module's identification:
     * GM \$1A, everyone else UDS \$22 F1xx / KWP \$1A.
     */
    suspend fun findModules(o: Obd, progress: (String) -> Unit = {}): List<GmModule> {
        _scan.update { it.copy(running = true, progress = 0f, status = tr("Поиск модулей…", "Searching for modules…")) }
        val sc = GmScanner(o) { session?.note(it) }
        val onProbe = { p: Float, s: String -> _scan.update { it.copy(progress = p * 0.8f, status = s) }; progress(s) }
        val a = addressing()
        // On K-line GMLAN \$1A / \$A9 don't apply even on a GM: KWP identification and \$18.
        fun gmlan(req: Int) = !o.kline && dialectOf(req).ident == "gm_1a"
        val found = if (o.kline) klineModules() else sc.probeModules(a.candidates, a.probes, a.name, onProbe)
        _scan.update { it.copy(modules = found, status = tr("Найдено модулей: ${found.size}", "Modules found: ${found.size}")) }
        val key = modulesPrefKey()
        if (found.isNotEmpty() && key != null) store.putString(key, found.joinToString(",") { "%03X:%03X".format(it.req, it.resp) })
        session?.report(tr("${a.tag}: найденные модули", "${a.tag}: modules found"), found.joinToString("\n") { "  %03X→%03X %s (%s)".format(it.req, it.resp, it.name, it.answeredTo) }
            .ifEmpty { tr("нет", "none") })
        val ids = mutableListOf<ScanHit>()
        val generic = mutableListOf<String>()
        for ((i, mod) in found.withIndex()) {
            val s = tr("Идентификация ${mod.name} (${i + 1} из ${found.size})…", "Identifying ${mod.name} (${i + 1} of ${found.size})…")
            _scan.update { it.copy(progress = 0.8f + 0.2f * i / found.size, status = s) }
            progress(s)
            if (!gmlan(mod.req)) {
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
        // No modules: the section the make's own identification would have written.
        val gmMods = if (found.isEmpty()) emptyList() else found.filter { gmlan(it.req) }
        val gmFamily = !o.kline && (family()?.dialect ?: Dialect.GENERIC).ident == "gm_1a"
        if (gmMods.size < found.size || found.isEmpty() && !gmFamily) {
            session?.report(tr("${a.tag}: идентификация модулей (UDS \$22 F1xx / KWP \$1A)", "${a.tag}: module identification (UDS \$22 F1xx / KWP \$1A)"), generic.joinToString("\n").ifEmpty { tr("нет модулей", "no modules") })
        }
        if (gmMods.isNotEmpty() || found.isEmpty() && gmFamily) session?.report(tr("GM: идентификация модулей (\$1A)", "GM: module identification (\$1A)"), gmMods.joinToString("\n\n") { mod ->
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
    suspend fun readAllModulesDtc(o: Obd) {
        if (!o.canTarget) {
            _vehicle.update { it.copy(gmDtcStatus = tr("На ISO 9141-2 доступны только стандартные ошибки OBD (вверху)", "On ISO 9141-2 only standard OBD codes are available (above)")) }
            return
        }
        readModuleDtcs(o) { s -> _vehicle.update { it.copy(gmDtcStatus = s) } }
        ensureAdapter(o)
    }

    /**
     * Right after connecting: the same as the button, but it must never break the session —
     * any failure is only logged, and the adapter is checked (re-initialised if needed) afterwards.
     */
    suspend fun autoModules(o: Obd) {
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
    suspend fun ensureAdapter(o: Obd) {
        o.broadcast()
        if (!o.request("0100", 3000).noData) return
        session?.note("adapter does not answer after module DTC read — re-init")
        o.at("ATZ", 5000)
        pause(300)
        baseSetup(o)
        o.at(o.adaptiveTiming)
        o.at(protocolCmd)
        o.resetState()
        o.broadcast()
        var back = !o.request("0100", if (o.kline) 10000 else 5000).noData
        // K-line: BUS INIT ERROR right after ATZ while the ECU is still busy or its old session hasn't timed out.
        if (!back && o.kline) for (n in 1..2) if (reinitKline(o, "no answer after re-init (try $n)")) { back = true; break }
        if (!back) throw IOException(tr("Адаптер не восстановился после чтения ошибок блоков — переподключитесь", "Adapter did not recover after reading module codes — reconnect"))
        session?.note("adapter re-init OK")
    }

    /** Null — nothing to keep the modules under (no VIN, no calibration): found again every time. */
    private fun modulesPrefKey() = carKey()?.let { "gm_modules_$it" }

    /**
     * What the saved settings of a car are kept under: its VIN; without one the model recognised by its
     * answers (JDM); else the engine ECU's calibration id with the make — the same software answers the same
     * requests, and another make picked by hand asks other ones. Null — nothing to tell the car by.
     */
    private fun carKey(): String? = _vehicle.value.let { v ->
        v.vin ?: v.car?.takeIf { it.match.isNotEmpty() }?.let { "model:" + it.id }
            ?: v.ecus.entries.sortedBy { it.key }.map { it.value }.firstNotNullOfOrNull { it.info09[0x04]?.trim()?.ifEmpty { null } }
                ?.let { "calid:${family()?.id.orEmpty()}:$it" }
    }

    /**
     * K-line modules: whoever answered the functional OBD requests (their source addresses, e.g. 11 engine,
     * 18 transmission). No probing — nothing is sent to addresses that didn't speak up themselves.
     */
    private fun klineModules(): List<GmModule> = _vehicle.value.ecus.keys.filter { it in 0x01..0xEF }.sorted().map {
        GmModule(it, it, ObdModules.klineName(it), tr("ответил на OBD", "answered OBD"))
    }

    /** Modules found in an earlier session of this car — saves a minute of probing on every connect. */
    private fun savedModules(): List<GmModule> = modulesPrefKey()?.let { store.getString(it) }.orEmpty()
        .split(',').mapNotNull { p ->
            val (req, resp) = p.split(':').takeIf { it.size == 2 }?.map { it.toIntOrNull(16) } ?: return@mapNotNull null
            if (req == null || resp == null) null else GmModule(req, resp, addressing().name(req), tr("из прошлой сессии", "from an earlier session"))
        }

    private suspend fun readModuleDtcs(o: Obd, status: (String) -> Unit) {
        var modules = _scan.value.modules
        // K-line: the modules are known from the OBD answers, identification is a few seconds — always fresh.
        if (modules.isEmpty() && !o.kline) {
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
        val gmReader = GmDtcReader(o) { session?.note(it) }
        val udsReader = UdsDtcReader(o) { session?.note(it) }
        val out = mutableListOf<GmDtcResult>()
        val used = mutableSetOf<String>()
        // Modules that didn't answer the hand-picked make's way but did a standard one: the pick may be wrong.
        val otherWay = mutableListOf<GmModule>()
        for ((i, mod) in modules.withIndex()) {
            status(tr("Ошибки ${mod.name} (${i + 1} из ${modules.size})…", "Codes: ${mod.name} (${i + 1} of ${modules.size})…"))
            val d = dialectOf(mod.req)
            // GMLAN \$A9 needs CAN (raw UUDT frames); on K-line the block gets the standard ways.
            val chain = d.dtc.filter { it != "gm_a9" || !o.kline }.ifEmpty { Dialect.GENERIC.dtc }
            // A make only picked by hand may be the wrong one: its ways first, then the standard ones.
            val guess = _vehicle.value.makeByPick && _vehicle.value.car?.knownBlocks?.containsKey(mod.req) != true
            session?.note("DTC %s: dialect %s (%s), %s%s".format(mod.id, d.id, dialectSource(mod.req), chain.joinToString(" → "),
                if (guess) ", then the standard ways if refused" else ""))
            used += chain.first().takeIf { it == "gm_a9" }?.let { listOf(it) } ?: chain
            var r = if (chain.first() == "gm_a9") gmReader.read(mod) else udsReader.read(mod, chain, vagNumbers = d.dtcFormat == "vag")
            val rest = Dialect.GENERIC.dtc.filter { it !in chain }
            // Nothing read (refused or silent, no end-of-list): the block may talk another make's way.
            if (guess && r.codes.isEmpty() && !r.complete && rest.isNotEmpty()) {
                session?.note("DTC %s: %s gave nothing — trying %s".format(mod.id, chain.joinToString("/"), rest.joinToString("/")))
                status(tr("Ошибки ${mod.name}: способ выбранной марки не сработал, стандартный…", "Codes: ${mod.name}: the picked make's way didn't work, the standard one…"))
                val again = udsReader.read(mod, rest)
                used += rest
                val picked = UdsDtcReader.title(chain.toSet())
                r = if (again.codes.isNotEmpty() || again.complete) {
                    otherWay += mod
                    session?.note("DTC %s: answered the standard way (%s) — the picked make may be wrong".format(mod.id, again.result))
                    again.copy(result = again.result + tr(". Способом выбранной марки ($picked) блок не ответил — прочитано стандартным",
                        ". The block didn't answer the picked make's way ($picked) — read the standard way"))
                } else again.copy(result = r.result + "; " + tr("стандартные способы: ", "standard ways: ") + again.result)
            }
            out += r
            _vehicle.update { it.copy(gmDtcs = out.toList()) }
        }
        val gm = used == setOf("gm_a9")
        o.broadcast()
        val total = out.sumOf { it.codes.size }
        val summary = tr("Блоков: ${out.size}, кодов: $total", "Modules: ${out.size}, codes: $total") + if (otherWay.isEmpty()) "" else tr(
            ". Марка выбрана вручную, но ${otherWay.joinToString { it.id }} ответили только стандартным способом — проверьте марку («Связь» → «Выбрать»)",
            ". The make is picked by hand, but ${otherWay.joinToString { it.id }} answered only the standard way — check the make (Connect → Pick)")
        if (otherWay.isNotEmpty()) session?.note("picked make doubtful: ${otherWay.joinToString { it.id }} answered only the standard way")
        _vehicle.update { it.copy(gmDtcs = out, gmDtcTime = clock(), gmDtcStatus = summary) }
        val title = if (gm) tr("GM: ошибки всех блоков (\$A9 81 %02X)", "GM: DTCs of all modules (\$A9 81 %02X)").format(GmDtcReader.MASK)
            else tr("${if (o.kline) "K-line" else addressing().tag}: ошибки всех блоков (${UdsDtcReader.title(used)})", "${addressing().tag}: DTCs of all modules (${UdsDtcReader.title(used)})")
        session?.report(title, out.joinToString("\n\n") { r ->
            "${r.module.name} [${r.module.id}] — ${r.result}" + r.codes.joinToString("") { c ->
                tr("\n  %s  статус %02X (%s)  %s", "\n  %s  status %02X (%s)  %s").format(c.full, c.status, c.flags, c.description)
            }
        })
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
        /** How long a K-line ECU that answered 78 (busy) gets to come back before a bus re-init. */
        private const val KLINE_BUSY_WAIT = 15000L
        val REBOOTING = tr(
            "Адаптер перезагрузился, переподключаюсь… Если долго — выньте его из разъёма на 5 секунд и вставьте снова.",
            "Adapter rebooted, reconnecting… If it takes long, unplug it for 5 seconds and plug it back in.",
        )
        val REPLUG = tr(
            "Адаптер отключился и не отвечает. Выньте его из разъёма на 5 секунд, вставьте и подключитесь снова.",
            "Adapter disconnected and does not answer. Unplug it for 5 seconds, plug it back in and connect again.",
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
