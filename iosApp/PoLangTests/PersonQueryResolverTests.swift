import XCTest
@testable import PoLang

/// 人物查询解析测试（contracts.md §7）。
/// 覆盖：四级优先级各分支（customLabel / 已命名人物 / 亲属称谓 / 合拍"我"）、
/// 抑制规则（"爸爸"抑制"爸"、customLabel 包含称谓的抑制）、谓词族扩展、
/// ≥2 共现 / =1 单人 / =0 回落的调用侧语义（§2.7，配合 §4.5 SQL 端到端验证）。
final class PersonQueryResolverTests: XCTestCase {

    private func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "person_resolver_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    private func makeMedia(_ db: TagDatabase, lid: String, captureDateMs: Int64) -> Int64 {
        db.getOrCreateMedia(localIdentifier: lid, type: "IMAGE", captureDateMs: captureDateMs, fileName: "\(lid).jpg")
    }

    /// 单人单媒体归属（insertEmbeddings 会先删该媒体旧 embedding，见 SearchDatabaseTests）。
    private func linkFace(_ db: TagDatabase, mediaId: Int64, personId: Int64) {
        db.insertEmbeddings(mediaId: mediaId, embeddings: [Data(repeating: 1, count: 2048)])
        db.assignEmbeddingsByMediaIds([mediaId], personId: personId)
    }

    // MARK: - KinshipLexicon（§7.2）

    func testLexiconScanLengthDedup() {
        // "爸爸"命中后"爸"不再命中（按称谓长度降序，短称谓被长称谓去重）
        let hits = KinshipLexicon.scan("我爸爸")
        XCTAssertEqual(hits.map { $0.term }, ["爸爸"])
        XCTAssertEqual(hits.first?.predicate, .father)
        // 多称谓并存，长度降序；同长度 tie 保持词表插入序
        // （Android sortedByDescending 为稳定排序：女儿在 mapOf 中先于老婆）
        let multi = KinshipLexicon.scan("我和老婆还有女儿")
        XCTAssertEqual(multi.map { $0.term }, ["女儿", "老婆"])
        // 未受控称谓不命中
        XCTAssertTrue(KinshipLexicon.scan("我家猫咪").isEmpty)
    }

    func testLexiconPredicateFamilies() {
        // 具体谓词 → {具体值, 同族未指定桶}
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("女儿"), [.daughter, .child])
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("爸爸"), [.father, .parent])
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("哥哥"), [.elderBrother, .sibling])
        // 泛化桶 → 整族
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("孩子"), [.son, .daughter, .child])
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("父母"), [.father, .mother, .parent])
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("兄弟姐妹"),
                       [.elderBrother, .elderSister, .youngerBrother, .youngerSister, .sibling])
        // 非族谓词 → 单例
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("老婆"), [.spouse])
        XCTAssertEqual(KinshipLexicon.queryPredicatesFor("同学"), [.classmate])
        // 非受控称谓 → nil
        XCTAssertNil(KinshipLexicon.queryPredicatesFor("闺蜜"))
    }

    // MARK: - 优先级 1：customLabel 精确命中（§7.1 步骤 1）

    func testCustomLabelHit() {
        let db = makeDb()
        _ = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let child = db.insertPerson(name: "小宝", coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: child, predicate: "SON",
                                              source: "USER_NAMED", customLabel: "二儿子"))
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "二儿子的照片", lang: "zh", db: db), [child])
    }

    func testCustomLabelBlankIgnored() {
        let db = makeDb()
        _ = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let p = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        // 空白 customLabel 归一丢弃（Android trim().ifEmpty{null}）；这里直接验证空白串不命中
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: p, predicate: "FRIEND",
                                              source: "USER_NAMED", customLabel: " "))
        XCTAssertTrue(PersonQueryResolver.resolvePersonIds(query: " 的照片", lang: "zh", db: db).isEmpty)
    }

    // MARK: - 优先级 2：已命名人物命中（§7.1 步骤 2）

    func testNamedPersonHit() {
        let db = makeDb()
        let p = db.insertPerson(name: "张三", coverMediaId: nil, faceCount: 1, isSelf: false)
        _ = db.insertPerson(name: "李四", coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "张三在海边", lang: "zh", db: db), [p])
    }

    func testSameNameUnion() {
        let db = makeDb()
        let p1 = db.insertPerson(name: "张伟", coverMediaId: nil, faceCount: 1, isSelf: false)
        let p2 = db.insertPerson(name: "张伟", coverMediaId: nil, faceCount: 1, isSelf: false)
        // 同名多人物取并集（isAmbiguous 语义在本 API 的简化返回中体现为并集）
        XCTAssertEqual(Set(PersonQueryResolver.resolvePersonIds(query: "张伟的合影", lang: "zh", db: db)),
                       Set([p1, p2]))
    }

    // MARK: - 优先级 3：亲属称谓命中 + 谓词族扩展（§7.1 步骤 3 / §7.3）

    func testKinshipHit() {
        let db = makeDb()
        _ = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let daughter = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: daughter, predicate: "DAUGHTER",
                                              source: "USER_NAMED", customLabel: nil))
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "我女儿的照片", lang: "zh", db: db), [daughter])
    }

    func testKinshipFamilyExpansion() {
        let db = makeDb()
        _ = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let child = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        // 关系存的是未指定桶 CHILD；具体称谓"女儿"经谓词族扩展 {DAUGHTER, CHILD} 仍命中
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: child, predicate: "CHILD",
                                              source: "USER_NAMED", customLabel: nil))
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "我女儿", lang: "zh", db: db), [child],
                       "具体称谓含同族未指定桶（女儿→{DAUGHTER,CHILD}）")
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "我孩子", lang: "zh", db: db), [child],
                       "泛化称谓含整族（孩子→{SON,DAUGHTER,CHILD}）")
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "我儿子", lang: "zh", db: db), [child],
                       "具体称谓含同族未指定桶（儿子→{SON,CHILD}，§7.2），存储 CHILD ∈ {SON,CHILD} 命中")
    }

    func testKinshipNoSelfNoHit() {
        let db = makeDb()
        let p = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        // 直接 SQL 写一条 object 指向非 self 的关系（无 is_self 人物 → 亲属解析恒空）
        db.exec("""
            INSERT INTO person_relations (subjectPersonId, objectPersonId, predicate, source, customLabel, confidence)
            VALUES (\(p), \(p), 'DAUGHTER', 'USER_NAMED', NULL, 1.0);
            """)
        XCTAssertTrue(PersonQueryResolver.resolvePersonIds(query: "我女儿", lang: "zh", db: db).isEmpty,
                      "无 is_self 人物 → resolveByKinship 返回空（§7.3）")
    }

    // MARK: - 抑制规则

    func testCustomLabelSuppressesKinshipTerm() {
        let db = makeDb()
        _ = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let elder = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        let younger = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: elder, predicate: "SON",
                                              source: "USER_NAMED", customLabel: "二儿子"))
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: younger, predicate: "SON",
                                              source: "USER_NAMED", customLabel: nil))
        // "二儿子"命中后，被其包含的称谓"儿子"跳过——younger 不被并集稀释（§7.1 步骤 3）
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "二儿子的照片", lang: "zh", db: db), [elder])
        // 对照：无 customLabel 命中时，"儿子"正常命中全部 SON 关系
        XCTAssertEqual(Set(PersonQueryResolver.resolvePersonIds(query: "我儿子的照片", lang: "zh", db: db)),
                       Set([elder, younger]))
    }

    func testLongerTermSuppressesShorter() {
        let db = makeDb()
        _ = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let father = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        let pal = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: father, predicate: "FATHER",
                                              source: "USER_NAMED", customLabel: nil))
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: pal, predicate: "FRIEND",
                                              source: "USER_NAMED", customLabel: "爸"))
        // query 只含"爸"：customLabel"爸"命中 pal；称谓"爸"被命中 label 包含而跳过 → 不并 father
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "爸的照片", lang: "zh", db: db), [pal],
                       "customLabel 包含称谓的抑制（§7.1 步骤 3）")
        // query 含"爸爸"：scan 长短去重只命中"爸爸"（"爸"被抑制，§7.2）；
        // customLabel"爸"仍 contains 命中 pal（label 排序/命中与称谓去重相互独立，与 Android 一致）
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "我爸爸", lang: "zh", db: db), [pal, father],
                       "「爸爸」抑制「爸」：称谓解析只走「爸爸」→ father；customLabel「爸」独立命中 pal")
    }

    // MARK: - 优先级 4：合拍 Pattern 的"我"（§7.1 步骤 4）

    func testSelfJoinPattern() {
        let db = makeDb()
        let selfId = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let spouse = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: spouse, predicate: "SPOUSE",
                                              source: "USER_NAMED", customLabel: nil))
        // 合拍 Pattern + 已有其他人物命中 → "我"计入
        XCTAssertEqual(Set(PersonQueryResolver.resolvePersonIds(query: "我和老婆的合影", lang: "zh", db: db)),
                       Set([spouse, selfId]))
        // 无合拍 Pattern → 不计入"我"
        XCTAssertEqual(PersonQueryResolver.resolvePersonIds(query: "老婆的照片", lang: "zh", db: db), [spouse])
        // 无其他人物命中 → 第一人称查询不误带本人照片
        XCTAssertTrue(PersonQueryResolver.resolvePersonIds(query: "我想看猫", lang: "zh", db: db).isEmpty)
    }

    // MARK: - 调用侧语义（§2.7）：≥2 共现 / =1 单人 / =0 回落

    func testCallSiteSemantics() {
        let db = makeDb()
        let selfId = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: true)
        let spouse = db.insertPerson(name: nil, coverMediaId: nil, faceCount: 1, isSelf: false)
        XCTAssertTrue(db.upsertRelationToSelf(subjectPersonId: spouse, predicate: "SPOUSE",
                                              source: "USER_NAMED", customLabel: nil))
        let solo = makeMedia(db, lid: "SOLO", captureDateMs: 100)
        let together = makeMedia(db, lid: "TOGETHER", captureDateMs: 200)
        linkFace(db, mediaId: solo, personId: spouse)
        db.insertEmbeddings(mediaId: together, embeddings: [Data(repeating: 1, count: 2048), Data(repeating: 2, count: 2048)])
        let faces = db.getUnassignedEmbeddings().filter { $0.mediaId == together }
        db.reassignEmbedding(embeddingId: faces[0].embeddingId, toPersonId: spouse)
        db.reassignEmbedding(embeddingId: faces[1].embeddingId, toPersonId: selfId)

        // ≥2 人 → 共现查询：只有同框照片
        let ids2 = PersonQueryResolver.resolvePersonIds(query: "我和老婆", lang: "zh", db: db)
        XCTAssertEqual(Set(ids2), Set([spouse, selfId]))
        XCTAssertEqual(db.getMediaByPersonsCooccurrence(ids2, personCount: ids2.count).map { $0.id }, [together])
        // =1 人 → 单人媒体（含未同框的 solo）
        let ids1 = PersonQueryResolver.resolvePersonIds(query: "我老婆", lang: "zh", db: db)
        XCTAssertEqual(ids1, [spouse])
        XCTAssertEqual(db.getMediaByPerson(ids1[0]).map { $0.id }, [together, solo])
        // =0 命中 → 空（调用方回落 findPersonByName LIKE）
        XCTAssertTrue(PersonQueryResolver.resolvePersonIds(query: "完全不相关的查询", lang: "zh", db: db).isEmpty)
        XCTAssertTrue(PersonQueryResolver.resolvePersonIds(query: "   ", lang: "zh", db: db).isEmpty,
                      "空白 query 直接返回空")
    }
}
