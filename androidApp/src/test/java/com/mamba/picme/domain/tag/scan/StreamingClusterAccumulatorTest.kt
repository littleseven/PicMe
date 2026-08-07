package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.ClusteringConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingClusterAccumulatorTest {
    @Test
    fun doesNotTriggerBeforeBatchSize() {
        val acc = StreamingClusterAccumulator(batchSize = 20)
        repeat(19) { idx -> assertFalse("call #$idx", acc.onFacePhoto()) }
    }

    @Test
    fun triggersExactlyAtBatchSizeAndResets() {
        val acc = StreamingClusterAccumulator(batchSize = 20)
        repeat(19) { acc.onFacePhoto() }
        assertTrue("20th call triggers", acc.onFacePhoto())
        // reset 后需再攒 20 张才再次触发
        repeat(19) { idx -> assertFalse("after reset #$idx", acc.onFacePhoto()) }
        assertTrue("40th call triggers", acc.onFacePhoto())
    }

    @Test
    fun manualResetClearsPending() {
        val acc = StreamingClusterAccumulator(batchSize = 20)
        repeat(10) { acc.onFacePhoto() }
        acc.reset()
        repeat(19) { idx -> assertFalse("post-reset #$idx", acc.onFacePhoto()) }
        assertTrue(acc.onFacePhoto())
    }

    @Test
    fun defaultBatchSizeMatchesConfig() {
        val acc = StreamingClusterAccumulator()
        repeat(ClusteringConfig.STREAMING_CLUSTER_BATCH - 1) { acc.onFacePhoto() }
        assertTrue(acc.onFacePhoto())
    }
}
