package com.mamba.picme.domain.usecase

import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.GalleryQueryResult
import com.mamba.picme.domain.model.QueryFilter
import com.mamba.picme.domain.model.applyFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 只读相册结构化查询，供 JS `gallery.query` handler 使用。
 *
 * - 读-only：不写库、不触发扫描。
 * - 候选集策略（规避全量 MediaEntity 的 OOM 风险——searchByHasFace/getAllMediaNow 已 deprecated）：
 *   - 需字段过滤（label/ocr/location 任一非空）→ 用对应 searchBy* 作主维度（结果已缩小），
 *     再内存 AND 其余维度（applyFilter）。
 *   - 仅时间/hasFace/空过滤 → 走 id-only 查询（searchByTimeRange 取 id / getHasFaceIds / getAllMediaIds）。
 * - [GalleryQueryResult.ids] 截断到 [QueryFilter.limit]；[GalleryQueryResult.total] 为未截断真实命中数。
 */
class QueryGalleryMediaUseCase(
    private val db: AppDatabase,
) {
    suspend operator fun invoke(filter: QueryFilter): GalleryQueryResult =
        withContext(Dispatchers.IO) {
            val needsFieldFilter =
                filter.label != null || filter.ocr != null || filter.location != null

            if (needsFieldFilter) {
                val candidates: List<MediaEntity> = when {
                    filter.label != null -> db.mediaDao().searchByLabel(filter.label)
                    filter.ocr != null -> db.mediaDao().searchByOcrText(filter.ocr)
                    else -> db.mediaDao().searchByLocation(filter.location!!)
                }
                val matched = candidates.applyFilter(filter)
                GalleryQueryResult(
                    ids = matched.take(filter.limit).map { it.id },
                    total = matched.size,
                )
            } else {
                val ids: List<Long> = when {
                    filter.fromMs != null || filter.toMs != null ->
                        db.mediaDao().searchByTimeRange(
                            filter.fromMs ?: 0L,
                            filter.toMs ?: Long.MAX_VALUE,
                        ).map { it.id }
                    filter.hasFace == true -> db.mediaDao().getHasFaceIds()
                    else -> db.mediaDao().getAllMediaIds()
                }
                GalleryQueryResult(ids = ids.take(filter.limit), total = ids.size)
            }
        }

    /** 单张媒体元数据（只读），供 JS `media.meta`。 */
    suspend fun meta(id: Long): MediaEntity? =
        withContext(Dispatchers.IO) { db.mediaDao().getMediaById(id) }

    /**
     * 聚合打标标签的计数分布（标签 → 含该标签的照片数），按计数降序取 top [limit]。
     * 供 JS `gallery.tags`：让 LLM 拿到相册实际有哪些标签，盘点不再瞎猜 label。
     */
    suspend fun tags(limit: Int = 50): Map<String, Int> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<String, Int>()
        db.mediaDao().getAllLabels().forEach { raw ->
            parseLabelArray(raw).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        counts.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
    }

    private fun parseLabelArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}
