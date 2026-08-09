import Foundation
import GRDB

/// 人物域本地持久化（GRDB），对标 Android Room `PersonRepository`。
///
/// **iOS 适配差异**：Phase 6.3 iOS 暂无人脸聚类（Phase 6.1），无 `face_embeddings`
/// 数据源。因此人物与照片的关联改为**手动指派**（`person_media_assignments`），
/// 而非 Android 的 label-mention + face embedding。三张表：
/// - `persons`：人物本体（名称/封面/是否本人）。
/// - `person_relations`：人物关系图谱边（谓词单一事实来源 = :shared `RelationPredicate`）。
/// - `person_media_assignments`：人物↔照片手动指派（mediaId = PHAsset localIdentifier）。
///
/// 关系谓词的标签词汇下沉至 :shared/commonMain（`PersonRelationSupport`），本层只存枚举名。
final class PersonStore {

    // MARK: - 行模型

    /// 人物行（photoCount 由 assignments 实时派生，避免与 assignments 漂移）
    struct PersonRow: Identifiable, Equatable {
        let id: Int64
        var name: String
        var coverMediaId: String?
        var photoCount: Int
        var isSelf: Bool
        var updatedAt: Int64
    }

    /// 关系行（带 object 人物名，供 UI 展示 "X 是 Y 的母亲"）
    struct RelationRow: Identifiable, Equatable {
        let id: Int64
        let subjectPersonId: Int64
        let objectPersonId: Int64
        let predicate: String      // RelationPredicate.name（SSOT = :shared）
        let source: String         // RelationSource.name
        var customLabel: String?
        let confidence: Double
        let objectName: String?    // 关系目标人物名（LEFT JOIN）
    }

    static let shared = PersonStore()

    private var dbQueue: DatabaseQueue!

    private init() {
        openOrInMemory()
    }

    // MARK: - 开库 / 建表

    /// 打开磁盘库；失败则退回内存库（保证应用不崩，数据当次会话丢失——降级而非崩溃）。
    private func openOrInMemory() {
        var config = Configuration()
        config.prepareDatabase { db in
            try db.execute(sql: "PRAGMA foreign_keys = ON")
        }
        let path = Self.dbPath()
        do {
            dbQueue = try DatabaseQueue(path: path, configuration: config)
            try createSchema()
        } catch {
            NSLog("PoLang:PersonStore open disk failed: \(error) — fallback in-memory")
            dbQueue = try? DatabaseQueue(configuration: config)
            try? createSchema()
        }
    }

    private static func dbPath() -> String {
        let fm = FileManager.default
        let dir: URL
        if let support = try? fm.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true) {
            dir = support
        } else {
            dir = fm.temporaryDirectory
        }
        return dir.appendingPathComponent("polang_person.sqlite").path
    }

    private func createSchema() throws {
        try dbQueue.write { db in
            try db.execute(sql: """
                CREATE TABLE IF NOT EXISTS persons (
                    personId     INTEGER PRIMARY KEY,
                    name         TEXT NOT NULL,
                    coverMediaId TEXT,
                    isSelf       INTEGER NOT NULL DEFAULT 0,
                    createdAt    INTEGER NOT NULL DEFAULT 0,
                    updatedAt    INTEGER NOT NULL DEFAULT 0
                )
                """)
            try db.execute(sql: """
                CREATE TABLE IF NOT EXISTS person_relations (
                    relationId      INTEGER PRIMARY KEY AUTOINCREMENT,
                    subjectPersonId INTEGER NOT NULL,
                    objectPersonId  INTEGER NOT NULL,
                    predicate       TEXT NOT NULL,
                    source          TEXT NOT NULL,
                    customLabel     TEXT,
                    confidence      REAL NOT NULL DEFAULT 1.0,
                    UNIQUE(subjectPersonId, predicate, objectPersonId),
                    FOREIGN KEY(subjectPersonId) REFERENCES persons(personId) ON DELETE CASCADE,
                    FOREIGN KEY(objectPersonId) REFERENCES persons(personId) ON DELETE CASCADE
                )
                """)
            try db.execute(sql: """
                CREATE TABLE IF NOT EXISTS person_media_assignments (
                    personId   INTEGER NOT NULL,
                    mediaId    TEXT NOT NULL,
                    assignedAt INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(personId, mediaId),
                    FOREIGN KEY(personId) REFERENCES persons(personId) ON DELETE CASCADE
                )
                """)
        }
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    // MARK: - persons CRUD

    /// 全部人物，本人优先 → 名称不区分大小写升序（无可信亲密度权重前的稳定排序）。
    func allPersonsSorted() throws -> [PersonRow] {
        try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT personId, name, coverMediaId, isSelf, createdAt, updatedAt,
                    (SELECT COUNT(*) FROM person_media_assignments a
                     WHERE a.personId = persons.personId) AS pc
                FROM persons
                ORDER BY isSelf DESC, name COLLATE NOCASE ASC
                """)
            return rows.map { row in
                PersonRow(
                    id: row["personId"],
                    name: row["name"],
                    coverMediaId: row["coverMediaId"],
                    photoCount: row["pc"],
                    isSelf: Int(exactly: row["isSelf"] as Int64 ?? 0) == 1,
                    updatedAt: row["updatedAt"])
            }
        }
    }

    func person(id: Int64) throws -> PersonRow? {
        try dbQueue.read { db in
            guard let row = try Row.fetchOne(db, sql: """
                SELECT personId, name, coverMediaId, isSelf, createdAt, updatedAt,
                    (SELECT COUNT(*) FROM person_media_assignments a
                     WHERE a.personId = persons.personId) AS pc
                FROM persons WHERE personId = ?
                """, arguments: [id]) else { return nil }
            return PersonRow(
                id: row["personId"],
                name: row["name"],
                coverMediaId: row["coverMediaId"],
                photoCount: row["pc"],
                isSelf: Int(exactly: row["isSelf"] as Int64 ?? 0) == 1,
                updatedAt: row["updatedAt"])
        }
    }

    /// 新建人物；isSelf=true 时先把其他人物置为非本人（全局唯一"我"）。
    @discardableResult
    func createPerson(name: String, coverMediaId: String?, isSelf: Bool) throws -> Int64 {
        try dbQueue.write { db in
            if isSelf {
                try db.execute(sql: "UPDATE persons SET isSelf = 0")
            }
            let now = Self.nowMs()
            try db.execute(sql: """
                INSERT INTO persons (name, coverMediaId, isSelf, createdAt, updatedAt)
                VALUES (?, ?, ?, ?, ?)
                """, arguments: [name, coverMediaId, isSelf ? 1 : 0, now, now])
            return db.lastInsertedRowID
        }
    }

    /// 整字段更新（调用方先读当前行再回填未变字段）；isSelf 唯一性同 createPerson。
    func updatePerson(id: Int64, name: String, coverMediaId: String?, isSelf: Bool) throws {
        try dbQueue.write { db in
            if isSelf {
                try db.execute(sql: "UPDATE persons SET isSelf = 0 WHERE personId != ?", arguments: [id])
            }
            try db.execute(sql: """
                UPDATE persons SET name = ?, coverMediaId = ?, isSelf = ?, updatedAt = ?
                WHERE personId = ?
                """, arguments: [name, coverMediaId, isSelf ? 1 : 0, Self.nowMs(), id])
        }
    }

    func deletePerson(id: Int64) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM persons WHERE personId = ?", arguments: [id])
        }
    }

    // MARK: - person_relations

    /// 该人物**发出**的关系（subject = id）。FK 级联保证删除人物时自动清理。
    func relations(subjectPersonId: Int64) throws -> [RelationRow] {
        try dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT r.relationId, r.subjectPersonId, r.objectPersonId, r.predicate,
                       r.source, r.customLabel, r.confidence, p.name AS objectName
                FROM person_relations r
                LEFT JOIN persons p ON p.personId = r.objectPersonId
                WHERE r.subjectPersonId = ?
                ORDER BY r.relationId
                """, arguments: [subjectPersonId])
            return rows.map { row in
                RelationRow(
                    id: row["relationId"],
                    subjectPersonId: row["subjectPersonId"],
                    objectPersonId: row["objectPersonId"],
                    predicate: row["predicate"],
                    source: row["source"],
                    customLabel: row["customLabel"],
                    confidence: row["confidence"],
                    objectName: row["objectName"])
            }
        }
    }

    /// 幂等 upsert：UNIQUE(subjectPersonId, predicate, objectPersonId) 命中则更新来源/标签/置信度。
    func upsertRelation(
        subjectPersonId: Int64,
        objectPersonId: Int64,
        predicate: String,
        source: String,
        customLabel: String?,
        confidence: Double = 1.0
    ) throws {
        try dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO person_relations
                    (subjectPersonId, objectPersonId, predicate, source, customLabel, confidence)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(subjectPersonId, predicate, objectPersonId) DO UPDATE SET
                    source = excluded.source,
                    customLabel = excluded.customLabel,
                    confidence = excluded.confidence
                """, arguments: [subjectPersonId, objectPersonId, predicate, source, customLabel, confidence])
        }
    }

    func deleteRelation(relationId: Int64) throws {
        try dbQueue.write { db in
            try db.execute(sql: "DELETE FROM person_relations WHERE relationId = ?", arguments: [relationId])
        }
    }

    // MARK: - person_media_assignments

    /// 该人物被指派的照片 localIdentifier 列表（按指派时间倒序）。
    func assignedMediaIds(personId: Int64) throws -> [String] {
        try dbQueue.read { db in
            let rows = try String.fetchAll(db, sql: """
                SELECT mediaId FROM person_media_assignments
                WHERE personId = ? ORDER BY assignedAt DESC
                """, arguments: [personId])
            return rows
        }
    }

    /// 单条指派增删。
    func setAssignment(personId: Int64, mediaId: String, assigned: Bool) throws {
        try dbQueue.write { db in
            if assigned {
                try db.execute(sql: """
                    INSERT OR IGNORE INTO person_media_assignments (personId, mediaId, assignedAt)
                    VALUES (?, ?, ?)
                    """, arguments: [personId, mediaId, Self.nowMs()])
            } else {
                try db.execute(sql: """
                    DELETE FROM person_media_assignments WHERE personId = ? AND mediaId = ?
                    """, arguments: [personId, mediaId])
            }
        }
    }

    /// 批量增删（单事务），用于照片选择器一次性提交。
    func applyAssignments(personId: Int64, add: [String], remove: [String]) throws {
        try dbQueue.write { db in
            let now = Self.nowMs()
            for mediaId in add {
                try db.execute(sql: """
                    INSERT OR IGNORE INTO person_media_assignments (personId, mediaId, assignedAt)
                    VALUES (?, ?, ?)
                    """, arguments: [personId, mediaId, now])
            }
            for mediaId in remove {
                try db.execute(sql: """
                    DELETE FROM person_media_assignments WHERE personId = ? AND mediaId = ?
                    """, arguments: [personId, mediaId])
            }
        }
    }
}
