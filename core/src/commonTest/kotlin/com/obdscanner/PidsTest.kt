package com.obdscanner

import com.obdscanner.obd.Pids
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs on the JVM and in the browser build alike: a byte the reply doesn't have is no value on either. */
class PidsTest {
    /** The CTS ECM answers PIDs 56/58 with one byte (bank 1/2 only): no bank 3/4 output. */
    @Test
    fun shortTrimReplyHasNoSecondBank() {
        val r = Pids.decode(0x7E8, "01", 0x56, intArrayOf(0x80))
        assertEquals(listOf("A"), r.map { it.key.substringAfterLast('.') })
        assertEquals(0.0, r.single().value)
    }
}
