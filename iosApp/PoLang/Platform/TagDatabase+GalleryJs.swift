import Foundation
import SQLite3

/// Gallery JS 沙盒 handler 的只读聚合查询（gallery.summary / gallery.tags 数据源）。
///
/// 对齐 Android `GetGallerySummaryUseCase` / `QueryGalleryMediaUseCase.tags()`——
/// 数据均来自 media_assets / tags / media_tag_cross_ref 表，纯 SQLite 读（线程安全：复用 TagDatabase 串行 queue）。
extension TagDatabase {

    /// 媒体类型计数（gallery.summary 的 totalPhotos/totalVideos 拆分）。
    /// media_assets.type：'IMAGE' / 'VIDEO'。
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

    /// 标签 → 关联媒体计数（gallery.tags）。
    /// 规范化 tags 表 LEFT JOIN media_tag_cross_ref 聚合，按 count 降序截断 [limit]（默认 50，对齐 Android MAX_TOP_N）。
    func tagCounts(limit: Int = 50) -> [(name: String, count: Int)] {
        queue.sync {
            guard let db = db else { return [(name: String, count: Int)]() }
            var out: [(name: String, count: Int)] = []
            var stmt: OpaquePointer?
            // limit 为受控 Int，内联安全（非用户输入）
            let sql = """
                SELECT t.name, COUNT(r.mediaId) AS cnt
                FROM tags t
                LEFT JOIN media_tag_cross_ref r ON t.tagId = r.tagId
                GROUP BY t.tagId
                ORDER BY cnt DESC
                LIMIT \(limit);
                """
            sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let raw = sqlite3_column_text(stmt, 0) {
                    let name = String(cString: raw)
                    let count = Int(sqlite3_column_int(stmt, 1))
                    out.append((name: name, count: count))
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }
}
