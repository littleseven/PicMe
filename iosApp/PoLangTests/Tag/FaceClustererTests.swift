import XCTest
@testable import PoLang

final class FaceClustererTests: XCTestCase {

    // 4 维向量，构造两组高内聚 + 1 噪声
    // A 组：集中在 (x,y,0,0)；B 组：集中在 (0,0,x,y)；N：均匀 → 与两组 cosine≈0.5 < 0.65
    private var groupA: [[Float]] {
        [[1, 0, 0, 0], [0.9, 0.435, 0, 0], [0.95, 0.31, 0, 0]]
    }
    private var groupB: [[Float]] {
        [[0, 0, 1, 0], [0, 0, 0.9, 0.435], [0, 0, 0.95, 0.31]]
    }
    private var noise: [[Float]] {
        [[0, 1, 0, 0]]  // 与 A/B 近似正交（cosine < 0.65）→ 真噪声；此前 [0.5,0.5,0.5,0.5] 会桥接两簇
    }

    func testTwoClustersPlusNoise() {
        // 顺序：A(0,1,2) B(3,4,5) N(6)
        let emb = groupA + groupB + noise
        let (clusters, noiseIdx) = FaceClusterer.cluster(emb)
        XCTAssertEqual(clusters.count, 2, "应聚成 2 个人物簇")
        XCTAssertEqual(noiseIdx, [6], "第 7 个（下标 6）应为噪声")
        // 每簇大小 3
        for c in clusters { XCTAssertEqual(c.count, 3) }
        // A、B 各成一簇（不混）
        let setA = Set(clusters.first { $0.contains(0) } ?? [])
        let setB = Set(clusters.first { $0.contains(3) } ?? [])
        XCTAssertEqual(setA.sorted(), [0, 1, 2])
        XCTAssertEqual(setB.sorted(), [3, 4, 5])
    }

    func testSingleSampleIsNoise() {
        let (clusters, noise) = FaceClusterer.cluster([[1, 0, 0, 0]])
        XCTAssertTrue(clusters.isEmpty)
        XCTAssertEqual(noise, [0])
    }

    func testEmpty() {
        let (clusters, noise) = FaceClusterer.cluster([])
        XCTAssertTrue(clusters.isEmpty)
        XCTAssertTrue(noise.isEmpty)
    }

    func testAllSimilarIsOneCluster() {
        // 三个互相高相似（几乎同向）→ 1 簇，无噪声
        let emb: [[Float]] = [[1, 0, 0, 0], [0.99, 0.01, 0, 0], [0.98, 0.02, 0, 0]]
        let (clusters, noise) = FaceClusterer.cluster(emb)
        XCTAssertEqual(clusters.count, 1)
        XCTAssertTrue(noise.isEmpty)
        XCTAssertEqual(clusters[0].count, 3)
    }

    func testCosine() {
        XCTAssertEqual(FaceClusterer.cosine([1, 0], [1, 0]), 1.0, accuracy: 1e-5)
        XCTAssertEqual(FaceClusterer.cosine([1, 0], [0, 1]), 0.0, accuracy: 1e-5)
        XCTAssertEqual(FaceClusterer.cosine([1, 0], [-1, 0]), -1.0, accuracy: 1e-5)
    }
}
