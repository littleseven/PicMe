package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.command.CommandRisk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WriteConfirmationController] 单测（纯 JVM）：核心不变式——「脚本已死，确认不再生效」。
 */
class WriteConfirmationControllerTest {

    @Test
    fun `request while script running resolves true on user confirm`() = runTest {
        val controller = WriteConfirmationController()
        controller.onScriptStarted()

        val deferred = async {
            controller.request("delete_media", CommandRisk.DESTRUCTIVE, 2, listOf("u1", "u2"))
        }
        runCurrent()
        val pending = controller.pending.value
        assertNotNull(pending)
        assertEquals("delete_media", pending!!.method)
        assertEquals(CommandRisk.DESTRUCTIVE, pending.risk)
        assertEquals(2, pending.targetCount)
        assertEquals(listOf("u1", "u2"), pending.previewUris)

        controller.resolve(true)
        assertTrue(deferred.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun `user rejection resolves false`() = runTest {
        val controller = WriteConfirmationController()
        controller.onScriptStarted()

        val deferred = async {
            controller.request("select_media", CommandRisk.REVERSIBLE_WRITE, 1, emptyList())
        }
        runCurrent()
        controller.resolve(false)
        assertFalse(deferred.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun `script end rejects in-flight confirmation and clears dialog`() = runTest {
        val controller = WriteConfirmationController()
        controller.onScriptStarted()

        val deferred = async {
            controller.request("delete_media", CommandRisk.DESTRUCTIVE, 3, listOf("u1"))
        }
        runCurrent()
        assertNotNull(controller.pending.value)

        // 模拟 eval 超时/取消后的 finally：脚本已死，在途确认必须失效
        controller.onScriptEnded()
        assertFalse("脚本结束后在途确认应被拒绝（不执行 dispatch）", deferred.await())
        assertNull("弹窗应被清理", controller.pending.value)
    }

    @Test
    fun `request after script ended returns false immediately without dialog`() = runTest {
        val controller = WriteConfirmationController()
        // 从未 onScriptStarted / 或已 onScriptEnded：确认请求直接拒绝，不弹窗
        assertFalse(controller.request("delete_media", CommandRisk.DESTRUCTIVE, 1, emptyList()))
        assertNull(controller.pending.value)
    }

    @Test
    fun `controller works again after a new script starts`() = runTest {
        val controller = WriteConfirmationController()
        controller.onScriptStarted()
        controller.onScriptEnded()
        controller.onScriptStarted()

        val deferred = async {
            controller.request("favorite_media", CommandRisk.REVERSIBLE_WRITE, 1, emptyList())
        }
        runCurrent()
        assertNotNull(controller.pending.value)
        controller.resolve(true)
        assertTrue(deferred.await())
    }
}
