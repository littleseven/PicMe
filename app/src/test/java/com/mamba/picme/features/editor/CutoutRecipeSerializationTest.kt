package com.mamba.picme.features.editor

import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.matting.MaskSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CutoutRecipeSerializationTest {

    private val repo = PhotoEditRecipeRepository(mockk<PhotoEditRecipeDao>(relaxed = true))

    @Test
    fun `recipe without cutout round-trips to null cutout`() {
        val original = EditRecipe(sourceUri = "file://a.jpg")
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")
        assertNull(parsed.cutout)
    }

    @Test
    fun `recipe with transparent cutout round-trips`() {
        val original = EditRecipe(
            sourceUri = "file://a.jpg",
            cutout = CutoutRecipe(
                maskSource = MaskSource.U2NETP,
                threshold = 0.5f,
                bgMode = CutoutRecipe.BgMode.TRANSPARENT
            )
        )
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")
        val cutout = parsed.cutout
        assertNotNull(cutout)
        assertEquals(MaskSource.U2NETP, cutout!!.maskSource)
        assertEquals(0.5f, cutout.threshold, 0.0001f)
        assertEquals(CutoutRecipe.BgMode.TRANSPARENT, cutout.bgMode)
        assertNull(cutout.bgColor)
    }

    @Test
    fun `recipe with color cutout round-trips bg color`() {
        val red = 0xFFFF0000.toInt()
        val original = EditRecipe(
            sourceUri = "file://a.jpg",
            cutout = CutoutRecipe(
                bgMode = CutoutRecipe.BgMode.COLOR,
                bgColor = red
            )
        )
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")
        assertEquals(red, parsed.cutout?.bgColor)
    }
}
