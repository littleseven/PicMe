package com.mamba.picme.domain.search

/**
 * 内容检索条件
 */
data class ContentFilter(
    val keywords: List<String> = emptyList(),
    val ocrKeywords: List<String> = emptyList(),
    val semanticQuery: String? = null
) {
    fun isEmpty(): Boolean = keywords.isEmpty() && ocrKeywords.isEmpty()
}
