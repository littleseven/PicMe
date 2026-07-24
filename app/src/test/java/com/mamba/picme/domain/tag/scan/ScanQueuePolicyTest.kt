package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanQueuePolicyTest {

    @Test
    fun `default policy runs pass1 and pass2 in first phase`() {
        val policy = ScanQueuePolicy()
        assertEquals(
            listOf(TagScanPass.FACE_DETECTION, TagScanPass.DBSCAN),
            policy.passes
        )
    }

    @Test
    fun `default policy defers pass3 to second phase`() {
        val policy = ScanQueuePolicy()
        assertEquals(
            listOf(TagScanPass.QWEN_TAGGING),
            policy.deferredPasses
        )
    }

    @Test
    fun `conservative preset inherits two-phase defaults`() {
        val policy = ScanQueuePolicy.conservative()
        assertEquals(listOf(TagScanPass.QWEN_TAGGING), policy.deferredPasses)
    }

    @Test
    fun `overnight preset inherits two-phase defaults`() {
        val policy = ScanQueuePolicy.overnight()
        assertEquals(listOf(TagScanPass.QWEN_TAGGING), policy.deferredPasses)
    }
}
