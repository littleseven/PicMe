package com.mamba.picme.agent.core.model.context

/**
 * 搜索意图标准化模型。
 *
 * 由 LLM 根据用户自然语言生成，与 app 层 [com.mamba.picme.domain.model.StructuredFilter]
 * 字段一一对应，作为命令层到本地检索层的桥梁。
 *
 * 规则：
 * - [query] 必填，保留用户原始查询文本，用于展示与语义召回。
 * - [timeRange] 将“近半年”“去年”等相对时间转换为毫秒时间戳。
 * - [keywords] 为场景/物体/标签关键词，对应本地 tags/labels/mlKitLabels/ocrText/fileName。
 * - [ocrKeywords] 为图片中可能出现的文字。
 * - [locationKeywords] 为地名，对应本地 locationName/GPS 信息。
 * - [personName] 为具体人物名，对应本地人脸分组。
 * - [hasFaces] 为 true 时表示搜索含人脸的照片。
 *
 * 所有字段均为可选；当 [timeRange] 与各关键词都为空时，可退化到 [query] 字符串搜索。
 */
data class SearchIntent(
    val query: String,
    val timeRange: TimeRange? = null,
    val keywords: List<String> = emptyList(),
    val ocrKeywords: List<String> = emptyList(),
    val locationKeywords: List<String> = emptyList(),
    val personName: String? = null,
    val hasFaces: Boolean? = null
)

/**
 * 时间范围（毫秒时间戳）。
 *
 * LLM 在标准化“近半年”“去年”“上个月”等表达时使用当前时间计算得出。
 */
data class TimeRange(
    val startMs: Long,
    val endMs: Long
)
