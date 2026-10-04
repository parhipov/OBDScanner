package com.obdscanner.transport

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.obdscanner.tr
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * ELM327 over Wi-Fi: the adapter is its own access point and listens on TCP (clones: 192.168.0.10:35000).
 * [candidates] are tried in order ("host:port"); [onConnected] gets the one that answered, to try it first next time.
 * [factory] — sockets bound to the adapter's Wi-Fi network ([WifiNet]): that network has no internet, and
 * Android sends everything else over mobile data.
 */
class WifiTransport(
    private val candidates: List<String>,
    private val factory: SocketFactory = SocketFactory.getDefault(),
    private val log: (String) -> Unit,
    private val onConnected: (String) -> Unit = {},
) : Transport {

    override var name: String = "Wi-Fi"
        private set
    private var socket: Socket? = null
    override lateinit var input: InputStream
    override lateinit var output: OutputStream

    override fun open() {
        var last: Exception? = null
        for (addr in candidates.distinct()) {
            val (host, port) = parse(addr) ?: continue
            var s: Socket? = null
            try {
                log("Wi-Fi: connect to $host:$port")
                s = factory.createSocket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                // One short command at a time: without this a request waits for the next packet.
                s.tcpNoDelay = true
                s.keepAlive = true
                socket = s
                input = s.getInputStream()
                output = s.getOutputStream()
                name = "Wi-Fi $host:$port"
                log("Wi-Fi: connected to $host:$port")
                onConnected("$host:$port")
                return
            } catch (e: Exception) {
                log("Wi-Fi: $host:$port failed: ${e.message}")
                last = e
                runCatching { s?.close() }
            }
        }
        throw IOException(tr("Wi-Fi-адаптер не отвечает. Проверьте, что телефон подключён к сети адаптера.",
            "The Wi-Fi adapter does not answer. Check that the phone is connected to the adapter's network."), last)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        /** Clones listen at 192.168.0.10:35000; the rest seen: .0.123, .1.10, and port 23 on some. */
        val DEFAULTS = listOf("192.168.0.10:35000", "192.168.0.123:35000", "192.168.1.10:35000", "192.168.0.10:23")
        const val DEFAULT_PORT = 35000
        private const val CONNECT_TIMEOUT_MS = 3000

        /** "host", "host:port" → host and port ([DEFAULT_PORT] if none); null if it doesn't read as an address. */
        fun parse(s: String): Pair<String, Int>? {
            val t = s.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            val host = t.substringBefore(':')
            val port = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
            return if (host.isNotEmpty() && port in 1..65535) host to port else null
        }
    }
}

/** The phone's Wi-Fi network: sockets bound to it and its gateway, which is the adapter itself on most clones. */
class WifiNet(val network: Network?, val gateway: String?) {
    val factory: SocketFactory get() = network?.socketFactory ?: SocketFactory.getDefault()

    companion object {
        fun find(cm: ConnectivityManager?): WifiNet {
            cm ?: return WifiNet(null, null)
            @Suppress("DEPRECATION")
            val net = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                ?: return WifiNet(null, null)
            val gw = cm.getLinkProperties(net)?.routes.orEmpty()
                .mapNotNull { it.gateway as? Inet4Address }.firstOrNull { !it.isAnyLocalAddress }?.hostAddress
            return WifiNet(net, gw)
        }
    }
}
