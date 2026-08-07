package com.mamba.picme.features.gallery.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPassProgressTest {

    @Test
    fun `partial progress computes processed as total minus remaining`() {
        // 100 张，待处理 20 → 已处理 80（不是 withFace=50，修正语义口径）
        val p = tagPassProgress(total = 100, remaining = 20)
        assertEquals(80, p.processed)
        assertEquals(20, p.remaining)
        assertEquals(0.8f, p.fraction, 1e-5f)
        assertFalse(p.isComplete)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `zero remaining with positive total is complete`() {
        val p = tagPassProgress(total = 100, remaining = 0)
        assertEquals(100, p.processed)
        assertEquals(1f, p.fraction, 1e-5f)
        assertTrue(p.isComplete)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `zero total is empty and never complete`() {
        val p = tagPassProgress(total = 0, remaining = 0)
        assertEquals(0, p.processed)
        assertEquals(0f, p.fraction, 1e-5f)
        assertTrue(p.isEmpty)
        assertFalse(p.isComplete)
    }

    @Test
    fun `remaining larger than total is clamped to total`() {
        val p = tagPassProgress(total = 10, remaining = 99)
        assertEquals(0, p.processed)
        assertEquals(10, p.remaining)
        assertEquals(0f, p.fraction, 1e-5f)
        assertFalse(p.isComplete)
    }

    @Test
    fun `negative inputs are clamped to zero`() {
        val p = tagPassProgress(total = -5, remaining = -3)
        assertEquals(0, p.total)
        assertEquals(0, p.remaining)
        assertEquals(0, p.processed)
        assertTrue(p.isEmpty)
    }
}
