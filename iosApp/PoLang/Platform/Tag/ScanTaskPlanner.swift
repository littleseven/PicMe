import Foundation

/// 扫描模式（对齐 Android SCAN_ALL / SCAN_INCREMENTAL）。
enum ScanMode: String, Sendable { case incremental, full }

enum ScanTaskPlanner {
    /// 计算 Pass1 待扫 mediaId 列表（保持输入顺序）。
    /// - incremental：剔除已覆盖 pass1 的 mediaId（去重）。
    /// - full：全部重扫。
    static func pass1TaskIds(
        allImageMediaIds: [Int64],
        pass1CoveredMediaIds: Set<Int64>,
        mode: ScanMode
    ) -> [Int64] {
        switch mode {
        case .full:
            return allImageMediaIds
        case .incremental:
            return allImageMediaIds.filter { !pass1CoveredMediaIds.contains($0) }
        }
    }
}
