import SwiftUI
import UIKit

// MARK: - GachaCandidateBar（AI 优化抽卡候选条）
//
// 编辑器 AI 一键优化的对比模式底栏（editor.yaml §17.1）：顶栏「AI 优化」触发抽卡后，
// 整个 bottomBar（面板 + tab 条）替换为本组件；主预览区照常全尺寸预览选中卡
// （previewedImage 由 ViewModel 驱动，本组件只负责候选卡与操作按钮）。
//
// 结构（自上而下）：
//   提示行 —— keepOriginal → ai_optimize_keep_hint（AI 认为原图已很好）；否则 ai_optimize_pick_hint
//   4 卡行 —— SpaceEvenly（等宽槽位）；缩略图内存态（ScoredCandidate.thumbnail，512px 渲染）；
//             previewing 卡 2pt primary 描边；推荐角标 ai_optimize_recommended（topStart）；
//             rejected 卡 0.4 alpha 不可点；卡下方向名 labelSmall（ai_optimize_direction_*）
//   按钮行 —— 右对齐：应用（已预览且非淘汰卡才可点）/ 换一组 / 关闭；
//             isProcessing 时整行替换为 16pt ProgressView
struct GachaCandidateBar: View {
    let run: PhotoEditorViewModel.GachaRunUiState
    let isProcessing: Bool
    let onPreview: (Int) -> Void
    let onApply: () -> Void
    let onReroll: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        let s = AppColorScheme.dark
        VStack(spacing: Spacing.md) {
            hintRow(scheme: s)
            cardRow(scheme: s)
            buttonRow(scheme: s)
        }
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, Spacing.md)
        .background(
            // 容器 surface 12 圆角（§17.1），浮于预览黑底之上
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(s.surface))
        // 紧凑外边距：保证 4×84pt 卡片在 375pt 窄屏不溢出（372 ≤ 375）
        .padding(.horizontal, Spacing.xs)
        .padding(.vertical, Spacing.sm)
    }

    // MARK: 提示行（§17.1：keepOriginal → keep_hint，否则 pick_hint）

    private func hintRow(scheme s: SchemeColors) -> some View {
        Text(L(run.keepOriginal ? "ai_optimize_keep_hint" : "ai_optimize_pick_hint"))
            .font(AppTypography.bodySmall.font)
            .foregroundStyle(s.onSurfaceVariant)
            .lineLimit(2)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: 4 卡行（SpaceEvenly = 等宽槽位均分）

    private func cardRow(scheme s: SchemeColors) -> some View {
        HStack(spacing: Spacing.xs) {
            ForEach(run.candidates, id: \.candidate.index) { scored in
                candidateCard(scored, scheme: s)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder
    private func candidateCard(_ scored: ScoredCandidate, scheme s: SchemeColors) -> some View {
        let isPreviewing = scored.candidate.index == run.previewedIndex
        let isRecommended = scored.candidate.index == run.recommendedIndex
        Button {
            onPreview(scored.candidate.index)
        } label: {
            VStack(spacing: Spacing.xs) {
                // 卡容器 = 84pt 方框：previewing 2pt primary 描边（overlay 不占布局，
                // 窄屏下 4 卡不溢出）；缩略图圆角 6 / 描边圆角 8 双 token 体系
                thumbnail(scored, scheme: s)
                    .overlay(
                        RoundedRectangle(cornerRadius: EditorTokens.gachaCardCornerRadius, style: .continuous)
                            .strokeBorder(isPreviewing ? s.primary : Color.clear, lineWidth: 2))
                    .overlay(alignment: .topLeading) {
                        if isRecommended {
                            recommendedBadge(scheme: s)
                        }
                    }
                Text(L("ai_optimize_direction_\(scored.candidate.direction)"))
                    .font(AppTypography.labelSmall.font)
                    .foregroundStyle(isPreviewing ? s.primary : s.onSurface.opacity(0.85))
                    .lineLimit(1)
            }
        }
        .buttonStyle(.plain)
        .disabled(scored.rejected)   // 护栏淘汰卡不可点
        .opacity(scored.rejected ? 0.4 : 1)
        .accessibilityLabel(Text(cardAccessibilityLabel(scored, recommended: isRecommended)))
    }

    /// 候选缩略图（编辑器路径为内存态 UIImage；缺图回退 surfaceVariant 占位）。
    private func thumbnail(_ scored: ScoredCandidate, scheme s: SchemeColors) -> some View {
        Group {
            if let image = scored.thumbnail {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                RoundedRectangle(cornerRadius: EditorTokens.gachaCardThumbCornerRadius, style: .continuous)
                    .fill(s.surfaceVariant)
            }
        }
        .frame(width: EditorTokens.gachaCardThumbSize, height: EditorTokens.gachaCardThumbSize)
        .clipShape(RoundedRectangle(cornerRadius: EditorTokens.gachaCardThumbCornerRadius, style: .continuous))
    }

    /// 推荐角标（topStart，内缩于卡角）。
    private func recommendedBadge(scheme s: SchemeColors) -> some View {
        Text(L("ai_optimize_recommended"))
            .font(AppTypography.labelSmall.font)
            .foregroundStyle(s.onPrimaryContainer)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(
                Capsule(style: .continuous).fill(s.primaryContainer))
            .padding(Spacing.xs)
    }

    // MARK: 按钮行（右对齐；isProcessing 时整行替换为 16pt ProgressView）

    @ViewBuilder
    private func buttonRow(scheme s: SchemeColors) -> some View {
        if isProcessing {
            HStack {
                Spacer()
                ProgressView()
                    .frame(width: 16, height: 16)
                    .tint(s.primary)
            }
        } else {
            HStack(spacing: Spacing.lg) {
                Spacer()
                barButton(L("ai_optimize_apply"), emphasized: true, enabled: applyEnabled, scheme: s) {
                    onApply()
                }
                barButton(L("ai_optimize_reroll"), enabled: true, scheme: s) {
                    onReroll()
                }
                barButton(L("ai_optimize_dismiss"), enabled: true, scheme: s) {
                    onDismiss()
                }
            }
        }
    }

    /// 应用可点条件：已预览某卡（previewedIndex >= 0）且该卡未被护栏淘汰。
    private var applyEnabled: Bool {
        guard run.previewedIndex >= 0,
              let scored = run.candidates.first(where: { item in item.candidate.index == run.previewedIndex })
        else { return false }
        return !scored.rejected
    }

    private func barButton(_ title: String, emphasized: Bool = false, enabled: Bool,
                           scheme s: SchemeColors, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(emphasized
                      ? AppTypography.labelLarge.font.weight(AppTypography.WeightOverride.semibold)
                      : AppTypography.labelLarge.font)
                .foregroundStyle(enabled ? s.primary : s.onSurfaceVariant.opacity(0.35))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private func cardAccessibilityLabel(_ scored: ScoredCandidate, recommended: Bool) -> String {
        var label = L("ai_optimize_direction_\(scored.candidate.direction)")
        if recommended {
            label += ", " + L("ai_optimize_recommended")
        }
        return label
    }
}
