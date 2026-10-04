package com.obdscanner.screen

import com.obdscanner.CarLink
import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.car.CarFamily
import com.obdscanner.car.CarModel
import com.obdscanner.car.ExtCommand
import com.obdscanner.obd.Make
import com.obdscanner.tr
import com.obdscanner.util.format

/** Names of the car database's codes, for the car card, the picker, the comparison and the guide. */
object CarText {
    /** Socket location ids of the car database (tools/cars/SCHEMA.md `obd`) → the picture's name (art/obd/make_obd_art.py). */
    fun obdPicture(loc: String?): String? = loc?.takeIf { it in PLACES }?.let { "obd_loc_$it" }

    fun obdPlace(loc: String?): String? = when (loc) {
        "left_door" -> tr("Под панелью слева от рулевой колонки, ближе к двери / ручке капота.", "Under the dash left of the steering column, towards the door / hood release.")
        "under_column" -> tr("Прямо под рулевой колонкой.", "Right under the steering column.")
        "right_of_column" -> tr("Под панелью между рулевой колонкой и центральной консолью.", "Under the dash between the steering column and the centre console.")
        "left_cover" -> tr("Слева под рулём за крышкой блока предохранителей или накладкой — снимите её.", "Left under the wheel behind the fuse box cover or a trim panel — remove it.")
        "console" -> tr("В центральной консоли: под пепельницей / нишей или за накладкой тоннеля.", "In the centre console: under the ashtray / cubby or behind the tunnel trim.")
        "passenger" -> tr("Со стороны пассажира: в ногах или у бардачка.", "On the passenger side: footwell or glovebox.")
        else -> null
    }

    private val PLACES = setOf("left_door", "under_column", "right_of_column", "left_cover", "console", "passenger")

    fun protocolName(p: Int?): String? = when (p) {
        null -> null
        1 -> "J1850 PWM"
        2 -> "J1850 VPW"
        else -> CarLink.PROTOCOLS[p]
    }

    /** Main-screen roles → what the card calls them (for "what is read on this make"). */
    fun roleName(role: String): String? = when (role) {
        "oil_temp" -> tr("температура масла", "oil temperature")
        "oil_pressure" -> tr("давление масла", "oil pressure")
        "oil_life" -> tr("ресурс масла", "oil life")
        "oil_level" -> tr("уровень масла", "oil level")
        "atf_temp" -> tr("масло АКПП", "transmission fluid")
        "cvt_temp" -> tr("масло вариатора", "CVT fluid")
        "clutch_temp" -> tr("температура сцепления", "clutch temperature")
        "cvt_wear" -> tr("износ масла вариатора", "CVT fluid wear")
        "gear" -> tr("передача", "gear")
        "tc_slip" -> tr("проскальзывание ГТ", "TC slip")
        "fuel_level_l" -> tr("литры в баке", "fuel in litres")
        "odometer" -> tr("пробег", "odometer")
        "battery_soc" -> tr("заряд АКБ", "battery charge")
        "knock_retard" -> tr("детонация", "knock")
        "boost" -> tr("наддув", "boost")
        "dpf_soot" -> tr("сажа DPF", "DPF soot")
        "service_km", "service_days" -> tr("до ТО", "service due")
        "hv_soc", "hv_soh" -> tr("ВВ батарея", "HV battery")
        else -> null
    }

    /** Requests known on the model, else on the make, else the whole family (what the card describes). */
    fun knownOn(family: CarFamily, brand: String?, car: CarModel?): List<ExtCommand> = when {
        car != null -> family.commandsFor(car)
        brand != null -> family.commandsOfBrand(brand)
        else -> family.commands
    }
}

/**
 * The car the app works with: found by the VIN or picked by hand — a model, or only a make (then its
 * family's parameters are probed and the VIN finds the model when it can). [reads] — what is read beyond
 * OBD; [details] and [features] — under "More".
 */
class CarCardView(
    val label: String,
    val title: String,
    val status: String,
    val pick: String,
    val reads: Pair<String, String>?,
    val details: List<Pair<String, String>>,
    val features: Pair<String, List<String>>?,
    val hasMore: Boolean,
) {
    companion object {
        val MORE = tr("Подробнее ▾", "More ▾")
        val LESS = tr("Свернуть ▴", "Less ▴")
    }
}

object CarCard {
    fun build(v: VehicleInfo, picked: CarChoice?): CarCardView {
        val car = v.car ?: picked?.model
        val brand = v.brand ?: picked?.brand
        val family = CarDb.family(car?.family ?: v.make.id.ifEmpty { null } ?: picked?.family)
        val status = when {
            car != null && car == picked?.model -> tr("выбрана вручную", "picked by hand")
            car != null -> tr("по VIN", "by VIN")
            picked != null && picked.brand == brand -> tr("выбрана марка; модель — по VIN, или выберите", "make picked; the model by the VIN, or pick it")
            brand != null -> tr("марка по VIN, модель не определилась — можно выбрать", "make by VIN, model unknown — you can pick it")
            else -> tr("определится по VIN при подключении, или выберите", "found by the VIN on connect, or pick it")
        }
        val reads = family?.let { f ->
            val makeBrand = if (car == null) brand else null
            val roles = CarText.knownOn(f, makeBrand, car).flatMap { c -> c.signals.mapNotNull { it.role } }.mapNotNull(CarText::roleName).distinct()
            tr("Что читается сверх OBD", "Read beyond standard OBD") to roles.joinToString(", ").ifEmpty { tr("ничего, только стандартный OBD", "nothing, standard OBD only") }
        }
        val details = mutableListOf<Pair<String, String>>()
        if (car != null) {
            if (car.engines.isNotEmpty()) details += tr("Двигатель", "Engine") to car.engines.joinToString("\n") { it.title }
            CarText.protocolName(car.protocol)?.let {
                details += tr("Протокол", "Protocol") to it + if (car.protocol in 1..2) tr(", многие клоны ELM327 его не поддерживают", ", many ELM327 clones don't support it") else ""
            }
            car.buses?.let { details += tr("Шины", "Buses") to it }
            car.note?.let { details += tr("Заметка", "Note") to it }
        }
        var features: Pair<String, List<String>>? = null
        if (family != null) {
            val makeBrand = if (car == null) brand else null
            val signals = CarText.knownOn(family, makeBrand, car).sumOf { it.signals.size }
            if (makeBrand != null) {
                val models = family.models.count { it.brand == makeBrand }
                if (models > 0) details += tr("Моделей в базе", "Models in the database") to "$models"
                family.commonObd(makeBrand)?.let { (loc, n, all) ->
                    CarText.obdPlace(loc)?.let { details += tr("Разъём обычно", "Socket usually") to it.trimEnd('.') + tr(" (у $n из $all моделей)", " ($n of $all models)") }
                }
            }
            details += tr("Параметры производителя", "Manufacturer parameters") to
                if (signals > 0) "$signals" else tr("нет, только стандартный OBD", "none, standard OBD only")
            if (family.features.isNotEmpty()) features = tr("Особенности ${family.title}", "About ${family.title}") to family.features
        }
        return CarCardView(
            label = tr("Машина", "Car"),
            title = car?.title ?: brand ?: tr("не выбрана", "not picked"),
            status = status,
            pick = tr("Выбрать", "Pick"),
            reads = reads,
            details = details,
            features = features,
            hasMore = car != null || family != null,
        )
    }
}

/**
 * The picker: makes, then "make only" or a model / generation; "Find by VIN" clears the hand pick. [Row.pick] —
 * the choice a tap makes ("" — by VIN), else [Row.open] — the make whose list a tap opens; [Row.compare] —
 * the id ticked for the comparison.
 */
object CarPicker {
    class Row(
        val title: String,
        val sub: String,
        val selected: Boolean,
        val pick: String? = null,
        val open: String? = null,
        val compare: String? = null,
    )

    val TITLE = tr("Марка", "Make")
    val SEARCH = tr("Поиск", "Search")
    val BACK = tr("Назад", "Back")
    val RESET = tr("Сбросить", "Reset")
    val CLOSE = tr("Закрыть", "Close")
    val NOTHING = tr("Ничего не нашлось", "Nothing found")
    fun compare(n: Int) = tr("Сравнить ($n)", "Compare ($n)")

    /** [nothing] — "Nothing found" under the rows: no make and no model matches. */
    class Rows(val rows: List<Row>, val nothing: Boolean)

    /** The list for [brand] (null — the makes) and the search text [filter]. */
    fun rows(brand: String?, filter: String, current: CarChoice?): Rows {
        val models = CarDb.models
        val brandNames = CarDb.brandNames
        val q = filter.trim()
        val out = mutableListOf<Row>()
        if (brand == null && q.isEmpty()) {
            out += Row(tr("Определять по VIN", "Find by VIN"), tr("без ручного выбора", "no hand pick"), current == null, pick = "")
            val counts = models.groupingBy { it.brand }.eachCount()
            for (b in brandNames) out += Row(b, modelsText(counts[b] ?: 0) + familyTag(b), current?.brand == b, open = b, compare = BRAND + b)
            return Rows(out, false)
        }
        if (brand != null && q.isEmpty()) {
            val fam = CarDb.familyOfBrand(brand)
            out += Row(tr("Только марка: $brand", "Make only: $brand"), tr("модель определится по VIN", "the model comes from the VIN"),
                current != null && current.model == null && current.brand == brand, pick = if (fam != null) BRAND + brand else null, compare = BRAND + brand)
        }
        // Search also finds the family: "GM" → Cadillac, Chevrolet, Opel…
        val makes = if (brand != null) emptyList() else
            brandNames.filter { b -> b.contains(q, ignoreCase = true) || GROUPS[CarDb.familyOfBrand(b)]?.contains(q, ignoreCase = true) == true }
        for (b in makes) out += Row(b, tr("марка", "make") + familyTag(b), current?.brand == b, open = b, compare = BRAND + b)
        val list = models.filter { (brand == null || it.brand == brand) && (q.isEmpty() || it.title.contains(q, ignoreCase = true)) }
            .sortedWith(compareBy({ it.brand }, { it.model }, { it.years?.first ?: 0 }))
        for (c in list) out += Row(if (brand == null) "${c.brand} ${c.shortTitle}" else c.shortTitle,
            c.engines.mapNotNull { it.code }.distinct().joinToString(", "), c == current?.model, pick = c.id, compare = c.id)
        return Rows(out, list.isEmpty() && makes.isEmpty())
    }

    /** Group names people search by; families without one (Chinese makes, Lada…) get no tag. */
    private val GROUPS = mapOf("gm" to "GM", "vag" to "VAG", "toyota" to "Toyota", "hyundai" to "Hyundai", "nissan" to "Nissan", "renault" to "Renault", "bmw" to "BMW")

    /** " · GM" after a make of a group named otherwise (Cadillac → GM, Skoda → VAG, Lexus → Toyota). */
    private fun familyTag(brand: String) = GROUPS[CarDb.familyOfBrand(brand)]?.takeIf { !it.equals(brand, ignoreCase = true) }?.let { " · $it" }.orEmpty()

    private fun modelsText(n: Int) = if (n == 0) tr("моделей в базе нет — можно выбрать марку", "no models in the database — pick the make")
    else tr("моделей: $n", "models: $n")

    private const val BRAND = CarChoice.BRAND
}

/**
 * Comparing makes and models: only what differs, parameter by parameter, the same values grouped — "Protocol:
 * CAN 11/500 — CTS, RAV4; K-line — Polo". Reads top to bottom for any number of cars.
 */
/** [empty] — over the rows when nothing differs; [same] — under them, how many are the same. */
class CompareView(val title: String, val names: String, val empty: String?, val rows: List<Pair<String, List<Pair<String, String>>>>, val same: String?, val close: String)

object CarCompare {
    fun build(ids: List<String>): CompareView {
        val items = ids.mapNotNull { CarDb.choice(it) }
        val cols = items.map { diagnostics(it) }
        val names = items.map(::title)
        val rows = cols.firstOrNull()?.keys.orEmpty().filter { k -> cols.map { it[k] }.distinct().size > 1 }
        val same = (cols.firstOrNull()?.size ?: 0) - rows.size
        return CompareView(
            title = tr("Сравнение", "Comparison"),
            names = names.joinToString(" · "),
            empty = if (rows.isEmpty()) tr("Отличий нет", "No differences") else null,
            rows = rows.map { r ->
                r to cols.indices.groupBy { cols[it][r].orEmpty() }.map { (value, who) ->
                    val shown = when (value) { "✓" -> tr("есть", "yes"); "—" -> tr("нет", "no"); else -> value }
                    shown to who.joinToString(", ") { names[it] }
                }
            },
            same = if (same > 0) tr("Совпадает ещё $same", "$same more are the same") else null,
            close = tr("Закрыть", "Close"),
        )
    }

    fun title(c: CarChoice) = c.model?.let { "${it.brand} ${it.shortTitle}" } ?: c.brand

    /**
     * Diagnostics of a make or a model, for comparing: how the app talks to it (protocol, buses, socket,
     * module steps, services and addresses) and what it reads beyond standard OBD. No years or engines —
     * this is about what the scanner can do, not a spec sheet.
     */
    private fun diagnostics(c: CarChoice): Map<String, String> {
        val family = CarDb.family(c.family)
        val car = c.model
        val models = family?.models.orEmpty().filter { it.brand == c.brand }
        val cmds: List<ExtCommand> = when {
            family == null -> emptyList()
            car != null -> family.commandsFor(car)
            else -> family.commandsOfBrand(c.brand)
        }
        val no = "—"
        val out = linkedMapOf<String, String>()
        out[tr("Семейство", "Family")] = family?.title ?: no
        out[tr("Протокол", "Protocol")] = if (car != null) CarText.protocolName(car.protocol) ?: no
        else models.mapNotNull { CarText.protocolName(it.protocol) }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(", ") { "${it.key} (${it.value})" }.ifEmpty { no }
        out[tr("Шины", "Buses")] = (car?.buses ?: models.mapNotNull { it.buses }.distinct().singleOrNull()) ?: no
        out[tr("Разъём", "Socket")] = (car?.obd ?: family?.commonObd(c.brand)?.first)?.let { CarText.obdPlace(it)?.trimEnd('.') } ?: no
        out[tr("Блоки и их ошибки", "Modules and their codes")] = when (Make.of(c.family)) {
            Make.GM -> tr("ищутся все блоки, читаются их ошибки", "all modules are found, their codes read")
            Make.VAG -> tr("ищутся блоки VW, читаются их ошибки", "VW modules are found, their codes read")
            else -> tr("двигатель, КПП и несколько частых блоков", "engine, transmission and a few common modules")
        }
        out[tr("Сервисы производителя", "Manufacturer services")] = cmds.map { "\$" + it.service }.distinct().sorted().joinToString(" ").ifEmpty { no }
        out[tr("Адреса запросов", "Request ids")] = cmds.map { "%03X".format(it.req) }.distinct().sorted().joinToString(" ").ifEmpty { no }
        val signals = cmds.sumOf { it.signals.size }
        out[tr("Параметров производителя", "Manufacturer parameters")] = if (signals == 0) no else "$signals"
        // One row per thing read beyond OBD: which of the compared ones have it.
        val roles = cmds.flatMap { x -> x.signals.mapNotNull { it.role } }.mapNotNull(CarText::roleName).toSet()
        for (r in ALL_ROLES.mapNotNull(CarText::roleName).distinct()) out[r.replaceFirstChar { it.uppercase() }] = if (r in roles) "✓" else no
        return out
    }

    private val ALL_ROLES = listOf("oil_temp", "oil_pressure", "oil_life", "oil_level", "atf_temp", "cvt_temp", "clutch_temp", "cvt_wear",
        "gear", "tc_slip", "fuel_level_l", "odometer", "battery_soc", "knock_retard", "boost", "dpf_soot", "service_km", "hv_soc")
}
