import XCTest
@testable import PoLang

/// 技术护栏测试（合成 RGBA8 像素缓冲，纯逻辑无设备依赖）。
/// 覆盖：高光裁剪率（步长采样语义 + 250 阈值）、Rec.601 平均亮度（RGBA 字节序）、
/// check 的增量判定（≥5pp 淘汰）与亮度漂移判定（>15% 淘汰、原图亮度 0 跳过）。
final class GuardrailsTests: XCTestCase {

    private let epsilon: Float = 1e-4

    /// 像素列表（r, g, b）→ RGBA8 缓冲（alpha 恒 255，不参与统计）。
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

    private func uniform(_ count: Int, _ rgb: (Int, Int, Int)) -> [UInt8] {
        rgba(Array(repeating: rgb, count: count))
    }

    // MARK: - highlightClipRatio

    func testHighlightClipRatioStepSampling() {
        // 8 像素，step=4 只采样第 0/4 个像素 → 白光占比按采样点而非全量计
        var pixels = Array(repeating: (100, 100, 100), count: 8)
        pixels[0] = (255, 255, 255)
        XCTAssertEqual(Guardrails.highlightClipRatio(rgba(pixels)), 0.5, accuracy: epsilon)

        // 白像素在非采样点（index 1）不计入
        var offGrid = Array(repeating: (100, 100, 100), count: 8)
        offGrid[1] = (255, 255, 255)
        XCTAssertEqual(Guardrails.highlightClipRatio(rgba(offGrid)), 0, accuracy: epsilon)
    }

    func testHighlightClipThreshold250() {
        // 阈值语义对齐 Android：r/g/b 全部 >= 250 才算裁剪
        XCTAssertEqual(Guardrails.highlightClipRatio(uniform(4, (250, 250, 250)), step: 1),
                       1.0, accuracy: epsilon)
        XCTAssertEqual(Guardrails.highlightClipRatio(uniform(4, (249, 255, 255)), step: 1),
                       0, accuracy: epsilon)
        XCTAssertEqual(Guardrails.highlightClipRatio(uniform(4, (255, 249, 255)), step: 1),
                       0, accuracy: epsilon)
    }

    func testEmptyBufferReturnsZero() {
        XCTAssertEqual(Guardrails.highlightClipRatio([]), 0)
        XCTAssertEqual(Guardrails.meanLuminance([]), 0)
        // 仅 alpha（不完整像素）同样按 0 处理
        XCTAssertEqual(Guardrails.highlightClipRatio([255, 255, 255]), 0)
    }

    // MARK: - meanLuminance

    func testMeanLuminanceRec601Weights() {
        // 灰 128：Rec.601 权重和为 1 → 128/255
        XCTAssertEqual(Guardrails.meanLuminance(uniform(4, (128, 128, 128)), step: 1),
                       128.0 / 255.0, accuracy: epsilon)
        // RGBA 字节序验证：纯红仅计 R 权重 0.299（Android IntArray 为 ARGB，iOS 为 RGBA——通道位序由测试锚定）
        XCTAssertEqual(Guardrails.meanLuminance(uniform(4, (255, 0, 0)), step: 1),
                       0.299, accuracy: epsilon)
        XCTAssertEqual(Guardrails.meanLuminance(uniform(4, (0, 255, 0)), step: 1),
                       0.587, accuracy: epsilon)
        XCTAssertEqual(Guardrails.meanLuminance(uniform(4, (0, 0, 255)), step: 1),
                       0.114, accuracy: epsilon)
    }

    // MARK: - check（增量判定 + 漂移判定）

    func testCheckRejectsHighlightClipDeltaOverLimit() {
        let candidate = uniform(8, (255, 255, 255)) // clip = 1.0，白图亮度 1.0
        let reason = Guardrails.check(candidatePx: candidate,
                                      originalMeanLuminance: Guardrails.meanLuminance(candidate),
                                      originalClipRatio: 0)
        XCTAssertNotNil(reason)
        XCTAssertTrue(reason?.hasPrefix("highlight_clip:") == true, "原因串格式对齐 Android：\(reason ?? "")")
    }

    func testCheckAllowsClipDeltaWithinLimit() {
        // 混合缓冲（部分白光 + 灰）；原图 clip 基线取「同分布 clip - 3pp」→ 增量恰 3pp ≤ 5pp 通过。
        // 基线经同一函数导出，规避 step 采样网格下手算占比的偏差。
        var pixels = Array(repeating: (150, 150, 150), count: 100)
        for i in 0..<53 { pixels[i] = (255, 255, 255) }
        let buffer = rgba(pixels)
        let clip = Guardrails.highlightClipRatio(buffer)
        XCTAssertGreaterThan(clip, 0, "前置：缓冲确含白光采样点")
        let reason = Guardrails.check(candidatePx: buffer,
                                      originalMeanLuminance: Guardrails.meanLuminance(buffer),
                                      originalClipRatio: clip - 0.03)
        XCTAssertNil(reason, "增量 3pp 在 5pp 限内应通过")
    }

    func testCheckClipDeltaExactlyAtLimitPasses() {
        // 边界：增量恰为 0.05 → Android 判定为 `>` 严格大于才淘汰。
        // 构造 80 像素（step4 → 恰 20 采样点），仅采样点 0 白 → clip = 1/20 = 0.05（与
        // Float 字面量 0.05 同一最近浮点表示，规避 clip-limit 的舍入噪声）。
        var pixels = Array(repeating: (150, 150, 150), count: 80)
        pixels[0] = (255, 255, 255)
        let buffer = rgba(pixels)
        XCTAssertEqual(Guardrails.highlightClipRatio(buffer), 0.05, accuracy: 1e-6)
        let reason = Guardrails.check(candidatePx: buffer,
                                      originalMeanLuminance: Guardrails.meanLuminance(buffer),
                                      originalClipRatio: 0)
        XCTAssertNil(reason, "delta == limit（5pp）不淘汰（严格大于语义）")
    }

    func testCheckRejectsLuminanceDriftOverLimit() {
        let candidate = uniform(8, (128, 128, 128)) // lum ≈ 0.502
        let reason = Guardrails.check(candidatePx: candidate,
                                      originalMeanLuminance: 0.8,
                                      originalClipRatio: 0)
        XCTAssertNotNil(reason)
        XCTAssertTrue(reason?.hasPrefix("luminance_drift:") == true, "原因串格式对齐 Android：\(reason ?? "")")
    }

    func testCheckAllowsLuminanceDriftWithinLimit() {
        let candidate = uniform(8, (128, 128, 128))
        let reason = Guardrails.check(candidatePx: candidate,
                                      originalMeanLuminance: 0.55, // |0.502-0.55|/0.55 ≈ 8.7% < 15%
                                      originalClipRatio: 0)
        XCTAssertNil(reason)
    }

    func testCheckSkipsDriftWhenOriginalLuminanceZero() {
        // 原图亮度为 0（黑图/空统计）→ 跳过漂移守卫（对齐 Android originalMeanLuminance > 0f 前置）
        let candidate = uniform(8, (200, 200, 200))
        let reason = Guardrails.check(candidatePx: candidate,
                                      originalMeanLuminance: 0,
                                      originalClipRatio: 0)
        XCTAssertNil(reason)
    }
}
