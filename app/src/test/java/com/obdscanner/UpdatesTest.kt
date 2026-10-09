package com.obdscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun versionFromRedirect() {
        assertEquals("3.16", Updates.tagVersion("https://github.com/parhipov/OBDScanner/releases/tag/v3.16"))
        assertEquals("2.0", Updates.tagVersion("https://github.com/parhipov/OBDScanner/releases/tag/v2.0"))
        assertNull(Updates.tagVersion(null))
        // No release yet — GitHub sends to the list; a login or an error page.
        assertNull(Updates.tagVersion("https://github.com/parhipov/OBDScanner/releases"))
        assertNull(Updates.tagVersion("https://github.com/login"))
        assertNull(Updates.tagVersion("https://github.com/parhipov/OBDScanner/releases/tag/nightly"))
    }

    @Test
    fun newerAsDecimals() {
        assertTrue(Updates.newer("3.17", "3.16"))
        assertFalse(Updates.newer("3.16", "3.16"))
        assertFalse(Updates.newer("3.15", "3.16"))
        assertTrue(Updates.newer("3.10", "3.09"))
        assertTrue(Updates.newer("3.1", "3.09"))
        assertTrue(Updates.newer("4.0", "3.99"))
        assertTrue(Updates.newer("2.0", "1.26"))
        assertFalse(Updates.newer("", "3.16"))
        assertFalse(Updates.newer("3.17", ""))
    }

    @Test
    fun testersBuildIsNotARelease() {
        assertTrue(Updates.isRelease("3.16"))
        assertTrue(Updates.isRelease("2.0"))
        assertFalse(Updates.isRelease("1.25-kyron"))
        assertFalse(Updates.isRelease(""))
    }
}
