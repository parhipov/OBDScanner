package com.obdscanner.web

import com.obdscanner.transport.Transport
import com.obdscanner.util.IOException
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.Channel
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * An ELM327 over Bluetooth LE (Web Bluetooth): Vgate iCar Pro BLE, Veepeak, OBDLink CX and the "OBDII" BLE clones.
 * They have no standard profile: a vendor service with a characteristic that notifies what the adapter says and one
 * that takes what is sent (often the same one). Any of [SERVICES] the device has is taken; the page asks the
 * browser for these services when it picks the device.
 */
class WebBluetoothTransport(private val device: dynamic, override val name: String, private val log: (String) -> Unit = {}) : Transport {
    private val rx = Channel<Int>(Channel.UNLIMITED)
    private var tx: dynamic = null
    private var noResponse = false
    private val onValue: (dynamic) -> Unit = { e ->
        val v = e.target.value.unsafeCast<DataView>()
        for (i in 0 until v.byteLength) rx.trySend(v.getUint8(i).toInt())
    }
    private val onGone: (dynamic) -> Unit = { rx.trySend(-1) }

    override suspend fun open() {
        try {
            device.addEventListener("gattserverdisconnected", onGone)
            val server = device.gatt.connect().unsafeCast<Promise<dynamic>>().await()
            val services = server.getPrimaryServices().unsafeCast<Promise<Array<dynamic>>>().await()
            for (s in services) {
                val chars = s.getCharacteristics().unsafeCast<Promise<Array<dynamic>>>().await()
                val notify = chars.firstOrNull { it.properties.notify == true || it.properties.indicate == true } ?: continue
                val write = chars.firstOrNull { it.properties.writeWithoutResponse == true } ?: chars.firstOrNull { it.properties.write == true } ?: continue
                notify.addEventListener("characteristicvaluechanged", onValue)
                notify.startNotifications().unsafeCast<Promise<Any?>>().await()
                tx = write
                noResponse = write.properties.writeWithoutResponse == true
                log("BLE: service ${s.uuid}, notify ${notify.uuid}, write ${write.uuid}" + if (noResponse) " (no response)" else "")
                return
            }
            throw IOException("BLE: no ELM327 service on ${device.name ?: "the device"}")
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException(e.message ?: e.toString(), e)
        }
    }

    override suspend fun read(buf: ByteArray): Int {
        if (buf.isEmpty()) return 0
        val first = rx.receive()
        if (first < 0) return -1
        buf[0] = first.toByte()
        var n = 1
        while (n < buf.size) {
            val next = rx.tryReceive().getOrNull() ?: break
            if (next < 0) {
                rx.trySend(next)
                break
            }
            buf[n++] = next.toByte()
        }
        return n
    }

    /** In pieces of [CHUNK] bytes: what fits a BLE packet without a negotiated larger MTU. */
    override suspend fun write(data: ByteArray) {
        val c = tx ?: throw IOException("BLE: not connected")
        var at = 0
        while (at < data.size) {
            val end = minOf(at + CHUNK, data.size)
            val a = data.unsafeCast<Int8Array>()
            val part = Uint8Array(a.buffer, a.byteOffset + at, end - at).slice()
            try {
                (if (noResponse) c.writeValueWithoutResponse(part) else c.writeValue(part)).unsafeCast<Promise<Any?>>().await()
            } catch (e: Throwable) {
                throw IOException(e.message ?: e.toString(), e)
            }
            at = end
        }
    }

    override fun close() {
        try {
            if (device.gatt.connected == true) device.gatt.disconnect()
        } catch (_: Throwable) {
        }
        rx.trySend(-1)
    }

    companion object {
        private const val CHUNK = 20

        /** The services ELM327 BLE adapters use; the page passes them as optionalServices to requestDevice. */
        val SERVICES = arrayOf(
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000fff0-0000-1000-8000-00805f9b34fb",
            "000018f0-0000-1000-8000-00805f9b34fb",
            "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        )
    }
}

private fun Uint8Array.slice(): Uint8Array = asDynamic().slice().unsafeCast<Uint8Array>()
