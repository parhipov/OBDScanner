package com.obdscanner.screen

import com.obdscanner.AppInfo
import com.obdscanner.TripMode
import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarDb
import com.obdscanner.gm.DtcScheme
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.Make
import com.obdscanner.obd.Mode09
import com.obdscanner.obd.Pids
import com.obdscanner.obd.Reading
import com.obdscanner.obd.TestResult
import com.obdscanner.obd.ecuName
import com.obdscanner.tr
import com.obdscanner.util.format
import com.obdscanner.util.stampText
import com.obdscanner.util.timeText
import com.obdscanner.vag.Tp20

/**
 * What «Все данные» calls a block ([com.obdscanner.CarLink.moduleNames]): names by request id, and the reply ids that
 * lead there — the standard PIDs come from 7E8, the manufacturer's from 7E0, both the engine.
 */
class ModuleNames(val byReq: Map<Int, String> = emptyMap(), val respToReq: Map<Int, Int> = emptyMap()) {
    /** The block a value from [ecu] belongs to: a reply id is its request's (7E8 → 7E0), the rest as they are; 0 — calculated. */
    fun group(ecu: Int): Int = when {
        ecu == 0 -> 0
        respToReq[ecu] != null -> respToReq.getValue(ecu)
        ecu in 0x7E8..0x7EF -> ecu - 8
        else -> ecu
    }

    /** The block's heading, without its id twice ("ЭБУ 7E5", not "ЭБУ 7E5 · 7E5"). */
    fun title(group: Int): String {
        if (group == 0) return ecuName(0)
        val name = byReq[group] ?: if (group in 0x7E0..0x7E7) ecuName(group + 8) else ecuName(group)
        val id = if (group in 0x01..0xFF) "%02X".format(group) else Tp20.label(group)
        return if (id in name) name else "$name · $id"
    }
}

/**
 * Every live value, grouped by block (the computed ones last); ☆ puts one on Main, the car icon on the car's screen
 * (Android Auto, shown once that screen has run — [carScreen]), a heading folds its group. The chosen ones come first,
 * a group each, in their order with «Выше» / «Ниже»; the car group has a line after every page of tiles.
 */
object AllScreen {
    fun build(
        r: Map<String, Reading>, v: VehicleInfo, names: ModuleNames = ModuleNames(), pins: Collection<String> = emptyList(),
        filter: String = "", toggled: Set<String> = emptySet(), carPins: Collection<String> = emptyList(), carScreen: Boolean = false,
    ): List<Block> {
        val b = Blocks()
        val q = filter.trim()
        val shown = if (q.isEmpty()) r.values.toList() else r.values.filter { it.name.contains(q, ignoreCase = true) || it.source.contains(q, ignoreCase = true) }
        b.note(tr("Опрашиваются все поддерживаемые PID: ${v.supported01.count { !Pids.isBitmask(it) }} шт. Всего значений: ${r.size}. ☆ — показать на главной.",
            "Polling all supported PIDs: ${v.supported01.count { !Pids.isBitmask(it) }}. Values in total: ${r.size}. ☆ — show on Main.") +
            if (carScreen) tr(" Значок машины — на экран машины (Android Auto).", " The car icon — onto the car's screen (Android Auto).") else "")
        // Manufacturer requests ($21/$22): their layouts come from other models' tables and may not fit this car.
        if (r.values.any { it.source.startsWith("21.") || it.source.startsWith("22.") })
            b.note(tr("Часть параметров производителя расшифрована по таблицам других моделей: на вашей машине значение может оказаться неверным. Заметили странное — отправьте сессию.",
                "Some manufacturer parameters are decoded with other models' tables: a value may be wrong on your car. If something looks off, send the session."))
        if (q.isNotEmpty()) b.note(if (shown.isEmpty()) tr("Ничего не найдено", "Nothing found") else tr("Найдено: ${shown.size}", "Found: ${shown.size}"))
        if (q.isEmpty()) {
            // The chosen values first, in their order; only the live ones (another car's picks wait for that car).
            fun group(id: String, title: String, keys: Collection<String>, car: Boolean) {
                val live = keys.mapNotNull { r[it] }
                if (live.isEmpty()) return
                if (!b.fold(id, title, true, toggled, "${live.size}")) return
                for ((i, x) in live.withIndex()) b.value(x, pin = x.key in pins, car = if (carScreen) x.key in carPins else null,
                    order = Block.Order(car, i == 0, i == live.lastIndex), divider = car && (i + 1) % CarScreen.PAGE == 0 && i != live.lastIndex)
            }
            group("all:pins", MainScreen.PINNED, pins, false)
            if (carScreen) group("all:car", CarScreen.TITLE, carPins, true)
        }
        // Standard OBD blocks first (engine, gearbox), then the rest by id, the computed values last.
        val groups = shown.groupBy { names.group(it.ecu) }.entries.sortedBy { (g, _) ->
            when {
                g == 0 -> Int.MAX_VALUE
                g in 0x7E0..0x7E7 || g in 0x01..0xFF -> g - 0x10000
                else -> g
            }
        }
        // A search shows every match, folded groups too.
        val folds = if (q.isEmpty()) toggled else emptySet()
        for ((g, list) in groups) {
            if (!b.fold("all:%X".format(g), names.title(g), true, folds, "${list.size}")) continue
            for (x in list.sortedBy { it.source }) b.value(x, pin = x.key in pins, car = if (carScreen) x.key in carPins else null)
        }
        return b.build()
    }
}

/**
 * «Ошибки»: by module — every module's own memory with the Check Engine codes (Mode 03/07/0A) on the same rows, the
 * freeze frame under its code, the modules without codes and those that didn't answer in a line each. [busy] — the
 * operation running, [canStop] — it can be stopped; [mil] — Check Engine as the engine says it (01 01).
 */
object CodesScreen {
    val CHECK = tr("Проверить ошибки", "Check codes")
    val CLEAR = tr("Стереть ошибки…", "Clear codes…")
    /** The first item of «Стереть ошибки?»: the standard OBD clear (Mode 04). */
    val CHECK_ENGINE = tr("Двигатель и АКПП (Check Engine)", "Engine and transmission (Check Engine)")
    /** [ClearModulesView.Item.key] of [CHECK_ENGINE]. */
    const val OBD = "obd"

    private class Row(val label: String, val state: String, val text: String, val level: Level, val ref: DtcRef, val code: String) {
        val tags = mutableListOf<String>()
    }

    private class Group(val key: String, val title: String, val result: GmDtcResult?) {
        val rows = mutableListOf<Row>()
    }

    fun build(v: VehicleInfo, busy: String?, canStop: Boolean = false, mil: Boolean? = null, toggled: Set<String> = emptySet()): List<Block> {
        val b = Blocks()
        b.buttons(listOfNotNull(Button(Action.READ_DTC, CHECK, primary = true), Button(Action.CLEAR_DTC, CLEAR),
            Button(Action.STOP, tr("Стоп", "Stop"), whileBusy = true).takeIf { canStop }))
        if (busy != null) {
            // What is asked right now: the connection's step, a module's codes («Ошибки BCM (3 из 10)…»).
            val now = v.step.ifEmpty { v.gmDtcStatus.takeIf { it.endsWith("…") } }
            b.banner(tr("Выполняется: $busy", "In progress: $busy") + (now?.let { "\n$it" } ?: ""), Level.GOOD)
        } else if (v.clearResult.isNotEmpty()) b.banner(v.clearResult.joinToString("\n"), Level.GOOD)

        val titles = titles(v)
        val groups = groups(v, titles)
        val checked = v.gmDtcTime > 0
        val rows = groups.flatMap { it.rows }
        val active = rows.count { it.level == Level.BAD }
        val time = maxOf(v.dtcTime, v.gmDtcTime)
        if (time > 0) b.note(listOfNotNull(
            tr("Проверено в ${timeText(time)}", "Checked at ${timeText(time)}"),
            if (checked) tr("блоков ${v.gmDtcs.size}", "${v.gmDtcs.size} modules") else null,
            if (rows.isEmpty()) tr("ошибок нет", "no codes") else tr("ошибок ${rows.size}", "${rows.size} codes") +
                if (active > 0) tr(", важных $active", ", $active important") else "",
        ).joinToString(" · "))
        when (mil) {
            true -> b.banner(tr("Check Engine горит", "Check Engine is on"), Level.BAD)
            false -> b.note(tr("Check Engine не горит", "Check Engine is off"))
            null -> {}
        }
        // Against the last connection's check: what is new, what went.
        val prev = v.dtcPrev?.takeIf { checked }
        if (prev != null) {
            val answered = v.gmDtcs.filter { it.complete || it.codes.isNotEmpty() }.map { it.module.key }.toSet()
            val now = codeKeys(v.gmDtcs)
            val fresh = now.keys - prev.codes.keys
            val gone = prev.codes.filterKeys { it !in now && it.substringBeforeLast(':') in answered }.values
            val date = stampText(prev.time).take(10).split('-').let { (y, m, d) -> "$d.$m.$y" }
            if (fresh.isNotEmpty()) b.banner(tr("Новые с прошлой проверки ($date): ${fresh.size}", "New since the last check ($date): ${fresh.size}"), Level.WARN)
            if (gone.isNotEmpty()) b.note(tr("С прошлой проверки ($date) пропали: ${gone.joinToString()}", "Gone since the last check ($date): ${gone.joinToString()}"))
        }

        var freezeShown = v.freeze.isEmpty()
        for (g in groups.filter { it.rows.isNotEmpty() }) {
            if (!b.fold("dtc:${g.key}", g.title, true, toggled, "${g.rows.size}")) continue
            // A module whose list may be cut (frames lost): said under its title.
            g.result?.takeIf { !it.complete }?.let { b.note(it.result) }
            for (r in g.rows) {
                b.row(r.label, r.state, "", (listOf(r.text) + r.tags).filter { it.isNotEmpty() }.joinToString(" · "), r.level, r.ref)
                if (!freezeShown && r.code == v.freezeDtc) {
                    freezeShown = true
                    freeze(b, v, toggled)
                }
            }
        }
        if (!freezeShown) freeze(b, v, toggled)

        val clean = v.gmDtcs.filter { it.codes.isEmpty() && it.complete }
        val silent = v.gmDtcs.filter { it.codes.isEmpty() && !it.complete }
        when {
            checked && rows.isEmpty() -> b.banner(tr("Ошибок нет ни в одном блоке", "No codes in any module"), Level.GOOD)
            !checked && v.dtcTime > 0 && rows.isEmpty() ->
                b.note(if (v.dtcNoAnswer) tr("Коды Check Engine: блок двигателя не ответил", "Check Engine codes: the engine module didn't answer")
                    else tr("Кодов Check Engine нет", "No Check Engine codes"))
        }
        if (clean.isNotEmpty()) b.note(tr("Без ошибок: ", "No codes: ") + clean.joinToString { titles.getValue(it.module.key) })
        if (silent.isNotEmpty()) b.note(tr("Не ответили: ", "Didn't answer: ") + silent.joinToString { titles.getValue(it.module.key) })
        for (p in v.busesOut) b.note(tr("Часть блоков этой машины — на отдельном проводе ($p): этому адаптеру он недоступен, нужен адаптер с его поддержкой.",
            "Some of this car's modules are on a separate wire ($p): this adapter can't reach it, it takes one that supports it."))
        when {
            !v.moduleAccess -> b.note(tr("На этом подключении читаются только коды Check Engine: память отдельных блоков недоступна.",
                "On this connection only the Check Engine codes are read: the modules' own memory is out of reach."))
            !checked && busy == null && v.gmDtcStatus.isNotEmpty() -> b.note(v.gmDtcStatus)
            !checked && busy == null && v.dtcTime > 0 -> b.note(NOT_CHECKED)
        }

        if (b.fold("dtc:more", tr("Подробнее", "More"), false, toggled)) {
            b.note(tr("«$CHECK» читает коды Check Engine (стандарт OBD) и полную память каждого блока: двигатель, АКПП, ABS, кузов и др., " +
                "включая коды без Check и историю. Первый раз блоки ищутся около минуты, дальше несколько секунд на блок. Зажигание включено, машина стоит.",
                "«$CHECK» reads the Check Engine codes (standard OBD) and every module's full memory: engine, transmission, ABS, body etc., " +
                    "including codes without Check Engine and the history. The first time the modules are searched for about a minute, then a few seconds per module. Ignition on, car parked."))
            b.note(tr("«$CLEAR» стирает отмеченное в списке: двигатель и АКПП — стандартным сбросом OBD (с ним уходят стоп-кадр и готовность мониторов), " +
                "остальные блоки — каждый своим запросом. Потом всё проверяется заново.",
                "«$CLEAR» clears what is ticked: the engine and transmission with the standard OBD clear (the freeze frame and the monitors' readiness go with it), " +
                    "the other modules each with its own request. Then everything is checked again."))
            for (r in v.gmDtcs) b.row(moduleTitle(r.module, pins(v, r.module), withId = true), "", sub = r.result, key = "res:${r.module.key}")
        }
        if (rows.isNotEmpty()) b.buttons(listOf(Button(Action.COPY_DTC, tr("Скопировать список", "Copy the list"), whileBusy = true)))
        return b.build()
    }

    /** The freeze frame: a folded block of its values, under the code it was taken for. */
    private fun freeze(b: Blocks, v: VehicleInfo, toggled: Set<String>) {
        if (v.freeze.isEmpty()) return
        if (b.fold("dtc:freeze", tr("Стоп-кадр", "Freeze frame") + (v.freezeDtc?.let { " — $it" } ?: ""), false, toggled, "${v.freeze.size}"))
            for (f in v.freeze) b.value(f)
    }

    /** «Память блоков ещё не проверена»: the line on «Ошибки» until the modules are read. */
    val NOT_CHECKED = tr("Память блоков (ABS, кузов, подушки, климат и др.) ещё не проверена: «$CHECK». Первый раз около 1–2 минут, дальше несколько секунд на блок.",
        "The modules' memory (ABS, body, airbags, climate etc.) isn't checked yet: «$CHECK». The first time about 1–2 minutes, then a few seconds per module.")

    /**
     * Each module's heading on this car by its key: the name alone; the id (and the wire) only where two modules
     * would read the same.
     */
    private fun titles(v: VehicleInfo): Map<String, String> {
        val plain = v.gmDtcs.associate { it.module.key to moduleTitle(it.module) }
        val twice = plain.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return v.gmDtcs.associate { r ->
            r.module.key to if (plain.getValue(r.module.key) in twice) moduleTitle(r.module, pins(v, r.module), withId = true) else plain.getValue(r.module.key)
        }
    }

    /** The modules with their codes, the Check Engine codes on the module that sent them (one row when both have it). */
    private fun groups(v: VehicleInfo, titles: Map<String, String> = titles(v)): List<Group> {
        val prev = v.dtcPrev?.codes?.keys
        val out = mutableListOf<Group>()
        for (m in v.gmDtcs) {
            val where = titles.getValue(m.module.key)
            val g = Group(m.module.key, where, m)
            for (c in m.codes) g.rows += Row(c.full, if (c.current) tr("активна", "active") else tr("история", "history"), "${c.description} · ${c.flags}",
                if (c.current || c.mil) Level.BAD else Level.WARN,
                DtcRef(c.code, where, c.failureType.takeIf { it >= 0 }, c.scheme == DtcScheme.GM, c.flags, label = c.full), c.code).also { r ->
                if (prev != null && "${m.module.key}:${c.code}" !in prev) r.tags += tr("новая", "new")
            }
            out += g
        }
        for (d in v.dtcs) {
            // The module that sent it: its reply id (7E8 → the engine), on the main bus.
            val g = out.firstOrNull { it.result?.module?.let { m -> m.bus == null && (m.resp == d.ecu || m.req == d.ecu) } == true }
                ?: out.firstOrNull { it.key == "obd:%03X".format(d.ecu) }
                ?: Group("obd:%03X".format(d.ecu), ecuName(d.ecu), null).also { out += it }
            val tag = when (d.kind) {
                DtcKind.STORED -> tr("код Check Engine", "Check Engine code")
                DtcKind.PENDING -> tr("ожидающая (Check Engine)", "pending (Check Engine)")
                DtcKind.PERMANENT -> tr("постоянная: уйдёт сама после проверки", "permanent: goes by itself after the check")
            }
            val same = g.rows.firstOrNull { it.code == d.code }
            if (same != null) {
                if (tag !in same.tags) same.tags += tag
                continue
            }
            g.rows += Row(d.code, kindState(d.kind), d.description,
                if (d.kind == DtcKind.PENDING) Level.WARN else Level.BAD, DtcRef(d.code, ecuName(d.ecu), kind = d.kind), d.code).also { it.tags += tag }
        }
        // Modules with codes first, in the order they were read.
        return out.sortedBy { if (it.rows.isEmpty()) 1 else 0 }
    }

    private fun kindState(k: DtcKind) = when (k) {
        DtcKind.STORED -> tr("сохранена", "stored")
        DtcKind.PENDING -> tr("ожидает", "pending")
        DtcKind.PERMANENT -> tr("постоянная", "permanent")
    }

    /** The pins of the bus a module is on («контакт 1»), from the car's family; null — the main bus. */
    private fun pins(v: VehicleInfo, m: GmModule): String? = m.bus?.let { id ->
        CarDb.family(v.car?.family ?: v.make.id)?.modules?.buses?.firstOrNull { it.id == id }?.pins ?: id
    }

    /**
     * A module's heading: its name ("BCM (кузов)"), the id only for one without a name ("Блок 24F", "ЭБУ 7E5"); [withId] —
     * with its id and the bus's [pins] when not the main one ("BCM (кузов) · 241", for «Подробнее» and namesakes).
     */
    fun moduleTitle(m: GmModule, pins: String? = null, withId: Boolean = false): String = with(m) {
        when {
            name == id -> tr("Блок $id", "Module $id")
            id in name || !withId -> name
            else -> "$name · $id"
        }
    } + (pins?.takeIf { withId }?.let { " · $it" } ?: "")

    /** The engine and gearbox: the blocks that answer the standard OBD requests (Mode 04 is theirs). */
    fun isObdBlock(v: VehicleInfo, m: GmModule) = m.bus == null && (m.resp in v.ecus.keys || m.req in v.ecus.keys)

    /** The codes [CHECK_ENGINE] stands for: the Check Engine ones (not the permanent) and the engine's and gearbox's own memory. */
    fun checkEngineCodes(v: VehicleInfo): Set<String> =
        v.dtcs.filter { it.kind != DtcKind.PERMANENT }.map { it.code }.toSet() + v.gmDtcs.filter { isObdBlock(v, it.module) }.flatMap { r -> r.codes.map { it.code } }

    /** Each code of the modules' memory: [GmModule.key]:code → how a line names it ("B1517 (BCM (кузов) · 241)"). */
    fun codeKeys(results: List<GmDtcResult>): Map<String, String> = results.flatMap { r ->
        r.codes.map { c -> "${r.module.key}:${c.code}" to "${c.code} (${moduleTitle(r.module)})" }
    }.toMap()

    /** The list as text for «Скопировать список»: the car, when, each module's codes. No VIN: it is pasted to strangers. */
    fun text(v: VehicleInfo, mil: Boolean? = null): String = buildString {
        val car = v.car?.title ?: listOfNotNull(v.brand, v.make.title.takeIf { v.make != Make.OTHER }).distinct().joinToString(" ")
        val time = maxOf(v.dtcTime, v.gmDtcTime)
        appendLine("OBD Scanner ${AppInfo.versionName}" + (if (car.isNotEmpty()) " · $car" else "") +
            (if (time > 0) " · " + stampText(time).replace('_', ' ').let { s -> s.take(10) + " " + s.drop(11).take(5).replace('-', ':') } else ""))
        when (mil) {
            true -> appendLine(tr("Check Engine горит", "Check Engine is on"))
            false -> appendLine(tr("Check Engine не горит", "Check Engine is off"))
            null -> {}
        }
        val titles = titles(v)
        for (g in groups(v, titles).filter { it.rows.isNotEmpty() }) {
            appendLine()
            appendLine(g.title + ":")
            for (r in g.rows) appendLine("  ${r.label} — ${r.state}" + (listOf(r.text) + r.tags).filter { it.isNotEmpty() }.joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" })
        }
        val clean = v.gmDtcs.filter { it.codes.isEmpty() && it.complete }
        if (clean.isNotEmpty()) {
            appendLine()
            appendLine(tr("Без ошибок: ", "No codes: ") + clean.joinToString { titles.getValue(it.module.key) })
        }
    }.trimEnd()

    /** «Стереть ошибки?»: the engine and gearbox ([OBD]), then the other modules with codes, each ticked or not. */
    class ClearModulesView(val title: String, val text: String, val items: List<Item>, val yes: String, val no: String) {
        class Item(val key: String, val label: String, val count: Int, val checked: Boolean)
    }

    private val AIRBAG = Regex("подуш|airbag|SRS|SDM", RegexOption.IGNORE_CASE)

    /**
     * Ticked: the engine and gearbox (the standard clear) and every other module with codes but the airbags. The engine
     * and gearbox aren't listed again: Mode 04 is theirs.
     */
    fun clearModules(v: VehicleInfo): ClearModulesView {
        val titles = titles(v)
        val modules = if (!v.moduleAccess) emptyList() else v.gmDtcs.filter { it.codes.isNotEmpty() && !isObdBlock(v, it.module) }.map { r ->
            val m = r.module
            val role = if (m.bus == null) v.car?.knownBlocks?.get(m.req)?.role else null
            val airbag = role == "airbag" || AIRBAG.containsMatchIn(m.name)
            ClearModulesView.Item(m.key, titles.getValue(m.key), r.codes.size, !airbag)
        }
        val items = listOf(ClearModulesView.Item(OBD, CHECK_ENGINE, checkEngineCodes(v).size, true)) + modules
        return ClearModulesView(
            tr("Стереть ошибки?", "Clear the codes?"),
            tr("Отмеченное стирается, потом всё проверяется заново: активная неисправность вернётся сразу. " +
                "У двигателя и АКПП вместе с кодами сбрасываются стоп-кадр и готовность мониторов. Подушки не отмечены. " +
                "Зажигание включено, двигатель заглушен, машина стоит. Все коды уже записаны в сессию.",
                "What is ticked is cleared, then everything is checked again: a fault present now comes back at once. " +
                    "The engine and transmission lose the freeze frame and the monitors' readiness with their codes. Airbags aren't ticked. " +
                    "Ignition on, engine off, car parked. All codes are already saved to the session.") +
                if (v.moduleAccess && v.gmDtcTime == 0L) tr(" Память остальных блоков ещё не прочитана: они появятся здесь после «$CHECK».",
                    " The other modules' memory isn't read yet: they show here after «$CHECK».") else "",
            items, tr("Стереть", "Clear"), tr("Отмена", "Cancel"))
    }
}

/**
 * «Машина»: the car, its readiness and on-board tests in a line each (the details folded), its modules with their part
 * numbers; the adapter, the ECUs' Mode 09 data and what the app remembers — folded, for specialists. [tests] — the
 * test modes ([TripMode.test]) after the car, cards as on the first screen ([Block.Modes]); [mode] — the one on.
 */
object InfoScreen {
    fun build(v: VehicleInfo, modules: List<GmModule> = emptyList(), toggled: Set<String> = emptySet(),
              mode: TripMode? = null, tests: Boolean = false): List<Block> {
        val b = Blocks()
        b.title(tr("Автомобиль", "Vehicle"))
        b.row("VIN", v.vin ?: "—")
        if (v.adapter.isNotEmpty()) b.row(tr("Марка (по VIN)", "Make (from VIN)"), listOfNotNull(v.brand, v.make.title.takeIf { v.make != Make.OTHER || v.brand == null }).distinct().joinToString(" · "))
        v.car?.let { b.row(tr("Модель", "Model"), it.title, sub = it.engines.joinToString("; ") { e -> e.title }.ifEmpty { null }) }
        if (v.adapter.isNotEmpty()) b.buttons(listOf(Button(Action.RESCAN, tr("Опросить заново", "Rescan"))))

        if (tests && TripMode.tests.isNotEmpty()) {
            b.title(MainScreen.TEST_MODES)
            b.modes(TripMode.tests, mode)
        }

        // Readiness: what the engine module has checked since the last clear (the same monitor of several ECUs once).
        val monitors = v.ecus.values.sortedBy { it.header }.flatMap { e -> e.readiness.filter { it.available }.map { e.header to it } }
        if (monitors.isNotEmpty()) {
            b.title(tr("Готовность мониторов", "Readiness monitors"))
            val names = monitors.map { it.second.name }.distinct()
            val notReady = monitors.filter { !it.second.complete }.map { it.second.name }.distinct()
            if (notReady.isEmpty()) b.row(tr("Все проверки", "All checks"), tr("готовы", "complete"), level = Level.GOOD, key = "mon:all")
            else b.row(tr("Не готовы", "Not complete"), tr("${notReady.size} из ${names.size}", "${notReady.size} of ${names.size}"),
                sub = notReady.joinToString(), level = Level.WARN, key = "mon:all")
            b.note(tr("Блок двигателя сам проверяет свои системы на ходу. «Не готов» — проверка ещё не прошла после сброса ошибок или снятия клеммы: машине надо поездить в разных режимах.",
                "The engine module checks its systems by itself while driving. «Not complete» — the check hasn't run since the codes were cleared or the battery disconnected: the car needs some driving in different modes."))
            if (b.fold("info:monitors", tr("Мониторы по блокам", "Monitors by module"), false, toggled)) {
                for ((h, mon) in monitors) b.row(mon.name, if (mon.complete) tr("готов", "complete") else tr("не готов", "not complete"),
                    sub = "${ecuName(h)} · %03X".format(h), level = if (mon.complete) Level.GOOD else Level.WARN, key = "mon:$h:${mon.name}")
            }
        }

        if (v.mode06.isNotEmpty() || v.adapter.isNotEmpty()) {
            b.title(tr("Бортовые тесты", "On-board tests"))
            val run = v.mode06.filter { !it.notRun }
            val failed = run.filter { !it.passed }
            if (v.mode06.isEmpty()) b.note(tr("Блоки не отдали результаты тестов", "The modules gave no test results"))
            else b.row(tr("Тестов", "Tests"), "${v.mode06.size}", sub = when {
                failed.isNotEmpty() -> tr("вне нормы: ${failed.size}", "out of limits: ${failed.size}")
                run.isEmpty() -> tr("ещё не выполнялись", "not run yet")
                else -> tr("все в норме", "all within limits") + if (run.size < v.mode06.size) tr(", не выполнялись: ${v.mode06.size - run.size}", ", not run: ${v.mode06.size - run.size}") else ""
            }, level = if (failed.isNotEmpty()) Level.BAD else if (run.isNotEmpty()) Level.GOOD else Level.MUTED, key = "m06:all")
            for (t in failed) test(b, t)
            if (v.mode06.isNotEmpty() && b.fold("info:mode06", tr("Все тесты", "All tests"), false, toggled, "${v.mode06.size}"))
                for (t in v.mode06) test(b, t, "all:")
            if (v.adapter.isNotEmpty()) b.buttons(listOf(Button(Action.MODE06, tr("Обновить тесты", "Refresh the tests"))))
        }

        val buses = CarDb.family(v.car?.family ?: v.make.id)?.modules?.buses.orEmpty()
        fun pins(m: GmModule) = m.bus?.let { id -> buses.firstOrNull { it.id == id }?.pins ?: id }
        if (modules.isNotEmpty()) {
            b.title(tr("Блоки (${modules.size})", "Modules (${modules.size})"))
            val plain = modules.map { CodesScreen.moduleTitle(it) }
            for ((m, title) in modules.zip(plain)) b.row(if (plain.count { it == title } > 1) CodesScreen.moduleTitle(m, pins(m), withId = true) else title,
                m.partNumber?.toString() ?: "", sub = m.partNumber?.let { tr("номер детали", "part number") }, key = "mod:${m.key}")
        }

        if (b.fold("info:expert", tr("Для специалистов", "For specialists"), false, toggled)) {
            b.title(tr("Адаптер", "Adapter"))
            b.row(tr("Версия", "Version"), v.adapter.ifEmpty { "—" })
            b.row(tr("Описание", "Description"), v.adapterDesc.ifEmpty { "—" })
            b.row(tr("Протокол", "Protocol"), v.protocol.ifEmpty { "—" })
            b.row(tr("Мульти-PID запросы", "Multi-PID requests"), if (v.pidBatch > 1) tr("по ${v.pidBatch} PID", "${v.pidBatch} PIDs each") else tr("нет", "no"))
            if (v.adapter.isNotEmpty()) b.row(tr("Параметры производителя", "Manufacturer parameters"), tr("ответили ${v.extActive.size}", "${v.extActive.size} answered"))
            if (modules.isNotEmpty()) {
                b.title(tr("Адреса блоков", "Module addresses"))
                for (m in modules) b.row(CodesScreen.moduleTitle(m), if (Tp20.isTp(m.req)) m.id else "%03X → %03X".format(m.req, m.resp),
                    sub = listOfNotNull(pins(m), m.diag?.let { tr("адрес %02X", "address %02X").format(it) }).joinToString(" · ").ifEmpty { null }, key = "addr:${m.key}")
            }
            for (e in v.ecus.values.sortedBy { it.header }) {
                b.title("${ecuName(e.header)} · %03X".format(e.header))
                b.row("PID Mode 01", "${e.pids01.count { !Pids.isBitmask(it) }}", key = "pids:${e.header}")
                for ((t, s) in e.info09) b.row(Mode09.name(t), if (t == 0x08 || t == 0x0B) "" else s, sub = if (t == 0x08 || t == 0x0B) s else null, key = "09:${e.header}:$t")
            }
            val mem = v.memory
            if (!mem.isEmpty) {
                b.title(tr("Что приложение помнит", "What the app remembers"))
                mem.protocol?.let { b.row(tr("Протокол адаптера", "Adapter protocol"), it, sub = tr("пробуется первым", "tried first")) }
                mem.extSaved?.let { b.row(tr("Параметры производителя", "Manufacturer parameters"), tr("$it сохранено", "$it saved"),
                    sub = tr("спрашиваются только они", "only these are asked")) }
                if (mem.modules > 0) b.row(tr("Найденные блоки", "Modules found"), "${mem.modules}")
                for (c in mem.skipped.sorted()) b.row(skippedName(c), tr("пропускается", "skipped"),
                    sub = tr("адаптер обрывал на нём связь", "the adapter dropped the link on it"), level = Level.WARN, key = "skip:$c")
                b.buttons(listOf(Button(Action.FORGET, FORGET)), FORGET_NOTE)
            }
        }
        return b.build()
    }

    /** One on-board test: its value, limits or «не выполнялся». */
    private fun test(b: Blocks, t: TestResult, prefix: String = "") =
        b.row("${t.midName}", "${Reading.fmt(t.value, 3)}", t.unit,
            if (t.notRun) tr("${t.tidName} · не выполнялся", "${t.tidName} · not run") else tr("${t.tidName} · норма ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}", "${t.tidName} · limits ${Reading.fmt(t.min, 3)}…${Reading.fmt(t.max, 3)}"),
            if (t.notRun) Level.MUTED else if (t.passed) Level.GOOD else Level.BAD, divider = true, key = prefix + "%03X.%02X.%02X".format(t.ecu, t.mid, t.tid))

    private fun skippedName(cmd: String): String = when {
        cmd.startsWith("09") -> Mode09.name(cmd.drop(2).toInt(16)) + " ($cmd)"
        else -> tr("Бортовой тест", "On-board test") + " ($cmd)"
    }

    val FORGET = tr("Сбросить", "Reset")
    val FORGET_NOTE = tr("следующее подключение найдёт всё заново", "the next connection finds it all again")
    /** The question before [com.obdscanner.CarLink.forgetCar]. */
    val FORGET_TITLE = tr("Забыть эту машину?", "Forget this car?")
    val FORGET_TEXT = tr("Приложение забудет протокол адаптера, сохранённые параметры производителя, найденные блоки и пропускаемые запросы. " +
        "Следующее подключение спросит всё заново — оно будет дольше. Параметры, сохранённые на «Скан блоков», останутся.",
        "The app forgets the adapter's protocol, the saved manufacturer parameters, the modules found and the skipped requests. " +
        "The next connection asks everything again and takes longer. The parameters saved on «Module scan» stay.")
    val FORGET_YES = tr("Сбросить", "Reset")
    val FORGET_NO = tr("Отмена", "Cancel")
}

/** «Справка»'s end: the app's version and license, the third-party licenses, the disclaimer. */
object AboutScreen {
    fun build(): List<Block> {
        val b = Blocks()
        b.title(tr("О приложении", "About"))
        b.note("OBD Scanner ${AppInfo.versionName} · MIT · github.com/parhipov/OBDScanner")
        b.buttons(listOf(Button(Action.LICENSES, Licenses.TITLE, whileBusy = true)))
        // A row of its own: next to Licenses the long label doesn't fit a narrow phone.
        b.buttons(listOf(Button(Action.TERMS, Terms.TITLE, whileBusy = true)))
        return b.build()
    }
}
