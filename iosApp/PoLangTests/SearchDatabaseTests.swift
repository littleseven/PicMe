import XCTest
@testable import PoLang

/// 相册搜索数据层测试（contracts.md §4/§5.5/§8）。
/// 覆盖：§4.3/§4.4/§4.5 SQL 语义（LIKE 大小写、通配符不转义 R8、BETWEEN 双端含、IN 分批）、
/// 辅助表写入→查询闭环、media_feedback 精确匹配（R10）、semanticEmbedding codec（R6）。
final class SearchDatabaseTests: XCTestCase {

    /// 用临时库测试，避免污染 Documents/polang_tag.db（与 TagDatabaseScanTests 同风格）。
    func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "search_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    /// 建一条媒体并直接用 SQL 写搜索相关列（labels/labelsEn/labelsZh/ocrText/locationName 暂无公开写入侧）。
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

    // MARK: - §4.3 media_assets LIKE 语义

    func testSearchByLabelLikeAndOrder() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100, labels: "{\"tags\":[\"cat\",\"park\"]}")
        let b = makeMedia(db, lid: "B", captureDateMs: 200, labels: "{\"tags\":[\"dog\"]}")
        let c = makeMedia(db, lid: "C", captureDateMs: 300, labels: "{\"tags\":[\"wildcat\"]}")
        let hits = db.searchByLabel("cat")
        XCTAssertEqual(hits.map { $0.id }, [c, a], "LIKE 子串匹配（wildcat 也命中），captureDate 降序")
        XCTAssertFalse(hits.contains { $0.id == b })
    }

    func testSearchByLabelAsciiCaseInsensitive() {
        let db = makeDb()
        let id = makeMedia(db, lid: "A", captureDateMs: 1, labels: "{\"tags\":[\"Cat\"]}")
        // SQLite LIKE 默认仅 ASCII 大小写不敏感（contracts §4.3 ⚠️ / R8）
        XCTAssertEqual(db.searchByLabel("cat").map { $0.id }, [id])
        XCTAssertEqual(db.searchByLabel("CAT").map { $0.id }, [id])
    }

    func testLikeWildcardNotEscaped() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 1, labels: "[\"x\"]")
        let b = makeMedia(db, lid: "B", captureDateMs: 2, labels: "[\"y\"]")
        // R8：用户输入的 % 不转义——"%" 作为通配符匹配所有非 NULL labels（双端一致行为）
        let hits = db.searchByLabel("%")
        XCTAssertEqual(Set(hits.map { $0.id }), Set([a, b]))
    }

    func testSearchEmptyResult() {
        let db = makeDb()
        _ = makeMedia(db, lid: "A", captureDateMs: 1, labels: "[\"cat\"]")
        XCTAssertTrue(db.searchByLabel("zebra").isEmpty)
        XCTAssertTrue(db.searchByOcrText("nothing").isEmpty)
        XCTAssertTrue(db.searchAll("nothing").isEmpty)
        XCTAssertTrue(db.getMediaByIds([]).isEmpty, "空 ids 直接返回空（不拼 SQL）")
    }

    func testSearchByLabelAllFields() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 1, labelsEn: "[\"beach\"]")
        let b = makeMedia(db, lid: "B", captureDateMs: 2, labelsZh: "[\"海滩\"]")
        let c = makeMedia(db, lid: "C", captureDateMs: 3, labels: "{\"tags\":[\"beach\"]}")
        XCTAssertEqual(Set(db.searchByLabelAllFields("beach").map { $0.id }), Set([a, c]))
        XCTAssertEqual(db.searchByLabelAllFields("海滩").map { $0.id }, [b])
    }

    func testSearchByOcrLocationFileName() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 1, fileName: "IMG_0001.JPG",
                          ocrText: "发票号码 12345", locationName: "上海市徐汇区")
        XCTAssertEqual(db.searchByOcrText("发票").map { $0.id }, [a])
        XCTAssertEqual(db.searchByLocation("上海").map { $0.id }, [a])
        XCTAssertEqual(db.searchByFileName("0001").map { $0.id }, [a])
        // 文件名 LIKE ASCII 大小写不敏感
        XCTAssertEqual(db.searchByFileName("img_").map { $0.id }, [a])
    }

    func testSearchByTimeRangeBetweenInclusive() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100)
        let b = makeMedia(db, lid: "B", captureDateMs: 200)
        let c = makeMedia(db, lid: "C", captureDateMs: 300)
        // BETWEEN 双端含（contracts.md:583）
        XCTAssertEqual(db.searchByTimeRange(100, 300).map { $0.id }, [c, b, a])
        XCTAssertEqual(db.getMediaIdsByTimeRange(100, 200).sorted(), [a, b].sorted())
        XCTAssertTrue(db.searchByTimeRange(301, 400).isEmpty)
    }

    func testSearchAllOrSemantics() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 1, labels: "[\"beach\"]")
        let b = makeMedia(db, lid: "B", captureDateMs: 2, ocrText: "beach ticket")
        let c = makeMedia(db, lid: "C", captureDateMs: 3, locationName: "beach city")
        let d = makeMedia(db, lid: "D", captureDateMs: 4, fileName: "beach_01.jpg")
        let e = makeMedia(db, lid: "E", captureDateMs: 5, labels: "[\"mountain\"]")
        let hits = db.searchAll("beach")
        XCTAssertEqual(hits.map { $0.id }, [d, c, b, a], "四字段 OR，captureDate 降序")
        XCTAssertFalse(hits.contains { $0.id == e })
    }

    func testGetHasFaceIds() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100, hasFace: true)
        _ = makeMedia(db, lid: "B", captureDateMs: 200, hasFace: false)
        let c = makeMedia(db, lid: "C", captureDateMs: 300, hasFace: true)
        XCTAssertEqual(db.getHasFaceIds(), [c, a], "hasFace=1，captureDate 降序")
        XCTAssertEqual(Set(db.getMediaIdsByHasFace()), Set([a, c]), "轻量版无排序约束")
    }

    func testGetMediaByIdsBatching() {
        let db = makeDb()
        // 造 600 条验证 IN 分批（> 单批 500，< SQLite 变量上限 999 的越界场景）
        var ids: [Int64] = []
        for i in 0..<600 {
            ids.append(makeMedia(db, lid: "L-\(i)", captureDateMs: Int64(i)))
        }
        let rows = db.getMediaByIds(ids)
        XCTAssertEqual(Set(rows.map { $0.id }), Set(ids))
        // 抽样子集
        let subset = Array(ids.prefix(3))
        XCTAssertEqual(Set(db.getMediaByIds(subset).map { $0.id }), Set(subset))
    }

    func testSearchInIdsVariants() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 1, fileName: "cat.jpg", labels: "[\"cat\"]", labelsEn: "[\"cat\"]",
                          ocrText: "hello")
        let b = makeMedia(db, lid: "B", captureDateMs: 2, fileName: "dog.jpg", labels: "[\"dog\"]", labelsEn: "[\"dog\"]",
                          ocrText: "hello world")
        XCTAssertEqual(db.searchLabelsInIds([a, b], keyword: "cat").map { $0.id }, [a])
        XCTAssertEqual(db.searchLabelsAllFieldsInIds([a, b], keyword: "dog").map { $0.id }, [b])
        XCTAssertEqual(Set(db.searchOcrInIds([a, b], keyword: "hello").map { $0.id }), Set([a, b]))
        XCTAssertEqual(db.searchFileNameInIds([a, b], keyword: "cat").map { $0.id }, [a])
        XCTAssertTrue(db.searchLabelsInIds([], keyword: "cat").isEmpty)
    }

    // MARK: - §4.4 辅助表：写入 → 查询闭环

    func testTagIndexRoundTrip() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100)
        let b = makeMedia(db, lid: "B", captureDateMs: 200)
        let tagId = db.upsertTag(name: "海滩", category: "scene")
        XCTAssertGreaterThan(tagId, 0)
        XCTAssertEqual(db.upsertTag(name: "海滩", category: "scene"), tagId, "同名 tag 幂等复用")
        db.insertMediaTag(mediaId: a, tagId: tagId)
        db.insertMediaTag(mediaId: b, tagId: tagId)
        db.insertMediaTag(mediaId: b, tagId: tagId, confidence: 0.9) // 重复关联被 IGNORE

        XCTAssertEqual(db.searchByExactTag("海滩").map { $0.id }, [b, a], "精确名匹配，DISTINCT + 降序")
        XCTAssertEqual(db.searchByTagName("海").map { $0.id }, [b, a], "模糊 LIKE")
        XCTAssertTrue(db.searchByExactTag("海").isEmpty, "精确匹配不含子串")

        db.clearTagsForMedia(a)
        XCTAssertEqual(db.searchByExactTag("海滩").map { $0.id }, [b])
    }

    func testOcrIndexRoundTrip() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100)
        let b = makeMedia(db, lid: "B", captureDateMs: 200)
        let w1 = db.upsertOcrWord(word: "发票", normalizedWord: "发票")
        let w2 = db.upsertOcrWord(word: "Invoice", normalizedWord: "invoice")
        db.insertOcrOccurrence(wordId: w1, mediaId: a)
        db.insertOcrOccurrence(wordId: w2, mediaId: b)

        XCTAssertEqual(db.searchByExactWord("发票").map { $0.id }, [a])
        XCTAssertEqual(db.searchByExactWord("invoice").map { $0.id }, [b])
        // 前缀匹配（contracts.md:658，`prefix || '%'`）
        XCTAssertEqual(db.searchByWordPrefix("inv").map { $0.id }, [b])
        XCTAssertTrue(db.searchByWordPrefix("票").isEmpty, "前缀不是子串——'票' 不是 '发票' 的前缀")

        db.clearOcrWordsForMedia(a)
        XCTAssertTrue(db.searchByExactWord("发票").isEmpty)
    }

    func testLocationIndexRoundTrip() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100)
        let b = makeMedia(db, lid: "B", captureDateMs: 200)
        let locId = db.upsertLocation(country: "中国", province: "上海市", city: "上海市",
                                      district: "徐汇区", poi: "徐家汇公园",
                                      latitude: 31.1885, longitude: 121.4365)
        XCTAssertGreaterThan(locId, 0)
        // 坐标 4 位小数容差内去重（LocationDao.findByCoordinate ABS < 0.0001）
        let dup = db.upsertLocation(country: "中国", province: "上海市", city: "上海市",
                                    district: "徐汇区", poi: nil,
                                    latitude: 31.18851, longitude: 121.43651)
        XCTAssertEqual(dup, locId, "坐标容差内复用同一 location")
        db.insertMediaLocation(mediaId: a, locationId: locId)
        db.insertMediaLocation(mediaId: b, locationId: locId)

        XCTAssertEqual(db.searchByPlace("徐汇").map { $0.id }, [b, a], "district 命中，DISTINCT + 降序")
        XCTAssertEqual(db.searchByPlace("徐家汇").map { $0.id }, [b, a], "poi 命中")
        XCTAssertEqual(db.searchByPlace("上海").map { $0.id }, [b, a], "city/province 命中")
        XCTAssertTrue(db.searchByPlace("北京").isEmpty)

        db.clearLocationsForMedia(a)
        XCTAssertEqual(db.searchByPlace("徐汇").map { $0.id }, [b])
    }

    // MARK: - §4.5 / §7 人物查询

    /// 造 face_embeddings 归属（单人单媒体）。
    /// ⚠️ insertEmbeddings 会先删该媒体旧 embedding——多人同框须在**同一次** insert 写多张脸，
    /// 再经 reassignEmbedding 逐条归属（见 testGetMediaByPersonAndCooccurrence）。
    private func linkFace(_ db: TagDatabase, mediaId: Int64, personId: Int64) {
        db.insertEmbeddings(mediaId: mediaId, embeddings: [Data(repeating: 1, count: 2048)])
        db.assignEmbeddingsByMediaIds([mediaId], personId: personId)
    }

    func testFindPersonByNameAndSelf() {
        let db = makeDb()
        _ = db.insertPerson(name: "张三", coverMediaId: nil, faceCount: 1, isSelf: false)
        let selfPid = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        XCTAssertEqual(db.findPersonByName("张")?.name, "张三", "模糊 LIKE LIMIT 1")
        XCTAssertNil(db.findPersonByName("李四"))
        XCTAssertEqual(db.getSelfPersonRow()?.personId, selfPid)
        XCTAssertEqual(db.selfPersonId(), selfPid)
    }

    func testGetMediaByPersonAndCooccurrence() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100)
        let b = makeMedia(db, lid: "B", captureDateMs: 200)
        let c = makeMedia(db, lid: "C", captureDateMs: 300)
        let p1 = db.insertPerson(name: "甲", coverMediaId: nil, faceCount: 0, isSelf: false)
        let p2 = db.insertPerson(name: "乙", coverMediaId: nil, faceCount: 0, isSelf: false)
        linkFace(db, mediaId: a, personId: p1)
        // b 同框 p1+p2：同一次 insert 写两张脸（insertEmbeddings 会先删旧 embedding），逐条归属
        db.insertEmbeddings(mediaId: b, embeddings: [Data(repeating: 1, count: 2048), Data(repeating: 2, count: 2048)])
        let bFaces = db.getUnassignedEmbeddings().filter { $0.mediaId == b }
        XCTAssertEqual(bFaces.count, 2)
        db.reassignEmbedding(embeddingId: bFaces[0].embeddingId, toPersonId: p1)
        db.reassignEmbedding(embeddingId: bFaces[1].embeddingId, toPersonId: p2)
        linkFace(db, mediaId: c, personId: p2)

        XCTAssertEqual(db.getMediaByPerson(p1).map { $0.id }, [b, a], "经 face_embeddings 归属，降序")
        XCTAssertEqual(Set(db.getMediaIdsByPerson(p2)), Set([b, c]))
        // 共现：HAVING COUNT(DISTINCT person_id) = 2 —— 只有 b 两人同框
        XCTAssertEqual(db.getMediaByPersonsCooccurrence([p1, p2], personCount: 2).map { $0.id }, [b])
        XCTAssertTrue(db.getMediaByPersonsCooccurrence([], personCount: 0).isEmpty)
    }

    func testGetPersonMediaIdsUnion() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100)
        let b = makeMedia(db, lid: "B", captureDateMs: 200, labelsEn: "[\"portrait of 甲\"]")
        let p1 = db.insertPerson(name: "甲", coverMediaId: nil, faceCount: 0, isSelf: false)
        linkFace(db, mediaId: a, personId: p1)

        // 聚类 ∪ 三字段标签提及（contracts.md:702-708）
        XCTAssertEqual(Set(db.getPersonMediaIds(p1, name: "甲")), Set([a, b]))
        // name 空串 → 仅聚类
        XCTAssertEqual(db.getPersonMediaIds(p1, name: ""), [a])
    }

    func testRelationSubjectIds() {
        let db = makeDb()
        let selfPid = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let child = db.insertPerson(name: "小宝", coverMediaId: nil, faceCount: 1, isSelf: false)
        let spouse = db.insertPerson(name: "爱人", coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: child, predicate: "CHILD",
                                              source: "USER_NAMED", customLabel: "女儿"))
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: spouse, predicate: "SPOUSE",
                                              source: "USER_NAMED", customLabel: nil))
        // contracts §7.3：objectPersonId = self AND predicate IN (...)
        XCTAssertEqual(Set(db.relationSubjectIds(objectPersonId: selfPid, predicates: ["SON", "DAUGHTER", "CHILD"])),
                       Set([child]))
        XCTAssertEqual(db.relationSubjectIds(objectPersonId: selfPid, predicates: ["SPOUSE"]), [spouse])
        XCTAssertTrue(db.relationSubjectIds(objectPersonId: selfPid, predicates: []).isEmpty)
    }

    // MARK: - §8 media_feedback（R10 精确匹配）

    func testFeedbackCountsExactQueryMatch() {
        let db = makeDb()
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s1")
        db.insertMediaFeedback(mediaId: "42", feedbackType: "like", queryText: "猫", sessionId: "s2")
        db.insertMediaFeedback(mediaId: "42", feedbackType: "dislike", queryText: "猫", sessionId: "s3")
        db.insertMediaFeedback(mediaId: "7", feedbackType: "dislike", queryText: "猫咪", sessionId: "s1")

        let counts = db.feedbackLikeDislikeCounts(queryText: "猫")
        XCTAssertEqual(counts["42"]?.likeCount, 2)
        XCTAssertEqual(counts["42"]?.dislikeCount, 1)
        // R10：query_text 精确等值——"猫咪" 的反馈不出现在 "猫" 的查询里
        XCTAssertNil(counts["7"])
        XCTAssertTrue(db.feedbackLikeDislikeCounts(queryText: "猫咪")["7"]?.dislikeCount == 1)
    }

    // MARK: - §5.5 / R6 semanticEmbedding codec

    func testCodecRoundTrip() {
        var vec = [Float](repeating: 0, count: 512)
        for i in 0..<512 { vec[i] = Float(i) * 0.001 - 0.25 } // 含负数、小数
        vec[0] = 1.0
        vec[511] = -0.0
        let encoded = SemanticEmbeddingCodec.encode(vec)
        XCTAssertNotNil(encoded)
        let decoded = SemanticEmbeddingCodec.decode(encoded)
        XCTAssertEqual(decoded?.count, 512)
        XCTAssertEqual(decoded?[0], 1.0)
        XCTAssertEqual(decoded?[100], vec[100], "往返逐位一致（bitPattern 编解码无损）")
        XCTAssertEqual(decoded?[511].bitPattern, (-0.0 as Float).bitPattern)
    }

    func testCodecBigEndianKnownBytes() {
        // 与 Android 对拍：1.0f bits=0x3F800000 → 大端字节 3F 80 00 00；0.5f bits=0x3F000000 → 3F 00 00 00
        var vec = [Float](repeating: 0, count: 512)
        vec[0] = 1.0
        vec[1] = 0.5
        vec[2] = -2.5 // bits=0xC0200000 → C0 20 00 00
        let encoded = SemanticEmbeddingCodec.encode(vec)!

        // 独立路径构造期望字节（不经过 codec）：前 12 字节已知 + 其余 2036 字节全 0
        var expected = Data([0x3F, 0x80, 0x00, 0x00, 0x3F, 0x00, 0x00, 0x00, 0xC0, 0x20, 0x00, 0x00])
        expected.append(Data(count: 2048 - 12))
        XCTAssertEqual(encoded, expected.base64EncodedString(), "大端字节序与 Android floatArrayToBase64 一致")
        XCTAssertFalse(encoded.contains("\n"), "Base64.NO_WRAP 无换行")
        XCTAssertEqual(encoded.count, 2732, "2048 字节 Base64 = ceil(2048/3)*4 = 2732 字符")
    }

    func testCodecRejectsInvalid() {
        XCTAssertNil(SemanticEmbeddingCodec.decode(nil))
        XCTAssertNil(SemanticEmbeddingCodec.decode(""))
        XCTAssertNil(SemanticEmbeddingCodec.decode("   "))
        XCTAssertNil(SemanticEmbeddingCodec.decode("!!!not-base64!!!"))
        // 字节数 % 4 != 0 拒绝（3 字节 → Base64 "AAAA"）
        XCTAssertNil(SemanticEmbeddingCodec.decode(Data([1, 2, 3]).base64EncodedString()))
        // float 数 != 512 拒绝（4 字节 = 1 float）
        XCTAssertNil(SemanticEmbeddingCodec.decode(Data([0x3F, 0x80, 0, 0]).base64EncodedString()))
        // 513 float（2052 字节）也拒绝
        XCTAssertNil(SemanticEmbeddingCodec.decode(Data(count: 2052).base64EncodedString()))
        // 编码维度不符拒绝
        XCTAssertNil(SemanticEmbeddingCodec.encode([1.0, 2.0]))
        XCTAssertNil(SemanticEmbeddingCodec.encode([]))
    }

    func testSemanticEmbeddingColumnRoundTrip() {
        let db = makeDb()
        let a = makeMedia(db, lid: "A", captureDateMs: 100)
        let b = makeMedia(db, lid: "B", captureDateMs: 200)
        let vec = [Float](repeating: 0.5, count: 512)
        let encoded = SemanticEmbeddingCodec.encode(vec)!
        db.updateSemanticEmbedding(a, embedding: encoded)
        db.exec("UPDATE media_assets SET semanticEmbedding='' WHERE id=\(b);") // 空串哨兵

        XCTAssertEqual(db.getSemanticEmbedding(a), encoded)
        XCTAssertEqual(SemanticEmbeddingCodec.decode(db.getSemanticEmbedding(a))?.first, 0.5)
        // contracts.md:624：IS NOT NULL AND != '' —— b 空串被排除
        XCTAssertEqual(db.getMediaWithSemanticEmbeddingIds(), [a])
        db.updateSemanticEmbedding(a, embedding: nil)
        XCTAssertTrue(db.getMediaWithSemanticEmbeddingIds().isEmpty)
    }
}
