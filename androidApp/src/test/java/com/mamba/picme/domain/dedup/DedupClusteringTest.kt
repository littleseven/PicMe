package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupClusteringTest {

    private fun hashed(uri: String, phash: Long, captureDate: Long = 0L) = DedupMember(
        uri = uri, sizeBytes = 1_000_000, mime = "image/jpeg",
        captureDate = captureDate, modifiedAt = captureDate,
        pixelArea = 12_000_000, aestheticScore = null,
        role = VersionRole.UNKNOWN, md5 = null, phash = phash,
    )

    private fun typed(
        uri: String,
        phash: Long,
        contentType: DedupContentType,
    ) = hashed(uri, phash).copy(contentType = contentType)

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

    @Test
    fun `visual bucketing applies tightened threshold to SCREENSHOT bucket only`() {
        // 0b1111 与 0 的 hamming 距离为 4：截图桶阈值 3 不成组，普通桶阈值 5 成组
        val items = listOf(
            typed("s1", 0b1111L, DedupContentType.SCREENSHOT),
            typed("s2", 0L, DedupContentType.SCREENSHOT),
            typed("p1", 0b1111L, DedupContentType.GENERAL),
            typed("p2", 0L, DedupContentType.GENERAL),
        )
        val groups = clusterVisualByContentType(
            hashed = items,
            visualThreshold = 5,
            screenshotVisualThreshold = 3,
        )
        // 截图对 hamming=4 > 3 不成组；普通对 hamming=4 ≤ 5 成组
        assertEquals(1, groups.size)
        assertEquals(DedupContentType.GENERAL, groups.single().contentType)
        assertEquals(setOf("p1", "p2"), groups.single().members.map { member -> member.uri }.toSet())
    }

    @Test
    fun `visual bucketing never mixes content types in one group`() {
        // 截图与普通照片 pHash 完全相同（hamming=0）也不进同一组（spec §10.5 跨桶不成组）
        val items = listOf(
            typed("s1", 0b1111L, DedupContentType.SCREENSHOT),
            typed("s2", 0b1111L, DedupContentType.SCREENSHOT),
            typed("p1", 0b1111L, DedupContentType.GENERAL),
            typed("p2", 0b1111L, DedupContentType.GENERAL),
        )
        val groups = clusterVisualByContentType(
            hashed = items,
            visualThreshold = 5,
            screenshotVisualThreshold = 3,
        )
        assertEquals(2, groups.size)
        val byType = groups.associateBy { group -> group.contentType }
        assertEquals(setOf("s1", "s2"), byType.getValue(DedupContentType.SCREENSHOT).members.map { m -> m.uri }.toSet())
        assertEquals(setOf("p1", "p2"), byType.getValue(DedupContentType.GENERAL).members.map { m -> m.uri }.toSet())
    }

    @Test
    fun `autoPreselectedFor follows spec matrix`() {
        assertTrue(autoPreselectedFor(DedupLevel.EXACT, DedupContentType.SCREENSHOT))
        assertTrue(autoPreselectedFor(DedupLevel.EXACT, DedupContentType.DOCUMENT))
        assertTrue(autoPreselectedFor(DedupLevel.VISUAL, DedupContentType.GENERAL))
        assertTrue(autoPreselectedFor(DedupLevel.VISUAL, DedupContentType.PORTRAIT))
        assertFalse(autoPreselectedFor(DedupLevel.VISUAL, DedupContentType.SCREENSHOT))
        assertFalse(autoPreselectedFor(DedupLevel.VISUAL, DedupContentType.DOCUMENT))
        DedupContentType.entries.forEach { type ->
            assertFalse(autoPreselectedFor(DedupLevel.SCENE, type))
        }
    }

    @Test
    fun `built groups carry contentType and autoPreselected`() {
        val screenshotGroups = clusterVisualByContentType(
            hashed = listOf(
                typed("s1", 0b1111L, DedupContentType.SCREENSHOT),
                typed("s2", 0b1110L, DedupContentType.SCREENSHOT),
            ),
            visualThreshold = 5,
            screenshotVisualThreshold = 3,
        )
        assertEquals(1, screenshotGroups.size)
        assertEquals(DedupContentType.SCREENSHOT, screenshotGroups.single().contentType)
        assertFalse(screenshotGroups.single().autoPreselected)

        val generalGroups = clusterVisualByContentType(
            hashed = listOf(hashed("p1", 0b1111L), hashed("p2", 0b1110L)),
            visualThreshold = 5,
            screenshotVisualThreshold = 3,
        )
        assertTrue(generalGroups.single().autoPreselected)
    }

    @Test
    fun `all-GENERAL input is equivalent to v1_0 single-bucket clustering`() {
        // AC-6 锁定：TAG 未覆盖（全 GENERAL）时 clusterVisualByContentType 与
        // v1.0 阈值-5 单桶聚类完全等价（组数、成员集合、keepUri、autoPreselected）
        val items = listOf(
            hashed("a", 0b1111L, captureDate = 1L),
            hashed("b", 0b1110L, captureDate = 2L),
            hashed("c", -1L, captureDate = 3L),
            hashed("d", -2L, captureDate = 4L),
            // 0xF0 与两簇的 hamming 距离均 > 5：孤立项，不成组
            hashed("e", 0xF0L, captureDate = 5L),
        )
        val legacy = clusterVisual(items, threshold = 5, timeWindowMs = null, level = DedupLevel.VISUAL)
        val bucketed = clusterVisualByContentType(
            hashed = items,
            visualThreshold = 5,
            screenshotVisualThreshold = 3,
        )
        assertEquals(2, legacy.size) // 前置断言：fixture 本身应产出两簇
        assertEquals(legacy.size, bucketed.size)
        val legacyById = legacy.associateBy { group -> group.id }
        bucketed.forEach { group ->
            val legacyGroup = legacyById.getValue(group.id)
            assertEquals(
                legacyGroup.members.map { member -> member.uri }.toSet(),
                group.members.map { member -> member.uri }.toSet(),
            )
            assertEquals(legacyGroup.keepUri, group.keepUri)
        }
        assertTrue(bucketed.all { group -> group.contentType == DedupContentType.GENERAL })
        assertTrue(bucketed.all { group -> group.autoPreselected })
    }
}
