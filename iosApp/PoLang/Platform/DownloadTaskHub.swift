import Foundation

/// URLSessionDownloadTask 枢纽——经典 delegate API 的进度/完成回调路由。
///
/// ⚠️ 为什么不用 async/await `session.download(for:)`：
/// 实测（macOS/iOS 行为一致）async 版本**完全不回调** `didWriteData`
/// （session 级 delegate 和 per-task delegate 变体 `download(for:delegate:)` 均为 0 次回调），
/// 导致下载进度恒为 0——这是「模型中心进度条不动」的根因。
/// 只有经典 `session.downloadTask(with:)` + session delegate 能拿到实时进度。
final class DownloadTaskHub: NSObject, URLSessionDownloadDelegate {

    /// 单个任务的回调集。onProgress 在 delegate 队列触发（调用方自行切线程/节流）。
    struct Handlers {
        /// 本任务已写入字节数（didWriteData 的 totalBytesWritten）
        let onProgress: (Int64) -> Void
        /// 恰好回调一次：成功=已落盘的临时文件 + HTTP 响应；失败=错误（含取消）
        let onCompletion: (Result<(tempFile: URL, response: HTTPURLResponse?), Error>) -> Void
    }

    static let shared = DownloadTaskHub()

    private let lock = NSLock()
    private var handlers: [Int: Handlers] = [:]

    /// 共享 session（delegate = 本枢纽）。系统内部复用连接。
    /// timeoutIntervalForResource = 0：大文件（1.4GB+）不限总时长。
    private(set) lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15  // stall 检测主驱动：无字节 15s → 必触发 .timedOut（不依赖 task.cancel）
        config.timeoutIntervalForResource = 0
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// 创建并启动一个下载任务。调用方持有返回的 task 以便 cancel()。
    func startTask(
        with request: URLRequest,
        onProgress: @escaping (Int64) -> Void,
        onCompletion: @escaping (Result<(tempFile: URL, response: HTTPURLResponse?), Error>) -> Void
    ) -> URLSessionDownloadTask {
        let task = session.downloadTask(with: request)
        lock.lock()
        handlers[task.taskIdentifier] = Handlers(onProgress: onProgress, onCompletion: onCompletion)
        lock.unlock()
        task.resume()
        return task
    }

    // MARK: - URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        lock.lock()
        let handler = handlers[downloadTask.taskIdentifier]
        lock.unlock()
        handler?.onProgress(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        lock.lock()
        let handler = handlers[downloadTask.taskIdentifier]
        lock.unlock()
        guard let handler else { return }
        // ⚠️ location 在此方法返回后即被系统删除——必须先移走再回调。
        do {
            let staging = FileManager.default.temporaryDirectory
                .appendingPathComponent("polang-dl-\(downloadTask.taskIdentifier)-\(UUID().uuidString)")
            try FileManager.default.moveItem(at: location, to: staging)
            handler.onCompletion(.success((staging, downloadTask.response as? HTTPURLResponse)))
        } catch {
            handler.onCompletion(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let handler = handlers.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        // error == nil 时 onCompletion 已在 didFinishDownloadingTo 回调过；
        // error != nil（含 NSURLErrorCancelled）时 didFinish 不会触发，在此补偿回调。
        if let error, let handler {
            handler.onCompletion(.failure(error))
        }
    }
}
