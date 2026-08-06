package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.content.Context
import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.features.editor.RecipeApplier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CandidateRendererTest {

    private val imageUri = "file:///test.jpg"

    private fun candidate() = OptimizeCandidate(
        index = 1,
        direction = "warm",
        preset = OptimizePreset(
            scene = "GENERAL",
            beauty = BeautyPreset(),
            filter = FilterPreset(),
            adjustment = AdjustmentPreset(temperature = 5400f)
        )
    )

    @Test
    fun `render delegates to recipeApplier and returns its output`() = runTest {
        val applier = mockk<RecipeApplier>()
        val base = mockk<Bitmap>()
        val rendered = mockk<Bitmap>()
        coEvery { applier.applyGpuEffects(base, any(), null) } returns rendered

        val renderer = CandidateRenderer(mockk<Context>(), applier, faceData = null)
        val result = renderer.render(candidate(), base, imageUri)

        assertEquals(rendered, result)
        coVerify(exactly = 1) { applier.applyGpuEffects(base, any(), null) }
    }

    @Test
    fun `render returns null when recipeApplier throws`() = runTest {
        val applier = mockk<RecipeApplier>()
        val base = mockk<Bitmap>()
        coEvery { applier.applyGpuEffects(base, any(), null) } throws RuntimeException("gpu dead")

        val renderer = CandidateRenderer(mockk<Context>(), applier, faceData = null)
        val result = renderer.render(candidate(), base, imageUri)

        assertNull(result)
    }
}
