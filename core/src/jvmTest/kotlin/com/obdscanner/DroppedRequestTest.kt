package com.obdscanner

import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opel Mokka 2014, 2026-10-08…10 (3.13–3.17): the ELM327 v1.5 clone hung on the CALID list (0904, 131 bytes) and
 * the Bluetooth link closed; every connection ended there. The next one leaves 0904 out and gets through.
 */
class DroppedRequestTest {
    private val log = ReplayLog.parse(
        """
        07:55:57.000 > ATSP6
        07:55:57.050 < OK
        07:55:58.539 > 0100
        07:55:58.722 < 7E8 06 41 00 BE 3F B8 13
        07:55:58.905 > ATSH7DF
        07:55:58.951 < OK
        07:56:00.270 > 0900
        07:56:00.428 < 7E8 06 49 00 55 40 00 00
        07:56:00.430 > 0902
        07:56:00.591 < 7E8 10 14 49 02 01 57 30 4C  | 7E8 21 4A 43 37 44 35 58 45  | 7E8 22 42 36 37 38 35 33 34
        07:56:00.600 > 090A
        07:56:00.719 < 7E8 10 17 49 0A 01 45 43 4D  | 7E8 21 00 2D 45 6E 67 69 6E  | 7E8 22 65 43 6F 6E 74 72 6F  | 7E8 23 6C 00 00
        """.trimIndent(),
    )

    /** The recorded adapter, which closes the link instead of answering [dropOn]. */
    private class Dropping(log: ReplayLog, private val dropOn: String) : Transport {
        private val inner = ReplayTransport(log, VirtualClock())
        override val name = "dropping"
        override suspend fun open() = inner.open()
        override suspend fun read(buf: ByteArray) = inner.read(buf)
        override suspend fun write(data: ByteArray) {
            if (data.decodeToString().trim().equals(dropOn, ignoreCase = true)) inner.close() else inner.write(data)
        }
        override fun close() = inner.close()
    }

    private fun connect(store: MapStore, adapter: String = "OBDII", after: (CarLink) -> Unit = {}): Pair<List<String>, String?> = runBlocking {
        val sent = mutableListOf<String>()
        val elm = Elm327(Dropping(log, "0904")) { dir, text -> if (dir == '>') sent += text }
        elm.open()
        val o = Obd(elm)
        val link = CarLink(store, pause = {})
        link.session = TextRecorder()
        val error = try {
            link.initAdapter(o, adapter)
            link.discover(o)
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        elm.close()
        after(link)
        sent to error
    }

    @Test
    fun theRequestThatDroppedTheLinkIsSkippedNextTime() {
        val store = MapStore()
        val (first, error) = connect(store)
        assertTrue(first.contains("0904"))
        assertTrue("the first connection ends on 0904: $error", error != null)

        val (second, error2) = connect(store)
        assertFalse("0904 is not sent again", second.contains("0904"))
        assertNull(error2)
        // The rest of Mode 09 is still read.
        assertTrue(second.contains("090A"))
    }

    @Test
    fun resetAsksAgain() {
        val store = MapStore()
        connect(store)
        var memory: CarMemory? = null
        connect(store) { link ->
            assertEquals(setOf("0904"), link.vehicle.value.memory.skipped)
            link.forgetCar()
            memory = link.vehicle.value.memory
        }
        assertTrue("nothing remembered after «Сбросить»: $memory", memory!!.isEmpty)
        val (third, _) = connect(store)
        assertTrue(third.contains("0904"))
    }

    @Test
    fun keptPerAdapterAndCar() {
        val store = MapStore()
        connect(store)
        assertEquals(setOf("0904"), store.getStringSet("dropped_${AppInfo.versionCode}_OBDII_W0LJC7D5XEB678534"))
        // Another adapter on the same car asks again.
        val (other, _) = connect(store, adapter = "Vgate")
        assertTrue(other.contains("0904"))
    }
}
