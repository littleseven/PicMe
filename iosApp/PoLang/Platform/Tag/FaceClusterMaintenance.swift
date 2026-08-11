import Foundation

// MARK: - FaceClusterMaintenance（对标 Android FaceClusterEngine.runClusterMaintenance）
//
// Pass2 k-NN 连通分量聚类**之后**的三道精修 pass + 释放重分 + 统一记账：
//   1. dissolveSinks          —— 解散 k-NN 链式并出的垃圾簇（median 两两相似度 < 0.40），释放重分
//   2. splitOvermergedClusters —— 把「两人并成一组」的簇切开（最远种子二分，intra≥0.55 & cross≤0.45）
//   3. mergeSmallClusters     —— 凝聚合并：小碎片簇（≤4）用宽松 0.50 阈值愈合过分裂（修 #1647/#1628）
//   4. recomputeFaceCounts / recomputeMediaFaceIds —— 统一重算，免去逐 op 记账
//
// 参数值与 :shared `ClusteringConfig` 完全一致（SSOT 在 Kotlin 侧；此处为 Swift 镜像）。
// 余弦相似度复用 `FaceClusterer.cosine`（完整 dot/(‖a‖·‖b‖)，对未归一化输入鲁棒）。

/// 聚类维护参数（镜像 shared/.../ClusteringConfig.kt）。
private enum ClusterParams {
    static let cosineThreshold: Float = 0.65          // COSINE_THRESHOLD
    static let mergeSimilarityThreshold: Float = 0.65  // MERGE_SIMILARITY_THRESHOLD
    static let mergeSmallSimThreshold: Float = 0.50    // MERGE_SMALL_CLUSTER_SIMILARITY_THRESHOLD
    static let mergeSmallMaxSize = 4                    // MERGE_SMALL_CLUSTER_MAX_SIZE
    static let splitIntraMin: Float = 0.55             // SPLIT_INTRA_MIN
    static let splitCrossMax: Float = 0.45             // SPLIT_CROSS_MAX
    static let splitMinClusterSize = 4                  // SPLIT_MIN_CLUSTER_SIZE
    static let sinkCohesionMax: Float = 0.40           // SINK_COHESION_MAX
    static let sinkMinSize = 8                          // SINK_MIN_SIZE
    static let sinkSampleCap = 30                       // SINK_SAMPLE_CAP
    static let maxMergeIterations = 200
    static let maxSplitIterations = 200
}

enum FaceClusterMaintenance {

    /// 聚类维护：解散 sink → 拆分过并 → 合并碎片。Pass2 聚类后调用。
    @discardableResult
    static func runClusterMaintenance() -> Int {
        let dissolved = dissolveSinks()
        let split = splitOvermergedClusters()
        let merged = mergeSmallClusters()
        // 统一重算 face_count（维护中 person 增删后计数可能漂移）+ media faceId（相册分组用）
        TagDatabase.shared.recomputeFaceCounts()
        TagDatabase.shared.recomputeMediaFaceIds()
        if dissolved + split + merged > 0 {
            scanDebugLog("ClusterMaintenance: dissolved=\(dissolved) split=\(split) merged=\(merged)")
        }
        return dissolved + split + merged
    }

    // MARK: - dissolveSinks（解散链式垃圾簇）

    /// 解散匿名 sink（median 两两相似度 < 0.40、规模 ≥ 8），释放其 embedding 后重分到正确的人/新建小簇。
    @discardableResult
    static func dissolveSinks() -> Int {
        let db = TagDatabase.shared
        var dissolved = 0
        for person in db.allPersonRows() {
            if let name = person.name, !name.isEmpty { continue }   // 命名人物尊重，不解散
            let embs = db.embeddingsByPerson(person.personId)
            if embs.count < ClusterParams.sinkMinSize { continue }
            let sample = embs.prefix(ClusterParams.sinkSampleCap).map { $0.vector }
            if medianPairwiseSim(sample) >= ClusterParams.sinkCohesionMax { continue }
            db.unlinkEmbeddingsByPerson(person.personId)
            db.deletePersonRow(person.personId)
            dissolved += 1
        }
        if dissolved > 0 { reassignUnassignedEmbeddings() }
        return dissolved
    }

    /// 把 person_id=NULL 的 embedding 按 0.65 质心匹配归入已有簇，否则建新簇（对标 assignStoredEmbeddings）。
    static func reassignUnassignedEmbeddings() {
        let db = TagDatabase.shared
        let unassigned = db.unassignedEmbeddingVectors()
        guard !unassigned.isEmpty else { return }

        // 现存 person 质心
        var centroids: [Int64: [Float]] = [:]
        for person in db.allPersonRows() {
            if let c = centroid(of: person.personId) { centroids[person.personId] = c }
        }

        for (eid, mid, vec) in unassigned {
            if vec.isEmpty || vec.allSatisfy({ $0 == 0 }) { continue }   // 跳过零向量占位
            var bestPid: Int64? = nil
            var bestSim = ClusterParams.cosineThreshold
            for (pid, cent) in centroids {
                let sim = FaceClusterer.cosine(vec, cent)
                if sim > bestSim { bestSim = sim; bestPid = pid }
            }
            if let pid = bestPid {
                db.reassignEmbedding(embeddingId: eid, toPersonId: pid)
            } else {
                let newPid = db.insertPerson(name: nil, coverMediaId: mid, faceCount: 1, isSelf: false)
                db.reassignEmbedding(embeddingId: eid, toPersonId: newPid)
                centroids[newPid] = vec
            }
        }
    }

    // MARK: - splitOvermergedClusters（拆分两人被并成一组）

    /// 簇内最远两点为种子分两半，仅当两半各 ≥2、各自内聚 ≥ 0.55、互相交叉 ≤ 0.45 才拆——较小半 spin off 新 person。
    @discardableResult
    static func splitOvermergedClusters() -> Int {
        let db = TagDatabase.shared
        var totalSplits = 0
        var guard_ = 0
        while guard_ < ClusterParams.maxSplitIterations {
            guard_ += 1
            let persons = db.allPersonRows()
            var didSplit = false
            for person in persons {
                let embs = db.embeddingsByPerson(person.personId)
                if embs.count < ClusterParams.splitMinClusterSize { continue }
                let feats = embs.map { $0.vector }
                let (halfA, halfB) = twoWayPartition(feats)
                if halfA.count < 2 || halfB.count < 2 { continue }
                let intraA = meanPairwiseSim(feats, halfA)
                let intraB = meanPairwiseSim(feats, halfB)
                let cross = meanCrossSim(feats, halfA, halfB)
                if intraA < ClusterParams.splitIntraMin || intraB < ClusterParams.splitIntraMin
                    || cross > ClusterParams.splitCrossMax { continue }

                let (keepIdx, spinIdx) = halfA.count >= halfB.count ? (halfA, halfB) : (halfB, halfA)
                let spinMediaIds = Array(Set(spinIdx.map { embs[$0].mediaId }))
                let newPid = db.insertPerson(name: nil, coverMediaId: spinMediaIds.first, faceCount: spinIdx.count, isSelf: false)
                for idx in spinIdx {
                    db.reassignEmbedding(embeddingId: embs[idx].embeddingId, toPersonId: newPid)
                }
                let keepMediaIds = Array(Set(keepIdx.map { embs[$0].mediaId }))
                db.updatePersonStats(personId: person.personId,
                                     faceCount: keepIdx.count,
                                     coverMediaId: keepMediaIds.first ?? person.coverMediaId)
                totalSplits += 1
                didSplit = true
                scanDebugLog("ClusterMaintenance split: \(person.personId) keep=\(keepIdx.count) spin=new\(newPid) \(spinIdx.count) (intraA=\(intraA) intraB=\(intraB) cross=\(cross))")
                break   // personId 变化，重新拉 persons
            }
            if !didSplit { break }
        }
        return totalSplits
    }

    // MARK: - mergeSmallClusters（凝聚合并碎片，修过分裂）

    /// 凝聚式：每轮在存活 person 中找质心相似度最高且可合并的一对 → 合并。双方均 ≤4 的小簇用宽松 0.50 阈值。
    /// 双方均已命名则跳过（尊重人工区分）。
    @discardableResult
    static func mergeSmallClusters() -> Int {
        let db = TagDatabase.shared
        let persons = db.allPersonRows()

        var centroids: [Int64: [Float]] = [:]
        var counts: [Int64: Int] = [:]
        var names: [Int64: String] = [:]
        var selves: [Int64: Bool] = [:]
        for person in persons {
            guard let c = centroid(of: person.personId) else { continue }
            let cnt = db.embeddingCount(person.personId)
            guard cnt > 0 else { continue }
            centroids[person.personId] = c
            counts[person.personId] = cnt
            names[person.personId] = person.name ?? ""
            selves[person.personId] = person.isSelf
        }
        var alive = Set(centroids.keys)
        guard alive.count >= 2 else { return 0 }

        var totalMerges = 0
        var guard_ = 0
        while guard_ < ClusterParams.maxMergeIterations {
            guard_ += 1
            let list = Array(alive)
            var bestSim: Float = -.greatestFiniteMagnitude
            var bestA: Int64 = -1
            var bestB: Int64 = -1
            for i in 0..<list.count {
                let a = list[i]
                guard let ca = centroids[a] else { continue }
                for j in (i + 1)..<list.count {
                    let b = list[j]
                    guard let cb = centroids[b] else { continue }
                    let aNamed = !(names[a] ?? "").isEmpty
                    let bNamed = !(names[b] ?? "").isEmpty
                    if aNamed && bNamed { continue }   // 双方命名：尊重人工区分
                    let bothSmall = (counts[a] ?? 0) <= ClusterParams.mergeSmallMaxSize
                        && (counts[b] ?? 0) <= ClusterParams.mergeSmallMaxSize
                    let effThresh = bothSmall
                        ? min(ClusterParams.mergeSimilarityThreshold, ClusterParams.mergeSmallSimThreshold)
                        : ClusterParams.mergeSimilarityThreshold
                    let sim = FaceClusterer.cosine(ca, cb)
                    if sim <= effThresh { continue }
                    if sim > bestSim { bestSim = sim; bestA = a; bestB = b }
                }
            }
            if bestA < 0 { break }

            // 决定幸存者：命名优先 → 数量大 → id 小
            let (survivor, absorbed) = decideMergeSurvivor(
                a: bestA, b: bestB, names: names, selves: selves, counts: counts)

            db.reassignEmbeddingsByPerson(fromPersonId: absorbed, toPersonId: survivor)
            db.deletePersonRow(absorbed)
            alive.remove(absorbed)
            counts[survivor] = (counts[survivor] ?? 0) + (counts.removeValue(forKey: absorbed) ?? 0)
            names.removeValue(forKey: absorbed)
            selves.removeValue(forKey: absorbed)
            centroids[survivor] = centroid(of: survivor)   // 重算幸存者质心
            centroids.removeValue(forKey: absorbed)

            totalMerges += 1
            scanDebugLog("ClusterMaintenance merge: \(absorbed)->\(survivor) sim=\(bestSim) total=\(totalMerges)")
        }
        return totalMerges
    }

    /// 合并幸存者选择：命名者幸存；都未命名则数量大的幸存；再平则 id 小的幸存。
    private static func decideMergeSurvivor(
        a: Int64, b: Int64,
        names: [Int64: String], selves: [Int64: Bool], counts: [Int64: Int]
    ) -> (survivor: Int64, absorbed: Int64) {
        let aNamed = !(names[a] ?? "").isEmpty
        let bNamed = !(names[b] ?? "").isEmpty
        if aNamed && !bNamed { return (a, b) }
        if bNamed && !aNamed { return (b, a) }
        let ca = counts[a] ?? 1
        let cb = counts[b] ?? 1
        if ca != cb { return ca > cb ? (a, b) : (b, a) }
        return a < b ? (a, b) : (b, a)
    }

    // MARK: - 数值辅助（对标 Android FaceClusterEngine 私有函数）

    /// 人物质心（其全部 embedding 的算术平均）。
    private static func centroid(of personId: Int64) -> [Float]? {
        let embs = TagDatabase.shared.embeddingsByPerson(personId)
        guard !embs.isEmpty else { return nil }
        let dim = embs[0].vector.count
        var cen = [Float](repeating: 0, count: dim)
        for e in embs where e.vector.count == dim {
            for i in 0..<dim { cen[i] += e.vector[i] }
        }
        let n = Float(embs.count)
        for i in 0..<dim { cen[i] /= n }
        return cen
    }

    /// 以最远（最低相似度）两点为种子把向量集分两半（返回 feats 下标列表）。
    private static func twoWayPartition(_ feats: [[Float]]) -> (a: [Int], b: [Int]) {
        guard feats.count >= 2 else { return ([0], []) }
        var wi = 0, wj = 1
        var worst = FaceClusterer.cosine(feats[0], feats[1])
        for i in 0..<feats.count {
            for j in (i + 1)..<feats.count {
                let s = FaceClusterer.cosine(feats[i], feats[j])
                if s < worst { worst = s; wi = i; wj = j }
            }
        }
        var a = [wi]
        var b = [wj]
        for k in 0..<feats.count where k != wi && k != wj {
            if FaceClusterer.cosine(feats[k], feats[wi]) >= FaceClusterer.cosine(feats[k], feats[wj]) {
                a.append(k)
            } else {
                b.append(k)
            }
        }
        return (a, b)
    }

    /// 一组内（按 feats 下标）的平均两两相似度。
    private static func meanPairwiseSim(_ feats: [[Float]], _ idx: [Int]) -> Float {
        if idx.count < 2 { return 0 }
        var sum: Float = 0
        var n = 0
        for i in 0..<idx.count {
            for j in (i + 1)..<idx.count {
                sum += FaceClusterer.cosine(feats[idx[i]], feats[idx[j]])
                n += 1
            }
        }
        return n > 0 ? sum / Float(n) : 0
    }

    /// 一组向量的 median 两两相似度（sink 判定）。
    private static func medianPairwiseSim(_ feats: [[Float]]) -> Float {
        if feats.count < 2 { return 1 }
        var sims: [Float] = []
        for i in 0..<feats.count {
            for j in (i + 1)..<feats.count {
                sims.append(FaceClusterer.cosine(feats[i], feats[j]))
            }
        }
        sims.sort()
        return sims[sims.count / 2]
    }

    /// 两组（按 feats 下标）之间的平均相似度。
    private static func meanCrossSim(_ feats: [[Float]], _ a: [Int], _ b: [Int]) -> Float {
        var sum: Float = 0
        var n = 0
        for i in a {
            for j in b {
                sum += FaceClusterer.cosine(feats[i], feats[j])
                n += 1
            }
        }
        return n > 0 ? sum / Float(n) : 0
    }
}
