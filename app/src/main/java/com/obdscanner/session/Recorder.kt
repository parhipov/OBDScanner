package com.obdscanner.session

import com.obdscanner.obd.Reading

/** Where a connection writes what it finds: the session folder ([Session]), or a replay in the unit tests. */
interface Recorder {
    fun note(text: String)
    fun report(title: String, body: String)
    fun value(r: Reading)
    fun scanHit(req: Int, resp: Int, service: String, id: String, data: IntArray)
    fun busFrames(window: Int, frames: List<Pair<Int, IntArray>>, windowMs: Long)
    fun flush()
}

/** Small persistent settings (SharedPreferences in the app, a map in the unit tests). */
interface Store {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getStringSet(key: String): Set<String>?
    fun putStringSet(key: String, value: Set<String>)
}
