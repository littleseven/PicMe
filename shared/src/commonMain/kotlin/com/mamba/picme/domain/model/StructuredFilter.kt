package com.mamba.picme.domain.model

/**
 * 结构化查询过滤条件 —— LLM 意图到 Room 查询的中间表示
 *
 * 由 [QueryParser]（规则引擎）或 LLM（远程模型）生成，
 * 经 [com.mamba.picme.domain.search.QueryBuilder] 转换为跨维度的 Room DAO 查询。
 */
data class StructuredFilter(
    /** 时间范围（毫秒时间戳） */
    val timeRange: TimeRange? = null,
    /** 通用关键词（匹配标签、文件名） */
    val keywords: List<String> = emptyList(),
    /** OCR 文字关键词 */
    val ocrKeywords: List<String> = emptyList(),
    /** 地点关键词 */
    val locationKeywords: List<String> = emptyList(),
    /** 人物名称 */
    val personName: String? = null,
    /** 是否包含人脸 */
    val hasFaces: Boolean? = null,
    /** 是否需要 LLM 兜底（规则引擎无法解析时） */
    val needsLlm: Boolean = false
)

data class TimeRange(
    val startMs: Long,
    val endMs: Long
)
