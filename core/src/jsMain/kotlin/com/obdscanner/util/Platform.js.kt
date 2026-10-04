package com.obdscanner.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.js.Date

actual fun String.format(vararg args: Any?): String = javaFormat(this, args, if (systemLanguage() == "ru") ',' else '.')

actual fun String.formatUs(vararg args: Any?): String = javaFormat(this, args, '.')

actual fun nowMs(): Long = Date.now().toLong()

private fun two(n: Int) = n.toString().padStart(2, '0')

actual fun clockText(ms: Long): String {
    val d = Date(ms.toDouble())
    return "${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}.${d.getMilliseconds().toString().padStart(3, '0')}"
}

actual fun stampText(ms: Long): String {
    val d = Date(ms.toDouble())
    return "${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}_${two(d.getHours())}-${two(d.getMinutes())}-${two(d.getSeconds())}"
}

actual fun timeText(ms: Long): String {
    val d = Date(ms.toDouble())
    return "${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}"
}

actual fun currentYear(): Int = Date().getFullYear()

actual fun systemLanguage(): String {
    val lang: String = js("typeof navigator !== 'undefined' && navigator.language ? navigator.language : 'en'")
    return lang.substringBefore('-').lowercase()
}

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

actual fun startReader(name: String, block: suspend () -> Unit) {
    CoroutineScope(Dispatchers.Default).launch { block() }
}

actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = HashMap()

actual open class IOException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause) {
    actual constructor(message: String?) : this(message, null)
}

actual fun roundHalfUp(v: Double): Long {
    if (v.isNaN()) return 0L
    val r: Double = js("Math.round(v)")
    return r.toLong()
}
