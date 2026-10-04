package com.obdscanner.transport

import com.obdscanner.util.format

/**
 * Finding the port speed of an ELM327 behind a USB-serial chip — the app's USB adapters and the page's Web Serial
 * ones. The speed the adapter listens at isn't known (clones: 38400, some 9600 or 115200, STN chips 115200 and up):
 * the transport tries [BAUDS] until ATI comes back as text with a prompt ([isElmReply]).
 */
object ElmProbe {
    /** Most common first; python-OBD tries the same set. */
    val BAUDS = listOf(38400, 115200, 9600, 230400, 57600, 19200)

    /** After the CR that ends what the adapter half-heard at the last speed (its "?" is dropped). */
    const val SETTLE_MS = 250L

    /** How long ATI may take to come back at one speed. */
    const val PROBE_MS = 1000L

    /**
     * At the wrong speed the bytes come in as 00/80/F8/FF and the like. Text only, ending in the prompt:
     * that's the adapter.
     */
    fun isElmReply(r: ByteArray): Boolean {
        val text = latin1(r).trimEnd()
        return text.endsWith(">") && text.length > 1 && r.all(::isText)
    }

    fun isText(b: Byte) = b == '\r'.code.toByte() || b == '\n'.code.toByte() || b in 0x20..0x7E

    /** A probe's answer for the log: the text, or the first bytes in hex. */
    fun show(r: ByteArray): String =
        if (r.isEmpty()) "nothing"
        else if (isElmReply(r)) "\"" + latin1(r).replace("\r", "\\r").replace("\n", "\\n") + "\""
        else "${r.size} bytes: " + r.take(16).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } + if (r.size > 16) " …" else ""

    private fun latin1(r: ByteArray) = r.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
}
