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

    private fun pairs(a: Arr, list: List<Pair<String, String>>) = list.forEach { (k, v) -> a.obj { str("k", k); str("v", v) } }

    fun carCard(c: CarCardView): String = obj {
        str("label", c.label); str("title", c.title); str("status", c.status); str("pick", c.pick)
        c.reads?.let { (k, v) -> obj("reads") { str("k", k); str("v", v) } }
        arr("details") { pairs(this, c.details) }
        c.features?.let { (t, items) -> obj("features") { str("title", t); arr("items") { items.forEach { str(it) } } } }
        bool("hasMore", c.hasMore); str("more", CarCardView.MORE); str("less", CarCardView.LESS)
    }

    fun picker(r: CarPicker.Rows, compareCount: Int): String = obj {
        arr("rows") {
            for (x in r.rows) obj {
                str("title", x.title); str("sub", x.sub); bool("selected", x.selected)
                str("pick", x.pick); str("open", x.open); str("compare", x.compare)
            }
        }
        bool("nothing", r.nothing)
        obj("texts") {
            str("title", CarPicker.TITLE); str("search", CarPicker.SEARCH); str("back", CarPicker.BACK); str("reset", CarPicker.RESET)
            str("close", CarPicker.CLOSE); str("nothing", CarPicker.NOTHING); str("compare", CarPicker.compare(compareCount))
        }
    }

    fun compare(c: CompareView): String = obj {
        str("title", c.title); str("names", c.names); str("empty", c.empty); str("same", c.same); str("close", c.close)
        arr("rows") { for ((t, groups) in c.rows) obj { str("title", t); arr("groups") { pairs(this, groups) } } }
    }

    fun guide(g: GuideView, car: CarCardView): String = obj {
        raw("car", carCard(car))
        str("steps", g.steps); num("collapsed", GuideView.COLLAPSED.toDouble()); str("showAll", GuideView.SHOW_ALL); str("collapse", GuideView.COLLAPSE)
        str("obdTitle", g.obdTitle); str("obdPicture", g.obdPicture); str("obdPlace", g.obdPlace)
        g.obdCommon?.let { a -> arr("obdCommon") { a.forEach { str(it) } } }
        str("obdNote", g.obdNote); str("detailsTitle", g.detailsTitle)
        arr("sections") { g.sections.forEach { str(it) } }
        g.features?.let { (t, items) -> obj("features") { str("title", t); arr("items") { items.forEach { str(it) } } } }
        arr("tail") { g.tail.forEach { str(it) } }
    }

    fun gm(g: GmView): String = obj {
        arr("hints") { g.hints.forEach { str(it) } }
        str("find", g.find); str("stop", g.stop)
        g.scan?.let { p -> obj("scan") { bool("running", p.running); num("progress", p.progress.toDouble()); str("status", p.status) } }
        str("busTitle", g.busTitle); str("busNote", g.busNote); str("busButton", g.busButton)
        g.bus?.let { p -> obj("bus") { bool("running", p.running); num("progress", p.progress.toDouble()); str("status", p.status) } }
        arr("busIds") { for (b in g.busIds) obj { str("id", b.id); str("hz", b.hz); str("last", b.last) } }
        str("rangeTitle", g.rangeTitle)
        arr("ranges") { g.ranges.forEach { str(it) } }
        str("modulesTitle", g.modulesTitle); str("modulesEmpty", g.modulesEmpty)
        arr("modules") { for (m in g.modules) obj { str("id", m.id); str("title", m.title); str("answeredTo", m.answeredTo) } }
        str("scanLabel", g.scanLabel); str("scanNote", g.scanNote)
        str("hitsTitle", g.hitsTitle); str("onlyWatched", g.onlyWatched); str("tickAll", g.tickAll); str("untick", g.untick); str("hitsNote", g.hitsNote)
        arr("hits") {
            for (h in g.hits) obj {
                str("key", h.key); str("title", h.title); str("data", h.data); str("partNumber", h.partNumber)
                str("ascii", h.ascii); str("live", h.live); bool("watched", h.watched)
            }
        }
    }

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
            is Block.Banner -> { str("t", "banner"); str("text", b.text); level(b.level); str("tab", b.tab?.name) }
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
