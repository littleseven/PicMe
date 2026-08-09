import Foundation

/// 扫描 Pass（与 tag_scan_tasks.pass 文本对齐；SP-B 只用 faceDetection）。
enum ScanPass: String, Sendable {
    case faceDetection = "FACE_DETECTION"
    case dbscan = "DBSCAN"
    case imageTagging = "IMAGE_TAGGING"
    case mobileClipEncoding = "MOBILE_CLIP_ENCODING"

    /// 冷启动默认单图耗时（对齐 Android TagScanOrchestrator 冷启值）。
    var coldStartMillis: Int {
        switch self {
        case .faceDetection: return 800
        case .dbscan: return 5_000
        case .imageTagging: return 7_000
        case .mobileClipEncoding: return 1_000
        }
    }
}

/// 滑动窗口中位数 ETA（对齐 Android：窗口 20、过滤 >30min 异常、冷启动默认值）。
struct ScanEtaEstimator: Sendable {
    static let windowSize = 20
    static let anomalyThresholdMs = 30 * 60 * 1_000 // 30 min

    let pass: ScanPass
    let samples: [Int] // 已观测的单图耗时 ms（按时间顺序）

    /// 中位数每图耗时；样本为空或全异常时回退冷启动默认。
    func perItemMillis() -> Int {
        let valid = samples.filter { $0 <= Self.anomalyThresholdMs }
        guard !valid.isEmpty else { return pass.coldStartMillis }
        let window = valid.suffix(Self.windowSize).sorted()
        let n = window.count
        let mid = n / 2
        if n % 2 == 1 { return window[mid] }
        return (window[mid - 1] + window[mid]) / 2
    }

    func estimateMillis(remaining: Int) -> Int {
        guard remaining > 0 else { return 0 }
        return perItemMillis() * remaining
    }
}
