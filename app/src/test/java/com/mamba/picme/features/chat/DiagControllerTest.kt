package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagControllerTest {

    private lateinit var c: DiagController

    @Before
    fun setUp() {
        c = DiagController()
    }

    @Test
    fun `requestConfirm exposes pending`() {
        c.requestConfirm(7, "NPE GalleryScreen") {}
        val p = c.pending.value
        assertEquals(7, p?.jobId)
        assertEquals("NPE GalleryScreen", p?.rootCause)
    }

    @Test
    fun `resolve with mode clears and callbacks`() {
        var received: String? = "<none>"
        c.requestConfirm(1, "rc") { received = it }
        c.resolve("pr")
        assertNull(c.pending.value)
        assertEquals("pr", received)
    }

    @Test
    fun `resolve null cancels`() {
        var received: String? = "<none>"
        c.requestConfirm(1, "rc") { received = it }
        c.resolve(null)
        assertNull(c.pending.value)
        assertEquals(null, received)
    }

    @Test
    fun `resolve with no pending is no-op`() {
        c.resolve("push") // 不抛
        assertNull(c.pending.value)
    }

    @Test
    fun `clear drops pending without callback`() {
        var called = false
        c.requestConfirm(1, "rc") { called = true }
        c.clear()
        assertNull(c.pending.value)
        assertTrue(!called)
    }
}
