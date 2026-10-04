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
 * Android sends everything else over mobile data. If Android refuses the binding (EPERM under a VPN that
 * doesn't let apps out — a Xiaomi, Android 13), plain sockets are tried. [vpn] — a VPN is on: said in the error.
 */
class WifiTransport(
    private val candidates: List<String>,
    private val factory: SocketFactory = SocketFactory.getDefault(),
    private val vpn: Boolean = false,
    private val log: (String) -> Unit,
    private val onConnected: (String) -> Unit = {},
) : Transport {

    override var name: String = "Wi-Fi"
        private set
    private var socket: Socket? = null
    override lateinit var input: InputStream
    override lateinit var output: OutputStream
    /** [factory] failed to make a socket once — plain sockets from then on. */
    private var plain = false

    override fun open() {
        var last: Exception? = null
        for (addr in candidates.distinct()) {
            val (host, port) = parse(addr) ?: continue
            var s: Socket? = null
            try {
                log("Wi-Fi: connect to $host:$port")
                s = createSocket()
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
            "The Wi-Fi adapter does not answer. Check that the phone is connected to the adapter's network.") +
            if (vpn) tr(" Включён VPN: выключите его или добавьте приложение в исключения VPN.",
                " A VPN is on: turn it off or add the app to the VPN's exclusions.") else "", last)
    }

    private fun createSocket(): Socket {
        if (!plain) {
            try {
                return factory.createSocket()
            } catch (e: IOException) {
                log("Wi-Fi: no socket on the Wi-Fi network (${e.message}) — plain sockets")
                plain = true
            }
        }
        return SocketFactory.getDefault().createSocket()
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

/**
 * The phone's Wi-Fi network: sockets bound to it and its gateway, which is the adapter itself on most clones.
 * [iface] — its interface (wlan0) for the log; [vpn] — a VPN is on.
 */
class WifiNet(val network: Network?, val gateway: String?, val iface: String? = null, val vpn: Boolean = false) {
    val factory: SocketFactory get() = network?.socketFactory ?: SocketFactory.getDefault()

    companion object {
        fun find(cm: ConnectivityManager?): WifiNet {
            cm ?: return WifiNet(null, null)
            @Suppress("DEPRECATION")
            val all = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            val vpn = all.any { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            // A VPN lists the transports of the network under it too (WIFI): that is the tun, not the adapter's network.
            val net = all.firstOrNull { (_, c) ->
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }?.first ?: return WifiNet(null, null, vpn = vpn)
            val lp = cm.getLinkProperties(net)
            val gw = lp?.routes.orEmpty()
                .mapNotNull { it.gateway as? Inet4Address }.firstOrNull { !it.isAnyLocalAddress }?.hostAddress
            return WifiNet(net, gw, lp?.interfaceName, vpn)
        }
    }
}
