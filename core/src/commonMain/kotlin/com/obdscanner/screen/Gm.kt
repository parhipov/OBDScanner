package com.obdscanner.screen

import com.obdscanner.BusState
import com.obdscanner.ScanState
import com.obdscanner.gm.ScanRanges
import com.obdscanner.obd.Reading
import com.obdscanner.tr
import com.obdscanner.util.format

/**
 * The GM scan screen (any make, really): find the modules, scan a module's IDs ($1A, $21, a range of $22),
 * tick the found ones to poll them, listen to the bus. What a front shows; it runs the buttons itself.
 */
class GmView(
    val hints: List<String>,
    val find: String,
    val stop: String,
    val scan: GmView.Progress?,
    val busTitle: String,
    val busNote: String,
    val busButton: String,
    val bus: GmView.Progress?,
    val busIds: List<BusRow>,
    val rangeTitle: String,
    /** The $22 ranges' names, in [ScanRanges.ranges22] order. */
    val ranges: List<String>,
    val modulesTitle: String,
    val modulesEmpty: String?,
    val modules: List<Module>,
    val scanLabel: String,
    val scanNote: String,
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
    /** [id] — what the scan buttons send back. */
    class Module(val id: String, val title: String, val answeredTo: String)
    class Hit(val key: String, val title: String, val data: String, val partNumber: String?, val ascii: String?, val live: String?, val watched: Boolean)
}

object GmScreen {
    fun build(s: ScanState, bus: BusState, r: Map<String, Reading>, onlyWatched: Boolean): GmView {
        val hits = if (onlyWatched) s.hits.filter { it.key in s.watched } else s.hits
        return GmView(
            hints = listOf(
                tr("Старый ELM327 видит только HS-CAN: ECM, TCM, ABS, BCM и др. Подушки, приборка, радио, климат, TPMS — на однопроводной GMLAN (пин 1), для них нужен OBDLink MX+.",
                    "An old ELM327 sees HS-CAN only: ECM, TCM, ABS, BCM etc. Airbags, cluster, radio, climate, TPMS are on single-wire GMLAN (pin 1) and need an OBDLink MX+."),
                tr("Только чтение (сервисы \$1A и \$22). Сканировать на стоящей машине: зажигание включено или двигатель на ХХ. " +
                    "Найденные DID-ы пишутся в scan.csv сессии. Отметьте ☑ интересные — они будут опрашиваться, и по логу можно понять, что это за параметр.",
                    "Read only (services \$1A and \$22). Scan with the car parked: ignition on or engine at idle. " +
                        "Found DIDs are written to the session's scan.csv. Tick ☑ the interesting ones: they will be polled, and the log shows what the parameter is."),
            ),
            find = tr("Найти модули", "Find modules"),
            stop = tr("Стоп", "Stop"),
            scan = if (s.running || s.status.isNotEmpty()) GmView.Progress(s.running, s.progress, s.status) else null,
            busTitle = tr("Прослушка шины", "Bus sniffing"),
            busNote = tr("Слушает обычный обмен между блоками (ничего не отправляет в блоки). ~10 с обзор, потом по 1.5 с на каждый ID — обычно 1–2 минуты. Лучше с заведённым двигателем; можно погазовать. Пишется в bus.csv.",
                "Listens to the normal traffic between modules (sends nothing to them). ~10 s overview, then 1.5 s per ID — usually 1–2 minutes. Best with the engine running; revving helps. Written to bus.csv."),
            busButton = tr("Слушать шину", "Sniff bus"),
            bus = if (bus.running || bus.status.isNotEmpty()) GmView.Progress(bus.running, bus.progress, bus.status) else null,
            busIds = bus.ids.map { b -> GmView.BusRow(b.idHex, if (b.hz > 0) tr("%.0f Гц", "%.0f Hz").format(b.hz) else "—", b.lastHex) },
            rangeTitle = tr("Диапазон для \$22", "Range for \$22"),
            ranges = ScanRanges.ranges22.map { it.first },
            modulesTitle = tr("Модули на HS-CAN (${s.modules.size})", "Modules on HS-CAN (${s.modules.size})"),
            modulesEmpty = if (s.modules.isEmpty()) tr("Нажмите «Найти модули». Опрос ~40 адресов, около минуты.", "Tap \"Find modules\". Polls ~40 addresses, about a minute.") else null,
            modules = s.modules.map { m -> GmView.Module(m.id, "${m.name}  ·  %03X → %03X".format(m.req, m.resp), m.answeredTo) },
            scanLabel = tr("Скан:", "Scan:"),
            scanNote = tr("\$1A и \$21 — по 256 номеров, ~20–40 с; \$21 — данные блока у Toyota (KWP). \$22 — выбранный диапазон.",
                "\$1A and \$21: 256 IDs each, ~20–40 s; \$21 is Toyota block data (KWP). \$22: the selected range."),
            hitsTitle = tr("Найдено DID: ${s.hits.size}", "DIDs found: ${s.hits.size}"),
            onlyWatched = tr("только отмеченные", "ticked only"),
            tickAll = tr("Отметить все с данными", "Tick all with data"),
            untick = tr("Снять", "Untick"),
            hitsNote = if (s.hits.isNotEmpty()) tr("Отмеченные опрашиваются и пишутся в data.csv: на этой вкладке каждый цикл, на других раз в 3 с.",
                "Ticked DIDs are polled and written to data.csv: every cycle on this tab, every 3 s on the others. So they can be decoded later from the recording (utils/scan_decode.py).") else null,
            hits = hits.map { h ->
                val live = r[h.key]
                GmView.Hit(
                    key = h.key,
                    title = tr("%03X  %s %s  (%d байт)", "%03X  %s %s  (%d bytes)").format(h.req, h.service, h.didHex, h.data.size) + (h.label?.let { " · $it" } ?: ""),
                    data = live?.text ?: h.hex,
                    partNumber = h.partNumber?.let { tr("№ $it", "P/N $it") },
                    ascii = if (h.looksLikeText) tr("«${h.ascii}»", "\"${h.ascii}\"") else null,
                    live = if (live?.value != null) tr("A/AB = ${live.display()} (мин ${live.min?.let { Reading.fmt(it, 0) }}, макс ${live.max?.let { Reading.fmt(it, 0) }})",
                        "A/AB = ${live.display()} (min ${live.min?.let { Reading.fmt(it, 0) }}, max ${live.max?.let { Reading.fmt(it, 0) }})") else null,
                    watched = h.key in s.watched,
                )
            },
        )
    }
}
