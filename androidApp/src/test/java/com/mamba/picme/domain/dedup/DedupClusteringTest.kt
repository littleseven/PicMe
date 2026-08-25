package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupClusteringTest {

    private fun hashed(uri: String, phash: Long, captureDate: Long = 0L) = DedupMember(
        uri = uri, sizeBytes = 1_000_000, mime = "image/jpeg",
        captureDate = captureDate, modifiedAt = captureDate,
        pixelArea = 12_000_000, aestheticScore = null,
        role = VersionRole.UNKNOWN, md5 = null, phash = phash,
    )

    @Test
    fun `visual clustering groups hashes within threshold`() {
        // c/d 必须用 -1L 而非 0L：hamming(0b1111, 0) = 4 ≤ threshold 5，
        // 并查集会把 a/b 与 c/d 传递合并成单簇；-1L 与 0b1111 距离 60，两簇才真正隔离。
        val items = listOf(
            hashed("a", 0b1111L), hashed("b", 0b1110L),
            hashed("c", -1L), hashed("d", -1L),
        )
        val groups = clusterVisual(items, threshold = 5, timeWindowMs = null, level = DedupLevel.VISUAL)
        assertEquals(2, groups.size)
        assertTrue(groups.all { group -> group.level == DedupLevel.VISUAL })
    }

    @Test
    fun `scene clustering splits by capture time window`() {
        val items = listOf(
            hashed("a", 0L, captureDate = 0L),
            hashed("b", 0L, captureDate = 5_000L),
            hashed("c", 0L, captureDate = 3_600_000L),
            hashed("d", 0L, captureDate = 3_605_000L),
        )
        val groups = clusterVisual(items, threshold = 8, timeWindowMs = 10_000L, level = DedupLevel.SCENE)
        assertEquals(2, groups.size)
        assertEquals(setOf("a", "b"), groups[0].members.map { member -> member.uri }.toSet())
        assertEquals(setOf("c", "d"), groups[1].members.map { member -> member.uri }.toSet())
    }

    @Test
    fun `group id is stable regardless of member order`() {
        val g1 = DedupGroup.stableId(DedupLevel.EXACT, listOf("u1", "u2", "u3"))
        val g2 = DedupGroup.stableId(DedupLevel.EXACT, listOf("u3", "u1", "u2"))
        assertEquals(g1, g2)
    }

    @Test
    fun `single element clusters produce no groups`() {
        val items = listOf(hashed("a", 0L), hashed("b", -1L))
        assertTrue(clusterVisual(items, threshold = 5, timeWindowMs = null, level = DedupLevel.VISUAL).isEmpty())
    }
}
