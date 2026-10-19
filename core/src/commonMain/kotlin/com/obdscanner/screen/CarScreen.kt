package com.obdscanner.screen

import com.obdscanner.VehicleInfo
import com.obdscanner.obd.Reading
import com.obdscanner.tr

/**
 * The car's screen (Android Auto): pages of [PAGE] tiles, the values chosen in «Все данные» (the car icon) in their
 * order, else Main as the phone shows it. The front fixes the tiles' keys and titles when the first live values come
 * ([slots]) and from then on only writes the values into them ([tile]): on the car a tile that appears or goes is a new
 * screen, not an update, and the host counts screens while driving.
 */
object CarScreen {
    /** Tiles on one page of the car's screen; «Все данные» draws a line after each page in the car group. */
    const val PAGE = 6

    val TITLE = tr("Экран машины", "Car screen")
    val CHOOSE = tr("Что показывать здесь, выбирается на телефоне: «Все данные», значок машины у значения.",
        "What is shown here is chosen on the phone: «All data», the car icon next to a value.")
    val NO_LINK = tr("Нет связи с адаптером. Подключитесь на телефоне, вкладка «Связь».",
        "No link to the adapter. Connect on the phone, the «Connect» tab.")
    val CONNECTING = tr("Подключение к адаптеру…", "Connecting to the adapter…")
    val WAITING = tr("Ждём данные от машины…", "Waiting for the car's data…")
    val NEXT = tr("Дальше", "Next")
    val CONNECT = tr("Подключиться", "Connect")
    val PAGE_OF = tr("Страница %d из %d", "Page %d of %d")

    /** A fixed tile: the reading's key, the title it keeps and the unit under it (both stay as the value changes). */
    class Slot(val key: String, val label: String, val unit: String)

    /**
     * The tiles for a first live snapshot: the chosen values that are live, else every card of Main (favourites and
     * «Как бензин?» first, as on the phone). Empty while nothing is live yet.
     */
    fun slots(r: Map<String, Reading>, v: VehicleInfo, watch: Boolean, carPins: List<String>, pins: List<String>, hidden: Set<String>): List<Slot> {
        if (r.isEmpty()) return emptyList()
        val chosen = carPins.mapNotNull { k -> r[k]?.let { Slot(k, it.name, it.unit) } }
        if (chosen.isNotEmpty()) return chosen
        return MainScreen.build(r, v, watch, pins, hidden).sections.flatMap { s -> s.tiles.map { Slot(it.reading.key, it.label, it.reading.unit) } }.distinctBy { it.key }
    }

    /** The slot's tile now: its value when the reading is live, else the label alone (the front writes a dash). */
    fun tile(slot: Slot, r: Map<String, Reading>): Tile? = r[slot.key]?.let { Tile(slot.label, it, MainScreen.tileLevel(it), false, null) }

    /** The pages of [slots], [PAGE] each. */
    fun pages(slots: List<Slot>): List<List<Slot>> = slots.chunked(PAGE)
}
