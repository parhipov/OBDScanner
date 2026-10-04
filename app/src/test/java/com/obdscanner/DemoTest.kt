package com.obdscanner

import com.obdscanner.elm.Elm327
import com.obdscanner.elm.Obd
import com.obdscanner.obd.Make
import com.obdscanner.transport.MockTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo mode (a simulated CTS 2.8 behind a simulated ELM327) through the whole connection, as the app runs it. */
class DemoTest {
    @Test
    fun demoCarConnects() {
        CarDbTest.load()
        val elm = Elm327(MockTransport()) { _, _ -> }
        elm.open()
        val o = Obd(elm)
        val link = CarLink(MapStore(), pause = {})
        link.session = TextRecorder()
        link.reset(null)
        runBlocking {
            link.initAdapter(o, "demo")
            link.discover(o)
            link.autoModules(o)
            link.startPolling()
            repeat(3) { link.pollCycle(o) }
        }
        elm.close()
        val v = link.vehicle.value
        assertEquals(Make.GM, v.make)
        assertEquals("CTS", v.car?.model)
        assertTrue(v.ecus.keys.containsAll(listOf(0x7E8, 0x7EA)))
        // GM: modules found on HS-CAN, their codes read with $A9 (the demo BCM has some).
        assertTrue(v.gmDtcs.map { it.module.req }.toString(), v.gmDtcs.any { it.module.req == 0x241 } && v.gmDtcs.any { it.codes.isNotEmpty() })
        // The GM parameters from the database answer and keep their keys.
        assertTrue(v.extActive.map { it.key }.toString(), v.extActive.any { it.key == "7E2:22.1940" })
        val r = link.readings.value
        assertTrue(r.keys.toString(), "7E8:01.0C" in r && "7E2:22.1940" in r)
    }
}
