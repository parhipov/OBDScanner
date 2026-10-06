package com.obdscanner.car

import com.obdscanner.util.format
import com.obdscanner.obd.PollRate
import com.obdscanner.tr
import com.obdscanner.util.JSONArray
import com.obdscanner.util.JSONObject
import com.obdscanner.util.currentYear
import com.obdscanner.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * The car database: core/data/cars/<family>.json (curated) and core/data/cars/obdb/<family>.json (assets/cars in the APK)
 * (generated from OBDb by tools/cars/obdb_import.py). Format: tools/cars/SCHEMA.md.
 *
 * A car is a set of blocks (engine, gearbox, ABS…), and how a block talks is its [Dialect]: whose
 * diagnostic spec its ECU follows, not whose badge is on the car (a Volvo engine speaks Volvo in any car).
 * Every family has its default dialect; a model can name the dialect of each block, and a dialect can be
 * recognised by the block's own answers ([MatchRule]) when the car is unknown.
 */
object CarDb {
    @Volatile var families: Map<String, CarFamily> = emptyMap()
        private set

    /** Every dialect by id: the families' defaults (id = family id) and the named ones. */
    @Volatile var dialects: Map<String, Dialect> = emptyMap()
        private set

    /** Entries the loader refused, "file: what" — the unit test fails on any. */
    @Volatile var problems: List<String> = emptyList()
        private set

    val models: List<CarModel> get() = families.values.flatMap { it.models }
    val brands: List<Brand> get() = families.values.flatMap { it.brands }

    private val _loaded = MutableStateFlow(false)
    /** True once the files are parsed ([init] runs in the background: ~3 MB of JSON). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Reads every file ([texts]: assets/cars, then assets/cars/obdb) in the background — [background] runs
     * it, a thread on the phone; later calls do nothing. Not on the main thread: on the phone it took longer
     * than Android allows for app startup (ANR, 1.04).
     */
    fun init(background: (() -> Unit) -> Unit, texts: () -> List<String>, log: (String, Throwable?) -> Unit) {
        if (families.isNotEmpty() || started) return
        started = true
        background {
            val t0 = nowMs()
            val all = texts()
            runCatching { load(all) }.onFailure { log("car database", it) }
            log("car database: ${models.size} models, ${families.values.sumOf { it.commands.size }} requests, ${nowMs() - t0} ms", null)
            _loaded.value = true
        }
    }

    @Volatile private var started = false

    /** Parses and merges the files: curated ones (with `brands`) first, so their parameters win. */
    fun load(texts: List<String>) {
        val parsed = texts.map { JSONObject(it) }.sortedBy { if (it.has("brands")) 0 else 1 }
        val out = linkedMapOf<String, CarFamily>()
        val bad = mutableListOf<String>()
        val specs = linkedMapOf<String, DialectSpec>()
        fun <T> tryParse(what: String, f: () -> T): T? = runCatching(f).onFailure { bad += "$what: ${it.message}" }.getOrNull()
        for (j in parsed) {
            val id = j.getString("family")
            val file = if (j.has("brands")) "$id.json" else "obdb/$id.json"
            val f = out.getOrPut(id) { CarFamily(id, j.optString("title", id)) }
            if (j.has("title") && f.title == id) f.title = j.getString("title")
            j.optJSONArray("brands")?.objects()?.forEach { b -> f.brands += Brand(b.getString("name"), b.optJSONArray("wmi").strings().map { it.uppercase() }, id) }
            j.optJSONArray("models")?.objects()?.forEach { m -> tryParse("$file ${m.optString("brand")} ${m.optString("model")} ${m.optString("gen")}") { parseModel(m, id) }?.let { f.models += it } }
            j.optJSONArray("features")?.objects()?.forEach { f.features += text(it) }
            j.optJSONObject("dialect")?.let { d -> tryParse("$file dialect") { parseDialect(d, id, id) }?.let { specs[id] = it } }
            j.optJSONObject("modules")?.let { m -> tryParse("$file modules") { parseModules(m) }?.let { f.modules = it } }
            j.optJSONArray("dialects")?.objects()?.forEach { d ->
                tryParse("$file dialect ${d.optString("id")}") {
                    val spec = parseDialect(d, d.getString("id"), id)
                    require(spec.id !in specs && spec.id !in out.keys) { "dialect id ${spec.id} is taken" }
                    spec
                }?.let { specs[it.id] = it }
            }
            val source = if (j.has("brands")) "db" else "obdb"
            j.optJSONArray("commands")?.objects()?.forEach { c -> tryParse("$file ${c.optString("hdr")} ${c.optString("svc")} ${c.optString("did")}") { parseCommand(c, source) }?.let { f.addCommand(it) } }
        }
        // Every family has a default dialect (the family's own requests); the named ones inherit from theirs.
        for (id in out.keys) specs.getOrPut(id) { DialectSpec(id, id) }
        val resolved = linkedMapOf<String, Dialect>()
        fun resolve(id: String, seen: Set<String> = emptySet()): Dialect? {
            resolved[id]?.let { return it }
            val spec = specs[id] ?: return null
            require(id !in seen) { "dialect $id extends itself" }
            val parentId = spec.extends ?: spec.family.takeIf { it != id }
            val parent = parentId?.let { resolve(it, seen + id) ?: throw IllegalArgumentException("dialect $id: no dialect $it") } ?: Dialect.GENERIC
            val own = if (id == spec.family) out[id]?.commands.orEmpty() else spec.commands
            return Dialect(
                id = id,
                family = spec.family,
                title = spec.title ?: parent.title,
                live = spec.live ?: parent.live,
                dtc = spec.dtc ?: parent.dtc,
                dtcFormat = spec.dtcFormat ?: parent.dtcFormat,
                ident = spec.ident ?: parent.ident,
                match = spec.match,
                commands = own,
            ).also { resolved[id] = it }
        }
        for (id in specs.keys) tryParse("dialect $id") { resolve(id) }
        // A block may only name a dialect that exists.
        for (f in out.values) f.models.removeAll { m ->
            val missing = m.allBlocks.map { it.dialect }.filter { it !in resolved }
            if (missing.isNotEmpty()) bad += "${f.id}.json ${m.title}: no dialect ${missing.joinToString()}"
            missing.isNotEmpty()
        }
        families = out
        dialects = resolved
        problems = bad
        _loaded.value = true
    }

    fun family(id: String?): CarFamily? = id?.let { families[it] }

    fun dialect(id: String?): Dialect? = id?.let { dialects[it] }

    /** Models that can be recognised by an ECU's answer (no VIN, or a VIN that doesn't say the model). */
    val matchable: List<CarModel> get() = models.filter { it.match.isNotEmpty() }

    fun model(id: String?): CarModel? = id?.let { k -> models.firstOrNull { it.id == k } }

    /** Every make the database knows (with WMIs or with models), sorted. */
    val brandNames: List<String> get() = (brands.map { it.name } + models.map { it.brand }).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun familyOfBrand(name: String): String? =
        brands.firstOrNull { it.name == name }?.family ?: models.firstOrNull { it.brand == name }?.family

    /** A saved pick ([CarChoice.id]): "brand:Toyota" or a model id. */
    fun choice(id: String?): CarChoice? {
        id ?: return null
        if (id.startsWith(CarChoice.BRAND)) {
            val name = id.removePrefix(CarChoice.BRAND)
            return familyOfBrand(name)?.let { CarChoice(name, it) }
        }
        return model(id)?.let { CarChoice(it.brand, it.family, it) }
    }

    /** Brand by the VIN's manufacturer code: the longest matching WMI prefix wins. */
    fun brandOf(vin: String?): Brand? {
        val v = vin?.trim()?.uppercase()?.takeIf { it.length >= 3 } ?: return null
        return brands.flatMap { b -> b.wmi.filter { v.startsWith(it) }.map { it.length to b } }.maxByOrNull { it.first }?.second
    }

    /** The model by the VIN: brand, a model whose `vin` pattern matches, and its years around the VIN's model year. */
    fun detect(vin: String?): CarModel? {
        val brand = brandOf(vin) ?: return null
        val v = vin!!.trim().uppercase()
        val year = modelYear(v)
        val hits = families[brand.family]?.models.orEmpty().filter { m -> m.brand == brand.name && m.vin.any { it.containsMatchIn(v) } }
        return hits.firstOrNull { year != null && m(it, year) } ?: hits.singleOrNull()
    }

    private fun m(model: CarModel, year: Int) = model.years?.let { year in it } ?: true

    /**
     * Model year from the 10th VIN character (ISO 3779 / NHTSA): letters repeat every 30 years, the
     * later cycle is taken unless it is in the future. Null when the character isn't a year code.
     */
    fun modelYear(vin: String): Int? {
        if (vin.length < 10) return null
        val c = vin[9].uppercaseChar()
        val digits = "123456789"
        val letters = "ABCDEFGHJKLMNPRSTVWXY"
        val now = currentYear()
        return when {
            c in digits -> 2001 + digits.indexOf(c)
            c in letters -> (2010 + letters.indexOf(c)).let { if (it > now + 1) it - 30 else it }
            else -> null
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun parseModel(m: JSONObject, family: String): CarModel {
        val years = m.optJSONArray("years")?.let { if (it.length() == 2) it.getInt(0)..it.getInt(1) else null }
        val engines = m.optJSONArray("engines")?.objects().orEmpty().map { e ->
            Engine(
                e.optString("code").ifEmpty { null },
                e.optDouble("liters").takeIf { !it.isNaN() },
                e.optInt("cyl", 0).takeIf { it > 0 },
                e.optString("fuel").ifEmpty { null },
                if (e.has("maf")) e.getBoolean("maf") else null,
                blocks = e.optJSONArray("blocks").objects().map { parseBlock(it) },
            )
        }
        return CarModel(
            family = family,
            brand = m.getString("brand"),
            model = m.getString("model"),
            gen = m.optString("gen").ifEmpty { null },
            years = years,
            vin = m.optJSONArray("vin").strings().mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() },
            engines = engines,
            protocol = m.optInt("protocol", 0).takeIf { it in 1..9 },
            obd = m.optString("obd").ifEmpty { null },
            buses = m.optJSONObject("buses")?.let { text(it) },
            note = m.optJSONObject("note")?.let { text(it) },
            src = m.optJSONArray("src").strings(),
            verified = m.optBoolean("verified", false),
            blocks = m.optJSONArray("blocks").objects().map { parseBlock(it) },
            match = m.optJSONArray("match").objects().map { parseMatch(it) },
        )
    }

    /** `{ "role": "engine", "addr": "7E0", "dialect": "toyota_jdm" }` — 11-bit CAN or a K-line address. */
    private fun parseBlock(b: JSONObject): BlockSpec {
        val role = b.getString("role")
        require(role in BlockSpec.ROLES) { "role $role: one of ${BlockSpec.ROLES.joinToString()}" }
        val addr = b.getString("addr")
        val req = when {
            addr.matches(Regex("7[0-9A-Fa-f]{2}")) -> addr.toInt(16).also { require(it != 0x7DF) { "7DF is the broadcast id" } }
            addr.matches(Regex("[0-9A-Fa-f]{2}")) -> addr.toInt(16).also { require(it in 0x01..0xEF && it != 0x33) { "K-line address $addr" } }
            else -> throw IllegalArgumentException("addr $addr: 7xx (CAN) or xx (K-line)")
        }
        val resp = b.optString("rsp").ifEmpty { null }?.toInt(16) ?: if (req > 0xFF) req + 8 else req
        return BlockSpec(role, req, resp, b.getString("dialect"))
    }

    /**
     * A read request whose answer recognises a model or a dialect: `{ "hdr": "7E0", "svc": "21", "did": "C1",
     * "ascii": "^GRS18" }`. Same safety as the parameters: a read service to one module's physical id.
     */
    private fun parseMatch(r: JSONObject): MatchRule {
        val hdr = r.getString("hdr")
        require(hdr.matches(Regex("7[0-9A-Fa-f]{2}"))) { "hdr $hdr: 11-bit 700–7FF only" }
        val req = hdr.toInt(16)
        require(req != 0x7DF) { "7DF is the broadcast id" }
        val service = r.getString("svc")
        require(service in MATCH_SERVICES) { "service $service: only ${MATCH_SERVICES.joinToString("/")} (read)" }
        val didText = r.getString("did")
        require(didText.matches(Regex(if (service == "22") "[0-9A-Fa-f]{4}" else "[0-9A-Fa-f]{2}"))) { "did $didText doesn't fit service $service" }
        val ascii = r.optString("ascii").ifEmpty { null }?.let { Regex(it) }
        val hex = r.optString("hex").ifEmpty { null }?.let { Regex(it, RegexOption.IGNORE_CASE) }
        require(ascii != null || hex != null) { "match needs \"ascii\" or \"hex\"" }
        val w = r.optJSONObject("when")
        val obd = w?.optString("obd")?.ifEmpty { null }?.also { require(it in setOf("refused", "ok")) { "when.obd $it: refused / ok" } }
        val protocols = w?.optJSONArray("protocol")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
        return MatchRule(req, r.optString("rsp").ifEmpty { null }?.toInt(16) ?: (req + 8), service, didText.toInt(16), ascii, hex, obd, protocols)
    }

    /** One dialect as written in a file; what it leaves out comes from the one it [DialectSpec.extends]. */
    private fun parseDialect(d: JSONObject, id: String, family: String): DialectSpec {
        require(id.matches(Regex("[a-z0-9_]+"))) { "dialect id $id: a-z 0-9 _" }
        val live = d.optString("live").ifEmpty { null }?.also { require(it in Dialect.LIVE) { "live $it: one of ${Dialect.LIVE.joinToString()}" } }
        val dtc = d.optJSONArray("dtc")?.strings()?.onEach { require(it in Dialect.DTC) { "dtc $it: one of ${Dialect.DTC.joinToString()}" } }
        val format = d.optString("dtc_format").ifEmpty { null }?.also { require(it in Dialect.FORMATS) { "dtc_format $it: one of ${Dialect.FORMATS.joinToString()}" } }
        val ident = d.optString("ident").ifEmpty { null }?.also { require(it in Dialect.IDENT) { "ident $it: one of ${Dialect.IDENT.joinToString()}" } }
        val commands = d.optJSONArray("commands").objects().map { parseCommand(it, "db") }
        return DialectSpec(id, family, d.optString("extends").ifEmpty { null }, d.optJSONObject("title")?.let { text(it) },
            live, dtc, format, ident, d.optJSONArray("match").objects().map { parseMatch(it) }, commands)
    }

    /** Where a make keeps its modules: `{ "tag": "GM", "replace": true, "addresses": [{ "req": "240-25F", "rsp": "+400" }], … }`. */
    private fun parseModules(m: JSONObject): ModuleSearch {
        val addresses = m.optJSONArray("addresses").objects().flatMap { a ->
            val reqs = a.optJSONArray("req")?.strings() ?: listOf(a.getString("req"))
            val rsp = a.getString("rsp")
            reqs.flatMap { r ->
                val range = r.split('-').map { it.toInt(16) }.let { if (it.size == 2) it[0]..it[1] else it[0]..it[0] }
                range.map { req ->
                    // Any 11-bit id (GM keeps its modules on 24x → 64x) but the broadcast one.
                    require(req in 0x001..0x7FF && req != 0x7DF) { "module %03X: an 11-bit id, not 7DF".format(req) }
                    val resp = if (rsp.startsWith("+")) req + rsp.drop(1).toInt(16) else rsp.toInt(16)
                    require(resp in 0x001..0x7FF) { "reply %03X: an 11-bit id".format(resp) }
                    req to resp
                }
            }
        }
        val probes = m.optJSONArray("probes")?.strings()?.onEach { p ->
            require(p.matches(Regex("(3E00|22[0-9A-F]{4}|1A[0-9A-F]{2}|21[0-9A-F]{2})"))) { "probe $p: 3E00 or a read (22/1A/21)" }
        }
        val names = m.optJSONObject("names")?.let { n -> n.keys().asSequence().associate { k -> k.toInt(16) to text(n.getJSONObject(k)) } }.orEmpty()
        val diagNames = m.optJSONObject("diagNames")?.let { n ->
            n.keys().asSequence().associate { k ->
                val a = k.toInt(16)
                require(a in 0x00..0xFF) { "diagNames $k: a one-byte address" }
                a to text(n.getJSONObject(k))
            }
        }.orEmpty()
        return ModuleSearch(m.optString("tag").ifEmpty { null }, m.optBoolean("replace", false), addresses, probes, names, diagNames)
    }

    /**
     * Safety: whatever a file says, only read requests get through — \$22 ReadDataByIdentifier and
     * \$21 ReadDataByLocalIdentifier (KWP), to one module's physical 11-bit id (700–7FF, not the 7DF
     * broadcast). No sessions, security access, routines or writes can come from the database.
     */
    private fun parseCommand(c: JSONObject, source: String): ExtCommand {
        val hdr = c.getString("hdr")
        require(hdr.matches(Regex("7[0-9A-Fa-f]{2}"))) { "hdr $hdr: 11-bit 700–7FF only" }
        val req = hdr.toInt(16)
        require(req != 0x7DF) { "7DF is the broadcast id" }
        val service = c.getString("svc")
        require(service in READ_SERVICES) { "service $service: only ${READ_SERVICES.joinToString("/")} (read)" }
        val didText = c.getString("did")
        require(didText.matches(Regex(if (service == "21") "[0-9A-Fa-f]{2}" else "[0-9A-Fa-f]{4}"))) { "did $didText doesn't fit service $service" }
        val did = didText.toInt(16)
        val period = when (c.optString("rate")) {
            "fast" -> PollRate.FAST
            "slow" -> PollRate.SLOW
            else -> PollRate.MEDIUM
        }
        val signals = c.getJSONArray("signals").objects().map { parseSignal(it) }
        require(signals.isNotEmpty()) { "no signals" }
        return ExtCommand(
            req = req,
            resp = c.optString("rsp").ifEmpty { null }?.also { require(it.matches(Regex("7[0-9A-Fa-f]{2}"))) { "rsp $it: 11-bit 700–7FF only" } }?.toInt(16) ?: (req + 8),
            service = service,
            did = did,
            periodMs = period,
            signals = signals,
            models = c.optJSONArray("models").strings().toSet(),
            years = c.optJSONArray("years")?.let { if (it.length() == 2) it.getInt(0)..it.getInt(1) else null },
            source = source,
            fuel = c.optJSONArray("fuel").strings().toSet(),
            engines = c.optJSONArray("engines").strings(),
            exclusive = c.optBoolean("exclusive", false),
        )
    }

    /** The most cylinders a `cyl` range may name (W16). */
    const val MAX_CYL = 16

    /** The only services the database may send (see [parseCommand]). */
    val READ_SERVICES = setOf("21", "22")

    /** Recognising a model or a dialect may also read the KWP identification (\$1A, read only). */
    val MATCH_SERVICES = READ_SERVICES + "1A"

    private fun parseSignal(s: JSONObject): ExtSignal {
        val f = s.getJSONObject("fmt")
        val bix = f.optInt("bix", 0)
        val len = f.optInt("len", 8)
        require(len in 1..32) { "len $len" }
        val mul = f.optDouble("mul", 1.0)
        val div = f.optDouble("div", 1.0).takeIf { it != 0.0 } ?: 1.0
        val add = f.optDouble("add", 0.0)
        val signed = f.optBoolean("signed", false)
        val le = f.optBoolean("le", false)
        val poly = f.optJSONArray("poly")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }?.takeIf { it.isNotEmpty() }
        require(!le || (bix % 8 == 0 && len % 8 == 0)) { "le needs whole bytes" }
        val map = f.optJSONObject("map")?.let { mj -> mj.keys().asSequence().associate { k -> k.toLong() to text(mj.getJSONObject(k)) } }
        val cyl = s.optJSONArray("cyl")?.let { a ->
            require(a.length() == 2 && a.getInt(0) in 1..a.getInt(1) && a.getInt(1) <= MAX_CYL) { "cyl ${a}: [from, to] within 1..$MAX_CYL" }
            a.getInt(0)..a.getInt(1)
        }
        return ExtSignal(
            id = s.getString("id"),
            name = text(s.getJSONObject("name")),
            // "°C", or a translated one: { "ru": "об/мин", "en": "rpm" }.
            unit = s.optJSONObject("unit")?.let { text(it) } ?: s.optString("unit"),
            decimals = s.optInt("dec", if (mul / div < 1) 1 else 0),
            confidence = if (s.optString("conf") == "OK") "OK" else "?",
            group = s.optString("group", "other"),
            role = s.optString("role").ifEmpty { null },
            fmt = SignalFormat(bix, len, mul, div, add, signed, map, le, poly),
            src = s.optJSONArray("src").strings(),
            cyl = cyl,
        )
    }

    private fun text(o: JSONObject): String {
        val ru = o.optString("ru")
        val en = o.optString("en")
        return tr(ru.ifEmpty { en }, en.ifEmpty { ru })
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
}

class CarFamily(val id: String, var title: String) {
    val brands = mutableListOf<Brand>()
    val models = mutableListOf<CarModel>()
    val commands = mutableListOf<ExtCommand>()
    val features = mutableListOf<String>()
    /** Where this make keeps its modules; null — the standard OBD ids only. */
    var modules: ModuleSearch? = null
    /** How this make's blocks talk unless a model says otherwise. */
    val dialect: Dialect get() = CarDb.dialect(id) ?: Dialect.GENERIC
    /** Command key → index in [commands]: a linear search per added command was quadratic over ~3000 of them. */
    private val index = HashMap<String, Int>()

    /** Same request from another file: add only the signals it doesn't have yet (same bits and scaling). */
    fun addCommand(c: ExtCommand) {
        val i = index[c.key]
        if (i == null) {
            index[c.key] = commands.size
            commands += c
            return
        }
        val old = commands[i]
        // A request checked on a car keeps exactly its own values.
        if (old.exclusive) return
        val extra = c.signals.filter { s -> old.signals.none { it.fmt.sameValue(s.fmt) } }
        commands[i] = old.copy(signals = old.signals + extra, models = if (old.models.isEmpty() || c.models.isEmpty()) emptySet() else old.models + c.models)
    }

    /**
     * What to probe on [car]: commands without a model list, then the ones listed for this model and its
     * years. Without a known model — everything, the car answers what it has.
     */
    fun commandsFor(car: CarModel?): List<ExtCommand> {
        if (car == null) return commands.filter { it.fuel.isEmpty() }
        return commands.filter { c ->
            (c.models.isEmpty() || c.models.any { it.equals(car.model, ignoreCase = true) }) &&
                (c.years == null || car.years == null || c.years.first <= car.years.last && car.years.first <= c.years.last)
        }
    }

    /**
     * Probe order on [car]: curated, then what is known on this model, then the family's other models —
     * most widespread first (the same engine/gearbox often sits in several models, e.g. HFV6 in CTS and
     * Traverse). Every request is a read; the ones that answer are remembered per VIN.
     */
    /** Main-screen roles the family's requests can give (what is read on this make beyond standard OBD). */
    val roles: Set<String> by lazy { commands.flatMap { c -> c.signals.mapNotNull { it.role } }.toSet() }

    /**
     * Requests known on [brand] itself — for describing the make (the probe still asks the whole family):
     * curated ones for any of its models or the whole family, OBDb ones from that make's repositories.
     */
    fun commandsOfBrand(brand: String): List<ExtCommand> {
        val names = models.filter { it.brand == brand }.map { it.model.lowercase() }.toSet()
        val repo = "/OBDb/" + brand.replace(' ', '-') + "-"
        return commands.filter { c ->
            when (c.source) {
                "obdb" -> c.signals.any { s -> s.src.any { it.contains(repo, ignoreCase = true) } }
                else -> c.models.isEmpty() || c.models.any { it.lowercase() in names }
            }
        }
    }

    /** The most common socket location among [brand]'s models: (location, models with it, models with any). */
    fun commonObd(brand: String): Triple<String, Int, Int>? {
        val locs = models.filter { it.brand == brand }.mapNotNull { it.obd }
        val top = locs.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return Triple(top.key, top.value, locs.size)
    }

    fun probeOrder(car: CarModel?, fuel: String?): List<ExtCommand> {
        val ok = { c: ExtCommand -> c.fuel.isEmpty() || fuel != null && fuel in c.fuel }
        val mine = commandsFor(car).filter(ok).map { c ->
            if (c.engines.isEmpty() || car != null && car.engines.isNotEmpty() && car.engines.all { e -> c.engines.any { e.code?.contains(it, ignoreCase = true) == true } }) c
            else c.unverified()
        }
        val keys = mine.map { it.key }.toSet()
        val rest = commands.filter { it.key !in keys && ok(it) }.sortedByDescending { it.models.size }.map { it.unverified() }
        return mine.sortedBy { if (it.source == "db") 0 else 1 } + rest
    }
}

data class Brand(val name: String, val wmi: List<String>, val family: String)

/** A hand pick: a make alone (its family's parameters, the VIN finds the model) or a make and its model. */
data class CarChoice(val brand: String, val family: String, val model: CarModel? = null) {
    val id get() = model?.id ?: BRAND + brand

    companion object {
        const val BRAND = "brand:"
    }
}

data class Engine(
    val code: String?, val liters: Double?, val cyl: Int?, val fuel: String?, val maf: Boolean?,
    /** Blocks that come with this engine (its ECU's dialect). */
    val blocks: List<BlockSpec> = emptyList(),
) {
    val title: String get() = listOfNotNull(code, liters?.let { "%.1f".format(it) }, cyl?.let { tr("$it цил.", "$it cyl") }, fuel?.let(::fuelName)).joinToString(" · ")

    private fun fuelName(f: String) = when (f) {
        "petrol" -> tr("бензин", "petrol")
        "diesel" -> tr("дизель", "diesel")
        "hybrid" -> tr("гибрид", "hybrid")
        "electric" -> tr("электро", "electric")
        "lpg" -> tr("газ", "LPG")
        else -> f
    }
}

data class CarModel(
    val family: String,
    val brand: String,
    val model: String,
    val gen: String?,
    val years: IntRange?,
    val vin: List<Regex>,
    val engines: List<Engine>,
    /** ELM327 ATSP number of the engine ECU. */
    val protocol: Int?,
    /** Socket location id: picture obd_loc_<id>. */
    val obd: String?,
    val buses: String?,
    val note: String?,
    val src: List<String>,
    /** Connected with this app. */
    val verified: Boolean,
    /** Blocks whose dialect is known for this model (the rest talk the family's default). */
    val blocks: List<BlockSpec> = emptyList(),
    /** Answers that recognise this model without a VIN. */
    val match: List<MatchRule> = emptyList(),
) {
    /** Every block named anywhere in the model, for checks. */
    val allBlocks: List<BlockSpec> get() = blocks + engines.flatMap { it.blocks }

    /**
     * Block address → its spec: the model's own blocks, and an engine's blocks when every engine of the
     * model agrees on that address (or there is one engine) — with several engines the car's is unknown.
     */
    val knownBlocks: Map<Int, BlockSpec> by lazy {
        val out = linkedMapOf<Int, BlockSpec>()
        val byEngine = engines.flatMap { it.blocks }.groupBy { it.req }
        for ((req, list) in byEngine) if (list.size == engines.size && list.map { it.dialect }.distinct().size == 1) out[req] = list.first()
        for (b in blocks) out[b.req] = b
        out
    }

    /** Unique: one generation can be split by years (BMW E90 before/after D-CAN, RAV4 XA20 2005). */
    val id get() = "$brand|$model|${gen.orEmpty()}|${years?.first ?: ""}-${years?.last ?: ""}"
    val yearsText get() = years?.let { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }.orEmpty()
    /** "Toyota RAV4 XA20 (2000–2005)". */
    val title get() = listOfNotNull(brand, model, gen).joinToString(" ") + if (years != null) " ($yearsText)" else ""
    /** "RAV4 XA20 · 2000–2005". */
    val shortTitle get() = listOfNotNull(model, gen).joinToString(" ") + if (years != null) " · $yearsText" else ""
}

/** Where a value sits in the answer and how to scale it (SCHEMA.md `fmt`). */
data class SignalFormat(
    val bix: Int, val len: Int, val mul: Double, val div: Double, val add: Double, val signed: Boolean, val map: Map<Long, String>?,
    /** Little-endian (whole bytes): the first byte is the lowest. */
    val le: Boolean = false,
    /** Polynomial instead of mul/div/add: value = poly[0] + poly[1]·raw + poly[2]·raw² … (Nissan CVT temp). */
    val poly: List<Double>? = null,
) {
    fun sameValue(o: SignalFormat) = bix == o.bix && len == o.len && mul == o.mul && div == o.div && add == o.add && signed == o.signed && le == o.le && poly == o.poly

    /** Raw bits: [len] bits from bit [bix] of [data], big-endian, bit 0 = MSB of the first byte. Null when the answer is too short. */
    fun raw(data: IntArray): Long? {
        if (bix < 0 || (bix + len + 7) / 8 > data.size) return null
        var v = 0L
        if (le) for (i in (bix + len) / 8 - 1 downTo bix / 8) v = (v shl 8) or data[i].toLong()
        else for (i in bix until bix + len) v = (v shl 1) or ((data[i / 8] shr (7 - i % 8)) and 1).toLong()
        if (signed && len > 1 && v >= 1L shl (len - 1)) v -= 1L shl len
        return v
    }

    /** (number, text): a mapped value comes as text. */
    fun decode(data: IntArray): Pair<Double?, String?>? {
        val r = raw(data) ?: return null
        map?.let { m -> return null to (m[r] ?: r.toString()) }
        poly?.let { p -> return p.foldRight(0.0) { c, acc -> acc * r + c } to null }
        return r * mul / div + add to null
    }
}

data class ExtSignal(
    val id: String,
    val name: String,
    val unit: String,
    val decimals: Int,
    /** "OK" — two sources agree or seen on a car, "?" — one source. */
    val confidence: String,
    /** Which screen needs it most: "main", "fuel" or "other". */
    val group: String,
    /** What it is ("oil_temp", "atf_temp"…), so the main screen finds it on any make. */
    val role: String?,
    val fmt: SignalFormat,
    /** Where it comes from (URLs). */
    val src: List<String> = emptyList(),
    /** Engines with this many cylinders have it ("cylinder 7" = 7..16, "V8" = 8..8); null — any. */
    val cyl: IntRange? = null,
) {
}

/** One manufacturer request (\$21 / \$22) and the values in its answer. */
data class ExtCommand(
    val req: Int,
    val resp: Int,
    val service: String,
    val did: Int,
    val periodMs: Long,
    val signals: List<ExtSignal>,
    val models: Set<String> = emptySet(),
    val years: IntRange? = null,
    /** "db" (curated) or "obdb". */
    val source: String = "db",
    /** Only on these fuels ("diesel"): the same DID means something else on the other ECUs. Empty = any. */
    val fuel: Set<String> = emptySet(),
    /** Known on these engines (code substrings, "EA888"); on another engine the values are marked unconfirmed ("(?)" in report.txt). */
    val engines: List<String> = emptyList(),
    /** Checked on a car: OBDb values for the same request are not added to it. */
    val exclusive: Boolean = false,
) {
    /** The same request with every value marked unverified — known on another model or engine, not on this car. */
    fun unverified() = copy(signals = signals.map { it.copy(confidence = "?") })

    /**
     * Only the values an engine with [cylinders] has (no "cylinder 7" on a V6, no "V8" DID on a V6);
     * null when none is left. Unknown count — everything, the car answers what it has.
     */
    fun forCylinders(cylinders: Int?): ExtCommand? {
        if (cylinders == null || signals.all { it.cyl == null }) return this
        val left = signals.filter { it.cyl == null || cylinders in it.cyl }
        return if (left.isEmpty()) null else if (left.size == signals.size) this else copy(signals = left)
    }

    // Stored, not getters: the loader, the probe and the poll loop look them up thousands of times.
    val didHex = if (service == "21") "%02X".format(did) else "%04X".format(did)
    val key = "%03X:%s.%s".format(req, service, didHex)
    /** The main-screen group: the most important one among its values. */
    val group get() = signals.map { it.group }.minByOrNull { listOf("main", "fuel", "other").indexOf(it).let { i -> if (i < 0) 9 else i } } ?: "other"

    /** Reading key of a value: the request and the signal id ("7E0:22.1172.BAT"); without an id just the request ("7E2:22.1940"). */
    fun readingKey(s: ExtSignal) = if (s.id.isEmpty()) key else "$key.${s.id}"
}

/** One block of a model: what it is, where it answers and which dialect it talks. */
data class BlockSpec(val role: String, val req: Int, val resp: Int, val dialect: String) {
    companion object {
        val ROLES = setOf("engine", "gearbox", "abs", "airbag", "body", "cluster", "climate", "steering", "gateway", "other")
    }
}

/**
 * A read request and what its answer must look like: recognises a model (no VIN) or a block's dialect.
 * [obd] — only when standard OBD (Mode 01) was "refused" with a negative answer or was "ok"; [protocols] —
 * only on these ATSP numbers (empty = any CAN).
 */
data class MatchRule(
    val req: Int, val resp: Int, val service: String, val did: Int,
    val ascii: Regex?, val hex: Regex?, val obd: String?, val protocols: Set<Int>,
) {
    val didHex get() = if (service == "22") "%04X".format(did) else "%02X".format(did)
    /** The same request for several rules is sent once. */
    val key get() = "%03X:%s%s".format(req, service, didHex)

    fun fits(protocol: Int, obdState: String?): Boolean =
        (obd == null || obd == obdState) && (if (protocols.isEmpty()) protocol in 6..9 else protocol in protocols)

    /** Whether an answer ([data] after the echo) is this one. */
    fun matches(data: IntArray): Boolean {
        val text = data.map { if (it in 0x20..0x7E) it.toChar() else '.' }.joinToString("")
        val hexText = data.joinToString(" ") { "%02X".format(it) }
        return (ascii == null || ascii.containsMatchIn(text)) && (hex == null || hex.containsMatchIn(hexText))
    }
}

/** How one kind of ECU talks: services for live data, codes and identification, and its own parameters. */
data class Dialect(
    val id: String,
    val family: String?,
    val title: String?,
    /** Standard PIDs: "01" — Mode 01 broadcast; "21" — the same PID numbers with \$21 to the block (Toyota JDM). */
    val live: String,
    /** How to read the block's DTC memory, tried in order until one answers: [DTC]. */
    val dtc: List<String>,
    /** How KWP codes are numbered: "sae" or "vag" (5-digit VAG numbers). */
    val dtcFormat: String,
    /** How to read the block's identification: "gm_1a" (GMLAN \$1A) or "uds_kwp" (\$22 F1xx, then KWP \$1A). */
    val ident: String,
    /** Answers that recognise this dialect on a block of an unknown car. */
    val match: List<MatchRule>,
    /** Manufacturer parameters of this dialect (a family's default dialect: the family's own). */
    val commands: List<ExtCommand>,
) {
    companion object {
        val LIVE = setOf("01", "21")
        /** GM \$A9 81, UDS \$19 02, KWP \$18 02 FF00, KWP \$13 (readDiagnosticTroubleCodes). */
        val DTC = setOf("gm_a9", "uds19", "kwp18", "kwp13")
        val FORMATS = setOf("sae", "vag")
        val IDENT = setOf("gm_1a", "uds_kwp")

        /** Whatever answers on the OBD port with no make known: standard OBD, UDS / KWP. */
        val GENERIC = Dialect("obd2", null, null, "01", listOf("uds19", "kwp18"), "sae", "uds_kwp", emptyList(), emptyList())
    }
}

/** A dialect as written: null fields come from the dialect it extends (or its family's). */
internal data class DialectSpec(
    val id: String,
    val family: String,
    val extends: String? = null,
    val title: String? = null,
    val live: String? = null,
    val dtc: List<String>? = null,
    val dtcFormat: String? = null,
    val ident: String? = null,
    val match: List<MatchRule> = emptyList(),
    val commands: List<ExtCommand> = emptyList(),
)

/**
 * Where a make keeps its diagnostic modules: [addresses] (request → reply) to probe, after the standard
 * OBD list or instead of it ([replace]); [probes] — what to ask each one; [names] by request id;
 * [diagNames] by the module's own diagnostic address (GM \$1A B0), which wins over the request id.
 */
data class ModuleSearch(
    val tag: String?,
    val replace: Boolean,
    val addresses: List<Pair<Int, Int>>,
    val probes: List<String>?,
    val names: Map<Int, String>,
    val diagNames: Map<Int, String> = emptyMap(),
)
