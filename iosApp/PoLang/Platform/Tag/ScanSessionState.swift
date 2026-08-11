import Foundation

/// TAG 扫描会话状态机（对齐 Android ScanSessionState）。
enum ScanSessionState: String, CaseIterable, Sendable {
    case idle, running, pausing, paused, cancelling, cancelled, completed

    var isTerminal: Bool { self == .cancelled || self == .completed }

    /// UI 本地化键（在 Localizable.xcstrings 中补三语）
    var localizationKey: String {
        switch self {
        case .idle: return "scan_state_idle"
        case .running: return "scan_state_running"
        case .pausing: return "scan_state_pausing"
        case .paused: return "scan_state_paused"
        case .cancelling: return "scan_state_cancelling"
        case .cancelled: return "scan_state_cancelled"
        case .completed: return "scan_state_completed"
        }
    }

    private static let table: [ScanSessionState: [ScanSessionEvent: ScanSessionState]] = [
        .idle: [.start: .running],
        .running: [.pause: .pausing, .cancel: .cancelling, .complete: .completed],
        .pausing: [.pauseAcknowledged: .paused, .cancel: .cancelling],
        .paused: [.resume: .running, .cancel: .cancelling],
        .cancelling: [.cancelAcknowledged: .cancelled]
    ]

    /// 合法迁移返回新状态；非法或终态返回 nil。
    func transition(_ event: ScanSessionEvent) -> ScanSessionState? {
        if isTerminal { return nil }
        return ScanSessionState.table[self]?[event]
    }
}

enum ScanSessionEvent: CaseIterable, Sendable {
    case start, pause, pauseAcknowledged, resume
    case cancel, cancelAcknowledged, complete
}
