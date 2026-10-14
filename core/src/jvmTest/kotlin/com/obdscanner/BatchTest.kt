package com.obdscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-PID batch size against a car that answers two PIDs but not six (the Kia Sportage QL's «ELM327 v2.1» of
 * 2026-10-14: 0 of 81 six-PID polls answered). The poll here asks 04 0C 0D 11 05 0F; a request the log doesn't
 * have gets nothing back; 0104 alone is answered unless a test says otherwise.
 */
class BatchTest {
    /** A hand-written raw.log: each (command, reply) as the app logs them. */
    private fun log(vararg pairs: Pair<String, String>) = ReplayLog.parse(pairs.joinToString("\n") { (c, r) -> "12:00:00.000 > $c\n12:00:00.040 < $r" })

    /** Connects and answers the 2-PID check; supports 04 05 0C 0D 0F 11. */
    private val connect = arrayOf(
        "ATZ" to "ELM327 v2.1", "ATSP6" to "OK", "0100" to "7E8 06 41 00 18 1A 80 00 AA", "ATDPN" to "6",
        "ATSH7DF" to "OK", "010C0D" to "7E8 06 41 0C 0C 98 0D 00 AA", "0100" to "7E8 06 41 00 18 1A 80 00 AA",
    )

    /** The first PID of the unanswered batch, asked alone. */
    private val alone = "0104" to "7E8 03 41 04 32 AA AA AA AA"

    /** Three PIDs a request answered: 04 0C 0D over two frames, 11 05 0F in one. */
    private val three = arrayOf(
        "01040C0D" to "7E8 10 08 41 04 32 0C 0C 98 | 7E8 21 0D 00 AA AA AA AA AA",
        "0111050F" to "7E8 07 41 11 20 05 5A 0F 3C",
    )

    private fun rpm(r: Replayed) = r.link.readings.value.values.firstOrNull { it.key.endsWith("01.0C") }?.value

    @Test
    fun sixUnansweredThreeAnswered() {
        CarDbTest.load()
        val r = replay(log(*connect, alone, *three), MapStore(), 30)
        assertEquals(3, r.link.vehicle.value.pidBatch)
        assertEquals(806.0, rpm(r))
        // Two six-PID requests went unanswered, then 04 alone, then threes only.
        val polls = r.requests.dropWhile { it != "01040C0D11050F" }
        assertEquals("0104", polls.filter { it.startsWith("01") }[2], polls.joinToString(" "))
        assertTrue(polls.drop(3).filter { it.startsWith("01") && it != "0100" }.all { it.length <= 8 }, polls.joinToString(" "))
        assertTrue(r.reports.any { it.first == tr("Мульти-PID", "Multi-PID") }, r.reports.joinToString { it.first })
    }

    @Test
    fun threeUnansweredTooOnePidAtATime() {
        CarDbTest.load()
        val r = replay(log(*connect, alone, "010C" to "7E8 04 41 0C 0C 98 AA AA AA"), MapStore(), 40)
        assertEquals(1, r.link.vehicle.value.pidBatch)
        assertEquals(806.0, rpm(r))
    }

    /** The lone PID isn't answered either: the car went silent, not the batch — the size stays. */
    @Test
    fun aSilentCarKeepsTheSize() {
        CarDbTest.load()
        val r = replay(log(*connect), MapStore(), 30)
        assertEquals(6, r.link.vehicle.value.pidBatch)
        assertTrue(r.reports.none { it.first == tr("Мульти-PID", "Multi-PID") })
    }
}
