import Foundation

/// 大文件分块并行下载器（对齐 Android `ParallelFileDownloader`）：
/// 把单个大文件切成多段，并发 Range 请求下载，按偏移写入同一 `.part` 文件。
///
/// 用于加速模型中心大文件（如 1.4GB llm.mnn.weight）——单连接受 CDN 单流限速，
/// 多段并发可提升吞吐。ModelScope 已验证支持 Range。
///
/// 与 Android 差异：支持 **chunk 级断点续传**——完成的 chunk 记录在 `.part.meta`
/// 侧车文件中，暂停/杀进程后重下只补缺失 chunk，且 `.part` 不计入「已下载」
/// （`isModelDownloaded` 只认最终文件名），避免预分配垃圾文件被误判为已下载。
final class ParallelFileDownloader {

    enum DownloadError: Error {
        case invalidTotalSize(Int64)
        case httpError(Int)
        case sizeMismatch(expected: Int64, actual: Int64)
    }

    /// 闭区间字节区段 [start, end]
    struct ChunkRange: Equatable {
        let start: Int64
        let end: Int64
        var length: Int64 { end - start + 1 }
    }

    /// `.part.meta` 侧车：记录已完成的 chunk 下标
    private struct PartMeta: Codable {
        let totalSize: Int64
        let completedChunks: [Int]
    }

    /// 进入分块并行的文件大小阈值（与 Android PARALLEL_DOWNLOAD_THRESHOLD 一致）
    static let parallelThreshold: Int64 = 32 * 1024 * 1024
    /// 默认并发区段数（与 Android DEFAULT_CHUNK_COUNT 一致）
    static let defaultChunkCount = 4
    /// 默认单段最小字节（与 Android DEFAULT_MIN_CHUNK_SIZE 一致）
    static let defaultMinChunkSize: Int64 = 8 * 1024 * 1024

    private let lock = NSLock()
    private var tasks: [URLSessionDownloadTask] = []
    private var completedChunks = Set<Int>()

    /// 取消所有进行中的 chunk 任务（暂停/取消/删除时调用）。
    /// 各 chunk 以 URLError.cancelled 收尾，任务组抛错退出；`.part` + `.meta` 保留供续传。
    func cancelAll() {
        lock.lock()
        let current = tasks
        lock.unlock()
        current.forEach { $0.cancel() }
    }

    /// 并发下载 [url] 到 [partUrl]（总长 [totalSize]），支持 chunk 级续传。
    ///
    /// - Parameter onProgress: 文件维度累计已下载字节（含前次会话完成的 chunk），
    ///   在 delegate 队列触发，调用方自行切线程/节流。
    /// - Throws: 区段 HTTP 非 206/200、字节数不符、或取消（URLError.cancelled）
    func download(url: URL, partUrl: URL, totalSize: Int64,
                  onProgress: @escaping (Int64) -> Void) async throws {
        let ranges = Self.computeChunkRanges(totalSize: totalSize)
        guard !ranges.isEmpty else { throw DownloadError.invalidTotalSize(totalSize) }

        let metaUrl = partUrl.appendingPathExtension("meta")

        // 读取上次进度（totalSize 不一致视为过期，重来）
        if let meta = Self.loadMeta(from: metaUrl), meta.totalSize == totalSize,
           FileManager.default.fileExists(atPath: partUrl.path) {
            completedChunks = Set(meta.completedChunks).intersection(ranges.indices)
        } else {
            try? FileManager.default.removeItem(at: partUrl)
            try? FileManager.default.removeItem(at: metaUrl)
            completedChunks = []
        }

        if !FileManager.default.fileExists(atPath: partUrl.path) {
            FileManager.default.createFile(atPath: partUrl.path, contents: nil)
        }

        // 预分配并截断到 totalSize（对齐 Android RandomAccessFile.setLength）：
        // 各 chunk 按偏移并发写时不再触发空洞扩展，写位置即偏移，且提前暴露磁盘不足。
        // 续传时文件已是 totalSize 长，重复 truncate 无副作用。
        let prealloc = try FileHandle(forWritingTo: partUrl)
        try prealloc.truncate(atOffset: UInt64(totalSize))
        try prealloc.close()

        // 防御性重置（实例正常不复用，但 cancelAll 后 tasks 不残留）
        lock.lock()
        tasks.removeAll()
        lock.unlock()

        let completedBytes = completedChunks.reduce(Int64(0)) { $0 + ranges[$1].length }
        let progress = ChunkProgressTracker(base: completedBytes, onProgress: onProgress)
        if completedBytes > 0 { onProgress(completedBytes) }

        try await withThrowingTaskGroup(of: Void.self) { group in
            for (index, range) in ranges.enumerated() where !completedChunks.contains(index) {
                group.addTask {
                    try await self.downloadChunk(url: url, range: range, index: index,
                                                 partUrl: partUrl, metaUrl: metaUrl,
                                                 totalSize: totalSize, progress: progress)
                }
            }
            try await group.waitForAll()
        }

        // 最终大小校验
        let finalAttrs = try? FileManager.default.attributesOfItem(atPath: partUrl.path)
        let finalSize = (finalAttrs?[.size] as? Int64) ?? 0
        guard finalSize == totalSize else {
            throw DownloadError.sizeMismatch(expected: totalSize, actual: finalSize)
        }
        try? FileManager.default.removeItem(at: metaUrl)
    }

    // MARK: - Chunk

    private func downloadChunk(url: URL, range: ChunkRange, index: Int,
                               partUrl: URL, metaUrl: URL, totalSize: Int64,
                               progress: ChunkProgressTracker) async throws {
        var request = URLRequest(url: url)
        request.setValue("PoLang-iOS/1.0", forHTTPHeaderField: "User-Agent")
        request.setValue("bytes=\(range.start)-\(range.end)", forHTTPHeaderField: "Range")

        let (tempUrl, response) = try await withCheckedThrowingContinuation {
            (cont: CheckedContinuation<(tempFile: URL, response: HTTPURLResponse?), Error>) in
            let task = DownloadTaskHub.shared.startTask(
                with: request,
                onProgress: { written in progress.report(chunk: index, written: written) },
                onCompletion: { cont.resume(with: $0) }
            )
            registerTask(task)
        }
        defer { try? FileManager.default.removeItem(at: tempUrl) }

        let statusCode = response?.statusCode ?? 0
        let tempAttrs = try? FileManager.default.attributesOfItem(atPath: tempUrl.path)
        let tempSize = (tempAttrs?[.size] as? Int64) ?? 0
        // 206 = 正常分段；200 = 服务端忽略 Range 发了整文件（仅单段场景可接受）。
        // 字节数必须恰好等于段长，任何不符都判失败重下。
        guard statusCode == 206 || statusCode == 200, tempSize == range.length else {
            throw DownloadError.httpError(statusCode)
        }

        // 流式写入 part 文件对应偏移（256KB 缓冲，不整段进内存）
        try Self.writeTempFile(at: tempUrl, toPart: partUrl, atOffset: range.start)

        progress.chunkDone(chunk: index, length: range.length)
        markChunkCompleted(index, metaUrl: metaUrl, totalSize: totalSize)
    }

    private func registerTask(_ task: URLSessionDownloadTask) {
        lock.lock()
        tasks.append(task)
        lock.unlock()
    }

    private func markChunkCompleted(_ index: Int, metaUrl: URL, totalSize: Int64) {
        lock.lock()
        completedChunks.insert(index)
        let meta = PartMeta(totalSize: totalSize, completedChunks: completedChunks.sorted())
        if let data = try? JSONEncoder().encode(meta) {
            try? data.write(to: metaUrl, options: .atomic)
        }
        lock.unlock()
    }

    private static func loadMeta(from metaUrl: URL) -> PartMeta? {
        guard let data = try? Data(contentsOf: metaUrl) else { return nil }
        return try? JSONDecoder().decode(PartMeta.self, from: data)
    }

    /// 把临时文件内容流式写入 part 文件的指定偏移
    private static func writeTempFile(at tempUrl: URL, toPart partUrl: URL, atOffset offset: Int64) throws {
        let reader = try FileHandle(forReadingFrom: tempUrl)
        let writer = try FileHandle(forWritingTo: partUrl)
        defer {
            try? reader.close()
            try? writer.close()
        }
        try writer.seek(toOffset: UInt64(offset))
        while true {
            let data = try reader.read(upToCount: 256 * 1024) ?? Data()
            if data.isEmpty { break }
            try writer.write(contentsOf: data)
        }
    }

    // MARK: - Chunk Ranges（对齐 Android computeChunkRanges，纯函数供单测）

    /// 把 [totalSize] 字节切成至多 [chunkCount] 个并发区段，每段不小于 [minChunkSize]。
    /// 返回闭区间列表，恰好连续覆盖 [0, totalSize-1]，无重叠无空隙。
    /// - totalSize <= 0 → 空
    /// - chunkCount <= 1 或 totalSize <= minChunkSize → 单区段
    /// - 余数均匀分给前若干段，保证各区段长度差 ≤ 1
    static func computeChunkRanges(
        totalSize: Int64,
        chunkCount: Int = defaultChunkCount,
        minChunkSize: Int64 = defaultMinChunkSize
    ) -> [ChunkRange] {
        if totalSize <= 0 { return [] }
        let last = totalSize - 1
        if chunkCount <= 1 || totalSize <= minChunkSize {
            return [ChunkRange(start: 0, end: last)]
        }
        let effective = max(1, min(Int64(chunkCount), totalSize / minChunkSize))
        if effective <= 1 {
            return [ChunkRange(start: 0, end: last)]
        }
        let base = totalSize / effective
        let remainder = totalSize % effective
        var ranges: [ChunkRange] = []
        var start: Int64 = 0
        for i in 0..<Int(effective) {
            let len = base + (Int64(i) < remainder ? 1 : 0)
            ranges.append(ChunkRange(start: start, end: start + len - 1))
            start += len
        }
        return ranges
    }
}

// MARK: - 进度聚合（线程安全）

/// 聚合多 chunk 并发进度：base = 已完成 chunk 字节，inFlight = 各 chunk 实时已下载。
private final class ChunkProgressTracker {
    private let lock = NSLock()
    private var base: Int64
    private var inFlight: [Int: Int64] = [:]
    private let onProgress: (Int64) -> Void

    init(base: Int64, onProgress: @escaping (Int64) -> Void) {
        self.base = base
        self.onProgress = onProgress
    }

    func report(chunk: Int, written: Int64) {
        lock.lock()
        inFlight[chunk] = written
        let total = base + inFlight.values.reduce(0, +)
        lock.unlock()
        onProgress(total)
    }

    func chunkDone(chunk: Int, length: Int64) {
        lock.lock()
        inFlight.removeValue(forKey: chunk)
        base += length
        let total = base
        lock.unlock()
        onProgress(total)
    }
}
