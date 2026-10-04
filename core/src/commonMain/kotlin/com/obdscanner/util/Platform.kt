package com.obdscanner.util

import kotlinx.coroutines.CoroutineDispatcher

/*
 * What the logic needs from the platform. On the JVM (the Android app, the unit tests) every one of these is
 * exactly the call the code made before it moved here, so the app behaves the same; jsMain has the browser's.
 */

/** java.lang.String.format in the default locale on the JVM (a Russian phone writes "0,5"); in the browser the subset the app uses. */
expect fun String.format(vararg args: Any?): String

/** The same, always with a dot ("%.1f" → "13.8"): what an adapter prints. */
expect fun String.formatUs(vararg args: Any?): String

/** Wall clock, ms. */
expect fun nowMs(): Long

/** Local "HH:mm:ss.SSS" of [ms] (raw.log). */
expect fun clockText(ms: Long): String

/** Local "yyyy-MM-dd_HH-mm-ss" of [ms] (session names). */
expect fun stampText(ms: Long): String

/** Local "HH:mm:ss" of [ms], for "read at …" on the screens. */
expect fun timeText(ms: Long): String

/** The current year, local calendar. */
expect fun currentYear(): Int

/** System language, ISO 639 ("ru", "en"). */
expect fun systemLanguage(): String

/** Blocking adapter I/O on the JVM, the event loop in the browser. */
expect val ioDispatcher: CoroutineDispatcher

/**
 * Runs the adapter's reader loop: a thread of its own on the JVM — its reads block, and an answer it hands on
 * must reach the waiting command at once, not from a coroutine worker's queue (~15 ms a command) — a
 * coroutine in the browser.
 */
expect fun startReader(name: String, block: suspend () -> Unit)

/** A map several threads write to (ConcurrentHashMap on the JVM). */
expect fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V>

/** kotlin.jvm.Synchronized on the JVM (the phone writes a session from several threads); nothing in the browser. */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
expect annotation class Synchronized()

/** java.io.IOException on the JVM: the Android transports throw it, the connection catches it. */
expect open class IOException : Exception {
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}

/** Math.round: halves up, NaN → 0. */
expect fun roundHalfUp(v: Double): Long

/** Map.merge for a counter: adds [value] to the key's count and returns the new count. */
fun <K> MutableMap<K, Int>.addTo(key: K, value: Int): Int = ((this[key] ?: 0) + value).also { this[key] = it }

/** Map.putIfAbsent: puts when the key is missing or holds null. */
fun <K, V> MutableMap<K, V>.putIfMissing(key: K, value: V) {
    if (this[key] == null) this[key] = value
}
