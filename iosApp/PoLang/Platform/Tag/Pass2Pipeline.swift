import Foundation

/// Pass 2：人脸嵌入 → 人物聚类（对标 Android runDbscanClustering + AdaptiveFaceClusterer）。
///
/// 全量重聚类：清空 persons + 重置 person_id/faceId → 取全部 embedding → k-NN 连通分量聚类
/// → 每簇建一个 person、赋 person_id、写 media_assets.faceId。embedding 为 512 维 Float32
/// 原生小端（arm64）、L2 归一化（cosine = dot）。
enum Pass2Pipeline {

    /// 执行全量聚类，返回人物数。建议仅在 Pass1 扫描会话结束时调用一次。
    @discardableResult
    static func runClustering() -> Int {
        let db = TagDatabase.shared

        // 全量重聚类：先清空（对标 Android isFullRescan=true；命名保留为后续优化）
        db.clearAllPersons()
        db.resetAllEmbeddingAssignments()
        db.resetAllFaceIds()

        let raw = db.getUnassignedEmbeddings() // [(embeddingId, mediaId, Data)]，此时 person_id 全 NULL = 全部
        if raw.count < FaceClusterer.minClusterSize { return 0 }

        // 解码 embedding（原生小端 Float32）
        var vectors: [[Float]] = []
        vectors.reserveCapacity(raw.count)
        var keepRaw: [(mediaId: Int64, embedding: [Float])] = []
        for r in raw {
            guard let v = decodeFloats(r.embedding), v.count == 512 else { continue }
            vectors.append(v)
            keepRaw.append((r.mediaId, v))
        }
        if vectors.count < FaceClusterer.minClusterSize { return 0 }

        let (clusters, _) = FaceClusterer.cluster(vectors)

        var personCount = 0
        for clusterIndices in clusters {
            // 该簇涉及的 mediaId（去重）
            var mediaIds = Set<Int64>()
            for idx in clusterIndices { mediaIds.insert(keepRaw[idx].mediaId) }
            let midArr = Array(mediaIds)
            let pid = db.insertPerson(name: nil, coverMediaId: midArr.first, faceCount: midArr.count, isSelf: false)
            db.assignEmbeddingsByMediaIds(midArr, personId: pid)
            db.updateFaceIdBatch(midArr, faceId: String(pid))
            personCount += 1
        }
        scanDebugLog("Pass2 clustering done: \(personCount) persons from \(vectors.count) embeddings")
        return personCount
    }

    /// Data → [Float]（原生小端）。
    private static func decodeFloats(_ data: Data) -> [Float]? {
        guard data.count % 4 == 0 else { return nil }
        return data.withUnsafeBytes { raw -> [Float] in
            Array(raw.bindMemory(to: Float.self))
        }
    }
}
