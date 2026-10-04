package com.obdscanner

import com.obdscanner.bus.BusId
import com.obdscanner.car.CarModel
import com.obdscanner.car.ExtCommand
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.ScanHit
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
    val multiPid: Boolean = false,
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
    /** Blocks whose dialect was recognised by their answers (request id → dialect id), see [com.obdscanner.car.Dialect]. */
    val dialects: Map<Int, String> = emptyMap(),
    /** The make (and so the blocks' dialects) is only the hand pick: no VIN, no model recognised by its answers. */
    val makeByPick: Boolean = false,
) {
    val supported01: Set<Int> get() = ecus.values.flatMap { it.pids01 }.toSet()
    /** PIDs asked with the Mode 01 broadcast (not the \$21 ones). */
    val functional01: Set<Int> get() = ecus.values.filter { it.pidReq == null }.flatMap { it.pids01 }.toSet()
    /** Car database family for manufacturer trouble codes (DtcDb). */
    val dtcFamily: String? get() = car?.family ?: make.id.ifEmpty { null }
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
