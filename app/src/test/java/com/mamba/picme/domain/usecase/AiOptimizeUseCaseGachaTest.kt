package com.mamba.picme.domain.usecase

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeCandidate
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeGachaEngine
import com.mamba.picme.domain.agent.capability.optimize.gacha.ScoredCandidate
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiOptimizeUseCaseGachaTest {

    private val imageUri = "file:///test.jpg"

    private fun presetFor(scene: Scene, tag: Float = 0f) = OptimizePreset(
        scene = scene.name,
        beauty = BeautyPreset(enabled = true, smoothing = 15f + tag, whitening = 10f),
        filter = FilterPreset("NONE", "NONE"),
        adjustment = AdjustmentPreset(brightness = 2f + tag, contrast = 52f, saturation = 100f)
    )

    private fun scored(index: Int, score: Float, tag: Float) = ScoredCandidate(
        candidate = OptimizeCandidate(index, "d$index", presetFor(Scene.GENERAL, tag)),
        nimaScore = score,
        rejected = false
    )

    private fun useCase(
        engine: OptimizeGachaEngine?,
        logger: OptimizeFeedbackLogger? = null
    ): AiOptimizeUseCase {
        val analyzer = mockk<SceneAnalyzer>()
        val repository = mockk<PresetRepository>()
        coEvery { analyzer.analyze(imageUri) } returns Scene.GENERAL
        every { repository.getPreset(Scene.GENERAL) } returns presetFor(Scene.GENERAL)
        return AiOptimizeUseCase(repository, analyzer, engine, logger)
    }

    @Test
    fun `falls back to fixed preset when gacha engine is null`() = runTest {
        val outcome = useCase(engine = null).optimizeWithGacha(imageUri)

        assertEquals(GachaResult.Unavailable, outcome.result)
        // 兜底路径仍返回固定预设 recipe（现有行为）
        assertNotNull(outcome.editRecipe)
        assertEquals(15f, outcome.editRecipe!!.beauty.smoothing, 0.001f)
    }

    @Test
    fun `Selected maps best candidate preset into edit recipe`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.5f, 20f))
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.Selected(best = all[1], all = all, originalScore = 5.0f)

        val outcome = useCase(engine).optimizeWithGacha(imageUri)

        assertTrue(outcome.result is GachaResult.Selected)
        // best 卡（tag=20）的参数被映射进 recipe
        assertEquals(35f, outcome.editRecipe!!.beauty.smoothing, 0.001f)
        assertEquals(22f, outcome.editRecipe!!.adjustments.brightness, 0.001f)
    }

    @Test
    fun `KeepOriginal returns null edit recipe`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.1f, 20f))
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.KeepOriginal(all = all, originalScore = 5.2f)

        val outcome = useCase(engine).optimizeWithGacha(imageUri)

        assertTrue(outcome.result is GachaResult.KeepOriginal)
        assertNull(outcome.editRecipe)
    }

    @Test
    fun `engine Unavailable falls back to fixed preset recipe`() = runTest {
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns GachaResult.Unavailable

        val outcome = useCase(engine).optimizeWithGacha(imageUri)

        assertEquals(GachaResult.Unavailable, outcome.result)
        assertNotNull(outcome.editRecipe)
    }

    @Test
    fun `auto feedback logged on Selected and KeepOriginal but not Unavailable`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.5f, 20f))
        val logger = mockk<OptimizeFeedbackLogger>()
        coEvery { logger.log(any(), any(), any(), any(), any()) } returns Unit

        val selectedEngine = mockk<OptimizeGachaEngine>()
        coEvery { selectedEngine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.Selected(best = all[1], all = all, originalScore = 5.0f)
        useCase(selectedEngine, logger).optimizeWithGacha(imageUri)
        coVerify(exactly = 1) {
            logger.log(imageUri, Scene.GENERAL, all, 1, OptimizeFeedbackLogger.SOURCE_AUTO)
        }

        val keepEngine = mockk<OptimizeGachaEngine>()
        coEvery { keepEngine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.KeepOriginal(all = all, originalScore = 5.6f)
        useCase(keepEngine, logger).optimizeWithGacha(imageUri)
        coVerify(exactly = 1) {
            logger.log(imageUri, Scene.GENERAL, all, -1, OptimizeFeedbackLogger.SOURCE_AUTO)
        }

        val unavailableEngine = mockk<OptimizeGachaEngine>()
        coEvery { unavailableEngine.run(any(), any(), any(), any(), any()) } returns GachaResult.Unavailable
        useCase(unavailableEngine, logger).optimizeWithGacha(imageUri)
        // 总共仍只有前两次调用
        coVerify(exactly = 2) { logger.log(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `usedFingerprints accumulates exclude plus all candidate fingerprints`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.5f, 20f))
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.Selected(best = all[1], all = all, originalScore = 5.0f)

        val outcome = useCase(engine).optimizeWithGacha(imageUri, exclude = setOf("old-fp"))

        assertTrue("old-fp" in outcome.usedFingerprints)
        assertEquals(3, outcome.usedFingerprints.size)
    }
}
