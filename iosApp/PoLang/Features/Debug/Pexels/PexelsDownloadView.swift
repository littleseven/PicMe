import SwiftUI

// MARK: - Pexels 测试图片下载页（对标 Android PexelsSection.kt）
//
// #if DEBUG 调试工具：输入 Pexels API key → 搜索/精选 → 多选或批量下载到相册。
// 下载的图保存到 Photos（gallery 与 TAG 扫描自动识别），用于补充测试数据（如聚类验证）。

struct PexelsDownloadView: View {
    @StateObject private var vm = PexelsViewModel()
    @State private var keyInput: String = ""
    @State private var searchText: String = ""
    @State private var batchSize: Int = 20
    private let batchSizes = [10, 20, 50]

    var body: some View {
        Group {
            if vm.state.hasKey {
                mainContent
            } else {
                keyInputCard
            }
        }
        .navigationTitle(L("Pexels Gallery"))
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottom) { if let t = vm.toast { toastView(t) } }
        .task(id: vm.toast) {
            guard vm.toast != nil else { return }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            vm.toast = nil
        }
    }

    // MARK: - key 输入

    private var keyInputCard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if vm.state.invalidKey {
                    Text(L("Invalid API Key, please re-enter"))
                        .foregroundColor(.red)
                        .font(.system(size: 13))
                }
                Text(L("Enter Pexels API Key"))
                    .font(.system(size: 14))
                    .foregroundColor(.secondary)
                TextField("", text: $keyInput)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                Button {
                    vm.saveKey(keyInput)
                    keyInput = ""
                } label: {
                    Text(L("Save"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(keyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Link(L("Get a free key at pexels.com/api"), destination: URL(string: "https://www.pexels.com/api/")!)
                    .font(.system(size: 13))
            }
            .padding(20)
        }
    }

    // MARK: - 主内容：搜索 + 网格 + 下载栏

    private var mainContent: some View {
        VStack(spacing: 8) {
            HStack {
                Text(L("API Key configured"))
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                Spacer()
                Button(L("Change")) { vm.clearKey() }
                    .font(.system(size: 13))
            }
            HStack {
                TextField(L("Search keywords (empty = curated)"), text: $searchText)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.search)
                    .onSubmit { vm.search(searchText) }
                Button { vm.search(searchText) } label: {
                    Image(systemName: "magnifyingglass").font(.system(size: 16))
                }
            }
            contentArea
            Text(L("Photos provided by Pexels"))
                .font(.system(size: 11))
                .foregroundColor(.secondary)
            downloadBar
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var contentArea: some View {
        if vm.state.loading {
            ProgressView().frame(maxWidth: .infinity, minHeight: 200)
        } else if let err = vm.state.error {
            VStack(spacing: 10) {
                Text(err == .rateLimited
                     ? L("Pexels rate limit reached (200/hour), try later")
                     : L("Network error, please retry"))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                Button(L("Retry")) { vm.retry() }
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity, minHeight: 200)
        } else if vm.state.photos.isEmpty {
            Text(L("No results"))
                .foregroundColor(.secondary)
                .frame(maxWidth: .infinity, minHeight: 200)
        } else {
            photoGrid
        }
    }

    private var photoGrid: some View {
        let cols = [GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4)]
        return ScrollView {
            LazyVGrid(columns: cols, spacing: 4) {
                ForEach(vm.state.photos) { photo in
                    cell(photo)
                        .onAppear { maybeLoadMore(reaching: photo, photos: vm.state.photos) }
                }
                if vm.state.loadingMore {
                    ProgressView().frame(maxWidth: .infinity).aspectRatio(1, contentMode: .fit)
                }
            }
            .padding(.bottom, 8)
        }
    }

    private func cell(_ photo: PexelsPhoto) -> some View {
        let selected = vm.state.selectedIds.contains(photo.id)
        return Button { vm.toggleSelect(photo.id) } label: {
            ZStack(alignment: .topTrailing) {
                AsyncImage(url: URL(string: photo.src.medium ?? "")) { phase in
                    switch phase {
                    case .success(let img): img.resizable().scaledToFill()
                    default: Color(.secondarySystemBackground)
                    }
                }
                .frame(maxWidth: .infinity)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .overlay(selected ? Color.accentColor.opacity(0.30) : Color.clear)
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 20))
                        .foregroundColor(.accentColor)
                        .background(Circle().fill(.white))
                        .padding(5)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(String(format: L("Photo by %1$@"), photo.photographer ?? "Pexels")))
    }

    private func maybeLoadMore(reaching photo: PexelsPhoto, photos: [PexelsPhoto]) {
        guard let idx = photos.firstIndex(where: { $0.id == photo.id }) else { return }
        if idx >= photos.count - 6 { vm.loadMore() }
    }

    // MARK: - 下载栏

    private var downloadBar: some View {
        HStack(spacing: 10) {
            Text(vm.state.downloading
                 ? String(format: L("Downloading %1$@"), vm.state.progress)
                 : String(format: L("%1$d selected"), vm.state.selectedIds.count))
                .font(.system(size: 13))
                .lineLimit(1)
            Spacer()
            Button { vm.downloadSelected() } label: {
                Label(L("Download Selected"), systemImage: "arrow.down.to.line")
                    .font(.system(size: 13))
            }
            .buttonStyle(.bordered)
            .disabled(vm.state.selectedIds.isEmpty || vm.state.downloading)
            Menu {
                ForEach(batchSizes, id: \.self) { n in
                    Button(String(format: L("Batch %1$d"), n)) { batchSize = n }
                }
            } label: {
                Button { vm.downloadBatch(batchSize) } label: {
                    Text(String(format: L("Batch %1$d"), batchSize))
                        .font(.system(size: 13))
                }
                .buttonStyle(.borderedProminent)
                .disabled(vm.state.downloading)
            }
        }
    }

    private func toastView(_ msg: String) -> some View {
        Text(msg)
            .font(.system(size: 13))
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(Color.black.opacity(0.8)))
            .padding(.bottom, 24)
            .transition(.opacity)
    }
}
