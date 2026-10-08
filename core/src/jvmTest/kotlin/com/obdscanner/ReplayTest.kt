package com.obdscanner

import com.obdscanner.obd.DtcDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every recorded session in the archive (sessions/<Model>/#N/<session>/raw.log, not in this repository: VINs) is played
 * back through [CarLink] — connect, discovery, modules, a few poll cycles — and what the app did is
 * compared with the golden file next to the session (replay-golden.txt): every request it sent, the
 * report, the vehicle summary and the values. A change in any of them fails the test with a diff.
 * The text is [ReplayText] (commonTest): the browser build is checked against the same goldens (ReplayJsTest).
 *
 * Environment: REPLAY_RECORD=1 writes the golden files from the current code; REPLAY_GOLDEN=<file name>
 * compares with (or records) another set — replay-main.txt is what main does, recorded in a checkout of
 * main with this test, so the branch's differences from main show up as a diff; REPLAY_ONLY=<text> plays
 * only the sessions whose path contains it; REPLAY_SESSIONS=<dir> — another archive. What the current
 * code did always goes to core/build/replay/ for diffing by hand. Folders starting with "_" are skipped:
 * sessions/_new/ is what came by mail and hasn't been looked at yet (no golden file).
 */
class ReplayTest {
    private val root = File(System.getenv("REPLAY_SESSIONS") ?: "../sessions")
    private val record = System.getenv("REPLAY_RECORD") == "1"
    private val only = System.getenv("REPLAY_ONLY")
    private val goldenName = System.getenv("REPLAY_GOLDEN") ?: ReplayText.GOLDEN

    @Test
    fun sessionsPlayBackAsRecorded() {
        assumeTrue("no session archive at ${root.absolutePath}", root.isDirectory)
        CarDbTest.load()
        loadDtcDb()
        val logs = root.walkTopDown().onEnter { it == root || !it.name.startsWith("_") }.filter { it.name == "raw.log" }.filter { only == null || it.path.contains(only) }
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
            val out = runCatching { runBlocking { ReplayText.of(log.readText(), name) } }
                .getOrElse { "REPLAY CRASHED: ${it.stackTraceToString()}" }
            File("build/replay/$name.txt").apply { parentFile.mkdirs() }.writeText(out)
            val golden = File(log.parentFile, goldenName)
            when {
                record -> golden.writeText(out)
                !golden.exists() -> failed += "$name: no $goldenName (REPLAY_RECORD=1 writes it)"
                golden.readText() != out -> failed += "$name:\n" + ReplayText.diff(golden.readText(), out)
            }
            println("replay $name: ${if (record) "recorded" else if (golden.exists() && golden.readText() == out) "same" else "DIFFERENT"}")
        }
        assertTrue("sessions that play back differently (${failed.size} of ${logs.size}):\n\n" + failed.joinToString("\n\n"), failed.isEmpty())
    }

    private fun loadDtcDb() {
        if (DtcDb.size > 0) return
        val files = File("data/dtc").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        DtcDb.load(files.map { it.readText() })
    }
}
