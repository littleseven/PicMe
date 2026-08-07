package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.annotations.Tool as KoogTool
import com.mamba.picme.agent.core.inference.remote.RemoteChatEngine
import com.mamba.tool.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具清单生成与 prompt 一致性单测（spec §5 Phase 2）。
 *
 * 门禁：chat 远程 agent 的 system prompt 必须覆盖 [ChatToolService] 的全部 @Tool，
 * 手写清单漂移（漏加/漏删工具）在此 fail-fast。
 */
class ToolInventoryTest {

    @Suppress("unused")
    private class Fixture {
        @Tool(name = "b_tool", value = ["第二工具。更多细节忽略"])
        fun b(): String = ""

        @Tool(name = "a_tool", value = ["首行即描述", "第二行忽略"])
        fun a(): String = ""

        @Tool(value = ["未命名工具。"])
        fun c_tool(): String = ""

        fun notATool(): String = ""
    }

    @Test
    fun `build sorts by name and truncates description to first sentence`() {
        val output = ToolInventory.build(Fixture::class.java)

        val lines = output.lineSequence().filter { it.startsWith("- ") }.toList()
        assertEquals(3, lines.size)
        assertEquals("- a_tool: 首行即描述", lines[0])
        assertEquals("- b_tool: 第二工具。", lines[1])
        // name 缺省回退方法名
        assertEquals("- c_tool: 未命名工具。", lines[2])
        assertTrue(output.startsWith("可用工具（3）："))
    }

    @Test
    fun `inventory contains every @Tool of ChatToolService`() {
        // Phase 4：ChatToolService 已迁到 Koog @Tool（customName 保蛇形 LLM-facing 名），
        // 扫 KoogTool.customName（与 ToolInventory.build 的 Koog 扫描分支一致）。
        val toolNames = ChatToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }
        assertTrue("ChatToolService 应暴露多个 @Tool", toolNames.size > 20)

        val inventory = ToolInventory.build(ChatToolService::class.java)
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
        assertEquals(
            ToolInventory.build(ChatToolService::class.java),
            ToolInventory.build(ChatToolService::class.java)
        )
    }

    @Test
    fun `chat system prompt has no indented lines`() {
        // 回归：raw string 内插值零缩进行会让 trimIndent 失效、手写段残留前导空格
        val indented = RemoteChatEngine.chatSystemPrompt.lines()
            .filter { it.startsWith(" ") || it.startsWith("\t") }
        assertEquals("system prompt 存在前导缩进行：$indented", emptyList<String>(), indented)
    }
}
