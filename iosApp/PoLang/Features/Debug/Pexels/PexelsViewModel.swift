import Foundation
import UIKit
import Photos

// MARK: - Pexels 页状态与下载管线（对标 Android PexelsViewModel + SampleDataGenerator）

struct PexelsUiState: Equatable {
    var hasKey: Bool = false
    var invalidKey: Bool = false          // 上一把 key 被 401 拒
    var photos: [PexelsPhoto] = []
    var selectedIds: Set<Int64> = []
    var page: Int = 1
    var endReached: Bool = false
    var loading: Bool = false             // 首屏加载
    var loadingMore: Bool = false         // 翻页
    var error: PexelsError? = nil
    var downloading: Bool = false
    var progress: String = ""             // "i/N"
}

@MainActor
final class PexelsViewModel: ObservableObject {

    @Published private(set) var state = PexelsUiState()
    @Published var toast: String?

    private static let keyUD = "pexels_api_key"
    private var apiKey: String?
    private var currentQuery: String?

    init() {
        let key = UserDefaults.standard.string(forKey: Self.keyUD)
        apiKey = key
        state.hasKey = (key != nil)
        if key != nil { loadCurated() }
    }

    // MARK: - key

    func saveKey(_ key: String) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        UserDefaults.standard.set(trimmed, forKey: Self.keyUD)
        apiKey = trimmed
        state.hasKey = true
        state.invalidKey = false
        loadCurated()
    }

    func clearKey() {
        UserDefaults.standard.removeObject(forKey: Self.keyUD)
        apiKey = nil
        currentQuery = nil
        state = PexelsUiState()   // 回到 noKey
    }

    // MARK: - 加载

    func loadCurated() {
        currentQuery = nil
        loadFirst()
    }

    func search(_ query: String) {
        currentQuery = query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : query.trimmingCharacters(in: .whitespacesAndNewlines)
        loadFirst()
    }

    func retry() { loadFirst() }

    private func loadFirst() {
        guard let key = apiKey else { state.hasKey = false; return }
        state.loading = true
        state.error = nil
        state.photos = []
        state.selectedIds = []
        state.page = 1
        state.endReached = false
        let query = currentQuery
        Task { [weak self] in
            guard let self else { return }
            do {
                let resp = try await self.fetch(query: query, page: 1, key: key)
                self.applyFirstPage(resp)
            } catch let e as PexelsError {
                self.handle(error: e, firstPage: true)
            } catch {
                self.handle(error: .network, firstPage: true)
            }
        }
    }

    func loadMore() {
        guard !state.loadingMore, !state.endReached, !state.downloading, let key = apiKey else { return }
        state.loadingMore = true
        let next = state.page + 1
        let query = currentQuery
        Task { [weak self] in
            guard let self else { return }
            do {
                let resp = try await self.fetch(query: query, page: next, key: key)
                self.state.photos.append(contentsOf: resp.photos ?? [])
                self.state.page = next
                self.state.endReached = (resp.nextPage?.isEmpty ?? true) || (resp.photos?.isEmpty ?? true)
            } catch {
                // 翻页失败保留已加载内容
            }
            self.state.loadingMore = false
        }
    }

    private func fetch(query: String?, page: Int, key: String) async throws -> PexelsResponse {
        if let q = query { return try await PexelsApi.search(query: q, page: page, apiKey: key) }
        return try await PexelsApi.curated(page: page, apiKey: key)
    }

    private func applyFirstPage(_ resp: PexelsResponse) {
        state.loading = false
        state.photos = resp.photos ?? []
        state.page = 1
        state.endReached = (resp.nextPage?.isEmpty ?? true) || (resp.photos?.isEmpty ?? true)
    }

    private func handle(error: PexelsError, firstPage: Bool) {
        state.loading = false
        state.loadingMore = false
        switch error {
        case .unauthorized:
            // 401：清 key 回到输入态，标记上次无效
            apiKey = nil
            UserDefaults.standard.removeObject(forKey: Self.keyUD)
            state = PexelsUiState(invalidKey: true)
        case .rateLimited, .network:
            state.error = error
        }
    }

    // MARK: - 选择

    func toggleSelect(_ id: Int64) {
        if state.selectedIds.contains(id) { state.selectedIds.remove(id) }
        else { state.selectedIds.insert(id) }
    }

    // MARK: - 下载（顺序，省额度 + 清晰进度）

    func downloadSelected() {
        let targets = state.photos.filter { state.selectedIds.contains($0.id) }
        guard !targets.isEmpty, !state.downloading else { return }
        Task { await runDownload(targets) }
    }

    /// 批量下载前 count 张；当前列表不足时自动翻页凑齐。
    func downloadBatch(_ count: Int) {
        guard !state.downloading else { return }
        Task { [weak self] in
            guard let self else { return }
            var all = self.state.photos
            var page = self.state.page
            while all.count < count && !self.state.endReached {
                page += 1
                guard let key = self.apiKey,
                      let resp = try? await self.fetch(query: self.currentQuery, page: page, key: key) else { break }
                all.append(contentsOf: resp.photos ?? [])
                if (resp.photos?.isEmpty ?? true) || (resp.nextPage?.isEmpty ?? true) {
                    self.state.endReached = true
                    break
                }
            }
            await self.runDownload(Array(all.prefix(count)))
        }
    }

    private func runDownload(_ targets: [PexelsPhoto]) async {
        state.downloading = true
        state.progress = "0/\(targets.count)"
        // 保存前确保 AddOnly 授权（对标 ShutterButton；被拒直接提示，不静默失败）
        guard await Self.ensureAddPermission() else {
            state.downloading = false
            state.progress = ""
            toast = L("Photos add permission denied")
            return
        }
        var success = 0
        for (i, photo) in targets.enumerated() {
            state.progress = "\(i + 1)/\(targets.count)"
            guard let urlStr = photo.src.large2x, let image = await downloadImage(urlStr) else { continue }
            if await saveToPhotos(image, creationDate: Self.randomDateLast180Days()) {
                success += 1
            }
        }
        state.downloading = false
        state.progress = ""
        state.selectedIds = []
        toast = String(format: L("Done: %1$d/%2$d saved"), success, targets.count)
    }

    /// 确保相册 AddOnly 授权（对标 ShutterButton：notDetermined 触发系统弹窗，被拒返回 false）。
    private static func ensureAddPermission() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return status == .authorized || status == .limited
    }

    // MARK: - 下载 + 存相册

    /// 下载原图字节，校验 ≥5KB（防占位/错误页），返回 UIImage。
    private func downloadImage(_ urlStr: String) async -> UIImage? {
        guard let url = URL(string: urlStr) else { return nil }
        do {
            let (data, resp) = try await URLSession.shared.data(from: url)
            guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  data.count >= 5120 else { return nil }
            return UIImage(data: data)
        } catch {
            return nil
        }
    }

    /// 存入 Photos，creationDate 设为最近 180 天内随机一天（对标 Android 伪拍摄时间）。
    private func saveToPhotos(_ image: UIImage, creationDate: Date) async -> Bool {
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let req = PHAssetChangeRequest.creationRequestForAsset(from: image)
                req.creationDate = creationDate
            }
            return true
        } catch {
            NSLog("PoLang:Pexels save to Photos failed: \(error)")
            return false
        }
    }

    private static func randomDateLast180Days() -> Date {
        Date().addingTimeInterval(-Double.random(in: 0...(180 * 86400)))
    }
}
