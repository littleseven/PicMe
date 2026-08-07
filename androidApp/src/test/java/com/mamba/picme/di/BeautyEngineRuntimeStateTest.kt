package com.mamba.picme.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeautyEngineRuntimeStateTest {

    @Test
    fun markAndConsume_fallbackReason_isOneShot() {
        BeautyEngineRuntimeState.markGlEngineFallback("init failed")

        val first = BeautyEngineRuntimeState.consumeGlEngineFallbackReason()
        val second = BeautyEngineRuntimeState.consumeGlEngineFallbackReason()

        assertEquals("init failed", first)
        assertNull(second)
    }
}

