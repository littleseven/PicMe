package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.TagTranslator
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONException

/**
 * 媒体搜索引擎
 *
 * 三层混合检索策略：
 * - Layer 1: QueryParser 规则匹配（快速、离线、免费）
 * - Layer 2: Agent LLM 解析复杂混合查询
 * - Layer 2.5: MobileCLIP 语义召回（连续语义空间匹配）
 * - Layer 3: 融合排序（结构化分 + 标签分 + 语义分 + 时间衰减）
 *
 * 搜索结果按匹配相关性排序：语义相似度 > 标签匹配 > OCR 匹配 > 地名匹配 > 文件名匹配
 *
 * 支持跨语言搜索：通过 [TagTranslator] 把用户输入的英文查询扩展为中文 canonical 词，
 * 从而命中已有中文 TAG，无需全量重生成。
 */
@Suppress("TooManyFunctions", "LargeClass")
class MediaSearchEngine(
    private val mediaDao: MediaDao,
    private val tagDao: TagDao? = null,
    private val ocrWordDao: OcrWordDao? = null,
    private val locationDao: LocationDao? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val tagTranslator: TagTranslator = TagTranslator(BilingualVocab.empty()),
    private val semanticSearchEngine: SemanticSearchEngine? = null,
    private val explicitFirstPipeline: ExplicitFirstSearchPipeline? = null,
    private val mediaFeedbackUseCase: MediaFeedbackUseCase? = null
) {

    /** 翻译结果 LRU 缓存：避免重复搜索时反复做词表查找 + OPUS-MT 推理（~50ms） */
    private val translationCache = object : LinkedHashMap<String, Set<String>>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    /**
     * 带缓存的搜索扩展：缓存 key = "$query|$uiLang"
     */
    private fun cachedExpandForSearch(query: String, uiLang: AppLanguage): Set<String> {
        val key = "$query|$uiLang"
        return translationCache.getOrPut(key) {
            SearchSynonyms.expand(query) + tagTranslator.expandForSearch(query, uiLang)
        }
    }

    /**
     * 以图搜图（公共入口）：委托给 [semanticSearchEngine]，把打分结果映射为 [MediaAsset] 列表。
     * 用于 Chat「找相似」意图——对用户刚选中的图片做 embedding 近邻召回。
     * 引擎未就绪/未注入时返回空列表。
     */
    suspend fun searchByImage(
        bitmap: android.graphics.Bitmap,
        topK: Int = 50
    ): List<MediaAsset> =
        semanticSearchEngine?.searchByImage(bitmap, null, topK)?.map { it.media } ?: emptyList()

    /**
     * 执行搜索（三层混合检索）
     *
     * @param query 自然语言查询（如"猫""去年的照片""上海""温馨的家庭聚餐"）
     * @param llmSearch LLM 结构化查询回调（仅在规则无法匹配时调用）
     * @param enableSemanticSearch 是否启用 MobileCLIP 语义召回（默认 true）
     * @return 匹配的媒体列表
     */
    suspend fun search(
        query: String,
        llmSearch: (suspend (String) -> StructuredFilter?)? = null,
        enableSemanticSearch: Boolean = true,
        limitToIds: Set<Long>? = null
    ): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), query)

        // refine（in-set）时把搜索结果限定在上一轮结果集 prior 内；全库搜索时为 null 不生效。
        fun limitToIdsFilter(list: List<MediaAsset>): List<MediaAsset> =
            if (limitToIds != null) list.filter { asset -> asset.id in limitToIds } else list

        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

        // Layer 0.5: 显式约束优先分段搜索（如"去年3月在室内小孩"）
        // 仅当存在时间/地点这类真正的收窄约束时才短路；纯人物/概念查询（如"小孩"）
        // 不在此短路，回落到 Layer 1/兜底的 SQL + 语义融合，避免丢失 MobileCLIP 语义召回。
        val segmentedQuery = QuerySegmenter.segment(query)
        if (segmentedQuery.hasNarrowingExplicit && explicitFirstPipeline != null) {
            val explicitResults = explicitFirstPipeline.search(segmentedQuery, uiLang)
            if (explicitResults.media.isNotEmpty()) {
                return SearchResult(limitToIdsFilter(explicitResults.media), query)
            }
        }

        // Layer 1: 规则匹配
        val filter = QueryParser.parse(query, uiLang)
        if (filter != null && !filter.needsLlm) {
            // SQL 搜索与语义召回并行执行
            val (results, semanticResults) = coroutineScope {
                val sqlDeferred = async { executeFilter(filter) }
                val semanticDeferred = async {
                    if (enableSemanticSearch && semanticSearchEngine != null) {
                        searchSemantic(query, filter)
                    } else emptyList()
                }
                sqlDeferred.await() to semanticDeferred.await()
            }

            // Layer 3: 融合排序
            val merged = mergeAndRank(results, semanticResults, query)
            return SearchResult(limitToIdsFilter(merged), query)
        }

        // Layer 2: LLM 解析
        if (llmSearch != null) {
            val llmFilter = llmSearch(query)
            if (llmFilter != null) {
                // SQL 搜索与语义召回并行执行
                val (results, semanticResults) = coroutineScope {
                    val sqlDeferred = async { executeFilter(llmFilter) }
                    val semanticDeferred = async {
                        if (enableSemanticSearch && semanticSearchEngine != null) {
                            searchSemantic(query, llmFilter)
                        } else emptyList()
                    }
                    sqlDeferred.await() to semanticDeferred.await()
                }

                val merged = mergeAndRank(results, semanticResults, query)
                return SearchResult(limitToIdsFilter(merged), query)
            }
        }

        // 回退：全字段模糊搜索 + 语义召回，并行执行
        val (sqlResults, semanticResults) = coroutineScope {
            val sqlDeferred = async {
                cachedExpandForSearch(query, uiLang)
                    .flatMap { mediaDao.searchAll(it) }
                    .map { it.toDomain() }
                    .distinct()
            }
            val semanticDeferred = async {
                if (enableSemanticSearch && semanticSearchEngine != null) {
                    searchSemantic(query, null)
                } else emptyList()
            }
            sqlDeferred.await() to semanticDeferred.await()
        }

        val merged = mergeAndRank(sqlResults, semanticResults, query)
        return SearchResult(limitToIdsFilter(merged), query)
    }

    /**
     * 使用已标准化的 [StructuredFilter] 直接搜索，跳过 QueryParser 规则解析。
     *
     * 供 chat 等已由 LLM 完成意图标准化的路径使用。
     *
     * @param filter 结构化过滤条件
     * @param limitToIds 可选的 ID 集合，用于 refine 时在 prior 结果集内过滤
     * @param enableSemanticSearch 是否启用 MobileCLIP 语义召回
     * @return 匹配的媒体列表
     */
    suspend fun search(
        filter: StructuredFilter,
        limitToIds: Set<Long>? = null,
        enableSemanticSearch: Boolean = true
    ): SearchResult {
        fun limitToIdsFilter(list: List<MediaAsset>): List<MediaAsset> =
            if (limitToIds != null) list.filter { asset -> asset.id in limitToIds } else list

        val query = filter.keywords.firstOrNull()
            ?: filter.ocrKeywords.firstOrNull()
            ?: filter.locationKeywords.firstOrNull()
            ?: filter.personName
            ?: ""

        val (results, semanticResults) = coroutineScope {
            val sqlDeferred = async { executeFilter(filter) }
            val semanticDeferred = async {
                if (enableSemanticSearch && semanticSearchEngine != null && query.isNotBlank()) {
                    searchSemantic(query, filter)
                } else emptyList()
            }
            sqlDeferred.await() to semanticDeferred.await()
        }

        val merged = mergeAndRank(results, semanticResults, query)
        return SearchResult(limitToIdsFilter(merged), query)
    }

    /**
     * 执行 MobileCLIP 语义召回
     *
     * @param query 用户原始查询
     * @param filter 结构化过滤条件（用于缩小候选集）
     * @return 语义搜索结果
     */
    private suspend fun searchSemantic(
        query: String,
        filter: StructuredFilter?
    ): List<SemanticScoredMedia> {
        // 如果 SQL 结果已足够多（> 50），语义召回优先级降低
        // 但仍执行语义搜索，用于融合排序中的语义分
        @Suppress("TooGenericExceptionCaught")
        return try {
            semanticSearchEngine?.searchByText(
                query = query,
                filter = filter,
                topK = 50
            ) ?: emptyList()
        } catch (e: Exception) {
            Logger.w(TAG, "Semantic search failed", e)
            emptyList()
        }
    }

    /**
     * Layer 3: 融合排序
     *
     * 将 SQL 搜索结果和语义搜索结果合并，按综合分数排序：
     * - 结构化匹配分：时间/地点/人脸命中 boost（0~1.0）
     * - 标签匹配分：标签命中 boost（0~0.8）
     * - 语义相似度分：CLIP 余弦相似度（0~1.0）
     * - 时间衰减：新照片 boost（0~0.3）
     *
     * 综合分 = 结构化分 * 0.3 + 标签分 * 0.2 + 语义分 * 0.4 + 时间衰减 * 0.1
     */
    private suspend fun mergeAndRank(
        sqlResults: List<MediaAsset>,
        semanticResults: List<SemanticScoredMedia>,
        query: String = ""
    ): List<MediaAsset> = mergeAndRankWithScores(sqlResults, semanticResults, query).map { it.media }

    /**
     * 融合排序并返回带分数的结果（搜索测试页观测用）。
     */
    private suspend fun mergeAndRankWithScores(
        sqlResults: List<MediaAsset>,
        semanticResults: List<SemanticScoredMedia>,
        query: String = ""
    ): List<ScoredMediaAsset> {
        val scoreMap = mutableMapOf<Long, Float>()
        val mediaMap = mutableMapOf<Long, MediaAsset>()

        sqlResults.forEachIndexed { index, media ->
            mediaMap[media.id] = media
            val baseScore = 1.0f - (index.toFloat() / (sqlResults.size + 1))
            scoreMap[media.id] = baseScore * SQL_SCORE_WEIGHT
        }

        semanticResults.forEach { scored ->
            mediaMap[scored.media.id] = scored.media
            val existingScore = scoreMap.getOrDefault(scored.media.id, 0f)
            scoreMap[scored.media.id] = existingScore + scored.score * SEMANTIC_SCORE_WEIGHT
        }

        val now = System.currentTimeMillis()
        scoreMap.keys.forEach { id ->
            val media = mediaMap[id] ?: return@forEach
            val daysSinceCapture = (now - media.captureDate) / MS_PER_DAY
            val timeBoost = when {
                daysSinceCapture < TIME_BOOST_RECENT_DAYS -> TIME_BOOST_RECENT
                daysSinceCapture < TIME_BOOST_YEAR_DAYS -> TIME_BOOST_YEAR
                else -> NO_TIME_BOOST
            }
            scoreMap[id] = scoreMap.getOrDefault(id, 0f) + timeBoost * TIME_SCORE_WEIGHT
        }

        // 叠加反馈权重
        applyFeedbackScores(scoreMap, mediaMap, query)

        return scoreMap.entries
            .sortedByDescending { it.value }
            .mapNotNull { mediaMap[it.key]?.let { media -> ScoredMediaAsset(media, it.value) } }
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun applyFeedbackScores(
        scoreMap: MutableMap<Long, Float>,
        mediaMap: Map<Long, MediaAsset>,
        query: String
    ) {
        if (query.isBlank() || mediaFeedbackUseCase == null) return

        try {
            val scores = mediaFeedbackUseCase.getScoresForQuery(query)

            scoreMap.keys.forEach { id ->
                val mediaId = mediaMap[id]?.id?.toString() ?: return@forEach
                val score = scores[mediaId]
                val delta = mediaFeedbackUseCase.calculateScoreDelta(score)
                if (delta != 0f) {
                    scoreMap[id] = scoreMap.getOrDefault(id, 0f) + delta
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to apply feedback scores", e)
        }
    }

    /**
     * 执行结构化过滤。
     *
     * 语义：维度之间取**交集**（AND），同一维度内不同关键词取**并集**（OR）。
     * 例如 "近半年小孩的照片" → 时间范围 ∩ (标签/文件名/OCR 命中 "小孩" 之一) ∩ 有人脸。
     *
     * 修复历史：此前实现把各维度结果累积到同一个 map 中，导致时间约束与关键词约束变成
     * 并集，旧照片只要命中关键词就会被召回，从而出现 2003 年/2023 年等非半年内结果。
     */
    private suspend fun executeFilter(filter: StructuredFilter): List<MediaAsset> {
        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE
        val totalStart = System.currentTimeMillis()

        // 1. 显式约束候选集（时间 / 地点 / 人脸）—— 维度间交集
        val explicitStart = System.currentTimeMillis()
        val explicitCandidateSets = mutableListOf<Set<Long>>()

        filter.timeRange?.let { range ->
            explicitCandidateSets.add(mediaDao.getMediaIdsByTimeRange(range.startMs, range.endMs).toSet())
        }

        if (filter.locationKeywords.isNotEmpty()) {
            val locationIds = mutableSetOf<Long>()
            for (keyword in filter.locationKeywords) {
                locationDao?.searchByPlace(keyword)?.mapTo(locationIds) { it.id }
                mediaDao.getMediaIdsByLocationKeyword(keyword).mapTo(locationIds) { it }
            }
            explicitCandidateSets.add(locationIds)
        }

        if (filter.hasFaces == true) {
            explicitCandidateSets.add(mediaDao.getHasFaceIds().toSet())
        }

        val explicitCandidateIds = if (explicitCandidateSets.isEmpty()) {
            null
        } else {
            explicitCandidateSets.reduce { acc, set -> acc.intersect(set) }
        }
        val explicitTime = System.currentTimeMillis() - explicitStart

        // 2. 内容关键词候选集（标签 / ML Kit / OCR / 文件名）—— 关键词间并集
        val contentStart = System.currentTimeMillis()
        val contentIds = mutableSetOf<Long>()
        val hasContentKeywords = filter.keywords.isNotEmpty() || filter.ocrKeywords.isNotEmpty()

        if (hasContentKeywords) {
            for (keyword in filter.keywords) {
                val candidates = cachedExpandForSearch(keyword, uiLang)
                for (candidate in candidates) {
                    contentIds.addAll(searchCandidateIds(candidate, explicitCandidateIds))
                }
            }
            for (keyword in filter.ocrKeywords) {
                val candidates = cachedExpandForSearch(keyword, uiLang)
                for (candidate in candidates) {
                    contentIds.addAll(searchOcrCandidateIds(candidate, explicitCandidateIds))
                }
            }
        }
        val contentTime = System.currentTimeMillis() - contentStart

        // 3. 最终 ID = 显式约束 ∩ 内容关键词
        val finalIds = when {
            explicitCandidateIds == null && !hasContentKeywords -> emptySet()
            explicitCandidateIds == null -> contentIds
            !hasContentKeywords -> explicitCandidateIds
            else -> explicitCandidateIds.intersect(contentIds)
        }

        if (finalIds.isEmpty()) {
            Logger.d(TAG, "executeFilter empty result explicit=${explicitTime}ms content=${contentTime}ms total=${System.currentTimeMillis() - totalStart}ms")
            return emptyList()
        }
        val fetchStart = System.currentTimeMillis()
        val result = mediaDao.getMediaByIds(finalIds.toList())
            .map { it.toDomain() }
            .sortedByDescending { it.captureDate }
        val fetchTime = System.currentTimeMillis() - fetchStart
        Logger.d(
            TAG,
            "executeFilter result=${result.size} explicit=${explicitTime}ms content=${contentTime}ms " +
                "fetch=${fetchTime}ms total=${System.currentTimeMillis() - totalStart}ms " +
                "candidateIds=${explicitCandidateIds?.size ?: "null"} finalIds=${finalIds.size}"
        )
        return result
    }

    /**
     * 搜索单个候选词在所有文本字段中的命中 ID。
     *
     * @param candidateIds 若不为 null，则在这些 ID 内搜索并返回子集；否则全局搜索。
     */
    private suspend fun searchCandidateIds(
        candidate: String,
        candidateIds: Set<Long>?
    ): Set<Long> {
        val matched = mutableSetOf<Long>()

        // 辅助表精确匹配（先全局查，再与候选集取交集，避免缺少 in-ID 接口）
        if (tagDao != null) {
            tagDao.searchByExactTag(candidate).mapTo(matched) { it.id }
        }
        if (ocrWordDao != null) {
            ocrWordDao.searchByWordPrefix(candidate.lowercase()).mapTo(matched) { it.id }
        }

        // 主表 LIKE 查询
        if (candidateIds != null) {
            val ids = candidateIds.toList()
            matched.addAll(mediaDao.searchLabelsInIds(ids, candidate).map { it.id })
            matched.addAll(mediaDao.searchMlKitLabelsInIds(ids, candidate).map { it.id })
            matched.addAll(mediaDao.searchMlKitLabelsZhInIds(ids, candidate).map { it.id })
            matched.addAll(mediaDao.searchFileNameInIds(ids, candidate).map { it.id })
        } else {
            matched.addAll(mediaDao.searchByLabel(candidate).map { it.id })
            matched.addAll(mediaDao.searchByMlKitLabel(candidate).map { it.id })
            matched.addAll(mediaDao.searchByMlKitLabelZh(candidate).map { it.id })
            matched.addAll(mediaDao.searchByFileName(candidate).map { it.id })
        }

        return if (candidateIds != null) matched.intersect(candidateIds) else matched
    }

    /**
     * 搜索单个 OCR 候选词的命中 ID。
     */
    private suspend fun searchOcrCandidateIds(
        candidate: String,
        candidateIds: Set<Long>?
    ): Set<Long> {
        val matched = mutableSetOf<Long>()

        if (ocrWordDao != null) {
            ocrWordDao.searchByExactWord(candidate.lowercase()).mapTo(matched) { it.id }
        }

        if (candidateIds != null) {
            val ids = candidateIds.toList()
            matched.addAll(mediaDao.searchOcrInIds(ids, candidate).map { it.id })
        } else {
            matched.addAll(mediaDao.searchByOcrText(candidate).map { it.id })
        }

        return if (candidateIds != null) matched.intersect(candidateIds) else matched
    }

    /**
     * LLM 结构化查询模板
     */
    fun buildLlmSearchPrompt(query: String, lang: AppLanguage = AppLanguage.CHINESE): String {
        return if (lang == AppLanguage.ENGLISH) {
            buildEnglishLlmSearchPrompt(query)
        } else {
            buildChineseLlmSearchPrompt(query)
        }
    }

    private fun buildChineseLlmSearchPrompt(query: String): String {
        return """
你是一个图片搜索助手。请将用户的自然语言查询转换为结构化过滤条件。

用户查询："$query"

请以 JSON 格式返回过滤条件：
{
  "timeRange": {"startMs": 开始时间戳毫秒, "endMs": 结束时间戳毫秒} 或 null,
  "keywords": ["关键词1", "关键词2"],
  "ocrKeywords": ["OCR文字关键词"],
  "locationKeywords": ["地点关键词"],
  "hasFaces": true/false/null,
  "explanation": "解释你是如何理解这个查询的"
}

当前年份：${QueryParser.currentYear}，当前月份：${QueryParser.currentMonth}

注意：
- 时间词示例："去年"→${QueryParser.currentYear - 1}年，"夏天"→6-8月
- keywords 是场景/物体/标签关键词
- ocrKeywords 是图片中可能出现的文字
- locationKeywords 是地名（城市、区域）
- 只返回 JSON，不要其他文字
""".trimIndent()
    }

    private fun buildEnglishLlmSearchPrompt(query: String): String {
        return """
You are a photo search assistant. Convert the user's natural language query into a structured filter.

User query: "$query"

Return a JSON filter in this format:
{
  "timeRange": {"startMs": start timestamp in ms, "endMs": end timestamp in ms} or null,
  "keywords": ["keyword1", "keyword2"],
  "ocrKeywords": ["ocr text keywords"],
  "locationKeywords": ["place keywords"],
  "hasFaces": true/false/null,
  "explanation": "explain how you understood this query"
}

Current year: ${QueryParser.currentYear}, current month: ${QueryParser.currentMonth}

Notes:
- Time words example: "last year" → year ${QueryParser.currentYear - 1}, "summer" → June-August
- keywords are scene/object/tag keywords
- ocrKeywords are text that may appear in images
- locationKeywords are place names (city, district)
- Return only JSON, no other text
""".trimIndent()
    }

    /**
     * 解析 LLM 返回的结构化过滤条件
     */
    fun parseLlmResponse(llmResponse: String): StructuredFilter? {
        return try {
            val jsonStart = llmResponse.indexOf('{')
            val jsonEnd = llmResponse.lastIndexOf('}') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null

            val json = llmResponse.substring(jsonStart, jsonEnd)
            val obj = org.json.JSONObject(json)

            val timeObj = obj.optJSONObject("timeRange")
            val timeRange = if (timeObj != null) {
                com.mamba.picme.domain.model.TimeRange(
                    startMs = timeObj.optLong("startMs", 0),
                    endMs = timeObj.optLong("endMs", 0)
                )
            } else null

            val keywordsArr = obj.optJSONArray("keywords")
            val keywords = if (keywordsArr != null) {
                (0 until keywordsArr.length()).map { keywordsArr.getString(it) }
            } else emptyList()

            val ocrArr = obj.optJSONArray("ocrKeywords")
            val ocrKeywords = if (ocrArr != null) {
                (0 until ocrArr.length()).map { ocrArr.getString(it) }
            } else emptyList()

            val locArr = obj.optJSONArray("locationKeywords")
            val locationKeywords = if (locArr != null) {
                (0 until locArr.length()).map { locArr.getString(it) }
            } else emptyList()

            val hasFaces = if (obj.has("hasFaces")) obj.optBoolean("hasFaces") else null

            StructuredFilter(
                timeRange = timeRange,
                keywords = keywords,
                ocrKeywords = ocrKeywords,
                locationKeywords = locationKeywords,
                hasFaces = hasFaces,
                needsLlm = false
            )
        } catch (e: JSONException) {
            Logger.w(TAG, "Failed to parse LLM search response", e)
            null
        }
    }

    /**
     * 执行搜索并返回完整诊断信息（用于搜索测试页观测召回链路）。
     *
     * 本方法复用现有搜索逻辑，但额外记录每个召回维度的命中数量、耗时与最终融合分数。
     * 不修改 [search] 行为，避免影响线上 Gallery 搜索链路。
     */
    suspend fun searchWithDiagnostics(
        query: String,
        enableSemanticSearch: Boolean = true
    ): SearchDiagnosticsResult {
        val totalStart = System.currentTimeMillis()
        val uiLang = userSettingsRepository?.getAppLanguageBlocking() ?: AppLanguage.CHINESE

        // Layer 1: 规则解析
        val parseStart = System.currentTimeMillis()
        val filter = QueryParser.parse(query, uiLang)
        val parseTimeMs = System.currentTimeMillis() - parseStart

        if (filter != null && !filter.needsLlm) {
            return executeDiagnosticsSearch(
                query = query,
                filter = filter,
                usedLlm = false,
                llmFilter = null,
                parseTimeMs = parseTimeMs,
                totalStart = totalStart,
                enableSemanticSearch = enableSemanticSearch,
                uiLang = uiLang
            )
        }

        // 规则无法解析 → 需要 LLM（测试页不触发 LLM，直接走兜底模糊搜索）
        val fallbackStart = System.currentTimeMillis()
        val queryCandidates = cachedExpandForSearch(query, uiLang)
        val breakdown = mutableListOf<RecallDimension>()
        val resultMap = mutableMapOf<Long, Pair<MediaAsset, MutableSet<String>>>()

        for (candidate in queryCandidates) {
            searchByCandidateWithDiagnostics(candidate, resultMap)
        }
        val fallbackTimeMs = System.currentTimeMillis() - fallbackStart

        val sqlResults = resultMap.values.map { (media, dims) ->
            DiagnosticMediaItem(media = media, score = 0f, matchDimensions = dims.toList())
        }.sortedByDescending { it.media.captureDate }

        return buildDiagnosticsResult(
            query = query,
            parsedFilter = filter,
            usedLlm = false,
            llmFilter = null,
            parseTimeMs = parseTimeMs,
            sqlRecallTimeMs = fallbackTimeMs,
            sqlResults = sqlResults,
            semanticResults = emptyList(),
            mergedResults = emptyList(),
            recallBreakdown = breakdown,
            totalStart = totalStart,
            enableSemanticSearch = false,
            semanticEngineReady = semanticSearchEngine?.isReady ?: false,
            semanticCandidateCount = 0
        )
    }

    @Suppress("LongParameterList")
    private suspend fun executeDiagnosticsSearch(
        query: String,
        filter: StructuredFilter,
        usedLlm: Boolean,
        llmFilter: StructuredFilter?,
        parseTimeMs: Long,
        totalStart: Long,
        enableSemanticSearch: Boolean,
        uiLang: AppLanguage
    ): SearchDiagnosticsResult {
        // Layer 1/2 SQL 召回（带诊断）
        val sqlStart = System.currentTimeMillis()
        val (sqlResultsRaw, recallBreakdown) = executeFilterWithDiagnostics(filter, uiLang)
        val sqlResults = sqlResultsRaw.map { (media, dims) ->
            DiagnosticMediaItem(media = media, score = 0f, matchDimensions = dims.toList())
        }.sortedByDescending { it.media.captureDate }
        val sqlRecallTimeMs = System.currentTimeMillis() - sqlStart

        // Layer 2.5 语义召回
        val semanticStart = System.currentTimeMillis()
        val semanticResults = if (enableSemanticSearch && semanticSearchEngine != null) {
            @Suppress("TooGenericExceptionCaught")
            try {
                semanticSearchEngine.searchByText(query, filter, topK = 50)
                    .map { DiagnosticSemanticItem(media = it.media, score = it.score) }
            } catch (e: Exception) {
                Logger.w(TAG, "Diagnostic semantic search failed", e)
                emptyList()
            }
        } else emptyList()
        val semanticRecallTimeMs = System.currentTimeMillis() - semanticStart
        // SemanticSearchEngine 当前未暴露候选集大小，测试页通过日志观察
        val semanticCandidateCount = -1

        // Layer 3 融合排序
        val mergeStart = System.currentTimeMillis()
        val scoredMerged = mergeAndRankWithScores(
            sqlResults.map { it.media },
            semanticResults.map { SemanticScoredMedia(it.media, it.score) },
            query
        )
        val mergeTimeMs = System.currentTimeMillis() - mergeStart

        val mergedItems = scoredMerged.map { scored ->
            val sqlItem = sqlResults.find { it.media.id == scored.media.id }
            val semanticItem = semanticResults.find { it.media.id == scored.media.id }
            DiagnosticMediaItem(
                media = scored.media,
                score = scored.score,
                matchDimensions = (sqlItem?.matchDimensions ?: emptyList()) +
                    if (semanticItem != null) listOf("semantic") else emptyList()
            )
        }

        // 语义维度统计：由于 SemanticSearchEngine 不暴露候选集大小，用 -1 占位，UI 显示"见日志"
        val semanticDimension = RecallDimension(
            name = "Semantic",
            count = semanticResults.size,
            timeMs = semanticRecallTimeMs
        )
        val fullBreakdown = recallBreakdown + semanticDimension

        return buildDiagnosticsResult(
            query = query,
            parsedFilter = filter,
            usedLlm = usedLlm,
            llmFilter = llmFilter,
            parseTimeMs = parseTimeMs,
            sqlRecallTimeMs = sqlRecallTimeMs,
            sqlResults = sqlResults,
            semanticResults = semanticResults,
            mergedResults = mergedItems,
            recallBreakdown = fullBreakdown,
            totalStart = totalStart,
            enableSemanticSearch = enableSemanticSearch,
            semanticEngineReady = semanticSearchEngine?.isReady ?: false,
            semanticCandidateCount = semanticCandidateCount,
            mergeTimeMs = mergeTimeMs
        )
    }

    private suspend fun executeFilterWithDiagnostics(
        filter: StructuredFilter,
        uiLang: AppLanguage
    ): Pair<List<Pair<MediaAsset, Set<String>>>, List<RecallDimension>> {
        val resultMap = mutableMapOf<Long, Pair<MediaAsset, MutableSet<String>>>()
        val breakdown = mutableListOf<RecallDimension>()

        applyTimeRangeWithDiagnostics(filter, resultMap, breakdown)
        applyContentKeywordsWithDiagnostics(filter.keywords, uiLang, resultMap, breakdown)
        applyOcrKeywordsWithDiagnostics(filter.ocrKeywords, resultMap, breakdown)
        applyLocationKeywordsWithDiagnostics(filter.locationKeywords, resultMap, breakdown)
        applyFaceFilterWithDiagnostics(filter.hasFaces, resultMap, breakdown)

        return resultMap.values.map { it.first to it.second.toSet() } to breakdown
    }

    private suspend fun applyTimeRangeWithDiagnostics(
        filter: StructuredFilter,
        resultMap: MutableMap<Long, Pair<MediaAsset, MutableSet<String>>>,
        breakdown: MutableList<RecallDimension>
    ) {
        val timeRange = filter.timeRange ?: return
        val start = System.currentTimeMillis()
        var count = 0
        mediaDao.searchByTimeRange(timeRange.startMs, timeRange.endMs).forEach { entity ->
            val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
            dims.add("time_range")
            resultMap[entity.id] = media to dims
            count++
        }
        breakdown.add(RecallDimension("Time", count, System.currentTimeMillis() - start))
    }

    private suspend fun applyContentKeywordsWithDiagnostics(
        keywords: List<String>,
        uiLang: AppLanguage,
        resultMap: MutableMap<Long, Pair<MediaAsset, MutableSet<String>>>,
        breakdown: MutableList<RecallDimension>
    ) {
        val start = System.currentTimeMillis()
        var count = 0
        for (keyword in keywords) {
            val candidates = cachedExpandForSearch(keyword, uiLang)
            for (candidate in candidates) {
                val before = resultMap.size
                searchByCandidateWithDiagnostics(candidate, resultMap)
                count += resultMap.size - before
            }
        }
        breakdown.add(RecallDimension("Tag/OCR/Label/File", count, System.currentTimeMillis() - start))
    }

    private suspend fun applyOcrKeywordsWithDiagnostics(
        ocrKeywords: List<String>,
        resultMap: MutableMap<Long, Pair<MediaAsset, MutableSet<String>>>,
        breakdown: MutableList<RecallDimension>
    ) {
        val start = System.currentTimeMillis()
        var count = 0
        for (keyword in ocrKeywords) {
            val before = resultMap.size
            if (ocrWordDao != null) {
                ocrWordDao.searchByExactWord(keyword.lowercase()).forEach { entity ->
                    val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
                    dims.add("ocr_exact")
                    resultMap[entity.id] = media to dims
                }
            }
            mediaDao.searchByOcrText(keyword).forEach { entity ->
                val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
                dims.add("ocr")
                resultMap[entity.id] = media to dims
            }
            count += resultMap.size - before
        }
        breakdown.add(RecallDimension("OCR", count, System.currentTimeMillis() - start))
    }

    private suspend fun applyLocationKeywordsWithDiagnostics(
        locationKeywords: List<String>,
        resultMap: MutableMap<Long, Pair<MediaAsset, MutableSet<String>>>,
        breakdown: MutableList<RecallDimension>
    ) {
        val start = System.currentTimeMillis()
        var count = 0
        for (keyword in locationKeywords) {
            val before = resultMap.size
            if (locationDao != null) {
                locationDao.searchByPlace(keyword).forEach { entity ->
                    val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
                    dims.add("location")
                    resultMap[entity.id] = media to dims
                }
            }
            mediaDao.searchByLocation(keyword).forEach { entity ->
                val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
                dims.add("location_name")
                resultMap[entity.id] = media to dims
            }
            count += resultMap.size - before
        }
        breakdown.add(RecallDimension("Location", count, System.currentTimeMillis() - start))
    }

    private suspend fun applyFaceFilterWithDiagnostics(
        hasFaces: Boolean?,
        resultMap: MutableMap<Long, Pair<MediaAsset, MutableSet<String>>>,
        breakdown: MutableList<RecallDimension>
    ) {
        if (hasFaces != true) return
        val start = System.currentTimeMillis()
        var count = 0
        val ids = mediaDao.getHasFaceIds()
        if (ids.isNotEmpty()) {
            mediaDao.getMediaByIds(ids).forEach { entity ->
                val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
                if (dims.add("has_face")) count++
                resultMap[entity.id] = media to dims
            }
        }
        breakdown.add(RecallDimension("Face", count, System.currentTimeMillis() - start))
    }

    private suspend fun searchByCandidateWithDiagnostics(
        candidate: String,
        resultMap: MutableMap<Long, Pair<MediaAsset, MutableSet<String>>>
    ) {
        // 辅助表查询
        if (tagDao != null) {
            tagDao.searchByExactTag(candidate).forEach { entity ->
                val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
                dims.add("tag_exact")
                resultMap[entity.id] = media to dims
            }
        }
        if (ocrWordDao != null) {
            ocrWordDao.searchByWordPrefix(candidate.lowercase()).forEach { entity ->
                val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
                dims.add("ocr_prefix")
                resultMap[entity.id] = media to dims
            }
        }

        // 主表 LIKE 查询
        mediaDao.searchByLabel(candidate).forEach { entity ->
            val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
            dims.add("label")
            resultMap[entity.id] = media to dims
        }
        mediaDao.searchByOcrText(candidate).forEach { entity ->
            val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
            dims.add("ocr")
            resultMap[entity.id] = media to dims
        }
        mediaDao.searchByFileName(candidate).forEach { entity ->
            val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
            dims.add("file_name")
            resultMap[entity.id] = media to dims
        }

        // ML Kit 标签（中英文）
        mediaDao.searchByMlKitLabel(candidate).forEach { entity ->
            val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
            dims.add("mlkit_label")
            resultMap[entity.id] = media to dims
        }
        mediaDao.searchByMlKitLabelZh(candidate).forEach { entity ->
            val (media, dims) = resultMap.getOrPut(entity.id) { entity.toDomain() to mutableSetOf() }
            dims.add("mlkit_label_zh")
            resultMap[entity.id] = media to dims
        }
    }

    @Suppress("LongParameterList")
    private fun buildDiagnosticsResult(
        query: String,
        parsedFilter: StructuredFilter?,
        usedLlm: Boolean,
        llmFilter: StructuredFilter?,
        parseTimeMs: Long,
        sqlRecallTimeMs: Long,
        sqlResults: List<DiagnosticMediaItem>,
        semanticResults: List<DiagnosticSemanticItem>,
        mergedResults: List<DiagnosticMediaItem>,
        recallBreakdown: List<RecallDimension>,
        totalStart: Long,
        enableSemanticSearch: Boolean,
        semanticEngineReady: Boolean,
        semanticCandidateCount: Int,
        mergeTimeMs: Long = 0L
    ): SearchDiagnosticsResult {
        return SearchDiagnosticsResult(
            originalQuery = query,
            parsedFilter = parsedFilter,
            needsLlm = parsedFilter?.needsLlm ?: true,
            usedLlm = usedLlm,
            llmFilter = llmFilter,
            metrics = SearchMetrics(
                totalTimeMs = System.currentTimeMillis() - totalStart,
                parseTimeMs = parseTimeMs,
                sqlRecallTimeMs = sqlRecallTimeMs,
                semanticRecallTimeMs = recallBreakdown.find { it.name == "Semantic" }?.timeMs ?: 0L,
                mergeTimeMs = mergeTimeMs,
                semanticEngineReady = semanticEngineReady,
                semanticCandidateCount = semanticCandidateCount
            ),
            recallBreakdown = recallBreakdown,
            sqlResults = sqlResults,
            semanticResults = semanticResults,
            mergedResults = mergedResults,
            enableSemanticSearch = enableSemanticSearch
        )
    }

    companion object {
        private const val TAG = "MediaSearchEngine"

        /** 翻译缓存最大条目数 */
        private const val MAX_CACHE_SIZE = 64

        /** SQL 召回基础分权重（语义搜索优先，SQL 仅作辅助召回） */
        private const val SQL_SCORE_WEIGHT = 0.25f

        /** 语义召回相似度权重（提高语义分占比，让 CLIP 结果排在前面） */
        private const val SEMANTIC_SCORE_WEIGHT = 0.65f

        /** 时间衰减权重 */
        private const val TIME_SCORE_WEIGHT = 0.1f

        /** 一天毫秒数 */
        private val MS_PER_DAY = TimeUnit.DAYS.toMillis(1)

        /** 近期照片天数阈值 */
        private const val TIME_BOOST_RECENT_DAYS = 30

        /** 一年内照片天数阈值 */
        private const val TIME_BOOST_YEAR_DAYS = 365

        /** 近期照片时间 boost */
        private const val TIME_BOOST_RECENT = 0.3f

        /** 一年内照片时间 boost */
        private const val TIME_BOOST_YEAR = 0.15f

        /** 无时间 boost */
        private const val NO_TIME_BOOST = 0f
    }
}

data class SearchResult(
    val media: List<MediaAsset>,
    val originalQuery: String,
    val resultCount: Int = media.size
)

/**
 * 搜索召回诊断结果（搜索测试页专用）。
 */
data class SearchDiagnosticsResult(
    val originalQuery: String,
    val parsedFilter: StructuredFilter?,
    val needsLlm: Boolean,
    val usedLlm: Boolean,
    val llmFilter: StructuredFilter?,
    val metrics: SearchMetrics,
    val recallBreakdown: List<RecallDimension>,
    val sqlResults: List<DiagnosticMediaItem>,
    val semanticResults: List<DiagnosticSemanticItem>,
    val mergedResults: List<DiagnosticMediaItem>,
    val enableSemanticSearch: Boolean
)

/**
 * 搜索耗时与状态指标。
 */
data class SearchMetrics(
    val totalTimeMs: Long,
    val parseTimeMs: Long,
    val sqlRecallTimeMs: Long,
    val semanticRecallTimeMs: Long,
    val mergeTimeMs: Long,
    val semanticEngineReady: Boolean,
    val semanticCandidateCount: Int
)

/**
 * 单个召回维度的统计。
 */
data class RecallDimension(
    val name: String,
    val count: Int,
    val timeMs: Long
)

/**
 * 诊断结果中的媒体项（含命中维度）。
 */
data class DiagnosticMediaItem(
    val media: MediaAsset,
    val score: Float,
    val matchDimensions: List<String>
)

/**
 * 语义召回诊断项。
 */
data class DiagnosticSemanticItem(
    val media: MediaAsset,
    val score: Float
)

/**
 * MediaEntity → MediaAsset 转换（精简版，用于搜索结果）
 */
private fun com.mamba.picme.data.model.MediaEntity.toDomain() =
    com.mamba.picme.agent.core.model.context.MediaAsset(
        id = id,
        uri = uri,
        type = type,
        captureDate = captureDate,
        fileName = fileName,
        duration = duration,
        hasFace = hasFace,
        faceId = faceId,
        source = source,
        labels = labels,
        ocrText = ocrText,
        latitude = latitude,
        longitude = longitude,
        locationName = locationName,
        indexedAt = indexedAt
    )

/**
 * 带融合分数的媒体（内部使用，不公开）。
 */
private data class ScoredMediaAsset(
    val media: MediaAsset,
    val score: Float
)
