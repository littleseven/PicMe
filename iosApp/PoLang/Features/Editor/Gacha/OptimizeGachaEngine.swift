import Foundation
import UIKit

// MARK: - OptimizeGachaEngine（抽卡编排引擎）
//
// 移植自 androidApp `domain/agent/capability/optimize/gacha/OptimizeGachaEngine.kt`。
// 采样 → 渲染 → 评分 → 选优/退化守卫；候选逐卡串行渲染评分（对齐 Android mapNotNull 顺序语义）。
//
// 所有媒体处理 100% 端侧（[PRIVACY] 红线）：小图解码、CIImage 渲染、NIMA 评分均不出设备。
// 日志对齐 iOS 现状（NSLog + [PoLang:Module] tag）；每卡明细日志为阈值校准的结构化可观测性依赖。
final class OptimizeGachaEngine {

    private static let tag = "[PoLang:OptimizeGacha]"

    private let sampler: CandidateSampler
    private let renderer: CandidateRenderer
    private let optimizeScorer: OptimizeScorer
    private let aestheticScorer: AestheticScorer

    init(sampler: CandidateSampler = CandidateSampler(),
         renderer: CandidateRenderer = CandidateRenderer(),
         aestheticScorer: AestheticScorer = NimaScorer()) {
        self.sampler = sampler
        self.renderer = renderer
        self.aestheticScorer = aestheticScorer
        self.optimizeScorer = OptimizeScorer(scorer: aestheticScorer)
    }

    /// 执行一次抽卡。
    ///
    /// - Parameters:
    ///   - imageFile: 原图本地文件 URL
    ///   - scene: 场景（决定采样方向池）
    ///   - basePreset: 锚点 preset（卡 0 原样使用）
    ///   - count: 候选总数（含锚点卡）
    ///   - exclude: 「换一组」时需排除的 fingerprint 集合
    func run(imageFile: URL,
             scene: OptimizeScene,
             basePreset: OptimizePreset,
             count: Int = CandidateSampler.defaultCount,
             exclude: Set<String> = []) async -> GachaResult {
        guard await aestheticScorer.initialize() else {
            NSLog("%@ aesthetic scorer unavailable, gacha skipped", OptimizeGachaEngine.tag)
            return .unavailable
        }
        guard let base = renderer.decodeDownscaled(imageFile: imageFile) else {
            NSLog("%@ base decode failed, gacha skipped: %@", OptimizeGachaEngine.tag, imageFile.path)
            return .unavailable
        }
        // iOS 增补：像素提取失败（异常位图）等价解码失败走降级；Android IntArray 分配不会失败
        guard let originalPx = renderer.extractPixels(base) else {
            NSLog("%@ base pixel extract failed, gacha skipped: %@", OptimizeGachaEngine.tag, imageFile.path)
            return .unavailable
        }
        let originalLuminance = Guardrails.meanLuminance(originalPx)
        let originalClipRatio = Guardrails.highlightClipRatio(originalPx)
        let originalScore = aestheticScorer.score(base)

        let sourceUri = imageFile.path
        let candidates = sampler.sample(base: basePreset, scene: scene, count: count, exclude: exclude)
        var scored: [ScoredCandidate] = []
        for candidate in candidates {
            guard let rendered = renderer.render(candidate: candidate, base: base, sourceUri: sourceUri) else {
                continue
            }
            guard let px = renderer.extractPixels(rendered) else { continue }
            scored.append(optimizeScorer.scoreCandidate(candidate: candidate,
                                                        rendered: rendered,
                                                        renderedPx: px,
                                                        originalMeanLuminance: originalLuminance,
                                                        originalClipRatio: originalClipRatio))
        }
        if scored.count < OptimizeScorer.minValidCards {
            NSLog("%@ only %ld cards rendered, gacha unavailable", OptimizeGachaEngine.tag, scored.count)
            return .unavailable
        }

        // 每卡明细：分值或淘汰原因（结构化可观测性，阈值校准依赖此日志）
        let cardsSummary = scored.map { sc -> String in
            let verdict = sc.nimaScore.map { score in "score=\(score)" } ?? "reject=\(sc.rejectReason ?? "nil")"
            return "#\(sc.candidate.index) \(sc.candidate.direction) \(verdict)"
        }.joined(separator: ", ")
        NSLog("%@ gacha cards: %@ (originalLum=%f, originalClip=%f)",
              OptimizeGachaEngine.tag, cardsSummary, originalLuminance, originalClipRatio)

        let result = optimizeScorer.select(all: scored, originalScore: originalScore)
        let resultName: String
        switch result {
        case .selected: resultName = "Selected"
        case .keepOriginal: resultName = "KeepOriginal"
        case .unavailable: resultName = "Unavailable"
        }
        NSLog("%@ gacha done: scene=%@, cards=%ld, original=%@, result=%@",
              OptimizeGachaEngine.tag, scene.rawValue, scored.count,
              originalScore.map { v in String(v) } ?? "null", resultName)
        return result
    }
}
