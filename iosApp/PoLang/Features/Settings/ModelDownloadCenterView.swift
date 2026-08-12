import SwiftUI

/// 端侧模型下载中心（1:1 对标 Android `ModelCenterScreen`）。
///
/// 结构：分类 Tab（横向滚动 chip）+ Tab 内容（头部卡片 + 模型卡片列表）。
/// 模型可出现在多个 Tab（如 must-have 同时在 beauty-camera）。
struct ModelDownloadCenterView: View {
    @StateObject private var manager = ModelDownloadManager.shared
    @Environment(\.dismiss) private var dismiss

    /// 非空分类 Tab（按固定顺序）
    private var visibleCategories: [(ModelCategory, [ModelEntry])] {
        ModelCatalog.shared.groupedByCategory()
    }

    var body: some View {
        VStack(spacing: 0) {
            if visibleCategories.isEmpty {
                emptyState
            } else {
                TabView(selection: $manager.selectedCategory) {
                    ForEach(visibleCategories, id: \.0) { cat, models in
                        categoryPage(cat, models: models)
                            .tag(cat)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .overlay(alignment: .top) {
                    categoryTabBar
                }
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(L("Model Center"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // 确保目录加载后刷新已下载状态（修复 init 时序问题）
            manager.refreshStates()
        }
    }

    // MARK: - Category Tab Bar

    private var categoryTabBar: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(visibleCategories.map { $0.0 }, id: \.self) { cat in
                        let isSelected = manager.selectedCategory == cat
                        Button {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                manager.selectedCategory = cat
                            }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: cat.iconSystemName)
                                    .font(.system(size: 14))
                                Text(cat.displayName)
                                    .font(.system(size: 14, weight: isSelected ? .semibold : .regular))
                            }
                            .foregroundColor(isSelected ? .white : .secondary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(isSelected ? Color.accentColor : Color(.tertiarySystemBackground))
                            .clipShape(Capsule())
                            .shadow(color: isSelected ? .black.opacity(0.15) : .clear, radius: 2, y: 1)
                        }
                        .id(cat)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .background(Color(.systemBackground))
            .onChange(of: manager.selectedCategory) { newCat in
                withAnimation { proxy.scrollTo(newCat, anchor: .center) }
            }
        }
    }

    // MARK: - Category Page

    private func categoryPage(_ cat: ModelCategory, models: [ModelEntry]) -> some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                // 头部卡片
                if cat == .mustHave {
                    MustHaveHeaderCard()
                        .environmentObject(manager)
                }
                // 模型卡片
                ForEach(models) { entry in
                    ModelDownloadCard(entry: entry)
                        .environmentObject(manager)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 56)  // 留出 Tab Bar 空间
            .padding(.bottom, 32)
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "cpu")
                .font(.system(size: 64))
                .foregroundColor(.secondary.opacity(0.4))
            Text(L("No models available"))
                .font(.system(size: 16))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Must-Have Header Card

private struct MustHaveHeaderCard: View {
    @EnvironmentObject private var manager: ModelDownloadManager

    var body: some View {
        let required = ModelCatalog.shared.models.filter { $0.isRequired }
        let missing = required.filter { !manager.isModelDownloaded($0.id) }

        VStack(alignment: .leading, spacing: 4) {
            Text(L("Must Have"))
                .font(.system(size: 16, weight: .semibold))

            Text("\(required.count) \(L("required models,")) \(missing.count) \(L("not downloaded"))")
                .font(.system(size: 13))
                .foregroundColor(.secondary)

            if !missing.isEmpty {
                HStack {
                    Spacer()
                    Button {
                        manager.downloadAllRequired()
                    } label: {
                        HStack(spacing: 6) {
                            Image(matIcon: "download").font(.system(size: 16))
                            Text(L("Download Missing"))
                                .font(.system(size: 13, weight: .semibold))
                        }
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.accentColor)
                        .clipShape(Capsule())
                    }
                }
                .padding(.top, 8)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Model Download Card

private struct ModelDownloadCard: View {
    let entry: ModelEntry
    @EnvironmentObject private var manager: ModelDownloadManager
    @State private var showDeleteConfirm = false
    @State private var showProperties = false

    private var state: DownloadState? { manager.downloadStates[entry.id] }
    private var isDownloaded: Bool {
        // 优先用 state 判断（避免文件系统时序问题）
        if state?.status == .completed { return true }
        return manager.isModelDownloaded(entry.id)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 上部：信息 + 操作按钮
            HStack(alignment: .top, spacing: 10) {
                // 左侧信息列
                VStack(alignment: .leading, spacing: 4) {
                    // 名称 + Tag Badge
                    HStack(spacing: 8) {
                        Text(entry.name)
                            .font(.system(size: 15, weight: .semibold))
                            .lineLimit(1)
                        TagBadge(tag: entry.primaryTag)
                    }
                    // 描述
                    Text(entry.description)
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                    // 信息行
                    HStack(spacing: 8) {
                        Text(entry.formattedSize)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.accentColor)
                        if entry.isLightweight {
                            LightweightBadge()
                        }
                        if entry.isRequired {
                            RequiredBadge()
                        }
                    }
                    .padding(.top, 2)
                }
                Spacer(minLength: 8)

                // 右侧操作按钮
                actionColumn
            }

            // 进度条（下载中 / 暂停 / 失败）
            if let state, state.status == .downloading || state.status == .paused {
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: max(0.001, state.progress))
                        .tint(state.status == .paused ? .secondary : .accentColor)
                    HStack {
                        if state.status == .paused {
                            Text(L("Pause"))
                                .font(.system(size: 11))
                                .foregroundColor(.secondary)
                        } else if manager.reconnectingModels.contains(entry.id) {
                            HStack(spacing: 4) {
                                ProgressView()
                                    .scaleEffect(0.5)
                                Text("\(L("Reconnecting…")) \(min(manager.stallAttempts[entry.id] ?? 1, 3))/3")
                                    .font(.system(size: 11))
                                    .foregroundColor(.orange)
                            }
                        }
                        Spacer()
                        Text("\(Int(state.progress * 100))%")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.top, 10)
            }
            if let state, state.status == .failed {
                Text(L("Download failed. Please try again."))
                    .font(.system(size: 11))
                    .foregroundColor(.red)
                    .padding(.top, 6)
            }
        }
        .padding(14)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onLongPressGesture {
            showProperties = true
        }
        .confirmationDialog(
            L("Delete model?"),
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button(L("Delete"), role: .destructive) {
                manager.delete(entry.id)
            }
            Button(L("Cancel"), role: .cancel) {}
        } message: {
            Text(L("Are you sure you want to delete") + " \(entry.name)?")
        }
        .sheet(isPresented: $showProperties) {
            ModelPropertiesSheet(entry: entry)
        }
    }

    // MARK: - Action Column

    @ViewBuilder
    private var actionColumn: some View {
        VStack(spacing: 4) {
            // 主操作按钮（按状态切换）
            if isDownloaded && state?.status != .downloading && state?.status != .paused {
                // 已下载 → Check 图标
                Button { showDeleteConfirm = true } label: {
                    Image(matIcon: "check")
                        .font(.system(size: 22))
                        .foregroundColor(.accentColor)
                }
                .frame(width: 36, height: 36)
            } else if let state {
                switch state.status {
                case .downloading:
                    HStack(spacing: 2) {
                        Button { manager.pause(entry.id) } label: {
                            Image(matIcon: "pause")
                                .font(.system(size: 18))
                                .foregroundColor(.accentColor)
                        }
                        .frame(width: 32, height: 32)
                        Button { manager.cancel(entry.id) } label: {
                            Image(matIcon: "close")
                                .font(.system(size: 18))
                                .foregroundColor(.red)
                        }
                        .frame(width: 32, height: 32)
                    }
                case .paused:
                    Button { manager.resume(entry.id) } label: {
                        Image(matIcon: "play_arrow")
                            .font(.system(size: 22))
                            .foregroundColor(.accentColor)
                    }
                    .frame(width: 36, height: 36)
                case .failed:
                    Button { manager.download(entry.id) } label: {
                        Image(matIcon: "download")
                            .font(.system(size: 20))
                            .foregroundColor(.accentColor)
                    }
                    .frame(width: 36, height: 36)
                default:
                    downloadButton
                }
            } else {
                downloadButton
            }

            // 删除按钮（始终显示）
            Button { showDeleteConfirm = true } label: {
                Image(matIcon: "delete")
                    .font(.system(size: 18))
                    .foregroundColor(.red.opacity(0.6))
            }
            .frame(width: 36, height: 36)
        }
    }

    private var downloadButton: some View {
        Button { manager.download(entry.id) } label: {
            Image(matIcon: "download")
                .font(.system(size: 20))
                .foregroundColor(.accentColor)
        }
        .frame(width: 36, height: 36)
    }
}

// MARK: - Badges

private struct TagBadge: View {
    let tag: String

    private var color: Color {
        Color(rgb: ModelEntry.tagColorHex(tag))
    }
    private var label: String {
        ModelEntry.tagDisplayName(tag)
    }

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
            Text(label)
                .font(.system(size: 10))
                .foregroundColor(color)
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(color.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

private struct LightweightBadge: View {
    var body: some View {
        Text(L("Lightweight"))
            .font(.system(size: 10))
            .foregroundColor(.secondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color(.tertiarySystemBackground).opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: 4))
    }
}

private struct RequiredBadge: View {
    var body: some View {
        Text(L("Must Have"))
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color(rgb: 0xE53935))
            .clipShape(RoundedRectangle(cornerRadius: 4))
    }
}

// MARK: - Properties Sheet

private struct ModelPropertiesSheet: View {
    let entry: ModelEntry
    @Environment(\.dismiss) private var dismiss

    private var jsonText: String {
        let dict: [String: Any] = [
            "id": entry.id,
            "name": entry.name,
            "description": entry.description,
            "size": entry.size,
            "sizeFormatted": entry.formattedSize,
            "tags": entry.tags,
            "files": entry.files,
            "sources": entry.sources,
            "isSmallModel": entry.isLightweight,
        ]
        let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys])
        return String(data: data ?? Data(), encoding: .utf8) ?? ""
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(jsonText)
                    .font(.system(size: 12, design: .monospaced))
                    .padding(16)
            }
            .navigationTitle(L("Model Properties"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L("Close")) { dismiss() }
                }
            }
        }
    }
}

// MARK: - Color Extension

extension Color {
    init(rgb: UInt32) {
        self.init(
            .sRGB,
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255,
            opacity: 1
        )
    }
}

#Preview {
    NavigationStack {
        ModelDownloadCenterView()
    }
}
