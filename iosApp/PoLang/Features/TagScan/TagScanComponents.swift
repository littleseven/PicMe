import SwiftUI

// MARK: 1) 后台守护横幅（iOS：静态电量/热态提示）
struct ScanBackgroundBanner: View {
    var body: some View {
        Label("scan_background_banner", systemImage: "battery.25")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(.thinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: 2) 进度卡
struct ScanProgressCard: View {
    let progress: TagScanSessionProgress?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(progress?.state.localizationKey ?? "scan_state_idle")
                .font(.headline)
            if let p = progress {
                ProgressView(value: Double(p.processed), total: max(1, Double(p.total)))
                HStack(spacing: 16) {
                    Label("\(p.processed)/\(p.total)", systemImage: "photo")
                    Label("\(p.failed)", systemImage: "exclamationmark.triangle")
                    Label(etaText(p.estimatedRemainingMs), systemImage: "clock")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
    private func etaText(_ ms: Int) -> String {
        let secs = ms / 1000
        if secs >= 60 {
            return "\(secs / 60)m"
        }
        return "\(secs)s"
    }
}

// MARK: 3) 会话控制条
struct ScanControlRow: View {
    @ObservedObject var vm: TagScanViewModel
    var body: some View {
        let state = vm.progress?.state ?? .idle
        HStack {
            switch state {
            case .running:
                Button("scan_action_pause") { vm.pause() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("scan_pause")
            case .paused:
                Button("scan_action_resume") { vm.resume() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("scan_resume")
            default:
                EmptyView()
            }
            if state == .running || state == .paused {
                Button("scan_action_cancel") { vm.cancel() }
                    .buttonStyle(.bordered)
            }
            if (vm.progress?.failed ?? 0) > 0 {
                Button("scan_action_retry") { vm.retryFailed() }
                    .buttonStyle(.bordered)
            }
        }
    }
}

// MARK: 4) 统计卡
struct ScanStatsCard: View {
    let stats: ScanDbStats
    private var rows: [(String, Int)] {
        [
            ("scan_stat_total", stats.totalMedia),
            ("scan_stat_with_face", stats.withFace),
            ("scan_stat_with_labels", stats.withLabels),
            ("scan_stat_with_semantic", stats.withSemantic),
            ("scan_stat_person_count", stats.personCount),
            ("scan_stat_embedding_count", stats.faceEmbeddingCount),
            ("scan_stat_remaining_pass1", stats.remainingPass1),
            ("scan_stat_remaining_pass3", stats.remainingPass3)
        ]
    }
    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
            ForEach(rows, id: \.0) { row in
                statCell(row.0, row.1)
            }
        }
    }
    private func statCell(_ key: String, _ value: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(.title3).bold()
            Text(LocalizedStringKey(key)).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: 5) 管线概览
struct ScanPipelineOverview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            pipelineRow("scan_pipeline_face", enabled: true)
            pipelineRow("scan_pipeline_cluster", enabled: false)
            pipelineRow("scan_pipeline_content", enabled: false)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
    private func pipelineRow(_ key: String, enabled: Bool) -> some View {
        HStack {
            Image(systemName: enabled ? "checkmark.circle.fill" : "hourglass")
                .foregroundStyle(enabled ? .green : .secondary)
            Text(key)
            if !enabled {
                Text("scan_pipeline_coming")
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(.secondary.opacity(0.15))
                    .clipShape(Capsule())
            }
            Spacer()
        }
    }
}

// MARK: 6) 快捷操作（idle）
struct ScanQuickActions: View {
    @ObservedObject var vm: TagScanViewModel
    private var idle: Bool {
        (vm.progress?.state ?? .idle) == .idle && !vm.hasUnfinishedSession
    }
    var body: some View {
        Group {
            if idle {
                HStack {
                    Button("scan_action_scan_full") { vm.startFull() }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("scan_start_full")
                    Button("scan_action_scan_incremental") { vm.startIncremental() }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("scan_start_incremental")
                }
            }
        }
    }
}

// MARK: 7) PassControlCard + section
struct PassControlCard: View {
    let titleKey: LocalizedStringKey
    let enabled: Bool
    var onIncremental: () -> Void
    var onFull: () -> Void
    var onDisabled: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(titleKey).font(.subheadline).bold()
                if !enabled {
                    Text("scan_coming_soon_badge")
                        .font(.caption2)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.secondary.opacity(0.2))
                        .clipShape(Capsule())
                }
            }
            HStack {
                actionBtn("scan_action_incremental", isFull: false)
                actionBtn("scan_action_full", isFull: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(enabled ? Color(.secondarySystemBackground) : Color.gray.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .opacity(enabled ? 1 : 0.6)
    }
    private func actionBtn(_ key: LocalizedStringKey, isFull: Bool) -> some View {
        Button(action: {
            guard enabled else { onDisabled(); return }
            isFull ? onFull() : onIncremental()
        }) {
            Text(key).font(.caption)
        }
        .buttonStyle(.bordered)
        .disabled(!enabled)
    }
}

struct ScanPassControlSection: View {
    @ObservedObject var vm: TagScanViewModel
    var onDisabled: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("scan_pass_section_title").font(.caption).foregroundStyle(.secondary)
            PassControlCard(titleKey: "scan_pass1_title", enabled: true,
                            onIncremental: { vm.startIncremental() }, onFull: { vm.startFull() },
                            onDisabled: onDisabled)
            PassControlCard(titleKey: "scan_pass2_title", enabled: false,
                            onIncremental: {}, onFull: {}, onDisabled: onDisabled)
            PassControlCard(titleKey: "scan_pass3_title", enabled: false,
                            onIncremental: {}, onFull: {}, onDisabled: onDisabled)
            PassControlCard(titleKey: "scan_aesthetic_title", enabled: false,
                            onIncremental: {}, onFull: {}, onDisabled: onDisabled)
        }
    }
}

// MARK: 8) 精细控制
struct ScanFineControlSection: View {
    var onDisabled: () -> Void
    private let categories: [LocalizedStringKey] = [
        "scan_cat_face", "scan_cat_scene", "scan_cat_activity",
        "scan_cat_objects", "scan_cat_tags", "scan_cat_summary"
    ]
    private let ranges: [LocalizedStringKey] = [
        "scan_range_all", "scan_range_7d", "scan_range_30d", "scan_range_90d"
    ]
    @State private var fullRegenerate = false
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("scan_fine_control_title").font(.caption).foregroundStyle(.secondary)
            FlowChips(items: categories, enabledIndices: [0], onDisabled: onDisabled)
            Divider()
            HStack {
                ForEach(ranges.indices, id: \.self) { i in
                    Button(ranges[i]) { if i != 0 { onDisabled() } }
                        .buttonStyle(.bordered)
                        .disabled(i != 0)
                }
            }
            Toggle("scan_full_regenerate", isOn: $fullRegenerate)
                .disabled(true)
                .onTapGesture { onDisabled() }
            Button("scan_regenerate_selected") { onDisabled() }
                .buttonStyle(.bordered)
                .disabled(true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

// MARK: - FlowChips（分类 chips 流式布局）
struct FlowChips: View {
    let items: [LocalizedStringKey]
    let enabledIndices: Set<Int>
    var onDisabled: () -> Void
    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items.indices, id: \.self) { i in
                let enabled = enabledIndices.contains(i)
                Text(items[i])
                    .font(.caption)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(enabled ? Color.accentColor.opacity(0.15) : Color.gray.opacity(0.12))
                    .clipShape(Capsule())
                    .foregroundStyle(enabled ? Color.primary : Color.secondary)
                    .onTapGesture { if !enabled { onDisabled() } }
            }
        }
    }
}

// 注：FlowLayout 复用 ChatView.swift 中已有的实现（init(spacing:)），不在此重复定义。
