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

    @Published private(set) var downloadStates: [String: DownloadState] = [:]
    @Published var selectedCategory: ModelCategory = .mustHave

    var modelsDir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("llm_models")
    }

    private var cancelledModels: Set<String> = []
    private let bufferSize = 256 * 1024

    private init() {
        refreshAllStates()
    }

    // MARK: - 查询

    func isModelDownloaded(_ modelId: String) -> Bool {
        guard let entry = ModelCatalog.shared.model(byId: modelId) else { return false }
        let dir = modelsDir.appendingPathComponent(modelId)
        return entry.files.allSatisfy { FileManager.default.fileExists(atPath: dir.appendingPathComponent($0).path) }
    }

    var downloadedModelIds: Set<String> {
        Set(ModelCatalog.shared.models.filter { isModelDownloaded($0.id) }.map { $0.id })
    }

    // MARK: - 下载控制

    func download(_ modelId: String) {
        guard let entry = ModelCatalog.shared.model(byId: modelId) else { return }
        guard let repo = entry.modelScopeRepo else { return }

        cancelledModels.remove(modelId)
        downloadStates[modelId] = DownloadState(
            modelId: modelId, downloadedBytes: 0, totalBytes: entry.size, status: .downloading)

        Task {
            await performDownload(entry: entry, repo: repo)
        }
    }

    func pause(_ modelId: String) {
        cancelledModels.insert(modelId)
        updateStatus(modelId, .paused)
    }

    func resume(_ modelId: String) {
        guard downloadStates[modelId]?.status == .paused else { return }
        download(modelId)
    }

    func cancel(_ modelId: String) {
        cancelledModels.insert(modelId)
        // 不删文件——保留已下载部分供下次续传
        downloadStates.removeValue(forKey: modelId)
    }

    func delete(_ modelId: String) {
        cancelledModels.insert(modelId)
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

    // MARK: - Core Download Logic（流式逐块下载 + 实时进度）

    private func performDownload(entry: ModelEntry, repo: String) async {
        let modelId = entry.id
        let modelDir = modelsDir.appendingPathComponent(modelId)
        try? FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)

        let fileInfos = await fetchFileList(repo: repo)
        let fileInfoMap = Dictionary(uniqueKeysWithValues: fileInfos.map { ($0.name, $0) })

        var totalDownloaded: Int64 = 0
        let lastProgressUpdate = Date()

        for fileName in entry.files {
            if cancelledModels.contains(modelId) { return }

            let destUrl = modelDir.appendingPathComponent(fileName)

            // 已存在 → 校验跳过
            if FileManager.default.fileExists(atPath: destUrl.path) {
                let info = fileInfoMap[fileName]
                if let info, verifySHA256(file: destUrl, expected: info.sha256) {
                    totalDownloaded += info.size
                    updateProgress(modelId, totalDownloaded)
                    continue
                }
                try? FileManager.default.removeItem(at: destUrl)
            }

            // 流式下载（逐块写入 + 实时进度）
            let url = URL(string: "https://modelscope.cn/models/\(repo)/resolve/master/\(fileName)")!
            do {
                let downloaded = try await downloadFileStreaming(
                    url: url, to: destUrl, modelId: modelId,
                    bytesAlreadyDownloaded: totalDownloaded,
                    totalModelSize: entry.size
                )
                totalDownloaded += downloaded
                updateProgress(modelId, totalDownloaded)
            } catch {
                if !cancelledModels.contains(modelId) {
                    updateStatus(modelId, .failed)
                }
                return
            }
        }

        if !cancelledModels.contains(modelId) {
            updateStatus(modelId, .completed)
        }
    }

    /// 流式下载：用 URLSession 下载数据块写入文件，实时报告进度
    /// 支持 Range 续传（如目标文件已有部分数据，从断点继续）
    private func downloadFileStreaming(
        url: URL, to destUrl: URL, modelId: String,
        bytesAlreadyDownloaded: Int64, totalModelSize: Int64
    ) async throws -> Int64 {
        // 检查已有文件大小（断点续传）
        var existingSize: Int64 = 0
        if FileManager.default.fileExists(atPath: destUrl.path) {
            existingSize = (try? FileManager.default.attributesOfItem(atPath: destUrl.path)[.size] as? Int64) ?? 0
        }

        // 构建请求（带 Range 头支持续传）
        var request = URLRequest(url: url)
        request.timeoutInterval = 60
        if existingSize > 0 {
            request.setValue("bytes=\(existingSize)-", forHTTPHeaderField: "Range")
        }

        // 用 URLSession 的 data task with delegate 获取块级回调
        let (asyncBytes, response) = try await URLSession.shared.bytes(for: request)

        let httpResp = response as? HTTPURLResponse
        let statusCode = httpResp?.statusCode ?? 0
        // 200 = 全新下载, 206 = Range 续传成功
        guard statusCode == 200 || statusCode == 206 else {
            throw URLError(.badServerResponse)
        }

        let isResuming = (statusCode == 206)
        let contentLength = Int64(httpResp?.value(forHTTPHeaderField: "Content-Length") ?? "") ?? 0
        let fileTotalSize = isResuming ? existingSize + contentLength : contentLength

        // 打开文件（追加模式 or 新建）
        if isResuming {
            // 追加写入已有文件
            guard let fileHandle = try? FileHandle(forWritingTo: destUrl) else {
                throw URLError(.cannotOpenFile)
            }
            try fileHandle.seekToEnd()
            return try await writeBytes(asyncBytes, to: fileHandle, modelId: modelId,
                                        bytesAlreadyDownloaded: bytesAlreadyDownloaded + existingSize,
                                        startingOffset: existingSize)
        } else {
            // 全新文件
            FileManager.default.createFile(atPath: destUrl.path, contents: nil)
            guard let fileHandle = try? FileHandle(forWritingTo: destUrl) else {
                throw URLError(.cannotOpenFile)
            }
            return try await writeBytes(asyncBytes, to: fileHandle, modelId: modelId,
                                        bytesAlreadyDownloaded: bytesAlreadyDownloaded,
                                        startingOffset: 0)
        }
    }

    /// 高效写入：按块（而非逐字节）读取 async bytes
    private func writeBytes(
        _ bytes: URLSession.AsyncBytes,
        to fileHandle: FileHandle,
        modelId: String,
        bytesAlreadyDownloaded: Int64,
        startingOffset: Int64
    ) async throws -> Int64 {
        var downloaded: Int64 = startingOffset
        var lastReportTime = Date()
        let chunkSize = 64 * 1024  // 64KB 块读取（非逐字节）
        var chunk = Data()
        chunk.reserveCapacity(chunkSize)

        do {
            for try await byte in bytes {
                if cancelledModels.contains(modelId) {
                    try? fileHandle.close()
                    // 不删文件——保留已下载部分供续传
                    return downloaded - startingOffset
                }

                chunk.append(byte)
                downloaded += 1

                if chunk.count >= chunkSize {
                    try fileHandle.write(contentsOf: chunk)
                    chunk.removeAll(keepingCapacity: true)

                    // 节流进度上报：500ms
                    let now = Date()
                    if now.timeIntervalSince(lastReportTime) > 0.5 {
                        updateProgress(modelId, bytesAlreadyDownloaded + downloaded - startingOffset)
                        lastReportTime = now
                    }
                }
            }

            // 写入最后一块
            if !chunk.isEmpty {
                try fileHandle.write(contentsOf: chunk)
            }
            try fileHandle.close()

            updateProgress(modelId, bytesAlreadyDownloaded + downloaded - startingOffset)
            return downloaded - startingOffset
        } catch {
            try? fileHandle.close()
            throw error
        }
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

    // MARK: - SHA256

    private func verifySHA256(file: URL, expected: String) -> Bool {
        guard !expected.isEmpty else { return true }
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
        state.status = .downloading
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
