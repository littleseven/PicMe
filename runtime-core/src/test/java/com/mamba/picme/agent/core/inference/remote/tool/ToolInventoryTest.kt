package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.annotations.Tool as KoogTool
import ai.koog.agents.core.tools.reflect.asToolsByClass
import com.mamba.picme.agent.core.inference.remote.RemoteChatEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具清单生成与 prompt 一致性单测（spec §5 Phase 2）。
 *
 * 门禁：chat 远程 agent 的 system prompt 必须覆盖 [ChatToolService] 的全部 @Tool，
 * 手写清单漂移（漏加/漏删工具）在此 fail-fast。
 *
 * Task 7 变更：`ToolInventory.build` 改收 Koog `ToolDescriptor` 列表（去 java 反射），
 * 反射展开经 `asToolsByClass()`（与旧 `reflect.ToolSet` 扫描同一函数）；排序/首句截断的
 * 格式化单测已迁 :shared commonTest `ToolInventoryTest`，逐字节防漂移护栏见 :shared
 * jvmTest `ToolPromptDeterminismTest`。
 */
class ToolInventoryTest {

    @Test
    fun `inventory contains every @Tool of ChatToolService`() {
        val toolNames = ChatToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }
        assertTrue("ChatToolService 应暴露多个 @Tool", toolNames.size > 20)

        val inventory = chatInventory()
        val missing = toolNames.filter { !inventory.contains("- $it:") }
        assertEquals("工具清单遗漏：$missing", emptyList<String>(), missing)
    }

    @Test
    fun `chat system prompt covers every @Tool of ChatToolService`() {
        val prompt = RemoteChatEngine.chatSystemPrompt
        val toolNames = ChatToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }

        val missing = toolNames.filter { !prompt.contains(it) }
        assertEquals("system prompt 未覆盖工具：$missing", emptyList<String>(), missing)
    }

    @Test
    fun `inventory output is deterministic`() {
        assertEquals(chatInventory(), chatInventory())
    }

    @Test
    fun `chat system prompt has no indented lines`() {
        // 回归：raw string 内插值零缩进行会让 trimIndent 失效、手写段残留前导空格
        val indented = RemoteChatEngine.chatSystemPrompt.lines()
            .filter { it.startsWith(" ") || it.startsWith("\t") }
        assertEquals("system prompt 存在前导缩进行：$indented", emptyList<String>(), indented)
    }

    private fun chatInventory(): String = ToolInventory.build(
        ChatToolService.getInstance().asToolsByClass().map { it.descriptor }
    )
}
