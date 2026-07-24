package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.model.MediaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryQueryFilterTest {

    private fun media(
        id: Long,
        labels: String? = null,
        ocr: String? = null,
        location: String? = null,
        captureDate: Long = 1_000L,
        hasFace: Boolean = false,
    ) = MediaEntity(
        id = id,
        uri = "uri$id",
        type = MediaType.PHOTO,
        captureDate = captureDate,
        fileName = "f$id",
        labels = labels,
        ocrText = ocr,
        locationName = location,
        hasFace = hasFace,
    )

    @Test
    fun `empty filter returns all candidates`() {
        val list = listOf(media(1), media(2))
        assertEquals(listOf(1L, 2L), list.applyFilter(QueryFilter()).map { it.id })
    }

    @Test
    fun `label filter is case-insensitive substring`() {
        val list = listOf(media(1, labels = """["猫","户外"]"""), media(2, labels = """["食物"]"""))
        val got = list.applyFilter(QueryFilter(label = "猫"))
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `time range filter inclusive`() {
        val list = listOf(media(1, captureDate = 500L), media(2, captureDate = 1_500L))
        val got = list.applyFilter(QueryFilter(fromMs = 1_000L, toMs = 2_000L))
        assertEquals(listOf(2L), got.map { it.id })
    }

    @Test
    fun `hasFace filter`() {
        val list = listOf(media(1, hasFace = true), media(2, hasFace = false))
        val got = list.applyFilter(QueryFilter(hasFace = true))
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `multi-dimension AND`() {
        val list = listOf(
            media(1, labels = """["猫"]""", captureDate = 1_500L, hasFace = true),
            media(2, labels = """["猫"]""", captureDate = 1_500L, hasFace = false),
            media(3, labels = """["猫"]""", captureDate = 500L, hasFace = true),
            media(4, labels = """["食物"]""", captureDate = 1_500L, hasFace = true),
        )
        val got = list.applyFilter(QueryFilter(label = "猫", fromMs = 1_000L, hasFace = true))
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test
    fun `no match returns empty`() {
        val list = listOf(media(1, labels = """["猫"]"""))
        assertTrue(list.applyFilter(QueryFilter(label = "不存在")).isEmpty())
    }

    @Test
    fun `null fields do not match substring filters`() {
        val list = listOf(media(1, labels = null))
        assertTrue(list.applyFilter(QueryFilter(label = "猫")).isEmpty())
    }
}
