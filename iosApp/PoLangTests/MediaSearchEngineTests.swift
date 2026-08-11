import XCTest
@testable import PoLang

/// 相册搜索引擎编排层测试（contracts.md §2 全流程 + §4.2 交集策略 + §2.5 LRU 缓存）。
///
/// 覆盖：
/// - §2.4 mergeAndRank 权重合成（0.25/0.65/0.1 公式、去重、tie 保持插入序、反馈分叠加 R10）
/// - §2.6 executeFilter 维度间交集 / 维度内并集 / 空交集
/// - §2.2 Layer 0.5 短路（非空直接返回、跳语义召回）与空交集回落 Layer 1
/// - §2.2 兜底路径（expandForSearch + searchAll ∥ 语义召回）
/// - §2.7 人物共现（≥2）/ 单人（=1）/ LIKE 回落（=0）三分支
/// - §2.3 filter 入口：personName 关闭语义召回、limitToIds 集内过滤
/// - §2.5 SearchTranslationCache LRU（64 条上限、access-order）
///
/// SemanticSearchEngine（任务 C）尚未交付：本文件用 StubSemanticEngine 实现
/// B2 定义的 SemanticSearching 协议隔离模型依赖。
final class MediaSearchEngineTests: XCTestCase {

    // MARK: - 测试基建

    private func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "engine_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    /// 建一条媒体并直写搜索相关列（与 SearchDatabaseTests 同风格）。
    @discardableResult
    private func makeMedia(_ db: TagDatabase, lid: String, captureDateMs: Int64,
                           fileName: String = "IMG.jpg", labels: String? = nil,
                           labelsEn: String? = nil, labelsZh: String? = nil,
                           ocrText: String? = nil, locationName: String? = nil,
                           hasFace: Bool = false) -> Int64 {
        let id = db.getOrCreateMedia(localIdentifier: lid, type: "IMAGE",
                                     captureDateMs: captureDateMs, fileName: fileName)
        func q(_ v: String?) -> String { v.map { "'\($0.replacingOccurrences(of: "'", with: "''"))'" } ?? "NULL" }
        db.exec("""
            UPDATE media_assets SET labels=\(q(labels)), labelsEn=\(q(labelsEn)), labelsZh=\(q(labelsZh)),
                ocrText=\(q(ocrText)), locationName=\(q(locationName)), hasFace=\(hasFace ? 1 : 0)
            WHERE id=\(id);
            """)
        return id
    }

    /// 直连构造 SearchMediaRow（mergeAndRank SQL 侧输入，无需落库）。
    private func makeRow(id: Int64, captureDate: Int64, fileName: String = "IMG.jpg",
                         labels: String? = nil) -> SearchMediaRow {
        SearchMediaRow(
            id: id, localIdentifier: "L\(id)", type: "IMAGE", captureDate: captureDate,
            fileName: fileName, duration: nil, hasFace: false, faceId: nil,
            labels: labels, labelsEn: nil, labelsZh: nil, ocrText: nil,
            latitude: nil, longitude: nil, locationName: nil, city: nil,
            indexedAt: nil, faceFocusY: nil, aestheticScore: nil, faceQualityScore: nil,
            semanticEmbedding: nil)
    }

    /// 写一条 face_embeddings 归属（person_id 归聚无公开写入侧，直接 SQL）。
    private func linkFace(_ db: TagDatabase, mediaId: Int64, personId: Int64) {
        db.exec("""
            INSERT INTO face_embeddings (media_id, person_id, embedding, created_at)
            VALUES (\(mediaId), \(personId), X'00', 1);
            """)
    }

    /// 语义召回 stub（SemanticSearching 协议，隔离任务 C 模型依赖）。
    private final class StubSemanticEngine: SemanticSearching {
        var calls: [(query: String, limitToIds: Set<Int64>?, topK: Int)] = []
        var results: [(mediaId: Int64, score: Float)] = []
        func searchByText(_ query: String, limitToIds: Set<Int64>?, topK: Int) async
            -> [(mediaId: Int64, score: Float)] {
            calls.append((query, limitToIds, topK))
            return results
        }
    }

    private func makeEngine(db: TagDatabase,
                            semantic: StubSemanticEngine? = nil,
                            pipeline: ExplicitFirstSearchPipeline? = nil,
                            feedback: MediaFeedbackUseCase? = nil,
                            personResolver: ((String, String) -> [Int64])? = nil,
                            lang: String = "zh") -> MediaSearchEngine {
        MediaSearchEngine(
            db: db,
            tagTranslator: TagTranslator(vocab: .empty()),
            explicitFirstPipeline: pipeline,
            semanticEngine: semantic,
            feedbackScoring: feedback,
            personIdsResolver: personResolver,
            langProvider: { lang })
    }

    // MARK: - §2.4 mergeAndRank 权重合成

    func testMergeAndRankWeightFormula() {
        let db = makeDb()
        let engine = makeEngine(db: db)
        let day = MediaSearchEngine.msPerDay
        let now: Int64 = 1_800_000_000_000

        // ⚠️ 硬编码 id 必须避开 db 自增 id（本用例 idC=1）：Android mediaMap 同 id 后者
        // 覆盖前者（契约 §2.4），id 相撞会让语义命中覆盖 SQL 行、结果集合并，测试语义全变。
        // A：SQL 第 0 位，10 天前（+0.3*0.1）；B：SQL 第 1 位 + 语义 0.5，100 天前（+0.15*0.1）
        let rowA = makeRow(id: 9001, captureDate: now - 10 * day)
        let rowB = makeRow(id: 9002, captureDate: now - 100 * day)
        // C：仅语义命中（0.8），400 天前（无时间 boost）；语义侧行数据经 db 补取
        let idC = makeMedia(db, lid: "C", captureDateMs: now - 400 * day)

        let scored = engine.mergeAndRankWithScores(
            sqlResults: [rowA, rowB],
            semanticResults: [(mediaId: 9002, score: 0.5), (mediaId: idC, score: 0.8)],
            query: "猫",
            nowMs: now)

        XCTAssertEqual(scored.map { $0.media.id }, [idC, 9002, 9001], "分数降序：C(0.52) > B(0.5067) > A(0.28)")
        // A = (1 - 0/3)*0.25 + 0.3*0.1 = 0.28
        XCTAssertEqual(scored[2].score, Float(0.28), accuracy: 0.001)
        // B = (1 - 1/3)*0.25 + 0.5*0.65 + 0.15*0.1 ≈ 0.5067
        XCTAssertEqual(scored[1].score, Float(0.5066667), accuracy: 0.001)
        // C = 0.8*0.65 = 0.52（SQL/语义 map key 去重天然完成）
        XCTAssertEqual(scored[0].score, Float(0.52), accuracy: 0.001)
    }

    func testMergeAndRankTieKeepsInsertionOrder() {
        let db = makeDb()
        let engine = makeEngine(db: db)
        let now: Int64 = 1_800_000_000_000
        // 两个仅语义命中、同分同日期 → tie 按插入序（Kotlin LinkedHashMap 迭代序 + 稳定排序）
        let id1 = makeMedia(db, lid: "S1", captureDateMs: now - 400 * MediaSearchEngine.msPerDay)
        let id2 = makeMedia(db, lid: "S2", captureDateMs: now - 400 * MediaSearchEngine.msPerDay)

        let scored = engine.mergeAndRankWithScores(
            sqlResults: [],
            semanticResults: [(mediaId: id1, score: 0.5), (mediaId: id2, score: 0.5)],
            query: "q",
            nowMs: now)
        XCTAssertEqual(scored.map { $0.media.id }, [id1, id2], "同分保持语义结果列表顺序")
    }

    func testMergeAndRankFeedbackAdjustmentExactQueryMatch() {
        let db = makeDb()
        let day = MediaSearchEngine.msPerDay
        let now: Int64 = 1_800_000_000_000
        let idD = makeMedia(db, lid: "D", captureDateMs: now - 10 * day)
        // 反馈落库：query_text = "猫" 精确等值（R10）
        db.insertMediaFeedback(mediaId: String(idD), feedbackType: "like", queryText: "猫", sessionId: "")

        let engine = makeEngine(db: db, feedback: MediaFeedbackUseCase(db: db))
        let row = makeRow(id: idD, captureDate: now - 10 * day)

        let withHit = engine.mergeAndRankWithScores(
            sqlResults: [row], semanticResults: [], query: "猫", nowMs: now)
        let noHit = engine.mergeAndRankWithScores(
            sqlResults: [row], semanticResults: [], query: "猫咪", nowMs: now)

        // base = (1 - 0/(1+1))*0.25 = 0.25（契约 §2.4：index 从 0 起，分母 size+1）；
        // 时间 10 天 → +0.3*0.1 → 0.28
        XCTAssertEqual(noHit[0].score, Float(0.28), accuracy: 0.001,
                       "query 非精确等值（猫咪 ≠ 猫）→ 反馈不生效（R10）")
        XCTAssertEqual(withHit[0].score - noHit[0].score, Float(0.15), accuracy: 0.001,
                       "LIKE_BONUS = 0.15 叠加")
    }

    func testFeedbackSkippedWhenUseCaseNotInjected() {
        let db = makeDb()
        let day = MediaSearchEngine.msPerDay
        let now: Int64 = 1_800_000_000_000
        let idD = makeMedia(db, lid: "D", captureDateMs: now - 10 * day)
        db.insertMediaFeedback(mediaId: String(idD), feedbackType: "like", queryText: "猫", sessionId: "")
        // useCase 未注入 → 跳过反馈叠加（与 Android useCase = null 行为一致）
        let engine = makeEngine(db: db)
        let scored = engine.mergeAndRankWithScores(
            sqlResults: [makeRow(id: idD, captureDate: now - 10 * day)],
            semanticResults: [], query: "猫", nowMs: now)
        XCTAssertEqual(scored[0].score, Float(0.28), accuracy: 0.001,
                       "base = (1 - 0/(1+1))*0.25 = 0.25 + 时间 0.03 = 0.28，无反馈叠加")
    }

    // MARK: - §2.6 executeFilter 交集语义

    func testExecuteFilterIntersectsTimeRangeAndKeyword() {
        let db = makeDb()
        let engine = makeEngine(db: db)
        let in1 = makeMedia(db, lid: "IN1", captureDateMs: 1000, labels: "[\"猫\"]")
        _ = makeMedia(db, lid: "OUT", captureDateMs: 9999, labels: "[\"猫\"]")   // 范围外命中关键词
        _ = makeMedia(db, lid: "IN2", captureDateMs: 2000, labels: "[\"狗\"]")   // 范围内不命中

        let filter = SearchFilter(
            timeRange: SearchTimeRange(startMs: 500, endMs: 3000), keywords: ["猫"])
        let rows = engine.executeFilter(filter, rawQuery: "猫", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [in1], "时间 ∩ 关键词 = AND 语义")
    }

    func testExecuteFilterEmptyIntersectionReturnsEmpty() {
        let db = makeDb()
        let engine = makeEngine(db: db)
        _ = makeMedia(db, lid: "OUT", captureDateMs: 9999, labels: "[\"猫\"]")
        let filter = SearchFilter(
            timeRange: SearchTimeRange(startMs: 500, endMs: 3000), keywords: ["猫"])
        XCTAssertTrue(engine.executeFilter(filter, rawQuery: "猫", lang: "zh").isEmpty)
    }

    func testExecuteFilterKeywordOrWithinDimension() {
        let db = makeDb()
        let engine = makeEngine(db: db)
        let a = makeMedia(db, lid: "A", captureDateMs: 100, labels: "[\"猫\"]")
        let b = makeMedia(db, lid: "B", captureDateMs: 200, fileName: "狗_01.jpg")
        _ = makeMedia(db, lid: "C", captureDateMs: 300, labels: "[\"车\"]")
        // 同一维度内不同关键词取并集（OR）
        let rows = engine.executeFilter(
            SearchFilter(keywords: ["猫", "狗"]), rawQuery: "猫 狗", lang: "zh")
        XCTAssertEqual(Set(rows.map { $0.id }), Set([a, b]))
    }

    func testExecuteFilterLocationOnly() {
        let db = makeDb()
        let engine = makeEngine(db: db)
        let a = makeMedia(db, lid: "A", captureDateMs: 100, locationName: "上海市徐汇区")
        _ = makeMedia(db, lid: "B", captureDateMs: 200, locationName: "北京市朝阳区")
        let rows = engine.executeFilter(
            SearchFilter(locationKeywords: ["上海"]), rawQuery: "上海", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [a], "无内容关键词 → 显式候选集即结果")
    }

    // MARK: - §2.2 Layer 0.5 短路与回落

    func testLayer0ShortCircuitReturnsPipelineResultAndSkipsSemantic() async {
        let db = makeDb()
        let lastYear = QueryParser.parseTimeRange("去年", now: Date())!
        let inYear = makeMedia(db, lid: "LY", captureDateMs: (lastYear.startMs + lastYear.endMs) / 2)
        _ = makeMedia(db, lid: "TY", captureDateMs: Int64(Date().timeIntervalSince1970 * 1000))

        let stub = StubSemanticEngine()
        let engine = makeEngine(
            db: db, semantic: stub,
            pipeline: ExplicitFirstSearchPipeline(db: db, tagTranslator: TagTranslator(vocab: .empty())))

        // "去年的照片" → TIME 段 → hasNarrowingExplicit → 短路；content 空 → 候选集全部
        let rows = await engine.search(query: "去年的照片", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [inYear])
        XCTAssertTrue(stub.calls.isEmpty, "短路结果非空直接返回，不做语义召回与融合排序")
    }

    func testPurePersonQueryDoesNotShortCircuit() async {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100, labels: "[\"小孩\"]", hasFace: true)
        // 语义候选基础集 = 有 embedding 的媒体（§5.4）：A 需带 embedding 才会走到 stub
        db.exec("UPDATE media_assets SET semanticEmbedding='stub' WHERE id=\(a);")
        let stub = StubSemanticEngine()
        let engine = makeEngine(
            db: db, semantic: stub,
            pipeline: ExplicitFirstSearchPipeline(db: db, tagTranslator: TagTranslator(vocab: .empty())))

        // "小孩" → PERSON 段但无 TIME/LOCATION → 不短路，走 Layer 1 SQL∥语义 → mergeAndRank
        let rows = await engine.search(query: "小孩", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [a])
        XCTAssertEqual(stub.calls.count, 1, "非短路路径语义召回被调用（topK=50）")
        XCTAssertEqual(stub.calls[0].topK, 50)
        XCTAssertEqual(stub.calls[0].limitToIds, [a], "§5.4 候选集 = embedding 基础集 ∩ hasFaces")
    }

    func testLayer0EmptyIntersectionFallsBackToLayer1() async {
        let db = makeDb()
        let lastYear = QueryParser.parseTimeRange("去年", now: Date())!
        let mid = (lastYear.startMs + lastYear.endMs) / 2
        // M1：去年，仅 tags 辅助表有精确标签「猫」（labels 三字段均无 → pipeline 候选集内查不到）
        let m1 = makeMedia(db, lid: "M1", captureDateMs: mid)
        let tagId = db.upsertTag(name: "猫", category: "animal")
        db.insertMediaTag(mediaId: m1, tagId: tagId)
        // M2：今年 labels 命中「猫」（时间范围外，两层都不应召回）
        _ = makeMedia(db, lid: "M2",
                      captureDateMs: Int64(Date().timeIntervalSince1970 * 1000), labels: "[\"猫\"]")

        let stub = StubSemanticEngine()
        let engine = makeEngine(
            db: db, semantic: stub,
            pipeline: ExplicitFirstSearchPipeline(db: db, tagTranslator: TagTranslator(vocab: .empty())))

        // "去年猫" → TIME+OBJECT 段 → 短路管线：候选集内 labels/fileName 无命中 → 空（不降级）
        // → 回落 Layer 1：executeFilter 的 searchCandidateIds 走 tags 精确表命中 M1
        let rows = await engine.search(query: "去年猫", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [m1], "pipeline 空交集回落 Layer 1 由辅助表召回")
        XCTAssertEqual(stub.calls.count, 1, "回落 Layer 1 后语义召回恢复")
    }

    // MARK: - §2.2 兜底路径

    func testFallbackPathSearchAllPlusSemantic() async {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let a = makeMedia(db, lid: "A", captureDateMs: now, fileName: "我的照片001.jpg")
        let b = makeMedia(db, lid: "B", captureDateMs: now)

        let stub = StubSemanticEngine()
        stub.results = [(mediaId: b, score: 0.9)]
        let engine = makeEngine(db: db, semantic: stub)

        // "的照片"：分词后无 TIME/LOCATION（不短路）；QueryParser 停用词剥光 → nil（needsLlm）→ 兜底
        let rows = await engine.search(query: "的照片", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [b, a],
                       "兜底 = searchAll(『的照片』) ∪ 语义召回 → mergeAndRank；语义 0.9*0.65 排前")
        XCTAssertEqual(stub.calls.count, 1)
        XCTAssertNil(stub.calls[0].limitToIds, "filter == nil → 语义候选全量（nil）")
    }

    // MARK: - §2.7 collectPersonMediaIds 三分支

    /// 造人物数据：p1 阿明（media X、Y），p2 阿红（media X）→ X 双人同框、Y 仅 p1。
    private func makePersonFixture(_ db: TagDatabase) -> (p1: Int64, p2: Int64, x: Int64, y: Int64) {
        let x = makeMedia(db, lid: "X", captureDateMs: 200, hasFace: true)
        let y = makeMedia(db, lid: "Y", captureDateMs: 100, hasFace: true)
        let p1 = db.insertPerson(name: "阿明", coverMediaId: nil, faceCount: 2, isSelf: false)
        let p2 = db.insertPerson(name: "阿红", coverMediaId: nil, faceCount: 1, isSelf: false)
        linkFace(db, mediaId: x, personId: p1)
        linkFace(db, mediaId: x, personId: p2)
        linkFace(db, mediaId: y, personId: p1)
        return (p1, p2, x, y)
    }

    func testCollectPersonCooccurrenceWhenTwoOrMoreResolved() {
        let db = makeDb()
        let f = makePersonFixture(db)
        let engine = makeEngine(db: db, personResolver: { _, _ in [f.p1, f.p2] })
        // ≥2 人 → 共现（每人至少一张脸同框）
        let rows = engine.executeFilter(
            SearchFilter(keywords: ["无关词"]), rawQuery: "阿明和阿红", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [f.x], "共现只含双人同框的 X")
    }

    func testCollectPersonSingleWhenExactlyOneResolved() {
        let db = makeDb()
        let f = makePersonFixture(db)
        let engine = makeEngine(db: db, personResolver: { _, _ in [f.p1] })
        // =1 人 → 该人物全部媒体（captureDate 降序）
        let rows = engine.executeFilter(
            SearchFilter(keywords: ["无关词"]), rawQuery: "阿明", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [f.x, f.y])
    }

    func testCollectPersonLikeFallbackWhenZeroResolved() {
        let db = makeDb()
        let f = makePersonFixture(db)
        let engine = makeEngine(db: db, personResolver: { _, _ in [] })
        // 0 命中 → LIKE 兜底：keywords 逐词 findPersonByName 模糊命中
        let rows = engine.executeFilter(
            SearchFilter(keywords: ["阿明"]), rawQuery: "阿明", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [f.x, f.y])
    }

    func testCollectPersonLikeFallbackWhenResolverNotInjected() {
        let db = makeDb()
        let f = makePersonFixture(db)
        let engine = makeEngine(db: db) // resolver = nil（对齐 Android personQueryResolver 未注入）
        let rows = engine.executeFilter(
            SearchFilter(personName: "阿红"), rawQuery: "阿红", lang: "zh")
        XCTAssertEqual(rows.map { $0.id }, [f.x], "personName 显式指定 → LIKE 兜底命中 p2 的媒体")
    }

    // MARK: - §2.3 filter 入口

    func testFilterSearchPersonNameDisablesSemantic() async {
        let db = makeDb()
        let f = makePersonFixture(db)
        // 关键词 filter 的对照组媒体（带 embedding 使语义候选基础集非空）
        let z = makeMedia(db, lid: "Z", captureDateMs: 300, labels: "[\"猫\"]")
        db.exec("UPDATE media_assets SET semanticEmbedding='stub' WHERE id=\(z);")
        let stub = StubSemanticEngine()
        let engine = makeEngine(db: db, semantic: stub)

        let rows = await engine.search(filter: SearchFilter(personName: "阿明"))
        XCTAssertEqual(rows.map { $0.id }, [f.x, f.y])
        XCTAssertTrue(stub.calls.isEmpty,
                      "personName 是精确约束：filter 含 personName 时关闭语义召回")

        let rows2 = await engine.search(filter: SearchFilter(keywords: ["猫"]))
        XCTAssertEqual(stub.calls.count, 1, "无 personName 的 filter 恢复语义召回")
        XCTAssertEqual(rows2.map { $0.id }, [z])
    }

    func testLimitToIdsAppliedOnQuerySearch() async {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100, labels: "[\"猫\"]")
        let b = makeMedia(db, lid: "B", captureDateMs: 200, labels: "[\"猫\"]")
        let engine = makeEngine(db: db)
        let rows = await engine.search(query: "猫", lang: "zh", limitToIds: [a])
        XCTAssertEqual(rows.map { $0.id }, [a], "每层返回前应用 limitToIdsFilter（refine in-set）")
        XCTAssertFalse(rows.contains { $0.id == b })
    }

    // MARK: - §2.5 LRU 翻译缓存

    func testTranslationCacheLruEviction() {
        let cache = SearchTranslationCache(translator: TagTranslator(vocab: .empty()))
        for i in 0..<SearchTranslationCache.maxCacheSize {
            _ = cache.expand(query: "q\(i)", lang: "zh")
        }
        XCTAssertEqual(cache.cachedCount, 64)

        // 访问最旧的 q0 → 变为最新；插入 q64 → 淘汰此时最旧的 q1
        XCTAssertNotNil(cache.peek(query: "q0", lang: "zh"))
        _ = cache.expand(query: "q0", lang: "zh")
        _ = cache.expand(query: "q64", lang: "zh")
        XCTAssertEqual(cache.cachedCount, 64, "MAX_CACHE_SIZE = 64")
        XCTAssertNil(cache.peek(query: "q1", lang: "zh"), "access-order LRU：q1 被淘汰")
        XCTAssertNotNil(cache.peek(query: "q0", lang: "zh"))
        XCTAssertNotNil(cache.peek(query: "q64", lang: "zh"))

        // key = "query|lang"：同 query 不同 lang 各占一条
        _ = cache.expand(query: "q0", lang: "en")
        XCTAssertNotNil(cache.peek(query: "q0", lang: "en"))
    }

    func testCachedExpandCombinesSynonymsAndTranslator() {
        let vocabData = """
            {"zh_to_en": {"猫": "cat"}, "en_to_zh": {}, "en_synonyms": {}}
            """.data(using: .utf8)!
        let translator = TagTranslator(vocab: BilingualVocab.parse(jsonData: vocabData)!)
        let cache = SearchTranslationCache(translator: translator)
        // "女性"：SearchSynonyms 扩「女」+ translator（无命中仅原词）→ [女, 女性]（并集去重）
        XCTAssertEqual(cache.expand(query: "女性", lang: "zh"), ["女", "女性"])
        // "猫"：synonyms 无 → translator zhToEn 命中 → [猫, cat]
        XCTAssertEqual(cache.expand(query: "猫", lang: "zh"), ["猫", "cat"])
    }
}
