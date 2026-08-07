package com.mamba.picme.domain.aesthetic

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSelectorTest {
    @Test
    fun faceQualityDominatesWhenAestheticSimilar() {
        // A 美学更高但人脸质量低；B 人脸质量高 → B 胜（人脸质量为主）
        val candidates = listOf(
            CoverCandidate(mediaId = 1, aestheticScore = 9f, faceQualityScore = 0.3f),
            CoverCandidate(mediaId = 2, aestheticScore = 6f, faceQualityScore = 0.8f)
        )
        assertEquals(2L, CoverSelector.bestCoverMediaId(candidates))
    }

    @Test
    fun combinedScoreFormula() {
        val a = CoverSelector.combinedScore(aestheticScore = 8f, faceQualityScore = 0.4f)
        // aNorm=(8-1)/9≈0.7778；0.6*0.4 + 0.4*0.7778 = 0.24 + 0.3111 ≈ 0.5511
        assertEquals(0.5511f, a!!, 0.001f)
    }

    @Test
    fun onlyFaceQualityWhenAestheticNull() {
        assertEquals(0.7f, CoverSelector.combinedScore(null, 0.7f)!!, 0.0001f)
        assertEquals(
            2L,
            CoverSelector.bestCoverMediaId(
                listOf(
                    CoverCandidate(1, null, 0.2f),
                    CoverCandidate(2, null, 0.7f)
                )
            )
        )
    }

    @Test
    fun onlyAestheticWhenFaceQualityNull() {
        // 仅美学：归一后比大小
        assertEquals(
            2L,
            CoverSelector.bestCoverMediaId(
                listOf(
                    CoverCandidate(1, 5f, null),
                    CoverCandidate(2, 9f, null)
                )
            )
        )
    }

    @Test
    fun returnsNullWhenAllUnscored() {
        val candidates = listOf(
            CoverCandidate(mediaId = 1, aestheticScore = null, faceQualityScore = null),
            CoverCandidate(mediaId = 2, aestheticScore = null, faceQualityScore = null)
        )
        assertEquals(null, CoverSelector.bestCoverMediaId(candidates))
    }
}
