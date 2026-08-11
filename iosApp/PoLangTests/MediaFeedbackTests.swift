import XCTest
@testable import PoLang

/// 反馈加权测试（contracts.md §8 + §9.6 resolveTarget）。
/// 覆盖：record 四种 target 解析（Ordinal 1-based / Description / MediaId / LastShown）、
/// scoreAdjustments 权重计算（LIKE_BONUS=DISLIKE_PENALTY=0.15、delta==0 不出现）、
/// query_text 精确等值（R10）、feedbackType = action rawValue lowercase。
final class MediaFeedbackTests: XCTestCase {

    private func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "feedback_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    private func makeRow(_ db: TagDatabase, lid: String, captureDateMs: Int64,
                         fileName: String = "IMG.jpg", labels: String? = nil) -> SearchMediaRow {
        let id = db.getOrCreateMedia(localIdentifier: lid, type: "IMAGE",
                                     captureDateMs: captureDateMs, fileName: fileName)
        func q(_ v: String?) -> String { v.map { "'\($0.replacingOccurrences(of: "'", with: "''"))'" } ?? "NULL" }
        db.exec("UPDATE media_assets SET labels=\(q(labels)) WHERE id=\(id);")
        return db.getMediaByIds([id])[0]
    }

    // MARK: - record：target 解析（§9.6 resolveTarget）

    func testRecordOrdinalOneBased() {
        let db = makeDb()
        let r1 = makeRow(db, lid: "A", captureDateMs: 100)
        let r2 = makeRow(db, lid: "B", captureDateMs: 200)
        let uc = MediaFeedbackUseCase(db: db)
        uc.record(target: .ordinal(2), action: .like, queryHint: "猫", shownResults: [r1, r2])
        let counts = db.feedbackLikeDislikeCounts(queryText: "猫")
        XCTAssertEqual(counts[String(r2.id)]?.likeCount, 1, "ordinal(2) → 第 2 项（1-based）")
        XCTAssertNil(counts[String(r1.id)])
    }

    func testRecordOrdinalClampsAndOutOfRange() {
        let db = makeDb()
        let r1 = makeRow(db, lid: "A", captureDateMs: 100)
        let uc = MediaFeedbackUseCase(db: db)
        // 负值钳到 0 → 首项（ChatViewModel.kt:1642 coerceAtLeast(0)）
        uc.record(target: .ordinal(-3), action: .like, queryHint: "q", shownResults: [r1])
        uc.record(target: .ordinal(0), action: .like, queryHint: "q", shownResults: [r1])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "q")[String(r1.id)]?.likeCount, 2)
        // 越界 → 解析失败，不写库（§9.6：getOrNull 越界返 null）。
        // 注意：r1 因上面两条 like 已有分组行（§8 SQL GROUP BY media_id），
        // dislikeCount 是 SUM(CASE...) 的 0 而非 nil——断言「未新增 dislike」应判 == 0。
        uc.record(target: .ordinal(5), action: .dislike, queryHint: "q", shownResults: [r1])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "q")[String(r1.id)]?.dislikeCount, 0)
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "q")[String(r1.id)]?.likeCount, 2, "like 计数不受越界影响")
        // 空结果集 → 不写库
        uc.record(target: .lastShown, action: .like, queryHint: "q2", shownResults: [])
        XCTAssertTrue(db.feedbackLikeDislikeCounts(queryText: "q2").isEmpty)
    }

    func testRecordLastShown() {
        let db = makeDb()
        let r1 = makeRow(db, lid: "A", captureDateMs: 100)
        let r2 = makeRow(db, lid: "B", captureDateMs: 200)
        MediaFeedbackUseCase(db: db).record(target: .lastShown, action: .dislike,
                                            queryHint: "夜景", shownResults: [r1, r2])
        let counts = db.feedbackLikeDislikeCounts(queryText: "夜景")
        XCTAssertEqual(counts[String(r1.id)]?.dislikeCount, 1, "LastShown → 首项")
        XCTAssertNil(counts[String(r2.id)])
    }

    func testRecordMediaId() {
        let db = makeDb()
        let r1 = makeRow(db, lid: "A", captureDateMs: 100)
        let r2 = makeRow(db, lid: "B", captureDateMs: 200)
        let uc = MediaFeedbackUseCase(db: db)
        // mediaId = asset.id.toString()（ChatViewModel.kt:1778）
        uc.record(target: .mediaId(String(r2.id)), action: .like, queryHint: "狗", shownResults: [r1, r2])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "狗")[String(r2.id)]?.likeCount, 1)
        // 不存在的 id → 不写库
        uc.record(target: .mediaId("99999"), action: .like, queryHint: "狗", shownResults: [r1, r2])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "狗")[String(r2.id)]?.likeCount, 1)
    }

    func testRecordDescription() {
        let db = makeDb()
        let r1 = makeRow(db, lid: "A", captureDateMs: 100, fileName: "IMG_0001.jpg",
                         labels: "{\"tags\":[\"cat\",\"park\"]}")
        let r2 = makeRow(db, lid: "B", captureDateMs: 200, fileName: "beach_01.jpg",
                         labels: "{\"tags\":[\"dog\"]}")
        let uc = MediaFeedbackUseCase(db: db)
        // 任一词 contains 命中 labels.tags（ignoreCase）
        uc.record(target: .description("那只 CAT"), action: .like, queryHint: "动物", shownResults: [r1, r2])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "动物")[String(r1.id)]?.likeCount, 1)
        // 命中 fileName（按空白切词，第一个命中的项）
        uc.record(target: .description("beach"), action: .like, queryHint: "动物", shownResults: [r1, r2])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "动物")[String(r2.id)]?.likeCount, 1)
        // 空白描述 → 不命中（terms 为空）
        uc.record(target: .description("   "), action: .like, queryHint: "动物", shownResults: [r1, r2])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "动物")[String(r1.id)]?.likeCount, 1, "无新增")
        // 无命中 → 不写库（计数不变，仍只有 r1/r2 两条）
        uc.record(target: .description("zebra"), action: .like, queryHint: "动物", shownResults: [r1, r2])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "动物").count, 2)
    }

    func testRecordFeedbackTypeAndQueryText() {
        let db = makeDb()
        let r1 = makeRow(db, lid: "A", captureDateMs: 100)
        let uc = MediaFeedbackUseCase(db: db)
        // feedbackType = action.name.lowercase()（MediaFeedbackRepositoryImpl.kt:21）
        uc.record(target: .lastShown, action: .dislike, queryHint: "猫", shownResults: [r1])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "猫")[String(r1.id)]?.dislikeCount, 1)
        // queryHint nil → query_text ""（Android 无快照时取 ""）
        uc.record(target: .lastShown, action: .like, queryHint: nil, shownResults: [r1])
        XCTAssertEqual(db.feedbackLikeDislikeCounts(queryText: "")[String(r1.id)]?.likeCount, 1)
        // MORE_LIKE_THIS 落库为 "more_like_this"（不参与 like/dislike 计数）
        uc.record(target: .lastShown, action: .moreLikeThis, queryHint: "猫", shownResults: [r1])
        let counts = db.feedbackLikeDislikeCounts(queryText: "猫")[String(r1.id)]
        XCTAssertEqual(counts?.likeCount, 0)
        XCTAssertEqual(counts?.dislikeCount, 1)
    }

    // MARK: - scoreAdjustments（§8 权重公式）

    func testScoreAdjustmentsWeight() {
        let db = makeDb()
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s2")
        db.insertMediaFeedback(mediaId: "42", feedbackType: "dislike", queryText: "猫", sessionId: "s3")
        db.insertMediaFeedback(mediaId: "7", feedbackType: "dislike", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "9", feedbackType: "like", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "9", feedbackType: "dislike", queryText: "猫", sessionId: "s1")

        let adj = MediaFeedbackUseCase(db: db).scoreAdjustments(queryText: "猫")
        // delta = like*0.15 - dislike*0.15
        XCTAssertEqual(adj["42"] ?? 0, 0.15, accuracy: 1e-6, "2 like - 1 dislike = 0.15")
        XCTAssertEqual(adj["7"] ?? 0, -0.15, accuracy: 1e-6)
        XCTAssertNil(adj["9"], "like/dislike 相等 → delta == 0 不出现（§8：delta == 0 不动）")
    }

    func testScoreAdjustmentsExactQueryMatch() {
        let db = makeDb()
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "7", feedbackType: "like", queryText: "猫咪", sessionId: "s1")
        let uc = MediaFeedbackUseCase(db: db)
        // R10：query_text 精确等值——"猫咪" 的反馈不影响 "猫" 的调整分
        XCTAssertEqual(uc.scoreAdjustments(queryText: "猫")["42"] ?? 0, 0.15, accuracy: 1e-6)
        XCTAssertNil(uc.scoreAdjustments(queryText: "猫")["7"])
        XCTAssertEqual(uc.scoreAdjustments(queryText: "猫咪")["7"] ?? 0, 0.15, accuracy: 1e-6)
        XCTAssertTrue(uc.scoreAdjustments(queryText: "狗").isEmpty)
    }
}
