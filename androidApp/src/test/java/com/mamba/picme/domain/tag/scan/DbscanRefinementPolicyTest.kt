package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.ClusteringConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DbscanRefinementPolicyTest {
    @Test
    fun runsOnFinalBatchRegardlessOfCount() {
        assertTrue(DbscanRefinementPolicy.shouldRunRefinement(0, isFinalBatch = true))
        assertTrue(DbscanRefinementPolicy.shouldRunRefinement(5, isFinalBatch = true))
    }

    @Test
    fun doesNotRunBeforeThresholdMidScan() {
        val under = ClusteringConfig.RE_CLUSTER_THRESHOLD - 1
        assertFalse(DbscanRefinementPolicy.shouldRunRefinement(under, isFinalBatch = false))
    }

    @Test
    fun runsAtThresholdMidScan() {
        assertTrue(
            DbscanRefinementPolicy.shouldRunRefinement(
                ClusteringConfig.RE_CLUSTER_THRESHOLD,
                isFinalBatch = false
            )
        )
    }
}
