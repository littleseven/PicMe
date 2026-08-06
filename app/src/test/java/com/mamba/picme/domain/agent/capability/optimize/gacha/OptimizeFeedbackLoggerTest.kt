package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.data.local.dao.OptimizeFeedbackDao
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimizeFeedbackLoggerTest {

    private fun scored(index: Int, score: Float?) = ScoredCandidate(
        candidate = OptimizeCandidate(
            index = index,
            direction = "d$index",
            preset = OptimizePreset(
                scene = "GENERAL",
                beauty = BeautyPreset(smoothing = 15f, whitening = 10f),
                filter = FilterPreset("WARM", "NONE"),
                adjustment = AdjustmentPreset(brightness = 5f, contrast = 60f, temperature = 5400f)
            )
        ),
        nimaScore = score,
        rejected = score == null,
        rejectReason = if (score == null) "nima_failed" else null
    )

    @Test
    fun `log inserts entity with hashed image key and candidates json`() = runTest {
        val dao = mockk<OptimizeFeedbackDao>()
        val slot = slot<com.mamba.picme.data.local.entity.OptimizeFeedbackEntity>()
        coEvery { dao.insert(capture(slot)) } just runs

        OptimizeFeedbackLogger(dao).log(
            imageUri = "file:///private/user/photo.jpg",
            scene = com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene.GENERAL,
            all = listOf(scored(0, 5.0f), scored(1, null)),
            selectedIndex = 0,
            source = OptimizeFeedbackLogger.SOURCE_AUTO
        )

        coVerify(exactly = 1) { dao.insert(any()) }
        val entity = slot.captured
        // image_key 是哈希，不含原始路径
        assertEquals(16, entity.imageKey.length)
        assertTrue(!entity.imageKey.contains("photo"))
        assertEquals("GENERAL", entity.scene)
        assertEquals(0, entity.selectedIndex)
        assertEquals("auto", entity.selectionSource)
        // candidates_json 含两张卡的参数与分数
        assertTrue(entity.candidatesJson.contains("\"direction\":\"d0\""))
        // org.json numberToString 会去掉尾零：5.0 → 5，5400.0 → 5400（数值等价）
        assertTrue(entity.candidatesJson.contains("\"nimaScore\":5"))
        assertTrue(entity.candidatesJson.contains("\"rejected\":true"))
        assertTrue(entity.candidatesJson.contains("\"temperature\":5400"))
    }

    @Test
    fun `log is no-op when dao is null`() = runTest {
        // 不抛异常即通过
        OptimizeFeedbackLogger(null).log(
            imageUri = "file:///a.jpg",
            scene = com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene.GENERAL,
            all = emptyList(),
            selectedIndex = -1,
            source = OptimizeFeedbackLogger.SOURCE_AUTO
        )
    }

    @Test
    fun `log swallows dao exceptions`() = runTest {
        val dao = mockk<OptimizeFeedbackDao>()
        coEvery { dao.insert(any()) } throws RuntimeException("db locked")

        // 不抛异常即通过
        OptimizeFeedbackLogger(dao).log(
            imageUri = "file:///a.jpg",
            scene = com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene.GENERAL,
            all = listOf(scored(0, 5.0f)),
            selectedIndex = 0,
            source = OptimizeFeedbackLogger.SOURCE_AUTO
        )
    }
}
