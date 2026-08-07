package com.mamba.picme.domain.search

import com.mamba.picme.domain.model.TimeRange

/**
 * 显式约束过滤条件
 */
data class ExplicitFilter(
    val timeRange: TimeRange? = null,
    val locationKeywords: List<String> = emptyList(),
    val hasFaces: Boolean? = null,
    val personKeywords: List<String> = emptyList()
)
