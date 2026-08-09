import SwiftUI

@MainActor
final class TagScanViewModel: ObservableObject {
    @Published private(set) var progress: TagScanSessionProgress?
    @Published private(set) var stats: ScanDbStats = .init(
        totalMedia: 0, withFace: 0, withLabels: 0, withSemantic: 0,
        personCount: 0, namedPersonCount: 0, faceEmbeddingCount: 0,
        remainingPass1: 0, remainingPass3: 0)
    @Published private(set) var hasUnfinishedSession: Bool = false

    private let orchestrator = TagScanOrchestrator.shared

    init() {
        orchestrator.onEvent = { [weak self] ev in
            Task { @MainActor in
                guard let self else { return }
                switch ev {
                case .progress(let p):
                    self.progress = p
                case .finished:
                    self.refreshStats()
                }
            }
        }
        refreshStats()
    }

    var isScanning: Bool {
        let s = progress?.state ?? .idle
        return s == .running || s == .pausing
    }

    func startFull() { orchestrator.start(mode: .full); refreshStats() }
    func startIncremental() { orchestrator.start(mode: .incremental); refreshStats() }
    func pause() { orchestrator.pause() }
    func resume() { orchestrator.resume() }
    func cancel() { orchestrator.cancel() }
    func retryFailed() { orchestrator.retryFailed() }

    func refreshStats() {
        stats = TagDatabase.shared.scanStats()
        hasUnfinishedSession = orchestrator.hasUnfinishedSession
        if progress == nil, let p = orchestrator.currentProgress() { progress = p }
    }

    /// 恢复上次未完成 session（进入扫描页提示后用）。
    func resumeUnfinished() {
        orchestrator.resumeUnfinishedSession()
        refreshStats()
    }
}
