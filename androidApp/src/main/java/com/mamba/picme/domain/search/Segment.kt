package com.mamba.picme.domain.search

/**
 * 查询中的单个语义段
 */
data class Segment(
    val type: SegmentType,
    val text: String,
    val confidence: Float = 1.0f
)
