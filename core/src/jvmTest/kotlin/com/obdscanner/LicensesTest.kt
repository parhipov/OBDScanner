package com.obdscanner

import com.obdscanner.screen.Block
import com.obdscanner.screen.Licenses
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Licenses screen: every entry's text is there, the app and the page each list what they ship. */
class LicensesTest {
    private val dir = File("data/licenses")

    @Test
    fun everyLicenseTextExists() {
        Licenses.load(File(dir, "licenses.json").readText())
        for (web in listOf(false, true)) {
            val rows = Licenses.build(web).filterIsInstance<Block.Row>()
            assertTrue("no rows (web=$web)", rows.isNotEmpty())
            for (r in rows) {
                val doc = Licenses.document(r.doc!!)!!
                doc.text?.let { assertTrue("${r.name}: no $it", File(dir, it).isFile) }
                assertTrue("${r.name}: no copyright", doc.lines.size >= 3)
            }
        }
        // What only one of them ships stays out of the other's list.
        val app = Licenses.build(false).filterIsInstance<Block.Row>().map { it.name }
        val web = Licenses.build(true).filterIsInstance<Block.Row>().map { it.name }
        assertTrue("usb-serial-for-android" in app && "usb-serial-for-android" !in web)
        assertTrue("Preact" in web && "Preact" !in app)
        assertTrue("OBDb" in app && "OBDb" in web)
    }
}
