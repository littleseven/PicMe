package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGallerySearchTest {

    private fun asset(
        id: Long, labels: String? = null, ocr: String? = null,
        loc: String? = null, name: String = "f$id.jpg", hasFace: Boolean = false
    ) = MediaAsset(
        id = id, uri = "u$id", type = MediaType.PHOTO, captureDate = id * 1000L, fileName = name,
        labels = labels, ocrText = ocr, locationName = loc, hasFace = hasFace
    )

    // ── in-set 过滤 ──────────────────────────────────────────────

    private val set = listOf(
        asset(1, labels = "beach,sea", loc = "三亚"),
        asset(2, labels = "night,city", ocr = "霓虹", loc = "上海"),
        asset(3, labels = "beach,sunset", loc = "海南"),
        asset(4, labels = "mountain")
    )

    @Test
    fun `filter matches labels case-insensitive`() {
        assertEquals(listOf(1L, 3L), ChatGallerySearch.filterInSet(set, "beach").map { it.id })
    }

    @Test
    fun `filter matches ocr and location`() {
        assertEquals(listOf(2L), ChatGallerySearch.filterInSet(set, "霓虹").map { it.id })
        assertEquals(listOf(3L), ChatGallerySearch.filterInSet(set, "海南").map { it.id })
    }

    @Test
    fun `filter no match returns empty`() {
        assertEquals(0, ChatGallerySearch.filterInSet(set, "太空").size)
    }

    @Test
    fun `filter empty constraint returns all`() {
        assertEquals(4, ChatGallerySearch.filterInSet(set, "").size)
    }

    // ── 编解码往返 ───────────────────────────────────────────────

    private val assets = listOf(
        asset(1, name = "a.jpg"),
        asset(2, name = "b.jpg")
    )

    @Test
    fun `serialize then deserialize round-trips assets and metadata`() {
        val content = ChatGallerySearch.serializeContent(assets)
        val metadata = ChatGallerySearch.serializeMetadata(query = "海边", totalCount = 5, isRefinement = true)
        val parsed = ChatGallerySearch.deserialize(content, metadata)

        assertEquals("海边", parsed.query)
        assertEquals(5, parsed.totalCount)
        assertTrue(parsed.isRefinement)
        assertEquals(2, parsed.assets.size)
        assertEquals(1L, parsed.assets[0].id)
        assertEquals("u1", parsed.assets[0].uri)
        assertEquals(MediaType.PHOTO, parsed.assets[1].type)
    }

    @Test
    fun `missing metadata yields empty-query non-refinement result`() {
        val content = ChatGallerySearch.serializeContent(assets)
        val parsed = ChatGallerySearch.deserialize(content, metadata = null)
        assertEquals("", parsed.query)
        assertEquals(false, parsed.isRefinement)
        assertEquals(2, parsed.assets.size)
    }

    // ── 人脸意图：用 hasFace 结构化字段，不依赖标签子串 ─────────

    @Test
    fun `filter matches hasFace when constraint expresses face intent`() {
        val faceSet = listOf(
            asset(1, labels = "beach", hasFace = true),
            asset(2, labels = "beach", hasFace = false),
            asset(3, labels = "sunset", hasFace = true)
        )
        assertEquals(
            listOf(1L, 3L),
            ChatGallerySearch.filterInSet(faceSet, "有人脸").map { a -> a.id }
        )
    }

    @Test
    fun `face intent matches english face keyword`() {
        val faceSet = listOf(
            asset(1, hasFace = true),
            asset(2, hasFace = false)
        )
        assertEquals(
            listOf(1L),
            ChatGallerySearch.filterInSet(faceSet, "face").map { a -> a.id }
        )
    }

    @Test
    fun `filter ignores hasFace when constraint is not face-related`() {
        val mixed = listOf(
            asset(1, labels = "beach", hasFace = true),
            asset(2, labels = "beach", hasFace = false),
            asset(3, labels = "mountain", hasFace = true)
        )
        assertEquals(
            listOf(1L, 2L),
            ChatGallerySearch.filterInSet(mixed, "beach").map { a -> a.id }
        )
    }

    // ── resolveRefine：searchEngine 命中 ∩ prior，空则 filterInSet 兜底 ──

    @Test
    fun `resolveRefine returns intersection when searchHits overlap prior`() {
        val prior = listOf(
            asset(1, labels = "beach"),
            asset(2, labels = "sunset"),
            asset(3, labels = "mountain")
        )
        // searchEngine 命中 2（在 prior 内）和 4（不在 prior 内）
        val searchHits = listOf(asset(2, labels = "sunset"), asset(4, labels = "sunset"))
        val result = ChatGallerySearch.resolveRefine(prior, searchHits, "日落")
        assertEquals(listOf(2L), result.map { a -> a.id })
    }

    @Test
    fun `resolveRefine falls back to filterInSet when intersection empty`() {
        val prior = listOf(asset(1, labels = "beach"), asset(2, labels = "sunset"))
        // searchHits 都不在 prior 内 → 交集空 → 回退 filterInSet(prior, "sunset") 命中 2
        val searchHits = listOf(asset(3, labels = "sunset"))
        val result = ChatGallerySearch.resolveRefine(prior, searchHits, "sunset")
        assertEquals(listOf(2L), result.map { a -> a.id })
    }

    @Test
    fun `resolveRefine returns empty when both intersection and filterInSet miss`() {
        val prior = listOf(asset(1, labels = "beach"))
        val searchHits = listOf(asset(2, labels = "sunset"))
        val result = ChatGallerySearch.resolveRefine(prior, searchHits, "太空")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolveRefine prefers filterInSet hits over searchEngine intersection`() {
        val prior = listOf(asset(1, labels = "sunset"), asset(2, labels = "beach"))
        // searchEngine 交集是 asset(2)，但 filterInSet(prior,"sunset") 命中 asset(1) 应优先
        val searchHits = listOf(asset(2, labels = "beach"))
        val result = ChatGallerySearch.resolveRefine(prior, searchHits, "sunset")
        assertEquals(listOf(1L), result.map { a -> a.id })
    }

    // ── cleanConstraint：去口语词，提取核心词 ─────────────────────

    @Test
    fun `cleanConstraint strips colloquial prefixes and suffixes`() {
        assertEquals("日落", ChatGallerySearch.cleanConstraint("其中的日落"))
    }

    @Test
    fun `cleanConstraint leaves keyword-only constraint unchanged`() {
        assertEquals("有人脸", ChatGallerySearch.cleanConstraint("有人脸"))
        assertEquals("海边", ChatGallerySearch.cleanConstraint("海边"))
    }

    @Test
    fun `cleanConstraint normalizes gender terms to single char for label match`() {
        // 标签体系用单字「女/男」（qwenSummary「一位女士」等），归一后 filterInSet 才能命中
        assertEquals("女", ChatGallerySearch.cleanConstraint("女性"))
        assertEquals("女", ChatGallerySearch.cleanConstraint("只保留女性"))
        assertEquals("女", ChatGallerySearch.cleanConstraint("只要女人的照片"))
        assertEquals("男", ChatGallerySearch.cleanConstraint("男人"))
    }
}
