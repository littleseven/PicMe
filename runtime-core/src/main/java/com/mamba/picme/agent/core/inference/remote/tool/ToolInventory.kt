package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.tool.Tool

/**
 * 从 `@Tool` 注解元数据生成确定性工具清单（system prompt 用）。
 *
 * 此前 chat system prompt 的「可用工具」段为手写，与 `@Tool` 实际表面无校验，
 * 能力增删需手工同步多处、漂移无告警（见 spec：docs/superpowers/specs/
 * 2026-07-29-capability-registry-prompt-observability-refactor.md §5）。
 *
 * 生成规则保证确定性：按工具 name 字典序排序、描述取首句（首个句号截断），
 * 同一输入类产出恒定文本——不破坏远程 prompt 前缀稳定（DeepSeek 上下文缓存）。
 */
object ToolInventory {

    /**
     * 生成 [serviceClass] 全部 `@Tool` 方法的清单段，格式：
     * `可用工具（N）：\n- name: 首句描述\n...`
     */
    fun build(serviceClass: Class<*>): String {
        val tools = serviceClass.declaredMethods
            .mapNotNull { method ->
                method.getAnnotation(Tool::class.java)
                    ?.let { (it.name.ifBlank { method.name }) to it }
            }
            .sortedBy { it.first }
        val lines = tools.joinToString("\n") { (name, tool) -> "- $name: ${firstSentence(tool)}" }
        return "可用工具（${tools.size}）：\n$lines"
    }

    /** 取描述首句：value 多段拼接后取首行，再截到首个句号（含）；无句号则用首行全文。 */
    internal fun firstSentence(tool: Tool): String {
        val firstLine = tool.value.joinToString("\n").lineSequence().first().trim()
        val period = firstLine.indexOf('。')
        return if (period >= 0) firstLine.substring(0, period + 1) else firstLine
    }
}
