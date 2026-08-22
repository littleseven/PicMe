package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.ToolDescriptor

/**
 * 从 Koog 工具描述元数据生成确定性工具清单（system prompt 用）。
 *
 * 此前 chat system prompt 的「可用工具」段为手写，与 `@Tool` 实际表面无校验，
 * 能力增删需手工同步多处、漂移无告警（2026-07-29 capability-registry 重构
 * 落地，设计稿已随交付清理）。
 *
 * 生成规则保证确定性：按工具 name 字典序排序、描述取首句（首个句号截断），
 * 同一输入产出恒定文本——不破坏远程 prompt 前缀稳定（DeepSeek 上下文缓存）。
 *
 * **去反射（KMP 抽取 Task 7）**：旧实现用 `java.lang.reflect.Method` 扫描 @Tool 注解，
 * commonMain 不可用。改为消费 Koog KMP 类型 [ToolDescriptor]（name + description）——
 * 由组合根（Android/JVM 侧）经 Koog 反射展开服务实例（`asToolsByClass()`，与旧
 * `reflect.ToolSet.asTools()` 同一扫描函数）后取 `tool.descriptor` 传入；chat
 *（[ChatToolService]）+ 相机/飞书（[CameraToolService] / [RemoteControlToolService]）
 * 均走此路径。逐字节等价性由 `ToolPromptDeterminismTest`（shared jvmTest，golden 比对）守卫。
 */
object ToolInventory {

    /**
     * 生成 [descriptors] 全部工具的清单段，格式：
     * `可用工具（N）：\n- name: 首句描述\n...`
     */
    fun build(descriptors: List<ToolDescriptor>): String {
        val tools = descriptors
            .map { it.name to firstSentence(it.description) }
            .sortedBy { it.first }
        val lines = tools.joinToString("\n") { (name, desc) -> "- $name: $desc" }
        return "可用工具（${tools.size}）：\n$lines"
    }

    /** 取描述首句：首个非空行截到首个句号（含）；无句号则用首行全文。 */
    internal fun firstSentence(text: String): String {
        val firstLine = text.lineSequence().first().trim()
        val period = firstLine.indexOf('。')
        return if (period >= 0) firstLine.substring(0, period + 1) else firstLine
    }
}
