import SwiftUI

// MARK: - TAG 生成控制页 v2 组件（spec: specs/screens/tag-control.yaml，特性 tag-control-v2）
//
// 四区块：Library 统计 / Scan 扫描动作 / Stages 阶段管理 / Regenerate 精细重生成。
// 执行链消费既有 TagScanOrchestrator（经 TagScanViewModel），本层纯控制页 UI。
// v2 强调色字面量按 yaml 内联（#00E676/#FFB020/#FF7EB0/#9B8CFF/#22D3EE/#4ADE80，暂不入 token）。

/// 品牌渐变（ChatBubbleTokens，#0F766E→#5EA88F）：渐变按钮/大数字/轨道。
let tagControlBrandGradient = LinearGradient(
    colors: [ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd],
    startPoint: .topLeading,
    endPoint: .bottomTrailing
)

/// yaml accent_literals 内联色（ARGB hex）。
private extension Color {
    init(hex: String) {
        let scanner = Scanner(string: hex)
        var hexNumber: UInt64 = 0
        scanner.scanHexInt64(&hexNumber)
        let a = Double((hexNumber & 0xFF00_0000) >> 24) / 255
        let r = Double((hexNumber & 0x00FF_0000) >> 16) / 255
        let g = Double((hexNumber & 0x0000_FF00) >> 8) / 255
        let b = Double(hexNumber & 0x0000_00FF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

// MARK: - v2 区块卡（surfaceContainer r16）

private struct TagCardBackground: ViewModifier {
    @Environment(\.colorScheme) private var cs

    func body(content: Content) -> some View {
        content
            .padding(Spacing.lg)
            .background(appScheme(cs).surfaceContainer)
            .clipShape(AppShapes.lg)
    }
}

extension View {
    /// v2 区块卡：surfaceContainer + 16dp 圆角（yaml token_mapping.card）。
    func tagCard() -> some View { modifier(TagCardBackground()) }
}

// MARK: - 公共 helper

/// 对齐 Android formatDuration：d>0→Xd Yh；h>0→Xh Ym；m>0→Xm Ys；else→Xs。
private func formatDuration(_ ms: Int) -> String {
    let t = max(0, ms / 1000)
    let d = t / 86400, h = (t % 86400) / 3600, m = (t % 3600) / 60, s = t % 60
    if d > 0 { return "\(d)d \(h)h" }
    if h > 0 { return "\(h)h \(m)m" }
    if m > 0 { return "\(m)m \(s)s" }
    return "\(s)s"
}

// MARK: - 渐变胶囊按钮（GradientPillButton：渐变底/白字）

struct GradientPillButton: View {
    let titleKey: String
    var height: CGFloat = 44
    var fontSize: CGFloat = 14
    var disabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(L(titleKey))
                .font(.system(size: fontSize, weight: .medium))
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .background(tagControlBrandGradient)
                .clipShape(Capsule())
        }
        .disabled(disabled)
        .opacity(disabled ? 0.4 : 1)
    }
}

// MARK: - 描边胶囊按钮（OutlinedPillButton：1dp outlineVariant 描边）

struct OutlinedPillButton: View {
    let titleKey: String
    var height: CGFloat = 40
    let action: () -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        Button(action: action) {
            Text(L(titleKey))
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(s.onSurface)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(s.outlineVariant, lineWidth: 1))
        }
    }
}

// MARK: - 区块 1：Library 统计卡（渐变 hero + 实色环 + 2×2 无图标瓦片）

struct TagStatsCard: View {
    let stats: ScanDbStats
    @Environment(\.colorScheme) private var cs

    /// 语义索引覆盖率（hero 数字 + 进度环共用）。
    private var coverage: Double {
        guard stats.totalMedia > 0 else { return 0 }
        return min(1, max(0, Double(stats.withSemantic) / Double(stats.totalMedia)))
    }
    private var coveragePercent: Int { Int((coverage * 100).rounded()) }

    var body: some View {
        let s = appScheme(cs)
        VStack(spacing: Spacing.lg) {
            // hero：实色 primary 弧 + surfaceVariant 轨道 + 品牌渐变大数字
            ZStack {
                Circle().stroke(s.surfaceVariant, lineWidth: 8)
                Circle()
                    .trim(from: 0, to: coverage)
                    .stroke(s.primary, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(coveragePercent)%")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(tagControlBrandGradient)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            .frame(width: 96, height: 96)
            .frame(maxWidth: .infinity)
            Text(String(format: L("%1$d%% semantically indexed"), coveragePercent))
                .font(AppTypography.bodySmall.font)
                .foregroundStyle(s.onSurfaceVariant)

            // 2×2 无图标瓦片（value 17 semibold / label 11）
            VStack(spacing: Spacing.sm) {
                HStack(spacing: Spacing.sm) {
                    tile("\(stats.withFace)", "Faces", s)
                    tile("\(stats.personCount)", "People", s)
                }
                HStack(spacing: Spacing.sm) {
                    tile("\(stats.withLabels)", "Content tags", s)
                    // iOS 执行链暂无美学评分数据 → 占位（见交付报告存疑项）
                    tile("—", "Quality scores", s)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .tagCard()
    }

    private func tile(_ value: String, _ labelKey: String, _ s: SchemeColors) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(s.onSurface)
                .lineLimit(1)
            Text(L(labelKey))
                .font(.system(size: 11))
                .foregroundStyle(s.onSurfaceVariant)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.md)
        .background(s.surfaceVariant)
        .clipShape(AppShapes.card)
    }
}

// MARK: - 状态 chip（h24 r12 + 6dp dot + 11sp；绿 #00E676 / 橙 #FFB020 系）

struct ScanStatusChip: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(text)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(color)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .frame(height: 24)
        .background(color.opacity(0.15))
        .clipShape(Capsule())
    }
}

// MARK: - 区块 2：空闲动作卡（chip + 标题 + 说明 + 渐变轨道 + 增量/全量双按钮）

struct ScanActionCard: View {
    @ObservedObject var vm: TagScanViewModel
    @Environment(\.colorScheme) private var cs

    /// 待处理媒体（Pass1 + Pass3 未覆盖数）：0 → Up to date。
    private var pendingCount: Int { vm.stats.remainingPass1 + vm.stats.remainingPass3 }
    private var upToDate: Bool { vm.stats.totalMedia > 0 && pendingCount == 0 }
    /// 上次会话终态 → 换「已完成」说明文案。
    private var doneCaption: Bool { (vm.progress?.state ?? .idle).isTerminal }

    var body: some View {
        let s = appScheme(cs)
        VStack(alignment: .leading, spacing: Spacing.sm) {
            // 标题左 + 状态 chip 右（Android ScanActionCard 同行式）
            HStack {
                Text(L("Scan status"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(s.onSurface)
                Spacer(minLength: 8)
                ScanStatusChip(
                    text: upToDate
                        ? L("Up to date")
                        : String(format: L("%1$d pending"), pendingCount),
                    color: upToDate ? Color(hex: "FF00E676") : Color(hex: "FFFFB020")
                )
            }
            Text(captionText)
                .font(AppTypography.bodySmall.font)
                .foregroundStyle(s.onSurfaceVariant)
            // 进度轨道（h6 r3）：surfaceVariant 底 + 渐变按已覆盖比例填充（对齐 Android :944-958）
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(s.surfaceVariant)
                    Capsule()
                        .fill(tagControlBrandGradient)
                        .frame(width: max(0, geo.size.width * progressFraction))
                }
            }
            .frame(height: 6)
            HStack(spacing: Spacing.sm) {
                GradientPillButton(titleKey: "Scan new", height: 40) { vm.startIncremental() }
                    .accessibilityIdentifier("scan_new_btn")
                // Android onRescanAll 仅 guard 无二次确认（审查 Y6）——直发全量
                OutlinedPillButton(titleKey: "Rescan all", height: 40) { vm.startFull() }
                    .accessibilityIdentifier("rescan_all_btn")
            }
            .padding(.top, Spacing.xs)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .tagCard()
    }

    /// 已覆盖比例 =(total−pending)/total；空库 0（审查 R1 修复：原恒满格）
    private var progressFraction: CGFloat {
        let total = vm.stats.totalMedia
        guard total > 0 else { return 0 }
        return CGFloat(max(0, total - pendingCount)) / CGFloat(total)
    }

    /// 说明行：空闲=图库/待处理统计（参数化，审查 Y2）；终态=上次会话结果
    private var captionText: String {
        if doneCaption, let p = vm.progress {
            return String(format: L("%1$d items scanned · %2$d failed"), p.processed, p.failed)
        }
        return String(format: L("%1$d items in library · %2$d pending"), vm.stats.totalMedia, pendingCount)
    }
}

// MARK: - 区块 2：进度卡（扫描中/终态结果摘要；state 着色容器，沿用 v1 语义）

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
                Text(L(progress.state.localizationKey))
                    .font(AppTypography.titleSmall.font)
                    .foregroundStyle(onCont)
                Spacer(minLength: 0)
            }
            ProgressView(value: Double(progress.processed), total: max(1, Double(progress.total)))
                .tint(onCont)
            Text(String(format: L("scan_progress_summary"),
                        progress.processed, progress.total, progress.pending, progress.failed))
                .font(AppTypography.bodySmall.font)
                .foregroundStyle(onCont)
            if active && progress.estimatedRemainingMs > 0 {
                Text("\(L("scan_eta_prefix")): \(formatDuration(progress.estimatedRemainingMs))")
                    .font(AppTypography.bodySmall.font)
                    .foregroundStyle(onCont.opacity(AppAlpha.emphasis))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.lg)
        .background(cont)
        .clipShape(AppShapes.lg)
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

// MARK: - 区块 2：会话控制条（暂停/恢复/取消/重试失败，secondaryContainer 卡）

struct ScanControlRow: View {
    @ObservedObject var vm: TagScanViewModel
    @Environment(\.colorScheme) private var cs

    private enum BtnStyle { case filled, outline, danger }

    var body: some View {
        let state = vm.progress?.state ?? .idle
        HStack(spacing: Spacing.sm) {
            if state == .running {
                ctrlBtn("pause", "scan_action_pause", style: .outline) { vm.pause() }
            } else if state == .paused {
                ctrlBtn("play_arrow", "scan_action_resume", style: .filled) { vm.resume() }
            }
            if !state.isTerminal {
                ctrlBtn("xmark.circle", "scan_action_cancel", style: .danger) { vm.cancel() }
            }
            if (vm.progress?.failed ?? 0) > 0 {
                ctrlBtn("arrow.clockwise", "scan_action_retry", style: .outline) { vm.retryFailed() }
            }
        }
        .padding(Spacing.md)
        .background(appScheme(cs).secondaryContainer)
        .clipShape(AppShapes.card)
    }

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

// MARK: - 区块标题（11sp 标题 + 右侧 hint）

struct TagSectionHeader: View {
    let titleKey: String
    var hintKey: String? = nil
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        HStack(spacing: Spacing.sm) {
            Text(L(titleKey))
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(s.onSurfaceVariant)
            Spacer(minLength: 0)
            if let hintKey {
                Text(L(hintKey))
                    .font(.system(size: 11))
                    .foregroundStyle(s.onSurfaceVariant.opacity(AppAlpha.hint))
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, Spacing.xs)
    }
}

// MARK: - 区块 3：Stages（4× StageRow → StageActionSheet）

/// 阶段定义（图标字形取 MaterialIconMap 已有映射；强调色按 yaml 内联）。
enum TagStage: String, Identifiable, CaseIterable {
    case faces, people, content, aesthetic

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .faces: return "Faces"
        case .people: return "People"
        case .content: return "Content tags"
        case .aesthetic: return "Quality scores"
        }
    }

    var iconName: String {
        switch self {
        case .faces: return "face.smiling"
        case .people: return "person.2"
        case .content: return "tag"
        case .aesthetic: return "sparkles"
        }
    }

    var accent: Color {
        switch self {
        case .faces: return Color(hex: "FFFF7EB0")
        case .people: return Color(hex: "FF9B8CFF")
        case .content: return Color(hex: "FF22D3EE")
        case .aesthetic: return Color(hex: "FF4ADE80")
        }
    }
}

struct TagStageSection: View {
    @ObservedObject var vm: TagScanViewModel
    /// iOS 执行链暂无美学评分 → 弹「后续版本」提示（沿用 v1 语义）。
    var onUnavailable: () -> Void
    @Environment(\.colorScheme) private var cs
    @State private var activeSheet: TagStage?

    var body: some View {
        VStack(spacing: Spacing.md) {
            TagSectionHeader(titleKey: "Stages", hintKey: "Tap a stage to manage")
            VStack(spacing: 0) {
                stageRow(.faces)
                Divider()
                stageRow(.people)
                Divider()
                stageRow(.content)
                Divider()
                stageRow(.aesthetic)
            }
            .tagCard()
        }
        .sheet(item: $activeSheet) { stage in
            StageActionSheet(
                stage: stage,
                onRunNew: { run(stage, full: false) },
                onRunFull: { run(stage, full: true) }
            )
        }
    }

    private func stageRow(_ stage: TagStage) -> some View {
        let s = appScheme(cs)
        return Button {
            activeSheet = stage
        } label: {
            HStack(spacing: Spacing.md) {
                MatIcon(name: stage.iconName, size: IconSize.sm)
                    .foregroundStyle(stage.accent)
                Text(L(stage.titleKey))
                    .font(AppTypography.bodyMedium.font)
                    .foregroundStyle(s.onSurface)
                Spacer(minLength: 0)
                Text(trailingValue(stage))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(s.primary)
                MatIcon(name: "arrow_forward", size: IconSize.sm)
                    .foregroundStyle(s.onSurfaceVariant.opacity(AppAlpha.hint))
            }
            .padding(.vertical, Spacing.md)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("stage_\(stage.rawValue)_row")
    }

    /// 行尾值：Faces/Content tags/Quality scores=百分比；People=人数。
    private func trailingValue(_ stage: TagStage) -> String {
        let st = vm.stats
        switch stage {
        case .faces:
            guard st.totalMedia > 0 else { return "0%" }
            let done = Double(max(0, st.totalMedia - st.remainingPass1))
            return "\(Int((done / Double(st.totalMedia) * 100).rounded()))%"
        case .people:
            return "\(st.personCount)"
        case .content:
            guard st.totalMedia > 0 else { return "0%" }
            return "\(Int((Double(st.withLabels) / Double(st.totalMedia) * 100).rounded()))%"
        case .aesthetic:
            // iOS 执行链暂无美学评分数据 → 占位（见交付报告存疑项）
            return "—"
        }
    }

    /// 阶段动作 → 既有执行链（调用面不变）：
    /// Faces new/full = 增量/全量扫描（Pass1→2→3 链）；People = 重聚类（new/full 同源）；
    /// Content tags = Pass3 增量/全量；aesthetic = 不可用（iOS 无对应执行链）。
    private func run(_ stage: TagStage, full: Bool) {
        activeSheet = nil
        switch stage {
        case .faces:
            if full { vm.startFull() } else { vm.startIncremental() }
        case .people:
            vm.runPass2()
        case .content:
            if full { vm.startPass3Full() } else { vm.startPass3Incremental() }
        case .aesthetic:
            onUnavailable()
        }
    }
}

// MARK: - 阶段动作 Sheet（surfaceContainerHighest；单选两档，点卡片直接执行）

struct StageActionSheet: View {
    let stage: TagStage
    var onRunNew: () -> Void
    var onRunFull: () -> Void
    @Environment(\.colorScheme) private var cs
    @State private var selection: StageAction = .new
    @State private var showFullConfirm = false

    enum StageAction { case new, full }

    var body: some View {
        let s = appScheme(cs)
        VStack(alignment: .leading, spacing: Spacing.lg) {
            Text(L(stage.titleKey))
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(s.onSurface)
            Text(L("Choose what to reprocess in this stage."))
                .font(AppTypography.bodySmall.font)
                .foregroundStyle(s.onSurfaceVariant)
            optionCard(.new, badge: L("Recommended"), s)
            optionCard(.full, badge: nil, s)
        }
        .padding(Spacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(s.surfaceContainerHighest)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        // 「Reprocess everything」先确认再执行（yaml confirm_first）
        .alert(L("Reprocess everything?"), isPresented: $showFullConfirm) {
            Button(L("Start")) { onRunFull() }
            Button(L("Cancel"), role: .cancel) {}
        } message: {
            Text(L("This stage will redo all photos and may take a while."))
        }
    }

    private func optionCard(_ action: StageAction, badge: String?, _ s: SchemeColors) -> some View {
        let selected = selection == action
        return Button {
            selection = action
            if action == .new {
                onRunNew()
            } else {
                showFullConfirm = true
            }
        } label: {
            HStack(alignment: .top, spacing: Spacing.md) {
                RadioCircle(selected: selected)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: Spacing.sm) {
                        Text(L(action == .new ? "Process new only" : "Reprocess everything"))
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(s.onSurface)
                        if let badge {
                            Text(badge)
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(s.primary)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(s.primary.opacity(0.14))
                                .clipShape(Capsule())
                        }
                    }
                    Text(L(action == .new
                           ? "Skips already-processed photos · fastest"
                           : "Redoes all photos · takes much longer"))
                        .font(AppTypography.bodySmall.font)
                        .foregroundStyle(s.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(Spacing.md)
            .background(s.surfaceContainer)
            .clipShape(AppShapes.card)
            .overlay(
                AppShapes.card.stroke(selected ? s.primary : s.outlineVariant,
                                      lineWidth: selected ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
    }
}

/// 单选圈：20dp 描边圈 + 选中内点（yaml radio_circle）。
struct RadioCircle: View {
    let selected: Bool
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        ZStack {
            Circle().strokeBorder(selected ? s.primary : s.outlineVariant, lineWidth: 2)
            if selected {
                Circle().fill(s.primary).frame(width: 10, height: 10)
            }
        }
        .frame(width: 20, height: 20)
    }
}

// MARK: - 区块 4：Regenerate（分类多选 + 时间范围单选 + 覆盖开关 + 渐变提交）

struct TagRegenerateCard: View {
    @ObservedObject var vm: TagScanViewModel
    @Environment(\.colorScheme) private var cs
    @State private var categories: Set<String> = []
    @State private var timeRange: String = "All time"
    @State private var overwrite = false
    @State private var showConfirm = false

    /// Faces 由 pass 决定不在此（yaml categories）；分类 key 即英文短句。
    private let categoryKeys: [String] = ["Scenes", "Activities", "Objects", "Tags", "Summary"]
    private let timeRangeKeys: [String] = ["All time", "7 days", "30 days", "90 days"]

    var body: some View {
        VStack(spacing: Spacing.md) {
            TagSectionHeader(titleKey: "Regenerate")
            VStack(alignment: .leading, spacing: Spacing.md) {
                let s = appScheme(cs)
                Text(L("Categories"))
                    .font(AppTypography.bodySmall.font)
                    .foregroundStyle(s.onSurfaceVariant)
                FlowLayout(spacing: Spacing.sm) {
                    ForEach(categoryKeys, id: \.self) { key in
                        TagChip(title: L(key), selected: categories.contains(key)) {
                            if categories.contains(key) {
                                categories.remove(key)
                            } else {
                                categories.insert(key)
                            }
                        }
                    }
                }
                Text(L("Time range"))
                    .font(AppTypography.bodySmall.font)
                    .foregroundStyle(s.onSurfaceVariant)
                FlowLayout(spacing: Spacing.sm) {
                    ForEach(timeRangeKeys, id: \.self) { key in
                        TagChip(title: L(key), selected: timeRange == key) { timeRange = key }
                    }
                }
                overwriteRow(s)
                GradientPillButton(titleKey: "Regenerate", height: 44, fontSize: 14,
                                   disabled: categories.isEmpty) {
                    // 覆盖模式=全量替换 → 二次确认；补齐模式（fill missing）直接执行
                    if overwrite {
                        showConfirm = true
                    } else {
                        submit()
                    }
                }
                .accessibilityIdentifier("regenerate_submit_btn")
            }
            .tagCard()
        }
        .alert(L("Reprocess everything?"), isPresented: $showConfirm) {
            Button(L("Start")) { submit() }
            Button(L("Cancel"), role: .cancel) {}
        } message: {
            Text(L("This stage will redo all photos and may take a while."))
        }
    }

    /// 覆盖开关行：开=覆盖已有结果；关=仅补齐缺失（原 fill_missing 语义）。
    private func overwriteRow(_ s: SchemeColors) -> some View {
        HStack(spacing: Spacing.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("Overwrite existing"))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(s.onSurface)
                Text(L("On: replace existing results. Off: fill in missing only."))
                    .font(.system(size: 11))
                    .foregroundStyle(s.onSurfaceVariant)
            }
            Spacer(minLength: 0)
            TagSwitch(isOn: $overwrite)
        }
    }

    /// 映射既有执行链：overwrite=false→Pass3 增量（仅补缺失）；true→Pass3 全量（覆盖重打）。
    /// categories/timeRange 为 UI 选择态，既有链暂不支持按类/时间过滤（见交付报告存疑项）。
    private func submit() {
        if overwrite {
            vm.startPass3Full()
        } else {
            vm.startPass3Incremental()
        }
    }
}

// MARK: - 选择 chip（h28 r100；选中 = primary@14% 底 + primary 描边）

struct TagChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: selected ? .semibold : .regular))
                .foregroundStyle(selected ? s.primary : s.onSurfaceVariant)
                .padding(.horizontal, 12)
                .frame(height: 28)
                .background(selected ? s.primary.opacity(0.14) : s.surfaceContainerHighest)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(selected ? s.primary : Color.clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 覆盖开关（44×26 r13 白点 20，替代旧「模式: X」单选）

struct TagSwitch: View {
    @Binding var isOn: Bool
    @Environment(\.colorScheme) private var cs

    var body: some View {
        let s = appScheme(cs)
        Button {
            withAnimation(.easeInOut(duration: AppMotion.fastMs / 1000)) {
                isOn.toggle()
            }
        } label: {
            ZStack {
                Capsule().fill(isOn ? s.primary : s.surfaceVariant)
                Circle()
                    .fill(Color.white)
                    .frame(width: 20, height: 20)
                    .offset(x: isOn ? 9 : -9)
                    .shadow(color: Color.black.opacity(0.2), radius: 1, y: 1)
            }
            .frame(width: 44, height: 26)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("Overwrite existing"))
    }
}
