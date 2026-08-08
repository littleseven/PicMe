package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.reflect.asToolsByClass
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具清单 prompt 确定性护栏（KMP 抽取 Task 7，防 suspend 化/去反射改写漂移）。
 *
 * golden 文本在改写**前**由旧 java 反射版 `ToolInventory.build(Class)` 抓取
 *（chat 35 工具 / camera 13 工具），本测试断言改写后的新链路输出**逐字节一致**：
 * - suspend 化不得触碰 @Tool/@LLMDescription 注解文本；
 * - `reflect.ToolSet` 标记接口移除后，`asToolsByClass()` 与旧 `ToolSet.asTools()` 是同一
 *   扫描函数（Koog 1.1.1 源码核实），ToolDescriptor.name/description 须等于旧反射读取值；
 * - commonMain `ToolInventory` 的排序/首句截断/计数头格式不变。
 *
 * 位置说明：计划原写 commonTest，但 @Tool 元数据的反射展开是 JVM-only（Koog reflect 包
 * 无 common 版本），只能在 jvmTest 跑；纯格式化的 KMP 部分由 commonTest `ToolInventoryTest` 覆盖。
 */
class ToolPromptDeterminismTest {

    @Test
    fun `chat tool inventory matches golden byte for byte`() {
        val actual = ToolInventory.build(
            ChatToolService.getInstance().asToolsByClass().map { it.descriptor }
        )
        assertEquals(golden("chat_tool_inventory_golden.txt"), actual)
    }

    @Test
    fun `camera tool inventory matches golden byte for byte`() {
        val actual = ToolInventory.build(
            CameraToolService.getInstance().asToolsByClass().map { it.descriptor }
        )
        assertEquals(golden("camera_tool_inventory_golden.txt"), actual)
    }

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/golden/$name")) { "golden 资源缺失：$name" }
            .readText(Charsets.UTF_8)
}
