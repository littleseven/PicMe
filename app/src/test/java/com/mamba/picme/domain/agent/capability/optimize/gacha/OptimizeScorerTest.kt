package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.aesthetic.AestheticScorer
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeScorerTest {

    private fun preset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(),
        filter = FilterPreset(),
        adjustment = AdjustmentPreset()
    )

    private fun candidate(index: Int) =
        OptimizeCandidate(index = index, direction = "d$index", preset = preset())

    private fun grayPx() = IntArray(64) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }

    @Test
    fun `scoreCandidate rejects card failing guardrails without calling scorer`() {
        val scorer = mockk<AestheticScorer>()
        val whitePx = IntArray(64) { (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255 }

        val result = OptimizeScorer(scorer).scoreCandidate(
            candidate = candidate(0),
            rendered = mockk<Bitmap>(),
            renderedPx = whitePx,
            originalMeanLuminance = 1.0f,   // 亮度漂移为 0，确保命中的是高光裁剪
            originalClipRatio = 0f          // 原图无裁剪，候选全白 → 增量 1.0 超阈值
        )

        assertTrue(result.rejected)
        assertTrue(result.rejectReason!!.startsWith("highlight_clip:"))
        assertNull(result.nimaScore)
    }

    @Test
    fun `scoreCandidate marks card rejected when nima returns null`() {
        val scorer = mockk<AestheticScorer>()
        every { scorer.score(any()) } returns null

        val result = OptimizeScorer(scorer).scoreCandidate(
            candidate = candidate(0),
            rendered = mockk<Bitmap>(),
            renderedPx = grayPx(),
            originalMeanLuminance = 0.5f,
            originalClipRatio = 0f
        )

        assertTrue(result.rejected)
        assertEquals("nima_failed", result.rejectReason)
    }

    @Test
    fun `select returns Selected when best exceeds original plus threshold`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = 5.3f, rejected = false),
            ScoredCandidate(candidate(2), nimaScore = 4.8f, rejected = false)
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = 5.0f)

        assertTrue(result is GachaResult.Selected)
        assertEquals(1, (result as GachaResult.Selected).best.candidate.index)
    }

    @Test
    fun `select returns KeepOriginal when improvement below threshold`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = 5.04f, rejected = false)
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = 5.0f)

        assertTrue(result is GachaResult.KeepOriginal)
    }

    @Test
    fun `select skips guard when original score unavailable`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = 5.0f, rejected = false)
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = null)

        assertTrue(result is GachaResult.Selected)
    }

    @Test
    fun `select returns Unavailable when valid cards below minimum`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = null, rejected = true, rejectReason = "nima_failed")
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = 5.0f)

        assertEquals(GachaResult.Unavailable, result)
    }
}
