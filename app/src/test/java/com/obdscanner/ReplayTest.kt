package com.obdscanner

import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.obd.DtcDb
import com.obdscanner.obd.Reading
import com.obdscanner.session.Store
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every recorded session in the archive (sessions/<car>/<session>/raw.log, not in git: VINs) is played
 * back through [CarLink] — connect, discovery, modules, a few poll cycles — and what the app did is
 * compared with the golden file next to the session (replay-golden.txt): every request it sent, the
 * report, the vehicle summary and the values. A change in any of them fails the test with a diff.
 *
 * Environment: REPLAY_RECORD=1 writes the golden files from the current code; REPLAY_GOLDEN=<file name>
 * compares with (or records) another set — replay-main.txt is what main does, recorded in a checkout of
 * main with this test, so the branch's differences from main show up as a diff; REPLAY_ONLY=<text> plays
 * only the sessions whose path contains it; REPLAY_SESSIONS=<dir> — another archive. What the current
 * code did always goes to app/build/replay/ for diffing by hand.
 */
class ReplayTest {
    private val root = File(System.getenv("REPLAY_SESSIONS") ?: "../sessions")
    private val record = System.getenv("REPLAY_RECORD") == "1"
    private val only = System.getenv("REPLAY_ONLY")
    private val goldenName = System.getenv("REPLAY_GOLDEN") ?: GOLDEN

    @Test
    fun sessionsPlayBackAsRecorded() {
        assumeTrue("no session archive at ${root.absolutePath}", root.isDirectory)
        CarDbTest.load()
        loadDtcDb()
        val logs = root.walkTopDown().filter { it.name == "raw.log" }.filter { only == null || it.path.contains(only) }
            .sortedBy { it.path }.toList()
        assumeTrue("no raw.log under ${root.absolutePath}", logs.isNotEmpty())
        val failed = mutableListOf<String>()
        for (log in logs) {
            val name = log.parentFile.relativeTo(root).path.replace('\\', '/')
            // The adapter never answered (Wi-Fi refused, Bluetooth failed): nothing to play back.
            if (ReplayLog.parse(log).requests == 0) {
                println("replay $name: skipped, no exchange with the adapter")
                continue
            }
            // First connection with nothing remembered, then a reconnect with what the first one saved
            // (protocol, the manufacturer requests that answered, the modules found) — both search orders.
            val store = MapStore()
            // And a first connection with the make picked by hand, as its owner would pick it.
            val pick = pickFor(name)
            val out = runCatching {
                play(log, store) + "\n\n######## reconnect: the protocol, parameters and modules remembered; then the buttons\n\n" + play(log, store, ops = true) +
                    (pick?.let { "\n\n######## new connection, the make picked by hand: ${it.brand}\n\n" + play(log, MapStore(), pick = it) } ?: "")
            }.getOrElse { "REPLAY CRASHED: ${it.stackTraceToString()}" }
            File("build/replay/$name.txt").apply { parentFile.mkdirs() }.writeText(out)
            val golden = File(log.parentFile, goldenName)
            when {
                record -> golden.writeText(out)
                !golden.exists() -> failed += "$name: no $goldenName (REPLAY_RECORD=1 writes it)"
                golden.readText() != out -> failed += "$name:\n" + diff(golden.readText(), out)
            }
            println("replay $name: ${if (record) "recorded" else if (golden.exists() && golden.readText() == out) "same" else "DIFFERENT"}")
        }
        assertTrue("sessions that play back differently (${failed.size} of ${logs.size}):\n\n" + failed.joinToString("\n\n"), failed.isEmpty())
    }

    /** One session: what the app sends and finds when the car answers as recorded. */
    /** The make the car's owner would pick: by the session folder's name ("Cadillac CTS 2", "Polo"). */
    private fun pickFor(name: String): CarChoice? {
        val car = name.substringBefore('/')
        val brand = CarDb.brandNames.firstOrNull { car.startsWith("$it ", ignoreCase = true) || car.equals(it, ignoreCase = true) }
            ?: MODEL_FOLDERS[car.substringBefore(' ')]
        return brand?.let { CarDb.choice(CarChoice.BRAND + it) }
    }

    private fun play(log: File, store: Store, ops: Boolean = false, pick: CarChoice? = null): String {
        val r = replay(ReplayLog.parse(log), store, POLL_CYCLES, ops, pick)
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
            for ((h, e) in v.ecus.toSortedMap()) {
                appendLine("ecu %03X: pids01 %s; mode09 %s; mids06 %s; readiness %d".format(h,
                    e.pids01.sorted().joinToString(" ") { "%02X".format(it) },
                    e.info09.keys.sorted().joinToString(" ") { "%02X".format(it) },
                    e.mids06.sorted().joinToString(" ") { "%02X".format(it) }, e.readiness.size))
            }
            appendLine("dtcs: " + v.dtcs.joinToString { "${it.kind} ${it.code} %03X".format(it.ecu) } + if (v.dtcNoAnswer) " (no answer)" else "")
            appendLine("freeze: ${v.freezeDtc} " + v.freeze.joinToString { "${it.source}=${it.display()}" })
            appendLine("mode06: " + v.mode06.size)
            appendLine("ext active: " + v.extActive.joinToString(" ") { it.key })
            for (r in v.gmDtcs) appendLine("module %03X %s: %s %s".format(r.module.req, r.module.name, r.result, r.codes.joinToString(" ") { it.full }))
            appendLine("module status: ${v.gmDtcStatus}")
            appendLine("modules: " + link.scan.value.modules.joinToString { "%03X→%03X %s".format(it.req, it.resp, it.name) })
            appendLine("scan hits: " + link.scan.value.hits.joinToString(" ") { it.key })
            appendLine("\n## readings")
            for (r in link.readings.value.values.sortedBy { it.key }) appendLine(line(r))
        }
    }

    private fun line(r: Reading) = "${r.key}  ${r.name} = ${r.display()} ${r.unit}".trimEnd() + (r.role?.let { "  [$it]" } ?: "")

    private fun loadDtcDb() {
        if (DtcDb.size > 0) return
        val files = File("src/main/assets/dtc").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        DtcDb.load(files.map { it.readText() })
    }

    /** The first lines that differ, with a little context: enough to see what changed. */
    private fun diff(old: String, new: String): String {
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

    companion object {
        const val GOLDEN = "replay-golden.txt"
        /** Enough for every PID to come round at least once (the slow ones are due every 30 s). */
        const val POLL_CYCLES = 40
        /** Session folders named by the model only. */
        private val MODEL_FOLDERS = mapOf("Polo" to "Volkswagen", "RAV4" to "Toyota")
    }
}
