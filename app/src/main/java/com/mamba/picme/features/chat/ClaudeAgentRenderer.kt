package com.mamba.picme.features.chat

import com.mamba.picme.data.remote.picme.ClaudeEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * claude-tunnel agent 气泡的可变状态（spec §6 事件折叠产物，§7.4 渲染数据）。
 *
 * 纯 Kotlin 数据类型，便于单测与 Room 持久化（[toJson] / [fromJson]）。
 * UI（ChatScreen）把它挂到 [ChatMessageUi.claudeAgent] inline 渲染，复用 diag 的内嵌字段套路。
 *
 * @property text assistant_text delta 累积的流式文本。
 * @property steps tool_use↔tool_result 配对的步骤列表；file_change 也记为一步。
 * @property hasFileChange 是否出现过 file_change（决定是否显示「交付」按钮，§8）。
 */
data class ClaudeAgentState(
    val text: String = "",
    val steps: List<ClaudeStepUi> = emptyList(),
    val hasFileChange: Boolean = false,
) {
    /** 序列化为 Room metadata JSON（气泡跨重载/重启保留）。 */
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in steps) {
            arr.put(
                JSONObject()
                    .put("tool", s.tool)
                    .put("status", s.status.name)
                    .put("detail", s.detail),
            )
        }
        return JSONObject()
            .put("text", text)
            .put("steps", arr)
            .put("hasFileChange", hasFileChange)
    }

    companion object {
        fun fromJson(obj: JSONObject): ClaudeAgentState {
            val arr = obj.optJSONArray("steps")
            val steps = mutableListOf<ClaudeStepUi>()
            for (i in 0 until (arr?.length() ?: 0)) {
                val s = arr!!.getJSONObject(i)
                steps += ClaudeStepUi(
                    tool = s.optString("tool"),
                    status = runCatching { ClaudeStepStatus.valueOf(s.optString("status")) }
                        .getOrDefault(ClaudeStepStatus.RUNNING),
                    detail = s.optString("detail"),
                )
            }
            return ClaudeAgentState(
                text = obj.optString("text"),
                steps = steps,
                hasFileChange = obj.optBoolean("hasFileChange", false),
            )
        }
    }
}

/** agent 气泡里的一步（工具调用 / 文件改动）的状态。 */
data class ClaudeStepUi(
    val tool: String,
    val status: ClaudeStepStatus,
    val detail: String,
)

enum class ClaudeStepStatus { RUNNING, SUCCESS, FAILED }

/**
 * claude 交付按钮状态（镜像 [DiagConfirmUi]）。pending=true 显示按钮；交付完成后置 false。
 * gateway MVP 仅支持 push（§8 + README：pr/auto 二期）。
 */
data class ClaudeDeliverUi(val sid: String, val pending: Boolean)

/**
 * ClaudeEvent（spec §6）→ [ClaudeAgentState] 的有状态折叠器。
 *
 * 纯逻辑，单测覆盖（[ClaudeAgentRendererTest]）。ViewModel 持有一个实例，每条事件 [apply]
 * 后取 [state] 写入 ChatMessageUi.claudeAgent，实现文本流式 + 步骤配对 + 文件改动徽标。
 *
 * 折叠规则：
 * - [ClaudeEvent.Session] / [ClaudeEvent.Done] / [ClaudeEvent.Cost]：无视觉变化（sid 由 ViewModel 另存）。
 * - [ClaudeEvent.AssistantText]：delta 追加到 [ClaudeAgentState.text]。
 * - [ClaudeEvent.ToolUse]：追加一步（RUNNING + input 简述）。
 * - [ClaudeEvent.ToolResult]：把最后一个 RUNNING 步骤改为 SUCCESS/FAILED + summary。
 * - [ClaudeEvent.FileChange]：追加一步（SUCCESS + "$action $path"）并置 hasFileChange=true。
 * - [ClaudeEvent.Error]：把 ⚠️ 提示追加到 [ClaudeAgentState.text]。
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
        is ClaudeEvent.Session, ClaudeEvent.Done, is ClaudeEvent.Cost -> cur
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
        is ClaudeEvent.Error -> {
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
