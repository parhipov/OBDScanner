package com.obdscanner

import com.obdscanner.elm.CanParser
import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.obd.DtcDb
import com.obdscanner.obd.Reading
import com.obdscanner.session.Recorder
import com.obdscanner.session.Store
import com.obdscanner.transport.Transport
import com.obdscanner.util.addTo
import kotlinx.coroutines.channels.Channel

/** Time of a replayed connection: moved by the recorded answer times and by the connection's own waits. */
class VirtualClock(var now: Long = 1_000_000L)

/**
 * The adapter state that decides who answers a request: protocol, header, receive filter, CAN formatting.
 * Tracked the same way over the recorded log and over the replayed commands, so an answer is only taken
 * from the same situation it was recorded in.
 */
class ElmState {
    private var sp = "-"
    private var sh = "-"
    private var cra = "-"
    private var caf = "1"

    fun apply(cmd: String) {
        val c = cmd.uppercase().replace(" ", "")
        when {
            c == "ATZ" || c == "ATD" || c == "ATWS" -> { sp = "-"; sh = "-"; cra = "-"; caf = "1" }
            c.startsWith("ATSP") -> sp = c.removePrefix("ATSP")
            c.startsWith("ATTP") -> sp = c.removePrefix("ATTP")
            c.startsWith("ATSH") -> sh = c.removePrefix("ATSH")
            c.startsWith("ATCRA") -> cra = c.removePrefix("ATCRA").ifEmpty { "-" }
            c == "ATAR" -> cra = "-"
            c.startsWith("ATCF") -> cra = "CF" + c.removePrefix("ATCF")
            c.startsWith("ATCAF") -> caf = c.removePrefix("ATCAF")
        }
    }

    fun key(cmd: String): String {
        val c = cmd.uppercase().replace(" ", "")
        return if (c.startsWith("AT")) c else "$sp|$sh|$cra|$caf|$c"
    }
}

/** A recorded raw.log as request → answers, in the order they were heard. */
class ReplayLog private constructor(
    private val answers: Map<String, List<Pair<String, Long>>>,
    /** Requests recorded with the response-count digit ("21001" = 2100, one answer), by the request without it. */
    private val counted: Map<String, List<Pair<String, Long>>>,
    val requests: Int,
) {
    private val used = HashMap<String, Int>()
    var misses = 0
        private set

    /** The next recorded answer to [key] (the last one again when they run out); null — never asked in this situation. */
    fun answer(key: String): Pair<String, Long>? {
        val (list, tag) = answers[key]?.let { it to key } ?: counted[key]?.let { it to "#$key" } ?: return null.also { misses++ }
        val i = used.addTo(tag, 1) - 1
        return list[minOf(i, list.size - 1)]
    }

    companion object {
        private val LINE = Regex("""^(\d\d):(\d\d):(\d\d)\.(\d{3}) ([<>#]) ?(.*)$""")
        // "\]", not "]": JavaScript's regexes refuse a lone bracket.
        private val TIMEOUT = Regex("""\s+\[TIMEOUT \d+ms\]$""")
        private const val CR = '\r'

        /** A log written by hand in the raw.log format ("12:00:00.000 > 0100", "12:00:00.050 < 7E8 06 41 00 …"). */
        fun parse(text: String): ReplayLog = parse(text.lineSequence())

        fun parse(lines: Sequence<String>): ReplayLog {
            val answers = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
            val counted = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
            val state = ElmState()
            var pending: Pair<String, Long>? = null
            var count = 0
            // Old sessions let the adapter search (ATSP0): what was asked under "0" was asked on the protocol
            // the next ATDPN names — the replayed app sets that one directly.
            val auto = mutableListOf<Triple<String, String, Long>>()
            fun add(key: String, reply: String, ms: Long) {
                answers.getOrPut(key) { mutableListOf() } += reply to ms
                // A request is whole bytes; one more digit is the response count the app adds on scans.
                val req = key.substringAfterLast('|')
                if (!req.startsWith("AT") && req.length % 2 == 1) counted.getOrPut(key.dropLast(1)) { mutableListOf() } += reply to ms
            }
            lines.forEach { line ->
                val m = LINE.find(line) ?: return@forEach
                val (h, mi, s, ms, dir, text) = m.destructured
                val t = ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
                when (dir) {
                    ">" -> {
                        pending = text.trim() to t
                        state.apply(text.trim())
                    }
                    "<" -> {
                        val (cmd, sent) = pending ?: return@forEach
                        pending = null
                        // The log keeps a reply on one line, " | " where the adapter sent a CR.
                        val reply = if (text.startsWith("[monitor:")) "" else
                            text.replace(TIMEOUT, "").split('|').joinToString(CR.toString()) { it.trim() }
                        val key = state.key(cmd)
                        val took = (t - sent).coerceIn(0, 60_000)
                        count++
                        if (key.startsWith("0|")) auto += Triple(key, reply, took) else add(key, reply, took)
                        if (cmd.uppercase().replace(" ", "") == "ATDPN" && auto.isNotEmpty()) {
                            val p = reply.trim().trimStart('A', 'a').take(1)
                            for ((k, r, d) in auto) add(p + k.removePrefix("0"), r, d)
                            auto.clear()
                        }
                    }
                }
            }
            for ((k, r, d) in auto) add(k, r, d)
            return ReplayLog(answers, counted, count)
        }
    }
}

/**
 * An ELM327 that answers from a recorded session: AT commands by the command, requests by the command
 * in the same adapter state. A request never asked in that state gets NO DATA — the car didn't say anything.
 */
class ReplayTransport(private val log: ReplayLog, private val clock: VirtualClock) : Transport {
    override val name = "replay"
    private val queue = Channel<Int>(Channel.UNLIMITED)
    private val cmd = StringBuilder()
    private val state = ElmState()

    override suspend fun read(buf: ByteArray): Int {
        if (buf.isEmpty()) return 0
        val first = queue.receive()
        if (first < 0) return -1
        buf[0] = first.toByte()
        var n = 1
        while (n < buf.size) {
            val next = queue.tryReceive().getOrNull() ?: break
            if (next < 0) {
                queue.trySend(next)
                break
            }
            buf[n++] = next.toByte()
        }
        return n
    }

    override suspend fun write(data: ByteArray) {
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == CR) {
                val line = cmd.toString().trim()
                cmd.clear()
                if (line.isEmpty()) continue
                state.apply(line)
                val hit = log.answer(state.key(line))
                val text = hit?.first ?: if (line.uppercase().startsWith("AT")) "OK" else "NO DATA"
                clock.now += hit?.second ?: 50
                for (ch in text + CR + CR + ">") queue.trySend(ch.code)
            } else if (c != LF) {
                cmd.append(c)
            }
        }
    }

    override suspend fun open() {}

    override fun close() {
        queue.trySend(-1)
    }

    private companion object {
        const val CR = '\r'
        const val LF = '\n'
    }
}

/** One connection played back: what the app sent (requests and AT commands, in order) and where it ended up. */
class Replayed(
    val log: ReplayLog, val requests: List<String>, val link: CarLink, val reports: List<Pair<String, String>>, val error: String?,
    /** User operations that failed, "name: message". */
    val opErrors: List<String> = emptyList(),
) {
    /** Each request with the header it went to ("7E0" after ATSH7E0; "-" before any). */
    val addressed: List<Pair<String, String>> get() {
        var h = "-"
        return requests.mapNotNull { r ->
            val c = r.uppercase().replace(" ", "")
            when {
                c.startsWith("ATSH") -> { h = c.removePrefix("ATSH"); null }
                c.startsWith("AT") -> null
                else -> h to c
            }
        }
    }
}

/**
 * Plays a connection through [CarLink] — connect, discovery, modules, [cycles] poll cycles — against [log];
 * with [ops], then the buttons a user presses (as ObdManager runs them: one at a time, back to broadcast after).
 */
suspend fun replayRun(log: ReplayLog, store: Store, cycles: Int, ops: Boolean = false, pick: com.obdscanner.car.CarChoice? = null): Replayed {
    CanParser.forgetEcus()
    DtcDb.family = null
    val clock = VirtualClock()
    val requests = mutableListOf<String>()
    val elm = Elm327(ReplayTransport(log, clock)) { dir, text -> if (dir == '>') requests += text }
    elm.open()
    val o = Obd(elm)
    val rec = TextRecorder()
    val link = CarLink(store, pickedCar = { pick }, clock = { clock.now }, pause = { clock.now += it })
    link.session = rec
    link.reset(pick?.model)
    var error: String? = null
    val opErrors = mutableListOf<String>()
    run {
        try {
            link.initAdapter(o, "replay")
            o.onAdapterReset = { link.adapterReset(o) }
            link.discover(o)
            if (o.canTarget) link.autoModules(o)
            link.startPolling()
            repeat(cycles) { link.pollCycle(o) }
            if (ops) {
                suspend fun op(name: String, block: suspend () -> Unit) {
                    requests += "# op $name"
                    try {
                        block()
                    } catch (e: Exception) {
                        opErrors += "$name: ${e.message ?: e}"
                    } finally {
                        runCatching { o.broadcast() }
                        link.opFinished()
                    }
                }
                op("refreshDtc") { link.refreshDtc(o) }
                op("refreshMode06") { link.refreshMode06(o) }
                op("rediscover") { link.rediscover(o) }
                op("readAllModulesDtc") { link.readAllModulesDtc(o) }
                op("probeModules") { link.probeModules(o) }
                link.scan.value.modules.firstOrNull()?.let { m -> op("scanModule ${m.id} 21") { link.scanModule(o, m, "21", 0x00..0x0F) } }
                link.watchAll(link.scan.value.hits)
                repeat(3) { link.pollCycle(o) }
                op("clearDtc") { link.clearDtc(o) }
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    elm.close()
    return Replayed(log, requests, link, rec.reports, error, opErrors)
}

class TextRecorder : Recorder {
    val reports = mutableListOf<Pair<String, String>>()
    override fun note(text: String) {}
    override fun report(title: String, body: String) { reports += title to body }
    override fun value(r: Reading) {}
    override fun scanHit(req: Int, resp: Int, service: String, id: String, data: IntArray) {}
    override fun busFrames(window: Int, frames: List<Pair<Int, IntArray>>, windowMs: Long) {}
    override fun flush() {}
}

class MapStore : Store {
    private val m = HashMap<String, Any?>()
    override fun getInt(key: String, default: Int) = m[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { m[key] = value }
    override fun getString(key: String) = m[key] as? String
    override fun putString(key: String, value: String?) { m[key] = value }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String) = m[key] as? Set<String>
    override fun putStringSet(key: String, value: Set<String>) { m[key] = value }
}
