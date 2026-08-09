import Foundation
import SQLite3  // iOS/macOS 系统 sqlite3 C API 模块（等价于 C 层 #include <sqlite3.h>）

// MARK: - TagDatabase

/// TAG 流水线 SQLite 存储层（iOS）。
///
/// 对齐 Android Room 数据库（`AppDatabase` + `PersonDao` / `MediaDao`），使用 iOS 系统
/// `sqlite3` C API 直接实现，避免引入第三方 SQLite 封装。
///
/// **三张表**（对应 Android 的 Room Entity）：
/// - `face_embeddings` ← `FaceEmbeddingEntity`（Glint360K R100 512 维向量）
/// - `media_tags`      ← `MediaEntity` 中 TAG 相关字段的子集（hasFace / faceRoiResult /
///   faceFocusY / semanticEmbedding / labelsEn / labelsZh / lastTagScanPasses）
/// - `persons`         ← `PersonEntity`（人脸聚类后的人物去重表）
///
/// **线程安全**：所有 sqlite3 调用均经 `queue.sync` 串行化，等价于 Android Room 的
/// suspend-DAO + Dispatchers.IO + `@Transaction` 保证。
///
/// **Embedding 二进制格式**：512-dim FloatArray → 2048 字节大端 IEEE-754 float
/// （Android `floatArrayToByteArray`），`Data` 原样存取，不在此层做字节序转换。
///
/// 参考源文件：
/// - `androidApp/.../data/local/AppDatabase.kt`           — Room 数据库定义
/// - `androidApp/.../data/local/entity/FaceEmbeddingEntity.kt` — face_embeddings 表
/// - `androidApp/.../data/local/entity/PersonEntity.kt`    — persons 表
/// - `androidApp/.../data/model/MediaEntity.kt`            — media_assets 表（TAG 字段子集）
/// - `androidApp/.../data/local/dao/PersonDao.kt`          — embedding / person DAO
/// - `androidApp/.../data/local/MediaDao.kt`               — media tag 写入 DAO
/// - `androidApp/.../domain/tag/TagGenerationScheduler.kt` — Pass 1 写入路径
final class TagDatabase {

    // MARK: - Properties

    /// sqlite3 数据库句柄。
    private var db: OpaquePointer?

    /// 串行队列：所有 sqlite3 调用在此队列上执行，保证线程安全。
    /// 对齐 Android：Room suspend-DAO 自动调度到 I/O executor + 调用方 `withContext(Dispatchers.IO)`。
    private let queue = DispatchQueue(label: "com.mamba.picme.tagdb", qos: .utility)

    /// 数据库文件名。与 Android 的 `"picme_database"` 不同——iOS 使用独立 TAG 专用库。
    private static let dbFileName = "polang_tag.db"

    // MARK: - Init / Deinit

    /// 打开（或创建）`Documents/polang_tag.db`，并确保所有表存在。
    init() {
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
        // ── 解析 Documents/polang_tag.db ──
        let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dbURL = docsDir.appendingPathComponent(Self.dbFileName)
        let dbPath = dbURL.path

        // sqlite3_open 若文件不存在会自动创建。
        var handle: OpaquePointer?
        guard sqlite3_open(dbPath, &handle) == SQLITE_OK else {
            let msg = handle.flatMap { String(cString: sqlite3_errmsg($0)) } ?? "unknown"
            sqlite3_close(handle)
            fatalError("[TagDatabase] Cannot open DB at \(dbPath): \(msg)")
        }
        db = handle

        // WAL 模式：并发读不阻塞写，与 Android Room 默认行为一致。
        exec("PRAGMA journal_mode=WAL;")
        exec("PRAGMA foreign_keys=ON;")

        // ── face_embeddings ──
        // Android: FaceEmbeddingEntity.kt:14-32
        //   embeddingId PK AUTOINCREMENT, mediaId NOT NULL, personId nullable,
        //   embedding BLOB NOT NULL, createdAt NOT NULL
        //   FK(personId) → persons(personId) ON DELETE SET NULL, INDEX(personId)
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

        // ── media_tags ──
        // Android: MediaEntity.kt:16-59 的 TAG 相关字段子集（hasFace / faceRoiResult /
        // faceFocusY / semanticEmbedding / labelsEn / labelsZh / lastTagScanPasses）。
        // iOS 使用 media_id INTEGER PK（而非 Android 的自增 id），因为 TAG 层只关心
        // 已索引媒体，以 media_id 为主键天然去重。
        exec("""
            CREATE TABLE IF NOT EXISTS media_tags (
                media_id           INTEGER PRIMARY KEY,
                has_face           INTEGER DEFAULT 0,
                face_roi_result    TEXT,
                face_focus_y       REAL,
                semantic_embedding TEXT,
                labels_en          TEXT,
                labels_zh          TEXT,
                last_scan_passes   TEXT
            );
            """)
        exec("CREATE INDEX IF NOT EXISTS idx_media_tags_has_face ON media_tags(has_face);")

        // ── persons ──
        // Android: PersonEntity.kt:13-24
        //   personId PK AUTOINCREMENT, name nullable, coverMediaId nullable,
        //   faceCount DEFAULT 0, is_self DEFAULT 0, createdAt, updatedAt
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
    }

    // MARK: - face_embeddings: Insert

    /// 删除该媒体的旧 embedding，再批量写入新 embedding（单事务）。
    ///
    /// 对齐 Android `TagGenerationScheduler.kt:1210-1223`：
    /// ```kotlin
    /// personDao.deleteEmbeddingsByMedia(entity.id)       // PersonDao.kt:147-148
    /// if (result.embeddings.isNotEmpty()) {
    ///     personDao.insertEmbeddings(embeddingEntities)   // PersonDao.kt:113-114
    /// }
    /// ```
    ///
    /// - Parameters:
    ///   - mediaId: 媒体 ID（对应 Android `MediaEntity.id`）。
    ///   - embeddings: 每张人脸的 512 维向量（大端 IEEE-754 float，2048 字节/条）。
    func insertEmbeddings(mediaId: Int64, embeddings: [Data]) {
        queue.sync {
            guard let db = db else { return }

            // 整个删除+插入在一个事务内，保证原子性（对齐 Room @Insert 隐式事务）。
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }

            // 1) 删除旧 embedding —— Android PersonDao.kt:147-148
            //    @Query("DELETE FROM face_embeddings WHERE mediaId = :mediaId")
            var delStmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM face_embeddings WHERE media_id = ?;", -1, &delStmt, nil)
            sqlite3_bind_int64(delStmt, 1, mediaId)
            sqlite3_step(delStmt)
            sqlite3_finalize(delStmt)

            // 2) 批量插入新 embedding —— Android PersonDao.kt:113-114
            //    personId = null（新 embedding 尚未聚类分配人物）
            //    createdAt = 当前毫秒时间戳（Android FaceEmbeddingEntity.createdAt = System.currentTimeMillis()）
            guard !embeddings.isEmpty else { return }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

            var insStmt: OpaquePointer?
            sqlite3_prepare_v2(
                db,
                "INSERT INTO face_embeddings (media_id, person_id, embedding, created_at) VALUES (?, NULL, ?, ?);",
                -1, &insStmt, nil
            )
            for emb in embeddings {
                // SQLITE_TRANSIENT: 让 sqlite 拷贝 blob 数据（Data 可能在此后被释放）。
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

    /// 获取所有尚未分配人物的 embedding（`person_id IS NULL`）。
    ///
    /// 对齐 Android `PersonDao.kt:119-120`：
    /// ```kotlin
    /// @Query("SELECT * FROM face_embeddings WHERE personId IS NULL")
    /// suspend fun getUnassignedEmbeddings(): List<FaceEmbeddingEntity>
    /// ```
    ///
    /// 用于 Pass 2（DBSCAN 聚类）读取待聚类向量。
    ///
    /// - Returns: 元组数组 `(embeddingId, mediaId, embedding)`。
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

    // MARK: - media_tags: Update

    /// 更新媒体 TAG 元数据（UPSERT）。
    ///
    /// 合并 Android 三个独立 DAO 方法为一次调用：
    /// - `updateFaceRoiResult(mediaId, json, hasFace)` — MediaDao.kt:336-337
    ///   `UPDATE media_assets SET faceRoiResult = :json, hasFace = :hasFace WHERE id = :mediaId`
    /// - `updateFaceFocusY(mediaId, faceFocusY)` — MediaDao.kt:340-341
    ///   `UPDATE media_assets SET faceFocusY = :faceFocusY WHERE id = :mediaId`
    /// - `updateSemanticEmbedding(mediaId, embedding)` — MediaDao.kt:392-393
    ///   `UPDATE media_assets SET semanticEmbedding = :embedding WHERE id = :mediaId`
    ///
    /// Android 中 `media_assets` 行一定先存在（媒体索引时创建）；iOS `media_tags` 以
    /// `media_id` 为 PK，使用 `INSERT OR IGNORE` 确保行存在后再 `UPDATE`，避免覆盖
    /// 未传入字段（如 labels_en / labels_zh / last_scan_passes）。
    ///
    /// - Parameters:
    ///   - mediaId: 媒体 ID。
    ///   - hasFace: 是否检测到人脸（对应 Android `MediaEntity.hasFace`）。
    ///   - faceRoiResult: 人脸 ROI 检测结果 JSON（对应 `MediaEntity.faceRoiResult`）。
    ///   - faceFocusY: 人脸纵向聚焦点，归一化 0~1（对应 `MediaEntity.faceFocusY`）。
    ///   - semanticEmbedding: MobileCLIP 512 维语义 embedding 的 Base64 字符串
    ///     （对应 `MediaEntity.semanticEmbedding`）。
    func updateMediaTags(
        mediaId: Int64,
        hasFace: Bool,
        faceRoiResult: String?,
        faceFocusY: Double?,
        semanticEmbedding: String?
    ) {
        queue.sync {
            guard let db = db else { return }

            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }

            // 1) 确保行存在（不影响已有字段）。
            var ensureStmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO media_tags (media_id) VALUES (?);", -1, &ensureStmt, nil)
            sqlite3_bind_int64(ensureStmt, 1, mediaId)
            sqlite3_step(ensureStmt)
            sqlite3_finalize(ensureStmt)

            // 2) 更新 TAG 元数据字段。
            var updStmt: OpaquePointer?
            sqlite3_prepare_v2(
                db,
                """
                UPDATE media_tags
                SET has_face = ?,
                    face_roi_result = ?,
                    face_focus_y = ?,
                    semantic_embedding = ?
                WHERE media_id = ?;
                """,
                -1, &updStmt, nil
            )
            // SQLite C API 参数索引从 1 开始。
            sqlite3_bind_int(updStmt, 1, hasFace ? 1 : 0)       // has_face
            if let roi = faceRoiResult {
                sqlite3_bind_text(updStmt, 2, roi, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            } else {
                sqlite3_bind_null(updStmt, 2)
            }
            if let focusY = faceFocusY {
                sqlite3_bind_double(updStmt, 3, focusY)          // face_focus_y
            } else {
                sqlite3_bind_null(updStmt, 3)
            }
            if let semEmb = semanticEmbedding {
                sqlite3_bind_text(updStmt, 4, semEmb, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            } else {
                sqlite3_bind_null(updStmt, 4)
            }
            sqlite3_bind_int64(updStmt, 5, mediaId)

            sqlite3_step(updStmt)
            sqlite3_finalize(updStmt)
        }
    }

    // MARK: - SQL Helpers

    /// 执行无需返回结果 / 无参数的 SQL（CREATE TABLE / BEGIN / COMMIT / PRAGMA 等）。
    private func exec(_ sql: String) {
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
