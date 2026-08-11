import SwiftUI

// MARK: - 扫描页公共 helper

extension View {
    /// 中性内容卡片：secondarySystemBackground + 12dp 圆角（本 App 内容卡片统一规范，对齐 Android Card 默认 tonal）。
    func scanCard(padding: CGFloat = Spacing.lg) -> some View {
        self
            .padding(padding)
            .background(Color(.secondarySystemBackground))
            .clipShape(AppShapes.card)
    }
}

// appScheme 已提升为 DesignSystem 共享 helper（DesignTokens.swift），本文件直接复用。

/// 对齐 Android formatDuration：d>0→Xd Yh；h>0→Xh Ym；m>0→Xm Ys；else→Xs。
private func formatDuration(_ ms: Int) -> String {
    let t = max(0, ms / 1000)
    let d = t / 86400, h = (t % 86400) / 3600, m = (t % 3600) / 60, s = t % 60
    if d > 0 { return "\(d)d \(h)h" }
    if h > 0 { return "\(h)h \(m)m" }
    if m > 0 { return "\(m)m \(s)s" }
    return "\(s)s"
}

private func l(_ key: String) -> String { NSLocalizedString(key, comment: "") }

// MARK: - 2) 进度卡（state 着色：primary/secondary/error container）
struct ScanProgressCard: View {
    let progress: TagScanSessionProgress
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        let cont = containerColor(progress.state, s)
        let onCont = onContainerColor(progress.state, s)
        let active: Bool = [.running, .pausing, .cancelling].contains(progress.state)
        return VStack(alignment: .leading, spacing: Spacing.sm) {
            HStack(spacing: Spacing.md) {
                if active {
                    ProgressView().controlSize(.small).tint(onCont)
                } else {
                    MatIcon(name: "info.circle", size: IconSize.sm).foregroundStyle(onCont)
                }
                Text(l(progress.state.localizationKey))
                    .font(AppTypography.titleSmall.font)
                    .foregroundStyle(onCont)
                Spacer(minLength: 0)
            }
            ProgressView(value: Double(progress.processed), total: max(1, Double(progress.total)))
                .tint(onCont)
            Text(String(format: l("scan_progress_summary"),
                        progress.processed, progress.total, progress.pending, progress.failed))
                .font(AppTypography.bodySmall.font)
                .foregroundStyle(onCont)
            if active && progress.estimatedRemainingMs > 0 {
                Text("\(l("scan_eta_prefix")): \(formatDuration(progress.estimatedRemainingMs))")
                    .font(AppTypography.bodySmall.font)
                    .foregroundStyle(onCont.opacity(AppAlpha.emphasis))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.lg)
        .background(cont)
        .clipShape(AppShapes.card)
    }

    private func containerColor(_ st: ScanSessionState, _ s: SchemeColors) -> Color {
        switch st {
        case .paused: return s.secondaryContainer
        case .cancelled: return s.errorContainer
        default: return s.primaryContainer
        }
    }
    private func onContainerColor(_ st: ScanSessionState, _ s: SchemeColors) -> Color {
        switch st {
        case .paused: return s.onSecondaryContainer
        case .cancelled: return s.onErrorContainer
        default: return s.onPrimaryContainer
        }
    }
}

// MARK: - 3) 会话控制条（secondaryContainer 卡 + 等宽图标按钮）
struct ScanControlRow: View {
    @ObservedObject var vm: TagScanViewModel
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let state = vm.progress?.state ?? .idle
        HStack(spacing: Spacing.sm) {
            if state == .running {
                ctrlBtn("pause", "scan_action_pause", style: .outline) { vm.pause() }
            } else if state == .paused {
                ctrlBtn("play_arrow", "scan_action_resume", style: .filled) { vm.resume() }
            }
            ctrlBtn("xmark.circle", "scan_action_cancel", style: .danger) { vm.cancel() }
            if (vm.progress?.failed ?? 0) > 0 {
                ctrlBtn("arrow.clockwise", "scan_action_retry", style: .outline) { vm.retryFailed() }
            }
        }
        .padding(Spacing.md)
        .background(appScheme(cs).secondaryContainer)
        .clipShape(AppShapes.card)
    }

    private enum BtnStyle { case filled, outline, danger }
    private func ctrlBtn(_ icon: String, _ key: String, style: BtnStyle, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                MatIcon(name: icon, size: IconSize.sm)
                Text(LocalizedStringKey(key)).font(AppTypography.labelSmall.font)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.xs)
            .background(style == .filled ? Color.accentColor : Color.clear)
            .foregroundStyle(style == .filled ? Color.white : (style == .danger ? StatusColor.error : Color.primary))
            .clipShape(Capsule())
            .overlay(
                Capsule().stroke((style == .danger ? StatusColor.error : Color.secondary).opacity(style == .filled ? 0 : 0.35),
                                 lineWidth: style == .filled ? 0 : 1)
            )
        }
    }
}

// MARK: - 4) 统计卡（单卡三分段 + 阶段表，对齐 Android StatsCard）
struct ScanStatsCard: View {
    let stats: ScanDbStats

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text("scan_db_stats_title").font(.system(size: 14, weight: .semibold))

            sectionTitle("scan_sub_media")
            HStack(spacing: 10) {
                numberCell("\(stats.totalMedia)", "scan_stat_total")
                numberCell("\(stats.withSemantic)", "scan_stat_with_semantic")
            }

            Divider().padding(.vertical, Spacing.sm)

            sectionTitle("scan_sub_face")
            HStack(spacing: 10) {
                numberCell("\(stats.withFace)", "scan_stat_with_face")
                numberCell("\(stats.faceEmbeddingCount)", "scan_stat_embedding_count")
            }
            HStack(spacing: 10) {
                numberCell("\(stats.personCount)", "scan_stat_person_count")
                numberCell("\(stats.namedPersonCount)", "scan_stat_named_person_count")
            }

            Divider().padding(.vertical, Spacing.sm)

            sectionTitle("scan_sub_stage")
            stageTable
        }
        .scanCard()
    }

    private func sectionTitle(_ k: String) -> some View {
        Text(LocalizedStringKey(k))
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(.accentColor)
            .padding(.top, Spacing.xs)
    }

    private func numberCell(_ val: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(val).font(.system(size: 16, weight: .bold)).lineLimit(1)
            Text(LocalizedStringKey(label)).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, Spacing.md).padding(.vertical, Spacing.sm)
        .background(Color(.tertiarySystemBackground))
        .clipShape(AppShapes.card)
    }

    private var stageTable: some View {
        let p1Done = max(0, stats.totalMedia - stats.remainingPass1)
        let p3Done = stats.withLabels
        return VStack(spacing: 0) {
            HStack {
                Text("scan_col_stage").font(AppTypography.labelSmall.font).foregroundColor(.secondary)
                Spacer()
                Text("scan_col_done").font(AppTypography.labelSmall.font).foregroundColor(.secondary)
                Spacer().frame(width: 28)
                Text("scan_col_pending").font(AppTypography.labelSmall.font).foregroundColor(.secondary)
            }
            .padding(.vertical, 4)
            Divider()
            stageRow("scan_pass_stage_face", p1Done, stats.remainingPass1)
            Divider()
            stageRow("scan_pass_stage_content", p3Done, stats.remainingPass3)
        }
    }
    private func stageRow(_ name: String, _ done: Int, _ pending: Int) -> some View {
        HStack {
            Text(l(name)).font(AppTypography.bodySmall.font).foregroundColor(.primary.opacity(AppAlpha.secondary))
            Spacer()
            Text("\(done)").font(.system(size: 12, weight: .semibold))
            Spacer().frame(width: 28)
            Text("\(pending)").font(.system(size: 12, weight: .semibold))
        }
        .padding(.vertical, 5)
    }
}

// MARK: - 5) 管线概览（对齐 Android 处理阶段概览）
struct ScanPipelineOverview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text("scan_pipeline_overview_title").font(.system(size: 14, weight: .semibold))
            pipelineRow("checkmark.circle", "scan_pipeline_face", done: true)
            pipelineRow("checkmark.circle", "scan_pipeline_cluster", done: true)
            pipelineRow("checkmark.circle", "scan_pipeline_content", done: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .scanCard()
    }
    private func pipelineRow(_ icon: String, _ key: String, done: Bool) -> some View {
        HStack(spacing: Spacing.sm) {
            MatIcon(name: icon, size: IconSize.sm)
                .foregroundStyle(done ? StatusColor.success : Color.secondary)
            Text(key).font(AppTypography.bodyMedium.font)
            Spacer(minLength: 0)
        }
    }
}

// MARK: - 6) 快捷操作（idle：全量/增量，对齐 Android quick-action card）
struct ScanQuickActions: View {
    @ObservedObject var vm: TagScanViewModel
    private var idle: Bool {
        (vm.progress?.state ?? .idle) == .idle && !vm.hasUnfinishedSession
    }
    var body: some View {
        Group {
            if idle {
                HStack(spacing: Spacing.sm) {
                    actionBtn("play_arrow", "scan_action_scan_full", filled: true) { vm.startFull() }
                    actionBtn("arrow.triangle.2.circlepath", "scan_action_scan_incremental", filled: false) { vm.startIncremental() }
                }
                .scanCard(padding: Spacing.md)
            }
        }
    }
    private func actionBtn(_ icon: String, _ key: String, filled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                MatIcon(name: icon, size: IconSize.sm)
                Text(LocalizedStringKey(key)).font(AppTypography.labelSmall.font)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.xs)
            .background(filled ? Color.accentColor : Color.clear)
            .foregroundStyle(filled ? Color.white : Color.primary)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Color.secondary.opacity(filled ? 0 : 0.35), lineWidth: filled ? 0 : 1))
        }
    }
}

// MARK: - 7) PassControlCard（横向：左 标题+描述+进度，右 增量/全量 图标行）
struct PassControlCard: View {
    let titleKey: LocalizedStringKey
    let descKey: LocalizedStringKey
    var fraction: Double?        // nil = 不显示进度条
    var progressText: String?
    var tag: String = ""          // accessibility 前缀（pass1/pass2/pass3/aesthetic）
    var onIncremental: () -> Void
    var onFull: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.sm) {
            VStack(alignment: .leading, spacing: 2) {
                Text(titleKey).font(.system(size: 14, weight: .semibold))
                Text(descKey).font(AppTypography.bodySmall.font).foregroundColor(.secondary)
                if let f = fraction {
                    HStack(spacing: Spacing.sm) {
                        ProgressView(value: f).tint(Color.accentColor)
                        if f >= 0.999 {
                            MatIcon(name: "checkmark.circle", size: IconSize.sm).foregroundStyle(StatusColor.success)
                        } else {
                            Text("\(Int((f * 100).rounded()))%").font(AppTypography.labelSmall.font).foregroundColor(Color.accentColor)
                        }
                    }
                    .padding(.top, 6)
                }
                if let pt = progressText {
                    Text(pt).font(AppTypography.bodySmall.font).foregroundColor(.secondary).padding(.top, 4)
                }
            }
            Spacer(minLength: Spacing.sm)
            VStack(alignment: .trailing, spacing: 2) {
                iconRow("plus.circle", "scan_action_incremental", onIncremental)
                    .accessibilityIdentifier("\(tag)_incremental")
                iconRow("arrow.clockwise.circle", "scan_action_full", onFull)
                    .accessibilityIdentifier("\(tag)_full")
            }
        }
        .padding(Spacing.md)
        .background(Color(.tertiarySystemBackground))
        .clipShape(AppShapes.card)
    }
    private func iconRow(_ icon: String, _ key: LocalizedStringKey, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                MatIcon(name: icon, size: IconSize.sm).foregroundStyle(Color.accentColor)
                Text(key).font(AppTypography.labelSmall.font).foregroundColor(.primary.opacity(AppAlpha.emphasis))
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 7+8) 分阶段独立控制（含精细控制，单卡）
struct ScanPassControlSection: View {
    @ObservedObject var vm: TagScanViewModel
    var onDisabled: () -> Void
    @State private var categories: Set<String> = ["scan_cat_face"]
    @State private var rangeAll = true
    @State private var fullRegenerate = false

    private let catKeys: [String] = ["scan_cat_face", "scan_cat_scene", "scan_cat_activity",
                                     "scan_cat_objects", "scan_cat_tags", "scan_cat_summary"]
    private let rangeKeys: [String] = ["scan_range_all", "scan_range_7d", "scan_range_30d", "scan_range_90d"]

    private var p1Fraction: Double? {
        guard vm.stats.totalMedia > 0 else { return nil }
        return Double(max(0, vm.stats.totalMedia - vm.stats.remainingPass1)) / Double(vm.stats.totalMedia)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text("scan_passctl_title").font(.system(size: 14, weight: .semibold))
            Text("scan_passctl_sub").font(AppTypography.bodySmall.font).foregroundColor(.secondary)

            PassControlCard(titleKey: "scan_pass1_title", descKey: "scan_pass1_desc",
                            fraction: p1Fraction, tag: "pass1",
                            onIncremental: { vm.startIncremental() }, onFull: { vm.startFull() })
            PassControlCard(titleKey: "scan_pass2_title", descKey: "scan_pass2_desc",
                            tag: "pass2",
                            onIncremental: { vm.runPass2() }, onFull: { vm.runPass2() })
            PassControlCard(titleKey: "scan_pass3_title", descKey: "scan_pass3_desc",
                            tag: "pass3",
                            onIncremental: { vm.startPass3Incremental() }, onFull: { vm.startPass3Full() })
            PassControlCard(titleKey: "scan_aesthetic_title", descKey: "scan_aesthetic_desc",
                            tag: "aesthetic",
                            onIncremental: { onDisabled() }, onFull: { onDisabled() })

            Divider().padding(.vertical, Spacing.xs)

            // 精细控制（同一卡下半区）
            Text("scan_fine_control_title").font(.system(size: 12, weight: .medium)).foregroundColor(.secondary)
            Text("scan_cat_label").font(AppTypography.bodySmall.font)
            FlowLayout(spacing: Spacing.sm) {
                ForEach(catKeys, id: \.self) { k in
                    ScanChip(key: LocalizedStringKey(k), selected: categories.contains(k)) {
                        if categories.contains(k) { categories.remove(k) } else { categories.insert(k) }
                    }
                }
            }
            Text("scan_range_label").font(AppTypography.bodySmall.font).padding(.top, Spacing.sm)
            FlowLayout(spacing: Spacing.sm) {
                ForEach(Array(rangeKeys.enumerated()), id: \.offset) { idx, k in
                    ScanChip(key: LocalizedStringKey(k), selected: (idx == 0) == rangeAll) {
                        rangeAll = (idx == 0)
                    }
                }
            }
            Toggle(isOn: $fullRegenerate) {
                if fullRegenerate {
                    Text("scan_mode_full")
                } else {
                    Text("scan_mode_inc")
                }
            }
            .padding(.top, Spacing.sm)

            Button(action: { vm.startPass3Full() }) {
                HStack(spacing: 4) {
                    MatIcon(name: "slider.horizontal.3", size: IconSize.sm)
                    Text("scan_regenerate_selected").font(AppTypography.labelSmall.font)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.sm)
                .background(Color.accentColor)
                .foregroundStyle(.white)
                .clipShape(Capsule())
            }
            .padding(.top, Spacing.sm)
        }
        .scanCard()
    }
}

// MARK: - 标准选择 chip（对齐本 App Settings/ModelCenter 的 capsule chip 习惯）
private struct ScanChip: View {
    let key: LocalizedStringKey
    let selected: Bool
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(key)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundColor(selected ? .white : .primary)
                .padding(.horizontal, 14).padding(.vertical, 6)
                .background(selected ? Color.accentColor : Color(.tertiarySystemBackground))
                .clipShape(Capsule())
        }
    }
}
