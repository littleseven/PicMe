package com.mamba.picme.domain.usecase

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [QA] AiOptimizeUseCase 单元测试（US-1 AC1.3）
 *
 * 重构后 useCase 构造为 (presetRepository, sceneAnalyzer)，optimize() 通过端侧
 * [SceneAnalyzer] 识别场景并按场景路由预设。本测试覆盖：
 * - 8 种 Scene 各跑一遍 optimize()，断言返回对应场景预设且不抛异常；
 * - SELFIE/FOOD/LOW_LIGHT 的返回 recipe 与 GENERAL 预设在关键字段上不同；
 * - analyze() 被调用，且其返回的 Scene 被用于 getPreset()。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiOptimizeUseCaseTest {

    private val imageUri = "file:///test.jpg"

    /**
     * 每个场景一套可区分字段的预设。GENERAL 作为对比基线，SELFIE/FOOD/LOW_LIGHT
     * 必须在 smoothing / saturation / brightness 上与 GENERAL 明显不同。
     */
    private fun presetFor(scene: Scene): OptimizePreset {
        val beauty = when (scene) {
            Scene.SELFIE -> BeautyPreset(smoothing = 60f, whitening = 40f, slimFace = 20f)
            Scene.PORTRAIT -> BeautyPreset(smoothing = 35f, whitening = 20f)
            Scene.GROUP -> BeautyPreset(smoothing = 25f)
            Scene.LOW_LIGHT -> BeautyPreset(smoothing = 18f)
            Scene.DOCUMENT -> BeautyPreset(smoothing = 5f)
            Scene.FOOD, Scene.LANDSCAPE, Scene.GENERAL -> BeautyPreset(smoothing = 10f)
        }
        val adjustment = when (scene) {
            Scene.FOOD -> AdjustmentPreset(saturation = 150f, brightness = 5f)
            Scene.LOW_LIGHT -> AdjustmentPreset(brightness = 35f, saturation = 90f)
            Scene.LANDSCAPE -> AdjustmentPreset(saturation = 120f, contrast = 60f)
            Scene.DOCUMENT -> AdjustmentPreset(contrast = 80f, saturation = 80f)
            Scene.SELFIE, Scene.PORTRAIT, Scene.GROUP, Scene.GENERAL -> AdjustmentPreset(saturation = 100f)
        }
        return OptimizePreset(
            scene = scene.name,
            beauty = beauty,
            filter = FilterPreset(colorFilter = "NONE", styleFilter = "NONE"),
            adjustment = adjustment
        )
    }

    @Test
    fun `optimize routes each of the 8 scenes to its preset without throwing`() = runTest {
        Scene.entries.forEach { scene ->
            val analyzer: SceneAnalyzer = mockk()
            val repository: PresetRepository = mockk()
            coEvery { analyzer.analyze(imageUri) } returns scene
            every { repository.getPreset(scene) } returns presetFor(scene)

            val result = AiOptimizeUseCase(repository, analyzer).optimize(imageUri)

            assertEquals(scene, result.scene)
            assertEquals(imageUri, result.editRecipe.sourceUri)
            // analyze() 被调用一次，且返回的 scene 被用于 getPreset()
            coVerify(exactly = 1) { analyzer.analyze(imageUri) }
            verify(exactly = 1) { repository.getPreset(scene) }
        }
    }

    @Test
    fun `SELFIE recipe differs from GENERAL preset`() = runTest {
        val analyzer: SceneAnalyzer = mockk()
        val repository: PresetRepository = mockk()

        // SELFIE 断言锚点
        coEvery { analyzer.analyze(imageUri) } returns Scene.SELFIE
        every { repository.getPreset(Scene.SELFIE) } returns presetFor(Scene.SELFIE)
        val selfieRecipe = AiOptimizeUseCase(repository, analyzer).optimize(imageUri).editRecipe

        // GENERAL 断言锚点（对比基线）
        coEvery { analyzer.analyze(imageUri) } returns Scene.GENERAL
        every { repository.getPreset(Scene.GENERAL) } returns presetFor(Scene.GENERAL)
        val generalRecipe = AiOptimizeUseCase(repository, analyzer).optimize(imageUri).editRecipe

        assertNotEquals(generalRecipe.beauty.smoothing, selfieRecipe.beauty.smoothing)
        assertNotEquals(generalRecipe.beauty.slimFace, selfieRecipe.beauty.slimFace)
        assertEquals(60f, selfieRecipe.beauty.smoothing, 0.001f)
    }

    @Test
    fun `FOOD recipe differs from GENERAL preset`() = runTest {
        val analyzer: SceneAnalyzer = mockk()
        val repository: PresetRepository = mockk()

        // FOOD 断言锚点
        coEvery { analyzer.analyze(imageUri) } returns Scene.FOOD
        every { repository.getPreset(Scene.FOOD) } returns presetFor(Scene.FOOD)
        val foodRecipe = AiOptimizeUseCase(repository, analyzer).optimize(imageUri).editRecipe

        coEvery { analyzer.analyze(imageUri) } returns Scene.GENERAL
        every { repository.getPreset(Scene.GENERAL) } returns presetFor(Scene.GENERAL)
        val generalRecipe = AiOptimizeUseCase(repository, analyzer).optimize(imageUri).editRecipe

        assertNotEquals(generalRecipe.adjustments.saturation, foodRecipe.adjustments.saturation)
        assertEquals(150f, foodRecipe.adjustments.saturation, 0.001f)
    }

    @Test
    fun `LOW_LIGHT recipe differs from GENERAL preset`() = runTest {
        val analyzer: SceneAnalyzer = mockk()
        val repository: PresetRepository = mockk()

        // LOW_LIGHT 断言锚点
        coEvery { analyzer.analyze(imageUri) } returns Scene.LOW_LIGHT
        every { repository.getPreset(Scene.LOW_LIGHT) } returns presetFor(Scene.LOW_LIGHT)
        val lowLightRecipe = AiOptimizeUseCase(repository, analyzer).optimize(imageUri).editRecipe

        coEvery { analyzer.analyze(imageUri) } returns Scene.GENERAL
        every { repository.getPreset(Scene.GENERAL) } returns presetFor(Scene.GENERAL)
        val generalRecipe = AiOptimizeUseCase(repository, analyzer).optimize(imageUri).editRecipe

        assertNotEquals(generalRecipe.adjustments.brightness, lowLightRecipe.adjustments.brightness)
        assertEquals(35f, lowLightRecipe.adjustments.brightness, 0.001f)
    }

    @Test
    fun `analyze result is used to select the preset`() = runTest {
        val analyzer: SceneAnalyzer = mockk()
        val repository: PresetRepository = mockk()
        coEvery { analyzer.analyze(imageUri) } returns Scene.LANDSCAPE
        every { repository.getPreset(Scene.LANDSCAPE) } returns presetFor(Scene.LANDSCAPE)

        AiOptimizeUseCase(repository, analyzer).optimize(imageUri)

        // analyze() 被调用一次，且其返回值 LANDSCAPE 被传给 getPreset()
        coVerify(exactly = 1) { analyzer.analyze(imageUri) }
        verify(exactly = 1) { repository.getPreset(Scene.LANDSCAPE) }
    }
}
