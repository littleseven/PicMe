package com.mamba.picme.domain.search

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.TagTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 跨维度查询构建器
 *
 * 将 [StructuredFilter] 转换为跨标签/OCR/地点/时间/人脸等维度的并发 Room 查询，
 * 合并去重后交由 [SearchRanker] 排序。
 *
 * 这是 Agent 搜索桥接的核心：LLM 意图 → StructuredFilter → QueryBuilder → 排序结果。
 *
 * 支持跨语言搜索：通过 [TagTranslator] 把英文查询扩展为中文 canonical 词，命中已有中文 TAG。
 */
class QueryBuilder(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao,
    private val ocrWordDao: OcrWordDao,
    private val locationDao: LocationDao,
    private val personDao: PersonDao? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty())
) {
    companion object {
        private const val TAG = "PoLang:QueryBuilder"
    }

    private val ranker = SearchRanker()

    /**
     * 执行跨维度搜索。
     *
     * 策略：
     * 1. 为 filter 中每个非空维度生成子查询
     * 2. 子查询并发执行（coroutineScope async）
     * 3. 按 MediaEntity 合并结果，每个 entity 记录命中的维度名集合
     * 4. 应用时间范围过滤
     * 5. 排序返回
     */
    suspend fun search(filter: StructuredFilter): List<ScoredMedia> = coroutineScope {
        val deferredQueries = mutableListOf<kotlinx.coroutines.Deferred<List<MediaEntity>>>()
        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

        addTagQueries(filter.keywords, uiLang, deferredQueries)
        addOcrQueries(filter.ocrKeywords, deferredQueries)
        addLegacyLikeQueries(filter.keywords + filter.ocrKeywords, uiLang, deferredQueries)
        addLocationQueries(filter.locationKeywords, deferredQueries)
        addPersonQuery(filter.personName, deferredQueries)
        addFaceFilterQuery(filter.hasFaces, deferredQueries)
        addTimeRangeQuery(filter.timeRange, deferredQueries)

        val allResults = awaitAllQueries(deferredQueries)
        val merged = mergeResults(allResults)
        val filtered = applyTimeFilter(merged, filter.timeRange)

        Logger.i(TAG, "Query complete: ${allResults.size} raw, ${merged.size} unique, " +
            "${filtered.size} after time filter")

        ranker.rank(results = filtered, filter = filter)
    }

    private fun CoroutineScope.addTagQueries(
        keywords: List<String>,
        uiLang: AppLanguage,
        deferredQueries: MutableList<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ) {
        for (keyword in keywords) {
            for (candidate in tagTranslator.expandForSearch(keyword, uiLang)) {
                deferredQueries += async {
                    tagDao.searchByExactTag(candidate).also {
                        if (it.isNotEmpty()) Logger.d(TAG, "Tag exact match '$candidate': ${it.size}")
                    }
                }
                deferredQueries += async {
                    tagDao.searchByTagName(candidate).also {
                        if (it.isNotEmpty()) Logger.d(TAG, "Tag fuzzy match '$candidate': ${it.size}")
                    }
                }
            }
        }
    }

    private fun CoroutineScope.addOcrQueries(
        ocrKeywords: List<String>,
        deferredQueries: MutableList<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ) {
        for (keyword in ocrKeywords) {
            val normalized = keyword.lowercase().trim()
            deferredQueries += async {
                ocrWordDao.searchByExactWord(normalized).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "OCR exact match '$normalized': ${it.size}")
                }
            }
            deferredQueries += async {
                ocrWordDao.searchByWordPrefix(normalized).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "OCR prefix match '$normalized': ${it.size}")
                }
            }
        }
    }

    private fun CoroutineScope.addLegacyLikeQueries(
        keywords: List<String>,
        uiLang: AppLanguage,
        deferredQueries: MutableList<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ) {
        for (keyword in keywords) {
            for (candidate in tagTranslator.expandForSearch(keyword, uiLang)) {
                deferredQueries += async { mediaDao.searchByOcrText(candidate) }
                deferredQueries += async { mediaDao.searchByLabel(candidate) }
            }
        }
    }

    private fun CoroutineScope.addLocationQueries(
        locationKeywords: List<String>,
        deferredQueries: MutableList<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ) {
        for (keyword in locationKeywords) {
            deferredQueries += async {
                locationDao.searchByPlace(keyword).also {
                    if (it.isNotEmpty()) Logger.d(TAG, "Location match '$keyword': ${it.size}")
                }
            }
            deferredQueries += async { mediaDao.searchByLocation(keyword) }
        }
    }

    private fun CoroutineScope.addPersonQuery(
        personName: String?,
        deferredQueries: MutableList<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ) {
        if (personName.isNullOrBlank()) return
        personDao?.let { dao ->
            deferredQueries += async {
                dao.findPersonByName(personName)?.let { person ->
                    dao.getMediaByPerson(person.personId).also {
                        if (it.isNotEmpty()) Logger.d(TAG, "Person match '$personName': ${it.size}")
                    }
                } ?: emptyList()
            }
        }
        deferredQueries += async { mediaDao.searchByFileName(personName) }
    }

    private fun CoroutineScope.addFaceFilterQuery(
        hasFaces: Boolean?,
        deferredQueries: MutableList<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ) {
        if (hasFaces == true) {
            deferredQueries += async {
                // 使用 ID-based 方法避免 OOM
                val ids = mediaDao.getHasFaceIds()
                if (ids.isNotEmpty()) mediaDao.getMediaByIds(ids) else emptyList()
            }
        }
    }

    private fun CoroutineScope.addTimeRangeQuery(
        timeRange: com.mamba.picme.domain.model.TimeRange?,
        deferredQueries: MutableList<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ) {
        if (timeRange != null) {
            deferredQueries += async {
                mediaDao.searchByTimeRange(timeRange.startMs, timeRange.endMs)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitAllQueries(
        deferredQueries: List<kotlinx.coroutines.Deferred<List<MediaEntity>>>
    ): List<MediaEntity> = deferredQueries.map { deferred ->
        try {
            deferred.await()
        } catch (e: Exception) {
            Logger.w(TAG, "Query failed", e)
            emptyList()
        }
    }.flatten()

    private fun mergeResults(
        allResults: List<MediaEntity>
    ): Map<MediaEntity, Set<String>> {
        val merged = mutableMapOf<MediaEntity, MutableSet<String>>()
        for (entity in allResults) {
            merged.getOrPut(entity) { mutableSetOf() }.add("keyword_match")
        }
        return merged
    }

    private fun applyTimeFilter(
        merged: Map<MediaEntity, Set<String>>,
        timeRange: com.mamba.picme.domain.model.TimeRange?
    ): Map<MediaEntity, Set<String>> {
        if (timeRange == null) return merged
        return merged.filterKeys { entity ->
            entity.captureDate in timeRange.startMs..timeRange.endMs
        }
    }

    /**
     * 简化搜索：单一关键词跨所有维度搜索
     */
    suspend fun searchSimple(query: String): List<ScoredMedia> {
        return search(
            StructuredFilter(
                keywords = listOf(query),
                ocrKeywords = listOf(query),
                locationKeywords = listOf(query)
            )
        )
    }
}
