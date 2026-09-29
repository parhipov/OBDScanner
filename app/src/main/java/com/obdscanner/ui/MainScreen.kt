package com.obdscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obdscanner.VehicleInfo
import com.obdscanner.gm.GmKnown
import com.obdscanner.obd.Reading
import com.obdscanner.obd.pick
import com.obdscanner.tr

/** A block of the main screen: its own accent (title) and tile background. */
private class Section(val title: String, val accent: Color, val tile: Color, val items: List<Pair<String, String>>)

private val SECTIONS = listOf(
    Section(tr("Двигатель", "Engine"), Color(0xFF7AB8FF), Color(0xFF16233A), listOf(
        "01.0C" to tr("Обороты", "RPM"),
        "01.0D" to tr("Скорость", "Speed"),
        "01.05" to tr("ОЖ", "Coolant"),
        "01.04" to tr("Нагрузка", "Load"),
        "01.11" to tr("Дроссель", "Throttle"),
        "01.49" to tr("Педаль газа", "Accel pedal"),
        "01.0E" to tr("Опережение", "Timing advance"),
        "01.10" to "MAF",
        "01.0B" to "MAP",
        "01.0F" to tr("Воздух на впуске", "Intake air"),
        "01.1F" to tr("С момента пуска", "Run time"),
    )),
    Section(tr("Масло", "Oil"), Color(0xFFFFC857), Color(0xFF2E2614), listOf(
        "01.5C" to tr("Масло двигателя", "Engine oil"),
        "22.1154" to tr("Масло двигателя", "Engine oil"),
        "22.1470" to tr("Давление масла", "Oil pressure"),
        "22.119F" to tr("Ресурс масла", "Oil life"),
    )),
    Section(tr("АКПП", "Transmission"), Color(0xFFB39DFF), Color(0xFF271D38), listOf(
        "22.1940" to tr("Масло АКПП", "Trans fluid"),
        "22.199A" to tr("Передача", "Gear"),
        "calc.gearRatio" to tr("Передаточное отношение", "Gear ratio"),
        "22.1991" to tr("Проскальз. ГТ", "TC slip"),
        "22.1941" to tr("Входной вал", "Input shaft"),
        "22.1942" to tr("Выходной вал", "Output shaft"),
    )),
    Section(tr("Топливо", "Fuel"), Color(0xFF6FD58E), Color(0xFF16291E), listOf(
        "01.2F" to tr("Топливо в баке", "Fuel level"),
        "calc.l100" to tr("Расход", "Fuel economy"),
        "calc.lph" to tr("Расход, л/ч", "Fuel rate, L/h"),
        "calc.trim1" to tr("Коррекция Б1", "Fuel trim B1"),
        "calc.trim2" to tr("Коррекция Б2", "Fuel trim B2"),
    )),
    Section(tr("Электрика и среда", "Electrical & ambient"), Color(0xFF5ED1D9), Color(0xFF14282C), listOf(
        "01.42" to tr("Бортсеть (ЭБУ)", "Voltage (ECU)"),
        "000:ATRV" to tr("Бортсеть (адаптер)", "Voltage (adapter)"),
        "01.46" to tr("За бортом", "Ambient"),
        "01.33" to tr("Атм. давление", "Baro pressure"),
    )),
)

@Composable
fun MainScreen(r: Map<String, Reading>, v: VehicleInfo) {
    val mil = r.pick("01.01.MIL")?.value
    val dtcCount = r.pick("01.01.DTC")?.value?.toInt()
    val gmCodes = v.gmDtcs.flatMap { it.codes }
    // Card help: (label, reading) — the dialog shows the live value when there is one.
    var help by remember { mutableStateOf<Pair<String, Reading>?>(null) }
    help?.let { (label, h) -> CardHelpDialog(label, r[h.key] ?: h, sample = r[h.key] == null) { help = null } }
    // Before the first value arrives (no connection yet) show every card empty, so the help can be read offline.
    val offline = r.isEmpty()
    val sections = SECTIONS.map { s ->
        s to if (offline) s.items.distinctBy { it.second }.map { (k, label) -> label to placeholder(k, label) }
        else s.items.mapNotNull { (k, label) ->
            val reading = if (k.contains(':')) r[k] else r.pick(k)
            reading?.let { label to it }
        }
    }.filter { it.second.isNotEmpty() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(165.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                offline -> Muted(tr("Нет данных — подключитесь к адаптеру на вкладке «Связь». Нажмите на карточку, чтобы прочитать, что это за параметр.",
                    "No data — connect to the adapter on the \"Connect\" tab. Tap a card to read what it shows."))
                mil == 1.0 -> Hint(tr("Check Engine горит · ошибок в памяти: ${dtcCount ?: "?"} — см. вкладку «Ошибки»",
                    "Check Engine is on · stored codes: ${dtcCount ?: "?"} — see the \"Codes\" tab"), Bad)
                (dtcCount ?: 0) > 0 || v.dtcs.isNotEmpty() -> Hint(tr("Есть коды ошибок: ${v.dtcs.size} — см. вкладку «Ошибки»",
                "Trouble codes: ${v.dtcs.size} — see the \"Codes\" tab"), Warn)
                v.step.isNotEmpty() -> Hint(tr("Опрос автомобиля: ${v.step}", "Scanning the car: ${v.step}"), Good)
            }
        }
        if (gmCodes.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            val active = gmCodes.count { it.current }
            Hint(tr("Ошибки всех блоков: ${gmCodes.size}", "Codes in all modules: ${gmCodes.size}") +
                (if (active > 0) tr(", активных $active", ", $active active") else "") + tr(" — см. вкладку «Ошибки»", " — see the \"Codes\" tab"),
                if (active > 0) Bad else Warn)
        }
        for ((s, tiles) in sections) {
            item(key = "h:${s.title}", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(s) }
            items(tiles, key = { it.second.key }) { (label, reading) ->
                if (offline) ValueTile(label, reading, color = MaterialTheme.colorScheme.outline, container = s.tile,
                    note = tr("пример · нет связи", "sample · offline"), onClick = { help = label to reading })
                else ValueTile(label, reading, color = tileColor(reading), container = s.tile, onClick = { help = label to reading })
            }
        }
    }
}

@Composable
private fun SectionHeader(s: Section) {
    Row(Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).background(s.accent, RoundedCornerShape(2.dp)))
        Text(s.title, style = MaterialTheme.typography.titleMedium, color = s.accent, modifier = Modifier.padding(start = 8.dp))
    }
}

private val U_RPM = tr("об/мин", "rpm")
private val U_KPA = tr("кПа", "kPa")
private val U_V = tr("В", "V")

/**
 * Typical values of a warm car idling in Park — shown greyed out on the offline cards, so the dashboard
 * looks like itself before the first connection: value, unit, decimals (units match the live readings).
 */
private val SAMPLES: Map<String, Triple<Double, String, Int>> = mapOf(
    "01.0C" to Triple(700.0, U_RPM, 0),
    "01.0D" to Triple(0.0, tr("км/ч", "km/h"), 0),
    "01.05" to Triple(90.0, "°C", 0),
    "01.04" to Triple(22.0, "%", 1),
    "01.11" to Triple(15.7, "%", 1),
    "01.49" to Triple(16.1, "%", 1),
    "01.0E" to Triple(12.0, "°", 1),
    "01.10" to Triple(3.5, tr("г/с", "g/s"), 2),
    "01.0B" to Triple(30.0, U_KPA, 0),
    "01.0F" to Triple(30.0, "°C", 0),
    "01.1F" to Triple(600.0, tr("с", "s"), 0),
    "01.5C" to Triple(95.0, "°C", 0),
    "22.1154" to Triple(95.0, "°C", 0),
    "22.1470" to Triple(250.0, U_KPA, 0),
    "22.119F" to Triple(70.0, "%", 0),
    "22.1940" to Triple(80.0, "°C", 0),
    "22.199A" to Triple(1.0, "", 0),
    "calc.gearRatio" to Triple(4.06, "", 2),
    "22.1991" to Triple(15.0, U_RPM, 0),
    "22.1941" to Triple(690.0, U_RPM, 0),
    "22.1942" to Triple(0.0, U_RPM, 0),
    "01.2F" to Triple(50.0, "%", 1),
    "calc.l100" to Triple(11.0, tr("л/100км", "L/100km"), 1),
    "calc.lph" to Triple(1.1, tr("л/ч", "L/h"), 2),
    "calc.trim1" to Triple(1.6, "%", 1),
    "calc.trim2" to Triple(-0.8, "%", 1),
    "01.42" to Triple(14.2, U_V, 2),
    "000:ATRV" to Triple(14.1, U_V, 1),
    "01.46" to Triple(20.0, "°C", 0),
    "01.33" to Triple(100.0, U_KPA, 0),
)

/** An offline card for [key] (a main-screen source or "ecu:source"), keyed like the live reading it stands in for. */
private fun placeholder(key: String, label: String): Reading {
    val gm = GmKnown.all.firstOrNull { "%s.%04X".format(it.service, it.did) == key }
    val full = when {
        key.contains(':') -> key
        gm != null -> gm.key
        key.startsWith("calc.") -> Reading.key(0, key)
        else -> Reading.key(0x7E8, key)
    }
    val sample = SAMPLES[key]
    return Reading(full, full.substringBefore(':').toInt(16), gm?.displayName ?: label, sample?.first, null,
        sample?.second ?: "", sample?.third ?: 1)
}

private fun tileColor(reading: Reading): Color? = when (reading.source) {
    "calc.trim1", "calc.trim2" -> trimColor(reading.value)
    "01.05" -> reading.value?.let { if (it > 105) Bad else if (it < 70) Warn else null }
    "22.1940" -> reading.value?.let { if (it > 110) Bad else if (it > 95) Warn else null }
    "22.1154" -> reading.value?.let { if (it > 135) Bad else if (it > 120) Warn else null }
    "22.119F" -> reading.value?.let { if (it < 10) Bad else if (it < 25) Warn else null }
    else -> null
}
