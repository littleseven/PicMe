package com.mamba.picme.features.chat

import com.mamba.picme.data.remote.picme.ClaudeEvent
import com.mamba.picme.domain.chat.ClaudeAgentState
import com.mamba.picme.domain.chat.ClaudeStepStatus
import com.mamba.picme.domain.chat.ClaudeStepUi
import org.json.JSONObject

/**
 * ClaudeEvent（spec §6）→ [ClaudeAgentState] 的有状态折叠器。
 *
 * 纯逻辑，单测覆盖（[ClaudeAgentRendererTest]）。ViewModel 持有一个实例，每条事件 [apply]
 * 后取 [state] 写入 ChatMessageUi.claudeAgent，实现文本流式 + 步骤配对 + 文件改动徽标。
 *
 * 折叠规则：
 * - [ClaudeEvent.Session] / [ClaudeEvent.Cost] / [ClaudeEvent.AppToolRequest]：无视觉变化
 *   （sid 由 ViewModel 另存；AppToolRequest 由 Task 7 在 ViewModel 合成 ToolUse/ToolResult 事件）。
 * - [ClaudeEvent.Done]：truncated 时置 [ClaudeAgentState.truncatedReason]（粘滞），否则无变化。
 * - [ClaudeEvent.AssistantText]：delta 追加到 [ClaudeAgentState.text]。
 * - [ClaudeEvent.ToolUse]：追加一步（RUNNING + input 简述）。
 * - [ClaudeEvent.ToolResult]：把最后一个 RUNNING 步骤改为 SUCCESS/FAILED + summary。
 * - [ClaudeEvent.FileChange]：追加一步（SUCCESS + "$action $path"）并置 hasFileChange=true。
 * - [ClaudeEvent.Error]：truncated 时置 truncatedReason（粘滞）；否则把 ⚠️ 提示追加到 [ClaudeAgentState.text]。
 *
 * 数据类（[ClaudeAgentState] / [ClaudeStepUi] / [ClaudeStepStatus]）已下沉 commonMain
 * （`com.mamba.picme.domain.chat`）；org.json 序列化在 [ChatModelCommonMainShim] 扩展。
 */
class ClaudeAgentRenderer {
    var state: ClaudeAgentState = ClaudeAgentState()
        private set

    /** 处理一条事件，更新内部状态并返回更新后的 [state]（便于链式 / 断言）。 */
    fun apply(event: ClaudeEvent): ClaudeAgentState {
        state = fold(state, event)
        return state
    }

    fun reset() {
        state = ClaudeAgentState()
    }

    private fun fold(cur: ClaudeAgentState, event: ClaudeEvent): ClaudeAgentState = when (event) {
        is ClaudeEvent.Session, is ClaudeEvent.Cost, is ClaudeEvent.AppToolRequest -> cur
        is ClaudeEvent.Done -> if (event.truncated && event.reason != null) {
            cur.copy(truncatedReason = event.reason)
        } else {
            cur
        }
        is ClaudeEvent.AssistantText -> cur.copy(text = cur.text + event.delta)
        is ClaudeEvent.ToolUse -> cur.copy(
            steps = cur.steps + ClaudeStepUi(
                tool = event.tool,
                status = ClaudeStepStatus.RUNNING,
                detail = briefInput(event.tool, event.input),
            ),
        )
        is ClaudeEvent.ToolResult -> {
            val idx = cur.steps.indexOfLast { it.status == ClaudeStepStatus.RUNNING }
            if (idx < 0) {
                cur
            } else {
                cur.copy(
                    steps = cur.steps.toMutableList().apply {
                        this[idx] = this[idx].copy(
                            status = if (event.ok) ClaudeStepStatus.SUCCESS else ClaudeStepStatus.FAILED,
                            detail = event.summary,
                        )
                    },
                )
            }
        }
        is ClaudeEvent.FileChange -> cur.copy(
            steps = cur.steps + ClaudeStepUi(
                tool = FILE_CHANGE_TOOL,
                status = ClaudeStepStatus.SUCCESS,
                detail = "${event.action} ${event.path}",
            ),
            hasFileChange = true,
        )
        is ClaudeEvent.Error -> if (event.truncated && event.reason != null) {
            cur.copy(truncatedReason = event.reason)
        } else {
            val prefix = if (cur.text.isBlank()) "" else "\n"
            cur.copy(text = "${cur.text}${prefix}⚠️ ${event.message}")
        }
    }

    companion object {
        /** file_change 步骤的 tool 标记（UI 据此本地化为「改文件」标签 + 文件图标）。 */
        const val FILE_CHANGE_TOOL = "file_change"

        /**
         * 从 tool_use input 提取最相关的简述（命令/文件路径等数据，非本地化文案）。
         * 未识别字段时回退为截断的 JSON，避免把整个 input 灌进气泡。
         */
        fun briefInput(tool: String, input: JSONObject): String {
            val cmd = input.optString("command").takeIf { it.isNotBlank() }
            if (cmd != null) return cmd
            val path = input.optString("file_path").takeIf { it.isNotBlank() }
            if (path != null) return path
            val pattern = input.optString("pattern").takeIf { it.isNotBlank() }
            if (pattern != null) return pattern
            val str = input.toString()
            return if (str.length <= MAX_INPUT_DETAIL) str else str.substring(0, MAX_INPUT_DETAIL) + "…"
        }

        private const val MAX_INPUT_DETAIL = 80
    }
}
