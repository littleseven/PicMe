import Foundation
import SQLite3

/// SQLITE_TRANSIENT（文件级 private，各扩展文件各自声明；与 TagDatabase+Scan.swift 同约定）。
private let SEARCH_SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// MARK: - TagDatabase · 相册搜索数据层（双端契约 SSOT: contracts.md §4 / §8）
//
// 逐字翻译 Android Room DAO SQL（MediaDao/TagDao/OcrWordDao/LocationDao/PersonDao/MediaFeedbackDao）。
// 保真要点（contracts §4.3 末尾 ⚠️ 与 §14 R8/R10）：
// - LIKE 模式串在 Swift 侧拼好 `"%kw%"` 再 bind，等价 Android SQL 的 `'%' || :kw || '%'` 拼接；
// - 用户输入**不转义** `%`/`_`（与 Android 一致，R8——不"改进"）；不加 `COLLATE NOCASE`
//   （SQLite LIKE 默认仅 ASCII 大小写不敏感，与 Android Room 同行为）；
// - `IN (:ids)` 自拼占位符并分批（SQLite 变量上限默认 999，批 500）；
// - 所有方法经 `queue.sync` 串行化，错误处理对齐现有 TagDatabase（prepare 失败记 assertionFailure 返回空）。

// MARK: - 行模型

/// 搜索结果行（media_assets 投影）。字段对齐 Android `MediaEntity`（contracts §1.3 MediaAsset）。
/// 注：iOS `type` 存 `'IMAGE'/'VIDEO'`（扫描写入侧约定），`localIdentifier` = `uri`。
struct SearchMediaRow: Equatable, Sendable {
    let id: Int64
    /// = media_assets.uri（iOS 约定 uri 即 PHAsset localIdentifier）
    let localIdentifier: String
    let type: String
    let captureDate: Int64
    let fileName: String
    let duration: Int64?
    let hasFace: Bool
    let faceId: String?
    let labels: String?
    /// 英文统一标签 JSON（§4.6；searchByLabelAllFields 三字段之一，UI 语言选择用）
    let labelsEn: String?
    /// 中文统一标签 JSON（§4.6；同上）
    let labelsZh: String?
    let ocrText: String?
    let latitude: Double?
    let longitude: Double?
    let locationName: String?
    let city: String?
    let indexedAt: Int64?
    let faceFocusY: Float?
    let aestheticScore: Float?
    let faceQualityScore: Float?
    /// semanticEmbedding 原始字符串（Base64 大端 float32×512，解码用 SemanticEmbeddingCodec，§5.5）
    let semanticEmbedding: String?
}

// MARK: - 列读取工具（文件级 private）

@inline(__always)
private func sColText(_ stmt: OpaquePointer?, _ idx: Int32) -> String? {
    if sqlite3_column_type(stmt, idx) == SQLITE_NULL { return nil }
    guard let cs = sqlite3_column_text(stmt, idx) else { return nil }
    return String(cString: cs)
}

@inline(__always)
private func sColInt64OrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Int64? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, idx)
}

@inline(__always)
private func sColDoubleOrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Double? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : sqlite3_column_double(stmt, idx)
}

@inline(__always)
private func sColFloatOrNil(_ stmt: OpaquePointer?, _ idx: Int32) -> Float? {
    sqlite3_column_type(stmt, idx) == SQLITE_NULL ? nil : Float(sqlite3_column_double(stmt, idx))
}

extension TagDatabase {

    /// `IN (?,?,...)` 分批大小（contracts §4.3 ⚠️：SQLite 变量上限默认 999，留余量取 500）。
    private static let inBatchSize = 500

    /// SearchMediaRow 投影列（顺序与 `mapSearchRow` 读取一一对应）。
    private static let searchCols = """
        id, uri, type, captureDate, fileName, duration, hasFace, faceId, \
        labels, labelsEn, labelsZh, ocrText, latitude, longitude, locationName, city, \
        indexedAt, faceFocusY, aestheticScore, faceQualityScore, semanticEmbedding
        """

    /// 带 `m.` 前缀的投影列（JOIN 查询用——location_hierarchy 与 media_assets 有
    /// city/latitude/longitude 同名列，非限定列名会报 ambiguous column name）。
    private static let searchColsM = """
        m.id, m.uri, m.type, m.captureDate, m.fileName, m.duration, m.hasFace, m.faceId, \
        m.labels, m.labelsEn, m.labelsZh, m.ocrText, m.latitude, m.longitude, m.locationName, m.city, \
        m.indexedAt, m.faceFocusY, m.aestheticScore, m.faceQualityScore, m.semanticEmbedding
        """

    /// LIKE 参数：`'%' || :kw || '%'`（contracts §4.3 各 LIKE 语句；Swift 侧预拼，语义等价）。
    private static func like(_ kw: String) -> String { "%" + kw + "%" }

    // MARK: - 行映射

    private func mapSearchRow(_ stmt: OpaquePointer?) -> SearchMediaRow? {
        guard let uri = sColText(stmt, 1) else { return nil }
        return SearchMediaRow(
            id: sqlite3_column_int64(stmt, 0),
            localIdentifier: uri,
            type: sColText(stmt, 2) ?? "",
            captureDate: sqlite3_column_int64(stmt, 3),
            fileName: sColText(stmt, 4) ?? "",
            duration: sColInt64OrNil(stmt, 5),
            hasFace: sqlite3_column_int(stmt, 6) == 1,
            faceId: sColText(stmt, 7),
            labels: sColText(stmt, 8),
            labelsEn: sColText(stmt, 9),
            labelsZh: sColText(stmt, 10),
            ocrText: sColText(stmt, 11),
            latitude: sColDoubleOrNil(stmt, 12),
            longitude: sColDoubleOrNil(stmt, 13),
            locationName: sColText(stmt, 14),
            city: sColText(stmt, 15),
            indexedAt: sColInt64OrNil(stmt, 16),
            faceFocusY: sColFloatOrNil(stmt, 17),
            aestheticScore: sColFloatOrNil(stmt, 18),
            faceQualityScore: sColFloatOrNil(stmt, 19),
            semanticEmbedding: sColText(stmt, 20))
    }

    /// 通用：执行单文本参数（或双时间参数）查询并映射行。`bind` 负责绑参。
    private func queryRows(_ sql: String, _ bind: (OpaquePointer?) -> Void) -> [SearchMediaRow] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                assertionFailure("[TagDatabase+Search] prepare failed: \(String(cString: sqlite3_errmsg(db)))\nSQL: \(sql)")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            bind(stmt)
            var rows: [SearchMediaRow] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let row = mapSearchRow(stmt) { rows.append(row) }
            }
            return rows
        }
    }

    /// 通用：执行查询并映射 Int64 id 列表。
    private func queryIds(_ sql: String, _ bind: (OpaquePointer?) -> Void) -> [Int64] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
                assertionFailure("[TagDatabase+Search] prepare failed: \(String(cString: sqlite3_errmsg(db)))\nSQL: \(sql)")
                return []
            }
            defer { sqlite3_finalize(stmt) }
            bind(stmt)
            var out: [Int64] = []
            while sqlite3_step(stmt) == SQLITE_ROW { out.append(sqlite3_column_int64(stmt, 0)) }
            return out
        }
    }

    /// `IN (:ids)` 分批执行（contracts §4.3 ⚠️）。`makeSql` 接收占位符串返回整句 SQL；
    /// `bindExtra` 在绑完本批 ids 之后绑其余参数（从下一序号开始）。
    private func queryIdsBatched(_ ids: [Int64],
                                 makeSql: (String) -> String,
                                 bindExtra: ((OpaquePointer?, Int32) -> Void)? = nil) -> [SearchMediaRow] {
        guard !ids.isEmpty else { return [] }
        var all: [SearchMediaRow] = []
        for chunkStart in stride(from: 0, to: ids.count, by: Self.inBatchSize) {
            let chunk = Array(ids[chunkStart..<min(chunkStart + Self.inBatchSize, ids.count)])
            let placeholders = chunk.map { _ in "?" }.joined(separator: ",")
            let rows = queryRows(makeSql(placeholders)) { stmt in
                for (i, mid) in chunk.enumerated() {
                    sqlite3_bind_int64(stmt, Int32(i + 1), mid)
                }
                bindExtra?(stmt, Int32(chunk.count + 1))
            }
            all.append(contentsOf: rows)
        }
        return all
    }

    // MARK: - §4.3 MediaDao 搜索 SQL（表 media_assets）

    /// searchByLabel（contracts.md:566）：标签 JSON 模糊匹配，captureDate 降序。
    func searchByLabel(_ label: String) -> [SearchMediaRow] {
        queryRows("SELECT \(Self.searchCols) FROM media_assets WHERE labels LIKE ? ORDER BY captureDate DESC;") {
            sqlite3_bind_text($0, 1, Self.like(label), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchByLabelAllFields（contracts.md:569-571）：labels/labelsEn/labelsZh 三字段 OR。
    func searchByLabelAllFields(_ keyword: String) -> [SearchMediaRow] {
        queryRows("""
            SELECT \(Self.searchCols) FROM media_assets WHERE labels LIKE ?
                OR labelsEn LIKE ? OR labelsZh LIKE ? ORDER BY captureDate DESC;
            """) { stmt in
            let p = Self.like(keyword)
            sqlite3_bind_text(stmt, 1, p, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, p, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 3, p, -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchByOcrText（contracts.md:574）。
    func searchByOcrText(_ query: String) -> [SearchMediaRow] {
        queryRows("SELECT \(Self.searchCols) FROM media_assets WHERE ocrText LIKE ? ORDER BY captureDate DESC;") {
            sqlite3_bind_text($0, 1, Self.like(query), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchByLocation（contracts.md:577）。
    func searchByLocation(_ place: String) -> [SearchMediaRow] {
        queryRows("SELECT \(Self.searchCols) FROM media_assets WHERE locationName LIKE ? ORDER BY captureDate DESC;") {
            sqlite3_bind_text($0, 1, Self.like(place), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchByFileName（contracts.md:580）。
    func searchByFileName(_ name: String) -> [SearchMediaRow] {
        queryRows("SELECT \(Self.searchCols) FROM media_assets WHERE fileName LIKE ? ORDER BY captureDate DESC;") {
            sqlite3_bind_text($0, 1, Self.like(name), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchByTimeRange（contracts.md:583）：BETWEEN 双端含。
    func searchByTimeRange(_ startMs: Int64, _ endMs: Int64) -> [SearchMediaRow] {
        queryRows("SELECT \(Self.searchCols) FROM media_assets WHERE captureDate BETWEEN ? AND ? ORDER BY captureDate DESC;") {
            sqlite3_bind_int64($0, 1, startMs)
            sqlite3_bind_int64($0, 2, endMs)
        }
    }

    /// searchAll（contracts.md:586-591）：兜底全字段模糊（labels/ocrText/locationName/fileName OR）。
    func searchAll(_ query: String) -> [SearchMediaRow] {
        queryRows("""
            SELECT \(Self.searchCols) FROM media_assets WHERE
                labels LIKE ? OR ocrText LIKE ? OR locationName LIKE ? OR fileName LIKE ?
            ORDER BY captureDate DESC;
            """) { stmt in
            let p = Self.like(query)
            for i in 1...4 { sqlite3_bind_text(stmt, Int32(i), p, -1, SEARCH_SQLITE_TRANSIENT) }
        }
    }

    /// getMediaByIds（contracts.md:594）。⚠️ 逐字：Android 该 SQL **无 ORDER BY**
    /// （调用方 MediaSearchEngine 取实体后自行按 captureDate 降序，§2.6 步骤 4）。
    func getMediaByIds(_ ids: [Int64]) -> [SearchMediaRow] {
        queryIdsBatched(ids) { "SELECT \(Self.searchCols) FROM media_assets WHERE id IN (\($0));" }
    }

    /// getHasFaceIds（contracts.md:597）。
    func getHasFaceIds() -> [Int64] {
        queryIds("SELECT id FROM media_assets WHERE hasFace = 1 ORDER BY captureDate DESC;") { _ in }
    }

    /// getMediaIdsByTimeRange（contracts.md:600）。
    func getMediaIdsByTimeRange(_ startMs: Int64, _ endMs: Int64) -> [Int64] {
        queryIds("SELECT id FROM media_assets WHERE captureDate BETWEEN ? AND ?;") {
            sqlite3_bind_int64($0, 1, startMs)
            sqlite3_bind_int64($0, 2, endMs)
        }
    }

    /// getMediaIdsByLocationKeyword（contracts.md:603）。
    func getMediaIdsByLocationKeyword(_ keyword: String) -> [Int64] {
        queryIds("SELECT id FROM media_assets WHERE locationName LIKE ?;") {
            sqlite3_bind_text($0, 1, Self.like(keyword), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// getMediaIdsByHasFace（contracts.md:606）。
    func getMediaIdsByHasFace() -> [Int64] {
        queryIds("SELECT id FROM media_assets WHERE hasFace = 1;") { _ in }
    }

    /// searchLabelsInIds（contracts.md:609）。
    func searchLabelsInIds(_ ids: [Int64], keyword: String) -> [SearchMediaRow] {
        queryIdsBatched(ids, makeSql: { "SELECT \(Self.searchCols) FROM media_assets WHERE id IN (\($0)) AND labels LIKE ?;" }) { stmt, next in
            sqlite3_bind_text(stmt, next, Self.like(keyword), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchLabelsAllFieldsInIds（contracts.md:612-615）。
    func searchLabelsAllFieldsInIds(_ ids: [Int64], keyword: String) -> [SearchMediaRow] {
        queryIdsBatched(ids, makeSql: { """
            SELECT \(Self.searchCols) FROM media_assets WHERE id IN (\($0)) AND (
                labels LIKE ? OR labelsEn LIKE ? OR labelsZh LIKE ?);
            """ }) { stmt, next in
            let p = Self.like(keyword)
            sqlite3_bind_text(stmt, next, p, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, next + 1, p, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, next + 2, p, -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchOcrInIds（contracts.md:618）。
    func searchOcrInIds(_ ids: [Int64], keyword: String) -> [SearchMediaRow] {
        queryIdsBatched(ids, makeSql: { "SELECT \(Self.searchCols) FROM media_assets WHERE id IN (\($0)) AND ocrText LIKE ?;" }) { stmt, next in
            sqlite3_bind_text(stmt, next, Self.like(keyword), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// searchFileNameInIds（contracts.md:621）。
    func searchFileNameInIds(_ ids: [Int64], keyword: String) -> [SearchMediaRow] {
        queryIdsBatched(ids, makeSql: { "SELECT \(Self.searchCols) FROM media_assets WHERE id IN (\($0)) AND fileName LIKE ?;" }) { stmt, next in
            sqlite3_bind_text(stmt, next, Self.like(keyword), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// getMediaWithSemanticEmbeddingIds（contracts.md:624）：语义召回全量候选。
    func getMediaWithSemanticEmbeddingIds() -> [Int64] {
        queryIds("""
            SELECT id FROM media_assets
            WHERE semanticEmbedding IS NOT NULL AND semanticEmbedding != '' ORDER BY captureDate DESC;
            """) { _ in }
    }

    /// getSemanticEmbedding（contracts.md:627）：返回原始 Base64 字符串，解码用 SemanticEmbeddingCodec。
    func getSemanticEmbedding(_ mediaId: Int64) -> String? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT semanticEmbedding FROM media_assets WHERE id = ?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return sColText(stmt, 0)
        }
    }

    /// updateSemanticEmbedding（contracts.md:630，写入侧）。
    /// - Parameter embedding: Base64 大端 float32×512（经 SemanticEmbeddingCodec.encode 产出）。
    func updateSemanticEmbedding(_ mediaId: Int64, embedding: String?) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE media_assets SET semanticEmbedding = ? WHERE id = ?;", -1, &stmt, nil)
            if let e = embedding { sqlite3_bind_text(stmt, 1, e, -1, SEARCH_SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 1) }
            sqlite3_bind_int64(stmt, 2, mediaId)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    // MARK: - §4.4 TagDao / OcrWordDao / LocationDao 辅助表 SQL

    /// TagDao.searchByExactTag（contracts.md:641-645，表 tags + media_tag_cross_ref）。
    func searchByExactTag(_ exactName: String) -> [SearchMediaRow] {
        queryRows("""
            SELECT DISTINCT \(Self.searchColsM) FROM media_assets m
            INNER JOIN media_tag_cross_ref c ON m.id = c.mediaId
            INNER JOIN tags t ON c.tagId = t.tagId
            WHERE t.name = ?
            ORDER BY m.captureDate DESC;
            """) {
            sqlite3_bind_text($0, 1, exactName, -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// TagDao.searchByTagName（contracts.md:648，模糊）。
    func searchByTagName(_ query: String) -> [SearchMediaRow] {
        queryRows("""
            SELECT DISTINCT \(Self.searchColsM) FROM media_assets m
            INNER JOIN media_tag_cross_ref c ON m.id = c.mediaId
            INNER JOIN tags t ON c.tagId = t.tagId
            WHERE t.name LIKE ?
            ORDER BY m.captureDate DESC;
            """) {
            sqlite3_bind_text($0, 1, Self.like(query), -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// OcrWordDao.searchByExactWord（contracts.md:651-655，表 ocr_words + ocr_word_occurrences）。
    /// ⚠️ 入参须先 lowercase（契约："入参先 lowercase"——由调用方负责，本层不重复归一）。
    func searchByExactWord(_ normalizedWord: String) -> [SearchMediaRow] {
        queryRows("""
            SELECT DISTINCT \(Self.searchColsM) FROM media_assets m
            INNER JOIN ocr_word_occurrences o ON m.id = o.mediaId
            INNER JOIN ocr_words w ON o.wordId = w.wordId
            WHERE w.normalizedWord = ?
            ORDER BY m.captureDate DESC;
            """) {
            sqlite3_bind_text($0, 1, normalizedWord, -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// OcrWordDao.searchByWordPrefix（contracts.md:658，前缀 LIKE；入参须先 lowercase）。
    func searchByWordPrefix(_ prefix: String) -> [SearchMediaRow] {
        queryRows("""
            SELECT DISTINCT \(Self.searchColsM) FROM media_assets m
            INNER JOIN ocr_word_occurrences o ON m.id = o.mediaId
            INNER JOIN ocr_words w ON o.wordId = w.wordId
            WHERE w.normalizedWord LIKE ? || '%'
            ORDER BY m.captureDate DESC;
            """) {
            sqlite3_bind_text($0, 1, prefix, -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// LocationDao.searchByPlace（contracts.md:661-668，city/district/poi/province 四字段 OR）。
    func searchByPlace(_ query: String) -> [SearchMediaRow] {
        queryRows("""
            SELECT DISTINCT \(Self.searchColsM) FROM media_assets m
            INNER JOIN media_locations ml ON m.id = ml.mediaId
            INNER JOIN location_hierarchy l ON ml.locationId = l.locationId
            WHERE l.city LIKE ? OR l.district LIKE ? OR l.poi LIKE ? OR l.province LIKE ?
            ORDER BY m.captureDate DESC;
            """) { stmt in
            let p = Self.like(query)
            for i in 1...4 { sqlite3_bind_text(stmt, Int32(i), p, -1, SEARCH_SQLITE_TRANSIENT) }
        }
    }

    // MARK: - §4.5 PersonDao 搜索 SQL（人物解析，contracts §7 交叉引用）

    /// PersonDao.findPersonByName（contracts.md:677）：模糊、取第一条。
    func findPersonByName(_ name: String) -> PersonDbRow? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT person_id, name, cover_media_id, face_count, is_self, created_at, updated_at
                FROM persons WHERE name LIKE ? LIMIT 1;
                """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, Self.like(name), -1, SEARCH_SQLITE_TRANSIENT)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return PersonDbRow(
                personId: sqlite3_column_int64(stmt, 0),
                name: sColText(stmt, 1),
                coverMediaId: sColInt64OrNil(stmt, 2),
                faceCount: Int(sqlite3_column_int(stmt, 3)),
                isSelf: sqlite3_column_int(stmt, 4) == 1,
                createdAt: sqlite3_column_int64(stmt, 5),
                updatedAt: sqlite3_column_int64(stmt, 6))
        }
    }

    /// PersonDao.getSelfPerson（contracts.md:699）：is_self=1 的"我"。（轻量 id 版用既有 selfPersonId()。）
    func getSelfPersonRow() -> PersonDbRow? {
        queue.sync {
            guard let db = db else { return nil }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT person_id, name, cover_media_id, face_count, is_self, created_at, updated_at
                FROM persons WHERE is_self = 1 LIMIT 1;
                """, -1, &stmt, nil)
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
            return PersonDbRow(
                personId: sqlite3_column_int64(stmt, 0),
                name: sColText(stmt, 1),
                coverMediaId: sColInt64OrNil(stmt, 2),
                faceCount: Int(sqlite3_column_int(stmt, 3)),
                isSelf: sqlite3_column_int(stmt, 4) == 1,
                createdAt: sqlite3_column_int64(stmt, 5),
                updatedAt: sqlite3_column_int64(stmt, 6))
        }
    }

    /// PersonDao.getMediaByPerson（contracts.md:680-683）：经 face_embeddings 归属，captureDate 降序。
    func getMediaByPerson(_ personId: Int64) -> [SearchMediaRow] {
        queryRows("""
            SELECT DISTINCT \(Self.searchColsM) FROM media_assets m
            INNER JOIN face_embeddings e ON m.id = e.media_id
            WHERE e.person_id = ?
            ORDER BY m.captureDate DESC;
            """) {
            sqlite3_bind_int64($0, 1, personId)
        }
    }

    /// PersonDao.getMediaIdsByPerson（contracts.md:686，轻量 id 版）。
    func getMediaIdsByPerson(_ personId: Int64) -> [Int64] {
        queryIds("SELECT DISTINCT media_id FROM face_embeddings WHERE person_id = ?;") {
            sqlite3_bind_int64($0, 1, personId)
        }
    }

    /// PersonDao.getMediaByPersonsCooccurrence（contracts.md:689-696）：
    /// 多人共现——`HAVING COUNT(DISTINCT personId) = 传入人数`，即每个指定人物至少一张脸同框。
    func getMediaByPersonsCooccurrence(_ personIds: [Int64], personCount: Int) -> [SearchMediaRow] {
        guard !personIds.isEmpty else { return [] }
        let placeholders = personIds.map { _ in "?" }.joined(separator: ",")
        return queryRows("""
            SELECT \(Self.searchCols) FROM media_assets m
            WHERE m.id IN (
                SELECT media_id FROM face_embeddings
                WHERE person_id IN (\(placeholders))
                GROUP BY media_id
                HAVING COUNT(DISTINCT person_id) = ?
            )
            ORDER BY m.captureDate DESC;
            """) { stmt in
            for (i, pid) in personIds.enumerated() {
                sqlite3_bind_int64(stmt, Int32(i + 1), pid)
            }
            sqlite3_bind_int64(stmt, Int32(personIds.count + 1), Int64(personCount))
        }
    }

    /// PersonDao.getPersonMediaIds（contracts.md:702-708，人物页/人物过滤态口径）：
    /// 聚类媒体 ∪ 三字段标签提及；name 空串时仅聚类（`:name != ''` 短路由 SQL 保证）。
    func getPersonMediaIds(_ personId: Int64, name: String) -> [Int64] {
        queryIds("""
            SELECT DISTINCT media_id FROM face_embeddings WHERE person_id = ?
            UNION
            SELECT id FROM media_assets WHERE ? != '' AND (
                labels LIKE ? OR labelsEn LIKE ? OR labelsZh LIKE ?
            );
            """) { stmt in
            sqlite3_bind_int64(stmt, 1, personId)
            sqlite3_bind_text(stmt, 2, name, -1, SEARCH_SQLITE_TRANSIENT)
            let p = Self.like(name)
            sqlite3_bind_text(stmt, 3, p, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 4, p, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 5, p, -1, SEARCH_SQLITE_TRANSIENT)
        }
    }

    /// 亲属关系反查（contracts §7.3 resolveByKinship 的 SQL 来源，PersonRelationDao:35-40）：
    /// `WHERE objectPersonId = self AND predicate IN (...)` → 各关系 subjectPersonId。
    func relationSubjectIds(objectPersonId: Int64, predicates: [String]) -> [Int64] {
        guard !predicates.isEmpty else { return [] }
        let placeholders = predicates.map { _ in "?" }.joined(separator: ",")
        return queryIds("""
            SELECT subjectPersonId FROM person_relations
            WHERE objectPersonId = ? AND predicate IN (\(placeholders));
            """) { stmt in
            sqlite3_bind_int64(stmt, 1, objectPersonId)
            for (i, p) in predicates.enumerated() {
                sqlite3_bind_text(stmt, Int32(i + 2), p, -1, SEARCH_SQLITE_TRANSIENT)
            }
        }
    }

    // MARK: - §8 反馈加权（media_feedback，MediaFeedbackDao.kt:21-31）

    /// 反馈落库（contracts.md:1001）：逐条 insert。feedbackType ∈ 'like'/'dislike'。
    /// ⚠️ media_id 为 TEXT（对齐 Android MediaFeedbackEntity.mediaId: String）。
    func insertMediaFeedback(mediaId: String, feedbackType: String, queryText: String, sessionId: String) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                INSERT INTO media_feedback (media_id, feedback_type, query_text, session_id, created_at)
                VALUES (?, ?, ?, ?, ?);
                """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, mediaId, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, feedbackType, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 3, queryText, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 4, sessionId, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_int64(stmt, 5, Int64(Date().timeIntervalSince1970 * 1000))
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    /// 反馈取分（contracts.md:1004-1011）：⚠️ query_text 是**精确等值匹配**（R10，非 LIKE）。
    /// 返回 media_id → (likeCount, dislikeCount)。
    func feedbackLikeDislikeCounts(queryText: String) -> [String: (likeCount: Int, dislikeCount: Int)] {
        queue.sync {
            guard let db = db else { return [:] }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT media_id,
                       SUM(CASE WHEN feedback_type = 'like' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN feedback_type = 'dislike' THEN 1 ELSE 0 END)
                FROM media_feedback
                WHERE query_text = ?
                GROUP BY media_id;
                """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, queryText, -1, SEARCH_SQLITE_TRANSIENT)
            defer { sqlite3_finalize(stmt) }
            var out: [String: (likeCount: Int, dislikeCount: Int)] = [:]
            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let mid = sColText(stmt, 0) else { continue }
                out[mid] = (likeCount: Int(sqlite3_column_int(stmt, 1)),
                            dislikeCount: Int(sqlite3_column_int(stmt, 2)))
            }
            return out
        }
    }

    // MARK: - 辅助索引写入 API（供 OCR/位置/标签索引 pass 与单测闭环用）
    //
    // 语义对齐 Android data/indexing/*IndexUpdater + 对应 DAO 写入方法（INSERT OR IGNORE /
    // clear-for-media 幂等重放）。⚠️ 当前 iOS 尚无调用方：tags/ocr_words 的 Android 写入侧
    // 为死代码（见 R9 盘点报告），location 索引 pass 为后续功能项；此处仅提供数据层能力。

    /// upsert tag（对齐 TagIndexUpdater：先 getTagByName 再 insertTag，避免唯一索引冲突）。
    @discardableResult
    func upsertTag(name: String, category: String) -> Int64 {
        queue.sync {
            guard let db = db else { return -1 }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT tagId FROM tags WHERE name = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, name, -1, SEARCH_SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let existing = sqlite3_column_int64(stmt, 0)
                sqlite3_finalize(stmt)
                return existing
            }
            sqlite3_finalize(stmt)
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO tags (name, category) VALUES (?, ?);", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, name, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, category, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// 清掉该媒体的全部标签关联（TagDao.clearTagsForMedia）。
    func clearTagsForMedia(_ mediaId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM media_tag_cross_ref WHERE mediaId = ?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    /// 写媒体-标签关联（TagDao.insertMediaTag，INSERT OR IGNORE）。
    func insertMediaTag(mediaId: Int64, tagId: Int64, confidence: Float? = nil) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO media_tag_cross_ref (mediaId, tagId, confidence) VALUES (?, ?, ?);", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_bind_int64(stmt, 2, tagId)
            if let c = confidence { sqlite3_bind_double(stmt, 3, Double(c)) } else { sqlite3_bind_null(stmt, 3) }
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    /// get-or-create OCR 词条（对齐 OcrIndexUpdater 的 insertedWordIds 去重语义；按 normalizedWord 查重）。
    @discardableResult
    func upsertOcrWord(word: String, normalizedWord: String) -> Int64 {
        queue.sync {
            guard let db = db else { return -1 }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT wordId FROM ocr_words WHERE normalizedWord = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, normalizedWord, -1, SEARCH_SQLITE_TRANSIENT)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let existing = sqlite3_column_int64(stmt, 0)
                sqlite3_finalize(stmt)
                return existing
            }
            sqlite3_finalize(stmt)
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO ocr_words (word, normalizedWord) VALUES (?, ?);", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, word, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, normalizedWord, -1, SEARCH_SQLITE_TRANSIENT)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// 清掉该媒体的全部 OCR 命中（OcrWordDao.clearWordsForMedia）。
    func clearOcrWordsForMedia(_ mediaId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM ocr_word_occurrences WHERE mediaId = ?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    /// 写 OCR 词条命中（OcrWordDao.insertOccurrence，INSERT OR IGNORE）。
    func insertOcrOccurrence(wordId: Int64, mediaId: Int64, confidence: Float? = nil, boundingBox: String? = nil) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO ocr_word_occurrences (wordId, mediaId, confidence, boundingBox) VALUES (?, ?, ?, ?);", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, wordId)
            sqlite3_bind_int64(stmt, 2, mediaId)
            if let c = confidence { sqlite3_bind_double(stmt, 3, Double(c)) } else { sqlite3_bind_null(stmt, 3) }
            if let b = boundingBox { sqlite3_bind_text(stmt, 4, b, -1, SEARCH_SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, 4) }
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    /// get-or-create 位置层级（对齐 LocationIndexUpdater：findByCoordinate 按坐标去重，
    /// 容差 0.0001 与 LocationDao.findByCoordinate 逐字一致；坐标应已由调用方按 4 位小数舍入）。
    @discardableResult
    func upsertLocation(country: String?, province: String?, city: String?,
                        district: String?, poi: String?, latitude: Double, longitude: Double) -> Int64 {
        queue.sync {
            guard let db = db else { return -1 }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
                SELECT locationId FROM location_hierarchy
                WHERE ABS(latitude - ?) < 0.0001 AND ABS(longitude - ?) < 0.0001 LIMIT 1;
                """, -1, &stmt, nil)
            sqlite3_bind_double(stmt, 1, latitude)
            sqlite3_bind_double(stmt, 2, longitude)
            if sqlite3_step(stmt) == SQLITE_ROW {
                let existing = sqlite3_column_int64(stmt, 0)
                sqlite3_finalize(stmt)
                return existing
            }
            sqlite3_finalize(stmt)
            sqlite3_prepare_v2(db, """
                INSERT OR IGNORE INTO location_hierarchy (country, province, city, district, poi, latitude, longitude)
                VALUES (?, ?, ?, ?, ?, ?, ?);
                """, -1, &stmt, nil)
            func bindOpt(_ idx: Int32, _ v: String?) {
                if let v = v { sqlite3_bind_text(stmt, idx, v, -1, SEARCH_SQLITE_TRANSIENT) } else { sqlite3_bind_null(stmt, idx) }
            }
            bindOpt(1, country); bindOpt(2, province); bindOpt(3, city); bindOpt(4, district); bindOpt(5, poi)
            sqlite3_bind_double(stmt, 6, latitude)
            sqlite3_bind_double(stmt, 7, longitude)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// 清掉该媒体的全部位置关联（LocationDao.clearLocationsForMedia）。
    func clearLocationsForMedia(_ mediaId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM media_locations WHERE mediaId = ?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }

    /// 写媒体-位置关联（LocationDao.insertMediaLocation，INSERT OR IGNORE）。
    func insertMediaLocation(mediaId: Int64, locationId: Int64, accuracy: Float? = nil) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO media_locations (mediaId, locationId, accuracy) VALUES (?, ?, ?);", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_bind_int64(stmt, 2, locationId)
            if let a = accuracy { sqlite3_bind_double(stmt, 3, Double(a)) } else { sqlite3_bind_null(stmt, 3) }
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
        }
    }
}
