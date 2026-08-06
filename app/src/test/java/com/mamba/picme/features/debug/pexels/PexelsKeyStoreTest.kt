package com.mamba.picme.features.debug.pexels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PexelsKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanUp() {
        PexelsKeyStore(context).clear()
    }

    @Test
    fun `no key saved returns null`() {
        assertNull(PexelsKeyStore(context).getKey())
    }

    @Test
    fun `saved key is readable and trimmed`() {
        val store = PexelsKeyStore(context)
        store.saveKey("  abc123  ")
        assertEquals("abc123", PexelsKeyStore(context).getKey())
    }

    @Test
    fun `blank key is treated as absent`() {
        PexelsKeyStore(context).saveKey("   ")
        assertNull(PexelsKeyStore(context).getKey())
    }

    @Test
    fun `clear removes key`() {
        val store = PexelsKeyStore(context)
        store.saveKey("abc123")
        store.clear()
        assertNull(store.getKey())
    }
}
