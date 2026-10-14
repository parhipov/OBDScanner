package com.obdscanner.screen

import com.obdscanner.BusState
import com.obdscanner.ScanState
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.ScanDecode
import com.obdscanner.obd.Reading
import com.obdscanner.obd.pick
import com.obdscanner.tr
import com.obdscanner.util.format
import com.obdscanner.vag.Tp20
import com.obdscanner.vag.VagBlocks
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The make-dependent parts of «Скан блоков» (CarLink.scanContext): a GM or not, the \$22 ranges ([ranges] — what
 * the range index the buttons send back means), and which services a block is asked.
 */
class ScanContext(val gm: Boolean, val ranges: List<Pair<String, IntRange>>, val services: (GmModule) -> List<String>)

/**
 * «Скан блоков» (any make): find the blocks, scan a block's identifiers, tick the found ones to poll them — the app
 * then guesses what their bytes are — save a field as the car's parameter, listen to the bus. What a front shows;
 * it runs the buttons itself.
 */
class GmView(
    val hints: List<String>,
    val find: String,
    val stop: String,
    val scan: GmView.Progress?,
    /** GM: «Блоки на SW-CAN» ([com.obdscanner.CarLink.swCanModules]); null — not a GM. */
    val swButton: String?,
    val swNote: String?,
    val sw: GmView.Progress?,
    /** What it found: a module per row, its codes under it. */
    val swRows: List<String>,
    val busTitle: String,
    val busNote: String,
    val busButton: String,
    val bus: GmView.Progress?,
    val busIds: List<BusRow>,
    val rangeTitle: String,
    /** The \$22 ranges' names, in [ScanContext.ranges] order. */
    val ranges: List<String>,
    val modulesTitle: String,
    val modulesEmpty: String?,
    val modules: List<Module>,
    val scanLabel: String,
    val scanNote: String,
    val paramsTitle: String?,
    val params: List<Param>,
    val hitsTitle: String,
    val onlyWatched: String,
    val tickAll: String,
    val untick: String,
    val hitsNote: String?,
    val hits: List<Hit>,
) {
    /** [running] — the bar shows; [status] under it. */
    class Progress(val running: Boolean, val progress: Float, val status: String)
    class BusRow(val id: String, val hz: String, val last: String)
    /** [id] — what the scan buttons send back; [services] — the block's scans, service code and its name. */
    class Module(val id: String, val title: String, val answeredTo: String, val services: List<Service>)
    class Service(val id: String, val label: String)
    /** A saved field: [key] — what «remove» sends back. */
    class Param(val key: String, val title: String, val value: String, val remove: String)
    class Hit(val key: String, val title: String, val data: String, val partNumber: String?, val ascii: String?, val live: String?, val watched: Boolean,
              /** Under a watched block: how far to the guess, or what its bytes look like. */
              val decodeNote: String?, val fields: List<Field>)
    /** One field of a watched block: [id] and [save] — what «Сохранить» sends back (null — saved already). */
    class Field(val id: String, val text: String, val matched: Boolean, val save: String?)
}

object GmScreen {
    private val SERVICE_NAMES = mapOf(
        "1A" to tr("Идентификация", "Identification"),
        "21" to tr("Данные блока", "Block data"),
        "22" to tr("Параметры", "Parameters"),
    )

    fun build(s: ScanState, bus: BusState, r: Map<String, Reading>, onlyWatched: Boolean, ctx: ScanContext): GmView {
        val hits = if (onlyWatched) s.hits.filter { it.key in s.watched } else s.hits
        val saved = s.params.map { it.key }.toSet()
        return GmView(
            hints = listOfNotNull(
                if (ctx.gm) tr("Старый ELM327 видит только HS-CAN: ECM, TCM, ABS, BCM и др. Часть блоков (у разных машин разные: подушки, климат, проекция, радио…) — на однопроводной GMLAN (пин 1): для них нужен адаптер с SW-CAN, кнопка «Блоки на SW-CAN» ниже.",
                    "An old ELM327 sees HS-CAN only: ECM, TCM, ABS, BCM etc. Some modules (they differ by car: airbags, HVAC, head-up display, radio…) are on single-wire GMLAN (pin 1): they need an adapter with SW-CAN, the «SW-CAN modules» button below.") else null,
                tr("Только чтение: блоки ничего не меняют и не стирают. Сканировать на стоящей машине: зажигание включено или двигатель на ХХ. " +
                    "Найденное пишется в scan.csv сессии. Отметьте ☑ интересное — оно будет опрашиваться, и приложение попробует понять, что это за параметр.",
                    "Read only: the blocks change and erase nothing. Scan with the car parked: ignition on or engine at idle. " +
                        "What is found goes to the session's scan.csv. Tick ☑ the interesting ones: they are polled, and the app tries to tell what the parameter is."),
            ),
            find = tr("Найти блоки", "Find blocks"),
            stop = tr("Стоп", "Stop"),
            scan = if (s.running || s.status.isNotEmpty()) GmView.Progress(s.running, s.progress, s.status) else null,
            swButton = if (ctx.gm) tr("Блоки на SW-CAN", "SW-CAN modules") else null,
            swNote = if (ctx.gm) tr("Однопроводная шина GM (пин 1): найти блоки, прочитать их номера и ошибки, потом вернуться на основную шину. Только чтение, 1–2 минуты. " +
                "Нужен адаптер, который понимает команды OBDLink (STP 63); обычный ELM327 не умеет — тогда приложение так и скажет, ничего не меняя.",
                "GM's single-wire bus (pin 1): find the modules, read their numbers and codes, then back to the main bus. Read only, 1–2 minutes. " +
                    "Needs an adapter that knows OBDLink's commands (STP 63); a plain ELM327 doesn't — the app then says so and changes nothing.") else null,
            sw = s.sw?.let { GmView.Progress(it.running, 0f, it.status) },
            swRows = s.sw?.let { w ->
                w.modules.map { m ->
                    val r = w.dtcs.firstOrNull { it.module.req == m.req }
                    "${m.name}  ·  %03X → %03X".format(m.req, m.resp) + (m.diag?.let { tr(", адрес %02X", ", address %02X").format(it) } ?: "") +
                        (r?.let { " — ${it.result}" + it.codes.joinToString("") { c -> "\n    ${c.full}  ${c.description}" } } ?: "")
                }
            }.orEmpty(),
            busTitle = tr("Прослушка шины", "Bus sniffing"),
            busNote = tr("Слушает обычный обмен между блоками (ничего не отправляет в блоки). ~10 с обзор, потом по 1.5 с на каждый ID — обычно 1–2 минуты. Лучше с заведённым двигателем; можно погазовать. Пишется в bus.csv.",
                "Listens to the normal traffic between modules (sends nothing to them). ~10 s overview, then 1.5 s per ID — usually 1–2 minutes. Best with the engine running; revving helps. Written to bus.csv."),
            busButton = tr("Слушать шину", "Sniff bus"),
            bus = if (bus.running || bus.status.isNotEmpty()) GmView.Progress(bus.running, bus.progress, bus.status) else null,
            busIds = bus.ids.map { b -> GmView.BusRow(b.idHex, if (b.hz > 0) tr("%.0f Гц", "%.0f Hz").format(b.hz) else "—", b.lastHex) },
            rangeTitle = tr("Диапазон для «Параметры»", "Range for «Parameters»"),
            ranges = ctx.ranges.map { it.first },
            modulesTitle = tr("Блоки (${s.modules.size})", "Blocks (${s.modules.size})"),
            modulesEmpty = if (s.modules.isEmpty()) tr("Нажмите «Найти блоки»: опрос адресов, на которых у этой марки бывают блоки, около минуты.",
                "Tap «Find blocks»: polls the addresses this make keeps its blocks on, about a minute.") else null,
            modules = s.modules.map { m ->
                GmView.Module(m.id, if (Tp20.isTp(m.req)) tr("${m.name}  ·  TP 2.0, адрес %02X", "${m.name}  ·  TP 2.0, address %02X").format(m.req - Tp20.BASE)
                    else "${m.name}  ·  %03X → %03X".format(m.req, m.resp), m.answeredTo,
                    ctx.services(m).map { s ->
                        // On a VAG TP 2.0 module \$21 reads the measuring blocks (groups), as VCDS calls them.
                        GmView.Service(s, if (s == "21" && Tp20.isTp(m.req)) tr("Группы измерений", "Measuring blocks") else SERVICE_NAMES[s] ?: "\$$s")
                    })
            },
            scanLabel = tr("Скан:", "Scan:"),
            scanNote = tr("Идентификация — номера и версии блока (\$1A). Данные блока (\$21) — 256 номеров, ~20–40 с. Параметры (\$22) — выбранный диапазон.",
                "Identification: the block's numbers and versions (\$1A). Block data (\$21): 256 IDs, ~20–40 s. Parameters (\$22): the selected range."),
            paramsTitle = if (s.params.isNotEmpty()) tr("Сохранённые параметры (${s.params.size})", "Saved parameters (${s.params.size})") else null,
            params = s.params.map { p ->
                val v = r[p.key]
                GmView.Param(p.key, p.name, v?.let { it.display() + if (it.value != null && it.unit.isNotEmpty()) " ${it.unit}" else "" } ?: "—", tr("Убрать", "Remove"))
            },
            hitsTitle = tr("Найдено: ${s.hits.size}", "Found: ${s.hits.size}"),
            onlyWatched = tr("только отмеченные", "ticked only"),
            tickAll = tr("Отметить все с данными", "Tick all with data"),
            untick = tr("Снять", "Untick"),
            hitsNote = if (s.hits.isNotEmpty()) tr("Отмеченные опрашиваются и пишутся в data.csv: на этой вкладке каждый цикл, на других раз в 3 с. " +
                "Через ${ScanDecode.MIN_SAMPLES} опросов приложение сверяет их байты с обычными параметрами — лучше в движении или погазовав. Найденное можно сохранить: оно появится в «Все данные».",
                "Ticked ones are polled and written to data.csv: every cycle on this tab, every 3 s on the others. " +
                    "After ${ScanDecode.MIN_SAMPLES} polls the app matches their bytes against the usual parameters — best while driving or revving. A match can be saved: it shows in «All data».") else null,
            hits = hits.map { h ->
                val live = r[h.key]
                val watched = h.key in s.watched
                val dec = s.decoded[h.key]
                val polls = s.polls[h.key] ?: 0
                // A VAG measuring block: its fields by their formula types, from the last poll.
                val block = if (Tp20.isTp(h.req) && h.service == "21") VagBlocks.describe(live?.text?.let(::bytes) ?: h.data) else null
                GmView.Hit(
                    key = h.key,
                    title = "%s  %s %s  (%d %s)".format(Tp20.label(h.req), h.service, h.didHex, h.data.size, bytesWord(h.data.size)) + (h.label?.let { " · $it" } ?: ""),
                    data = live?.text ?: h.hex,
                    partNumber = h.partNumber?.let { tr("№ $it", "P/N $it") },
                    ascii = if (h.looksLikeText) tr("«${h.ascii}»", "\"${h.ascii}\"") else null,
                    live = block ?: if (live?.value != null) tr("A/AB = ${live.display()} (мин ${live.min?.let { Reading.fmt(it, 0) }}, макс ${live.max?.let { Reading.fmt(it, 0) }})",
                        "A/AB = ${live.display()} (min ${live.min?.let { Reading.fmt(it, 0) }}, max ${live.max?.let { Reading.fmt(it, 0) }})") else null,
                    watched = watched,
                    decodeNote = when {
                        !watched || h.looksLikeText -> null
                        dec == null -> tr("Собираю опросы: $polls из ${ScanDecode.MIN_SAMPLES}", "Collecting polls: $polls of ${ScanDecode.MIN_SAMPLES}")
                        dec.matched.isEmpty() && dec.varying.isEmpty() -> tr("За ${dec.samples} опросов ни один байт не менялся", "No byte changed in ${dec.samples} polls")
                        dec.matched.isEmpty() -> tr("За ${dec.samples} опросов совпадений с обычными параметрами нет", "No match with the usual parameters in ${dec.samples} polls")
                        else -> tr("По ${dec.samples} опросам:", "From ${dec.samples} polls:")
                    },
                    fields = if (!watched || dec == null) emptyList() else
                        dec.matched.map { f -> field(h.key, f, r, saved) } + dec.varying.map { f -> field(h.key, f, r, saved) },
                )
            },
        )
    }

    /** "0A 3C 00" back to bytes; null — not that. */
    private fun bytes(hex: String): IntArray? = hex.split(' ').filter { it.isNotEmpty() }.takeIf { p -> p.isNotEmpty() && p.all { it.length == 2 } }
        ?.map { it.toIntOrNull(16) ?: return null }?.toIntArray()

    private fun field(hitKey: String, f: ScanDecode.Field, r: Map<String, Reading>, saved: Set<String>): GmView.Field {
        val bytes = if (f.size == 1) tr("Байт ${ScanDecode.letter(f.offset)}", "Byte ${ScanDecode.letter(f.offset)}")
            else tr("Байты ${ScanDecode.letter(f.offset)}–${ScanDecode.letter(f.offset + 1)}", "Bytes ${ScanDecode.letter(f.offset)}–${ScanDecode.letter(f.offset + 1)}")
        val pct = (abs(f.r) * 100).roundToInt()
        val name = f.ref?.let { r.pick(it)?.name ?: it }
        val text = when {
            f.same -> {
                val unit = r.pick(f.ref!!)?.unit?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""
                tr("$bytes: похоже на «$name» = ${f.formula()}$unit · совпадение $pct %", "$bytes: looks like «$name» = ${f.formula()}$unit · match $pct %")
            }
            name != null -> tr("$bytes: меняется вместе с «$name» · совпадение $pct %", "$bytes: moves with «$name» · match $pct %")
            else -> tr("$bytes: меняется ${f.min}…${f.max} (${f.distinct} ${values(f.distinct)}), пары нет",
                "$bytes: varies ${f.min}…${f.max} (${f.distinct} values), no match")
        }
        val key = "$hitKey@${f.offset}.${f.size}${if (f.signed) "s" else ""}"
        return GmView.Field(f.id, text, f.same, if (key in saved) null else tr("Сохранить", "Save"))
    }

    /** Russian plural after a number: 1 байт, 2 байта, 5 байт (and the same for «значение»). */
    private fun plural(n: Int, one: String, few: String, many: String) = when {
        n % 100 in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }

    private fun bytesWord(n: Int) = tr(plural(n, "байт", "байта", "байт"), if (n == 1) "byte" else "bytes")
    private fun values(n: Int) = plural(n, "значение", "значения", "значений")
}
