package com.obdscanner.transport

import java.io.InputStream
import java.io.OutputStream

/**
 * A [Transport] over blocking streams: the phone's Bluetooth socket, USB serial and TCP, the replay in the
 * tests. The calls block — [com.obdscanner.elm.Elm327] makes them on Dispatchers.IO, as it always did.
 */
abstract class StreamTransport : Transport {
    abstract val input: InputStream
    abstract val output: OutputStream

    /** Connects, blocking. */
    abstract fun connect()

    override suspend fun open() = connect()

    override suspend fun read(buf: ByteArray): Int = input.read(buf)

    override suspend fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }
}
