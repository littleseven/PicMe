import XCTest
@testable import PoLang

/// 抽卡候选采样器测试（移植自 Android CandidateSampler JVM 单测语义）。
/// 覆盖：确定性随机源注入、首卡 base 锚点、方向池命中、±30% 抖动边界、
/// 指纹去重（换一组）、exclude 生效、人像美颜抖动 ±10/±8、形变维度不扰动。
final class CandidateSamplerTests: XCTestCase {

    /// SplitMix64 确定性随机源（注入 stdlib RandomNumberGenerator 协议）。
    private final class SeededGenerator: RandomNumberGenerator {
        private var state: UInt64
        init(seed: UInt64) { state = seed }
        func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
    }

    private let epsilon: Float = 1e-4

    /// 对齐 Android assets SELFIE 预设（beauty enabled，多维度非零）。
    private func selfieBase() -> OptimizePreset {
        OptimizePreset(
            scene: OptimizeScene.selfie.rawValue,
            beauty: BeautyPreset(enabled: true, smoothing: 35, whitening: 25,
                                 slimFace: 10, bigEyes: 15, lipColor: 25, blush: 10),
            filter: FilterPreset(colorFilter: "NONE", styleFilter: "NONE"),
            adjustment: AdjustmentPreset(brightness: 5, exposure: 0, contrast: 52,
                                         saturation: 102, temperature: 5200, tint: 2)
        )
    }

    /// 对齐 Android assets FOOD 预设（beauty disabled）。
    private func foodBase() -> OptimizePreset {
        OptimizePreset(
            scene: OptimizeScene.food.rawValue,
            beauty: BeautyPreset(enabled: false),
            filter: FilterPreset(colorFilter: "LEICA_VIBRANT", styleFilter: "NONE"),
            adjustment: AdjustmentPreset(brightness: 2, exposure: 0, contrast: 55,
                                         saturation: 110, temperature: 5400, tint: 3)
        )
    }

    private func template(named name: String, scene: OptimizeScene) throws -> CandidateSampler.DirectionTemplate {
        try XCTUnwrap(CandidateSampler.directionPool(scene).first { tpl in tpl.name == name },
                      "template \(name) not in \(scene.rawValue) pool")
    }

    // MARK: - 锚点与确定性

    func testFirstCardIsBaseAnchor() {
        let sampler = CandidateSampler(random: SeededGenerator(seed: 42))
        let candidates = sampler.sample(base: selfieBase(), scene: .selfie)
        XCTAssertEqual(candidates.first?.index, 0)
        XCTAssertEqual(candidates.first?.direction, "base")
        XCTAssertEqual(candidates.first?.preset, selfieBase(), "锚点卡必须原样使用 base preset")
        XCTAssertEqual(candidates.count, CandidateSampler.defaultCount)
    }

    func testSameSeedProducesIdenticalCandidates() {
        let a = CandidateSampler(random: SeededGenerator(seed: 7)).sample(base: foodBase(), scene: .food)
        let b = CandidateSampler(random: SeededGenerator(seed: 7)).sample(base: foodBase(), scene: .food)
        XCTAssertEqual(a, b, "同 seed 注入必须产出完全一致的候选序列（可测确定性）")
        XCTAssertEqual(a.map { candidate in candidate.index }, Array(0..<a.count), "index 必须连续编号")
    }

    // MARK: - 方向池命中

    func testDirectionsComeFromScenePool() throws {
        for seed: UInt64 in 0..<20 {
            let sampler = CandidateSampler(random: SeededGenerator(seed: seed))
            let candidates = sampler.sample(base: foodBase(), scene: .food)
            let poolNames = Set(CandidateSampler.directionPool(.food).map { tpl in tpl.name })
            for candidate in candidates.dropFirst() {
                XCTAssertTrue(poolNames.contains(candidate.direction),
                              "seed=\(seed) 卡 #\(candidate.index) 方向 \(candidate.direction) 不在 FOOD 方向池")
            }
        }
    }

    func testDocumentPoolSmallerThanCountStillProducesCards() {
        // DOCUMENT 池仅 3 方向，count=4 依赖抖动差异补位；MAX_RETRY=20 内应产出 4 张不重复卡
        let sampler = CandidateSampler(random: SeededGenerator(seed: 99))
        let candidates = sampler.sample(base: OptimizePreset(scene: OptimizeScene.document.rawValue),
                                        scene: .document,
                                        count: CandidateSampler.maxRetry + 4)
        // 语义验证点：结果可能短于 count（Android 同——方向空间耗尽），但指纹互不重复
        let fps = candidates.map { candidate in CandidateSampler.fingerprint(candidate.preset) }
        XCTAssertEqual(Set(fps).count, fps.count, "候选指纹必须互不重复")
        XCTAssertGreaterThanOrEqual(candidates.count, CandidateSampler.defaultCount,
                                     "常规 count=4 量级下不应耗尽")
    }

    // MARK: - 抖动边界

    func testJitterBoundsWithinPlusMinus30Percent() throws {
        let base = foodBase()
        for seed: UInt64 in 0..<50 {
            let sampler = CandidateSampler(random: SeededGenerator(seed: seed))
            for candidate in sampler.sample(base: base, scene: .food).dropFirst() {
                let tpl = try template(named: candidate.direction, scene: .food)
                let a = candidate.preset.adjustment
                // 非 0 delta 维度：value ∈ [base+0.7δ, base+1.3δ]（clamp 只会向内收）
                for (value, baseVal, delta) in [
                    (a.brightness, base.adjustment.brightness, tpl.brightness),
                    (a.exposure, base.adjustment.exposure, tpl.exposure),
                    (a.contrast, base.adjustment.contrast, tpl.contrast),
                    (a.saturation, base.adjustment.saturation, tpl.saturation),
                    (a.temperature, base.adjustment.temperature, tpl.temperature),
                    (a.tint, base.adjustment.tint, tpl.tint),
                ] {
                    if delta == 0 {
                        XCTAssertEqual(value, baseVal, accuracy: epsilon,
                                       "零 delta 维度不得抖动（direction=\(candidate.direction)）")
                    } else {
                        let lower = min(baseVal + delta * 0.7, baseVal + delta * 1.3) - epsilon
                        let upper = max(baseVal + delta * 0.7, baseVal + delta * 1.3) + epsilon
                        XCTAssertTrue(value >= lower && value <= upper,
                                      "seed=\(seed) direction=\(candidate.direction) value=\(value) 越界 [\(lower), \(upper)]")
                    }
                }
            }
        }
    }

    func testPortraitBeautyJitterBoundsAndShapeDimensionsUntouched() {
        let base = selfieBase()
        for seed: UInt64 in 0..<50 {
            let sampler = CandidateSampler(random: SeededGenerator(seed: seed))
            for candidate in sampler.sample(base: base, scene: .selfie).dropFirst() {
                let beauty = candidate.preset.beauty
                XCTAssertTrue(abs(beauty.smoothing - base.beauty.smoothing) <=
                              CandidateSampler.beautyJitterSmoothing + epsilon,
                              "smoothing 抖动不得超出 ±10（actual=\(beauty.smoothing)）")
                XCTAssertTrue(abs(beauty.whitening - base.beauty.whitening) <=
                              CandidateSampler.beautyJitterWhitening + epsilon,
                              "whitening 抖动不得超出 ±8（actual=\(beauty.whitening)）")
                // 形变维度不扰动（512px 小图关键点不可靠，spec §4）
                XCTAssertEqual(beauty.slimFace, base.beauty.slimFace)
                XCTAssertEqual(beauty.bigEyes, base.beauty.bigEyes)
                XCTAssertEqual(beauty.lipColor, base.beauty.lipColor)
                XCTAssertEqual(beauty.blush, base.beauty.blush)
                XCTAssertEqual(candidate.preset.filter, base.filter, "滤镜维度不参与抖动")
            }
        }
    }

    func testNonPortraitSceneSkipsBeautyJitterEvenWhenEnabled() {
        // FOOD 非人像场景：即使 beauty.enabled=true 也不抖动（beautyJitter 仅限人像场景）
        var base = foodBase()
        base.beauty.enabled = true
        base.beauty.smoothing = 40
        base.beauty.whitening = 30
        for seed: UInt64 in 0..<20 {
            let sampler = CandidateSampler(random: SeededGenerator(seed: seed))
            for candidate in sampler.sample(base: base, scene: .food).dropFirst() {
                XCTAssertEqual(candidate.preset.beauty.smoothing, 40)
                XCTAssertEqual(candidate.preset.beauty.whitening, 30)
            }
        }
    }

    // MARK: - 指纹

    func testFingerprintQuantizationMatchesKotlinRounding() {
        var preset = OptimizePreset(scene: OptimizeScene.general.rawValue)
        preset.adjustment = AdjustmentPreset(brightness: 0, exposure: 0, contrast: 50,
                                             saturation: 100, temperature: 5000, tint: 0)
        XCTAssertEqual(CandidateSampler.fingerprint(preset), "0|0|50|100|100|0|0|0|NONE|NONE")
        // temperature 以 50 为栅格：5249/50=104.98 → 105
        preset.adjustment.temperature = 5249
        XCTAssertEqual(CandidateSampler.fingerprint(preset), "0|0|50|100|105|0|0|0|NONE|NONE")
        // Kotlin roundToInt 半正上取整：-1.5 → -1（Swift .rounded() 为 -2，指纹需对齐 Kotlin）
        preset.adjustment.brightness = -1.5
        preset.adjustment.temperature = 5000
        XCTAssertEqual(CandidateSampler.fingerprint(preset), "-1|0|50|100|100|0|0|0|NONE|NONE")
    }

    func testExcludeDeduplicatesAcrossDraws() {
        let first = CandidateSampler(random: SeededGenerator(seed: 11)).sample(base: foodBase(), scene: .food)
        let exclude = Set(first.map { candidate in CandidateSampler.fingerprint(candidate.preset) })

        let second = CandidateSampler(random: SeededGenerator(seed: 12)).sample(base: foodBase(),
                                                                               scene: .food,
                                                                               exclude: exclude)
        XCTAssertEqual(second.first?.direction, "base", "锚点卡不受排除约束")
        for candidate in second.dropFirst() {
            XCTAssertFalse(exclude.contains(CandidateSampler.fingerprint(candidate.preset)),
                           "换一组不得出现已排除指纹")
        }
    }

    func testExcludeNeverSuppressesAnchorCard() {
        let base = foodBase()
        let exclude: Set<String> = [CandidateSampler.fingerprint(base)]
        let candidates = CandidateSampler(random: SeededGenerator(seed: 3))
            .sample(base: base, scene: .food, exclude: exclude)
        XCTAssertEqual(candidates.first?.direction, "base")
        XCTAssertEqual(candidates.first?.preset, base)
    }
}
