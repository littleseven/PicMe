package com.mamba.picme.features.editor

import android.graphics.Bitmap
import com.mamba.picme.beauty.api.PhotoProcessor
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test

class RecipeApplierTest {

    private val processor = mockk<PhotoProcessor>(relaxed = true)
    private val applier = RecipeApplier(processor)

    @Test
    fun `applyMarkup with empty actions returns same bitmap`() {
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = applier.applyMarkup(bitmap, emptyList())
        assertSame(bitmap, result)
    }
}
