package com.obdscanner

import com.obdscanner.elm.Elm327
import com.obdscanner.transport.MockTransport
import com.obdscanner.transport.WifiTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** A Wi-Fi clone: the demo ELM327 behind a TCP port on localhost. */
private class FakeWifiAdapter : AutoCloseable {
    private val server = ServerSocket(0)
    val address = "127.0.0.1:${server.localPort}"
    private val elm = MockTransport()

    init {
        thread(isDaemon = true) {
            val s = runCatching { server.accept() }.getOrNull() ?: return@thread
            elm.open()
            thread(isDaemon = true) {
                val buf = ByteArray(256)
                while (true) {
                    val n = runCatching { elm.input.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    runCatching { s.getOutputStream().write(buf, 0, n) }.onFailure { return@thread }
                }
            }
            val buf = ByteArray(256)
            while (true) {
                val n = runCatching { s.getInputStream().read(buf) }.getOrDefault(-1)
                if (n < 0) break
                elm.output.write(buf, 0, n)
                elm.output.flush()
            }
            runCatching { s.close() }
        }
    }

    override fun close() {
        server.close()
        elm.close()
    }
}

class WifiTransportTest {
    /** A port nothing listens on: connect is refused at once. */
    private fun deadAddress(): String = ServerSocket(0).use { "127.0.0.1:${it.localPort}" }

    @Test
    fun goesPastDeadAddressesToTheAdapter() = FakeWifiAdapter().use { a ->
        var found: String? = null
        val t = WifiTransport(listOf("not an address", deadAddress(), a.address), log = { println(it) }) { found = it }
        val elm = Elm327(t) { d, x -> println("$d $x") }
        elm.open()
        assertEquals(a.address, found)
        assertEquals("Wi-Fi ${a.address}", t.name)
        runBlocking {
            assertTrue(elm.send("ATZ").lines.any { it.startsWith("ELM327") })
            assertTrue(elm.send("0100").lines.any { "41 00" in it })
        }
        elm.close()
    }

    @Test
    fun nobodyAnswersIsAnError() {
        try {
            WifiTransport(listOf(deadAddress()), log = { println(it) }).open()
            fail("connected to nobody")
        } catch (_: IOException) {
        }
    }

    /** Android refused to bind the socket to the Wi-Fi network (EPERM under a VPN): plain sockets still reach it. */
    @Test
    fun bindingRefusedFallsBackToPlainSocket() = FakeWifiAdapter().use { a ->
        val refusing = object : SocketFactory() {
            override fun createSocket(): Socket = throw SocketException("Binding socket to network 164 failed: EPERM")
            override fun createSocket(host: String?, port: Int) = createSocket()
            override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int) = createSocket()
            override fun createSocket(host: InetAddress?, port: Int) = createSocket()
            override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int) = createSocket()
        }
        val t = WifiTransport(listOf(a.address), refusing, log = { println(it) })
        t.open()
        assertEquals("Wi-Fi ${a.address}", t.name)
        t.close()
    }

    @Test
    fun vpnIsNamedInTheError() {
        try {
            WifiTransport(listOf(deadAddress()), vpn = true, log = { println(it) }).open()
            fail("connected to nobody")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("VPN"))
        }
    }

    @Test
    fun closeEndsTheBlockedRead()= FakeWifiAdapter().use { a ->
        val t = WifiTransport(listOf(a.address), log = { println(it) })
        t.open()
        var got = 0
        val reader = thread { got = runCatching { t.input.read(ByteArray(512)) }.getOrDefault(-1) }
        Thread.sleep(200)
        assertTrue(reader.isAlive)
        t.close()
        reader.join(2000)
        assertFalse(reader.isAlive)
        assertEquals(-1, got)
    }

    @Test
    fun parsesAddresses() {
        assertEquals("192.168.0.10" to 35000, WifiTransport.parse("192.168.0.10"))
        assertEquals("192.168.0.10" to 23, WifiTransport.parse(" 192.168.0.10:23 "))
        assertNull(WifiTransport.parse(""))
        assertNull(WifiTransport.parse("192.168.0.10:"))
        assertNull(WifiTransport.parse("192.168.0.10:99999"))
        assertNull(WifiTransport.parse("192.168 .0.10"))
    }
}
