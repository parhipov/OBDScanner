package com.obdscanner

import com.obdscanner.car.CarChoice
import com.obdscanner.session.Store
import kotlinx.coroutines.runBlocking
import java.io.File

/** The replay harness (commonTest, Replay.kt) for the JVM tests: a recorded raw.log from disk, played synchronously. */
fun ReplayLog.Companion.parse(file: File): ReplayLog = file.useLines(Charsets.UTF_8) { parse(it) }

fun replay(log: ReplayLog, store: Store, cycles: Int, ops: Boolean = false, pick: CarChoice? = null): Replayed =
    runBlocking { replayRun(log, store, cycles, ops, pick) }
