package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.TagTranslator

/**
 * 显式约束优先的搜索管道
 *
 * 规则：
 * 1. 先执行显式约束（时间、地点、人脸）得到候选集；
 * 2. 候选集内再执行内容关键词匹配（带跨语言扩展）；
 * 3. 若无显式约束，直接在全局执行内容关键词搜索。
 *
 * 通过 [TagTranslator] 支持跨语言搜索扩展：
 * 中文查询 → 英文候选词（命中 Qwen/SmolVLM 生成的英文标签）
 */
class ExplicitFirstSearchPipeline(
    private val mediaDao: MediaDao,
    private val personDao: PersonDao? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty())
) {

    /**
     * 使用已经分段的查询执行搜索
     */
    suspend fun search(
        segmentedQuery: SegmentedQuery,
        uiLang: AppLanguage = AppLanguage.CHINESE
    ): com.mamba.picme.domain.search.SearchResult {
        val (explicit, content) = QuerySegmenter.toFilters(segmentedQuery)
        return search(explicit, content, uiLang)
    }

    /**
     * 使用显式约束和内容过滤条件执行搜索
     */
    suspend fun search(
        explicit: ExplicitFilter,
        content: ContentFilter,
        uiLang: AppLanguage = AppLanguage.CHINESE
    ): com.mamba.picme.domain.search.SearchResult {
        val candidateIds = resolveCandidateIds(explicit)
        val mediaList = if (candidateIds == null) {
            searchGlobal(content, uiLang)
        } else {
            searchInCandidates(candidateIds, content, uiLang)
        }
        return com.mamba.picme.domain.search.SearchResult(
            media = mediaList.map { it.toDomain(uiLang) },
            originalQuery = content.semanticQuery ?: ""
        )
    }

    /**
     * 根据显式约束解析候选媒体 ID 集合；若没有任何显式约束则返回 null，表示全局搜索
     */
    private suspend fun resolveCandidateIds(explicit: ExplicitFilter): Set<Long>? {
        val candidateSets = mutableListOf<Set<Long>>()

        explicit.timeRange?.let { range ->
            candidateSets.add(
                mediaDao.getMediaIdsByTimeRange(range.startMs, range.endMs).toSet()
            )
        }

        if (explicit.locationKeywords.isNotEmpty()) {
            val locationIds = explicit.locationKeywords
                .flatMap { keyword -> mediaDao.getMediaIdsByLocationKeyword(keyword) }
                .toSet()
            candidateSets.add(locationIds)
        }

        if (explicit.hasFaces == true) {
            candidateSets.add(mediaDao.getMediaIdsByHasFace().toSet())
        }

        if (candidateSets.isEmpty()) return null
        return candidateSets.reduce { acc, set -> acc.intersect(set) }
    }

    /**
     * 在候选集中执行内容关键词搜索（带跨语言扩展），返回去重后的媒体列表
     */
    private suspend fun searchInCandidates(
        candidateIds: Set<Long>,
        content: ContentFilter,
        uiLang: AppLanguage
    ): List<MediaEntity> {
        if (candidateIds.isEmpty()) return emptyList()
        if (content.isEmpty()) {
            return mediaDao.getMediaByIds(candidateIds.toList())
                .sortedByDescending { it.captureDate }
        }

        val ids = candidateIds.toList()
        val matchedIds = mutableSetOf<Long>()

        for (keyword in content.keywords) {
            searchPersonByNameCandidate(keyword, candidateIds)?.let { matchedIds.addAll(it) }

            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchLabelsAllFieldsInIds(ids, candidate).map { it.id })
                matchedIds.addAll(mediaDao.searchFileNameInIds(ids, candidate).map { it.id })
            }
        }

        for (keyword in content.ocrKeywords) {
            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchOcrInIds(ids, candidate).map { it.id })
            }
        }

        if (matchedIds.isEmpty()) return emptyList()
        return mediaDao.getMediaByIds(matchedIds.toList())
            .sortedByDescending { it.captureDate }
    }

    /**
     * 全局内容关键词搜索（无显式约束时，带跨语言扩展）
     */
    private suspend fun searchGlobal(
        content: ContentFilter,
        uiLang: AppLanguage
    ): List<MediaEntity> {
        if (content.isEmpty()) return emptyList()

        val matchedIds = mutableSetOf<Long>()
        for (keyword in content.keywords) {
            searchPersonByNameCandidate(keyword, null)?.let { matchedIds.addAll(it) }

            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchByLabelAllFields(candidate).map { it.id })
            }
        }
        for (keyword in content.ocrKeywords) {
            val candidates = tagTranslator.expandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                matchedIds.addAll(mediaDao.searchByOcrText(candidate).map { it.id })
            }
        }

        if (matchedIds.isEmpty()) return emptyList()
        return mediaDao.getMediaByIds(matchedIds.toList())
            .sortedByDescending { it.captureDate }
    }

    /**
     * 将关键词作为人物分组名称进行匹配。
     *
     * 与 [MediaSearchEngine] 保持一致：支持用户自定义的人物分组名称搜索。
     *
     * @param candidateIds 若不为 null，则返回结果与该候选集取交集
     * @return 命中人物的媒体 ID 集合；未命中或 [personDao] 未注入时返回 null
     */
    private suspend fun searchPersonByNameCandidate(
        keyword: String,
        candidateIds: Set<Long>?
    ): Set<Long>? {
        val dao = personDao ?: return null
        val person = dao.findPersonByName(keyword.trim()) ?: return null
        val media = dao.getMediaByPerson(person.personId)
        Logger.d(TAG, "keyword='$keyword' matched personId=${person.personId}, media=${media.size}")
        val ids = media.map { it.id }.toSet()
        return if (candidateIds != null) ids.intersect(candidateIds) else ids
    }

    companion object {
        private const val TAG = "ExplicitFirstSearchPipeline"
    }
}

/**
 * MediaEntity → MediaAsset 转换（精简版，用于搜索结果）
 */
private fun MediaEntity.toDomain(uiLang: AppLanguage): MediaAsset = MediaAsset(
    id = id,
    uri = uri,
    type = type,
    captureDate = captureDate,
    fileName = fileName,
    duration = duration,
    hasFace = hasFace,
    faceId = faceId,
    faceFocusY = faceFocusY,
    source = source,
    labels = labelsForLanguage(uiLang),
    ocrText = ocrText,
    latitude = latitude,
    longitude = longitude,
    locationName = locationName,
    city = city,
    indexedAt = indexedAt
)
