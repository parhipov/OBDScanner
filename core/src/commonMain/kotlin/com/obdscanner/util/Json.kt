package com.obdscanner.util

/*
 * org.json, as far as the databases use it (CarDb, DtcDb). The JVM build is org.json itself — Android's own on
 * the phone — so the databases parse exactly as before; the browser has a copy with the same rules.
 */

expect class JSONObject(json: String) {
    fun has(name: String): Boolean
    fun keys(): Iterator<String>
    fun getString(name: String): String
    fun getInt(name: String): Int
    fun getDouble(name: String): Double
    fun getBoolean(name: String): Boolean
    fun getJSONObject(name: String): JSONObject
    fun getJSONArray(name: String): JSONArray
    fun optString(name: String): String
    fun optString(name: String, fallback: String): String
    fun optInt(name: String, fallback: Int): Int
    fun optDouble(name: String): Double
    fun optDouble(name: String, fallback: Double): Double
    fun optBoolean(name: String, fallback: Boolean): Boolean
    fun optJSONObject(name: String): JSONObject?
    fun optJSONArray(name: String): JSONArray?
}

expect class JSONArray {
    fun length(): Int
    fun getInt(index: Int): Int
    fun getDouble(index: Int): Double
    fun getJSONObject(index: Int): JSONObject
    fun optString(index: Int): String
    fun optJSONObject(index: Int): JSONObject?
}
