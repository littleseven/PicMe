import UIKit

// MARK: - OptimizeScorer（抽卡评分器）
//
// 移植自 androidApp `domain/agent/capability/optimize/gacha/OptimizeScorer.kt`。
// 技术护栏 → NIMA 打分 → 选优 + 退化守卫。
final class OptimizeScorer {

    /// 退化守卫阈值：最优候选相对原图的最小 NIMA 提升（初始值，离线样张校准）。
    static let minImprovement: Float = 0.05

    /// 有效候选卡下限，低于则判定抽卡不可用。
    static let minValidCards = 2

    private let scorer: AestheticScorer

    init(scorer: AestheticScorer) {
        self.scorer = scorer
    }

    /// 给单张渲染结果评分：先护栏后 NIMA（护栏淘汰的卡不再打分）。
    ///
    /// - Parameters:
    ///   - candidate: 候选参数
    ///   - rendered: 候选渲染结果（同时作为 thumbnail 带回）
    ///   - renderedPx: rendered 的 RGBA8 像素缓冲（护栏计算用）；必须与 rendered 为同一
    ///     渲染结果的像素，护栏计算依赖两者一致
    ///   - originalMeanLuminance: 原图平均亮度
    ///   - originalClipRatio: 原图高光裁剪率（护栏增量判定基准）
    func scoreCandidate(candidate: OptimizeCandidate,
                        rendered: UIImage,
                        renderedPx: [UInt8],
                        originalMeanLuminance: Float,
                        originalClipRatio: Float) -> ScoredCandidate {
        let rejectReason = Guardrails.check(candidatePx: renderedPx,
                                            originalMeanLuminance: originalMeanLuminance,
                                            originalClipRatio: originalClipRatio)
        if let rejectReason = rejectReason {
            return ScoredCandidate(candidate: candidate,
                                   nimaScore: nil,
                                   rejected: true,
                                   rejectReason: rejectReason,
                                   thumbnail: rendered)
        }
        let score = scorer.score(rendered)
        return ScoredCandidate(candidate: candidate,
                               nimaScore: score,
                               rejected: score == nil,
                               rejectReason: score == nil ? "nima_failed" : nil,
                               thumbnail: rendered)
    }

    /// 选优 + 退化守卫。
    ///
    /// - 有效卡（未淘汰且有分）< `minValidCards` → `.unavailable`
    /// - 原图分可用且最优卡提升 ≤ `minImprovement` → `.keepOriginal`
    /// - 原图分不可用 → 跳过守卫直接选优（spec §9）
    func select(all: [ScoredCandidate], originalScore: Float?) -> GachaResult {
        let valid = all.filter { candidate in
            !candidate.rejected && candidate.nimaScore != nil
        }
        if valid.count < OptimizeScorer.minValidCards { return .unavailable }
        // 手写遍历保证「并列最高取首张」——对齐 Kotlin maxBy 语义
        // （Swift max(by:) 对并列元素的行为不作首张保证）。
        var best = valid[0]
        for candidate in valid.dropFirst() {
            if let bestScore = best.nimaScore, let score = candidate.nimaScore, score > bestScore {
                best = candidate
            }
        }
        if let originalScore = originalScore,
           let bestScore = best.nimaScore,
           bestScore <= originalScore + OptimizeScorer.minImprovement {
            return .keepOriginal(all: all, originalScore: originalScore)
        }
        return .selected(best: best, all: all, originalScore: originalScore)
    }
}
