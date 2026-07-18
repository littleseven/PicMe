package com.mamba.picme.features.editor

import android.graphics.Bitmap
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MattingResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeApplierCutoutTest {

    private val processor = mockk<PhotoProcessor>(relaxed = true)

    @Test
    fun `applyCutout with null cutout returns same bitmap`() = runBlocking {
        val engine = mockk<MattingEngine>(relaxed = true)
        val applier = RecipeApplier(processor, mattingEngine = engine)
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = applier.applyCutout(src, cutout = null)
        assertTrue(src === out)
    }

    @Test
    fun `applyCutout transparent removes background where mask is zero`() = runBlocking {
        // 全 0 alpha（背景）-> 抠图后像素全透明
        val engine = object : MattingEngine {
            override suspend fun removeBackground(bitmap: Bitmap): MattingResult =
                MattingResult(FloatArray(bitmap.width * bitmap.height) { 0f }, bitmap.width, bitmap.height)
        }
        val applier = RecipeApplier(processor, mattingEngine = engine)
        val src = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val out = applier.applyCutout(src, CutoutRecipe(bgMode = CutoutRecipe.BgMode.TRANSPARENT))
        assertEquals(0, (out.getPixel(0, 0) ushr 24) and 0xFF)
    }

    @Test
    fun `applyCutout color mode composites on solid color`() = runBlocking {
        val engine = object : MattingEngine {
            override suspend fun removeBackground(bitmap: Bitmap): MattingResult =
                MattingResult(FloatArray(bitmap.width * bitmap.height) { 0f }, bitmap.width, bitmap.height)
        }
        val applier = RecipeApplier(processor, mattingEngine = engine)
        val src = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val red = 0xFFFF0000.toInt()
        val out = applier.applyCutout(src, CutoutRecipe(bgMode = CutoutRecipe.BgMode.COLOR, bgColor = red))
        assertEquals(red, out.getPixel(0, 0))
    }

    @Test
    fun `applyCutout without mattingEngine returns same bitmap`() = runBlocking {
        val applier = RecipeApplier(processor)
        val src = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val out = applier.applyCutout(src, CutoutRecipe(bgMode = CutoutRecipe.BgMode.TRANSPARENT))
        assertTrue(src === out)
    }
}
