package com.mamba.picme.features.chat

import com.mamba.picme.data.local.ChatMessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelGachaTest : ChatViewModelTestBase() {

    private val gachaController = mockk<ChatOptimizeGachaController>(relaxUnitFun = true)

    override fun newViewModel(): ChatViewModel = super.newViewModelWithGacha(gachaController)

    private fun group() = OptimizeCandidateGroup(
        sourceImageUri = "content://media/1",
        scene = "GENERAL",
        recommendedIndex = 1,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate("base", "file:///a.jpg", 6.0f, false),
            OptimizeCandidateGroup.Candidate("warm", "file:///b.jpg", 6.5f, false)
        ),
        usedFingerprints = listOf("fp1"),
        drawIndex = 1
    )

    @Test
    fun `insertOptimizeCandidatesMessage persists optimize_candidates and seeds selection`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.insertOptimizeCandidatesMessage("default", "msg1", group(), "expl", "remote_deepseek")
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals(OptimizeCandidateGroup.MESSAGE_TYPE, slot.captured.type)
        assertEquals("msg1", slot.captured.id)
        assertEquals(group().toJson(), slot.captured.metadata)
        assertEquals(1, vm.gachaSelections.value["msg1"])
    }

    @Test
    fun `onOptimizeGachaConfirm rewrites message to agent_image`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        coEvery { gachaController.confirm("msg1", 1) } returns
            ChatOptimizeGachaController.ConfirmResult("file:///full.jpg", mockk())
        coEvery { chatMessageDao.getMessageById("msg1") } returns ChatMessageEntity(
            id = "msg1", sessionId = "default",
            type = OptimizeCandidateGroup.MESSAGE_TYPE,
            content = "expl", metadata = group().toJson()
        )

        vm.onOptimizeGachaConfirm("msg1", 1) { }
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals("agent_image", slot.captured.type)
        assertEquals("msg1", slot.captured.id)
        assertTrue(slot.captured.metadata!!.contains("file:///full.jpg"))
        assertEquals("expl", slot.captured.content)
    }

    @Test
    fun `onOptimizeGachaConfirm failure keeps message and reports error`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        coEvery { gachaController.confirm("msg1", 1) } returns null

        var failed = false
        vm.onOptimizeGachaConfirm("msg1", 1) { ok -> failed = !ok }
        advanceUntilIdle()

        assertTrue(failed)
        coVerify(exactly = 0) { chatMessageDao.insertMessage(any()) }
    }

    @Test
    fun `onOptimizeGachaReroll rewrites message metadata and resets selection`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val newGroup = group().copy(drawIndex = 2, recommendedIndex = 0)
        coEvery { gachaController.reroll("msg1") } returns
            ChatOptimizeGachaController.RerollOutcome.Rerolled(newGroup, "new expl")
        coEvery { chatMessageDao.getMessageById("msg1") } returns ChatMessageEntity(
            id = "msg1", sessionId = "default",
            type = OptimizeCandidateGroup.MESSAGE_TYPE,
            content = "expl", metadata = group().toJson()
        )

        vm.onOptimizeGachaReroll("msg1") { }
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals(newGroup.toJson(), slot.captured.metadata)
        assertEquals("new expl", slot.captured.content)
        assertEquals(0, vm.gachaSelections.value["msg1"])
    }

    @Test
    fun `clearChat discards pending gacha groups`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.clearChat()
        advanceUntilIdle()

        coVerify { gachaController.discardPending("default", any()) }
    }
}
