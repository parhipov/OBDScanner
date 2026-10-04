package com.obdscanner

import com.obdscanner.car.CarDb
import com.obdscanner.obd.DtcDb
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The browser build plays every recorded session back like ReplayTest does on the JVM, against the same
 * goldens (replay-golden.txt): what the page does with a car is what the phone does, to the byte. Runs in
 * Node (`gradlew :core:jsNodeTest`); without the session archive (not in git) there is nothing to play.
 */
class ReplayJsTest {
    @Test
    fun sessionsPlayBackAsOnTheJvm() = runTest(timeout = 20.minutes) {
        val fs: dynamic = js("import('node:fs')").unsafeCast<Promise<dynamic>>().await()
        val root = repoRoot(fs) ?: return@runTest
        val sessions = "$root/sessions"
        if (!(fs.existsSync(sessions) as Boolean)) {
            println("no session archive at $sessions")
            return@runTest
        }
        fun texts(dir: String): List<String> = (fs.readdirSync(dir) as Array<String>).filter { it.endsWith(".json") }.sorted()
            .map { fs.readFileSync("$dir/$it", "utf8") as String }
        CarDb.load(texts("$root/core/data/cars") + texts("$root/core/data/cars/obdb"))
        DtcDb.load(texts("$root/core/data/dtc"))
        assertTrue(CarDb.problems.isEmpty(), "car database: ${CarDb.problems}")

        val logs = mutableListOf<String>()
        fun walk(dir: String) {
            for (e in (fs.readdirSync(dir) as Array<String>).sorted()) {
                val p = "$dir/$e"
                if ((fs.statSync(p).isDirectory() as Boolean)) walk(p) else if (e == "raw.log") logs += dir
            }
        }
        walk(sessions)
        val failed = mutableListOf<String>()
        var played = 0
        for (dir in logs.sorted()) {
            val name = dir.removePrefix("$sessions/")
            val raw = fs.readFileSync("$dir/raw.log", "utf8") as String
            if (ReplayLog.parse(raw).requests == 0) continue
            played++
            val out = try {
                ReplayText.of(raw, name)
            } catch (e: Throwable) {
                "REPLAY CRASHED: ${e.stackTraceToString()}"
            }
            val outDir = "$root/core/build/replay-js/${name.substringBeforeLast('/')}"
            fs.mkdirSync(outDir, js("({recursive: true})"))
            fs.writeFileSync("$root/core/build/replay-js/$name.txt", out)
            val goldenFile = "$dir/${ReplayText.GOLDEN}"
            val golden = if (fs.existsSync(goldenFile) as Boolean) fs.readFileSync(goldenFile, "utf8") as String else null
            when {
                golden == null -> failed += "$name: no ${ReplayText.GOLDEN}"
                golden != out -> failed += "$name:\n" + ReplayText.diff(golden, out)
            }
            println("replay (js) $name: ${if (golden == out) "same" else "DIFFERENT"}")
        }
        assertTrue(failed.isEmpty(), "sessions the browser build plays back differently (${failed.size} of $played):\n\n" + failed.joinToString("\n\n"))
    }

    /** The checkout: up from Node's working directory (core/build/js/packages/…) to settings.gradle.kts. */
    private fun repoRoot(fs: dynamic): String? {
        var dir = (js("process.cwd()") as String).replace('\\', '/')
        repeat(10) {
            if (fs.existsSync("$dir/settings.gradle.kts") as Boolean) return dir
            dir = dir.substringBeforeLast('/', "")
            if (dir.isEmpty()) return null
        }
        return null
    }
}
