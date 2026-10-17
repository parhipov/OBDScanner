package com.obdscanner

import com.obdscanner.bus.BusId
import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarModel
import com.obdscanner.car.ExtCommand
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.ScanDecode
import com.obdscanner.gm.ScanHit
import com.obdscanner.gm.ScanParam
import com.obdscanner.obd.DtcCode
import com.obdscanner.obd.Make
import com.obdscanner.obd.Monitor
import com.obdscanner.obd.Reading
import com.obdscanner.obd.TestResult

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val device: String, val step: String) : ConnState
    data class Connected(val device: String) : ConnState
    /** Phone sensors only, no adapter. */
    data object Recording : ConnState
    data class Failed(val message: String) : ConnState
}

/**
 * In the order of the tab row: what is looked at while driving first, the guide and the app's own things last.
 * The names stay (the page selects its tabs by them): Info is «Машина», Guide is «Справка».
 */
enum class Tab(val title: String) {
    Connect(tr("Связь", "Connect")),
    Main(tr("Главная", "Main")),
    Fuel(tr("Топливо", "Fuel")),
    Dtc(tr("Ошибки", "Codes")),
    All(tr("Все данные", "All data")),
    Info(tr("Машина", "Car")),
    Gm(tr("Скан блоков", "Module scan")),
    Sessions(tr("Сессии", "Sessions")),
    Guide(tr("Справка", "Help")),
}

/** What the app keeps about the car on this phone; «Сбросить» on «Машина» forgets it ([CarLink.forgetCar]). */
data class CarMemory(
    /** The protocol this adapter worked on last ("CAN 11/500"): tried first at the next connection. */
    val protocol: String? = null,
    /** Manufacturer requests saved as answering (only they are asked next time); null — nothing saved. */
    val extSaved: Int? = null,
    /** Modules found: the codes of all modules and the block scan start from them. */
    val modules: Int = 0,
    /** Requests left out: the adapter dropped the link on them. */
    val skipped: Set<String> = emptySet(),
) {
    val isEmpty get() = protocol == null && extSaved == null && modules == 0 && skipped.isEmpty()
}

data class EcuInfo(
    val header: Int,
    val pids01: Set<Int> = emptySet(),
    /** The standard PIDs of this ECU are read with \$21 to this request id (Toyota JDM); null — Mode 01 broadcast. */
    val pidReq: Int? = null,
    val info09: Map<Int, String> = emptyMap(),
    val readiness: List<Monitor> = emptyList(),
    val mids06: Set<Int> = emptySet(),
)

data class VehicleInfo(
    val adapter: String = "",
    val adapterDesc: String = "",
    val protocol: String = "",
    /** Mode 01 PIDs one request may carry: up to 6, fewer after bigger ones went unanswered; 1 — one PID at a time. */
    val pidBatch: Int = 1,
    val step: String = "",
    val ecus: Map<Int, EcuInfo> = emptyMap(),
    val vin: String? = null,
    /** By VIN (or the picked / recognised car): the guide and texts of the make. */
    val make: Make = Make.OTHER,
    val dtcs: List<DtcCode> = emptyList(),
    val dtcTime: Long = 0,
    /** Nobody answered mode 03 at all (not even "43 00"): the empty list says nothing about the codes. */
    val dtcNoAnswer: Boolean = false,
    val freezeDtc: String? = null,
    val freeze: List<Reading> = emptyList(),
    val mode06: List<TestResult> = emptyList(),
    val mode06Time: Long = 0,
    /** Manufacturer requests that answered (the car database), polled with standard PIDs. */
    val extActive: List<ExtCommand> = emptyList(),
    /** Of those, the ones whose knock retard can't be real on this car (CarLink.knockChecked): not used as knock. */
    val knockRejected: Set<String> = emptySet(),
    /** The car: picked by hand or found by the VIN in [CarDb]. */
    val car: CarModel? = null,
    /** Brand by the VIN ("Cadillac"), null before the VIN or when unknown. */
    val brand: String? = null,
    /** The full memory of every module found, on the main bus and the others the adapter reaches (GM \$A9, UDS, KWP). */
    val gmDtcs: List<GmDtcResult> = emptyList(),
    val gmDtcTime: Long = 0,
    val gmDtcStatus: String = "",
    /** The modules' codes at this car's check before this connection: «новая» / «пропали» on «Ошибки»; null — none kept. */
    val dtcPrev: PrevCodes? = null,
    /** What the last clearing did ([CarLink.clearDtc], [CarLink.clearModules]): a line per module or one for Mode 04. */
    val clearResult: List<String> = emptyList(),
    /** Another bus the adapter can't reach on this car (its pins, «контакт 1»): one line on «Ошибки». */
    val busesOut: List<String> = emptyList(),
    /** ISO 9141-2 / ISO 14230: standard OBD only. */
    val kline: Boolean = false,
    /** Single modules can be asked on this connection (not ISO 9141-2, not 29-bit CAN): their memory on «Ошибки». */
    val moduleAccess: Boolean = true,
    /** Blocks whose dialect was recognised by their answers (request id → dialect id), see [com.obdscanner.car.Dialect]. */
    val dialects: Map<Int, String> = emptyMap(),
    /** The make (and so the blocks' dialects) is only the hand pick: no VIN, no model recognised by its answers. */
    val makeByPick: Boolean = false,
    /** The app-wide hand pick, not applied — another car: its protocol isn't the one the car answered on, or the VIN is another make's. */
    val misfitPick: CarChoice? = null,
    /** The make by the VIN when that is why [misfitPick] doesn't fit; null — the protocol is. */
    val misfitMake: String? = null,
    /** The hand pick is the one made on this car (kept by its VIN or calibration), not the app-wide one. */
    val pickForCar: Boolean = false,
    val memory: CarMemory = CarMemory(),
) {
    val supported01: Set<Int> get() = ecus.values.flatMap { it.pids01 }.toSet()
    /** PIDs asked with the Mode 01 broadcast (not the \$21 ones). */
    val functional01: Set<Int> get() = ecus.values.filter { it.pidReq == null }.flatMap { it.pids01 }.toSet()
    /** Car database family for manufacturer trouble codes (DtcDb). */
    val dtcFamily: String? get() = car?.family ?: make.id.ifEmpty { null }
}

/** The codes a check found ([com.obdscanner.gm.GmModule.key] + code → how «Ошибки» names it), [time] — when. */
data class PrevCodes(val time: Long, val codes: Map<String, String>)

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
    /** What a watched block's bytes look like (by hit key), once it was polled enough: [ScanDecode]. */
    val decoded: Map<String, ScanDecode.Result> = emptyMap(),
    /** How many polls each watched block has (by hit key): the screen says how far to the guess. */
    val polls: Map<String, Int> = emptyMap(),
    /** Fields saved as this car's parameters. */
    val params: List<ScanParam> = emptyList(),
    /** GM SW-CAN (pin 1): «Блоки на SW-CAN» and «Проверить ошибки»; null — not asked yet. */
    val sw: SwCanState? = null,
)

/**
 * The modules of GM's single-wire bus (pin 1). Apart from [ScanState.modules]: they answer only while the adapter is
 * switched there, so the polls and the scans leave them out; their codes are read with the others («Ошибки»).
 */
data class SwCanState(
    val running: Boolean = false,
    val status: String = "",
    val modules: List<GmModule> = emptyList(),
)
