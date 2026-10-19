package com.obdscanner.obd

/**
 * Which values of one role to show when a car answers with several: the app's own checked value, OBDb's variants
 * (often the same bytes under two names — a layout copied from model to model), a request that answers but stays at 0.
 *  - A value that never left 0 while another of the role did is dead and is left out (on a Trailblazer the CTS's
 *    22.1470 answers 0 all along, the pressure is in 22.2345). All at 0 — nothing is: the engine hasn't run yet.
 *  - Values of one request (the same ECU and DID) with the same min and max are one value: the checked one is kept,
 *    else the one with the shorter name (22.2345 came as «Давление масла ДВС» and «Давление масла ДВС, факт.»).
 *  - The checked ones first, then the shorter name — a qualifier makes it longer («Давление масла ДВС» before
 *    «Давление масла ДВС 2» and «Расчётное давление масла ДВС»), then the key.
 * Main shows the first; the report charts them all and judges the first when it is the checked value with limits.
 */
object RoleValues {
    /** [key] — the reading key "7E0:22.2345.BLAZER_EOP"; [min] / [max] — what the value spanned (0 when it never came). */
    class Value(val key: String, val name: String, val curated: Boolean, val min: Double, val max: Double) {
        /** The request behind the value — ECU, service and DID: "7E0:22.2345". */
        val request: String get() = key.split('.').take(2).joinToString(".")
        val dead: Boolean get() = min == 0.0 && max == 0.0
    }

    fun order(values: List<Value>): List<Value> {
        val alive = values.filter { !it.dead }.ifEmpty { values }
        return alive.sortedWith(compareByDescending<Value> { it.curated }.thenBy { it.name.length }.thenBy { it.key })
            .distinctBy { Triple(it.request, it.min, it.max) }
    }
}
