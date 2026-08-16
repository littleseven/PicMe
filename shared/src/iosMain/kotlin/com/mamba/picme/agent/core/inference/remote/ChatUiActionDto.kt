package com.mamba.picme.agent.core.inference.remote

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction

/**
 * Swift 安全的 UI 动作 DTO（Phase 6.2 T5）。
 *
 * [AgentAction] 是 sealed class，经 K/N 导出到 Swift 后 `when` 穷尽检查在 ObjC 桥接层
 * 失效（Swift 只看到 ObjC 协议，不能 switch sealed）。本 DTO 将 iOS chat 关心的 4 种
 * action 扁平为单一 data class + kind 标记，Swift 直接按 `kind` 字段 switch。
 *
 * [PRIVACY] 红线：只携带计数、查询词、mediaIds（与 Android 同口径）；
 * **不含**文件路径、GPS、base64、缩略图数据——Swift 侧用 mediaIds 经
 * `IosMediaRepositoryBridge.fetchAllMedia()` 本地取图，纯端侧渲染。
 *
 * @property kind 动作类型："media_results" | "text_reply" | "success" | "error"
 * @property message 文本内容（TextReply.message / Error.message / Success.command 的 method 名 / 空串）
 * @property query 搜索关键词（仅 media_results 有值）
 * @property totalCount 命中数量（仅 media_results）
 * @property mediaIds 命中媒体 id 列表（仅 media_results，用于 Swift 取缩略图）
 */
data class ChatUiActionDto(
    val kind: String,
    val message: String = "",
    val query: String = "",
    val totalCount: Long = 0,
    val mediaIds: List<Long> = emptyList()
) {

    companion object {
        private const val KIND_MEDIA_RESULTS = "media_results"
        private const val KIND_TEXT_REPLY = "text_reply"
        private const val KIND_SUCCESS = "success"
        private const val KIND_ERROR = "error"

        /**
         * 从 [AgentAction]（[ChatToolService.uiActions] 产出）转换。
         * 不关心的 action 子类型返回 null（Swift 不消费）。
         */
        fun from(action: AgentAction): ChatUiActionDto? = when (action) {
            is AgentAction.MediaResults -> ChatUiActionDto(
                kind = KIND_MEDIA_RESULTS,
                query = action.query,
                totalCount = action.totalCount.toLong(),
                mediaIds = action.mediaIds
            )
            is AgentAction.TextReply -> ChatUiActionDto(
                kind = KIND_TEXT_REPLY,
                message = action.message
            )
            is AgentAction.Success -> ChatUiActionDto(
                // command 的 method 名（如 "navigate_to"），Swift 端映射「✅ 已执行 …」可见反馈
                // （对齐 Android describeCommandResult 兜底分支）
                kind = KIND_SUCCESS,
                message = AgentCommand.getMethodName(action.command)
            )
            is AgentAction.Error -> ChatUiActionDto(
                kind = KIND_ERROR,
                message = action.message
            )
            is AgentAction.BatchResult -> null
        }
    }
}
