import Foundation
import SQLite3  // iOS/macOS 系统 sqlite3 C API 模块（等价于 C 层 #include <sqlite3.h>）

// MARK: - TagDatabase

/// TAG 流水线 SQLite 存储层（iOS）。
///
/// 对齐 Android Room 数据库，使用 iOS 系统 `sqlite3` C API 直接实现，避免引入第三方 SQLite 封装。
///
/// **表**（对齐 Android Room Entity）：
/// - `face_embeddings`  ← `FaceEmbeddingEntity`（Glint360K R100 512 维向量）
/// - `media_assets`     ← `MediaEntity`（28 列，扫描列并入；`mediaId=Int64` + `localIdentifier` 映射）
/// - `tag_scan_tasks`   ← `TagScanTaskEntity`（扫描任务队列）
/// - `persons`          ← `PersonEntity`（人脸聚类后的人物去重表）
///
/// 注：旧 `media_tags` 表已废弃，扫描列并入 `media_assets`（见 TagScanOrchestrator 写入路径）。
///
/// **线程安全**：所有 sqlite3 调用均经 `queue.sync` 串行化，等价于 Android Room 的
/// suspend-DAO + Dispatchers.IO + `@Transaction` 保证。
///
/// **Embedding 二进制格式**：512-dim FloatArray → 2048 字节大端 IEEE-754 float
/// （Android `floatArrayToByteArray`），`Data` 原样存取，不在此层做字节序转换。
final class TagDatabase {
    static let shared = TagDatabase(dbPath: TagDatabase.defaultPath())

    /// 生产库路径：Documents/polang_tag.db。
    static func defaultPath() -> String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent("polang_tag.db").path
    }

    // MARK: - Properties

    /// sqlite3 数据库句柄。（internal get 供同模块扩展 TagDatabase+Scan 使用；仅本文件内 set）
    private(set) var db: OpaquePointer?

    /// 串行队列：所有 sqlite3 调用在此队列上执行，保证线程安全（同模块扩展共用）。
    /// 对齐 Android：Room suspend-DAO 自动调度到 I/O executor + 调用方 `withContext(Dispatchers.IO)`。
    let queue = DispatchQueue(label: "com.mamba.picme.tagdb", qos: .utility)

    /// 注入的数据库文件路径（生产=defaultPath；测试=临时路径）。
    private let dbPath: String

    // MARK: - Init / Deinit

    /// 打开（或创建）指定路径的数据库，并确保所有表存在。
    /// - Parameter dbPath: 生产用 `TagDatabase.defaultPath()`；测试可传临时路径。
    init(dbPath: String) {
        self.dbPath = dbPath
        queue.sync { openAndCreateSchema() }
    }

    deinit {
        // Android Room 由 `RoomDatabase.close()` 关闭；iOS 在析构时直接 close。
        if let db = db {
            sqlite3_close(db)
        }
    }

    // MARK: - Schema

    /// 打开数据库并创建表结构（幂等，`IF NOT EXISTS`）。
    private func openAndCreateSchema() {
        // sqlite3_open 若文件不存在会自动创建。
        var handle: OpaquePointer?
        guard sqlite3_open(self.dbPath, &handle) == SQLITE_OK else {
            let msg = handle.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            sqlite3_close(handle)
            fatalError("[TagDatabase] Cannot open DB at \(self.dbPath): \(msg)")
        }
        db = handle

        // WAL 模式：并发读不阻塞写，与 Android Room 默认行为一致。
        exec("PRAGMA journal_mode=WAL;")
        exec("PRAGMA foreign_keys=ON;")

        // ── face_embeddings ──
        exec("""
            CREATE TABLE IF NOT EXISTS face_embeddings (
                embedding_id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id     INTEGER NOT NULL,
                person_id    INTEGER,
                embedding    BLOB    NOT NULL,
                created_at   INTEGER NOT NULL,
                FOREIGN KEY(person_id) REFERENCES persons(person_id) ON DELETE SET NULL
            );
            """)
        exec("CREATE INDEX IF NOT EXISTS idx_face_embeddings_person_id ON face_embeddings(person_id);")
        exec("CREATE INDEX IF NOT EXISTS idx_face_embeddings_media_id ON face_embeddings(media_id);")

        // ── persons ──
        exec("""
            CREATE TABLE IF NOT EXISTS persons (
                person_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                name           TEXT,
                cover_media_id INTEGER,
                face_count     INTEGER DEFAULT 0,
                is_self        INTEGER DEFAULT 0,
                created_at     INTEGER,
                updated_at     INTEGER
            );
            """)

        // ── media_assets ──（对齐 Android MediaEntity，扫描列并入）
        exec("""
            CREATE TABLE IF NOT EXISTS media_assets (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                uri             TEXT NOT NULL,
                type            TEXT NOT NULL,
                captureDate     INTEGER NOT NULL,
                fileName        TEXT NOT NULL,
                duration        INTEGER,
                hasFace         INTEGER DEFAULT 0,
                faceId          TEXT,
                source          TEXT,
                labels          TEXT,
                labelsEn        TEXT,
                labelsZh        TEXT,
                mlKitLabels     TEXT,
                mlKitLabelsZh   TEXT,
                ocrText         TEXT,
                latitude        REAL,
                longitude       REAL,
                locationName    TEXT,
                city            TEXT,
                indexedAt       INTEGER,
                faceRoiResult   TEXT,
                faceFocusY      REAL,
                aestheticScore  REAL,
                faceQualityScore REAL,
                semanticEmbedding TEXT,
                lastTagScanAt   INTEGER,
                lastTagScanPasses TEXT,
                localIdentifier TEXT NOT NULL UNIQUE
            );
            """)
        exec("CREATE INDEX IF NOT EXISTS idx_media_assets_captureDate ON media_assets(captureDate);")
        exec("CREATE INDEX IF NOT EXISTS idx_media_assets_hasFace ON media_assets(hasFace);")

        // ── tag_scan_tasks ──（对齐 Android TagScanTaskEntity + 3 索引）
        exec("""
            CREATE TABLE IF NOT EXISTS tag_scan_tasks (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId     TEXT NOT NULL,
                mediaId       INTEGER NOT NULL,
                pass          TEXT NOT NULL,
                tagCategories TEXT,
                status        TEXT NOT NULL DEFAULT 'PENDING',
                priority      INTEGER NOT NULL DEFAULT 0,
                attemptCount  INTEGER NOT NULL DEFAULT 0,
                createdAt     INTEGER NOT NULL,
                scheduledAt   INTEGER,
                startedAt     INTEGER,
                completedAt   INTEGER,
                errorMessage  TEXT
            );
            """)
        exec("CREATE INDEX IF NOT EXISTS idx_tasks_sched ON tag_scan_tasks(status, priority, scheduledAt);")
        exec("CREATE INDEX IF NOT EXISTS idx_tasks_media ON tag_scan_tasks(mediaId, pass, status);")
        exec("CREATE INDEX IF NOT EXISTS idx_tasks_session ON tag_scan_tasks(sessionId, status);")

        // ── person_relations ──（人物关系图谱边；对标 Android PersonRelationEntity。
        //   谓词存 RelationPredicate.name；object 隐式 = is_self 人物。FK 级联。）
        exec("""
            CREATE TABLE IF NOT EXISTS person_relations (
                relationId      INTEGER PRIMARY KEY AUTOINCREMENT,
                subjectPersonId INTEGER NOT NULL,
                objectPersonId  INTEGER NOT NULL,
                predicate       TEXT NOT NULL,
                source          TEXT NOT NULL,
                customLabel     TEXT,
                confidence      REAL NOT NULL DEFAULT 1.0,
                UNIQUE(subjectPersonId, predicate, objectPersonId),
                FOREIGN KEY(subjectPersonId) REFERENCES persons(person_id) ON DELETE CASCADE,
                FOREIGN KEY(objectPersonId) REFERENCES persons(person_id) ON DELETE CASCADE
            );
            """)
        exec("CREATE INDEX IF NOT EXISTS idx_person_relations_subject ON person_relations(subjectPersonId);")
    }

    // MARK: - face_embeddings: Insert

    /// 删除该媒体的旧 embedding，再批量写入新 embedding（单事务）。
    func insertEmbeddings(mediaId: Int64, embeddings: [Data]) {
        queue.sync {
            guard let db = db else { return }

            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }

            var delStmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM face_embeddings WHERE media_id = ?;", -1, &delStmt, nil)
            sqlite3_bind_int64(delStmt, 1, mediaId)
            sqlite3_step(delStmt)
            sqlite3_finalize(delStmt)

            guard !embeddings.isEmpty else { return }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

            var insStmt: OpaquePointer?
            sqlite3_prepare_v2(
                db,
                "INSERT INTO face_embeddings (media_id, person_id, embedding, created_at) VALUES (?, NULL, ?, ?);",
                -1, &insStmt, nil
            )
            for emb in embeddings {
                _ = emb.withUnsafeBytes { rawBuf -> Int32 in
                    sqlite3_bind_blob(
                        insStmt, 2,
                        rawBuf.baseAddress, Int32(emb.count),
                        unsafeBitCast(-1, to: sqlite3_destructor_type.self) // SQLITE_TRANSIENT
                    )
                }
                sqlite3_bind_int64(insStmt, 1, mediaId)
                sqlite3_bind_int64(insStmt, 3, nowMs)
                _ = sqlite3_step(insStmt)
                sqlite3_reset(insStmt)
                sqlite3_clear_bindings(insStmt)
            }
            sqlite3_finalize(insStmt)
        }
    }

    // MARK: - face_embeddings: Query

    /// 获取所有尚未分配人物的 embedding（`person_id IS NULL`）。Pass 2 DBSCAN 用。
    func getUnassignedEmbeddings() -> [(embeddingId: Int64, mediaId: Int64, embedding: Data)] {
        queue.sync {
            guard let db = db else { return [] }

            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(
                db,
                "SELECT embedding_id, media_id, embedding FROM face_embeddings WHERE person_id IS NULL;",
                -1, &stmt, nil
            ) == SQLITE_OK
            else {
                assertionFailure("[TagDatabase] Failed to prepare getUnassignedEmbeddings: \(String(cString: sqlite3_errmsg(db)))")
                return []
            }
            defer { sqlite3_finalize(stmt) }

            var results: [(embeddingId: Int64, mediaId: Int64, embedding: Data)] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                let embeddingId = sqlite3_column_int64(stmt, 0)
                let mediaId = sqlite3_column_int64(stmt, 1)

                let blobSize = Int(sqlite3_column_bytes(stmt, 2))
                if let blobPtr = sqlite3_column_blob(stmt, 2), blobSize > 0 {
                    let embedding = Data(bytes: blobPtr, count: blobSize)
                    results.append((embeddingId: embeddingId, mediaId: mediaId, embedding: embedding))
                }
            }
            return results
        }
    }

    // MARK: - SQL Helpers

    /// 执行无需返回结果 / 无参数的 SQL（CREATE TABLE / BEGIN / COMMIT / PRAGMA 等）。
    /// （internal：供同模块扩展 TagDatabase+Scan 使用。）
    func exec(_ sql: String) {
        guard let db = db else { return }
        var errMsg: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(db, sql, nil, nil, &errMsg)
        if rc != SQLITE_OK {
            let msg = errMsg.map { String(cString: $0) } ?? "unknown"
            assertionFailure("[TagDatabase] sqlite3_exec failed (rc=\(rc)): \(msg)\nSQL: \(sql)")
        }
        if errMsg != nil { sqlite3_free(errMsg) }
    }
}
