package com.obdscanner.gm

import com.obdscanner.util.format
import com.obdscanner.car.CarFamily
import com.obdscanner.car.ExtCommand
import com.obdscanner.tr
import com.obdscanner.vag.Tp20

/** A diagnostic module found on the OBD port (CAN id, K-line address or a VAG TP 2.0 module — [Tp20.BASE] + its address). */
data class GmModule(
    val req: Int, val resp: Int, val name: String, val answeredTo: String,
    /** Its own diagnostic address (GM \$1A B0), once read; a TP 2.0 module: its VCDS address. */
    val diag: Int? = null,
    /**
     * Its part number (GM \$1A CB, end model), once read: the one on the module's label — a catalogue by the VIN may
     * give another, more common one (an owner checked two modules, issue #1).
     */
    val partNumber: Long? = null,
    /** The bus it is on when not the main one ([com.obdscanner.car.ExtraBus.id]); null — the main bus. */
    val bus: String? = null,
) {
    val id get() = Tp20.label(req)
    /** Unique over the buses: a request id may be taken on both ("swcan/24B"). */
    val key get() = bus?.let { "$it/$id" } ?: id
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
    private val all = tr("Всё 0000–FFFF (часы!)", "All 0000–FFFF (hours!)") to (0x0000..0xFFFF)
    private val ident = tr("Идентификация F100–F1FF", "Identification F100–F1FF") to (0xF100..0xF1FF)
    private fun block(from: Int) = tr("Блок %04X–%04X", "Block %04X–%04X").format(from, from + 0xFFF) to (from..from + 0xFFF)

    /** The \$22 ranges offered for a GM: its own parameters live in 1000–1FFF. */
    val gm = listOf(tr("Параметры GM 1000–1FFF", "GM parameters 1000–1FFF") to (0x1000..0x1FFF),
        block(0x2000), block(0x4000), block(0x8000), ident, all)

    /** Any other make: identification first (UDS F1xx answers on most blocks), then the blocks makers use. */
    val other = listOf(ident, block(0x0000), block(0x1000), block(0x2000), block(0x3000), block(0x4000), block(0xD000),
        tr("Режим 01 через \$22 F400–F4FF", "Mode 01 over \$22 F400–F4FF") to (0xF400..0xF4FF), all)

    /**
     * Where the make's known \$22 parameters ([commands], the car database) live, first in the list: the 256-ID pages
     * holding one, merged over a gap of one page, the [MAKE_RUNS] fullest, fullest first. A block the database knows nothing in is
     * likely to keep its own there too: the Outback BT's CVT refused all nine of its known ones, the make's CVTs sit in
     * 10xx–11xx and 30xx — a range of a few hundred, not 4096.
     */
    fun ofMake(title: String, commands: List<ExtCommand>): List<Pair<String, IntRange>> {
        val pages = commands.filter { it.service == "22" && it.did < 0xF100 }.groupingBy { it.did shr 8 }.eachCount().toList().sortedBy { it.first }
        val runs = ArrayList<Triple<Int, Int, Int>>()
        for ((p, n) in pages) {
            val last = runs.lastOrNull()
            if (last != null && p - last.second <= 2) runs[runs.size - 1] = Triple(last.first, p, last.third + n) else runs += Triple(p, p, n)
        }
        // The fullest first: the scan screen starts on the list's first range.
        return runs.sortedByDescending { it.third }.take(MAKE_RUNS).map { (a, b, n) ->
            tr("$title: %04X–%04X (известных $n)", "$title: %04X–%04X ($n known)").format(a shl 8, (b shl 8) + 0xFF) to ((a shl 8)..((b shl 8) + 0xFF))
        }
    }

    private const val MAKE_RUNS = 4

    fun forMake(gm: Boolean, family: CarFamily? = null) = if (gm) this.gm else family?.let { ofMake(it.title, it.commands) }.orEmpty() + other
}

/**
 * A field of a block saved as a parameter of this car (from «Скан блоков»): polled with the watched blocks,
 * shown in «Все данные» as [name], value = [a]·x + [b] of the [size]-byte field at [offset] of the answer.
 */
data class ScanParam(
    val req: Int, val resp: Int, val service: String, val did: Int,
    val offset: Int, val size: Int, val signed: Boolean, val a: Double, val b: Double,
    val name: String, val unit: String, val decimals: Int,
) {
    val hitKey get() = "%03X:%s.%04X".format(req, service, did)
    val key get() = "$hitKey@$offset.$size${if (signed) "s" else ""}"

    fun value(bytes: IntArray): Double? = ScanDecode.raw(bytes, offset, size, signed)?.let { a * it + b }

    /** One line of the saved list; '\t' and '\n' can't be in a name typed nowhere (it is built by the app). */
    fun encode() = listOf("%03X".format(req), "%03X".format(resp), service, "%04X".format(did), offset, size, if (signed) 1 else 0,
        a, b, name, unit, decimals).joinToString("\t")

    companion object {
        fun decode(line: String): ScanParam? {
            val p = line.split('\t')
            if (p.size != 12) return null
            return runCatching {
                ScanParam(p[0].toInt(16), p[1].toInt(16), p[2], p[3].toInt(16), p[4].toInt(), p[5].toInt(), p[6] == "1",
                    p[7].toDouble(), p[8].toDouble(), p[9], p[10], p[11].toInt())
            }.getOrNull()
        }
    }
}
