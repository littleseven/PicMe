package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsBridgeException
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.CommandRisk
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [CapabilityDispatchHandler] 单测（纯 JVM）：确认通过 / 拒绝 / 超时 / READ_ONLY 直通 /
 * 未知 method / 并发写确认串行化。
 */
class CapabilityDispatchHandlerTest {

    /** 一次确认调用的完整入参。 */
    private data class ConfCall(
        val method: String,
        val risk: CommandRisk,
        val targetCount: Int,
        val previewIds: List<String>,
    )

    /** 带确认计数回读的 fixture（闭包内计数，便于断言）。 */
    private class Probe {
        val dispatched = mutableListOf<AgentCommand>()
        val confirmationArgs = mutableListOf<ConfCall>()

        fun handler(
            answer: suspend () -> Boolean = { true },
            action: AgentAction = AgentAction.TextReply(commandId = 1, message = "done"),
            timeoutMs: Long = CapabilityDispatchHandler.DEFAULT_CONFIRMATION_TIMEOUT_MS,
        ) = CapabilityDispatchHandler(
            dispatch = { command ->
                dispatched.add(command)
                Result.success(action)
            },
            requestConfirmation = { method, risk, count, previewIds ->
                confirmationArgs.add(ConfCall(method, risk, count, previewIds))
                answer()
            },
            confirmationTimeoutMs = timeoutMs,
        )
    }

    private fun argsOf(method: String, params: JsValue? = null): JsValue.Obj = JsValue.Obj(
        linkedMapOf<String, JsValue>(
            "method" to JsValue.Str(method),
            "params" to (params ?: JsValue.Obj(emptyMap())),
        )
    )

    @Test
    fun `READ_ONLY get_gallery_summary dispatches directly without confirmation`() = runTest {
        val probe = Probe()
        val result = probe.handler().invoke(argsOf("get_gallery_summary")) as JsValue.Obj

        assertEquals(1, probe.dispatched.size)
        assertTrue(probe.dispatched[0] is AgentCommand.GetGallerySummary)
        assertEquals(0, probe.confirmationArgs.size)
        assertEquals(true, (result.entries["ok"] as JsValue.Bool).value)
    }

    @Test
    fun `confirmed delete_media dispatches DeleteMedia and returns ok`() = runTest {
        val probe = Probe()
        val params = JsValue.Obj(
            linkedMapOf("ids" to JsValue.Arr(listOf(JsValue.Num(11.0), JsValue.Num(22.0))))
        )
        val result = probe.handler().invoke(argsOf("delete_media", params)) as JsValue.Obj

        val call = probe.confirmationArgs.single()
        assertEquals("delete_media", call.method)
        assertEquals(CommandRisk.DESTRUCTIVE, call.risk)
        assertEquals(2, call.targetCount)
        assertEquals(listOf("11", "22"), call.previewIds)
        val command = probe.dispatched.single() as AgentCommand.DeleteMedia
        assertEquals(listOf("11", "22"), command.mediaIds)
        assertEquals(true, (result.entries["ok"] as JsValue.Bool).value)
        assertEquals("done", (result.entries["message"] as JsValue.Str).value)
    }

    @Test
    fun `delete_media preview ids are capped at MAX_PREVIEW_IDS`() = runTest {
        val probe = Probe()
        val ids = (1L..10L).map { JsValue.Num(it.toDouble()) }
        val params = JsValue.Obj(linkedMapOf("ids" to JsValue.Arr(ids)))
        probe.handler().invoke(argsOf("delete_media", params))

        val call = probe.confirmationArgs.single()
        assertEquals(10, call.targetCount)
        assertEquals(CapabilityDispatchHandler.MAX_PREVIEW_IDS, call.previewIds.size)
    }

    @Test
    fun `rejected write throws and never dispatches`() = runTest {
        val probe = Probe()
        val params = JsValue.Obj(linkedMapOf("ids" to JsValue.Arr(listOf(JsValue.Num(1.0)))))
        try {
            probe.handler(answer = { false }).invoke(argsOf("delete_media", params))
            fail("expected JsBridgeException")
        } catch (e: JsBridgeException) {
            assertTrue(e.message!!.contains("rejected"))
        }
        assertTrue(probe.dispatched.isEmpty())
    }

    @Test
    fun `confirmation timeout is treated as rejection`() = runTest {
        val probe = Probe()
        val params = JsValue.Obj(linkedMapOf("id" to JsValue.Num(7.0), "favorite" to JsValue.Bool(true)))
        try {
            probe.handler(answer = { awaitCancellation() }, timeoutMs = 50).invoke(
                argsOf("favorite_media", params)
            )
            fail("expected JsBridgeException")
        } catch (e: JsBridgeException) {
            assertTrue(e.message!!.contains("timed out") || e.message!!.contains("rejected"))
        }
        assertTrue(probe.dispatched.isEmpty())
    }

    @Test
    fun `unknown method throws without dispatch or confirmation`() = runTest {
        val probe = Probe()
        try {
            probe.handler().invoke(argsOf("hack_everything"))
            fail("expected JsBridgeException")
        } catch (e: JsBridgeException) {
            assertTrue(e.message!!.contains("unsupported method"))
        }
        assertTrue(probe.dispatched.isEmpty())
        assertEquals(0, probe.confirmationArgs.size)
    }

    @Test
    fun `delete_media without ids throws`() = runTest {
        val probe = Probe()
        try {
            probe.handler().invoke(argsOf("delete_media"))
            fail("expected JsBridgeException")
        } catch (e: JsBridgeException) {
            assertTrue(e.message!!.contains("ids"))
        }
        assertTrue(probe.dispatched.isEmpty())
    }

    @Test
    fun `error action from dispatch throws so JS can catch`() = runTest {
        val probe = Probe()
        val params = JsValue.Obj(linkedMapOf("id" to JsValue.Num(3.0), "selected" to JsValue.Bool(true)))
        try {
            probe.handler(
                action = AgentAction.Error(
                    commandId = 1,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "写操作失败",
                )
            ).invoke(argsOf("select_media", params))
            fail("expected JsBridgeException")
        } catch (e: JsBridgeException) {
            assertEquals("写操作失败", e.message)
        }
    }

    @Test
    fun `favorite_media and select_media map params correctly`() = runTest {
        val probe = Probe()
        val handler = probe.handler()
        handler.invoke(
            argsOf(
                "favorite_media",
                JsValue.Obj(linkedMapOf("id" to JsValue.Num(9.0), "favorite" to JsValue.Bool(false))),
            )
        )
        handler.invoke(
            argsOf(
                "select_media",
                JsValue.Obj(linkedMapOf("id" to JsValue.Num(8.0), "selected" to JsValue.Bool(true))),
            )
        )
        val favorite = probe.dispatched[0] as AgentCommand.FavoriteMedia
        assertEquals("9", favorite.mediaId)
        assertEquals(false, favorite.favorite)
        val select = probe.dispatched[1] as AgentCommand.SelectMedia
        assertEquals("8", select.mediaId)
        assertEquals(true, select.selected)
        // 两次均为 REVERSIBLE_WRITE，均需确认，且预览 id 为对应单条
        assertEquals(2, probe.confirmationArgs.size)
        assertTrue(probe.confirmationArgs.all { it.risk == CommandRisk.REVERSIBLE_WRITE })
        assertEquals(listOf("9"), probe.confirmationArgs[0].previewIds)
        assertEquals(listOf("8"), probe.confirmationArgs[1].previewIds)
    }

    @Test
    fun `concurrent write confirmations are serialized and second pops after first`() = runTest {
        val dispatched = mutableListOf<AgentCommand>()
        var confirmationCalls = 0
        var inConfirmation = 0
        var maxConcurrentConfirmations = 0
        val firstConfirmationEntered = CompletableDeferred<Unit>()
        val releaseFirstConfirmation = CompletableDeferred<Unit>()

        val handler = CapabilityDispatchHandler(
            dispatch = { command ->
                dispatched.add(command)
                Result.success(AgentAction.TextReply(commandId = 1, message = "ok"))
            },
            requestConfirmation = { _, _, _, _ ->
                val index = ++confirmationCalls
                inConfirmation++
                maxConcurrentConfirmations = maxOf(maxConcurrentConfirmations, inConfirmation)
                try {
                    if (index == 1) {
                        // 第一个确认挂起（模拟用户尚未点击），验证第二个确认不会并发弹出
                        firstConfirmationEntered.complete(Unit)
                        releaseFirstConfirmation.await()
                    }
                } finally {
                    inConfirmation--
                }
                true
            },
        )

        val deleteArgs = argsOf(
            "delete_media",
            JsValue.Obj(linkedMapOf("ids" to JsValue.Arr(listOf(JsValue.Num(1.0))))),
        )
        val favoriteArgs = argsOf(
            "favorite_media",
            JsValue.Obj(linkedMapOf("id" to JsValue.Num(2.0), "favorite" to JsValue.Bool(true))),
        )
        val first = async { handler.invoke(deleteArgs) }
        val second = async { handler.invoke(favoriteArgs) }

        // 第一个确认已弹出；让第二个 invoke 充分调度——它应阻塞在确认互斥锁上
        firstConfirmationEntered.await()
        runCurrent()
        assertEquals("第二个确认在第一个未决时不应弹出", 1, confirmationCalls)

        // 用户确认第一个 → 第二个确认随后正常弹出，两个 dispatch 都完成
        releaseFirstConfirmation.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, confirmationCalls)
        assertEquals(2, dispatched.size)
        assertEquals("确认框不允许并发弹出", 1, maxConcurrentConfirmations)
    }
}
