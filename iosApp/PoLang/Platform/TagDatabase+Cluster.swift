import Foundation
import SQLite3

// MARK: - TagDatabase · 聚类维护辅助（对标 Android FaceClusterEngine 的簇操作 DAO）
//
// 供 FaceClusterMaintenance（dissolveSinks / split / merge）使用的簇级读写：
// embedding 读取与解码、person 间 embedding 改派、media faceId 改派、face_count 重算。

/// SQLITE_TRANSIENT（文件级 private，各扩展文件各自声明）。
private let CLUSTER_SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

extension TagDatabase {

    // MARK: - embedding 读取（解码为 [Float]，512 维原生小端）

    /// 该人物的全部 embedding（embeddingId / mediaId / 解码向量）。
    func embeddingsByPerson(_ personId: Int64) -> [(embeddingId: Int64, mediaId: Int64, vector: [Float])] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT embedding_id, media_id, embedding FROM face_embeddings WHERE person_id=?;",
                -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            defer { sqlite3_finalize(stmt) }
            var out: [(embeddingId: Int64, mediaId: Int64, vector: [Float])] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                let eid = sqlite3_column_int64(stmt, 0)
                let mid = sqlite3_column_int64(stmt, 1)
                let bytes = Int(sqlite3_column_bytes(stmt, 2))
                var vec: [Float] = []
                if let ptr = sqlite3_column_blob(stmt, 2), bytes > 0 {
                    vec = Data(bytes: ptr, count: bytes).withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
                }
                out.append((eid, mid, vec))
            }
            return out
        }
    }

    /// 全部未分配（person_id IS NULL）的 embedding（dissolveSinks 释放后重分用）。
    func unassignedEmbeddingVectors() -> [(embeddingId: Int64, mediaId: Int64, vector: [Float])] {
        queue.sync {
            guard let db = db else { return [] }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "SELECT embedding_id, media_id, embedding FROM face_embeddings WHERE person_id IS NULL;",
                -1, &stmt, nil)
            defer { sqlite3_finalize(stmt) }
            var out: [(embeddingId: Int64, mediaId: Int64, vector: [Float])] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                let eid = sqlite3_column_int64(stmt, 0)
                let mid = sqlite3_column_int64(stmt, 1)
                let bytes = Int(sqlite3_column_bytes(stmt, 2))
                var vec: [Float] = []
                if let ptr = sqlite3_column_blob(stmt, 2), bytes > 0 {
                    vec = Data(bytes: ptr, count: bytes).withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
                }
                out.append((eid, mid, vec))
            }
            return out
        }
    }

    /// 该人物的 embedding 数。
    func embeddingCount(_ personId: Int64) -> Int {
        queue.sync {
            guard let db = db else { return 0 }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM face_embeddings WHERE person_id=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            defer { sqlite3_finalize(stmt) }
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
        }
    }

    // MARK: - embedding / media 改派

    /// 把 fromPerson 的全部 embedding 改派到 toPerson（merge 用）。
    func reassignEmbeddingsByPerson(fromPersonId: Int64, toPersonId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE face_embeddings SET person_id=? WHERE person_id=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, toPersonId)
            sqlite3_bind_int64(stmt, 2, fromPersonId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 单条 embedding 改派（split 用）。
    func reassignEmbedding(embeddingId: Int64, toPersonId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE face_embeddings SET person_id=? WHERE embedding_id=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, toPersonId)
            sqlite3_bind_int64(stmt, 2, embeddingId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// media_assets.faceId 字符串改派（merge 后相册分组同步）。
    func reassignMediaFaceId(fromFaceId: String, toFaceId: String) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE media_assets SET faceId=? WHERE faceId=?;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, toFaceId, -1, CLUSTER_SQLITE_TRANSIENT)
            sqlite3_bind_text(stmt, 2, fromFaceId, -1, CLUSTER_SQLITE_TRANSIENT)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 解除该人物的所有 embedding 归属（person_id=NULL），dissolveSinks 释放用。
    func unlinkEmbeddingsByPerson(_ personId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "UPDATE face_embeddings SET person_id=NULL WHERE person_id=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    // MARK: - person 写

    /// 删除人物（FK person_id ON DELETE SET NULL 清理 embedding 归属；person_relations 级联）。
    func deletePersonRow(_ personId: Int64) {
        queue.sync {
            guard let db = db else { return }
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "DELETE FROM persons WHERE person_id=?;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, personId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 整字段更新人物 stats（face_count + cover），split/merge 后用。
    func updatePersonStats(personId: Int64, faceCount: Int, coverMediaId: Int64?) {
        queue.sync {
            guard let db = db else { return }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db,
                "UPDATE persons SET face_count=?, cover_media_id=?, updated_at=? WHERE person_id=?;",
                -1, &stmt, nil)
            sqlite3_bind_int(stmt, 1, Int32(faceCount))
            if let cm = coverMediaId { sqlite3_bind_int64(stmt, 2, cm) } else { sqlite3_bind_null(stmt, 2) }
            sqlite3_bind_int64(stmt, 3, now)
            sqlite3_bind_int64(stmt, 4, personId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
        }
    }

    /// 重算全部人物的 face_count（= 其 embedding 数）。维护末尾统一调用，避免逐 op 记账。
    func recomputeFaceCounts() {
        queue.sync {
            guard let db = db else { return }
            exec("UPDATE persons SET face_count = COALESCE((SELECT COUNT(*) FROM face_embeddings fe WHERE fe.person_id = persons.person_id), 0);")
        }
    }

    /// 重算 media_assets.faceId = 该 media 任一 embedding 的 person_id（相册「人物分组」用）。
    /// 维护中 person 增删/改派后统一同步，免去逐 op 维护 faceId。多脸媒体取其一（与 Android 一致）。
    func recomputeMediaFaceIds() {
        queue.sync {
            guard let db = db else { return }
            exec("UPDATE media_assets SET faceId = (SELECT CAST(person_id AS TEXT) FROM face_embeddings WHERE media_id = media_assets.id AND person_id IS NOT NULL LIMIT 1);")
        }
    }
}
