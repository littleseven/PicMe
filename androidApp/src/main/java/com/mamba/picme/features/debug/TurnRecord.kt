package com.mamba.picme.features.debug

import com.mamba.picme.data.local.llmlog.JsRunLogEntity
import com.mamba.picme.data.local.llmlog.LlmCallLogEntity
import com.mamba.picme.data.local.llmlog.ToolCallLogEntity

/** turn pager 的层级标签。 */
enum class TurnKind { LLM, TOOL, JS }

/**
 * 同一 traceId 的三层记录统一投影，供详情页 [HorizontalPager] 按时间顺序横滑浏览。
 */
sealed class TurnRecordItem {
    abstract val createdAt: Long

    data class Llm(val entity: LlmCallLogEntity) : TurnRecordItem() {
        override val createdAt: Long get() = entity.createdAt
    }

    data class Tool(val entity: ToolCallLogEntity) : TurnRecordItem() {
        override val createdAt: Long get() = entity.createdAt
    }

    data class Js(val entity: JsRunLogEntity) : TurnRecordItem() {
        override val createdAt: Long get() = entity.createdAt
    }
}

/**
 * 合并同一 traceId 的三层记录，按 [TurnRecordItem.createdAt] 升序排列。纯函数，便于单测。
 */
fun mergeTurnRecords(
    llm: List<LlmCallLogEntity>,
    tool: List<ToolCallLogEntity>,
    js: List<JsRunLogEntity>
): List<TurnRecordItem> = (
    llm.map { TurnRecordItem.Llm(it) } +
        tool.map { TurnRecordItem.Tool(it) } +
        js.map { TurnRecordItem.Js(it) }
).sortedBy { it.createdAt }

/** 统计合并后各层级的条数，供详情页指示器展示。 */
fun countByKind(items: List<TurnRecordItem>): Map<TurnKind, Int> = buildMap {
    put(TurnKind.LLM, items.count { it is TurnRecordItem.Llm })
    put(TurnKind.TOOL, items.count { it is TurnRecordItem.Tool })
    put(TurnKind.JS, items.count { it is TurnRecordItem.Js })
}
