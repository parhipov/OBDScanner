package com.obdscanner.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.obdscanner.tr
import java.io.IOException

/** A plugged-in USB device with a serial driver: what Connect lists. [id] stays the same across replugs. */
class UsbAdapter(val id: String, val chip: String, val product: String?, val device: UsbDevice, internal val driver: UsbSerialDriver)

object UsbAdapters {
    private const val PREFIX = "usb:"

    /** Only devices the library has a driver for: flash drives, keyboards and hubs stay out. */
    fun list(um: UsbManager?): List<UsbAdapter> =
        um?.let { runCatching { UsbSerialProber.getDefaultProber().findAllDrivers(it) }.getOrNull() }.orEmpty()
            .filter { it.ports.isNotEmpty() }
            .map { d ->
                UsbAdapter(PREFIX + "%04X:%04X".format(d.device.vendorId, d.device.productId), chip(d),
                    runCatching { d.device.productName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }, d.device, d)
            }
            // Two of the same chip would share the id; one is enough to pick from.
            .distinctBy { it.id }

    private fun chip(d: UsbSerialDriver) = when (d) {
        is Ch34xSerialDriver -> "CH340"
        is FtdiSerialDriver -> "FTDI"
        is Cp21xxSerialDriver -> "CP210x"
        is ProlificSerialDriver -> "PL2303"
        is CdcAcmSerialDriver -> "CDC"
        else -> d.javaClass.simpleName.removeSuffix("SerialDriver")
    }
}

/** [SerialLink] over usb-serial-for-android. */
class UsbSerialLink(private val um: UsbManager, private val a: UsbAdapter) : SerialLink {
    override val name: String = "USB · ${a.chip}"
    private val port: UsbSerialPort = a.driver.ports.first()

    override fun open() {
        val c = um.openDevice(a.device) ?: throw IOException(tr("Нет доступа к USB-адаптеру", "No access to the USB adapter"))
        try {
            port.open(c)
        } catch (e: Exception) {
            runCatching { c.close() }
            throw e
        }
        // A serial terminal raises both on open; some adapters stay silent without DTR.
        runCatching { port.setDTR(true); port.setRTS(true) }
    }

    override fun setBaud(baud: Int) = port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    override fun read(buf: ByteArray, timeoutMs: Int): Int = port.read(buf, timeoutMs)

    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun close() {
        runCatching { if (port.isOpen) port.close() }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
