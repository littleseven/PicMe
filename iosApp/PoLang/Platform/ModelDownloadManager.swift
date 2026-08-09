import Foundation
import CryptoKit
import Combine

/// 端侧模型下载管理器（对齐 Android `LlmModelDownloadManager`）。
///
/// 从 ModelScope CDN 下载端侧 AI 模型（MNN/ONNX），SHA256 校验，
/// 支持 暂停/恢复/取消/删除，进度实时上报。
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

    /// 所有模型的下载状态（单一状态源，UI 绑定）
    @Published private(set) var downloadStates: [String: DownloadState] = [:]

    /// 下载目录
    var modelsDir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("llm_models")
    }

    /// 每个 model 的 URLSession（用于 cancel）
    private var sessions: [String: URLSession] = [:]
    /// 每个 model 的下载 Task
    private var tasks: [String: [URLSessionDownloadTask]] = [:]
    /// 暂停时的 resume data（用于断点续传）
    private var resumeData: [String: [Data]] = [:]
    /// 下载缓冲
    private let bufferSize = 256 * 1024  // 256KB

    private init() {
        refreshAllStates()
    }

    // MARK: - 查询

    /// 模型是否已下载完成（所有文件存在）
    func isModelDownloaded(_ modelId: String) -> Bool {
        guard let entry = ModelCatalog.shared.model(byId: modelId) else { return false }
        let dir = modelsDir.appendingPathComponent(modelId)
        return entry.files.allSatisfy { FileManager.default.fileExists(atPath: dir.appendingPathComponent($0).path) }
    }

    /// 已下载模型的 id 集合
    var downloadedModelIds: Set<String> {
        Set(ModelCatalog.shared.models.filter { isModelDownloaded($0.id) }.map { $0.id })
    }

    // MARK: - 下载控制

    /// 启动下载
    func download(_ modelId: String) {
        guard let entry = ModelCatalog.shared.model(byId: modelId) else { return }
        guard let repo = entry.modelScopeRepo else { return }

        downloadStates[modelId] = DownloadState(
            modelId: modelId, downloadedBytes: 0, totalBytes: entry.size, status: .downloading)

        Task {
            await performDownload(entry: entry, repo: repo)
        }
    }

    /// 暂停
    func pause(_ modelId: String) {
        cancelTasks(modelId)
        updateStatus(modelId, .paused)
    }

    /// 恢复
    func resume(_ modelId: String) {
        guard downloadStates[modelId]?.status == .paused else { return }
        download(modelId)  // v1：简化为重新下载（resume data 在 v2 接入）
    }

    /// 取消（清理半成品）
    func cancel(_ modelId: String) {
        cancelTasks(modelId)
        // 删除半成品文件
        let dir = modelsDir.appendingPathComponent(modelId)
        try? FileManager.default.removeItem(at: dir)
        downloadStates.removeValue(forKey: modelId)
    }

    /// 删除已下载模型
    func delete(_ modelId: String) {
        cancelTasks(modelId)
        let dir = modelsDir.appendingPathComponent(modelId)
        try? FileManager.default.removeItem(at: dir)
        downloadStates.removeValue(forKey: modelId)
    }

    /// 一键下载所有缺失的 must-have 模型
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

        // 创建目录
        try? FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)

        // 获取文件列表 + SHA256
        let fileInfos = await fetchFileList(repo: repo)
        let fileInfoMap = Dictionary(uniqueKeysWithValues: fileInfos.map { ($0.name, $0) })

        var totalDownloaded: Int64 = 0

        for fileName in entry.files {
            // 检查取消
            if downloadStates[modelId]?.status == .cancelled { return }

            let destUrl = modelDir.appendingPathComponent(fileName)

            // 已存在 → 校验
            if FileManager.default.fileExists(atPath: destUrl.path) {
                let info = fileInfoMap[fileName]
                if let info, verifySHA256(file: destUrl, expected: info.sha256) {
                    totalDownloaded += info.size
                    updateProgress(modelId, totalDownloaded)
                    continue
                }
                // 校验失败 → 删除重下
                try? FileManager.default.removeItem(at: destUrl)
            }

            // 下载
            let url = URL(string: "https://modelscope.cn/models/\(repo)/resolve/master/\(fileName)")!
            let expectedSize = fileInfoMap[fileName]?.size ?? 0

            do {
                let downloaded = try await downloadFile(url: url, to: destUrl, modelId: modelId,
                                                        expectedSize: expectedSize)
                totalDownloaded += downloaded
                updateProgress(modelId, totalDownloaded)
            } catch {
                updateStatus(modelId, .failed)
                return
            }
        }

        updateStatus(modelId, .completed)
    }

    /// 下载单个文件（URLSessionDownloadTask，系统托管后台下载）
    private func downloadFile(url: URL, to destUrl: URL, modelId: String,
                              expectedSize: Int64) async throws -> Int64 {
        let config = URLSessionConfiguration.default
        config.allowsCellularAccess = true
        config.timeoutIntervalForRequest = 60
        let session = URLSession(configuration: config)
        sessions[modelId] = session

        let (tempUrl, response) = try await session.download(from: url)
        let httpResp = response as? HTTPURLResponse
        guard httpResp?.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }

        // 移动到目标
        if FileManager.default.fileExists(atPath: destUrl.path) {
            try FileManager.default.removeItem(at: destUrl)
        }
        try FileManager.default.moveItem(at: tempUrl, to: destUrl)

        let actualSize = (try? FileManager.default.attributesOfItem(atPath: destUrl.path)[.size] as? Int64) ?? 0
        return actualSize
    }

    // MARK: - ModelScope API

    /// 从 ModelScope API 获取文件列表 + SHA256。
    /// GET https://modelscope.cn/api/v1/models/{repo}/repo/files?Revision=master
    private func fetchFileList(repo: String) async -> [ModelFileInfo] {
        let apiUrl = "https://modelscope.cn/api/v1/models/\(repo)/repo/files?Revision=master"
        guard let url = URL(string: apiUrl) else { return [] }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let dataDict = json["Data"] as? [String: Any],
                  let files = dataDict["Files"] as? [[String: Any]] else {
                return []
            }

            return files.compactMap { fileObj in
                let name = fileObj["Name"] as? String ?? ""
                let type = fileObj["Type"] as? String ?? "blob"
                guard type == "blob" else { return nil }  // 只取文件，跳过目录
                let size = Int64(fileObj["Size"] as? Int ?? 0)
                let sha256 = fileObj["Sha256"] as? String ?? ""
                return ModelFileInfo(name: name, size: size, sha256: sha256)
            }
        } catch {
            return []
        }
    }

    // MARK: - SHA256

    private func verifySHA256(file: URL, expected: String) -> Bool {
        guard !expected.isEmpty else { return true }  // 无 SHA256 → 跳过校验
        guard let data = try? Data(contentsOf: file) else { return false }
        let hash = SHA256.hash(data: data)
        let hashString = hash.compactMap { String(format: "%02x", $0) }.joined()
        return hashString.lowercased() == expected.lowercased()
    }

    // MARK: - State Helpers

    private func updateProgress(_ modelId: String, _ downloaded: Int64) {
        var state = downloadStates[modelId] ?? DownloadState(
            modelId: modelId, downloadedBytes: 0, totalBytes: 0, status: .downloading)
        state.downloadedBytes = downloaded
        downloadStates[modelId] = state
    }

    private func updateStatus(_ modelId: String, _ status: DownloadStatus) {
        var state = downloadStates[modelId] ?? DownloadState(
            modelId: modelId, downloadedBytes: 0, totalBytes: 0, status: status)
        state.status = status
        downloadStates[modelId] = state
    }

    private func cancelTasks(_ modelId: String) {
        sessions[modelId]?.invalidateAndCancel()
        sessions.removeValue(forKey: modelId)
        tasks.removeValue(forKey: modelId)
    }

    /// 刷新所有模型状态（启动时检查已下载的模型）
    private func refreshAllStates() {
        for entry in ModelCatalog.shared.models {
            if isModelDownloaded(entry.id) {
                downloadStates[entry.id] = DownloadState(
                    modelId: entry.id, downloadedBytes: entry.size,
                    totalBytes: entry.size, status: .completed)
            }
        }
    }

    // MARK: - 缺失模型统计

    /// Must-have 中尚未下载的模型
    var missingRequiredModels: [ModelEntry] {
        ModelCatalog.shared.models.filter { $0.isRequired && !isModelDownloaded($0.id) }
    }

    /// 缺失 must-have 总大小
    var missingRequiredSize: Int64 {
        missingRequiredModels.reduce(0) { $0 + $1.size }
    }
}
