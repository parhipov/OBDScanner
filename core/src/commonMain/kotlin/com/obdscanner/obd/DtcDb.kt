package com.obdscanner.obd

import com.obdscanner.L10n
import com.obdscanner.tr
import com.obdscanner.util.JSONObject
import com.obdscanner.util.putIfMissing
import kotlin.concurrent.Volatile

/** A code's text in both languages; either can be missing (then the other one is shown). */
data class DtcText(val en: String?, val ru: String?) {
    val text: String? get() = if (L10n.ru) ru ?: en else en ?: ru
    /** True when the shown text is not in the UI language (a Russian UI showing an English-only entry). */
    val untranslated: Boolean get() = if (L10n.ru) ru == null && en != null else en == null && ru != null
}

/** One code in one set: title, optional description, causes (most likely first) and symptoms, and where it comes from. */
data class DtcEntry(
    val code: String,
    val title: DtcText,
    val desc: DtcText?,
    val causes: List<DtcText>,
    val symptoms: List<DtcText>,
    val set: String,
    val source: String,
)

/**
 * The trouble code database: core/data/dtc/<set>.json (assets/dtc in the APK) (built by tools/dtc/, format in tools/dtc/SCHEMA.md).
 * `generic` holds the SAE J2012 codes that mean the same on every car; a make's set (`gm`, `vag`…, the car
 * database family ids) holds its manufacturer codes and wins for that make. Loaded in the background like
 * [com.obdscanner.car.CarDb].
 */
object DtcDb {
    /** set → code → entry. */
    @Volatile private var sets: Map<String, Map<String, DtcEntry>> = emptyMap()
    /** Failure type byte tables: "j2012" (UDS, ISO 14229 / SAE J2012) and "gm" (GMLAN \$A9 symptom byte). */
    @Volatile private var ftb: Map<String, Map<Int, DtcText>> = emptyMap()
    @Volatile private var started = false

    /** Family of the connected / picked car (car database id), for manufacturer codes. */
    @Volatile var family: String? = null

    /** Reads the files ([texts]: assets/dtc) in the background ([background] runs it); later calls do nothing. */
    fun init(background: (() -> Unit) -> Unit, texts: () -> List<String>, log: (String, Throwable?) -> Unit) {
        if (started) return
        started = true
        background {
            val all = texts()
            runCatching { load(all) }.onFailure { log("trouble code database", it) }
        }
    }

    /** Parses the files; a set split over several files is merged, the first file wins per code. */
    fun load(texts: List<String>) {
        val out = mutableMapOf<String, MutableMap<String, DtcEntry>>()
        val types = mutableMapOf<String, MutableMap<Int, DtcText>>()
        for (t in texts) {
            val j = JSONObject(t)
            j.optJSONObject("ftb")?.let { f ->
                for (table in f.keys()) {
                    val m = types.getOrPut(table) { mutableMapOf() }
                    val tj = f.getJSONObject(table)
                    for (k in tj.keys()) m.putIfMissing(k.toInt(16), text(tj.getJSONObject(k)))
                }
            }
            val set = j.optString("set")
            if (set.isEmpty()) continue
            val source = j.optString("source")
            val m = out.getOrPut(set) { mutableMapOf() }
            // Causes and symptoms are shared phrases: the file lists each once, codes refer to them by index.
            val shared = j.optJSONArray("texts")?.let { a -> List(a.length()) { text(a.getJSONObject(it)) } }.orEmpty()
            fun refs(c: JSONObject, key: String) = c.optJSONArray(key)?.let { a -> List(a.length()) { a.getInt(it) }.mapNotNull(shared::getOrNull) }.orEmpty()
            val codes = j.optJSONObject("codes") ?: continue
            for (code in codes.keys()) {
                val c = codes.getJSONObject(code)
                m.putIfMissing(code, DtcEntry(code, text(c), c.optJSONObject("d")?.let(::text), refs(c, "c"), refs(c, "s"), set, source))
            }
        }
        sets = out
        ftb = types
    }

    private fun text(o: JSONObject) = DtcText(o.optString("en").ifEmpty { null }, o.optString("ru").ifEmpty { null })

    val size: Int get() = sets.values.sumOf { it.size }

    /**
     * The entry for [code]: the make's own set first (manufacturer codes, and makes that redefine a
     * generic one), then the generic SAE set — but only for the generic ranges, a P1 code means
     * something else on every make.
     */
    fun find(code: String, family: String? = this.family): DtcEntry? {
        val c = code.uppercase()
        family?.let { f -> sets[f]?.get(c)?.let { return it } }
        return if (DtcAnatomy.isGeneric(c)) sets["generic"]?.get(c) else null
    }

    /** Only the make's own set: a hand-written generic text beats the imported one, a make's text beats both. */
    fun ofMake(code: String, family: String? = this.family): DtcEntry? = family?.let { sets[it]?.get(code.uppercase()) }

    /** Every make that has its own text for this code — shown when the make is unknown. */
    fun others(code: String): List<DtcEntry> = sets.filterKeys { it != "generic" }.values.mapNotNull { it[code.uppercase()] }

    /** Meaning of a failure type byte; [gm] — the GMLAN \$A9 symptom byte, else SAE J2012 / ISO 14229. */
    fun failureType(ftbByte: Int, gm: Boolean): DtcText? {
        val table = if (gm) "gm" else "j2012"
        ftb[table]?.get(ftbByte)?.let { return it }
        // Not listed: at least the category, by the high digit (both tables group by it).
        val cat = ftb[table + "_cat"]?.get(ftbByte shr 4) ?: return null
        return DtcText(cat.en?.let { "Category: $it" }, cat.ru?.let { "Категория: $it" })
    }
}

/** What the code itself tells, by SAE J2012: system, who defines it, subsystem. */
object DtcAnatomy {
    /** SAE-defined ranges: P0, P2, P34–P3F, B0, C0, U0, U3. The rest are the manufacturer's. */
    fun isGeneric(code: String): Boolean {
        if (code.length != 5) return false
        return when (code[0]) {
            'P' -> code[1] == '0' || code[1] == '2' || code[1] == '3' && code[2] >= '4'
            'B', 'C', 'U' -> code[1] == '0' || code[1] == '3'
            else -> false
        }
    }

    fun system(code: String): String? = when (code.firstOrNull()) {
        'P' -> tr("двигатель и трансмиссия (P)", "powertrain: engine and transmission (P)")
        'C' -> tr("шасси: ABS, стабилизация, подвеска, рулевое (C)", "chassis: ABS, stability, suspension, steering (C)")
        'B' -> tr("кузов: свет, двери, подушки, климат, приборка (B)", "body: lights, doors, airbags, climate, cluster (B)")
        'U' -> tr("сеть: связь между блоками (U)", "network: communication between modules (U)")
        else -> null
    }

    fun owner(code: String): String = if (isGeneric(code))
        tr("Общий код SAE: значит одно и то же на любой машине.", "Generic SAE code: means the same on every car.")
    else tr("Код производителя: значение своё у каждой марки, описание другой марки может не подойти.",
        "Manufacturer code: every make defines it its own way; another make's text may not apply.")

    /** SAE J2012 subsystem by the third character (P0/P2 and U0 only; the rest isn't standardised). */
    fun subsystem(code: String): String? {
        if (code.length != 5) return null
        return when (code.substring(0, 2)) {
            "P0", "P2" -> when (code[2]) {
                '0', '1', '2' -> tr("топливо и воздух (дозирование, форсунки, наддув, VVT)", "fuel and air metering (injectors, boost, VVT)")
                '3' -> tr("зажигание и пропуски", "ignition and misfire")
                '4' -> tr("экология: EGR, EVAP, катализатор, вторичный воздух", "emissions: EGR, EVAP, catalyst, secondary air")
                '5' -> tr("скорость, холостой ход, вспомогательные входы", "vehicle speed, idle control, auxiliary inputs")
                '6' -> tr("блок управления и выходные цепи", "computer and output circuits")
                '7', '8', '9' -> tr("трансмиссия", "transmission")
                'A', 'B', 'C' -> if (code[1] == '0') tr("гибридная силовая установка", "hybrid propulsion") else null
                else -> null
            }
            "U0" -> when (code[2]) {
                '0' -> tr("электрика шины CAN (обрыв, замыкание, bus off)", "network electrical (open, short, bus off)")
                '1', '2' -> tr("пропала связь с блоком", "lost communication with a module")
                '3' -> tr("несовместимость ПО / конфигурации блоков", "software or configuration incompatibility")
                '4' -> tr("от блока пришли недостоверные данные", "invalid data received from a module")
                else -> null
            }
            else -> null
        }
    }
}
