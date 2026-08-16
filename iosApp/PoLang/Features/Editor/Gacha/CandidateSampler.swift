import Foundation

// MARK: - CandidateSampler（抽卡候选采样器）
//
// 移植自 androidApp `domain/agent/capability/optimize/gacha/CandidateSampler.kt`（纯逻辑 1:1 直译）。
//
// 以 base preset 为锚点生成 `defaultCount` 个差异化候选：
// - 卡 0：base 原样（锚点）
// - 其余：从场景方向池随机取方向模板，叠加 ±30% seed 抖动；
//   人像类场景（SELFIE/PORTRAIT/GROUP）额外叠加 smoothing/whitening 小幅抖动。
//
// 扰动只动调色维度 + smoothing/whitening；形变维度（slimFace/bigEyes 等）保持 base 值，
// 因为形变依赖人脸关键点，512px 候选小图上不可靠（见 spec §4）。
//
// 随机源偏差（相对 Android）：Kotlin 注入 `kotlin.random.Random`；Swift 注入 stdlib
// `RandomNumberGenerator` 协议（默认 SystemRandomNumberGenerator），测试可注入确定性序列。
// 随机数消费顺序与 Android 一致（先方向选择，再按 brightness/exposure/contrast/saturation/
// temperature/tint 顺序 jitter——零 delta 维度不消耗随机数，最后 beauty 抖动 smoothing→whitening）。
final class CandidateSampler {

    /// 方向模板：叠加到 base 上的调色偏移量。
    struct DirectionTemplate: Equatable {
        let name: String
        var brightness: Float = 0
        var exposure: Float = 0
        var contrast: Float = 0
        var saturation: Float = 0
        var temperature: Float = 0
        var tint: Float = 0

        init(name: String,
             brightness: Float = 0,
             exposure: Float = 0,
             contrast: Float = 0,
             saturation: Float = 0,
             temperature: Float = 0,
             tint: Float = 0) {
            self.name = name
            self.brightness = brightness
            self.exposure = exposure
            self.contrast = contrast
            self.saturation = saturation
            self.temperature = temperature
            self.tint = tint
        }
    }

    // MARK: - 常量（对齐 Android companion object；internal 供单测校准断言）

    static let defaultCount = 4
    static let maxRetry = 20
    static let jitterRatio: Float = 0.3
    static let beautyJitterSmoothing: Float = 10
    static let beautyJitterWhitening: Float = 8

    static let clarity = DirectionTemplate(name: "clarity", contrast: 8, saturation: 6)
    static let vivid = DirectionTemplate(name: "vivid", contrast: 5, saturation: 10)
    static let warm = DirectionTemplate(name: "warm", temperature: 400, tint: 3)
    static let cool = DirectionTemplate(name: "cool", brightness: 5, temperature: -400)
    static let brighten = DirectionTemplate(name: "brighten", brightness: 6, exposure: 3)
    static let crisp = DirectionTemplate(name: "crisp", brightness: 4, contrast: 12)

    static let portraitScenes: Set<OptimizeScene> = [.selfie, .portrait, .group]

    /// 场景 → 方向模板池（对齐 Android directionPool）。
    static func directionPool(_ scene: OptimizeScene) -> [DirectionTemplate] {
        switch scene {
        case .food, .landscape:
            return [clarity, vivid, warm, cool]
        case .selfie, .portrait, .group:
            return [warm, cool, clarity, brighten]
        case .lowLight:
            return [brighten, warm, clarity, crisp]
        case .document:
            return [clarity, brighten, crisp]
        case .general:
            return [clarity, warm, cool, brighten]
        }
    }

    /// 参数量化到整数栅格后的指纹，用于「换一组」去重。
    /// 10 元组：brightness|exposure|contrast|saturation|temperature/50|tint|smoothing|whitening|colorFilter|styleFilter。
    static func fingerprint(_ preset: OptimizePreset) -> String {
        let a = preset.adjustment
        let b = preset.beauty
        let parts: [String] = [
            String(kotlinRoundInt(a.brightness)),
            String(kotlinRoundInt(a.exposure)),
            String(kotlinRoundInt(a.contrast)),
            String(kotlinRoundInt(a.saturation)),
            String(kotlinRoundInt(a.temperature / 50)),
            String(kotlinRoundInt(a.tint)),
            String(kotlinRoundInt(b.smoothing)),
            String(kotlinRoundInt(b.whitening)),
            preset.filter.colorFilter,
            preset.filter.styleFilter,
        ]
        return parts.joined(separator: "|")
    }

    /// Kotlin `Float.roundToInt()` = floor(x + 0.5)（half-up；Swift `.rounded()` 为
    /// half-away-from-zero，负半轴差 1，指纹量化需逐位对齐）。
    static func kotlinRoundInt(_ x: Float) -> Int {
        Int((x + 0.5).rounded(.down))
    }

    // MARK: - 随机源

    private var rng: RandomNumberGenerator

    init(random rng: RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.rng = rng
    }

    /// Kotlin `Random.nextFloat()`：24-bit 精度均匀 [0, 1)。
    private func nextFloat() -> Float {
        Float(rng.next() >> 40) / Float(1 << 24)
    }

    /// Kotlin `Random.nextInt(bound)`：均匀 [0, bound)，拒绝采样消除模偏差。
    private func nextInt(upperBound: Int) -> Int {
        precondition(upperBound > 0, "upperBound must be positive")
        let bound = UInt64(upperBound)
        let limit = UInt64.max - UInt64.max % bound
        while true {
            let r = rng.next()
            if r < limit { return Int(r % bound) }
        }
    }

    // MARK: - 采样

    /// 生成候选卡。
    ///
    /// - Parameters:
    ///   - base: 锚点 preset（卡 0 原样使用）
    ///   - scene: 当前场景（决定方向池与是否叠加美颜抖动）
    ///   - count: 候选总数（含锚点卡）
    ///   - exclude: 已出现过的 fingerprint 集合（「换一组」去重）；锚点卡不受排除约束
    /// - Returns: 候选卡列表，首张恒为 base 锚点卡（index 0、direction "base"）。
    ///   当方向空间耗尽（exclude 过大或方向池过小）且达到 `maxRetry` 上限时，
    ///   返回数量可能少于 count，调用方需处理短结果。
    func sample(base: OptimizePreset,
                scene: OptimizeScene,
                count: Int = CandidateSampler.defaultCount,
                exclude: Set<String> = []) -> [OptimizeCandidate] {
        var result = [OptimizeCandidate(index: 0, direction: "base", preset: base)]
        var seen = exclude
        seen.insert(CandidateSampler.fingerprint(base))

        let pool = CandidateSampler.directionPool(scene)
        var retry = 0
        while result.count < count && retry < CandidateSampler.maxRetry {
            retry += 1
            let template = pool[nextInt(upperBound: pool.count)]
            let jittered = applyTemplate(base, template, beautyJitter: CandidateSampler.portraitScenes.contains(scene))
            let fp = CandidateSampler.fingerprint(jittered)
            if seen.contains(fp) { continue }
            seen.insert(fp)
            result.append(OptimizeCandidate(index: result.count, direction: template.name, preset: jittered))
        }
        return result
    }

    /// 叠加方向模板 + 抖动。随机数消耗顺序对齐 Android（见类注释）。
    private func applyTemplate(_ base: OptimizePreset,
                               _ t: DirectionTemplate,
                               beautyJitter: Bool) -> OptimizePreset {
        func jitter(_ delta: Float) -> Float {
            if delta == 0 { return 0 }
            return delta * (1 + (nextFloat() * 2 - 1) * CandidateSampler.jitterRatio)
        }

        let a = base.adjustment
        var adjustment = a
        adjustment.brightness = min(max(a.brightness + jitter(t.brightness), -100), 100)
        adjustment.exposure = min(max(a.exposure + jitter(t.exposure), -100), 100)
        adjustment.contrast = min(max(a.contrast + jitter(t.contrast), 0), 200)
        adjustment.saturation = min(max(a.saturation + jitter(t.saturation), 0), 200)
        adjustment.temperature = min(max(a.temperature + jitter(t.temperature), 2000), 8000)
        adjustment.tint = min(max(a.tint + jitter(t.tint), -100), 100)

        let b = base.beauty
        var beauty = b
        if beautyJitter && b.enabled {
            beauty.smoothing = min(max(b.smoothing + (nextFloat() * 2 - 1) * CandidateSampler.beautyJitterSmoothing, 0), 100)
            beauty.whitening = min(max(b.whitening + (nextFloat() * 2 - 1) * CandidateSampler.beautyJitterWhitening, 0), 100)
        }

        var out = base
        out.adjustment = adjustment
        out.beauty = beauty
        return out
    }
}
