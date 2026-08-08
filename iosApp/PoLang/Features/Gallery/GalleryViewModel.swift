import Foundation
import SharedKit
import Combine

/// UI 态唯一持有者（spec S4 单一状态源）。
/// 数据：shared MediaRepository Flow（FlowWatcher 订阅）；分组按日降序，
/// 与 Android GroupingMode.DATE 对齐（S5 双端一致：分组边界/排序/数量必须完全相同）。
/// shared GetGroupedMediaUseCase 在 commonMain 尚不存在（Task 0 核对结论），
/// 落地后替换 applyGrouping 为本用例的等价调用。
@MainActor
final class GalleryViewModel: ObservableObject {
    struct DayGroup: Identifiable, Equatable {
        let id: String          // "yyyy-MM-dd"
        let items: [MediaAsset]
    }

    @Published private(set) var groups: [DayGroup] = []
    @Published private(set) var isLoading = true

    private var watcher: FlowWatcher?
    private let repository: IosMediaRepository

    init(repository: IosMediaRepository) {
        self.repository = repository
    }

    func start() {
        watcher?.cancel()  // onAppear 多次触发时先取消旧订阅，防协程泄漏（🟡-3）
        watcher = FlowWatchersKt.watch(repository.allMedia) { [weak self] assets in
            let list = (assets as? [MediaAsset]) ?? []
            Task { @MainActor in
                self?.applyGrouping(list)
            }
        }
    }

    func stop() {
        watcher?.cancel()
        watcher = nil
    }

    /// 按日分组：captureDate 降序，同日一组（字典插入序 = 输入序 = 降序，组内顺序保持）。
    private func applyGrouping(_ assets: [MediaAsset]) {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        var map: [String: [MediaAsset]] = [:]
        for a in assets {
            let key = fmt.string(from: Date(timeIntervalSince1970: TimeInterval(a.captureDate) / 1000))
            map[key, default: []].append(a)
        }
        groups = map.sorted { $0.key > $1.key }.map { DayGroup(id: $0.key, items: $0.value) }
        isLoading = false
        DebugOverlayState.shared.set("gallery.count", "\(assets.count)")
    }
}
