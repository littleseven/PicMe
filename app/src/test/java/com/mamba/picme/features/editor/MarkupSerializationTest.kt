package com.mamba.picme.features.editor

import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupSerializationTest {

    private val repo = PhotoEditRecipeRepository(mockk<PhotoEditRecipeDao>(relaxed = true))

    @Test
    fun `empty markup round-trips to empty list`() {
        val original = EditRecipe(sourceUri = "file://a.jpg")
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")
        assertTrue(parsed.markup.isEmpty())
    }

    @Test
    fun `doodle round-trips points color and stroke width`() {
        val points = listOf(NormPoint(0.1f, 0.2f), NormPoint(0.5f, 0.6f), NormPoint(0.9f, 0.8f))
        val original = EditRecipe(
            sourceUri = "file://a.jpg",
            markup = listOf(
                MarkupAction.Doodle(
                    id = "d1",
                    points = points,
                    color = 0xFFFF3B30.toInt(),
                    strokeWidth = 0.02f
                )
            )
        )
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")

        val doodle = parsed.markup.single() as MarkupAction.Doodle
        assertEquals("d1", doodle.id)
        assertEquals(points, doodle.points)
        assertEquals(0xFFFF3B30.toInt(), doodle.color)
        assertEquals(0.02f, doodle.strokeWidth, 0.0001f)
    }

    @Test
    fun `mosaic round-trips mode and points`() {
        val original = EditRecipe(
            sourceUri = "file://a.jpg",
            markup = listOf(
                MarkupAction.Mosaic(
                    id = "m1",
                    points = listOf(NormPoint(0.3f, 0.3f), NormPoint(0.7f, 0.7f)),
                    strokeWidth = 0.05f,
                    mode = MosaicMode.BLUR
                )
            )
        )
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")

        val mosaic = parsed.markup.single() as MarkupAction.Mosaic
        assertEquals("m1", mosaic.id)
        assertEquals(MosaicMode.BLUR, mosaic.mode)
        assertEquals(2, mosaic.points.size)
        assertEquals(0.05f, mosaic.strokeWidth, 0.0001f)
    }

    @Test
    fun `text round-trips content position color and size`() {
        val original = EditRecipe(
            sourceUri = "file://a.jpg",
            markup = listOf(
                MarkupAction.Text(
                    id = "t1",
                    text = "你好 PoLang",
                    position = NormPoint(0.25f, 0.75f),
                    color = 0xFFFFFFFF.toInt(),
                    size = 0.05f
                )
            )
        )
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")

        val text = parsed.markup.single() as MarkupAction.Text
        assertEquals("t1", text.id)
        assertEquals("你好 PoLang", text.text)
        assertEquals(NormPoint(0.25f, 0.75f), text.position)
        assertEquals(0xFFFFFFFF.toInt(), text.color)
        assertEquals(0.05f, text.size, 0.0001f)
    }

    @Test
    fun `multiple actions keep order after round-trip`() {
        val original = EditRecipe(
            sourceUri = "file://a.jpg",
            markup = listOf(
                MarkupAction.Mosaic(id = "m1", points = listOf(NormPoint(0.1f, 0.1f)), strokeWidth = 0.04f),
                MarkupAction.Doodle(
                    id = "d1",
                    points = listOf(NormPoint(0.2f, 0.2f), NormPoint(0.4f, 0.4f)),
                    color = 0xFF000000.toInt(),
                    strokeWidth = 0.01f
                ),
                MarkupAction.Text(
                    id = "t1",
                    text = "note",
                    position = NormPoint(0.5f, 0.5f),
                    color = 0xFFFF0000.toInt(),
                    size = 0.05f
                )
            )
        )
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")

        assertEquals(listOf("m1", "d1", "t1"), parsed.markup.map { it.id })
        assertTrue(parsed.markup[0] is MarkupAction.Mosaic)
        assertTrue(parsed.markup[1] is MarkupAction.Doodle)
        assertTrue(parsed.markup[2] is MarkupAction.Text)
    }

    @Test
    fun `malformed markup entry is skipped instead of dropping all`() {
        val json = """
            {
              "version": 2,
              "sourceUri": "file://a.jpg",
              "crop": {},
              "adjustments": {},
              "beauty": {},
              "colorFilter": "NONE",
              "styleFilter": "NONE",
              "markup": [
                {"type": "unknown", "id": "x1"},
                {"type": "doodle", "id": "d1", "points": [[0.1, 0.2]], "color": -65536, "strokeWidth": 0.02}
              ]
            }
        """.trimIndent()
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")

        val doodle = parsed.markup.single() as MarkupAction.Doodle
        assertEquals("d1", doodle.id)
        assertEquals(listOf(NormPoint(0.1f, 0.2f)), doodle.points)
    }
}
