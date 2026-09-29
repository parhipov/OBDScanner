package com.obdscanner.obd

import com.obdscanner.tr

/**
 * One live value. [key] = "7E8:01.0C" (ECU header : source), multi-output PIDs add a suffix ("7E8:01.14.V").
 * Computed values use ECU 0 ("000:calc.lph").
 */
data class Reading(
    val key: String,
    val ecu: Int,
    val name: String,
    val value: Double?,
    val text: String?,
    val unit: String,
    val decimals: Int = 1,
    val min: Double? = value,
    val max: Double? = value,
    val time: Long = System.currentTimeMillis(),
) {
    val source get() = key.substringAfter(':')

    fun display(): String = when {
        value != null -> fmt(value, decimals)
        text != null -> text
        else -> "—"
    }

    fun merged(old: Reading?): Reading {
        if (old == null || value == null) return this
        return copy(
            min = listOfNotNull(old.min, value).minOrNull(),
            max = listOfNotNull(old.max, value).maxOrNull(),
        )
    }

    companion object {
        fun key(ecu: Int, source: String) = "%03X:%s".format(ecu, source)
        fun fmt(v: Double, decimals: Int) = if (decimals <= 0) Math.round(v).toString() else "%.${decimals}f".format(v)
    }
}

fun ecuName(header: Int): String = when (header) {
    0 -> tr("Расчёт", "Calculated")
    // K-line: the header is the ECU's source address (ISO 9141-2 / 14230).
    0x10 -> tr("ECM (двигатель)", "ECM (engine)")
    in 0x01..0xFF -> tr("ЭБУ %02X", "ECU %02X").format(header)
    0x7E8 -> tr("ECM (двигатель)", "ECM (engine)")
    0x7E9 -> tr("ЭБУ 7E9", "ECU 7E9")
    0x7EA -> tr("TCM (АКПП)", "TCM (transmission)")
    0x7EB -> tr("ЭБУ 7EB", "ECU 7EB")
    else -> tr("ЭБУ %03X", "ECU %03X").format(header)
}

/** Finds a value by source regardless of ECU, preferring the engine ECU. */
fun Map<String, Reading>.pick(source: String): Reading? =
    this[Reading.key(0x7E8, source)] ?: this[Reading.key(0, source)] ?: values.firstOrNull { it.source == source }
