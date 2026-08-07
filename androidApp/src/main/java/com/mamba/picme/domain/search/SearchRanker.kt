package com.mamba.picme.domain.search

import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.StructuredFilter

/**
 * 搜索结果排序器
 *
 * 对多维度查询结果进行加权评分和排序。
 *
 * 评分策略：
 * - 标签精确匹配: 1.0
 * - OCR 词汇精确匹配: 0.9
 * - 地点精确匹配: 0.8
 * - 标签模糊匹配: 0.7
 * - OCR 词汇前缀匹配: 0.6
 * - 地点模糊匹配: 0.5
 * - 文件名匹配: 0.4
 *
 * Boosts：
 * - 多维度命中：score *= (1 + 0.2 * N)
 * - 时间衰减：最近 30 天的照片 +0.1
 */
class SearchRanker {

    /**
     * 对搜索结果排序并打分。
     *
     * @param results 各维度查询结果（MediaEntity → 命中维度名列表）
     * @param filter 原始过滤条件（用于额外 Boost）
     * @return 按分数降序排列的 ScoredMedia 列表
     */
    fun rank(
        results: Map<MediaEntity, Set<String>>,
        filter: StructuredFilter
    ): List<ScoredMedia> {
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 3600 * 1000

        return results.entries.map { (media, dimensions) ->
            var score = 0f

            for (dim in dimensions) {
                score += when {
                    dim.startsWith("tag_exact:") -> 1.0f
                    dim.startsWith("ocr_exact:") -> 0.9f
                    dim.startsWith("loc_exact:") -> 0.8f
                    dim.startsWith("tag:") -> 0.7f
                    dim.startsWith("ocr:") -> 0.6f
                    dim.startsWith("loc:") -> 0.5f
                    dim == "file_name" -> 0.4f
                    dim == "time_range" -> 0.3f
                    dim == "has_face" -> 0.5f
                    else -> 0.3f
                }
            }

            // 多维度 Boost
            if (dimensions.size > 1) {
                score *= (1f + 0.2f * dimensions.size)
            }

            // 时间衰减 Boost：最近照片加分
            if (now - media.captureDate < thirtyDaysMs) {
                score += 0.1f
            }

            ScoredMedia(media = media, score = score, matchDimensions = dimensions.toList())
        }.sortedByDescending { it.score }
    }
}

data class ScoredMedia(
    val media: MediaEntity,
    val score: Float,
    val matchDimensions: List<String>
)
