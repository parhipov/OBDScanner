package com.obdscanner.screen

import com.obdscanner.obd.Reading
import com.obdscanner.util.format

/**
 * The screens as JSON, for the browser page (it draws the same blocks the phone does). Levels are
 * "good" / "warn" / "bad" / "muted", colours "#RRGGBB"; a value comes ready to show (formatted, with its
 * unit only when there is a number, the line under it already written).
 */
object ScreenJson {
    fun main(v: MainView): String = obj {
        bool("offline", v.offline)
        arr("top") { v.top.forEach { b -> raw(block(b)) } }
        arr("sections") {
            for (s in v.sections) obj {
                str("title", s.title)
                str("accent", color(s.accent))
                str("tile", color(s.tile))
                arr("tiles") {
                    for (t in s.tiles) obj {
                        str("label", t.label)
                        str("key", t.reading.key)
                        str("value", t.reading.display())
                        str("unit", if (t.reading.value != null) t.reading.unit else "")
                        str("range", t.note ?: t.reading.tileRange())
                        level(t.level)
                        bool("sample", t.sample)
                        raw("help", help(t.label, t.reading, t.sample))
                    }
                }
            }
        }
    }

    fun blocks(list: List<Block>): String = arr { list.forEach { raw(block(it)) } }

    fun dtcHelp(h: DtcHelpView): String = obj {
        str("label", h.label)
        str("title", h.title)
        str("untranslated", h.untranslated)
        str("module", h.module)
        arr("sections") { for ((t, x) in h.sections) obj { str("title", t); str("text", x) } }
        str("source", h.source)
        str("close", DtcHelpView.CLOSE)
    }

    /** One license: its title, the notice lines and the text's file (in licenses/). */
    fun license(d: Licenses.Document): String = obj {
        str("title", d.title)
        arr("lines") { d.lines.forEach { str(it) } }
        str("text", d.text)
    }

    /** What a card's help dialog needs: the texts by name ([CardHelp]), where the value comes from, its range. */
    private fun help(label: String, r: Reading, sample: Boolean): String = obj {
        str("label", label)
        str("texts", CardHelp.forReading(r))
        val (res, args) = CardHelp.source(r)
        str("sourceRes", res)
        arr("sourceArgs") { args.forEach { str(it) } }
        str("value", if (r.value != null || r.text != null) r.display() + if (r.value != null && r.unit.isNotEmpty()) " ${r.unit}" else "" else null)
        val lo = r.min
        val hi = r.max
        if (!sample && r.value != null && lo != null && hi != null) arr("range") { str(Reading.fmt(lo, r.decimals)); str(Reading.fmt(hi, r.decimals)) }
        bool("sample", sample)
    }

    private fun block(b: Block): String = obj {
        str("key", b.key)
        when (b) {
            is Block.Title -> { str("t", "title"); str("text", b.text) }
            is Block.Note -> { str("t", "note"); str("text", b.text) }
            is Block.Banner -> { str("t", "banner"); str("text", b.text); level(b.level) }
            is Block.Row -> {
                str("t", "row"); str("name", b.name); str("value", b.value); str("unit", b.unit); str("sub", b.sub); level(b.level)
                b.dtc?.let { raw("dtc", dtc(it)) }
                str("doc", b.doc)
                bool("divider", b.divider)
            }
            is Block.Value -> {
                val r = b.reading
                str("t", "row"); str("name", r.name); str("value", r.display()); str("unit", if (r.value != null) r.unit else "")
                str("sub", r.rowSub()); level(b.level)
            }
            is Block.Bank -> {
                str("t", "bank"); str("title", b.title)
                arr("cells") {
                    for ((label, r) in listOf(Block.Bank.SHORT to b.short, Block.Bank.LONG to b.long, Block.Bank.TOTAL to b.total)) obj {
                        str("label", label); str("value", r?.display() ?: "—"); level(trimLevel(r?.value))
                    }
                }
                num("total", b.total?.value)
                level("totalLevel", trimLevel(b.total?.value))
                str("longRange", b.longRange)
            }
            is Block.Misfires -> {
                str("t", "misfires")
                arr("cylinders") { for (c in b.cylinders) obj { str("label", c.label); str("value", c.value); level(c.level); str("avg", c.avg) } }
            }
            is Block.Table -> {
                str("t", "table")
                arr("header") { b.header.forEach { str(it) } }
                arr("rows") {
                    for (r in b.rows) obj {
                        str("label", r.label)
                        arr("cells") { for (c in r.cells) obj { str("text", c.text); level(c.level) } }
                    }
                }
            }
            is Block.Buttons -> {
                str("t", "buttons")
                arr("buttons") { for (x in b.buttons) obj { str("action", x.action.name); str("label", x.label); bool("primary", x.primary); bool("whileBusy", x.whileBusy) } }
                str("note", b.note)
            }
            is Block.Gap -> str("t", "gap")
        }
    }

    private fun dtc(d: DtcRef): String = obj {
        str("code", d.code); str("module", d.module); num("ftb", d.ftb?.toDouble()); bool("gmFtb", d.gmFtb)
        str("state", d.state); str("kind", d.kind?.name); str("label", d.label)
    }

    private fun color(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    // ---------------------------------------------------------------- a small JSON writer

    private class Obj {
        val sb = StringBuilder("{")
        private var first = true
        private fun key(k: String) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(k)).append(':')
        }
        fun str(k: String, v: String?) { if (v != null) { key(k); sb.append(quote(v)) } }
        fun num(k: String, v: Double?) { if (v != null && v.isFinite()) { key(k); sb.append(number(v)) } }
        fun bool(k: String, v: Boolean) { key(k); sb.append(v) }
        fun level(v: Level?) = level("level", v)
        fun level(k: String, v: Level?) = str(k, v?.name?.lowercase())
        fun raw(k: String, json: String) { key(k); sb.append(json) }
        fun arr(k: String, body: Arr.() -> Unit) { key(k); sb.append(Arr().apply(body).done()) }
        fun obj(k: String, body: Obj.() -> Unit) { key(k); sb.append(Obj().apply(body).done()) }
        fun done() = sb.append('}').toString()
    }

    private class Arr {
        val sb = StringBuilder("[")
        private var first = true
        private fun next() {
            if (!first) sb.append(',')
            first = false
        }
        fun str(v: String) { next(); sb.append(quote(v)) }
        fun raw(json: String) { next(); sb.append(json) }
        fun obj(body: Obj.() -> Unit) { next(); sb.append(Obj().apply(body).done()) }
        fun done() = sb.append(']').toString()
    }

    private fun obj(body: Obj.() -> Unit) = Obj().apply(body).done()
    private fun arr(body: Arr.() -> Unit) = Arr().apply(body).done()

    private fun number(v: Double): String = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
