package com.obdscanner.screen

import com.obdscanner.Tab
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.Reading
import com.obdscanner.tr
import kotlin.math.abs

/*
 * What the screens show, without drawing it: the phone (Compose) and the browser page both render these
 * blocks, so a text, a threshold or a hint is written once, here. A front only knows how each kind of
 * block looks and what a [Level] and an [Action] mean to it.
 */

/** How a value or a line is coloured; each front has its palette (green / amber / red / grey). */
enum class Level { GOOD, WARN, BAD, MUTED }

/** A button's job; the front runs it (ObdManager on the phone). */
enum class Action { READ_DTC, CLEAR_DTC, READ_ALL_MODULES, RESCAN, MODE06, LICENSES, TERMS }

/**
 * A trouble code that opens its help on a tap ([DtcHelp]). [ftb] — failure type byte (GM \$A9 symptom /
 * UDS FTB), null when the protocol has none; [state] — the status as text ("active, history");
 * [kind] — for Mode 03/07/0A codes; [label] — the code as listed, "B1517 03" or "P0301 (VAG 16685)".
 */
data class DtcRef(
    val code: String,
    val module: String,
    val ftb: Int? = null,
    val gmFtb: Boolean = false,
    val state: String? = null,
    val kind: DtcKind? = null,
    val label: String = code,
)

class Button(val action: Action, val label: String, val primary: Boolean = false, val whileBusy: Boolean = false)

class Cylinder(val label: String, val value: String, val level: Level, val avg: String?)

class Cell(val text: String, val level: Level? = null)

class TableRow(val label: String, val cells: List<Cell>)

/** One piece of a screen; [key] is unique on the screen (list positions survive updates). */
sealed class Block(val key: String) {
    /** Section heading. */
    class Title(key: String, val text: String) : Block(key)

    /** Small grey text. */
    class Note(key: String, val text: String) : Block(key)

    /** A coloured card with a sentence: a hint or a warning; [tab] — a tap opens that tab. */
    class Banner(key: String, val text: String, val level: Level, val tab: Tab? = null) : Block(key)

    /**
     * "name ..... value unit", [sub] under the name; [dtc] — a tap opens the code's help; [doc] — a tap opens
     * that license ([Licenses.document]); [divider] — a line under it.
     */
    class Row(
        key: String, val name: String, val value: String, val unit: String = "", val sub: String? = null,
        val level: Level? = null, val dtc: DtcRef? = null, val divider: Boolean = false, val doc: String? = null,
    ) : Block(key)

    /** A live value as a row: under the name its source and the session's min…max. */
    class Value(key: String, val reading: Reading, val level: Level? = null) : Block(key)

    /** A fuel trim bank: short, long and total ([trimLevel] colours them), the total as a bar around zero. */
    class Bank(key: String, val title: String, val short: Reading?, val long: Reading?, val total: Reading?, val longRange: String?) : Block(key) {
        companion object {
            val SHORT = tr("Кратк.", "Short")
            val LONG = tr("Долг.", "Long")
            val TOTAL = tr("Сумма", "Total")
        }
    }

    /** Per-cylinder misfire counters in one card (empty when the ECU has none). */
    class Misfires(key: String, val cylinders: List<Cylinder>) : Block(key)

    /** A table in a card: [header] over the value columns, a label column first. */
    class Table(key: String, val header: List<String>, val rows: List<TableRow>) : Block(key)

    /** Buttons in a row, then [note] beside them; disabled while an operation runs unless [Button.whileBusy]. */
    class Buttons(key: String, val buttons: List<Button>, val note: String? = null) : Block(key)

    /** Some space at the end. */
    class Gap(key: String) : Block(key)
}

/** Collects a screen's blocks, keeping their keys unique. */
class Blocks {
    private val out = mutableListOf<Block>()
    private val used = HashMap<String, Int>()

    private fun key(base: String): String {
        val n = used[base] ?: 0
        used[base] = n + 1
        return if (n == 0) base else "$base#$n"
    }

    fun title(text: String) { out += Block.Title(key("t:$text"), text) }
    fun note(text: String) { out += Block.Note(key("n:$text"), text) }
    fun banner(text: String, level: Level, tab: Tab? = null) { out += Block.Banner(key("b:$text"), text, level, tab) }
    fun row(name: String, value: String, unit: String = "", sub: String? = null, level: Level? = null, dtc: DtcRef? = null, divider: Boolean = false, key: String? = null, doc: String? = null) {
        out += Block.Row(key(key ?: "r:$name"), name, value, unit, sub, level, dtc, divider, doc)
    }
    fun value(r: Reading, level: Level? = null) { out += Block.Value(key("v:${r.key}"), r, level) }
    fun bank(title: String, short: Reading?, long: Reading?, total: Reading?, longRange: String?) { out += Block.Bank(key("bank:$title"), title, short, long, total, longRange) }
    fun misfires(cylinders: List<Cylinder>) { out += Block.Misfires(key("misfires"), cylinders) }
    fun table(name: String, header: List<String>, rows: List<TableRow>) { out += Block.Table(key("table:$name"), header, rows) }
    fun buttons(buttons: List<Button>, note: String? = null) { out += Block.Buttons(key("buttons:" + buttons.joinToString { it.action.name }), buttons, note) }
    fun gap() { out += Block.Gap(key("gap")) }

    fun build(): List<Block> = out.toList()
}

/** Under a value row: its source, and the session's min…max once the value has moved. */
fun Reading.rowSub(): String {
    val lo = min
    val hi = max
    return if (value != null && lo != null && hi != null && lo != hi) "$source · ${Reading.fmt(lo, decimals)}…${Reading.fmt(hi, decimals)}" else source
}

/** Under a tile: the session's min and max once the value has moved; " " keeps the line so tiles in a row stay level. */
fun Reading.tileRange(): String {
    val lo = min
    val hi = max
    return if (value != null && lo != null && hi != null && lo != hi)
        tr("мин ${Reading.fmt(lo, decimals)} · макс ${Reading.fmt(hi, decimals)}", "min ${Reading.fmt(lo, decimals)} · max ${Reading.fmt(hi, decimals)}") else " "
}

/** Fuel trim colour: within ±8 % fine, ±15 % worth a look, beyond that wrong. */
fun trimLevel(v: Double?): Level? = when {
    v == null -> null
    abs(v) >= 15 -> Level.BAD
    abs(v) >= 8 -> Level.WARN
    else -> Level.GOOD
}
