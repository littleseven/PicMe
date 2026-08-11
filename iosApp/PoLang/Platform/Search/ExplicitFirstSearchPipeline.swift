import Foundation

// MARK: - 显式约束优先的搜索管道（双端契约 SSOT: contracts.md §4.2，Layer 0.5 短路管线）
//
// 逐字对齐 Android `app/domain/search/ExplicitFirstSearchPipeline.kt:32-201`。规则：
// 1. 先执行显式约束（时间、地点、人脸）各自成 set，**全部交集**得候选集；
// 2. 候选集内再执行内容关键词匹配（带跨语言扩展）；
// 3. 无任何显式约束 → 全局内容关键词搜索。
// ⚠️ 与 MediaSearchEngine.executeFilter 的差异（契约 §4.2 vs §2.6）：
//    - 地点候选**仅** getMediaIdsByLocationKeyword（不含 location_hierarchy 辅助表）；
//    - 关键词主表匹配走 searchLabelsAllFieldsInIds（labels/labelsEn/labelsZh 三字段 OR）；
//    - 空交集**不降级**（命中为空直接返回空，由上层 MediaSearchEngine 回落 Layer 1）。

final class ExplicitFirstSearchPipeline {

    private let db: TagDatabase
    private let tagTranslator: TagTranslator

    init(db: TagDatabase, tagTranslator: TagTranslator) {
        self.db = db
        self.tagTranslator = tagTranslator
    }

    /// 使用已经分段的查询执行搜索（契约 §4.2 入口）。结果按 captureDate 降序。
    func search(segmented: SegmentedQuery, lang: String, now: Date = Date()) -> [SearchMediaRow] {
        let (explicit, content) = segmented.toFilters(now: now)
        return search(explicit: explicit, content: content, lang: lang)
    }

    /// 使用显式约束和内容过滤条件执行搜索（internal：测试入口；Android 同名方法为 public）。
    func search(explicit: ExplicitFilter, content: ContentFilter, lang: String) -> [SearchMediaRow] {
        guard let candidateIds = resolveCandidateIds(explicit) else {
            return searchGlobal(content: content, lang: lang)
        }
        return searchInCandidates(candidateIds: candidateIds, content: content, lang: lang)
    }

    /// 根据显式约束解析候选媒体 ID 集合；若没有任何显式约束则返回 nil，表示全局搜索。
    func resolveCandidateIds(_ explicit: ExplicitFilter) -> Set<Int64>? {
        var candidateSets: [Set<Int64>] = []

        if let range = explicit.timeRange {
            candidateSets.append(Set(db.getMediaIdsByTimeRange(range.startMs, range.endMs)))
        }

        if !explicit.locationKeywords.isEmpty {
            // ⚠️ 契约 §4.2 step 1：仅 getMediaIdsByLocationKeyword，词间并集
            var locationIds = Set<Int64>()
            for keyword in explicit.locationKeywords {
                locationIds.formUnion(db.getMediaIdsByLocationKeyword(keyword))
            }
            candidateSets.append(locationIds)
        }

        if explicit.hasFaces == true {
            candidateSets.append(Set(db.getMediaIdsByHasFace()))
        }

        guard !candidateSets.isEmpty else { return nil }
        return candidateSets.reduce(candidateSets[0]) { $0.intersection($1) }
    }

    /// 在候选集中执行内容关键词搜索（带跨语言扩展）。
    private func searchInCandidates(candidateIds: Set<Int64>,
                                    content: ContentFilter,
                                    lang: String) -> [SearchMediaRow] {
        if candidateIds.isEmpty { return [] }
        if content.keywords.isEmpty && content.ocrKeywords.isEmpty {
            // content 为空 → 返回候选集全部媒体，按 captureDate 降序
            return db.getMediaByIds(Array(candidateIds))
                .sorted { $0.captureDate > $1.captureDate }
        }

        let ids = Array(candidateIds)
        var matchedIds = Set<Int64>()

        for keyword in content.keywords {
            // 先尝试人物分组名匹配（命中并入，结果已与候选集交集）
            if let personIds = personMediaIds(keyword: keyword, candidateIds: candidateIds) {
                matchedIds.formUnion(personIds)
            }
            for candidate in tagTranslator.expandForSearch(keyword, lang: lang) {
                matchedIds.formUnion(db.searchLabelsAllFieldsInIds(ids, keyword: candidate).map(\.id))
                matchedIds.formUnion(db.searchFileNameInIds(ids, keyword: candidate).map(\.id))
            }
        }

        for keyword in content.ocrKeywords {
            for candidate in tagTranslator.expandForSearch(keyword, lang: lang) {
                matchedIds.formUnion(db.searchOcrInIds(ids, keyword: candidate).map(\.id))
            }
        }

        // 命中为空 → 返回空（空交集不降级，由上层回落 Layer 1）
        if matchedIds.isEmpty { return [] }
        return db.getMediaByIds(Array(matchedIds))
            .sorted { $0.captureDate > $1.captureDate }
    }

    /// 全局内容关键词搜索（无显式约束时，带跨语言扩展）。
    private func searchGlobal(content: ContentFilter, lang: String) -> [SearchMediaRow] {
        if content.keywords.isEmpty && content.ocrKeywords.isEmpty { return [] }

        var matchedIds = Set<Int64>()
        for keyword in content.keywords {
            if let personIds = personMediaIds(keyword: keyword, candidateIds: nil) {
                matchedIds.formUnion(personIds)
            }
            for candidate in tagTranslator.expandForSearch(keyword, lang: lang) {
                matchedIds.formUnion(db.searchByLabelAllFields(candidate).map(\.id))
            }
        }
        for keyword in content.ocrKeywords {
            for candidate in tagTranslator.expandForSearch(keyword, lang: lang) {
                matchedIds.formUnion(db.searchByOcrText(candidate).map(\.id))
            }
        }

        if matchedIds.isEmpty { return [] }
        return db.getMediaByIds(Array(matchedIds))
            .sorted { $0.captureDate > $1.captureDate }
    }

    /// 将关键词作为人物分组名称进行匹配（对齐 Android searchPersonByNameCandidate；
    /// iOS person 表恒可用，等价 Android personDao 已注入）。
    /// - Returns: 命中人物的媒体 ID 集合（candidateIds 非 nil 时已∩候选集）；未命中 → nil
    private func personMediaIds(keyword: String, candidateIds: Set<Int64>?) -> Set<Int64>? {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let person = db.findPersonByName(trimmed) else { return nil }
        let ids = Set(db.getMediaByPerson(person.personId).map(\.id))
        return candidateIds.map { ids.intersection($0) } ?? ids
    }
}

extension SearchMediaRow {

    /// 契约 §4.2 step 5（Android MediaEntity.labelsForLanguage，MediaEntity.kt:65-66）：
    /// 英文 UI → labelsEn，其余 → labelsZh；目标字段为空（老数据未回填）时回退 labels。
    ///
    /// 设计说明：Android 在 pipeline toDomain 时即按 UI 语言收敛到单字段 labels；
    /// iOS SearchMediaRow 保留三字段原样上行，由展示层经本方法选择（信息无损超集）。
    func labelsForLanguage(_ lang: String) -> String? {
        lang.lowercased() == "en" ? (labelsEn ?? labels) : (labelsZh ?? labels)
    }
}
