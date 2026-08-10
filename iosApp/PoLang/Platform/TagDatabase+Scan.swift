import Foundation
import SQLite3

/// SQLITE_TRANSIENT：让 sqlite 在 bind 时立即拷贝文本/blob（Swift String 跨 bind→step 不保证指针有效）。
private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// MARK: - 扫描 DB 统计（对齐 Android TagScanDbStats；SP-B 未实现列恒 0）
struct ScanDbStats: Sendable, Equatable {
    let totalMedia: Int
    let withFace: Int
    let withLabels: Int      // SP-D
    let withSemantic: Int
    let personCount: Int     // SP-C
    let namedPersonCount: Int// SP-C
    let faceEmbeddingCount: Int
    let remainingPass1: Int
    let remainingPass3: Int  // SP-D
}

extension TagDatabase {

    // MARK: - media_assets: get-or-create / 更新 / 查询

    /// get-or-create：按 localIdentifier 返回稳定 Int64 id（对齐 Android media_assets.id）。
    @discardableResult
    func getOrCreateMedia(localIdentifier: String, type: String,
                          captureDateMs: Int64, fileName: String) -> Int64 {
        queue.sync {
            guard let db = db else { return -1 }
            // 先查
            var id: Int64 = -1
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE localIdentifier = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, localIdentifier, -1, SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW { id = sqlite3_column_int64(stmt, 0) }
            sqlite3_finalize(stmt)
            if id > 0 { return id }
            // 插入
            sqlite3_prepare_v2(db, """
            INSERT INTO media_assets (uri, type, captureDate, fileName, localIdentifier)
            VALUES (?, ?, ?, ?, ?);
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, localIdentifier, -1, SQLITE_TRANSIENT) // uri = localIdentifier
            sqlite3_bind_text(stmt, 2, type, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 3, captureDateMs)
            sqlite3_bind_text(stmt, 4, fileName, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 5, localIdentifier, -1, SQLITE_TRANSIENT)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// 写 Pass1 产出列（目标 media_assets）。
    func updateMediaAssetsScanFields(mediaId: Int64, hasFace: Bool,
                                     faceRoiResult: String?, faceFocusY: Double?,
                                     semanticEmbedding: String?, lastTagScanPasses: String?) {
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }

            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO media_assets(id) VALUES (?);", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)

            sqlite3_prepare_v2(db, """
            UPDATE media_assets SET hasFace=?, faceRoiResult=?, faceFocusY=?,
                semanticEmbedding=?, lastTagScanPasses=?, lastTagScanAt=?
            WHERE id=?;
            """, -1, &stmt, nil)
            sqlite3_bind_int(stmt, 1, hasFace ? 1 : 0)
            if let s = faceRoiResult { sqlite3_bind_text(stmt, 2, s, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 2) }
            if let f = faceFocusY { sqlite3_bind_double(stmt, 3, f) } else { sqlite3_bind_null(stmt, 3) }
            if let e = semanticEmbedding { sqlite3_bind_text(stmt, 4, e, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 4) }
            if let p = lastTagScanPasses { sqlite3_bind_text(stmt, 5, p, -1, SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 5) }
            sqlite3_bind_int64(stmt, 6, Int64(Date().timeIntervalSince1970 * 1000))
            sqlite3_bind_int64(stmt, 7, mediaId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 已完成 Pass1 的 mediaId 集合（lastTagScanPasses 含 "1"）。
    func pass1CoveredMediaIds() -> Set<Int64> {
        queue.sync {
            guard let db = db else { return [] }
            var out = Set<Int64>()
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id, lastTagScanPasses FROM media_assets WHERE lastTagScanPasses IS NOT NULL;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = sqlite3_column_int64(stmt, 0)
                if let cs = sqlite3_column_text(stmt, 1) {
                    if String(cString: cs).contains("\"1\"") { out.insert(id) }
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 所有图片 mediaId（按 captureDate 降序，对齐 fetchAllMedia 排序）。
    func allImageMediaIds() -> [Int64] {
        queue.sync {
            guard let db = db else { return [] }
            var out: [Int64] = []
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE type = 'IMAGE' ORDER BY captureDate DESC;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW { out.append(sqlite3_column_int64(stmt, 0)) }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// localIdentifier → id。
    func mediaId(forLocalIdentifier lid: String) -> Int64? {
        queue.sync {
            guard let db = db else { return nil }
            var id: Int64 = -1
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE localIdentifier = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, lid, -1, SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW { id = sqlite3_column_int64(stmt, 0) }
            sqlite3_finalize(stmt)
            return id > 0 ? id : nil
        }
    }

    /// mediaId → uri（=localIdentifier），给扫描循环加载图片用。
    func localIdentifier(forMediaId mediaId: Int64) -> String? {
        queue.sync {
            guard let db = db else { return nil }
            var lid: String?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri FROM media_assets WHERE id=? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            if sqlite3_step(stmt) == SQLITE_ROW, let cs = sqlite3_column_text(stmt, 0) {
                lid = String(cString: cs)
            }
            sqlite3_finalize(stmt)
            return lid
        }
    }

    /// 统计（对齐 Android TagScanDbStats；SP-B 未实现列返回 0）。
    func scanStats() -> ScanDbStats {
        queue.sync {
            guard let db = db else {
                return ScanDbStats(totalMedia: 0, withFace: 0, withLabels: 0, withSemantic: 0,
                                   personCount: 0, namedPersonCount: 0, faceEmbeddingCount: 0,
                                   remainingPass1: 0, remainingPass3: 0)
            }
            func countInt(_ sql: String) -> Int {
                var n = 0
                var stmt: OpaquePointer?
                sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
                if sqlite3_step(stmt) == SQLITE_ROW { n = Int(sqlite3_column_int(stmt, 0)) }
                sqlite3_finalize(stmt)
                return n
            }
            return ScanDbStats(
                totalMedia: countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE';"),
                withFace: countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND hasFace=1;"),
                withLabels: 0,
                withSemantic: countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND semanticEmbedding IS NOT NULL;"),
                personCount: 0,
                namedPersonCount: 0,
                faceEmbeddingCount: countInt("SELECT COUNT(*) FROM face_embeddings;"),
                remainingPass1: countInt("""
                    SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND (
                        lastTagScanPasses IS NULL OR lastTagScanPasses NOT LIKE '%\"1\"%');
                    """),
                remainingPass3: 0
            )
        }
    }

    // MARK: - tag_scan_tasks: 队列

    struct QueuedTask: Sendable {
        let taskId: Int64
        let mediaId: Int64
        let pass: String
    }

    /// 批量入队 Pass1 任务。
    func enqueuePass1Tasks(sessionId: String, mediaIds: [Int64], now: Int64) {
        queue.sync {
            guard let db = db else { return }
            exec("BEGIN TRANSACTION;")
            defer { exec("COMMIT;") }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            INSERT INTO tag_scan_tasks (sessionId, mediaId, pass, status, priority, attemptCount, createdAt, scheduledAt)
            VALUES (?, ?, 'FACE_DETECTION', 'PENDING', 0, 0, ?, ?);
            """, -1, &stmt, nil)
            for mid in mediaIds {
                sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
                sqlite3_bind_int64(stmt, 2, mid)
                sqlite3_bind_int64(stmt, 3, now)
                sqlite3_bind_int64(stmt, 4, now)
                sqlite3_step(stmt)
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }

    /// 取下一条可执行 PENDING（status=PENDING 且 scheduledAt<=now），FIFO。poll 不改状态。
    func pollNextPending(sessionId: String, now: Int64) -> QueuedTask? {
        queue.sync {
            guard let db = db else { return nil }
            var out: QueuedTask?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT id, mediaId, pass FROM tag_scan_tasks
            WHERE sessionId = ?
              AND (status = 'PENDING' OR (status = 'FAILED' AND attemptCount < 3))
              AND (scheduledAt IS NULL OR scheduledAt <= ?)
            ORDER BY priority ASC, id ASC LIMIT 1;
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 2, now)
            if sqlite3_step(stmt) == SQLITE_ROW {
                out = QueuedTask(taskId: sqlite3_column_int64(stmt, 0),
                                 mediaId: sqlite3_column_int64(stmt, 1),
                                 pass: String(cString: sqlite3_column_text(stmt, 2)))
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 原子地「取下一条可执行任务并置为 RUNNING」（SELECT+UPDATE 在同一 queue.sync 内）。
    /// 防止两个并发运行循环抢到同一任务导致重复处理。
    func pollAndMarkRunning(sessionId: String, now: Int64) -> QueuedTask? {
        queue.sync {
            guard let db = db else { return nil }
            var task: QueuedTask?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT id, mediaId, pass FROM tag_scan_tasks
            WHERE sessionId = ?
              AND (status = 'PENDING' OR (status = 'FAILED' AND attemptCount < 3))
              AND (scheduledAt IS NULL OR scheduledAt <= ?)
            ORDER BY priority ASC, id ASC LIMIT 1;
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 2, now)
            if sqlite3_step(stmt) == SQLITE_ROW {
                task = QueuedTask(taskId: sqlite3_column_int64(stmt, 0),
                                  mediaId: sqlite3_column_int64(stmt, 1),
                                  pass: String(cString: sqlite3_column_text(stmt, 2)))
            }
            sqlite3_finalize(stmt)
            if let t = task {
                exec("UPDATE tag_scan_tasks SET status='RUNNING', startedAt=\(now) WHERE id=\(t.taskId);")
            }
            return task
        }
    }

    func markRunning(taskId: Int64, now: Int64) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='RUNNING', startedAt=\(now) WHERE id=\(taskId);") }
    }
    func markCompleted(taskId: Int64, now: Int64) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='COMPLETED', completedAt=\(now) WHERE id=\(taskId);") }
    }
    func markFailed(taskId: Int64, now: Int64, errorMessage: String, backoffMs: Int64) {
        queue.sync {
            let escaped = errorMessage.replacingOccurrences(of: "'", with: "''")
            let sql = "UPDATE tag_scan_tasks SET status='FAILED', completedAt=\(now), " +
                      "errorMessage='\(escaped)', scheduledAt=\(now + backoffMs), " +
                      "attemptCount=(SELECT attemptCount FROM tag_scan_tasks WHERE id=\(taskId))+1 " +
                      "WHERE id=\(taskId);"
            exec(sql)
        }
    }
    func pauseSession(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PAUSED' WHERE sessionId='\(escaped)' AND status IN ('PENDING','RUNNING');")
        }
    }
    func resumeSession(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PENDING' WHERE sessionId='\(escaped)' AND status='PAUSED';")
        }
    }
    func cancelSession(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='CANCELLED' WHERE sessionId='\(escaped)' AND status IN ('PENDING','RUNNING','PAUSED');")
        }
    }
    func resetRunningToPending(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PENDING' WHERE sessionId='\(escaped)' AND status='RUNNING';")
        }
    }
    func retryFailed(sessionId: String) {
        queue.sync {
            let escaped = sessionId.replacingOccurrences(of: "'", with: "''")
            exec("UPDATE tag_scan_tasks SET status='PENDING', errorMessage=NULL WHERE sessionId='\(escaped)' AND status='FAILED';")
        }
    }
    func countTasks(sessionId: String, status: String) -> Int {
        queue.sync {
            guard let db = db else { return 0 }
            var n = 0
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM tag_scan_tasks WHERE sessionId=? AND status=?;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, status, -1, SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW { n = Int(sqlite3_column_int(stmt, 0)) }
            sqlite3_finalize(stmt)
            return n
        }
    }

    /// session 内各状态计数（GROUP BY，避免 countTasks 重入死锁）。
    func sessionCounts(_ sessionId: String) -> (pending: Int, running: Int, completed: Int, failed: Int, total: Int) {
        queue.sync {
            guard let db = db else { return (0,0,0,0,0) }
            var p = 0, r = 0, co = 0, f = 0
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT status, COUNT(*) FROM tag_scan_tasks WHERE sessionId=? GROUP BY status;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, SQLITE_TRANSIENT)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let st = String(cString: sqlite3_column_text(stmt, 0))
                let n = Int(sqlite3_column_int(stmt, 1))
                switch st {
                case "PENDING", "PAUSED": p += n
                case "RUNNING": r += n
                case "COMPLETED": co += n
                case "FAILED": f += n
                default: break
                }
            }
            sqlite3_finalize(stmt)
            return (p, r, co, f, p + r + co + f)
        }
    }

    /// 含人脸的 localIdentifier 集合（= media_assets.uri，供相册「按人脸分组」用）。
    func hasFaceLocalIdentifiers() -> Set<String> {
        queue.sync {
            guard let db = db else { return [] }
            var out = Set<String>()
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri FROM media_assets WHERE hasFace=1;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let cs = sqlite3_column_text(stmt, 0) { out.insert(String(cString: cs)) }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 是否存在未完成 session（扫描页中断恢复提示用）。
    func unfinishedSessionId() -> String? {
        queue.sync {
            guard let db = db else { return nil }
            var sid: String?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT sessionId FROM tag_scan_tasks
            WHERE status IN ('PENDING','RUNNING','PAUSED','FAILED') LIMIT 1;
            """, -1, &stmt, nil)
            if sqlite3_step(stmt) == SQLITE_ROW, let cs = sqlite3_column_text(stmt, 0) {
                sid = String(cString: cs)
            }
            sqlite3_finalize(stmt)
            return sid
        }
    }
}
