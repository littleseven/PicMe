package com.mamba.picme.domain.usecase

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalysis
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.consent.CloudOptimizeConsentManager
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import com.mamba.picme.domain.agent.capability.optimize.smart.SmartOptimizeEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] AiOptimizeUseCase 单元测试
 *
 * 验证 fast/smart 优化路径、云端授权降级、异常降级行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiOptimizeUseCaseTest {

    private val sceneAnalyzer: SceneAnalyzer = mockk()
    private val presetRepository: PresetRepository = mockk()
    private val consentManager: CloudOptimizeConsentManager = mockk()
    private val smartEngine: SmartOptimizeEngine = mockk()

    private val testPreset = OptimizePreset(
        scene = Scene.PORTRAIT.name,
        beauty = BeautyPreset(smoothing = 25f),
        filter = FilterPreset(colorFilter = "WARM"),
        adjustment = AdjustmentPreset(brightness = 5f)
    )

    private fun createUseCase(engine: SmartOptimizeEngine? = smartEngine) = AiOptimizeUseCase(
        sceneAnalyzer = sceneAnalyzer,
        presetRepository = presetRepository,
        consentManager = consentManager,
        smartEngine = engine
    )

    @Test
    fun `fastOptimize returns local analysis result`() = runTest {
        coEvery { sceneAnalyzer.analyze(any()) } returns SceneAnalysis(
            scene = Scene.PORTRAIT,
            confidence = 0.85f
        )
        every { presetRepository.getPreset(Scene.PORTRAIT) } returns testPreset

        val result = createUseCase().fastOptimize("file:///test.jpg")

        assertEquals(Scene.PORTRAIT, result.scene)
        assertEquals(0.85f, result.confidence, 0.001f)
        assertFalse(result.usedCloud)
        assertEquals("file:///test.jpg", result.editRecipe.sourceUri)
        coVerify { sceneAnalyzer.analyze("file:///test.jpg") }
        verify { presetRepository.getPreset(Scene.PORTRAIT) }
    }

    @Test
    fun `smartOptimize falls back to fast when consent denied`() = runTest {
        coEvery { consentManager.isCloudOptimizeAllowed() } returns false
        coEvery { sceneAnalyzer.analyze(any()) } returns SceneAnalysis(
            scene = Scene.FOOD,
            confidence = 0.8f
        )
        every { presetRepository.getPreset(Scene.FOOD) } returns testPreset.copy(scene = Scene.FOOD.name)

        val result = createUseCase().smartOptimize("file:///test.jpg")

        assertFalse(result.usedCloud)
        assertEquals(Scene.FOOD, result.scene)
        coVerify { consentManager.isCloudOptimizeAllowed() }
        coVerify { sceneAnalyzer.analyze("file:///test.jpg") }
    }

    @Test
    fun `smartOptimize falls back to fast when engine is null`() = runTest {
        coEvery { consentManager.isCloudOptimizeAllowed() } returns true
        coEvery { sceneAnalyzer.analyze(any()) } returns SceneAnalysis(
            scene = Scene.LANDSCAPE,
            confidence = 0.8f
        )
        every { presetRepository.getPreset(Scene.LANDSCAPE) } returns testPreset.copy(scene = Scene.LANDSCAPE.name)

        val result = createUseCase(engine = null).smartOptimize("file:///test.jpg")

        assertFalse(result.usedCloud)
        assertEquals(Scene.LANDSCAPE, result.scene)
    }

    @Test
    fun `smartOptimize uses cloud when allowed and engine available`() = runTest {
        coEvery { consentManager.isCloudOptimizeAllowed() } returns true
        coEvery { smartEngine.optimize(any()) } returns testPreset.copy(scene = Scene.SELFIE.name)

        val result = createUseCase().smartOptimize("file:///test.jpg")

        assertTrue(result.usedCloud)
        assertEquals(Scene.SELFIE, result.scene)
        assertEquals(0.85f, result.confidence, 0.001f)
        coVerify { smartEngine.optimize("file:///test.jpg") }
    }

    @Test
    fun `smartOptimize falls back to fast on engine error`() = runTest {
        coEvery { consentManager.isCloudOptimizeAllowed() } returns true
        coEvery { smartEngine.optimize(any()) } throws RuntimeException("cloud failed")
        coEvery { sceneAnalyzer.analyze(any()) } returns SceneAnalysis(
            scene = Scene.GENERAL,
            confidence = 0.6f
        )
        every { presetRepository.getPreset(Scene.GENERAL) } returns testPreset.copy(scene = Scene.GENERAL.name)

        val result = createUseCase().smartOptimize("file:///test.jpg")

        assertFalse(result.usedCloud)
        assertEquals(Scene.GENERAL, result.scene)
    }
}
