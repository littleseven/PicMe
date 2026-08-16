import XCTest
@testable import PoLang

/// NIMA 预处理纯函数测试（无模型文件依赖）。
/// 覆盖：RGBA8→NHWC 归一化 (x-127.5)/127.5（通道序、长度、边界值）、
/// softmax 10-bin 期望分 Σpᵢ·(i+1) ∈ [1,10]。
final class NimaPreprocessTests: XCTestCase {

    private let epsilon: Float = 1e-5

    private func rgba(_ pixels: [(Int, Int, Int)]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(pixels.count * 4)
        for (r, g, b) in pixels {
            out.append(UInt8(r))
            out.append(UInt8(g))
            out.append(UInt8(b))
            out.append(255)
        }
        return out
    }

    // MARK: - preprocessPixels（RGBA8 → NHWC float）

    func testPreprocessNormalizationBounds() {
        // 纯黑 (0,0,0) → -1；纯白 (255,255,255) → +1（(255-127.5)/127.5 = 1）
        let black = NimaScorer.preprocessPixels(rgba([(0, 0, 0)]))
        XCTAssertEqual(black, [-1, -1, -1])

        let white = NimaScorer.preprocessPixels(rgba([(255, 255, 255)]))
        XCTAssertEqual(white, [1, 1, 1])

        // 127.5 恰为零点：127 → -0.5/127.5，128 → +0.5/127.5（期望值直接写公式，勿手算常量）
        let below = NimaScorer.preprocessPixels(rgba([(127, 127, 127)]))
        XCTAssertEqual(below[0], (127.0 - 127.5) / 127.5, accuracy: epsilon)
        let above = NimaScorer.preprocessPixels(rgba([(128, 128, 128)]))
        XCTAssertEqual(above[0], (128.0 - 127.5) / 127.5, accuracy: epsilon)
    }

    func testPreprocessChannelOrderIsRgbInterleaved() {
        // NHWC 交错语义：每像素 R,G,B 三连 float（Android 版为 ARGB IntArray，等价换载体）
        let out = NimaScorer.preprocessPixels(rgba([(255, 0, 128), (0, 255, 64)]))
        XCTAssertEqual(out.count, 6, "n 像素 → 3n float")
        XCTAssertEqual(out[0], 1, accuracy: epsilon)        // 像素0 R
        XCTAssertEqual(out[1], -1, accuracy: epsilon)       // 像素0 G
        XCTAssertEqual(out[2], 1.0 / 255, accuracy: epsilon) // 像素0 B：(128-127.5)/127.5
        XCTAssertEqual(out[3], -1, accuracy: epsilon)       // 像素1 R
        XCTAssertEqual(out[4], 1, accuracy: epsilon)        // 像素1 G
        XCTAssertEqual(out[5], (64.0 - 127.5) / 127.5, accuracy: epsilon) // 像素1 B
    }

    func testPreprocessEmptyBuffer() {
        XCTAssertEqual(NimaScorer.preprocessPixels([]), [])
        XCTAssertEqual(NimaScorer.preprocessPixels([255, 255, 255]), [],
                       "不完整像素（仅 3 字节）按 0 像素处理")
    }

    // MARK: - expectedScore（10-bin 期望分）

    func testExpectedScoreUniformDistribution() {
        let uniform = [Float](repeating: 0.1, count: 10)
        // Σ 0.1·(i+1) = 0.55·10 = 5.5（中位分）
        XCTAssertEqual(NimaScorer.expectedScore(uniform), 5.5, accuracy: epsilon)
    }

    func testExpectedScoreOneHot() {
        var low = [Float](repeating: 0, count: 10)
        low[0] = 1
        XCTAssertEqual(NimaScorer.expectedScore(low), 1.0, accuracy: epsilon, "全概率在 bin0 → 1 分下界")

        var high = [Float](repeating: 0, count: 10)
        high[9] = 1
        XCTAssertEqual(NimaScorer.expectedScore(high), 10.0, accuracy: epsilon, "全概率在 bin9 → 10 分上界")
    }

    func testExpectedScoreWeighted() {
        // p = [0.5, 0.5, 0, ...] → 1·0.5 + 2·0.5 = 1.5
        var dist = [Float](repeating: 0, count: 10)
        dist[0] = 0.5
        dist[1] = 0.5
        XCTAssertEqual(NimaScorer.expectedScore(dist), 1.5, accuracy: epsilon)

        // p = [0, 0.25, 0.75, 0, ...] → 2·0.25 + 3·0.75 = 2.75
        var mixed = [Float](repeating: 0, count: 10)
        mixed[1] = 0.25
        mixed[2] = 0.75
        XCTAssertEqual(NimaScorer.expectedScore(mixed), 2.75, accuracy: epsilon)
    }

    func testExpectedScoreEmpty() {
        XCTAssertEqual(NimaScorer.expectedScore([]), 0, accuracy: epsilon)
    }
}
