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
        let id: String          // "yyyy-MM-dd"；GroupingMode.none 时为 ""（无分组头）
        let items: [MediaAsset]
    }

    /// 分组模式（对齐 Android GroupingMode）：none=全部 / date=按日 /
    /// face=按是否含人脸（接 Pass1 hasFace）/ person·landscape·location 数据依赖后续扫描阶段，
    /// 先以占位分组开放下拉项。
    enum GroupingMode: String {
        case none, date, face, person, landscape, location
    }

    @Published private(set) var groups: [DayGroup] = []
    @Published private(set) var isLoading = true
    @Published var groupingMode: GroupingMode = .date {
        didSet { applyGrouping(lastAssets) }
    }

    private var watchTask: Task<Void, Never>?
    private var lastAssets: [MediaAsset] = []
    private let repository: IosMediaRepository

    init(repository: IosMediaRepository) {
        self.repository = repository
    }

    func start() {
        watchTask?.cancel()  // onAppear 多次触发时先取消旧订阅，防协程泄漏（🟡-3）
        // SKIE 直消费（spike/skie 迁移实证）：SkieSwiftFlow<[MediaAsset]> 即 AsyncSequence，
        // 泛型保留免强转；Task 取消经 SKIE 双向取消传播回 Kotlin 协程。
        watchTask = Task { @MainActor [weak self] in
            guard let repository = self?.repository else { return }
            for await assets in repository.allMedia {
                self?.applyGrouping(assets)
            }
        }
    }

    func stop() {
        watchTask?.cancel()
        watchTask = nil
    }

    /// 分组：date=按日降序（字典插入序 = 输入序 = 降序，组内顺序保持）；
    /// none=全部一组（id 为空串，网格不渲染分组头）。
    private func applyGrouping(_ assets: [MediaAsset]) {
        lastAssets = assets
        switch groupingMode {
        case .none:
            groups = assets.isEmpty ? [] : [DayGroup(id: "", items: assets)]
        case .date:
            let fmt = DateFormatter()
            fmt.dateFormat = "yyyy-MM-dd"
            var map: [String: [MediaAsset]] = [:]
            for a in assets {
                let key = fmt.string(from: Date(timeIntervalSince1970: TimeInterval(a.captureDate) / 1000))
                map[key, default: []].append(a)
            }
            groups = map.sorted { $0.key > $1.key }.map { DayGroup(id: $0.key, items: $0.value) }
        case .face:
            // 接 Pass1 扫描产出（media_assets.hasFace）：含脸 / 无脸 两组。
            let faceSet = TagDatabase.shared.hasFaceLocalIdentifiers()
            var withFace: [MediaAsset] = []
            var noFace: [MediaAsset] = []
            for a in assets {
                if faceSet.contains(a.uri) { withFace.append(a) } else { noFace.append(a) }
            }
            var gs: [DayGroup] = []
            if !withFace.isEmpty {
                gs.append(DayGroup(id: NSLocalizedString("gallery_group_with_face", comment: ""), items: withFace))
            }
            if !noFace.isEmpty {
                gs.append(DayGroup(id: NSLocalizedString("gallery_group_no_face", comment: ""), items: noFace))
            }
            groups = gs
        case .person:
            // 接 Pass2 聚类产出（media_assets.faceId）：按人物分组。
            let faceIdMap = TagDatabase.shared.faceIdByLocalIdentifier()
            var byPerson: [String: [MediaAsset]] = [:]
            var none: [MediaAsset] = []
            for a in assets {
                if let fid = faceIdMap[a.uri], !fid.isEmpty {
                    byPerson[fid, default: []].append(a)
                } else {
                    none.append(a)
                }
            }
            var gs: [DayGroup] = []
            for (fid, items) in byPerson.sorted(by: { $0.value.count > $1.value.count }) {
                gs.append(DayGroup(id: NSLocalizedString("gallery_group_person_label", comment: "") + " \(fid)",
                                   items: items))
            }
            if !none.isEmpty {
                gs.append(DayGroup(id: NSLocalizedString("gallery_group_person_none", comment: ""), items: none))
            }
            groups = gs
        case .landscape, .location:
            // 数据依赖后续扫描阶段（内容标签 Pass3 / 地理），先以占位分组开放下拉项。
            let key: String = groupingMode == .landscape
                ? NSLocalizedString("gallery_group_landscape_pending", comment: "")
                : NSLocalizedString("gallery_group_location_pending", comment: "")
            groups = assets.isEmpty ? [] : [DayGroup(id: key, items: assets)]
        }
        isLoading = false
        DebugOverlayState.shared.set("gallery.count", "\(assets.count)")
    }
}
