import SwiftUI

/// 端侧模型下载中心（对标 Android `ModelCenterScreen`）。
///
/// 分类 Tab + 模型卡片 + 进度条 + 一键下载 + 删除。
struct ModelDownloadCenterView: View {
    @StateObject private var manager = ModelDownloadManager.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selectedCategory: ModelCategory = .mustHave

    var body: some View {
        VStack(spacing: 0) {
            // 分类 Tab
            categoryTabs

            // 内容
            ScrollView {
                LazyVStack(spacing: 12) {
                    categoryHeader
                    ForEach(currentModels) { entry in
                        ModelDownloadCard(entry: entry)
                            .environmentObject(manager)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 32)
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(String(localized: "Model Downloads"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { dismiss() } label: {
                    MatIcon(name: "chevron.left", size: 20)
                }
            }
        }
    }

    // MARK: - Category Tabs

    private var categoryTabs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ModelCategory.allCases, id: \.self) { cat in
                    let count = modelsInCategory(cat).count
                    Button {
                        selectedCategory = cat
                    } label: {
                        Text("\(cat.displayName) (\(count))")
                            .font(.system(size: 13, weight: selectedCategory == cat ? .semibold : .regular))
                            .foregroundColor(selectedCategory == cat ? .white : .secondary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(selectedCategory == cat ? Color.accentColor : Color(.tertiarySystemBackground))
                            .clipShape(Capsule())
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background(Color(.systemBackground))
    }

    // MARK: - Category Header

    @ViewBuilder
    private var categoryHeader: some View {
        if selectedCategory == .mustHave && !manager.missingRequiredModels.isEmpty {
            MustHaveHeaderCard()
                .environmentObject(manager)
        }
    }

    // MARK: - Helpers

    private var currentModels: [ModelEntry] {
        modelsInCategory(selectedCategory)
    }

    private func modelsInCategory(_ cat: ModelCategory) -> [ModelEntry] {
        ModelCatalog.shared.models.filter { $0.category == cat }
    }
}

// MARK: - Must-Have Header

private struct MustHaveHeaderCard: View {
    @EnvironmentObject private var manager: ModelDownloadManager

    var body: some View {
        let missing = manager.missingRequiredModels
        let totalSize = manager.missingRequiredSize

        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(matIcon: "download")
                    .font(.system(size: 20))
                    .foregroundColor(.accentColor)
                Text(String(localized: "Download All Required"))
                    .font(.system(size: 15, weight: .semibold))
                Spacer()
            }
            Text("\(missing.count) \(String(localized: "models missing")) · \(ByteCountFormatter.string(fromByteCount: totalSize, countStyle: .file))")
                .font(.system(size: 12))
                .foregroundColor(.secondary)
            Button {
                manager.downloadAllRequired()
            } label: {
                Text(String(localized: "Download All"))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color.accentColor)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(14)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Model Card

private struct ModelDownloadCard: View {
    let entry: ModelEntry
    @EnvironmentObject private var manager: ModelDownloadManager

    private var state: DownloadState? { manager.downloadStates[entry.id] }
    private var isDownloaded: Bool { manager.isModelDownloaded(entry.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // 标题行
            HStack(spacing: 8) {
                Text(entry.name)
                    .font(.system(size: 14, weight: .semibold))
                if entry.isRequired {
                    tierBadge(String(localized: "Required"), color: .red)
                } else if entry.isRecommended {
                    tierBadge(String(localized: "Recommended"), color: .orange)
                }
                if entry.isLightweight {
                    tierBadge(String(localized: "Lite"), color: .green)
                }
            }

            // 描述
            Text(entry.description)
                .font(.system(size: 12))
                .foregroundColor(.secondary)
                .lineLimit(2)

            // 大小
            HStack {
                Image(matIcon: "storage")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                Text(entry.formattedSize)
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                Spacer()
            }

            // 进度条（下载中/暂停）
            if let state, state.status == .downloading || state.status == .paused {
                VStack(spacing: 4) {
                    ProgressView(value: state.progress)
                        .tint(state.status == .paused ? .orange : .accentColor)
                    HStack {
                        Text("\(ByteCountFormatter.string(fromByteCount: state.downloadedBytes, countStyle: .file)) / \(entry.formattedSize)")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                        Spacer()
                        Text(state.status == .paused
                             ? String(localized: "Paused")
                             : "\(Int(state.progress * 100))%")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                    }
                }
            }

            // 操作按钮
            actionBar
        }
        .padding(14)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private var actionBar: some View {
        HStack(spacing: 8) {
            if isDownloaded {
                // 已下载 → 删除按钮
                Button(role: .destructive) {
                    manager.delete(entry.id)
                } label: {
                    labelButton(icon: "delete", text: String(localized: "Delete"), color: .red)
                }
            } else if let state {
                switch state.status {
                case .downloading:
                    Button { manager.pause(entry.id) } label: {
                        labelButton(icon: "pause", text: String(localized: "Pause"), color: .orange)
                    }
                    Button { manager.cancel(entry.id) } label: {
                        labelButton(icon: "close", text: String(localized: "Cancel"), color: .red)
                    }
                case .paused:
                    Button { manager.resume(entry.id) } label: {
                        labelButton(icon: "play_arrow", text: String(localized: "Resume"), color: .accentColor)
                    }
                    Button { manager.cancel(entry.id) } label: {
                        labelButton(icon: "close", text: String(localized: "Cancel"), color: .red)
                    }
                case .failed:
                    Button { manager.download(entry.id) } label: {
                        labelButton(icon: "refresh", text: String(localized: "Retry"), color: .accentColor)
                    }
                default:
                    Button { manager.download(entry.id) } label: {
                        labelButton(icon: "download", text: String(localized: "Download"), color: .accentColor)
                    }
                }
            } else {
                Button { manager.download(entry.id) } label: {
                    labelButton(icon: "download", text: String(localized: "Download"), color: .accentColor)
                }
            }
        }
    }

    private func tierBadge(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 9, weight: .medium))
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
    }

    private func labelButton(icon: String, text: String, color: Color) -> some View {
        HStack(spacing: 4) {
            Image(matIcon: icon).font(.system(size: 14))
            Text(text).font(.system(size: 12, weight: .medium))
        }
        .foregroundColor(color)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(color.opacity(0.1))
        .clipShape(Capsule())
    }
}

#Preview {
    NavigationStack {
        ModelDownloadCenterView()
    }
    .preferredColorScheme(.dark)
}
