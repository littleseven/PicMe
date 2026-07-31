package com.mamba.picme.core.diag

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashTraceStoreTest {

    @Test
    fun `save then read returns the stack trace and delete clears it`() {
        val dir = Files.createTempDirectory("crash-test").toFile()
        CrashTraceStore.save(dir, RuntimeException("boom at GalleryScreen"))

        val trace = CrashTraceStore.read(dir)
        assertTrue("trace persisted: $trace", trace!!.contains("boom at GalleryScreen"))

        CrashTraceStore.delete(dir)
        assertNull(CrashTraceStore.read(dir))
    }

    @Test
    fun `read returns null when no crash file exists`() {
        val dir = Files.createTempDirectory("crash-test-empty").toFile()
        assertNull(CrashTraceStore.read(dir))
    }
}
