import Foundation

/// 人脸聚类（对标 Android AdaptiveFaceClusterer：k-NN 图连通分量）。
///
/// 算法：为每个样本取 k 近邻（相似度 ≥ minSimilarity 才建无向边）→ 求连通分量 →
/// 大小 ≥ minClusterSize 的分量作为人物簇，其余为噪声。对 embedding 质量波动鲁棒，
/// 不需全局统一 eps。embedding 须 L2 归一化（cosine = dot）。
enum FaceClusterer {
    static let knnK = 2
    static let minSimilarity: Float = 0.45
    static let minClusterSize = 2

    /// 聚类：返回 (人物簇数组[每簇=输入下标列表], 噪声下标列表)。
    static func cluster(_ embeddings: [[Float]]) -> (clusters: [[Int]], noise: [Int]) {
        let n = embeddings.count
        if n == 0 { return ([], []) }
        if n == 1 { return minClusterSize <= 1 ? ([[0]], []) : ([], [0]) }

        let adj = buildKnnGraph(embeddings, k: knnK, minSim: minSimilarity)

        // 连通分量（迭代 DFS）
        var visited = [Bool](repeating: false, count: n)
        var components: [[Int]] = []
        for start in 0..<n {
            if visited[start] { continue }
            var comp: [Int] = []
            var stack = [start]
            visited[start] = true
            while let node = stack.popLast() {
                comp.append(node)
                for nb in adj[node] where !visited[nb] {
                    visited[nb] = true
                    stack.append(nb)
                }
            }
            components.append(comp)
        }

        var clusters: [[Int]] = []
        var noise: [Int] = []
        for c in components {
            if c.count >= minClusterSize { clusters.append(c) } else { noise.append(contentsOf: c) }
        }
        return (clusters, noise)
    }

    /// k-NN 无向图：每个节点保留最相似的 k 个邻居（且相似度 ≥ minSim），双向建边。
    private static func buildKnnGraph(_ emb: [[Float]], k: Int, minSim: Float) -> [[Int]] {
        let n = emb.count
        var adj = [[Int]](repeating: [], count: n)
        let effK = min(max(1, k), n - 1)
        for i in 0..<n {
            var cand: [(j: Int, sim: Float)] = []
            for j in 0..<n where j != i {
                let s = cosine(emb[i], emb[j])
                if s >= minSim { cand.append((j, s)) }
            }
            cand.sort { $0.sim > $1.sim }
            for t in cand.prefix(effK) {
                adj[i].append(t.j)
                adj[t.j].append(i) // 无向边
            }
        }
        return adj
    }

    /// 余弦相似度（embedding 已 L2 归一化时即 dot；此处仍算完整 cosine 以防未归一化输入）。
    static func cosine(_ a: [Float], _ b: [Float]) -> Float {
        guard a.count == b.count, !a.isEmpty else { return 0 }
        var dot: Float = 0, na: Float = 0, nb: Float = 0
        for i in 0..<a.count {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        let denom = (na.squareRoot()) * (nb.squareRoot())
        return denom == 0 ? 0 : dot / denom
    }
}
