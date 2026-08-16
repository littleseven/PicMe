import XCTest
import UIKit
@testable import PoLang

/// 抽卡评分器测试（mock AestheticScorer，纯逻辑无模型文件依赖）。
/// 覆盖：scoreCandidate 先护栏后 NIMA（淘汰卡不打分、nima_failed）、
/// select 三分支（有效卡<2→unavailable；best 未超 original+0.05→keepOriginal；超过→selected）、
/// originalScore nil 跳守卫直选、并列最高取首张（Kotlin maxBy 语义）。
final class OptimizeScorerTests: XCTestCase {

    /// 注入式 mock：按调用次序回放预设分值。
    private final class MockScorer: AestheticScorer {
        var queue: [Float?] = []
        private(set) var callCount = 0

        func initialize() async -> Bool { true }

        func score(_ image: UIImage) -> Float? {
            callCount += 1
            if queue.isEmpty { return nil }
            return queue.removeFirst()
        }

        func release() {}
    }

    private let epsilon: Float = 1e-4

    // MARK: - 工具

    private func solidRgba(_ count: Int, _ rgb: (Int, Int, Int)) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(count * 4)
        for _ in 0..<count {
            out.append(UInt8(rgb.0))
            out.append(UInt8(rgb.1))
            out.append(UInt8(rgb.2))
            out.append(255)
        }
        return out
    }

    private func candidate(_ index: Int, direction: String = "clarity") -> OptimizeCandidate {
        OptimizeCandidate(index: index,
                          direction: direction,
                          preset: OptimizePreset(scene: OptimizeScene.general.rawValue))
    }

    private func scored(_ index: Int, score: Float?, rejected: Bool = false) -> ScoredCandidate {
        ScoredCandidate(candidate: candidate(index), nimaScore: score, rejected: rejected)
    }

    // MARK: - scoreCandidate

    func testScoreCandidateGuardrailRejectSkipsNima() throws {
        let mock = MockScorer()
        mock.queue = [7.5]
        let scorer = OptimizeScorer(scorer: mock)

        let whitePx = solidRgba(8, (255, 255, 255))
        let result = scorer.scoreCandidate(candidate: candidate(0),
                                           rendered: try XCTUnwrap(UIImage.solid(rgb: (255, 255, 255), size: 2)),
                                           renderedPx: whitePx,
                                           originalMeanLuminance: 0.5,
                                           originalClipRatio: 0)
        XCTAssertTrue(result.rejected)
        XCTAssertNil(result.nimaScore, "护栏淘汰卡不打分")
        XCTAssertTrue(result.rejectReason?.hasPrefix("highlight_clip:") == true)
        XCTAssertEqual(mock.callCount, 0, "NIMA 未被调用")
        XCTAssertNotNil(result.thumbnail, "淘汰卡仍带回缩略图（对比条展示）")
    }

    func testScoreCandidateNimaFailMarksRejected() throws {
        let mock = MockScorer()
        mock.queue = [nil]
        let scorer = OptimizeScorer(scorer: mock)

        let grayPx = solidRgba(8, (128, 128, 128))
        let result = scorer.scoreCandidate(candidate: candidate(1),
                                           rendered: try XCTUnwrap(UIImage.solid(rgb: (128, 128, 128), size: 2)),
                                           renderedPx: grayPx,
                                           originalMeanLuminance: Guardrails.meanLuminance(grayPx),
                                           originalClipRatio: 0)
        XCTAssertTrue(result.rejected)
        XCTAssertNil(result.nimaScore)
        XCTAssertEqual(result.rejectReason, "nima_failed")
        XCTAssertEqual(mock.callCount, 1)
    }

    func testScoreCandidateSuccess() throws {
        let mock = MockScorer()
        mock.queue = [6.25]
        let scorer = OptimizeScorer(scorer: mock)

        let grayPx = solidRgba(8, (128, 128, 128))
        let result = scorer.scoreCandidate(candidate: candidate(2),
                                           rendered: try XCTUnwrap(UIImage.solid(rgb: (128, 128, 128), size: 2)),
                                           renderedPx: grayPx,
                                           originalMeanLuminance: Guardrails.meanLuminance(grayPx),
                                           originalClipRatio: 0)
        XCTAssertFalse(result.rejected)
        XCTAssertEqual(result.nimaScore ?? 0, 6.25, accuracy: epsilon)
        XCTAssertNil(result.rejectReason)
        XCTAssertEqual(mock.callCount, 1)
    }

    // MARK: - select 三分支

    func testSelectUnavailableWhenFewerThanTwoValidCards() {
        let scorer = OptimizeScorer(scorer: MockScorer())
        // 仅 1 张有效卡
        guard case .unavailable = scorer.select(all: [scored(0, score: 5.0)], originalScore: 4.0) else {
            return XCTFail("有效卡 < 2 应返回 unavailable")
        }
        // 有效卡定义：未淘汰且有分——护栏淘汰卡不算
        guard case .unavailable = scorer.select(all: [scored(0, score: nil, rejected: true),
                                                      scored(1, score: 5.0)], originalScore: 4.0) else {
            return XCTFail("1 有效 + 1 淘汰仍不满足 MIN_VALID_CARDS=2")
        }
    }

    func testSelectKeepOriginalWhenBestDoesNotExceedImprovement() {
        let scorer = OptimizeScorer(scorer: MockScorer())
        // best 5.5，original 5.5：5.5 <= 5.5+0.05 → KeepOriginal
        guard case .keepOriginal(let all, let originalScore) =
            scorer.select(all: [scored(0, score: 5.0), scored(1, score: 5.5)], originalScore: 5.5) else {
            return XCTFail("best 未显著优于原图应返回 keepOriginal")
        }
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(originalScore ?? 0, 5.5, accuracy: epsilon)

        // 边界（浮点精确算术）：best == original+MIN_IMPROVEMENT → 仍 KeepOriginal（<= 语义）。
        // 取 original=0、best=0.05：0.0 + 0.05f 与字面量 0.05f 逐位相等（5.0+0.05f 会因
        // 加法舍入不等于 5.05f，边界断言须用精确可表示值）。
        guard case .keepOriginal = scorer.select(all: [scored(0, score: 0.0),
                                                       scored(1, score: 0.05)],
                                                 originalScore: 0.0) else {
            return XCTFail("best == original+MIN_IMPROVEMENT 应返回 keepOriginal（<= 语义）")
        }
    }

    func testSelectSelectedWhenBestExceedsImprovement() {
        let scorer = OptimizeScorer(scorer: MockScorer())
        guard case .selected(let best, let all, let originalScore) =
            scorer.select(all: [scored(0, score: 5.0), scored(1, score: 6.0), scored(2, score: 4.0)],
                          originalScore: 5.0) else {
            return XCTFail("best 6.0 > 5.0+0.05 应返回 selected")
        }
        XCTAssertEqual(best.candidate.index, 1)
        XCTAssertEqual(all.count, 3)
        XCTAssertEqual(originalScore ?? 0, 5.0, accuracy: epsilon)
    }

    func testSelectNilOriginalScoreSkipsGuard() {
        let scorer = OptimizeScorer(scorer: MockScorer())
        // 原图分不可用：即使 best=5.0 无任何提升也直接选优（spec §9）
        guard case .selected(let best, _, let originalScore) =
            scorer.select(all: [scored(0, score: 5.0), scored(1, score: 4.0)], originalScore: nil) else {
            return XCTFail("originalScore=nil 应跳过退化守卫直接选优")
        }
        XCTAssertEqual(best.candidate.index, 0)
        XCTAssertNil(originalScore)
    }

    func testSelectTieReturnsFirstMax() {
        let scorer = OptimizeScorer(scorer: MockScorer())
        // 并列最高 6.0：须取首张（对齐 Kotlin maxBy；Swift max(by:) 无此保证）
        guard case .selected(let best, _, _) =
            scorer.select(all: [scored(0, score: 6.0), scored(1, score: 6.0), scored(2, score: 6.0)],
                          originalScore: nil) else {
            return XCTFail("并列最高也应 selected")
        }
        XCTAssertEqual(best.candidate.index, 0)
    }
}

// MARK: - 测试工具：纯色 UIImage（CGContext 直出，无 UIGraphicsImageRenderer 宿主依赖）

private extension UIImage {

    /// 生成纯色方图；CGContext/makeImage 失败返回 nil（实践中不会发生，由 XCTUnwrap 暴露）。
    static func solid(rgb: (Int, Int, Int), size: Int) -> UIImage? {
        guard let ctx = CGContext(data: nil,
                                  width: size,
                                  height: size,
                                  bitsPerComponent: 8,
                                  bytesPerRow: size * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.setFillColor(red: CGFloat(rgb.0) / 255,
                          green: CGFloat(rgb.1) / 255,
                          blue: CGFloat(rgb.2) / 255,
                          alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
        guard let cg = ctx.makeImage() else { return nil }
        return UIImage(cgImage: cg)
    }
}
