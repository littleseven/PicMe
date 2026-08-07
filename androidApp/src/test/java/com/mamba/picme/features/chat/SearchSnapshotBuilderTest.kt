package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchSnapshotBuilderTest {

    @Test
    fun `build extracts tags and caps at 3`() {
        val asset = MediaAsset(
            id = 1L,
            uri = "uri://1",
            type = MediaType.PHOTO,
            captureDate = 0L,
            fileName = "img_001.jpg",
            labels = """{"tags":["海","日落","沙滩","人物"]}"""
        )
        val snapshot = SearchSnapshotBuilder.build(
            results = listOf(asset),
            query = "海边的照片",
            totalCount = 1,
            isRefinement = false
        )
        assertEquals("海边的照片", snapshot.query)
        assertEquals(1, snapshot.results.size)
        assertEquals("1", snapshot.results[0].mediaId)
        assertEquals(listOf("海", "日落", "沙滩"), snapshot.results[0].tags)
    }

    @Test
    fun `build handles missing labels`() {
        val asset = MediaAsset(
            id = 2L,
            uri = "uri://2",
            type = MediaType.PHOTO,
            captureDate = 0L,
            fileName = "img_002.jpg",
            labels = null
        )
        val snapshot = SearchSnapshotBuilder.build(
            results = listOf(asset),
            query = "",
            totalCount = 0,
            isRefinement = true
        )
        assertEquals(true, snapshot.isRefinement)
        assertEquals(emptyList<String>(), snapshot.results[0].tags)
    }
}
