import SwiftUI

/// TAG 扫描控制页（完全复刻 Android TagGenerationControlScreen 的 8 个 section）。
/// SP-B 仅 Pass1 可用；依赖 Pass2/Pass3 的控件渲染但置灰 +「后续阶段」徽标，点击 toast。
struct TagScanScreen: View {
    @StateObject private var vm = TagScanViewModel()
    @Environment(\.dismiss) private var dismiss
    @State private var showComingSoon = false

    /// 由父视图注入的关闭回调；nil 时不显示右上角关闭按钮（用作 tab 根视图时）。
    var onDismiss: (() -> Void)? = nil

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if vm.hasUnfinishedSession && !vm.isScanning && (vm.progress?.state ?? .idle) != .running {
                        resumePromptRow
                    }
                    ScanBackgroundBanner()                     // 1) 后台守护横幅
                    ScanProgressCard(progress: vm.progress)    // 2) 进度卡
                    ScanControlRow(vm: vm)                     // 3) 会话控制条
                    ScanStatsCard(stats: vm.stats)             // 4) 统计卡
                    ScanPipelineOverview()                     // 5) 管线概览
                    ScanQuickActions(vm: vm)                   // 6) 全量/增量
                    ScanPassControlSection(vm: vm,             // 7) 4 张 PassControlCard
                                           onDisabled: { showComingSoon = true })
                    ScanFineControlSection(onDisabled: { showComingSoon = true }) // 8) 精细控制
                }
                .padding()
            }
            .navigationTitle(Text("scan_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let onDismiss {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: onDismiss) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityIdentifier("scan_close")
                    }
                }
            }
            .onAppear { vm.refreshStats() }
            .alert(Text("scan_coming_soon_toast"), isPresented: $showComingSoon) {
                Button("OK", role: .cancel) {}
            }
        }
    }

    private var resumePromptRow: some View {
        HStack {
            Text("scan_resume_unfinished").font(.subheadline)
            Spacer()
            Button("scan_action_resume") { vm.resumeUnfinished() }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("scan_resume_unfinished_btn")
        }
        .padding(12)
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
