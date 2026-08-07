package com.mamba.picme.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagSanitizerTest {

    @Test
    fun `redacts email addresses`() {
        assertEquals("user <email> logged in", DiagSanitizer.sanitize("user a@b.com logged in"))
    }

    @Test
    fun `redacts pl app tokens`() {
        assertEquals("auth=<token>", DiagSanitizer.sanitize("auth=pl-0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `redacts absolute media and filesystem paths`() {
        val out = DiagSanitizer.sanitize("saved /storage/emulated/0/DCIM/IMG.jpg and /data/data/com.mamba.picme/x")
        assertTrue("path redacted: $out", !out.contains("/storage/") && !out.contains("/data/data/"))
    }

    @Test
    fun `redacts content uris`() {
        val out = DiagSanitizer.sanitize("loaded content://media/external/images/media/42")
        assertEquals("loaded <path>", out)
    }

    @Test
    fun `redacts gps coordinate pairs`() {
        val out = DiagSanitizer.sanitize("loc=31.23040,121.47370")
        assertEquals("loc=<coord>", out)
    }

    @Test
    fun `leaves clean log text unchanged`() {
        val clean = "PoLang:Camera Preview started at 30fps"
        assertEquals(clean, DiagSanitizer.sanitize(clean))
    }
}
