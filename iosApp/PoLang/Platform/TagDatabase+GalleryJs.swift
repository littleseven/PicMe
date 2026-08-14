import Foundation
import SQLite3

/// SQLite 临时拷贝哨兵（与 TagDatabase+Scan 同义，本文件私有避免跨文件可见性问题）。
private let SQLITE_TRANSIENT_JS = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// `gallery.query` 过滤参数（移植 Android QueryFilter，多维 AND）。
struct GalleryQueryFilter {
    var label: String?
    var ocr: String?
    var location: String?
    var fromMs: Int64?
    var toMs: Int64?
    var hasFace: Bool?
    var person: String?
    var limit: Int = 200

    static let defaultLimit = 200
}

/// `media_assets` 白名单行（gallery.meta 用）。
/// **隐私红线**：不含 localIdentifier(uri)/latitude/longitude/ocrText/embedding/ROI；
/// 仅计数/标签/评分等聚合友好字段（对齐 Android MediaEntity.toMetaJsValue 白名单）。
struct MediaDbRow {
    let id: Int64
    let type: String          // 'IMAGE' / 'VIDEO'（iOS DB 存储值；Android 枚举名 PHOTO/VIDEO，端差异）
    let captureMs: Int64
    let fileName: String
    let labels: String?       // JSON 数组串 `["猫","户外"]`
    let locationName: String?
    let city: String?
    let hasFace: Bool
    let faceId: String?
    let aestheticScore: Double?
    let faceQualityScore: Double?
}

/// 绑定值（参数化查询防注入——filter 值来自 LLM 生成的 JS）。
private enum BindValue {
    case text(String)
    case int(Int64)
    func bind(to stmt: OpaquePointer?, index: Int32) {
        switch self {
        case .text(let s):
            sqlite3_bind_text(stmt, index, s, -1, SQLITE_TRANSIENT_JS)
        case .int(let n):
            sqlite3_bind_int64(stmt, index, n)
        }
    }
}

/// Gallery JS 沙盒 handler 的只读聚合/查询（gallery.summary/tags、media.meta/batch_meta、
/// gallery.query/stats_by_tag 数据源）。纯 SQLite 读（线程安全：复用 TagDatabase 串行 queue）。
extension TagDatabase {

    // MARK: - 盘点（Tier 1）

    /// 媒体类型计数（gallery.summary 的 totalPhotos/totalVideos 拆分）。
    func mediaTypeCounts() -> (photos: Int, videos: Int) {
        queue.sync {
            guard let db = db else { return (photos: 0, videos: 0) }
            var photos = 0
            var videos = 0
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT type, COUNT(*) FROM media_assets GROUP BY type;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let type = String(cString: sqlite3_column_text(stmt, 0))
                let count = Int(sqlite3_column_int(stmt, 1))
                if type == "IMAGE" {
                    photos = count
                } else if type == "VIDEO" {
                    videos = count
                }
            }
            sqlite3_finalize(stmt)
            return (photos: photos, videos: videos)
        }
    }

    /// 标签 → 关联媒体计数（gallery.tags，全局分布）。
    func tagCounts(limit: Int = 50) -> [(name: String, count: Int)] {
        queue.sync {
            guard let db = db else { return [(name: String, count: Int)]() }
            var out: [(name: String, count: Int)] = []
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT t.name, COUNT(r.mediaId) AS cnt
                FROM tags t
                LEFT JOIN media_tag_cross_ref r ON t.tagId = r.tagId
                GROUP BY t.tagId
                ORDER BY cnt DESC
                LIMIT ?;
                """, -1, &stmt, nil)
            sqlite3_bind_int(stmt, 1, Int32(limit))
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let raw = sqlite3_column_text(stmt, 0) {
                    out.append((name: String(cString: raw), count: Int(sqlite3_column_int(stmt, 1))))
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    // MARK: - 元数据（Tier 2：media.meta / media.batch_meta）

    /// 单媒体白名单元数据（只读）。
    func mediaRow(id: Int64) -> MediaDbRow? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT id, type, captureDate, fileName, labels, locationName, city,
                       hasFace, faceId, aestheticScore, faceQualityScore
                FROM media_assets WHERE id=? LIMIT 1;
                """, -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, id)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return Self.readMediaRow(stmt)
        }
    }

    /// 批量媒体白名单元数据（只读，截断 [maxIds] 防爆量）。
    func mediaRows(ids: [Int64], maxIds: Int = 50) -> [MediaDbRow] {
        let limited = Array(ids.prefix(maxIds))
        guard !limited.isEmpty else { return [] }
        return queue.sync {
            guard let db = db else { return [MediaDbRow]() }
            let placeholders = limited.map { _ in "?" }.joined(separator: ",")
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT id, type, captureDate, fileName, labels, locationName, city,
                       hasFace, faceId, aestheticScore, faceQualityScore
                FROM media_assets WHERE id IN (\(placeholders));
                """, -1, &stmt, nil)
            for (i, id) in limited.enumerated() {
                sqlite3_bind_int64(stmt, Int32(i + 1), id)
            }
            defer { sqlite3_finalize(stmt) }
            var rows: [MediaDbRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let row = Self.readMediaRow(stmt) { rows.append(row) }
            }
            return rows
        }
    }

    // MARK: - 结构化查询（Tier 2：gallery.query / gallery.stats_by_tag）

    /// 人物名 → 命中媒体 id 集合（persons.name LIKE → face_embeddings 归属的 mediaId）。
    /// 未命名/未找到 → 空集（上层据此返回空结果，不误回全量）。对齐 Android resolvePersonMediaIds。
    func personMediaIds(name: String) -> [Int64] {
        queue.sync {
            guard let db = db else { return [] }
            // 名 → personId（LIKE 模糊，对齐 PersonDao.findPersonByName）
            var personIds: [Int64] = []
            var s1: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT person_id FROM persons WHERE name LIKE ?;", -1, &s1, nil)
            sqlite3_bind_text(s1, 1, "%\(name)%", -1, SQLITE_TRANSIENT_JS)
            while sqlite3_step(s1) == SQLITE_ROW {
                personIds.append(sqlite3_column_int64(s1, 0))
            }
            sqlite3_finalize(s1)
            guard !personIds.isEmpty else { return [] }
            // personId → mediaId（face_embeddings，仅存于 media_assets 的）
            let placeholders = personIds.map { _ in "?" }.joined(separator: ",")
            var s2: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT DISTINCT media_id FROM face_embeddings
                WHERE person_id IN (\(placeholders))
                  AND media_id IN (SELECT id FROM media_assets);
                """, -1, &s2, nil)
            for (i, pid) in personIds.enumerated() {
                sqlite3_bind_int64(s2, Int32(i + 1), pid)
            }
            var mediaIds = Set<Int64>()
            while sqlite3_step(s2) == SQLITE_ROW {
                mediaIds.insert(sqlite3_column_int64(s2, 0))
            }
            sqlite3_finalize(s2)
            return Array(mediaIds)
        }
    }

    /// 结构化查询：多维 AND filter → 命中 media id（按 captureDate 降序）。
    /// 返回**未截断**全集（handler 层按 limit 截断；total = 全集大小）。对齐 Android QueryGalleryMediaUseCase。
    /// person 先在独立 queue.sync 解析（见 personMediaIds），不与本查询的 queue.sync 嵌套（避免串行队列重入死锁）。
    func queryMediaIds(filter: GalleryQueryFilter) -> [Int64] {
        let personIds: Set<Int64>? = filter.person.map { Set(personMediaIds(name: $0)) }
        if filter.person != nil, personIds?.isEmpty != false { return [] }
        return queue.sync {
            guard let db = db else { return [] }
            var conditions: [String] = []
            var bindValues: [BindValue] = []
            if let label = filter.label, !label.isEmpty {
                conditions.append("labels LIKE ?")
                bindValues.append(.text("%\(label)%"))
            }
            if let ocr = filter.ocr, !ocr.isEmpty {
                conditions.append("ocrText LIKE ?")
                bindValues.append(.text("%\(ocr)%"))
            }
            if let location = filter.location, !location.isEmpty {
                conditions.append("locationName LIKE ?")
                bindValues.append(.text("%\(location)%"))
            }
            if filter.fromMs != nil || filter.toMs != nil {
                conditions.append("captureDate BETWEEN ? AND ?")
                bindValues.append(.int(filter.fromMs ?? 0))
                bindValues.append(.int(filter.toMs ?? Int64.max))
            }
            if let hasFace = filter.hasFace {
                conditions.append("hasFace = ?")
                bindValues.append(.int(hasFace ? 1 : 0))
            }
            if let pids = personIds, !pids.isEmpty {
                let placeholders = pids.map { _ in "?" }.joined(separator: ",")
                conditions.append("id IN (\(placeholders))")
                bindValues.append(contentsOf: pids.map { .int($0) })
            }
            let whereClause = conditions.isEmpty ? "" : "WHERE " + conditions.joined(separator: " AND ")
            let sql = "SELECT id FROM media_assets \(whereClause) ORDER BY captureDate DESC;"
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
            for (i, value) in bindValues.enumerated() {
                value.bind(to: stmt, index: Int32(i + 1))
            }
            defer { sqlite3_finalize(stmt) }
            var ids: [Int64] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                ids.append(sqlite3_column_int64(stmt, 0))
            }
            return ids
        }
    }

    /// 在 [filter] 结果集内聚合标签分布（gallery.stats_by_tag）。对齐 Android tagsByFilter。
    func tagsByFilter(filter: GalleryQueryFilter, limit: Int = 50) -> [(name: String, count: Int)] {
        let candidateIds = queryMediaIds(filter: filter)
        guard !candidateIds.isEmpty else { return [] }
        return queue.sync {
            guard let db = db else { return [(name: String, count: Int)]() }
            let placeholders = candidateIds.map { _ in "?" }.joined(separator: ",")
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT labels FROM media_assets WHERE id IN (\(placeholders));", -1, &stmt, nil)
            for (i, id) in candidateIds.enumerated() {
                sqlite3_bind_int64(stmt, Int32(i + 1), id)
            }
            defer { sqlite3_finalize(stmt) }
            var counts: [String: Int] = [:]
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let raw = sqlite3_column_text(stmt, 0) {
                    parseLabelArray(String(cString: raw)).forEach { counts[$0, default: 0] += 1 }
                }
            }
            return counts.sorted { $0.value > $1.value }
                .prefix(limit)
                .map { (name: $0.key, count: $0.value) }
        }
    }

    // MARK: - Tier 3：timeline / city / face.cluster / tag.audit 数据源

    /// 时间分桶统计（gallery.timeline）。读 [fromMs,toMs] 内媒体 captureDate，按 bucketMs 整除分桶。
    /// 返回桶起始时间戳→计数（时间升序）。对齐 Android QueryGalleryMediaUseCase.timeline。
    func timelineCounts(fromMs: Int64?, toMs: Int64?, bucketMs: Int64) -> [(bucketMs: Int64, count: Int)] {
        let start = fromMs ?? 0
        let end = toMs ?? Int64.max
        return queue.sync {
            guard let db = db else { return [(bucketMs: Int64, count: Int)]() }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT captureDate FROM media_assets WHERE captureDate BETWEEN ? AND ?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, start)
            sqlite3_bind_int64(stmt, 2, end)
            defer { sqlite3_finalize(stmt) }
            var buckets: [Int64: Int] = [:]
            while sqlite3_step(stmt) == SQLITE_ROW {
                let capture = sqlite3_column_int64(stmt, 0)
                let key = bucketMs > 0 ? (capture / bucketMs) * bucketMs : capture
                buckets[key, default: 0] += 1
            }
            return buckets.sorted { $0.key < $1.key }.map { (bucketMs: $0.key, count: $0.value) }
        }
    }

    /// 城市分组媒体计数（gallery.stats_by_city，DB 层 GROUP BY）。
    func cityCounts(limit: Int = 50) -> [(city: String, count: Int)] {
        queue.sync {
            guard let db = db else { return [(city: String, count: Int)]() }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT city, COUNT(*) AS cnt FROM media_assets
                WHERE city IS NOT NULL AND city != ''
                GROUP BY city ORDER BY cnt DESC LIMIT ?;
                """, -1, &stmt, nil)
            sqlite3_bind_int(stmt, 1, Int32(limit))
            defer { sqlite3_finalize(stmt) }
            var out: [(city: String, count: Int)] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let raw = sqlite3_column_text(stmt, 0) {
                    out.append((city: String(cString: raw), count: Int(sqlite3_column_int(stmt, 1))))
                }
            }
            return out
        }
    }

    /// face_embeddings 计数（total + 未归属 person_id IS NULL）。
    func embeddingCounts() -> (total: Int, unassigned: Int) {
        queue.sync {
            guard let db = db else { return (total: 0, unassigned: 0) }
            func countInt(_ sql: String) -> Int {
                var stmt: OpaquePointer?
                sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
                defer { sqlite3_finalize(stmt) }
                return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
            }
            return (
                total: countInt("SELECT COUNT(*) FROM face_embeddings;"),
                unassigned: countInt("SELECT COUNT(*) FROM face_embeddings WHERE person_id IS NULL;")
            )
        }
    }

    /// tag.audit 计数聚合（IMAGE 口径，与 gallery.summary 一致）。
    func tagAuditCounts() -> (totalMedia: Int, unlabeled: Int, neverScanned: Int, lastScanAt: Int64?) {
        queue.sync {
            guard let db = db else { return (0, 0, 0, nil) }
            func countInt(_ sql: String) -> Int {
                var stmt: OpaquePointer?
                sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
                defer { sqlite3_finalize(stmt) }
                return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
            }
            let totalMedia = countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE';")
            let unlabeled = countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND labelsEn IS NULL;")
            let neverScanned = countInt("SELECT COUNT(*) FROM media_assets WHERE lastTagScanAt IS NULL;")
            var lastScanAt: Int64?
            var s: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT MAX(lastTagScanAt) FROM media_assets;", -1, &s, nil)
            if sqlite3_step(s) == SQLITE_ROW, sqlite3_column_type(s, 0) != SQLITE_NULL {
                lastScanAt = sqlite3_column_int64(s, 0)
            }
            sqlite3_finalize(s)
            return (totalMedia, unlabeled, neverScanned, lastScanAt)
        }
    }

    // MARK: - 行读取 / 标签解析辅助

    /// 从 stmt 当前行读 MediaDbRow（列顺序须与 SELECT 一致）。
    private static func readMediaRow(_ stmt: OpaquePointer?) -> MediaDbRow? {
        guard let stmt = stmt else { return nil }
        func text(_ idx: Int32) -> String? {
            guard let raw = sqlite3_column_text(stmt, idx) else { return nil }
            return String(cString: raw)
        }
        let hasFace = sqlite3_column_int(stmt, 7) != 0
        let aesthetic = sqlite3_column_type(stmt, 9) == SQLITE_NULL ? nil : sqlite3_column_double(stmt, 9)
        let faceQuality = sqlite3_column_type(stmt, 10) == SQLITE_NULL ? nil : sqlite3_column_double(stmt, 10)
        return MediaDbRow(
            id: sqlite3_column_int64(stmt, 0),
            type: text(1) ?? "IMAGE",
            captureMs: sqlite3_column_int64(stmt, 2),
            fileName: text(3) ?? "",
            labels: text(4),
            locationName: text(5),
            city: text(6),
            hasFace: hasFace,
            faceId: text(8),
            aestheticScore: aesthetic,
            faceQualityScore: faceQuality
        )
    }

    /// `["猫","户外"]` JSON 数组串 → [String]；空/异常 → []。
    private func parseLabelArray(_ raw: String?) -> [String] {
        guard let raw = raw, !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [String] else {
            return []
        }
        return array
    }
}
