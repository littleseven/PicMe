import SwiftUI

/// TAG 扫描控制页（完全复刻 Android TagGenerationControlScreen 的结构 + 视觉规范）。
/// 使用本 App 设计系统（DesignTokens / MatIcon / AppTopBar / 卡片习惯），SP-B 仅 Pass1 真正可用。
struct TagScanScreen: View {
    @StateObject private var vm = TagScanViewModel()
    @State private var showComingSoon = false

    /// 由父视图注入的关闭回调（TAB 与 fullScreenCover 均注入）。
    var onDismiss: (() -> Void)? = nil

    private var state: ScanSessionState { vm.progress?.state ?? .idle }
    private var showControls: Bool { state == .running || state == .paused }
    private var showQuick: Bool { state == .idle && !vm.hasUnfinishedSession }

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
                    if let p = vm.progress {
                        ScanProgressCard(progress: p)
                    }
                    if showControls {
                        ScanControlRow(vm: vm)
                    }
                    ScanStatsCard(stats: vm.stats)
                    ScanPipelineOverview()
                    if showQuick {
                        ScanQuickActions(vm: vm)
                    }
                    ScanPassControlSection(vm: vm, onDisabled: { showComingSoon = true })
                }
                .padding(Spacing.lg)
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .onAppear { vm.refreshStats() }
        .alert(Text("scan_coming_soon_toast"), isPresented: $showComingSoon) {
            Button("OK", role: .cancel) {}
        }
    }

    private var resumeRow: some View {
        HStack {
            Text("scan_resume_unfinished").font(AppTypography.bodyMedium.font)
            Spacer()
            Button(action: { vm.resumeUnfinished() }) {
                Text("scan_action_resume")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, Spacing.md).padding(.vertical, 6)
                    .background(Color.accentColor)
                    .clipShape(Capsule())
            }
            .accessibilityIdentifier("scan_resume_unfinished_btn")
        }
        .padding(Spacing.md)
        .background(Color(.secondarySystemBackground))
        .clipShape(AppShapes.card)
    }
}
