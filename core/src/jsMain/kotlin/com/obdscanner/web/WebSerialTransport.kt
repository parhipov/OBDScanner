package com.obdscanner.web

import com.obdscanner.tr
import com.obdscanner.transport.ElmProbe
import com.obdscanner.transport.Transport
import com.obdscanner.util.IOException
import com.obdscanner.util.nowMs
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise
import kotlin.js.json

/**
 * An ELM327 behind a Web Serial port (navigator.serial): a paired Bluetooth adapter (SPP) on Android Chrome,
 * a USB or Bluetooth one on a computer. Bluetooth has no port speed; a USB adapter's is found like the app's
 * USB transport does ([ElmProbe]): [preferredBaud] first (the one that worked last time), [onFound] gets it.
 */
class WebSerialTransport(
    private val port: dynamic,
    override val name: String,
    private val preferredBaud: Int? = null,
    private val log: (String) -> Unit = {},
    private val onFound: (Int) -> Unit = {},
) : Transport {
    private var reader: dynamic = null
    /** A read still waiting for the adapter: a probe timed out on it, the next read takes its bytes. */
    private var pendingRead: Promise<dynamic>? = null
    private var pending: Int8Array? = null
    private var pendingAt = 0

    private val usb: Boolean get() = port.getInfo().usbVendorId != undefined

    override suspend fun open() {
        if (!usb) {
            openAt(DEFAULT_BAUD)
            return
        }
        for (baud in (listOfNotNull(preferredBaud) + ElmProbe.BAUDS).distinct()) {
            openAt(baud)
            val reply = probe()
            log("USB: $baud baud → ${ElmProbe.show(reply)}")
            if (ElmProbe.isElmReply(reply)) {
                log("USB: adapter answers at $baud baud")
                onFound(baud)
                return
            }
            closeNow()
        }
        throw IOException(tr("USB-адаптер не отвечает ни на одной скорости порта", "The USB adapter does not answer at any port speed"))
    }

    private suspend fun openAt(baud: Int) {
        try {
            port.open(json("baudRate" to baud)).unsafeCast<Promise<Any?>>().await()
        } catch (e: Throwable) {
            throw IOException(e.message ?: e.toString(), e)
        }
    }

    /** ATI at the current speed. The CR before it ends whatever the adapter half-heard at the last speed. */
    private suspend fun probe(): ByteArray {
        write("\r".encodeToByteArray())
        readFor(ElmProbe.SETTLE_MS, untilPrompt = false)
        write("ATI\r".encodeToByteArray())
        return readFor(ElmProbe.PROBE_MS, untilPrompt = true)
    }

    private suspend fun readFor(ms: Long, untilPrompt: Boolean): ByteArray {
        val out = mutableListOf<Byte>()
        val end = nowMs() + ms
        val buf = ByteArray(512)
        while (true) {
            val left = end - nowMs()
            if (left <= 0) break
            val n = withTimeoutOrNull(left) { read(buf) } ?: break
            if (n < 0) break
            for (i in 0 until n) out += buf[i]
            if (untilPrompt && n > 0) {
                if (buf[n - 1] == '>'.code.toByte()) break
                // Not text: the wrong speed, no need to wait for the rest.
                if ((0 until n).any { !ElmProbe.isText(buf[it]) }) break
            }
        }
        return out.toByteArray()
    }

    override suspend fun read(buf: ByteArray): Int {
        pending?.let { return take(it, buf) }
        while (true) {
            val readable = port.readable ?: return -1
            if (reader == null) reader = readable.getReader()
            val p = pendingRead ?: reader.read().unsafeCast<Promise<dynamic>>().also { pendingRead = it }
            val r: dynamic = try {
                p.await()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                pendingRead = null
                releaseReader()
                // A framing or overrun error gives the port a fresh stream; a lost link leaves none.
                if (port.readable != null) continue
                throw IOException(e.message ?: e.toString(), e)
            }
            pendingRead = null
            if (r.done == true) {
                releaseReader()
                return -1
            }
            val v = r.value.unsafeCast<Uint8Array>()
            if (v.length == 0) continue
            return take(Int8Array(v.buffer, v.byteOffset, v.length), buf)
        }
    }

    /** Copies what fits into [buf]; the rest waits for the next read. */
    private fun take(src: Int8Array, buf: ByteArray): Int {
        val n = minOf(buf.size, src.length - pendingAt)
        buf.unsafeCast<Int8Array>().set(src.subarray(pendingAt, pendingAt + n), 0)
        if (pendingAt + n < src.length) {
            pending = src
            pendingAt += n
        } else {
            pending = null
            pendingAt = 0
        }
        return n
    }

    private fun releaseReader() {
        val r = reader ?: return
        reader = null
        try {
            r.releaseLock()
        } catch (_: Throwable) {
        }
    }

    override suspend fun write(data: ByteArray) {
        val writable = port.writable ?: throw IOException("port closed")
        val w = writable.getWriter()
        try {
            val a = data.unsafeCast<Int8Array>()
            w.write(Uint8Array(a.buffer, a.byteOffset, a.length)).unsafeCast<Promise<Any?>>().await()
        } catch (e: Throwable) {
            throw IOException(e.message ?: e.toString(), e)
        } finally {
            w.releaseLock()
        }
    }

    /** Between two speeds: the read cancelled, the port closed, so it can open at the next one. */
    private suspend fun closeNow() {
        try {
            reader?.cancel()?.unsafeCast<Promise<Any?>>()?.await()
        } catch (_: Throwable) {
        }
        pendingRead = null
        pending = null
        pendingAt = 0
        releaseReader()
        try {
            port.close().unsafeCast<Promise<Any?>>().await()
        } catch (_: Throwable) {
        }
    }

    /** Cancels the read (the reader loop sees the end of the stream), then closes the port. */
    override fun close() {
        val r = reader
        val cancel: Promise<Any?> = if (r != null) r.cancel().unsafeCast<Promise<Any?>>() else Promise.resolve(null)
        cancel.then({ closePort() }, { closePort() })
    }

    private fun closePort() {
        releaseReader()
        try {
            port.close().unsafeCast<Promise<Any?>>().catch { }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        /** Bluetooth ignores it; Web Serial wants one. */
        const val DEFAULT_BAUD = 38400
    }
}
