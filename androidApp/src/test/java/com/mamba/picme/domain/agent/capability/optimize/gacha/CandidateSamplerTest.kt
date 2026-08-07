package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CandidateSamplerTest {

    private fun basePreset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(enabled = true, smoothing = 15f, whitening = 10f, slimFace = 5f),
        filter = FilterPreset("NONE", "NONE"),
        adjustment = AdjustmentPreset(
            brightness = 2f, exposure = 0f, contrast = 52f,
            saturation = 100f, temperature = 5000f, tint = 0f
        )
    )

    @Test
    fun `sample returns count candidates with base preset as anchor card`() {
        val base = basePreset()
        val cards = CandidateSampler(Random(42)).sample(base, Scene.GENERAL)

        assertEquals(CandidateSampler.DEFAULT_COUNT, cards.size)
        assertEquals("base", cards[0].direction)
        assertEquals(base, cards[0].preset)
        cards.forEachIndexed { i, card -> assertEquals(i, card.index) }
    }

    @Test
    fun `same seed produces identical candidates`() {
        val base = basePreset()
        val a = CandidateSampler(Random(7)).sample(base, Scene.GENERAL)
        val b = CandidateSampler(Random(7)).sample(base, Scene.GENERAL)
        assertEquals(a, b)
    }

    @Test
    fun `all candidates have distinct fingerprints`() {
        val cards = CandidateSampler(Random(1)).sample(basePreset(), Scene.GENERAL)
        val fps = cards.map { CandidateSampler.fingerprint(it.preset) }
        assertEquals(fps.size, fps.toSet().size)
    }

    @Test
    fun `params stay within legal ranges across many seeds`() {
        val base = basePreset()
        for (seed in 0L until 50L) {
            val cards = CandidateSampler(Random(seed)).sample(base, Scene.SELFIE)
            for (c in cards) {
                val a = c.preset.adjustment
                assertTrue(a.brightness in -100f..100f)
                assertTrue(a.exposure in -100f..100f)
                assertTrue(a.contrast in 0f..200f)
                assertTrue(a.saturation in 0f..200f)
                assertTrue(a.temperature in 2000f..8000f)
                assertTrue(a.tint in -100f..100f)
                assertTrue(c.preset.beauty.smoothing in 0f..100f)
                assertTrue(c.preset.beauty.whitening in 0f..100f)
                // 形变维度不扰动
                assertEquals(base.beauty.slimFace, c.preset.beauty.slimFace, 0.001f)
                assertEquals(base.beauty.bigEyes, c.preset.beauty.bigEyes, 0.001f)
            }
        }
    }

    @Test
    fun `exclude forces new combinations on reroll`() {
        val base = basePreset()
        val first = CandidateSampler(Random(3)).sample(base, Scene.GENERAL)
        val exclude = first.map { CandidateSampler.fingerprint(it.preset) }.toSet()

        val second = CandidateSampler(Random(4)).sample(base, Scene.GENERAL, exclude = exclude)
        val secondNonBase = second.drop(1).map { CandidateSampler.fingerprint(it.preset) }

        assertTrue(secondNonBase.none { it in exclude })
    }

    @Test
    fun `fingerprint quantizes sub-integer differences`() {
        val p1 = basePreset()
        val p2 = basePreset().copy(
            adjustment = basePreset().adjustment.copy(brightness = 2.4f)
        )
        val p3 = basePreset().copy(
            adjustment = basePreset().adjustment.copy(brightness = 3.0f)
        )
        assertEquals(CandidateSampler.fingerprint(p1), CandidateSampler.fingerprint(p2))
        assertNotEquals(CandidateSampler.fingerprint(p1), CandidateSampler.fingerprint(p3))
    }

    @Test
    fun `non-portrait scene does not jitter beauty params`() {
        val base = basePreset()
        val cards = CandidateSampler(Random(9)).sample(base, Scene.LANDSCAPE)
        cards.forEach { c ->
            assertEquals(base.beauty.smoothing, c.preset.beauty.smoothing, 0.001f)
            assertEquals(base.beauty.whitening, c.preset.beauty.whitening, 0.001f)
        }
    }

    @Test
    fun `returns fewer than count when direction space is exhausted`() {
        val cards = CandidateSampler(Random(0)).sample(basePreset(), Scene.DOCUMENT, count = 100)
        // DOCUMENT 只有 3 个方向；MAX_RETRY=20 封顶总尝试次数 → 远小于 100
        assertTrue(cards.size < 100)
        assertTrue(cards.isNotEmpty())
        assertEquals("base", cards[0].direction) // 锚点恒在
    }
}
