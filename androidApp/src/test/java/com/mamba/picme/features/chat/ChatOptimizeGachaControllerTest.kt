package com.mamba.picme.features.chat

import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeCandidate
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.ScoredCandidate
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatOptimizeGachaControllerTest {

    private val useCase = mockk<AiOptimizeUseCase>()
    private val renderer = mockk<ChatImageRenderer>()
    private val store = mockk<ChatImageStore>()
    private val feedbackLogger = mockk<OptimizeFeedbackLogger>(relaxUnitFun = true)
    private val stateHolder = mockk<ChatEditStateHolder>(relaxUnitFun = true)

    private fun controller() = ChatOptimizeGachaController(
        optimizeUseCase = useCase,
        chatImageRenderer = renderer,
        chatImageStore = store,
        feedbackLogger = feedbackLogger,
        chatEditStateHolder = stateHolder
    )

    private fun preset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(),
        filter = FilterPreset(),
        adjustment = AdjustmentPreset()
    )

    private fun scored(index: Int, score: Float?, rejected: Boolean = false) = ScoredCandidate(
        candidate = OptimizeCandidate(index = index, direction = "d$index", preset = preset()),
        nimaScore = score,
        rejected = rejected,
        rejectReason = if (rejected) "nima_failed" else null,
        thumbnail = mockk<Bitmap>()
    )

    private fun outcome(result: GachaResult, fingerprints: Set<String> = setOf("fp1")) =
        AiOptimizeUseCase.GachaOutcome(
            result = result,
            scene = Scene.GENERAL,
            editRecipe = null,
            explanation = "expl",
            usedFingerprints = fingerprints,
            processingTimeMs = 1L
        )

    @Test
    fun `draw Selected returns Candidates with best as recommendedIndex`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f), scored(2, 5.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        val result = c.draw("msg1", "uri", "session1")

        assertTrue(result is ChatOptimizeGachaController.DrawOutcome.Candidates)
        val group = (result as ChatOptimizeGachaController.DrawOutcome.Candidates).group
        assertEquals(1, group.recommendedIndex)
        assertEquals(3, group.candidates.size)
        assertEquals("file:///t.jpg", group.candidates[0].thumbPath)
        assertEquals(1, group.drawIndex)
        assertTrue(c.hasPending("msg1"))
    }

    @Test
    fun `draw KeepOriginal returns Candidates with recommendedIndex -1`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.02f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.KeepOriginal(all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val result = controller().draw("msg1", "uri", "session1")

        assertEquals(-1, (result as ChatOptimizeGachaController.DrawOutcome.Candidates).group.recommendedIndex)
    }

    @Test
    fun `draw Unavailable falls back to legacy single-shot`() = runTest {
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Unavailable)
        coEvery { renderer.aiOptimize("uri", "session1") } returns
            ChatImageRenderer.Outcome("file:///r.jpg", "legacy expl")

        val result = controller().draw("msg1", "uri", "session1")

        assertEquals(
            ChatOptimizeGachaController.DrawOutcome.Fallback("file:///r.jpg", "legacy expl"),
            result
        )
    }

    @Test
    fun `draw falls back when all thumbnails fail to persist`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } throws RuntimeException("disk full")
        coEvery { renderer.aiOptimize("uri", "session1") } returns
            ChatImageRenderer.Outcome("file:///r.jpg", "legacy expl")

        val result = controller().draw("msg1", "uri", "session1")

        assertTrue(result is ChatOptimizeGachaController.DrawOutcome.Fallback)
    }

    @Test
    fun `draw keeps candidate with blank thumbPath when individual persist fails`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        var call = 0
        coEvery { store.writeResult(any(), any(), any()) } answers {
            call++
            if (call == 1) throw RuntimeException("io") else "file:///t.jpg"
        }

        val result = controller().draw("msg1", "uri", "session1")

        val group = (result as ChatOptimizeGachaController.DrawOutcome.Candidates).group
        assertEquals("", group.candidates[0].thumbPath)
        assertEquals("file:///t.jpg", group.candidates[1].thumbPath)
    }

    @Test
    fun `reroll passes usedFingerprints as exclude and increments drawIndex`() = runTest {
        val first = listOf(scored(0, 6.0f), scored(1, 6.5f))
        val second = listOf(scored(0, 6.1f), scored(1, 6.6f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = emptySet()) } returns
            outcome(GachaResult.Selected(best = first[1], all = first, originalScore = 6.0f), setOf("fp1"))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = setOf("fp1")) } returns
            outcome(GachaResult.Selected(best = second[1], all = second, originalScore = 6.0f), setOf("fp1", "fp2"))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.reroll("msg1")

        assertTrue(result is ChatOptimizeGachaController.RerollOutcome.Rerolled)
        val group = (result as ChatOptimizeGachaController.RerollOutcome.Rerolled).group
        assertEquals(2, group.drawIndex)
        assertEquals(listOf("fp1", "fp2"), group.usedFingerprints)
    }

    @Test
    fun `reroll returns Expired for unknown messageId`() = runTest {
        assertEquals(
            ChatOptimizeGachaController.RerollOutcome.Expired,
            controller().reroll("nope")
        )
    }

    @Test
    fun `reroll Unavailable keeps existing pending group`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = emptySet()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = setOf("fp1")) } returns
            outcome(GachaResult.Unavailable)
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.reroll("msg1")

        assertEquals(ChatOptimizeGachaController.RerollOutcome.Unavailable, result)
        assertTrue(c.hasPending("msg1"))
    }

    @Test
    fun `confirm renders full size, updates state holder, logs user, clears pending`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"
        coEvery { renderer.renderRecipe("uri", any(), "session1") } returns "file:///full.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.confirm("msg1", 1)

        assertEquals("file:///full.jpg", result?.imageUri)
        verify(exactly = 1) { stateHolder.update("session1", any()) }
        coVerify(exactly = 1) {
            feedbackLogger.log(
                "uri", Scene.GENERAL, all.map { it.copy(thumbnail = null) }, 1,
                OptimizeFeedbackLogger.SOURCE_USER
            )
        }
        assertFalse(c.hasPending("msg1"))
    }

    @Test
    fun `confirm render failure returns null and keeps pending for retry`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"
        coEvery { renderer.renderRecipe("uri", any(), "session1") } returns null

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.confirm("msg1", 1)

        assertNull(result)
        verify(exactly = 0) { stateHolder.update(any(), any()) }
        coVerify(exactly = 0) { feedbackLogger.log(any(), any(), any(), any(), OptimizeFeedbackLogger.SOURCE_USER) }
        assertTrue(c.hasPending("msg1")) // 可重试
    }

    @Test
    fun `confirm returns null for unknown messageId`() = runTest {
        assertNull(controller().confirm("nope", 0))
    }

    @Test
    fun `confirm returns null for rejected card without side effects`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, null, rejected = true))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[0], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.confirm("msg1", 1)

        assertNull(result)
        verify(exactly = 0) { stateHolder.update(any(), any()) }
        coVerify(exactly = 0) { feedbackLogger.log(any(), any(), any(), any(), OptimizeFeedbackLogger.SOURCE_USER) }
    }

    @Test
    fun `discardPending logs dismiss only for matching session`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha(any(), any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg-a", "uri-a", "session1")
        c.draw("msg-b", "uri-b", "session2")
        c.discardPending("session1")

        coVerify(exactly = 1) {
            feedbackLogger.log(
                "uri-a", Scene.GENERAL, all.map { it.copy(thumbnail = null) }, -1,
                OptimizeFeedbackLogger.SOURCE_DISMISS
            )
        }
        assertFalse(c.hasPending("msg-a"))
        assertTrue(c.hasPending("msg-b"))
    }

    @Test
    fun `reroll persist all failure returns Unavailable and keeps pending`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = emptySet()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = setOf("fp1")) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f), setOf("fp1", "fp2"))
        var call = 0
        coEvery { store.writeResult(any(), any(), any()) } answers {
            call++
            if (call <= 2) "file:///t.jpg" else throw RuntimeException("io")
        }

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.reroll("msg1")

        assertEquals(ChatOptimizeGachaController.RerollOutcome.Unavailable, result)
        assertTrue(c.hasPending("msg1"))
    }
}
