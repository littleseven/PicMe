package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.aesthetic.AestheticScorer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class OptimizeGachaEngineTest {

    private val imageUri = "file:///test.jpg"

    private fun basePreset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(enabled = true, smoothing = 15f, whitening = 10f),
        filter = FilterPreset("NONE", "NONE"),
        adjustment = AdjustmentPreset(contrast = 52f, saturation = 100f)
    )

    /** 中灰像素（过护栏，亮度 0.502） */
    private fun grayPx() = IntArray(64) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }

    /**
     * 装配引擎：base 图打 [originalScore]，候选渲染图统一打 [candidateScore]。
     * base 与 rendered 用不同 mock 实例以区分打分对象。
     */
    private fun engine(
        originalScore: Float?,
        candidateScore: Float?,
        renderNullFor: Set<Int> = emptySet()
    ): OptimizeGachaEngine {
        val baseBitmap = mockk<Bitmap>()
        val scorer = mockk<AestheticScorer>()
        coEvery { scorer.initialize() } returns true
        // 用引用匹配区分原图与候选渲染图（两个 matcher 不相交，无顺序依赖）
        every { scorer.score(match { it === baseBitmap }) } returns originalScore
        every { scorer.score(match { it !== baseBitmap }) } answers { candidateScore }

        val renderer = mockk<CandidateRenderer>()
        every { renderer.decodeDownscaled(imageUri, any()) } returns baseBitmap
        every { renderer.extractPixels(any()) } returns grayPx()
        coEvery { renderer.render(any(), baseBitmap, imageUri) } answers {
            val c = firstArg<OptimizeCandidate>()
            if (c.index in renderNullFor) null else mockk<Bitmap>()
        }

        return OptimizeGachaEngine(
            sampler = CandidateSampler(Random(42)),
            renderer = renderer,
            optimizeScorer = OptimizeScorer(scorer),
            aestheticScorer = scorer
        )
    }

    @Test
    fun `run returns Selected when candidate beats original`() = runTest {
        val result = engine(originalScore = 5.0f, candidateScore = 5.4f)
            .run(imageUri, Scene.GENERAL, basePreset())

        assertTrue(result is GachaResult.Selected)
        assertEquals(4, (result as GachaResult.Selected).all.size)
    }

    @Test
    fun `run returns KeepOriginal when no candidate beats original plus threshold`() = runTest {
        val result = engine(originalScore = 5.4f, candidateScore = 5.0f)
            .run(imageUri, Scene.GENERAL, basePreset())

        assertTrue(result is GachaResult.KeepOriginal)
    }

    @Test
    fun `run returns Unavailable when scorer not initialized`() = runTest {
        val scorer = mockk<AestheticScorer>()
        coEvery { scorer.initialize() } returns false
        val engine = OptimizeGachaEngine(
            sampler = CandidateSampler(Random(1)),
            renderer = mockk(),
            optimizeScorer = OptimizeScorer(scorer),
            aestheticScorer = scorer
        )

        assertEquals(GachaResult.Unavailable, engine.run(imageUri, Scene.GENERAL, basePreset()))
    }

    @Test
    fun `run returns Unavailable when decode fails`() = runTest {
        val scorer = mockk<AestheticScorer>()
        coEvery { scorer.initialize() } returns true
        val renderer = mockk<CandidateRenderer>()
        every { renderer.decodeDownscaled(imageUri, any()) } returns null
        val engine = OptimizeGachaEngine(
            sampler = CandidateSampler(Random(1)),
            renderer = renderer,
            optimizeScorer = OptimizeScorer(scorer),
            aestheticScorer = scorer
        )

        assertEquals(GachaResult.Unavailable, engine.run(imageUri, Scene.GENERAL, basePreset()))
    }

    @Test
    fun `run returns Unavailable when fewer than 2 cards render`() = runTest {
        // 4 张卡中 3 张渲染失败 → 有效卡 1 < MIN_VALID_CARDS
        val result = engine(originalScore = 5.0f, candidateScore = 5.4f, renderNullFor = setOf(1, 2, 3))
            .run(imageUri, Scene.GENERAL, basePreset())

        assertEquals(GachaResult.Unavailable, result)
    }
}
