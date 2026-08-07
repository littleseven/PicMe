package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool as KoogTool
import java.lang.reflect.Method

/**
 * 从 Koog `@Tool` 注解元数据生成确定性工具清单（system prompt 用）。
 *
 * 此前 chat system prompt 的「可用工具」段为手写，与 `@Tool` 实际表面无校验，
 * 能力增删需手工同步多处、漂移无告警（见 spec：docs/superpowers/specs/
 * 2026-07-29-capability-registry-prompt-observability-refactor.md §5）。
 *
 * 生成规则保证确定性：按工具 name 字典序排序、描述取首句（首个句号截断），
 * 同一输入类产出恒定文本——不破坏远程 prompt 前缀稳定（DeepSeek 上下文缓存）。
 *
 * **仅 Koog 注解扫描（langchain4j 分支已随 `:agent-core` 在 Phase 6 移除）**：
 * [KoogTool]（`ai.koog...annotations.Tool`）+ [LLMDescription]——chat（[ChatToolService]）+
 * 相机/飞书（[CameraToolService] / [RemoteControlToolService]）。工具名取 `@Tool.customName`
 * （保 LLM-facing 蛇形名确定性），描述取方法级 `@LLMDescription.value`。
 */
object ToolInventory {

    /**
     * 生成 [serviceClass] 全部 `@Tool` 方法的清单段，格式：
     * `可用工具（N）：\n- name: 首句描述\n...`
     */
    fun build(serviceClass: Class<*>): String {
        val tools = serviceClass.declaredMethods
            .mapNotNull { method -> describeTool(method) }
            .sortedBy { it.first }
        val lines = tools.joinToString("\n") { (name, desc) -> "- $name: $desc" }
        return "可用工具（${tools.size}）：\n$lines"
    }

    /** 返回 (name, 首句描述) 或 null（方法无 Koog `@Tool`）。 */
    private fun describeTool(method: Method): Pair<String, String>? {
        method.getAnnotation(KoogTool::class.java)?.let { tool ->
            val name = tool.customName.ifBlank { method.name }
            val description = method.getAnnotation(LLMDescription::class.java)?.value ?: ""
            return name to firstSentence(description)
        }
        return null
    }

    /** 取描述首句：首个非空行截到首个句号（含）；无句号则用首行全文。 */
    internal fun firstSentence(text: String): String {
        val firstLine = text.lineSequence().first().trim()
        val period = firstLine.indexOf('。')
        return if (period >= 0) firstLine.substring(0, period + 1) else firstLine
    }
}
