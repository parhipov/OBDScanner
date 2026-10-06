package com.obdscanner.gm

import com.obdscanner.util.format
import com.obdscanner.tr

/** A diagnostic module found on the OBD port (CAN id or K-line address). */
data class GmModule(
    val req: Int, val resp: Int, val name: String, val answeredTo: String,
    /** Its own diagnostic address (GM \$1A B0), once read. */
    val diag: Int? = null,
) {
    val id get() = "%03X".format(req)
}

/** A DID that answered positively during a scan. */
data class ScanHit(val req: Int, val resp: Int, val service: String, val did: Int, val data: IntArray) {
    val key get() = "%03X:%s.%04X".format(req, service, did)
    val didHex get() = if (GmScanner.oneByteId(service)) "%02X".format(did) else "%04X".format(did)
    val hex get() = data.joinToString(" ") { "%02X".format(it) }
    val ascii get() = data.map { if (it in 0x20..0x7E) it.toChar() else '·' }.joinToString("")
    val looksLikeText get() = data.size >= 4 && data.count { it in 0x20..0x7E } >= data.size * 0.8
    /** GM part/software numbers are 4-byte big-endian decimals (as printed on labels). */
    val partNumber: Long? get() = if (service == "1A" && did in 0xC0..0xCC && data.size == 4)
        data.fold(0L) { acc, b -> acc * 256 + b } else null
    /** GMLAN \$1A identifiers have names ([Gm1A]). */
    val label: String? get() = if (service == "1A") Gm1A.name(did) else null
    override fun equals(other: Any?) = other is ScanHit && other.key == key
    override fun hashCode() = key.hashCode()
}

/** GMLAN \$1A identifiers (GMW3110). */
object Gm1A {
    fun name(did: Int): String? = when (did) {
        0x90 -> "VIN"
        0x92 -> tr("Поставщик", "Supplier")
        0x97 -> tr("Имя системы / двигатель", "System name / engine")
        0x98 -> tr("Код СТО / номер тестера", "Repair shop code / tester no.")
        0x99 -> tr("Дата программирования", "Programming date")
        0x9A -> tr("Диагностический идентификатор", "Diagnostic identifier")
        0xA0 -> tr("Счётчик разрешений", "Permission counter")
        0xB0 -> tr("Диагностический адрес", "Diagnostic address")
        0xB4 -> tr("Трассировка", "Traceability")
        0xC0 -> tr("Номер загрузчика", "Boot loader part no.")
        in 0xC1..0xCA -> tr("Программный модуль ${did - 0xC0}", "Software module ${did - 0xC0}")
        0xCB -> tr("Номер детали (end model)", "Part no. (end model)")
        0xCC -> tr("Номер детали (base model)", "Part no. (base model)")
        in 0xD0..0xDC -> tr("Альфа-код ${did - 0xCF}", "Alpha code ${did - 0xCF}")
        0xDF -> tr("Одометр в модуле", "Module odometer")
        else -> null
    }
}

/** What the scan screen offers to scan (read requests, any make). */
object ScanRanges {
    /** Scan ranges offered in the UI (Mode 22). */
    val ranges22 = listOf(
        tr("Классические GM (1000–1FFF)", "Classic GM (1000–1FFF)") to (0x1000..0x1FFF),
        tr("Блок 2000–2FFF", "Block 2000–2FFF") to (0x2000..0x2FFF),
        tr("Блок 4000–4FFF", "Block 4000–4FFF") to (0x4000..0x4FFF),
        tr("Блок 8000–8FFF", "Block 8000–8FFF") to (0x8000..0x8FFF),
        tr("Идентификация F1xx (F100–F1FF)", "Identification F1xx (F100–F1FF)") to (0xF100..0xF1FF),
        tr("Всё 0000–FFFF (часы!)", "All 0000–FFFF (hours!)") to (0x0000..0xFFFF),
    )
}
