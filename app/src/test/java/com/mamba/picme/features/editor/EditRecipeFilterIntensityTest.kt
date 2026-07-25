package com.mamba.picme.features.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditRecipeFilterIntensityTest {

    @Test
    fun `default filter intensity is 1_0`() {
        val recipe = EditRecipe(sourceUri = "file:///test.jpg")
        assertEquals(1.0f, recipe.filterIntensity, 0.001f)
    }

    @Test
    fun `filter intensity can be customized`() {
        val recipe = EditRecipe(sourceUri = "file:///test.jpg", filterIntensity = 0.4f)
        assertEquals(0.4f, recipe.filterIntensity, 0.001f)
    }
}
