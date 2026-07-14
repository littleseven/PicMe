package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGallerySearchTest {

    private fun asset(
        id: Long, labels: String? = null, ocr: String? = null,
        loc: String? = null, name: String = "f$id.jpg"
    ) = MediaAsset(
        id = id, uri = "u$id", type = MediaType.PHOTO, captureDate = id * 1000L, fileName = name,
        labels = labels, ocrText = ocr, locationName = loc
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
}
