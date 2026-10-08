package com.obdscanner

import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.obd.Reading
import com.obdscanner.session.Store
import com.obdscanner.util.format

/**
 * One recorded session as the golden file has it (replay-golden.txt next to the session): a first connection,
 * a reconnect with what the first one saved and the buttons, a connection with the make picked by hand. The same
 * text on the JVM (ReplayTest) and in the browser build (ReplayJsTest), so both are checked against one golden.
 */
object ReplayText {
    const val GOLDEN = "replay-golden.txt"

    /** Enough for every PID to come round at least once (the slow ones are due every 30 s). */
    const val POLL_CYCLES = 40

    /** [name] — "Cadillac CTS 2/#1/2026-09-25_19-07-12", the session's path under the archive. */
    suspend fun of(rawLog: String, name: String): String {
        val log = { ReplayLog.parse(rawLog) }
        // First connection with nothing remembered, then a reconnect with what the first one saved
        // (protocol, the manufacturer requests that answered, the modules found) — both search orders.
        val store = MapStore()
        // And a first connection with the make picked by hand, as its owner would pick it.
        val pick = pickFor(name)
        return play(log(), store) + "\n\n######## reconnect: the protocol, parameters and modules remembered; then the buttons\n\n" + play(log(), store, ops = true) +
            (pick?.let { "\n\n######## new connection, the make picked by hand: ${it.brand}\n\n" + play(log(), MapStore(), pick = it) } ?: "")
    }

    /** The make the car's owner would pick: by the archive's model folder ("Cadillac CTS 2", "Volkswagen Polo 9N"). */
    fun pickFor(name: String): CarChoice? {
        val car = name.substringBefore('/')
        val brand = CarDb.brandNames.firstOrNull { car.startsWith("$it ", ignoreCase = true) || car.equals(it, ignoreCase = true) }
        return brand?.let { CarDb.choice(CarChoice.BRAND + it) }
    }

    /** One session: what the app sends and finds when the car answers as recorded. */
    private suspend fun play(log: ReplayLog, store: Store, ops: Boolean = false, pick: CarChoice? = null): String {
        val r = replayRun(log, store, POLL_CYCLES, ops, pick)
        val replay = r.log
        val requests = r.requests
        val link = r.link
        val error = r.error
        val rec = r
        val v = link.vehicle.value
        return buildString {
            appendLine("# replay: ${replay.requests} recorded exchanges, ${requests.size} sent, ${replay.misses} never recorded" +
                ", language ${if (L10n.ru) "ru" else "en"}")
            error?.let { appendLine("# ERROR: $it") }
            r.opErrors.forEach { appendLine("# OP FAILED: $it") }
            appendLine("\n## requests")
            requests.forEach { appendLine(it) }
            appendLine("\n## report")
            rec.reports.forEach { (title, body) ->
                appendLine("=== $title ===")
                // The app version is in the adapter section: not a difference in behaviour.
                appendLine(body.lines().filterNot { it.startsWith("Приложение: ") || it.startsWith("App: ") }.joinToString("\n"))
            }
            appendLine("\n## vehicle")
            appendLine("protocol: ${v.protocol}, multi-PID: ${v.multiPid}, K-line: ${v.kline}")
            appendLine("vin: ${v.vin}, brand: ${v.brand}, make: ${v.make}, car: ${v.car?.title}")
            for ((h, e) in v.ecus.entries.sortedBy { it.key }) {
                appendLine("ecu %03X: pids01 %s; mode09 %s; mids06 %s; readiness %d".format(h,
                    e.pids01.sorted().joinToString(" ") { "%02X".format(it) },
                    e.info09.keys.sorted().joinToString(" ") { "%02X".format(it) },
                    e.mids06.sorted().joinToString(" ") { "%02X".format(it) }, e.readiness.size))
            }
            appendLine("dtcs: " + v.dtcs.joinToString { "${it.kind} ${it.code} %03X".format(it.ecu) } + if (v.dtcNoAnswer) " (no answer)" else "")
            appendLine("freeze: ${v.freezeDtc} " + v.freeze.joinToString { "${it.source}=${it.display()}" })
            appendLine("mode06: " + v.mode06.size)
            appendLine("ext active: " + v.extActive.joinToString(" ") { it.key })
            for (m in v.gmDtcs) appendLine("module %03X %s: %s %s".format(m.module.req, m.module.name, m.result, m.codes.joinToString(" ") { it.full }))
            appendLine("module status: ${v.gmDtcStatus}")
            appendLine("modules: " + link.scan.value.modules.joinToString { "%03X→%03X %s".format(it.req, it.resp, it.name) })
            appendLine("scan hits: " + link.scan.value.hits.joinToString(" ") { it.key })
            appendLine("\n## readings")
            for (x in link.readings.value.values.sortedBy { it.key }) appendLine(line(x))
        }
    }

    private fun line(r: Reading) = "${r.key}  ${r.name} = ${r.display()} ${r.unit}".trimEnd() + (r.role?.let { "  [$it]" } ?: "")

    /** The first lines that differ, with a little context: enough to see what changed. */
    fun diff(old: String, new: String): String {
        val a = old.lines()
        val b = new.lines()
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        var ea = a.size - 1
        var eb = b.size - 1
        while (ea > i && eb > i && a[ea] == b[eb]) { ea--; eb-- }
        val from = maxOf(0, i - 3)
        val ctx = a.subList(from, i).map { "  $it" }
        val removed = a.subList(i, minOf(ea + 1, i + 30)).map { "- $it" } + if (ea + 1 - i > 30) listOf("- … ${ea + 1 - i - 30} more") else emptyList()
        val added = b.subList(i, minOf(eb + 1, i + 30)).map { "+ $it" } + if (eb + 1 - i > 30) listOf("+ … ${eb + 1 - i - 30} more") else emptyList()
        return "  @ line ${i + 1}\n" + (ctx + removed + added).joinToString("\n")
    }
}
