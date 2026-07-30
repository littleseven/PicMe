package com.mamba.picme.domain.aesthetic

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSelectorTest {
    @Test
    fun picksHighestScoreMember() {
        val candidates = listOf(
            CoverCandidate(mediaId = 1, score = 6.0f),
            CoverCandidate(mediaId = 2, score = 8.5f),
            CoverCandidate(mediaId = 3, score = 7.0f)
        )
        assertEquals(2L, CoverSelector.bestCoverMediaId(candidates))
    }

    @Test
    fun returnsNullWhenAllUnscored() {
        val candidates = listOf(
            CoverCandidate(mediaId = 1, score = null),
            CoverCandidate(mediaId = 2, score = null)
        )
        assertEquals(null, CoverSelector.bestCoverMediaId(candidates))
    }

    @Test
    fun ignoresNullScores() {
        val candidates = listOf(
            CoverCandidate(mediaId = 1, score = null),
            CoverCandidate(mediaId = 2, score = 5.0f)
        )
        assertEquals(2L, CoverSelector.bestCoverMediaId(candidates))
    }
}
