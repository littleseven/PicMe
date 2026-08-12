import Foundation

// MARK: - 媒体搜索引擎总编排（双端契约 SSOT: contracts.md §2 全流程）
//
// 逐字对齐 Android `app/domain/search/MediaSearchEngine.kt:91-539`。三层混合检索：
// - Layer 0.5: 显式约束分段短路（hasNarrowingExplicit 且 pipeline 已注入；结果非空直接返回）
// - Layer 1: QueryParser 规则解析 → SQL（executeFilter）∥ 语义召回（topK=50）→ mergeAndRank
// - Layer 2: LLM 解析（⚠️ iOS 入口未声明 llmSearch 回调，行为等价 Android llmSearch = nil）
// - 兜底: expandForSearch 候选逐词 searchAll（全字段模糊）∥ 语义召回 → mergeAndRank
// - Layer 3: 融合排序 mergeAndRank（SQL 0.25 / 语义 0.65 / 时间 0.1，以实现常量为准——
//   Android KDoc 的 0.3/0.2/0.4/0.1 与实现不一致，契约 §14 R1）
//
// 并行 API 对接：
// - 语义召回：任务 C 交付 SemanticSearchEngine（async 非 throwing 签名）；未交付/未注入时
//   经 SemanticSearching 协议隔离，仅 SQL 路径（对齐 Android enableSemanticSearch 开关语义）。
// - 人物解析：任务 D 已交付 PersonQueryResolver.resolvePersonIds(query:lang:db:)；
//   经 personIdsResolver 闭包注入（默认 nil = Android resolver 未注入，仅 LIKE 兜底）。
// - 反馈加权：任务 D 已交付 MediaFeedbackUseCase.scoreAdjustments(queryText:)；
//   可选注入，nil 时跳过（对齐 Android useCase 未注入行为）。

/// 语义召回抽象（任务 C SemanticSearchEngine 交付后适配注入）。
/// 签名与并行 API 约定一致（async 非 throwing）；任务 C 的具体类可直接声明遵守本协议。
protocol SemanticSearching {
    func searchByText(_ query: String, limitToIds: Set<Int64>?, topK: Int) async
        -> [(mediaId: Int64, score: Float)]
}

/// 翻译扩展 LRU 缓存（契约 §2.5，MediaSearchEngine.kt:53-70）：
/// access-order LRU，最大 64 条，key = "query|lang"；
/// 值 = SearchSynonyms.expand(query) ∪ tagTranslator.expandForSearch(query, lang)（保序并集）。
final class SearchTranslationCache {

    static let maxCacheSize = 64

    private let translator: TagTranslator
    private let lock = NSLock()
    private var map: [String: [String]] = [:]
    /// 访问序（最旧在前，最新在尾）
    private var order: [String] = []

    init(translator: TagTranslator) {
        self.translator = translator
    }

    /// 当前缓存条数（测试观测用）。
    var cachedCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return map.count
    }

    /// 测试观测：查询缓存命中值（不改变访问序）。
    func peek(query: String, lang: String) -> [String]? {
        lock.lock()
        defer { lock.unlock() }
        return map["\(query)|\(lang)"]
    }

    func expand(query: String, lang: String) -> [String] {
        let key = "\(query)|\(lang)"
        lock.lock()
        if let hit = map[key] {
            order.removeAll { $0 == key }
            order.append(key)
            lock.unlock()
            return hit
        }
        lock.unlock()

        // 计算放锁外（词表查找 + 可能的 MT 推理 ~50ms，避免长占锁）
        var combined = SearchSynonyms.expand(query)
        for candidate in translator.expandForSearch(query, lang: lang)
        where !combined.contains(candidate) {
            combined.append(candidate)
        }

        lock.lock()
        // 并发双检：后写覆盖即可（同 key 值等价）
        if map[key] == nil {
            order.append(key)
        } else {
            order.removeAll { $0 == key }
            order.append(key)
        }
        map[key] = combined
        while map.count > Self.maxCacheSize, !order.isEmpty {
            let eldest = order.removeFirst()
            map.removeValue(forKey: eldest)
        }
        lock.unlock()
        return combined
    }
}

final class MediaSearchEngine {

    /// 搜索诊断（TEMP：写 scan_debug.log 供 devicectl copy from 拉取，定位「女人」无结果后移除）
    func searchDiagLog(_ msg: String) {
        scanDebugLog("SearchDiag \(msg)")
        print("🔍[SearchDiag] \(msg)")
    }

    /// 生产单例（db 用 TagDatabase 默认路径）。
    /// 语义引擎已接任务 C 的 SemanticSearchEngine：模型未下载/加载失败时其内部置
    /// unavailable、searchByText 返回 [] —— 即契约的「仅 SQL 路径」降级，无需在此判空。
    static let shared: MediaSearchEngine = {
        let db = TagDatabase.shared
        let translator = TagTranslator.shared
        return MediaSearchEngine(
            db: db,
            tagTranslator: translator,
            explicitFirstPipeline: ExplicitFirstSearchPipeline(db: db, tagTranslator: translator),
            semanticEngine: SemanticSearchEngine(db: db),
            feedbackScoring: MediaFeedbackUseCase(db: db),
            personIdsResolver: { query, lang in
                PersonQueryResolver.resolvePersonIds(query: query, lang: lang, db: db)
            },
            langProvider: {
                LanguageManager.shared.currentLanguage == "english" ? "en" : "zh"
            })
    }()

    // MARK: - 常量（契约 §2.4，以实现常量为准）

    /// SQL 召回基础分权重
    static let sqlScoreWeight: Float = 0.25
    /// 语义召回相似度权重
    static let semanticScoreWeight: Float = 0.65
    /// 时间衰减权重
    static let timeScoreWeight: Float = 0.1
    /// 一天毫秒数
    static let msPerDay: Int64 = 86_400_000
    /// 近期照片天数阈值
    static let timeBoostRecentDays: Int64 = 30
    /// 一年内照片天数阈值
    static let timeBoostYearDays: Int64 = 365
    /// 近期照片时间 boost
    static let timeBoostRecent: Float = 0.3
    /// 一年内照片时间 boost
    static let timeBoostYear: Float = 0.15

    // MARK: - 依赖

    private let db: TagDatabase
    private let explicitFirstPipeline: ExplicitFirstSearchPipeline?
    private let semanticEngine: SemanticSearching?
    private let feedbackScoring: MediaFeedbackUseCase?
    /// 人物解析闭包（query, lang）→ personIds；包装任务 D 的静态接口，便于测试注入。
    private let personIdsResolver: ((String, String) -> [Int64])?
    /// filter 入口的界面语言来源（等价 Android userSettingsRepository.getAppLanguageBlocking，
    /// 默认 CHINESE）。
    private let langProvider: () -> String
    private let translationCache: SearchTranslationCache

    init(db: TagDatabase,
         tagTranslator: TagTranslator = .shared,
         explicitFirstPipeline: ExplicitFirstSearchPipeline? = nil,
         semanticEngine: SemanticSearching? = nil,
         feedbackScoring: MediaFeedbackUseCase? = nil,
         personIdsResolver: ((String, String) -> [Int64])? = nil,
         langProvider: @escaping () -> String = { "zh" }) {
        self.db = db
        self.explicitFirstPipeline = explicitFirstPipeline
        self.semanticEngine = semanticEngine
        self.feedbackScoring = feedbackScoring
        self.personIdsResolver = personIdsResolver
        self.langProvider = langProvider
        self.translationCache = SearchTranslationCache(translator: tagTranslator)
    }

    // MARK: - Gallery 搜索框入口（契约 §2.2）

    /// 自然语言搜索（三层混合检索）。
    /// - Parameters:
    ///   - query: 原始查询文本
    ///   - lang: 界面语言（"en" → 英文，其余 → 中文；对齐 Android uiLang，默认 CHINESE 由调用方决定）
    ///   - limitToIds: 非 nil 时每层返回前按 id ∈ limitToIds 过滤（Chat refine in-set 用，§2.2 step 7）
    ///   - enableSemanticSearch: 语义召回开关（语义引擎未注入时自动仅 SQL）
    func search(query: String,
                lang: String,
                limitToIds: Set<Int64>? = nil,
                enableSemanticSearch: Bool = true) async -> [SearchMediaRow] {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [] }

        func limitFilter(_ list: [SearchMediaRow]) -> [SearchMediaRow] {
            guard let limitToIds else { return list }
            return list.filter { limitToIds.contains($0.id) }
        }

        // Layer 0.5: 显式约束优先分段搜索（如"去年3月在室内小孩"）。
        // 仅当存在时间/地点这类真正的收窄约束时才短路；纯人物/概念查询（如"小孩"）不短路。
        let segmentedQuery = QuerySegmenter.segment(query)
        if segmentedQuery.hasNarrowingExplicit, let pipeline = explicitFirstPipeline {
            let explicitResults = pipeline.search(segmented: segmentedQuery, lang: lang)
            // 结果非空则直接返回（不再做语义召回与融合排序）；空则继续 Layer 1
            if !explicitResults.isEmpty {
                return limitFilter(explicitResults)
            }
        }

        // Layer 1: 规则匹配 → SQL 与语义召回并行 → 融合排序
        if let filter = QueryParser.parse(query, lang: lang), !filter.needsLlm {
            async let sqlResults = executeFilter(filter, rawQuery: query, lang: lang)
            async let semanticResults: [(mediaId: Int64, score: Float)] =
                (enableSemanticSearch && semanticEngine != nil)
                    ? searchSemantic(query: query, filter: filter)
                    : []
            let merged = mergeAndRank(
                sqlResults: await sqlResults,
                semanticResults: await semanticResults,
                query: query)
            return limitFilter(merged)
        }

        // Layer 2（LLM 解析）：iOS 入口无 llmSearch 回调，行为等价 Android llmSearch = nil，直接进兜底。

        // 兜底：全字段模糊搜索 + 语义召回，并行执行
        async let sqlResults = fallbackSqlSearch(query: query, lang: lang)
        async let semanticResults: [(mediaId: Int64, score: Float)] =
            (enableSemanticSearch && semanticEngine != nil)
                ? searchSemantic(query: query, filter: nil)
                : []
        let merged = mergeAndRank(
            sqlResults: await sqlResults,
            semanticResults: await semanticResults,
            query: query)
        return limitFilter(merged)
    }

    // MARK: - Chat/Agent 入口（契约 §2.3，filter 驱动 + limitToIds 集内过滤）

    /// 使用已标准化的 SearchFilter 直接搜索，跳过 QueryParser 规则解析。
    /// - Parameters:
    ///   - filter: 结构化过滤条件
    ///   - originalQuery: 用户原始查询文本（非空时替代派生 query 用于语义召回文本 /
    ///     人物解析 rawQuery / 反馈分 query 精确匹配；nil 时按契约 §2.3 从 filter 派生）
    ///   - limitToIds: refine 时在 prior 结果集内过滤
    ///   - enableSemanticSearch: 语义召回开关
    func search(filter: SearchFilter,
                originalQuery: String? = nil,
                limitToIds: Set<Int64>? = nil,
                enableSemanticSearch: Bool = true) async -> [SearchMediaRow] {
        func limitFilter(_ list: [SearchMediaRow]) -> [SearchMediaRow] {
            guard let limitToIds else { return list }
            return list.filter { limitToIds.contains($0.id) }
        }

        let lang = langProvider()
        let derivedQuery = filter.keywords.first
            ?? filter.ocrKeywords.first
            ?? filter.locationKeywords.first
            ?? filter.personName
            ?? ""
        let query: String
        if let originalQuery, !originalQuery.isEmpty {
            query = originalQuery
        } else {
            query = derivedQuery
        }

        // 人物名查询是精确约束：filter 含 personName 时关闭语义召回
        // （否则全库"长得像"的图片混入结果）。
        let personNameBlank = filter.personName?
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true
        let enableSemanticForFilter = enableSemanticSearch && personNameBlank

        async let sqlResults = executeFilter(filter, rawQuery: query, lang: lang)
        async let semanticResults: [(mediaId: Int64, score: Float)] =
            (enableSemanticForFilter && semanticEngine != nil
                && !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                ? searchSemantic(query: query, filter: filter)
                : []
        let merged = mergeAndRank(
            sqlResults: await sqlResults,
            semanticResults: await semanticResults,
            query: query)
        return limitFilter(merged)
    }

    // MARK: - Layer 2.5 语义召回（契约 §2.3/§5.4）

    /// 执行语义召回；候选集按契约 §5.4 计算（filter.keywords 故意忽略——
    /// 语义搜索的价值正是跨越标签词汇鸿沟）。引擎未注入 → 空（仅 SQL 路径）。
    private func searchSemantic(query: String,
                                filter: SearchFilter?) async -> [(mediaId: Int64, score: Float)] {
        guard let engine = semanticEngine else {
            searchDiagLog("semantic: engine nil → skip") // TEMP
            return []
        }
        let limitIds = semanticCandidateIds(filter: filter)
        // 候选集为空 → 跳过语义召回（契约 §5.4：任一步交集为空 → 返回空）
        if let limitIds, limitIds.isEmpty {
            searchDiagLog("semantic query='\(query)': candidate set EMPTY → skip") // TEMP
            return []
        }
        let results = await engine.searchByText(query, limitToIds: limitIds, topK: 50)
        searchDiagLog("semantic query='\(query)': limitIds=\(limitIds?.count ?? -1) results=\(results.count) topScores=\(results.prefix(3).map { $0.score })") // TEMP
        return results
    }

    /// 语义候选集过滤规则（契约 §5.4；Android SemanticSearchEngine.getFilteredCandidates
    /// 的 ID 交集部分，SemanticSearchEngine.kt:297-368）。
    /// - Returns: filter == nil → nil（全量 embedding 候选，由语义引擎自取）；
    ///   否则时间/人脸/OCR/地点逐维交集后的 ID 集
    private func semanticCandidateIds(filter: SearchFilter?) -> Set<Int64>? {
        guard let filter else { return nil }

        // 1. 基础集：timeRange 非空 → 时间范围；否则全量 embedding IDs
        var result: Set<Int64>
        if let range = filter.timeRange {
            result = Set(db.getMediaIdsByTimeRange(range.startMs, range.endMs))
        } else {
            result = Set(db.getMediaWithSemanticEmbeddingIds())
        }
        if result.isEmpty { return [] }

        // 2. 人脸过滤
        if filter.hasFaces == true {
            result.formIntersection(Set(db.getHasFaceIds()))
            if result.isEmpty { return [] }
        }

        // 3. OCR 关键词（每词命中并集，再 ∩）
        if !filter.ocrKeywords.isEmpty {
            var ocrMatched = Set<Int64>()
            for keyword in filter.ocrKeywords
            where !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ocrMatched.formUnion(db.searchOcrInIds(Array(result), keyword: keyword).map(\.id))
            }
            result.formIntersection(ocrMatched)
            if result.isEmpty { return [] }
        }

        // 4. 地点关键词（每词命中并集，再 ∩；Android 此步后无单独空检查，
        //    末尾 filteredIds.isEmpty 统一判——语义等价）
        if !filter.locationKeywords.isEmpty {
            var locMatched = Set<Int64>()
            for keyword in filter.locationKeywords
            where !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                locMatched.formUnion(db.getMediaIdsByLocationKeyword(keyword))
            }
            result.formIntersection(locMatched)
        }
        return result
    }

    // MARK: - Layer 3 融合排序（契约 §2.4 mergeAndRank，照抄公式）

    /// 融合排序，输出按分数降序的媒体列表。
    /// internal：单测验证权重公式（对齐 Android @VisibleForTesting 的可观测性意图）。
    func mergeAndRank(sqlResults: [SearchMediaRow],
                      semanticResults: [(mediaId: Int64, score: Float)],
                      query: String,
                      nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> [SearchMediaRow] {
        mergeAndRankWithScores(
            sqlResults: sqlResults,
            semanticResults: semanticResults,
            query: query,
            nowMs: nowMs
        ).map(\.media)
    }

    /// 融合排序并返回带分数的结果。
    ///
    /// 保真要点：
    /// - mediaMap SQL 先写入、语义结果同 id 可覆盖（iOS 同库行等价，覆盖无差异）；
    /// - 语义侧 C 接口只回 (mediaId, score)，行数据经 getMediaByIds 补取
    ///   （Android 由 SemanticScoredMedia 直接携带 MediaAsset）；
    /// - 排序键插入序对齐 Kotlin LinkedHashMap 迭代序；Swift 5 sorted 稳定排序，
    ///   tie 保持插入序（对齐 Kotlin sortedByDescending 稳定性——契约「无显式 tie-breaking」）；
    /// - daysSinceCapture 整数除法截断（Kotlin Long / Swift Int64 同语义）。
    func mergeAndRankWithScores(
        sqlResults: [SearchMediaRow],
        semanticResults: [(mediaId: Int64, score: Float)],
        query: String,
        nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) -> [ScoredMedia<SearchMediaRow>] {
        var scoreMap: [Int64: Float] = [:]
        var mediaMap: [Int64: SearchMediaRow] = [:]
        var order: [Int64] = []

        // SQL 侧：按列表顺序给基础分
        for (index, media) in sqlResults.enumerated() {
            if mediaMap[media.id] == nil { order.append(media.id) }
            mediaMap[media.id] = media
            let baseScore = Float(1.0) - Float(index) / Float(sqlResults.count + 1)
            scoreMap[media.id] = baseScore * Self.sqlScoreWeight
        }

        // 语义侧行数据补取（仅 SQL 未覆盖的 id）
        let missingIds = semanticResults.map(\.mediaId).filter { mediaMap[$0] == nil }
        var semanticRows: [Int64: SearchMediaRow] = [:]
        if !missingIds.isEmpty {
            for row in db.getMediaByIds(missingIds) { semanticRows[row.id] = row }
        }

        // 语义侧：叠加余弦相似度
        for scored in semanticResults {
            if mediaMap[scored.mediaId] == nil { order.append(scored.mediaId) }
            if let row = semanticRows[scored.mediaId] ?? mediaMap[scored.mediaId] {
                mediaMap[scored.mediaId] = row
            }
            scoreMap[scored.mediaId] =
                (scoreMap[scored.mediaId] ?? 0) + scored.score * Self.semanticScoreWeight
        }

        // 时间 boost：按拍摄时间距今天数
        for id in order {
            guard let media = mediaMap[id] else { continue }
            let daysSinceCapture = (nowMs - media.captureDate) / Self.msPerDay
            let timeBoost: Float
            if daysSinceCapture < Self.timeBoostRecentDays {
                timeBoost = Self.timeBoostRecent
            } else if daysSinceCapture < Self.timeBoostYearDays {
                timeBoost = Self.timeBoostYear
            } else {
                timeBoost = 0
            }
            scoreMap[id] = (scoreMap[id] ?? 0) + timeBoost * Self.timeScoreWeight
        }

        // 反馈权重（契约 §8；query 非空且 useCase 已注入时叠加，delta == 0 不动）
        applyFeedbackScores(scoreMap: &scoreMap, mediaMap: mediaMap, order: order, query: query)

        return order
            .filter { mediaMap[$0] != nil }
            .sorted { (scoreMap[$0] ?? 0) > (scoreMap[$1] ?? 0) }
            .compactMap { id in
                mediaMap[id].map { ScoredMedia(media: $0, score: scoreMap[id] ?? 0) }
            }
    }

    /// 叠加反馈分（契约 §8 + §2.4 applyFeedbackScores）：
    /// `delta = likeCount * 0.15 - dislikeCount * 0.15`（由 MediaFeedbackUseCase 内部计算），
    /// 直接加到总分；query 精确等值匹配（R10，不做 LIKE"改进"）。
    private func applyFeedbackScores(scoreMap: inout [Int64: Float],
                                     mediaMap: [Int64: SearchMediaRow],
                                     order: [Int64],
                                     query: String) {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return }
        guard let feedbackScoring else { return }
        let adjustments = feedbackScoring.scoreAdjustments(queryText: query)
        for id in order {
            guard let media = mediaMap[id],
                  let delta = adjustments[String(media.id)],
                  delta != 0 else { continue }
            scoreMap[id] = (scoreMap[id] ?? 0) + delta
        }
    }

    // MARK: - SQL 召回核心（契约 §2.6 executeFilter 候选集交集语义）

    /// 执行结构化过滤。**维度之间取交集（AND），同一维度内不同关键词取并集（OR）**。
    /// internal：单测验证交集与降级路径。
    func executeFilter(_ filter: SearchFilter, rawQuery: String, lang: String) -> [SearchMediaRow] {
        // 1. 显式约束候选集（时间 / 地点 / 人脸）—— 维度间交集
        var explicitCandidateSets: [Set<Int64>] = []

        if let range = filter.timeRange {
            explicitCandidateSets.append(
                Set(db.getMediaIdsByTimeRange(range.startMs, range.endMs)))
        }

        if !filter.locationKeywords.isEmpty {
            var locationIds = Set<Int64>()
            for keyword in filter.locationKeywords {
                locationIds.formUnion(db.searchByPlace(keyword).map(\.id))
                locationIds.formUnion(db.getMediaIdsByLocationKeyword(keyword))
            }
            explicitCandidateSets.append(locationIds)
        }

        if filter.hasFaces == true {
            explicitCandidateSets.append(Set(db.getHasFaceIds()))
        }

        let explicitCandidateIds: Set<Int64>? = explicitCandidateSets.isEmpty
            ? nil
            : explicitCandidateSets.reduce(explicitCandidateSets[0]) { $0.intersection($1) }

        // 🔍[SearchDiag] TEMP: 诊断「女人」无结果，定位后移除
        searchDiagLog("executeFilter query='\(rawQuery)' keywords=\(filter.keywords) hasFaces=\(String(describing: filter.hasFaces)) explicitSets=\(explicitCandidateSets.map(\.count)) explicit∩=\(explicitCandidateIds?.count ?? -1)")

        // 2. 内容关键词候选集（标签 / OCR / 文件名 / 人物名）—— 维度内并集
        var contentIds = Set<Int64>()
        let personNameBlank = filter.personName?
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true
        let hasContentKeywords = !filter.keywords.isEmpty
            || !filter.ocrKeywords.isEmpty || !personNameBlank

        // 人物名匹配：显式 personName + 每个关键词都可能命中自定义分组名称
        contentIds.formUnion(collectPersonMediaIds(filter: filter, rawQuery: rawQuery, lang: lang))

        if !filter.keywords.isEmpty || !filter.ocrKeywords.isEmpty {
            for keyword in filter.keywords {
                // 🔍[SearchDiag] TEMP
                let expansion = cachedExpandForSearch(keyword, lang: lang)
                searchDiagLog("expand('\(keyword)', lang=\(lang)) = \(expansion)")
                for candidate in expansion {
                    let hits = searchCandidateIds(candidate, candidateIds: explicitCandidateIds)
                    searchDiagLog("  candidate '\(candidate)' hits=\(hits.count)")
                    contentIds.formUnion(hits)
                }
            }
            for keyword in filter.ocrKeywords {
                for candidate in cachedExpandForSearch(keyword, lang: lang) {
                    contentIds.formUnion(
                        searchOcrCandidateIds(candidate, candidateIds: explicitCandidateIds))
                }
            }
        }

        // 3. 最终 ID = 显式约束 ∩ 内容关键词
        let finalIds: Set<Int64>
        switch (explicitCandidateIds, hasContentKeywords) {
        case (nil, false):
            finalIds = []
        case (nil, true):
            finalIds = contentIds
        case let (.some(explicit), false):
            finalIds = explicit
        case let (.some(explicit), true):
            finalIds = explicit.intersection(contentIds)
        }

        if finalIds.isEmpty {
            searchDiagLog("executeFilter finalIds EMPTY (explicit=\(explicitCandidateIds?.count ?? -1) content=\(contentIds.count))") // TEMP
            return []
        }
        searchDiagLog("executeFilter finalIds=\(finalIds.count) (explicit=\(explicitCandidateIds?.count ?? -1) content=\(contentIds.count))") // TEMP
        // 按 captureDate 降序（该顺序即 mergeAndRank 中 SQL 基础分的次序依据）
        return db.getMediaByIds(Array(finalIds))
            .sorted { $0.captureDate > $1.captureDate }
    }

    /// 搜索单个候选词在所有文本字段中的命中 ID（契约 §2.6 searchCandidateIds）。
    /// iOS 辅助表恒可用（等价 Android tagDao/ocrWordDao 已注入）。
    /// - Parameter candidateIds: 非 nil 时在候选集内搜索并返回子集；否则全局搜索
    private func searchCandidateIds(_ candidate: String, candidateIds: Set<Int64>?) -> Set<Int64> {
        var matched = Set<Int64>()

        // 辅助表精确匹配（先全局查，再与候选集取交集，避免缺少 in-ID 接口）
        matched.formUnion(db.searchByExactTag(candidate).map(\.id))
        matched.formUnion(db.searchByWordPrefix(candidate.lowercased()).map(\.id))

        if let candidateIds {
            let ids = Array(candidateIds)
            matched.formUnion(db.searchLabelsAllFieldsInIds(ids, keyword: candidate).map(\.id))
            matched.formUnion(db.searchFileNameInIds(ids, keyword: candidate).map(\.id))
            return matched.intersection(candidateIds)
        } else {
            matched.formUnion(db.searchByLabelAllFields(candidate).map(\.id))
            matched.formUnion(db.searchByFileName(candidate).map(\.id))
            return matched
        }
    }

    /// 搜索单个 OCR 候选词的命中 ID（契约 §2.6 searchOcrCandidateIds）。
    private func searchOcrCandidateIds(_ candidate: String, candidateIds: Set<Int64>?) -> Set<Int64> {
        var matched = Set<Int64>()

        matched.formUnion(db.searchByExactWord(candidate.lowercased()).map(\.id))

        if let candidateIds {
            let ids = Array(candidateIds)
            matched.formUnion(db.searchOcrInIds(ids, keyword: candidate).map(\.id))
            return matched.intersection(candidateIds)
        } else {
            matched.formUnion(db.searchByOcrText(candidate).map(\.id))
            return matched
        }
    }

    // MARK: - 人物解析入口（契约 §2.7 collectPersonMediaIds）

    /// 收集所有人物名匹配相关的媒体 ID。
    ///
    /// 解析优先级（personIdsResolver 已注入且 rawQuery 非空时）：
    /// 1. 命中 ≥2 个不同人物 → 共现查询（同框照片，每人至少一张脸）
    /// 2. 恰好命中 1 个 → 该人物全部媒体（含亲属称谓命中，如"我女儿"）
    /// 3. 0 命中 → 回落人名 LIKE 兜底（filter.personName trim 非空 + filter.keywords 全部词，
    ///    逐词 findPersonByName 模糊 LIMIT 1）
    private func collectPersonMediaIds(filter: SearchFilter,
                                       rawQuery: String,
                                       lang: String) -> Set<Int64> {
        if let resolver = personIdsResolver,
           !rawQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let resolvedIds = resolver(rawQuery, lang)
            if resolvedIds.count >= 2 {
                return Set(db.getMediaByPersonsCooccurrence(
                    resolvedIds, personCount: resolvedIds.count).map(\.id))
            } else if resolvedIds.count == 1 {
                return Set(db.getMediaByPerson(resolvedIds[0]).map(\.id))
            }
            // 0 命中：回落人名 LIKE 兜底
        }

        var names = Set<String>()
        if let personName = filter.personName?
            .trimmingCharacters(in: .whitespacesAndNewlines), !personName.isEmpty {
            names.insert(personName)
        }
        names.formUnion(filter.keywords.map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        })

        var ids = Set<Int64>()
        for name in names {
            if let person = db.findPersonByName(name) {
                ids.formUnion(db.getMediaByPerson(person.personId).map(\.id))
            }
        }
        return ids
    }

    // MARK: - 兜底 SQL（契约 §2.2 step 6）

    /// cachedExpandForSearch 候选词集合逐词 searchAll（全字段模糊）合并去重（按 id，保首次出现序）。
    private func fallbackSqlSearch(query: String, lang: String) -> [SearchMediaRow] {
        var seen = Set<Int64>()
        var out: [SearchMediaRow] = []
        for candidate in cachedExpandForSearch(query, lang: lang) {
            for row in db.searchAll(candidate) where seen.insert(row.id).inserted {
                out.append(row)
            }
        }
        return out
    }

    // MARK: - 跨语言扩展缓存（契约 §2.5）

    private func cachedExpandForSearch(_ query: String, lang: String) -> [String] {
        translationCache.expand(query: query, lang: lang)
    }
}
