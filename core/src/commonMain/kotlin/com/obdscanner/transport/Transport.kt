package com.obdscanner.transport

/**
 * Byte pipe to an ELM327 adapter: Bluetooth, USB or Wi-Fi on the phone ([StreamTransport] there), Web Serial
 * in the browser. [com.obdscanner.elm.Elm327] calls [open], [read] and [write] on [com.obdscanner.util.ioDispatcher].
 */
interface Transport {
    val name: String

    /** Connects; may take seconds (a Bluetooth connect, the Wi-Fi address search). */
    suspend fun open()

    /** Waits for what the adapter sends and puts it into [buf]; -1 — the link is closed. */
    suspend fun read(buf: ByteArray): Int

    /** Sends [data] and flushes it. */
    suspend fun write(data: ByteArray)

    fun close()
}
