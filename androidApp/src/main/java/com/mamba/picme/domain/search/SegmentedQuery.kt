package com.mamba.picme.domain.search

/**
 * 分段后的查询
 */
data class SegmentedQuery(
    val original: String,
    val segments: List<Segment>
) {
    val explicitSegments: List<Segment> =
        segments.filter { it.type.isExplicit }

    val contentSegments: List<Segment> =
        segments.filter { it.type.isContent }

    val hasExplicit: Boolean =
        explicitSegments.isNotEmpty()

    /**
     * 是否含"收窄型"显式约束（时间 / 地点）。
     *
     * 与 [hasExplicit] 的区别：纯人物词（如"小孩"）虽属 explicit 段，但本质是概念查询，
     * 应依赖 MobileCLIP 语义召回；若据 [hasExplicit] 触发 Layer 0.5 短路，会跳过语义召回、
     * 只返回"有人脸 ∩ 被显式打 child 标签"的少量结果（典型表现：搜"小孩"只剩 1 张）。
     * 仅当存在时间/地点这类真正的候选集收窄约束时，才适合 explicit-first 短路。
     */
    val hasNarrowingExplicit: Boolean =
        segments.any { it.type == SegmentType.TIME || it.type == SegmentType.LOCATION }

    val hasContent: Boolean =
        contentSegments.isNotEmpty()

    val isEmpty: Boolean =
        segments.isEmpty() || segments.all { it.type == SegmentType.UNKNOWN }
}
