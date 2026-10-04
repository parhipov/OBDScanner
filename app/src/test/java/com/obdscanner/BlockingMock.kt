package com.obdscanner

import com.obdscanner.transport.MockTransport
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

/** The demo ELM327 with blocking streams: the adapter the fakes put behind a USB chip or a TCP port. */
class BlockingMock {
    private val elm = MockTransport()

    fun open() = runBlocking { elm.open() }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val tmp = ByteArray(len)
            val n = runBlocking { elm.read(tmp) }
            if (n > 0) tmp.copyInto(b, off, 0, n)
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            runBlocking { elm.write(b.copyOfRange(off, off + len)) }
        }
    }

    fun close() = elm.close()
}
