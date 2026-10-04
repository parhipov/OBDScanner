package com.obdscanner.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

actual fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)

actual fun String.formatUs(vararg args: Any?): String = java.lang.String.format(Locale.US, this, *args)

actual fun nowMs(): Long = System.currentTimeMillis()

actual fun clockText(ms: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

actual fun stampText(ms: Long): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ms))

actual fun timeText(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

actual fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

actual fun systemLanguage(): String = Locale.getDefault().language

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual fun startReader(name: String, block: suspend () -> Unit) {
    thread(isDaemon = true, name = name) { runBlocking { block() } }
}

actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = ConcurrentHashMap()

actual typealias IOException = java.io.IOException

actual typealias Synchronized = kotlin.jvm.Synchronized

actual fun roundHalfUp(v: Double): Long = Math.round(v)

actual typealias JSONObject = org.json.JSONObject

actual typealias JSONArray = org.json.JSONArray
