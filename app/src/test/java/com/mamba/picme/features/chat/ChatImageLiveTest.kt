package com.mamba.picme.features.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatImageLiveTest {
    @Test
    fun `content uri is always live`() {
        assertTrue(chatImageIsLive("content://media/external/images/media/1"))
    }

    @Test
    fun `file uri live when file exists`() {
        val f = File(System.getProperty("java.io.tmpdir"), "polang_live_test_${System.nanoTime()}.jpg")
        f.writeBytes(ByteArray(1))
        try {
            assertTrue(chatImageIsLive("file://${f.absolutePath}"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `file uri dead when file missing`() {
        assertFalse(chatImageIsLive("file:///nope/missing_${System.nanoTime()}.jpg"))
    }

    @Test
    fun `null or blank is dead`() {
        assertFalse(chatImageIsLive(null))
        assertFalse(chatImageIsLive(""))
    }
}
