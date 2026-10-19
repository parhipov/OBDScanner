package com.obdscanner

import com.obdscanner.elm.CanParser
import com.obdscanner.elm.ElmReply
import com.obdscanner.util.format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on the JVM and in the browser build alike: J1850 messages as ELM327 shows them with headers on. The check byte
 * is checked against frames an owner logged on a 2002 TrailBlazer / 2005 Yukon (gmtnation.com, "More PIDs for
 * Torque App", page 8).
 */
class J1850ParseTest {
    /** A J1850 message the way the ELM shows it with headers on: the bytes and their CRC. */
    private fun j1850(hex: String): String = hex + " %02X".format(CanParser.j1850Crc(hex.split(' ').map { it.toInt(16) }))

    /** The check byte ELM327 shows on J1850 is SAE J1850's CRC-8, not K-line's sum. */
    @Test
    fun crcOfLoggedFrames() {
        for (f in listOf("6C F1 10 62 19 41 00 00 9A", "6C F1 40 62 21 24 FF CF", "6C F1 1A 7F 22 19 41 11 11",
            "6C F1 10 7F 22 19 41 12 B0", "6C F1 40 62 22 1C 0C 80", "6C F1 40 7F 22 19 41 01 31 07")) {
            val b = f.split(' ').map { it.toInt(16) }
            assertEquals(b.last(), CanParser.j1850Crc(b.dropLast(1)), f)
        }
    }

    /** VPW and PWM messages are the ECU's; a line whose check byte fits neither CRC nor sum is not taken as one. */
    @Test
    fun parsesVpwAndPwm() {
        val r = CanParser.parse(ElmReply("010C", "${j1850("48 6B 10 41 0C 0B B8")}\r${j1850("41 6B 18 41 0C 0B B8")}", false), 3)
        assertEquals(listOf(0x10, 0x18), r.messages.map { it.header })
        assertEquals("41 0C 0B B8", r.messages[0].hex())
        val bad = CanParser.parse(ElmReply("010C", "41 6B 10 41 0C 0B B8 00", false), 3)
        assertTrue(bad.messages.none { it.header == 0x10 })
    }
}
