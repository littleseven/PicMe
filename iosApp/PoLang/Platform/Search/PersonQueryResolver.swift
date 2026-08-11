import Foundation
import SQLite3

// MARK: - 人物查询解析（双端契约 SSOT: contracts.md §7.1/§7.3）
//
// 逐字对齐 Android `PersonQueryResolver.kt:32-96`（原始 query → 人物 ID 集合）与
// `PersonRepository.kt:214-239`（resolveByKinship / resolveByCustomLabels）。
// 四级优先级：
// 1. 自定义称呼：query 包含指向"我"的关系的 customLabel（"二儿子""发小"）→ 命中 subject 人物
//    （多个称呼可同时命中，按 label 长度降序）
// 2. 已命名人物：query 包含某人名 → 命中（同名多人物取并集）
// 3. 亲属称谓：KinshipLexicon.scan 命中 → 谓词族扩展反查 person_relations；
//    已被更长 customLabel 包含的称谓跳过（"二儿子"命中后不再用"儿子"取并集）
// 4. "我"：仅当已命中 ≥1 人物 且 query 含合拍 Pattern（我和/和我/与我/我跟/跟我）才计入
//
// 调用侧（contracts §2.7）：≥2 人 → getMediaByPersonsCooccurrence 共现查询；
// =1 → getMediaByPerson 单人；=0 → findPersonByName LIKE 回落。
//
// `lang` 参数为并行 agent API 约定保留——Android 侧 PersonQueryResolver.resolve 无语言参数
// （KinshipLexicon 词表仅中文，与界面语言无关），当前实现不消费该参数。

enum PersonQueryResolver {

    /// 合拍意图 Pattern："X 和我/我和 X/与我"（PersonQueryResolver.kt:95，逐字照抄）。
    private static let selfJoinPatterns = ["我和", "和我", "与我", "我跟", "跟我"]

    /// 契约 §7：原始查询 → 人物 ID 列表（按命中优先级次序去重；空 = 无人物命中）。
    static func resolvePersonIds(query: String, lang: String, db: TagDatabase) -> [Int64] {
        _ = lang // 词表仅中文（KinshipLexicon），与 Android 一致不区分界面语言
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }

        var personIds: [Int64] = [] // 保序去重，等价 Android linkedSetOf
        func add(_ pid: Int64) { if !personIds.contains(pid) { personIds.append(pid) } }

        // 1. 自定义称呼精确命中（最高优先级）：query contains customLabel → subject 人物。
        //    按 label 长度降序（PersonRepository.kt:238）；指向已删人物的关系丢弃（mapNotNull 语义）。
        var matchedLabels: [String] = []
        if let selfId = db.selfPersonId() {
            for hit in db.customLabelRelationsToSelf(objectPersonId: selfId) where query.contains(hit.customLabel) {
                if db.personRow(hit.subjectPersonId) != nil {
                    add(hit.subjectPersonId)
                    matchedLabels.append(hit.customLabel)
                }
            }
        }

        // 2. 已命名人物命中：所有 name 非空人物，query contains(name) 即命中；同名取并集。
        for person in db.allPersonRows() {
            guard let name = person.name,
                  !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  query.contains(name) else { continue }
            add(person.personId)
        }

        // 3. 亲属称谓命中（KinshipLexicon.scan 已按称谓长度降序 + 短称谓去重）；
        //    已被命中的 customLabel 包含的称谓跳过（matchedLabels.any { $0.contains(term) }）。
        for (term, _) in KinshipLexicon.scan(query) {
            if matchedLabels.contains(where: { $0.contains(term) }) { continue }
            for pid in resolveByKinship(term: term, db: db) { add(pid) }
        }

        // 4. "我"：仅合拍 Pattern 且已有其他人物命中时计入（避免"我想看猫"误带本人照片）。
        if !personIds.isEmpty, Self.selfJoinPatterns.contains(where: { query.contains($0) }),
           let selfId = db.selfPersonId() {
            add(selfId)
        }

        return personIds
    }

    /// 契约 §7.3 resolveByKinship：称谓 → 谓词族扩展 →
    /// `person_relations WHERE objectPersonId = self AND predicate IN (...)` 的 subject 人物
    /// （无 self → 空；指向已删人物的关系丢弃，对齐 PersonRepository.kt:221 mapNotNull）。
    private static func resolveByKinship(term: String, db: TagDatabase) -> [Int64] {
        guard let predicates = KinshipLexicon.queryPredicatesFor(term),
              let selfId = db.selfPersonId() else { return [] }
        let subjectIds = db.relationSubjectIds(
            objectPersonId: selfId,
            predicates: predicates.map { $0.rawValue })
        return subjectIds.filter { db.personRow($0) != nil }
    }
}

// MARK: - 数据访问补充（customLabel 反查；TagDatabase+Search.swift 未覆盖，本文件私有扩展）

private extension TagDatabase {

    /// 指向"我"且 customLabel 非空的关系（PersonRelationDao.getByObjectWithCustomLabel 语义）。
    /// customLabel 先 trim、空白丢弃（PersonRepository.kt:232），返回按 label 长度降序（:238）。
    func customLabelRelationsToSelf(objectPersonId: Int64) -> [(subjectPersonId: Int64, customLabel: String)] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT subjectPersonId, customLabel FROM person_relations
                WHERE objectPersonId = ? AND customLabel IS NOT NULL;
                """, -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, objectPersonId)
            defer { sqlite3_finalize(stmt) }
            var hits: [(subjectPersonId: Int64, customLabel: String)] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let cs = sqlite3_column_text(stmt, 1) else { continue }
                let label = String(cString: cs).trimmingCharacters(in: .whitespacesAndNewlines)
                if label.isEmpty { continue }
                hits.append((subjectPersonId: sqlite3_column_int64(stmt, 0), customLabel: label))
            }
            // 按 label 长度降序（PersonRepository.kt:238 稳定排序；同长保持读出序）
            return hits.enumerated()
                .sorted { lhs, rhs in
                    let lc = lhs.element.customLabel.count, rc = rhs.element.customLabel.count
                    return lc == rc ? lhs.offset < rhs.offset : lc > rc
                }
                .map { $0.element }
        }
    }
}
