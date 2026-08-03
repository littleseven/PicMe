package com.mamba.picme.domain.usecase

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
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
 * 本地场景分析（LocalSceneAnalyzer/ML Kit image-labeling）已移除：fast 路径
 * 固定走 GENERAL 预设。本测试验证 fast/smart 路径、云端授权降级、异常降级行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiOptimizeUseCaseTest {

    private val presetRepository: PresetRepository = mockk()
    private val consentManager: CloudOptimizeConsentManager = mockk()
    private val smartEngine: SmartOptimizeEngine = mockk()

    private val testPreset = OptimizePreset(
        scene = Scene.GENERAL.name,
        beauty = BeautyPreset(smoothing = 15f),
        filter = FilterPreset(colorFilter = "NONE"),
        adjustment = AdjustmentPreset(brightness = 2f)
    )

    private fun createUseCase(engine: SmartOptimizeEngine? = smartEngine) = AiOptimizeUseCase(
        presetRepository = presetRepository,
        consentManager = consentManager,
        smartEngine = engine
    )

    @Test
    fun `fastOptimize returns GENERAL preset`() = runTest {
        every { presetRepository.getPreset(Scene.GENERAL) } returns testPreset

        val result = createUseCase().fastOptimize("file:///test.jpg")

        assertEquals(Scene.GENERAL, result.scene)
        assertFalse(result.usedCloud)
        assertEquals("file:///test.jpg", result.editRecipe.sourceUri)
        verify { presetRepository.getPreset(Scene.GENERAL) }
    }

    @Test
    fun `smartOptimize falls back to fast when consent denied`() = runTest {
        coEvery { consentManager.isCloudOptimizeAllowed() } returns false
        every { presetRepository.getPreset(Scene.GENERAL) } returns testPreset

        val result = createUseCase().smartOptimize("file:///test.jpg")

        assertFalse(result.usedCloud)
        assertEquals(Scene.GENERAL, result.scene)
        coVerify { consentManager.isCloudOptimizeAllowed() }
    }

    @Test
    fun `smartOptimize falls back to fast when engine is null`() = runTest {
        coEvery { consentManager.isCloudOptimizeAllowed() } returns true
        every { presetRepository.getPreset(Scene.GENERAL) } returns testPreset

        val result = createUseCase(engine = null).smartOptimize("file:///test.jpg")

        assertFalse(result.usedCloud)
        assertEquals(Scene.GENERAL, result.scene)
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
        every { presetRepository.getPreset(Scene.GENERAL) } returns testPreset

        val result = createUseCase().smartOptimize("file:///test.jpg")

        assertFalse(result.usedCloud)
        assertEquals(Scene.GENERAL, result.scene)
    }
}
