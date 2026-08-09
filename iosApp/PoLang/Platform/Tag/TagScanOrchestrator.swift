import Foundation

/// 扫描进度快照（对齐 Android TagScanSessionProgress）。
struct TagScanSessionProgress: Sendable, Equatable {
    var sessionId: String
    var state: ScanSessionState
    var currentPass: ScanPass
    var processed: Int
    var total: Int
    var pending: Int
    var failed: Int
    var estimatedRemainingMs: Int
    var message: String
}

/// 扫描事件（orchestrator → ViewModel）。
enum ScanEvent: Sendable {
    case progress(TagScanSessionProgress)
    case finished(ScanSessionState) // completed / cancelled
}

/// TAG 扫描编排器。
///
/// 设计：可变状态经串行 `lockQueue` 保护（与 TagDatabase 同风格）；单一后台 `Task(.utility)`
/// 串行调 `Pass1Pipeline.shared`（MNN 单线程约束）；pause/cancel 协作式（每张图之间检查）；
/// 进度经 `onEvent` 回调发出（订阅方自行切主线程）。前台优先：进后台由调用方触发 `pause()`。
final class TagScanOrchestrator: @unchecked Sendable {
    static let shared = TagScanOrchestrator()

    /// 进度回调（ViewModel 订阅；在 lockQueue 或后台线程发出，订阅方需自行切主线程）。
    var onEvent: (@Sendable (ScanEvent) -> Void)?

    private let db = TagDatabase.shared
    private let media = PhMediaBridge()
    private let lockQueue = DispatchQueue(label: "com.mamba.picme.tagscan", qos: .userInitiated)

    private struct Box {
        var sessionState: ScanSessionState = .idle
        var sessionId: String?
        var pauseRequested: Bool = false
        var cancelRequested: Bool = false
        var samples: [Int] = []
        var processed: Int = 0
        var failed: Int = 0
        var total: Int = 0
        var task: Task<Void, Never>?
    }
    private var box = Box()

    private init() {
        // 中断恢复：把上次 RUNNING 重置为 PENDING，等用户在扫描页点恢复。
        if let sid = db.unfinishedSessionId() {
            db.resetRunningToPending(sessionId: sid)
        }
    }

    // MARK: - 锁内读写

    @inline(__always)
    private func mutate<T>(_ body: (inout Box) -> T) -> T { lockQueue.sync { body(&self.box) } }
    private func read<T>(_ body: (Box) -> T) -> T { lockQueue.sync { body(self.box) } }

    private func snapshot() -> TagScanSessionProgress {
        mutate { box in
            let eta = ScanEtaEstimator(pass: .faceDetection, samples: box.samples)
                .estimateMillis(remaining: max(0, box.total - box.processed))
            let pending = max(0, box.total - box.processed)
            return TagScanSessionProgress(
                sessionId: box.sessionId ?? "",
                state: box.sessionState,
                currentPass: .faceDetection,
                processed: box.processed, total: box.total,
                pending: pending, failed: box.failed,
                estimatedRemainingMs: eta,
                message: box.sessionState.localizationKey)
        }
    }

    // MARK: - 公共控制（UI 在 MainActor 调）

    /// 启动新扫描会话。
    func start(mode: ScanMode) {
        let canStart = read { $0.sessionState == .idle || $0.sessionState.isTerminal }
        guard canStart else { return }

        // 1) 同步 media_assets 索引（get-or-create 全量，不持 lockQueue）
        let now = Self.nowMs()
        for item in media.fetchAllMedia() where item.mediaType == "PHOTO" {
            db.getOrCreateMedia(localIdentifier: item.localIdentifier,
                                type: "IMAGE",
                                captureDateMs: item.captureDateMs,
                                fileName: item.fileName)
        }
        // 2) 规划 Pass1 任务集
        let allIds = db.allImageMediaIds()
        let covered = db.pass1CoveredMediaIds()
        let planned = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: allIds, pass1CoveredMediaIds: covered, mode: mode)

        let sid = "tag-\(UUID().uuidString.prefix(8))"
        if planned.isEmpty {
            // 无待扫：直接完成
            let snap = mutate { box -> TagScanSessionProgress in
                box.sessionId = sid
                box.sessionState = .completed
                box.total = 0; box.processed = 0; box.failed = 0
                return self.snapshotLocked(box)
            }
            emit(.progress(snap)); emit(.finished(.completed))
            return
        }
        // 3) 入队
        db.enqueuePass1Tasks(sessionId: sid, mediaIds: planned, now: now)
        // 4) 置状态 + 启运行循环（持锁）
        let snap = mutate { box -> TagScanSessionProgress in
            box.task?.cancel()
            box.sessionId = sid
            box.sessionState = box.sessionState.transition(.start) ?? .running
            box.total = planned.count
            box.processed = 0; box.failed = 0
            box.samples = []
            box.pauseRequested = false; box.cancelRequested = false
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
            return self.snapshotLocked(box)
        }
        emit(.progress(snap))
    }

    func pause() {
        let snap = mutate { box -> TagScanSessionProgress? in
            guard box.sessionState == .running else { return nil }
            box.pauseRequested = true
            box.sessionState = box.sessionState.transition(.pause) ?? .pausing
            return self.snapshotLocked(box)
        }
        if let snap = snap { emit(.progress(snap)) }
    }

    func resume() {
        let snap = mutate { box -> TagScanSessionProgress? in
            guard box.sessionState == .paused, let sid = box.sessionId else { return nil }
            self.db.resumeSession(sessionId: sid)
            box.pauseRequested = false
            box.sessionState = box.sessionState.transition(.resume) ?? .running
            box.task?.cancel()
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
            return self.snapshotLocked(box)
        }
        if let snap = snap { emit(.progress(snap)) }
    }

    func cancel() {
        let evt: ScanEvent? = mutate { box -> ScanEvent? in
            guard let sid = box.sessionId, !box.sessionState.isTerminal else { return nil }
            box.cancelRequested = true
            self.db.cancelSession(sessionId: sid)
            box.task?.cancel()
            box.sessionState = .cancelling.transition(.cancelAcknowledged) ?? .cancelled
            box.task = nil
            return .finished(.cancelled)
        }
        if let evt = evt {
            emit(.progress(snapshot()))
            emit(evt)
        }
    }

    /// 恢复上次未完成 session（App 重启后扫描页「恢复」用）。
    /// 从 DB 采纳未完成 sessionId，置 running 并启动循环（不重新入队）。
    func resumeUnfinishedSession() {
        guard let sid = db.unfinishedSessionId() else { return }
        let counts = db.sessionCounts(sid)
        let snap = mutate { box -> TagScanSessionProgress in
            box.task?.cancel()
            box.sessionId = sid
            box.sessionState = box.sessionState.transition(.start) ?? .running
            box.total = counts.total
            box.processed = counts.completed
            box.failed = counts.failed
            box.samples = []
            box.pauseRequested = false; box.cancelRequested = false
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
            return self.snapshotLocked(box)
        }
        emit(.progress(snap))
    }

    func retryFailed() {
        let resumed = mutate { box -> Bool in
            guard let sid = box.sessionId else { return false }
            self.db.retryFailed(sessionId: sid)
            let canRun = box.sessionState == .paused || box.sessionState == .idle || box.sessionState.isTerminal
            if canRun {
                box.sessionState = .running
                box.cancelRequested = false; box.pauseRequested = false
                box.task?.cancel()
                box.task = Task.detached(priority: .utility) { [weak self] in
                    await self?.runLoop()
                }
            }
            return canRun
        }
        if resumed { emit(.progress(snapshot())) }
    }

    /// 当前进度快照（UI 刷新用）。
    func currentProgress() -> TagScanSessionProgress? {
        read { box in box.sessionState == .idle ? nil : self.snapshotLocked(box) }
    }

    /// 是否有未完成 session（扫描页「恢复」提示用）。
    var hasUnfinishedSession: Bool { db.unfinishedSessionId() != nil }

    /// 前台优先：进后台时由调用方触发（仅 running 时协作暂停）。
    func pauseForBackground() { pause() }

    // MARK: - 运行循环（后台 Task）

    private func runLoop() async {
        // 确保模型已加载（首次）。RetinaFace/2d106 已 bundled；glintr100/mobileclip 需在
        // Model Center 预下载——缺失时 loadModels 返回 false，扫描仍跑但仅 RetinaFace 出人脸、
        // 无 embedding/语义向量。modelsReady 一旦为 true 后续启动跳过重复加载。
        if !Pass1Pipeline.shared.modelsReady {
            _ = Pass1Pipeline.shared.loadModels()
        }
        let sid = read { $0.sessionId } ?? ""
        var localSamples: [Int] = []
        while !Task.isCancelled {
            // 1) 检查控制标志
            let (pauseReq, cancelReq) = read { ($0.pauseRequested, $0.cancelRequested) }
            if cancelReq { return }                  // cancel() 已置状态/发事件
            if pauseReq {
                db.pauseSession(sessionId: sid)
                let snap = mutate { box -> TagScanSessionProgress in
                    box.sessionState = box.sessionState.transition(.pauseAcknowledged) ?? .paused
                    return self.snapshotLocked(box)
                }
                emit(.progress(snap))
                return
            }
            // 2) 原子领取任务
            let now = Self.nowMs()
            guard let task = db.pollAndMarkRunning(sessionId: sid, now: now) else {
                // 队列空：再次确认未被取消，否则完成
                let cancelled = read { $0.cancelRequested }
                if cancelled { return }
                let snap = mutate { box -> TagScanSessionProgress in
                    box.sessionState = box.sessionState.transition(.complete) ?? .completed
                    return self.snapshotLocked(box)
                }
                emit(.progress(snap)); emit(.finished(.completed))
                return
            }
            // 3) 执行 Pass1（同步、串行、后台线程）
            let lid = db.localIdentifier(forMediaId: task.mediaId)
            if let lid = lid, let image = ScanImageLoader.load(localIdentifier: lid) {
                let t0 = CFAbsoluteTimeGetCurrent()
                _ = Pass1Pipeline.shared.process(image, mediaId: task.mediaId)
                let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
                localSamples.append(ms)
                db.markCompleted(taskId: task.taskId, now: Self.nowMs())
                let snap = mutate { box -> TagScanSessionProgress in
                    box.processed += 1
                    box.samples = localSamples
                    return self.snapshotLocked(box)
                }
                emit(.progress(snap))
            } else {
                db.markFailed(taskId: task.taskId, now: now,
                              errorMessage: "image load failed", backoffMs: 5_000)
                let snap = mutate { box -> TagScanSessionProgress in
                    box.failed += 1
                    return self.snapshotLocked(box)
                }
                emit(.progress(snap))
            }
            // 4) 任务间轻让步
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    // MARK: - 辅助

    /// 由 box 构造进度快照（须在锁内调用）。
    private func snapshotLocked(_ box: Box) -> TagScanSessionProgress {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: box.samples)
            .estimateMillis(remaining: max(0, box.total - box.processed))
        return TagScanSessionProgress(
            sessionId: box.sessionId ?? "",
            state: box.sessionState,
            currentPass: .faceDetection,
            processed: box.processed, total: box.total,
            pending: max(0, box.total - box.processed),
            failed: box.failed,
            estimatedRemainingMs: eta,
            message: box.sessionState.localizationKey)
    }

    private func emit(_ ev: ScanEvent) { onEvent?(ev) }
    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
