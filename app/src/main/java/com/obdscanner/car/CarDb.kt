package com.obdscanner.car

import android.content.Context
import com.obdscanner.obd.PollRate
import com.obdscanner.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * The car database: assets/cars/<family>.json (curated) and assets/cars/obdb/<family>.json
 * (generated from OBDb by tools/cars/obdb_import.py). Format: tools/cars/SCHEMA.md.
 */
object CarDb {
    @Volatile var families: Map<String, CarFamily> = emptyMap()
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
     * Reads every JSON under assets/cars on a background thread; later calls do nothing. Not on the
     * main thread: on the phone it took longer than Android allows for app startup (ANR, 1.04).
     */
    fun init(context: Context) {
        if (families.isNotEmpty() || started) return
        started = true
        val am = context.applicationContext.assets
        thread(name = "CarDb", isDaemon = true) {
            val t0 = System.currentTimeMillis()
            val texts = buildList {
                for (dir in listOf("cars", "cars/obdb")) for (f in am.list(dir).orEmpty().filter { it.endsWith(".json") }.sorted()) {
                    add(am.open("$dir/$f").bufferedReader(Charsets.UTF_8).use { it.readText() })
                }
            }
            runCatching { load(texts) }.onFailure { android.util.Log.e("OBD", "car database", it) }
            android.util.Log.i("OBD", "car database: ${models.size} models, ${families.values.sumOf { it.commands.size }} requests, ${System.currentTimeMillis() - t0} ms")
            _loaded.value = true
        }
    }

    @Volatile private var started = false

    /** Parses and merges the files: curated ones (with `brands`) first, so their parameters win. */
    fun load(texts: List<String>) {
        val parsed = texts.map { JSONObject(it) }.sortedBy { if (it.has("brands")) 0 else 1 }
        val out = linkedMapOf<String, CarFamily>()
        val bad = mutableListOf<String>()
        fun <T> tryParse(what: String, f: () -> T): T? = runCatching(f).onFailure { bad += "$what: ${it.message}" }.getOrNull()
        for (j in parsed) {
            val id = j.getString("family")
            val file = if (j.has("brands")) "$id.json" else "obdb/$id.json"
            val f = out.getOrPut(id) { CarFamily(id, j.optString("title", id)) }
            if (j.has("title") && f.title == id) f.title = j.getString("title")
            j.optJSONArray("brands")?.objects()?.forEach { b -> f.brands += Brand(b.getString("name"), b.optJSONArray("wmi").strings().map { it.uppercase() }, id) }
            j.optJSONArray("models")?.objects()?.forEach { m -> tryParse("$file ${m.optString("brand")} ${m.optString("model")} ${m.optString("gen")}") { parseModel(m, id) }?.let { f.models += it } }
            j.optJSONArray("features")?.objects()?.forEach { f.features += text(it) }
            val source = if (j.has("brands")) "db" else "obdb"
            j.optJSONArray("commands")?.objects()?.forEach { c -> tryParse("$file ${c.optString("hdr")} ${c.optString("svc")} ${c.optString("did")}") { parseCommand(c, source) }?.let { f.addCommand(it) } }
        }
        families = out
        problems = bad
        _loaded.value = true
    }

    fun family(id: String?): CarFamily? = id?.let { families[it] }

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
        val now = Calendar.getInstance().get(Calendar.YEAR)
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
        )
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
        )
    }

    /** The only services the database may send (see [parseCommand]). */
    val READ_SERVICES = setOf("21", "22")

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
        return ExtSignal(
            id = s.getString("id"),
            name = text(s.getJSONObject("name")),
            unit = s.optString("unit"),
            decimals = s.optInt("dec", if (mul / div < 1) 1 else 0),
            confidence = if (s.optString("conf") == "OK") "OK" else "?",
            group = s.optString("group", "other"),
            role = s.optString("role").ifEmpty { null },
            fmt = SignalFormat(bix, len, mul, div, add, signed, map, le, poly),
            src = s.optJSONArray("src").strings(),
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

data class Engine(val code: String?, val liters: Double?, val cyl: Int?, val fuel: String?, val maf: Boolean?) {
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
) {
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
) {
    val displayName get() = if (confidence == "OK") name else "$name (?)"
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
    /** "db" (curated), "obdb" or "code" (GmKnown). */
    val source: String = "db",
    /** GmKnown: a formula in code instead of [ExtSignal.fmt]. */
    val code: ((IntArray) -> Double?)? = null,
    /** Only on these fuels ("diesel"): the same DID means something else on the other ECUs. Empty = any. */
    val fuel: Set<String> = emptySet(),
    /** Known on these engines (code substrings, "EA888"); on another engine the values are marked "(?)". */
    val engines: List<String> = emptyList(),
) {
    /** The same request with every value marked unverified — known on another model or engine, not on this car. */
    fun unverified() = copy(signals = signals.map { it.copy(confidence = "?") })

    // Stored, not getters: the loader, the probe and the poll loop look them up thousands of times.
    val didHex = if (service == "21") "%02X".format(did) else "%04X".format(did)
    val key = "%03X:%s.%s".format(req, service, didHex)
    /** The main-screen group: the most important one among its values. */
    val group get() = signals.map { it.group }.minByOrNull { listOf("main", "fuel", "other").indexOf(it).let { i -> if (i < 0) 9 else i } } ?: "other"

    /** Reading key of a value: GmKnown ones keep the old "7E2:22.1940", database ones add the signal id. */
    fun readingKey(s: ExtSignal) = if (code != null) key else "$key.${s.id}"
}
