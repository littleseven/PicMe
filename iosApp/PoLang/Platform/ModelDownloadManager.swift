import Foundation
import CryptoKit
import Combine

/// 端侧模型下载管理器（对齐 Android `LlmModelDownloadManager`）。
///
/// 从 ModelScope CDN 下载端侧 AI 模型（MNN/ONNX），SHA256 校验，
/// 支持 暂停/恢复/取消/删除，进度实时上报。
///
/// 下载核心：
/// - 经典 `URLSessionDownloadTask` + `DownloadTaskHub` delegate 拿实时进度。
///   ⚠️ 不可用 async/await `session.download(for:)`——实测其完全不回调
///   `didWriteData`（含 per-task delegate 变体），进度恒为 0（旧「进度条不动」根因）。
/// - 大文件（> 32MB）走 `ParallelFileDownloader` 分块并行（对齐 Android），
///   chunk 级断点续传（`.part` + `.part.meta`）。
/// - 暂停/取消/删除会真正 cancel 系统任务——旧实现只置标志位，僵尸任务继续
///   占满带宽跑到完再丢弃，是「越下越慢」的主因之一。
///
/// 存储路径：`Documents/llm_models/<modelId>/`

// MARK: - Types

struct ModelFileInfo: Codable {
    let name: String
    let size: Int64
    let sha256: String
}

enum DownloadStatus: Equatable {
    case pending
    case downloading
    case paused
    case completed
    case failed
    case cancelled
}

struct DownloadState: Equatable {
    let modelId: String
    var downloadedBytes: Int64
    var totalBytes: Int64
    var status: DownloadStatus

    var progress: Double {
        guard totalBytes > 0 else { return 0 }
        return Double(downloadedBytes) / Double(totalBytes)
    }
}

// MARK: - Manager

@MainActor
final class ModelDownloadManager: ObservableObject {
    static let shared = ModelDownloadManager()

    @Published private(set) var downloadStates: [String: DownloadState] = [:]
    @Published var selectedCategory: ModelCategory = .mustHave

    var modelsDir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("llm_models")
    }

    private var cancelledModels: Set<String> = []
    /// 单文件下载中的系统任务（按 modelId）——暂停/取消/删除时真正 cancel
    private var activeTasks: [String: [URLSessionDownloadTask]] = [:]
    /// 分块并行下载器（按 modelId）——暂停/取消/删除时 cancelAll
    private var activeDownloaders: [String: ParallelFileDownloader] = [:]
    /// 进度节流：每个 modelId 的上次上报时间
    private var lastProgressReport: [String: Date] = [:]
    /// 字节到达时间戳（每次 didWriteData 刷新，throttle 之前）——stall 看门狗的判据
    private var lastByteReceivedAt: [String: Date] = [:]
    /// 正在 stall 重连中的模型（驱动 UI「重连中…」提示）
    @Published private(set) var reconnectingModels: Set<String> = []

    /// stall 看门狗：无字节超过此阈值 → cancel 当前 chunk 重连（外科手术式，不重启整个下载）
    private static let stallThreshold: TimeInterval = 12
    /// 看门狗检查间隔
    private static let stallCheckInterval: TimeInterval = 5
    /// 单文件连续 stall 重连上限（超过 → 真失败）
    private static let maxStallRestarts = 5

    private init() {
        refreshAllStates()
    }

    // MARK: - 查询

    func isModelDownloaded(_ modelId: String) -> Bool {
        guard let entry = ModelCatalog.shared.model(byId: modelId) else { return false }
        let dir = modelsDir.appendingPathComponent(modelId)
        // 只认最终文件名；下载中的 `.part` 不算已下载
        return entry.files.allSatisfy { FileManager.default.fileExists(atPath: dir.appendingPathComponent($0).path) }
    }

    var downloadedModelIds: Set<String> {
        Set(ModelCatalog.shared.models.filter { isModelDownloaded($0.id) }.map { $0.id })
    }

    // MARK: - 下载控制

    func download(_ modelId: String) {
        guard let entry = ModelCatalog.shared.model(byId: modelId) else { return }
        guard let repo = entry.modelScopeRepo else { return }
        // 防重复启动（旧实现会并发起两个任务互抢带宽）
        guard downloadStates[modelId]?.status != .downloading else { return }

        cancelledModels.remove(modelId)
        downloadStates[modelId] = DownloadState(
            modelId: modelId, downloadedBytes: 0, totalBytes: entry.size, status: .downloading)

        Task {
            await performDownload(entry: entry, repo: repo)
        }
    }

    func pause(_ modelId: String) {
        cancelledModels.insert(modelId)
        cancelActiveWork(modelId)
        updateStatus(modelId, .paused)
    }

    func resume(_ modelId: String) {
        guard downloadStates[modelId]?.status == .paused else { return }
        download(modelId)
    }

    func cancel(_ modelId: String) {
        cancelledModels.insert(modelId)
        cancelActiveWork(modelId)
        // 不删文件——保留已下载部分（含 `.part`）供下次续传
        downloadStates.removeValue(forKey: modelId)
    }

    func delete(_ modelId: String) {
        cancelledModels.insert(modelId)
        cancelActiveWork(modelId)
        let dir = modelsDir.appendingPathComponent(modelId)
        try? FileManager.default.removeItem(at: dir)
        downloadStates.removeValue(forKey: modelId)
    }

    func downloadAllRequired() {
        for entry in ModelCatalog.shared.models where entry.isRequired && !isModelDownloaded(entry.id) {
            if downloadStates[entry.id]?.status != .downloading {
                download(entry.id)
            }
        }
    }

    // MARK: - Core Download Logic

    private func performDownload(entry: ModelEntry, repo: String) async {
        let modelId = entry.id
        let modelDir = modelsDir.appendingPathComponent(modelId)
        try? FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)

        let fileInfos = await fetchFileList(repo: repo)
        let fileInfoMap = Dictionary(uniqueKeysWithValues: fileInfos.map { ($0.name, $0) })

        // 与 Android 对齐：总大小以 API 文件清单为准（≥ entry.size），
        // 避免目录大小与实际不符导致进度提前 100% 或永远到不了 100%
        let actualTotal = max(
            entry.files.reduce(Int64(0)) { $0 + (fileInfoMap[$1]?.size ?? 0) },
            entry.size
        )
        updateTotalBytes(modelId, actualTotal)

        var totalDownloaded: Int64 = 0

        for fileName in entry.files {
            if cancelledModels.contains(modelId) { return }

            let destUrl = modelDir.appendingPathComponent(fileName)
            let info = fileInfoMap[fileName]

            // 已存在 → 校验跳过（大文件 SHA256 流式计算，放后台线程避免卡 UI）
            if FileManager.default.fileExists(atPath: destUrl.path) {
                let verified = await Task.detached(priority: .utility) {
                    Self.verifyExistingFile(destUrl, info: info)
                }.value
                if verified {
                    totalDownloaded += Self.fileSize(of: destUrl)
                    updateProgress(modelId, totalDownloaded)
                    continue
                }
                try? FileManager.default.removeItem(at: destUrl)
            }

            let url = URL(string: "https://modelscope.cn/models/\(repo)/resolve/master/\(fileName)")!
            let expectedSize = info?.size ?? 0

            do {
                if expectedSize > ParallelFileDownloader.parallelThreshold {
                    // 大文件：分块并行（单连接受 CDN 单流限速，多段并发提速）
                    let base = totalDownloaded
                    try await downloadFileParallel(
                        url: url, fileName: fileName, modelDir: modelDir,
                        expectedSize: expectedSize, expectedSha256: info?.sha256,
                        modelId: modelId, base: base)
                    totalDownloaded += expectedSize
                } else {
                    let base = totalDownloaded
                    let downloaded = try await downloadFileSingle(
                        url: url, destUrl: destUrl, modelId: modelId, base: base)
                    totalDownloaded += downloaded
                }
                if cancelledModels.contains(modelId) { return }
                updateProgress(modelId, totalDownloaded)
            } catch {
                if !cancelledModels.contains(modelId) {
                    updateStatus(modelId, .failed)
                }
                cancelActiveWork(modelId)
                return
            }
        }

        if !cancelledModels.contains(modelId) {
            updateStatus(modelId, .completed)
        }
        cancelActiveWork(modelId)
    }

    /// 单连接下载（小文件）。经典 downloadTask + delegate 实时进度。
    private func downloadFileSingle(
        url: URL, destUrl: URL, modelId: String, base: Int64
    ) async throws -> Int64 {
        var request = URLRequest(url: url)
        request.timeoutInterval = 120
        request.setValue("PoLang-iOS/1.0", forHTTPHeaderField: "User-Agent")

        // 外科手术式 stall 重试（单文件无续传，重试从头下；<32MB 可接受）
        var attempt = 0
        let tempUrl: URL
        let response: HTTPURLResponse?
        while true {
            if cancelledModels.contains(modelId) { return 0 }
            lastByteReceivedAt[modelId] = Date()  // grace：覆盖首连接
            let attemptNow = attempt  // Task 闭包按值捕获

            // stall 看门狗：无字节 > 阈值 → cancel 当前 task 让 continuation 抛错 → 本循环重试
            let stallWatchdog = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: UInt64(Self.stallCheckInterval * 1_000_000_000))
                    guard let self else { return }
                    guard self.downloadStates[modelId]?.status == .downloading else { return }
                    if let last = self.lastByteReceivedAt[modelId],
                       Date().timeIntervalSince(last) > Self.stallThreshold {
                        NSLog("PoLang:ModelDownload stall restart modelId=%@ singleFile attempt=%d",
                              modelId, attemptNow)
                        self.setReconnecting(modelId, true)
                        self.activeTasks[modelId]?.forEach { $0.cancel() }
                        return
                    }
                }
            }

            do {
                let result = try await withCheckedThrowingContinuation {
                    (cont: CheckedContinuation<(tempFile: URL, response: HTTPURLResponse?), Error>) in
                    let task = DownloadTaskHub.shared.startTask(
                        with: request,
                        onProgress: { written in
                            DispatchQueue.main.async { [weak self] in
                                self?.reportProgressThrottled(modelId, base + written)
                            }
                        },
                        onCompletion: { cont.resume(with: $0) }
                    )
                    // continuation body 同步执行且继承 MainActor 隔离，append 与 cancelActiveWork 无并发
                    activeTasks[modelId, default: []].append(task)
                }
                stallWatchdog.cancel()
                setReconnecting(modelId, false)
                tempUrl = result.tempFile
                response = result.response
                break
            } catch {
                stallWatchdog.cancel()
                if cancelledModels.contains(modelId) {
                    setReconnecting(modelId, false)
                    throw error
                }
                attempt += 1
                let isStallCancel = (error as? URLError)?.code == .cancelled || error is CancellationError
                if !isStallCancel || attempt > Self.maxStallRestarts {
                    setReconnecting(modelId, false)
                    if isStallCancel {
                        NSLog("PoLang:ModelDownload give up modelId=%@ singleFile reason=stallRestartLimit",
                              modelId)
                    }
                    throw error
                }
                // stall 取消 → loop 重试（从头下）
            }
        }

        defer { try? FileManager.default.removeItem(at: tempUrl) }

        let statusCode = response?.statusCode ?? 0
        guard statusCode == 200 else { throw URLError(.badServerResponse) }

        if cancelledModels.contains(modelId) { return 0 }

        if FileManager.default.fileExists(atPath: destUrl.path) {
            try FileManager.default.removeItem(at: destUrl)
        }
        try FileManager.default.moveItem(at: tempUrl, to: destUrl)
        return Self.fileSize(of: destUrl)
    }

    /// 大文件分块并行下载（`.part` 暂存，完成后转正；`.part.meta` 记录已完成 chunk 供续传）。
    /// 完成后若 API 提供了 SHA256 立即流式校验（并发偏移写错的防线，不等下次启动才发现）。
    private func downloadFileParallel(
        url: URL, fileName: String, modelDir: URL,
        expectedSize: Int64, expectedSha256: String?, modelId: String, base: Int64
    ) async throws {
        let partUrl = modelDir.appendingPathComponent(fileName + ".part")

        // 外科手术式 stall 重试：只在卡住的 chunk 上重连（.part.meta 续传剩余 chunk），
        // 不重启整个 performDownload——避免重新遍历文件列表 + 重新校验已完成文件造成的长冻住与进度回跳。
        var attempt = 0
        while true {
            if cancelledModels.contains(modelId) { return }  // 保留 .part/.meta 供续传

            let downloader = ParallelFileDownloader()
            activeDownloaders[modelId] = downloader
            // 身份守卫清理：重试下一轮会换新 downloader，只在自己仍持有时清（防误删新实例）
            defer {
                if activeDownloaders[modelId] === downloader {
                    activeDownloaders.removeValue(forKey: modelId)
                }
            }
            lastByteReceivedAt[modelId] = Date()  // grace：覆盖 chunk 首连接（建连期无字节不应判 stall）
            let attemptNow = attempt  // Task 闭包按值捕获（避免 @Sendable 捕获 var）

            // 本 downloader 专属看门狗：无字节 > 阈值 → cancelAll 让 download() 抛错 → 本循环重试
            let stallWatchdog = Task { [weak self, weak downloader] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: UInt64(Self.stallCheckInterval * 1_000_000_000))
                    guard let self else { return }
                    // downloader 已换 / 已离开 downloading → 退出，不误杀
                    guard self.downloadStates[modelId]?.status == .downloading,
                          self.activeDownloaders[modelId] === downloader else { return }
                    if let last = self.lastByteReceivedAt[modelId],
                       Date().timeIntervalSince(last) > Self.stallThreshold {
                        NSLog("PoLang:ModelDownload stall restart modelId=%@ file=%@ attempt=%d",
                              modelId, fileName, attemptNow)
                        self.setReconnecting(modelId, true)
                        downloader?.cancelAll()
                        return
                    }
                }
            }

            do {
                try await downloader.download(url: url, partUrl: partUrl, totalSize: expectedSize) { withinFile in
                    DispatchQueue.main.async { [weak self] in
                        self?.reportProgressThrottled(modelId, base + withinFile)
                    }
                }
                stallWatchdog.cancel()
                setReconnecting(modelId, false)
                break  // 成功（defer 清理 downloader）
            } catch {
                stallWatchdog.cancel()
                if cancelledModels.contains(modelId) {
                    setReconnecting(modelId, false)
                    throw error  // 用户暂停/取消：上抛（performDownload 不报 .failed）
                }
                attempt += 1
                let isStallCancel = (error as? URLError)?.code == .cancelled || error is CancellationError
                if !isStallCancel || attempt > Self.maxStallRestarts {
                    setReconnecting(modelId, false)
                    if isStallCancel {
                        NSLog("PoLang:ModelDownload give up modelId=%@ file=%@ reason=stallRestartLimit",
                              modelId, fileName)
                    }
                    throw error  // 真错误（HTTP/sizeMismatch）或重试上限：上抛
                }
                // stall 取消 → loop 重试（新 downloader，.part.meta 续传剩余 chunk；defer 已清旧实例）
            }
        }

        if cancelledModels.contains(modelId) { return }  // 保留 .part/.meta 供续传

        if let sha256 = expectedSha256, !sha256.isEmpty {
            let verified = await Task.detached(priority: .utility) {
                Self.verifySHA256(file: partUrl, expected: sha256)
            }.value
            if !verified {
                // 校验失败：删除 .part/.meta 重来（续传会从头开始该文件）
                try? FileManager.default.removeItem(at: partUrl)
                try? FileManager.default.removeItem(at: partUrl.appendingPathExtension("meta"))
                throw URLError(.cannotDecodeContentData)
            }
        }

        if cancelledModels.contains(modelId) { return }  // 校验耗时，复检一次

        let destUrl = modelDir.appendingPathComponent(fileName)
        if FileManager.default.fileExists(atPath: destUrl.path) {
            try FileManager.default.removeItem(at: destUrl)
        }
        try FileManager.default.moveItem(at: partUrl, to: destUrl)
    }

    /// 真正取消进行中的系统任务/并行下载器（旧实现只置标志位，僵尸任务继续吃带宽）。
    /// 任务取消后以 URLError.cancelled 收尾，performDownload 依 cancelledModels 判定不报 failed。
    private func cancelActiveWork(_ modelId: String) {
        activeTasks.removeValue(forKey: modelId)?.forEach { $0.cancel() }
        activeDownloaders.removeValue(forKey: modelId)?.cancelAll()
        lastProgressReport.removeValue(forKey: modelId)
        setReconnecting(modelId, false)
    }

    // MARK: - ModelScope API

    private func fetchFileList(repo: String) async -> [ModelFileInfo] {
        let apiUrl = "https://modelscope.cn/api/v1/models/\(repo)/repo/files?Revision=master"
        guard let url = URL(string: apiUrl) else { return [] }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let dataDict = json["Data"] as? [String: Any],
                  let files = dataDict["Files"] as? [[String: Any]] else { return [] }

            return files.compactMap { fileObj in
                let name = fileObj["Name"] as? String ?? ""
                let type = fileObj["Type"] as? String ?? "blob"
                guard type == "blob" else { return nil }
                let size = Int64(fileObj["Size"] as? Int ?? 0)
                let sha256 = fileObj["Sha256"] as? String ?? ""
                return ModelFileInfo(name: name, size: size, sha256: sha256)
            }
        } catch { return [] }
    }

    // MARK: - 文件校验（后台线程执行，流式不整读进内存）

    /// 校验已存在文件是否完整：大小匹配（API 有 size 时）+ SHA256（API 有 sha 时）。
    private nonisolated static func verifyExistingFile(_ fileUrl: URL, info: ModelFileInfo?) -> Bool {
        let actualSize = fileSize(of: fileUrl)
        guard actualSize > 0 else { return false }
        guard let info else { return true }  // API 无信息：存在即视为完整（对齐旧行为）
        if info.size > 0 && info.size != actualSize { return false }
        if !info.sha256.isEmpty {
            return verifySHA256(file: fileUrl, expected: info.sha256)
        }
        return true
    }

    /// 流式 SHA256（旧实现 `Data(contentsOf:)` 整文件读入内存，1.4GB 文件直接卡死/崩溃）
    private nonisolated static func verifySHA256(file: URL, expected: String) -> Bool {
        guard !expected.isEmpty else { return true }
        guard let handle = try? FileHandle(forReadingFrom: file) else { return false }
        defer { try? handle.close() }

        var hasher = SHA256()
        while true {
            let chunk = autoreleasepool { handle.readData(ofLength: 1024 * 1024) }
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        let hashString = hasher.finalize().compactMap { String(format: "%02x", $0) }.joined()
        return hashString.lowercased() == expected.lowercased()
    }

    private nonisolated static func fileSize(of url: URL) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    // MARK: - State Helpers

    /// 仅在 downloading 状态下更新进度——暂停/取消后在途的进度回调不得把状态翻回 downloading
    private func updateProgress(_ modelId: String, _ downloaded: Int64) {
        guard var state = downloadStates[modelId], state.status == .downloading else { return }
        state.downloadedBytes = downloaded
        downloadStates[modelId] = state
    }

    /// 节流 500ms 的进度上报（delegate 队列回调经 main.async 汇入）
    private func reportProgressThrottled(_ modelId: String, _ downloaded: Int64) {
        let now = Date()
        // 字节到达即刷新（throttle 之前）——stall 看门狗据此判断连接是否还在流；
        // 字节恢复也意味着重连成功 → 清「重连中」提示
        lastByteReceivedAt[modelId] = now
        setReconnecting(modelId, false)
        if let last = lastProgressReport[modelId], now.timeIntervalSince(last) < 0.5 { return }
        lastProgressReport[modelId] = now
        updateProgress(modelId, downloaded)
    }

    /// 切换「重连中」UI 状态。⚠️ 必须 reassign（不能 in-place mutate）——
    /// @Published 只在赋值时触发 objectWillChange，Set.remove/insert 不触发。
    private func setReconnecting(_ modelId: String, _ on: Bool) {
        let present = reconnectingModels.contains(modelId)
        if on && !present {
            reconnectingModels = reconnectingModels.union([modelId])
        } else if !on && present {
            reconnectingModels = reconnectingModels.subtracting([modelId])
        }
    }

    private func updateTotalBytes(_ modelId: String, _ total: Int64) {
        guard var state = downloadStates[modelId] else { return }
        state.totalBytes = total
        downloadStates[modelId] = state
    }

    private func updateStatus(_ modelId: String, _ status: DownloadStatus) {
        var state = downloadStates[modelId] ?? DownloadState(
            modelId: modelId, downloadedBytes: 0, totalBytes: 0, status: status)
        state.status = status
        if status == .completed {
            state.downloadedBytes = state.totalBytes
        }
        downloadStates[modelId] = state
    }

    /// 刷新所有模型状态（启动/页面出现时检查已下载的模型）
    func refreshStates() {
        refreshAllStates()
    }

    private func refreshAllStates() {
        for entry in ModelCatalog.shared.models {
            if isModelDownloaded(entry.id) {
                downloadStates[entry.id] = DownloadState(
                    modelId: entry.id, downloadedBytes: entry.size,
                    totalBytes: entry.size, status: .completed)
            }
        }
    }

    var missingRequiredModels: [ModelEntry] {
        ModelCatalog.shared.models.filter { $0.isRequired && !isModelDownloaded($0.id) }
    }

    var missingRequiredSize: Int64 {
        missingRequiredModels.reduce(0) { $0 + $1.size }
    }
}
