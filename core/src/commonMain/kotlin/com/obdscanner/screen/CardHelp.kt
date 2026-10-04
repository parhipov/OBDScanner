package com.obdscanner.screen

import com.obdscanner.car.CarDb
import com.obdscanner.obd.Reading
import com.obdscanner.obd.ecuName
import com.obdscanner.util.format

/**
 * Which help a main screen card opens: the name of its texts — a string-array in the app's res/values/help_….xml
 * and res/values-ru/help_….xml (what it is / normal values / what a deviation means); the browser page reads
 * the same files.
 */
object CardHelp {
    private val bySource: Map<String, String> = mapOf(
        "01.0C" to "help_rpm",
        "01.0D" to "help_speed",
        "01.05" to "help_coolant",
        "01.04" to "help_load",
        "01.11" to "help_throttle",
        "01.49" to "help_pedal",
        "01.0E" to "help_timing",
        "01.10" to "help_maf",
        "01.0B" to "help_map",
        "01.0F" to "help_iat",
        "01.1F" to "help_runtime",
        "01.5C" to "help_oil_temp",
        "22.1154" to "help_oil_temp",
        "22.1470" to "help_oil_pressure",
        "22.119F" to "help_oil_life",
        "22.1940" to "help_atf_temp",
        "22.199A" to "help_gear",
        "calc.gearRatio" to "help_gear_ratio",
        "22.1991" to "help_tcc_slip",
        "22.1941" to "help_input_shaft",
        "22.1942" to "help_output_shaft",
        "01.2F" to "help_fuel_level",
        "calc.l100" to "help_l100",
        "calc.lph" to "help_lph",
        "calc.trim1" to "help_trim",
        "calc.trim2" to "help_trim",
        "01.42" to "help_ecu_voltage",
        "ATRV" to "help_adapter_voltage",
        "01.46" to "help_ambient",
        "01.33" to "help_baro",
    )

    /** Manufacturer parameters of any make, by their role (tools/cars/SCHEMA.md). */
    private val byRole: Map<String, String> = mapOf(
        "oil_temp" to "help_oil_temp",
        "oil_pressure" to "help_oil_pressure",
        "oil_life" to "help_oil_life",
        "atf_temp" to "help_atf_temp",
        "gear" to "help_gear",
        "tc_slip" to "help_tcc_slip",
        "input_rpm" to "help_input_shaft",
        "output_rpm" to "help_output_shaft",
    )

    fun forSource(source: String): String? = bySource[source]

    fun forReading(r: Reading): String? = bySource[r.source] ?: r.role?.let { byRole[it] }

    /**
     * Where the value comes from, for the help: a string resource of the app (help_src_pid, help_src_gm,
     * help_src_ext, help_src_calc, help_src_adapter) and its arguments; an empty name — the text is the first argument.
     */
    fun source(r: Reading): Pair<String, List<String>> = when {
        r.source.startsWith("01.") -> "help_src_pid" to listOf(r.source.substring(3, 5), ecuName(r.ecu))
        r.source.matches(GM_DID) -> "help_src_gm" to listOf(r.source.substring(3), CarDb.family("gm")?.modules?.names?.get(r.ecu) ?: "%03X".format(r.ecu))
        r.source.startsWith("22.") || r.source.startsWith("21.") ->
            "help_src_ext" to listOf(r.source.substring(0, 2), r.source.substring(3).substringBefore('.'), ecuName(r.ecu + 8))
        r.source.startsWith("calc.") -> "help_src_calc" to emptyList()
        r.source == "ATRV" -> "help_src_adapter" to emptyList()
        else -> "" to listOf(r.source)
    }

    private val GM_DID = Regex("22\\.[0-9A-F]{4}")
}
