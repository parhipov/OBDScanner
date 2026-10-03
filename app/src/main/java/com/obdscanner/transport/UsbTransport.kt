package com.obdscanner.transport

import com.obdscanner.tr
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What [UsbTransport] needs from a USB-serial chip ([UsbSerialLink]); the tests put an emulator behind it. */
interface SerialLink {
    val name: String
    fun open()
    fun setBaud(baud: Int)
    /** Up to [buf].size bytes, 0 if nothing came in [timeoutMs]. [timeoutMs] = 0: waits for data, throws on [close]. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
    fun write(data: ByteArray)
    fun close()
}

/**
 * ELM327 behind a USB-serial chip. The port speed the adapter listens at isn't known (clones: 38400, some
 * 9600 or 115200, STN chips 115200 and up): [open] tries them until ATI comes back as text with a prompt.
 * [preferredBaud] — the one that worked last time with this adapter, tried first; [onConnected] gets the one found now.
 */
class UsbTransport(
    private val link: SerialLink,
    private val preferredBaud: Int? = null,
    private val log: (String) -> Unit,
    private val onConnected: (Int) -> Unit = {},
) : Transport {

    override val name: String get() = link.name
    @Volatile private var closed = false

    override fun open() {
        link.open()
        try {
            for (baud in (listOfNotNull(preferredBaud) + BAUDS).distinct()) {
                if (closed) throw IOException(tr("Отключено", "Disconnected"))
                link.setBaud(baud)
                val reply = probe()
                log("USB: $baud baud → ${show(reply)}")
                if (isElmReply(reply)) {
                    log("USB: adapter answers at $baud baud")
                    onConnected(baud)
                    return
                }
            }
        } catch (e: Exception) {
            runCatching { link.close() }
            throw e
        }
        runCatching { link.close() }
        throw IOException(tr("USB-адаптер не отвечает ни на одной скорости порта", "The USB adapter does not answer at any port speed"))
    }

    /** ATI at the current speed. The CR before it ends whatever the adapter half-heard at the last speed; its "?" is dropped. */
    private fun probe(): ByteArray {
        link.write("\r".toByteArray(Charsets.US_ASCII))
        readFor(SETTLE_MS)
        link.write("ATI\r".toByteArray(Charsets.US_ASCII))
        return readFor(PROBE_MS, untilPrompt = true)
    }

    private fun readFor(ms: Long, untilPrompt: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(512)
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            // Short bulk timeouts lose data on some chips; the probe is a few bytes, but keep it at 200 ms and up.
            val n = link.read(buf, READ_TIMEOUT_MS)
            if (n > 0) out.write(buf, 0, n)
            if (untilPrompt && n > 0) {
                if (buf[n - 1] == '>'.code.toByte()) break
                // Not text: the wrong speed, no need to wait for the rest.
                if ((0 until n).any { !isText(buf[it]) }) break
            }
        }
        return out.toByteArray()
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val buf = if (off == 0 && len == b.size) b else ByteArray(len)
            while (true) {
                if (closed) return -1
                val n = try {
                    link.read(buf, 0)
                } catch (e: IOException) {
                    if (closed) return -1
                    throw e
                }
                if (n > 0) {
                    if (buf !== b) System.arraycopy(buf, 0, b, off, n)
                    return n
                }
            }
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException(tr("Адаптер отключён", "Adapter disconnected"))
            link.write(b.copyOfRange(off, off + len))
        }
    }

    override fun close() {
        closed = true
        runCatching { link.close() }
    }

    companion object {
        /** Most common first; python-OBD tries the same set. */
        val BAUDS = listOf(38400, 115200, 9600, 230400, 57600, 19200)
        private const val SETTLE_MS = 250L
        private const val PROBE_MS = 1000L
        private const val READ_TIMEOUT_MS = 200

        /**
         * At the wrong speed the bytes come in as 00/80/F8/FF and the like. Text only, ending in the prompt:
         * that's the adapter.
         */
        fun isElmReply(r: ByteArray): Boolean {
            val text = String(r, Charsets.ISO_8859_1).trimEnd()
            return text.endsWith(">") && text.length > 1 && r.all(::isText)
        }

        private fun isText(b: Byte) = b == '\r'.code.toByte() || b == '\n'.code.toByte() || b in 0x20..0x7E

        private fun show(r: ByteArray): String =
            if (r.isEmpty()) "nothing"
            else if (isElmReply(r)) "\"" + String(r, Charsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n") + "\""
            else "${r.size} bytes: " + r.take(16).joinToString(" ") { "%02X".format(it) } + if (r.size > 16) " …" else ""
    }
}
