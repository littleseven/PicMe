package com.mamba.picme.agent.core.inference.remote.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ToolInventory 清单段格式化单测（KMP 纯逻辑，synthetic ToolDescriptor）。
 *
 * 覆盖：name 字典序排序、描述首句截断（首个句号含）、计数头格式、同输入恒定输出。
 * 真实服务（ChatToolService/CameraToolService）的逐字节防漂移守卫见
 * jvmTest `ToolPromptDeterminismTest`（golden 比对）。
 */
class ToolInventoryTest {

    @Test
    fun `build sorts by name and truncates description to first sentence`() {
        val descriptors = listOf(
            descriptor("b_tool", "第二工具。更多细节忽略"),
            descriptor("a_tool", "首行即描述\n第二行忽略"),
            descriptor("c_tool", "未命名工具。"),
        )

        val output = ToolInventory.build(descriptors)

        val lines = output.lineSequence().filter { it.startsWith("- ") }.toList()
        assertEquals(3, lines.size)
        assertEquals("- a_tool: 首行即描述", lines[0])
        assertEquals("- b_tool: 第二工具。", lines[1])
        assertEquals("- c_tool: 未命名工具。", lines[2])
        assertTrue(output.startsWith("可用工具（3）："))
    }

    @Test
    fun `build output is deterministic for same input`() {
        val descriptors = listOf(
            descriptor("x", "甲。乙"),
            descriptor("y", "丙"),
        )
        assertEquals(ToolInventory.build(descriptors), ToolInventory.build(descriptors))
    }

    @Test
    fun `firstSentence edge cases`() {
        assertEquals("无句号整行", ToolInventory.firstSentence("无句号整行"))
        assertEquals("首句。", ToolInventory.firstSentence("首句。次句。"))
        assertEquals("首行。", ToolInventory.firstSentence("首行。\n次行忽略"))
        // 实现取「首行」而非「首个非空行」（golden 已钉死此行为）：首行为空则返回空
        assertEquals("", ToolInventory.firstSentence("\n首行。"))
        assertEquals("", ToolInventory.firstSentence(""))
    }

    private fun descriptor(name: String, description: String) =
        ai.koog.agents.core.tools.ToolDescriptor(name = name, description = description)
}
