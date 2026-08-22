import SwiftUI

/// TAG 生成控制页 v2（spec: specs/screens/tag-control.yaml，特性 tag-control-v2）。
/// 四区块：Library 统计 / Scan 扫描动作 / Stages 阶段管理 / Regenerate 精细重生成；
/// 执行链消费既有 TagScanOrchestrator（经 TagScanViewModel，调用面不变）。
struct TagScanScreen: View {
    @StateObject private var vm = TagScanViewModel()
    @State private var showComingSoon = false
    @Environment(\.colorScheme) private var cs

    /// 由父视图注入的关闭回调（TAB 与 fullScreenCover 均注入）。
    var onDismiss: (() -> Void)? = nil

    private var state: ScanSessionState { vm.progress?.state ?? .idle }
    /// 会话进行中（非 idle 且未到终态）→ 进度卡 + 会话控制。
    private var sessionActive: Bool { state != .idle && !state.isTerminal }
    private var showControls: Bool { state == .running || state == .paused }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: NSLocalizedString("scan_page_title", comment: ""),
                      showsBackButton: true,
                      onBack: { onDismiss?() }) {
                EmptyView()
            }
            ScrollView {
                VStack(spacing: Spacing.lg) {
                    if vm.hasUnfinishedSession && state != .running {
                        resumeRow
                    }
                    // 区块 1：Library（统计置顶）
                    TagStatsCard(stats: vm.stats)
                    // 区块 2：Scan（动作卡 / 进度卡+会话控制 / 终态结果摘要）
                    scanSection
                    // 区块 3：Stages（阶段行 → 动作 Sheet）
                    TagStageSection(vm: vm, onUnavailable: { showComingSoon = true })
                    // 区块 4：Regenerate（精细重生成）
                    TagRegenerateCard(vm: vm)
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.top, Spacing.sm)
                .padding(.bottom, 28)
            }
        }
        .background(appScheme(cs).background.ignoresSafeArea())
        .onAppear { vm.refreshStats() }
        .alert(Text("scan_coming_soon_toast"), isPresented: $showComingSoon) {
            Button("OK", role: .cancel) {}
        }
        .alert(Text("scan_models_needed_title"), isPresented: $vm.showModelsNeeded) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("scan_models_needed_msg")
        }
    }

    /// 区块 2 状态机：进行中 → 进度卡+会话控制；终态 → 上次会话结果摘要（失败可重试）+动作卡；空闲 → 动作卡。
    @ViewBuilder private var scanSection: some View {
        if let p = vm.progress, sessionActive {
            ScanProgressCard(progress: p)
            if showControls {
                ScanControlRow(vm: vm)
            }
        } else {
            if let p = vm.progress, p.state.isTerminal {
                ScanProgressCard(progress: p)
                if p.failed > 0 {
                    ScanControlRow(vm: vm)
                }
            }
            ScanActionCard(vm: vm)
        }
    }

    /// 上次未完成 session 恢复行（保留 v1 既有能力，v2 卡片风格重绘）。
    private var resumeRow: some View {
        let s = appScheme(cs)
        return HStack {
            Text("scan_resume_unfinished").font(AppTypography.bodyMedium.font).foregroundStyle(s.onSurface)
            Spacer()
            Button(action: { vm.resumeUnfinished() }) {
                Text("scan_action_resume")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, Spacing.md).padding(.vertical, 6)
                    .background(tagControlBrandGradient)
                    .clipShape(Capsule())
            }
            .accessibilityIdentifier("scan_resume_unfinished_btn")
        }
        .padding(Spacing.md)
        .background(s.surfaceContainer)
        .clipShape(AppShapes.lg)
    }
}
