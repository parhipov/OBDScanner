package com.obdscanner.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * Android's org.json rules over kotlinx.serialization: a missing key is the fallback, a number or a boolean
 * reads as its text, a numeric string reads as a number, JSON null reads as "null".
 */

private fun fail(what: String): Nothing = throw IllegalArgumentException("JSON: $what")

/** A literal as Android's JSONTokener reads it: whole numbers stay as written, the rest as a Double. */
private fun JsonPrimitive.text(): String = when {
    isString -> content
    this is JsonNull -> "null"
    content == "true" || content == "false" -> content
    content.any { it == '.' || it == 'e' || it == 'E' } -> content.toDouble().let { d ->
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e7) "${d.toLong()}.0" else d.toString()
    }
    else -> content
}

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { p ->
    if (p.isString) p.content.toDoubleOrNull()?.toInt() else p.content.toLongOrNull()?.toInt() ?: p.content.toDoubleOrNull()?.toInt()
}

private fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()

private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let {
    when {
        it.equals("true", ignoreCase = true) -> true
        it.equals("false", ignoreCase = true) -> false
        else -> null
    }
}

private fun JsonElement?.str(): String? = when (this) {
    null -> null
    is JsonPrimitive -> text()
    else -> toString()
}

actual class JSONObject internal constructor(private val o: JsonObject) {
    actual constructor(json: String) : this(Json.parseToJsonElement(json).jsonObject)

    actual fun has(name: String): Boolean = name in o
    actual fun keys(): Iterator<String> = o.keys.iterator()
    actual fun getString(name: String): String = o[name].str() ?: fail("no $name")
    actual fun getInt(name: String): Int = o[name].int() ?: fail("$name is not a number")
    actual fun getDouble(name: String): Double = o[name].double() ?: fail("$name is not a number")
    actual fun getBoolean(name: String): Boolean = o[name].bool() ?: fail("$name is not a boolean")
    actual fun getJSONObject(name: String): JSONObject = optJSONObject(name) ?: fail("$name is not an object")
    actual fun getJSONArray(name: String): JSONArray = optJSONArray(name) ?: fail("$name is not an array")
    actual fun optString(name: String): String = optString(name, "")
    actual fun optString(name: String, fallback: String): String = o[name].str() ?: fallback
    actual fun optInt(name: String, fallback: Int): Int = o[name].int() ?: fallback
    actual fun optDouble(name: String): Double = optDouble(name, Double.NaN)
    actual fun optDouble(name: String, fallback: Double): Double = o[name].double() ?: fallback
    actual fun optBoolean(name: String, fallback: Boolean): Boolean = o[name].bool() ?: fallback
    actual fun optJSONObject(name: String): JSONObject? = (o[name] as? JsonObject)?.let(::JSONObject)
    actual fun optJSONArray(name: String): JSONArray? = (o[name] as? JsonArray)?.let(::JSONArray)
    override fun toString(): String = o.toString()
}

actual class JSONArray internal constructor(private val a: JsonArray) {
    actual fun length(): Int = a.size
    actual fun getInt(index: Int): Int = a.getOrNull(index).int() ?: fail("[$index] is not a number")
    actual fun getDouble(index: Int): Double = a.getOrNull(index).double() ?: fail("[$index] is not a number")
    actual fun getJSONObject(index: Int): JSONObject = optJSONObject(index) ?: fail("[$index] is not an object")
    actual fun optString(index: Int): String = a.getOrNull(index).str() ?: ""
    actual fun optJSONObject(index: Int): JSONObject? = (a.getOrNull(index) as? JsonObject)?.let(::JSONObject)
    override fun toString(): String = a.toString()
}
