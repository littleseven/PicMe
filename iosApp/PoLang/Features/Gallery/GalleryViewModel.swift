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
    /// uri(localIdentifier) → faceFocusY，供网格缩略图人脸感知裁切（防「砍头杀」，spec R3）。
    /// iOS 的 faceFocusY 存独立 TagDatabase（Pass1 写），PHAsset 不携带；相册 MediaAsset 不带此字段，
    /// 故在此批量读一次注入渲染。对齐 Android：Room 驱动的 MediaAsset.faceFocusY 由 tag 生成回填。
    @Published private(set) var faceFocusYMap: [String: Float] = [:]
    @Published var groupingMode: GroupingMode = .date {
        didSet { applyGrouping(lastAssets) }
    }

    // MARK: - 搜索状态（spec §search_top_bar / §search_results / §search_no_result）

    /// 搜索框文本（双向绑定 SearchTopBar）
    @Published var searchQuery = ""
    /// 搜索模式是否激活（顶栏切换 + 网格替换）
    @Published private(set) var isSearchActive = false
    /// 搜索结果（映射回 MediaAsset；空 = 无结果）
    @Published private(set) var searchResults: [MediaAsset] = []
    /// 全屏 Loading（仅首次搜索、无旧结果时显示，防闪烁）
    @Published private(set) var isSearchLoading = false
    /// 是否已完成至少一次搜索（控制 trailing resultCount 可见性）
    @Published private(set) var hasSearched = false

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
        searchTask?.cancel()
        searchTask = nil
    }

    // MARK: - 搜索（spec §search_top_bar / §search_results / states.search_active）

    private var searchTask: Task<Void, Never>?
    /// 最近一次实际提交搜索的（trimmed）查询串，用于结果标题/空态文案
    private(set) var lastSearchQuery = ""

    /// 激活搜索态（spec states.search_active: 顶栏→SearchTopBar，网格→搜索结果）
    func enterSearch() {
        isSearchActive = true
        searchQuery = ""
        searchResults = []
        isSearchLoading = false
        hasSearched = false
        lastSearchQuery = ""
    }

    /// 退出搜索态（spec back_stack search_active → clear_search_and_close_search_bar）
    func exitSearch() {
        searchTask?.cancel()
        searchTask = nil
        isSearchActive = false
        searchQuery = ""
        searchResults = []
        isSearchLoading = false
        hasSearched = false
        lastSearchQuery = ""
    }

    /// 查询文本变化回调：防抖 300ms（spec motion.searchDebounceMs），query 非空白才触发搜索。
    /// - 空白 → 立即清空结果、不 Loading
    /// - 非空白 → debounce 后搜索；首次搜索（无旧结果）显示全屏 Loading，有旧结果时不显示（防闪烁）
    func handleQueryChange(_ query: String) {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchResults = []
            isSearchLoading = false
            hasSearched = false
            return
        }
        // 在 debounce 前捕获，判断是否首次搜索（有旧结果则不闪烁）
        let firstSearch = searchResults.isEmpty
        searchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(AppMotion.searchDebounceMs * 1_000_000))
            if Task.isCancelled { return }
            if firstSearch { self.isSearchLoading = true }
            self.lastSearchQuery = trimmed
            let lang = Self.currentLang()
            let rows = await MediaSearchEngine.shared.search(query: trimmed, lang: lang)
            if Task.isCancelled { return }
            // 映射 SearchMediaRow.localIdentifier → 现有 MediaAsset（按引擎 mergeAndRank 分数序；
            // DB 有记录但 PHAsset 不在当前相册的条目跳过）
            let assetById = Dictionary(self.lastAssets.map { ($0.uri, $0) },
                                       uniquingKeysWith: { first, _ in first })
            self.searchResults = rows.compactMap { assetById[$0.localIdentifier] }
            self.isSearchLoading = false
            self.hasSearched = true
        }
    }

    /// 搜索结果分组（spec §search_results.group_header：标题 '搜索 "{query}"（{count} 张）'）
    var searchGroup: DayGroup? {
        guard isSearchActive && !searchResults.isEmpty else { return nil }
        let title = String(format: NSLocalizedString("gallery_search_results_title", comment: ""),
                           lastSearchQuery, searchResults.count)
        return DayGroup(id: title, items: searchResults)
    }

    /// 搜索语言（契约：引擎词表/停用词按 zh/en 二分；西语/法语回退 en）。
    /// 对齐 Android 语义：显式英文/西语/法语 → "en"，显式中文 → "zh"；跟随系统时按系统语言判 en/zh。
    private static func currentLang() -> String {
        switch LanguageManager.shared.currentLanguage {
        case "english", "spanish", "french":
            return "en"
        case "chinese_simplified", "chinese_traditional":
            return "zh"
        default:
            let code = Locale.current.language.languageCode?.identifier
            return (code == "en" || code == "es" || code == "fr") ? "en" : "zh"
        }
    }

    /// 分组：date=按日降序（字典插入序 = 输入序 = 降序，组内顺序保持）；
    /// none=全部一组（id 为空串，网格不渲染分组头）。
    private func applyGrouping(_ assets: [MediaAsset]) {
        lastAssets = assets
        // 人脸感知裁切数据注入：每次资产刷新批量读一次 faceFocusY（一张 SELECT，仅含已扫描人脸行）。
        faceFocusYMap = TagDatabase.shared.faceFocusYByLocalIdentifier()
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
        case .landscape:
            // 风景筛选：labels（Pass3）命中风景关键词 → 唯一「风景」组；无命中则空
            // （对齐 Android GetGroupedMediaUseCase LANDSCAPE：筛选进单组，非按标签分组）
            let labelsMap = TagDatabase.shared.labelsByLocalIdentifier()
            let landscapes = assets.filter { asset in
                guard let json = labelsMap[asset.uri] else { return false }
                return !Self.landscapeKeywords.isDisjoint(with: Self.labelSet(fromLabelsJson: json))
            }
            groups = landscapes.isEmpty
                ? []
                : [DayGroup(id: NSLocalizedString("gallery_group_landscape", comment: ""), items: landscapes)]
        case .location:
            // 按城市分组（media_assets.city）+ 无位置兜底组（对齐 Android LOCATION/NO_LOCATION）
            let cityMap = TagDatabase.shared.cityByLocalIdentifier()
            var byCity: [String: [MediaAsset]] = [:]
            var noCity: [MediaAsset] = []
            for asset in assets {
                if let city = cityMap[asset.uri], !city.isEmpty {
                    byCity[city, default: []].append(asset)
                } else {
                    noCity.append(asset)
                }
            }
            var gs: [DayGroup] = byCity
                .sorted { $0.value.count > $1.value.count }
                .map { DayGroup(id: $0.key, items: $0.value) }
            if !noCity.isEmpty {
                gs.append(DayGroup(id: NSLocalizedString("gallery_group_no_location", comment: ""), items: noCity))
            }
            groups = gs
        }
        isLoading = false
        DebugOverlayState.shared.set("gallery.count", "\(assets.count)")
    }

    // MARK: - 风景分组关键词（对齐 Android LANDSCAPE_SCENES，controlled_vocab 自然/城市/户外场景词）

    /// labels JSON {scene, activity, objects[], tags[]} → 全部标签小写集合
    private static func labelSet(fromLabelsJson json: String) -> Set<String> {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        var labels: [String] = []
        if let scene = obj["scene"] as? String, !scene.isEmpty { labels.append(scene) }
        if let activity = obj["activity"] as? String, !activity.isEmpty { labels.append(activity) }
        if let objects = obj["objects"] as? [String] { labels.append(contentsOf: objects) }
        if let tags = obj["tags"] as? [String] { labels.append(contentsOf: tags) }
        return Set(labels.map { $0.lowercased() })
    }

    private static let landscapeKeywords: Set<String> = [
        "风景", "户外", "公园", "街道", "海边", "山脉", "城市", "乡村", "花园", "阳台",
        "河边", "森林", "雪地", "沙漠", "泳池", "田野", "草原", "湖边", "瀑布", "隧道",
        "桥下", "天桥", "花田", "竹林", "枫林", "茶园", "古镇", "寺庙", "教堂", "植物园",
        "动物园", "游乐园", "水族馆", "广场", "海滩", "庭院", "操场", "农家",
        "landscape", "outdoor", "park", "street", "seaside", "mountains", "city", "countryside",
        "garden", "balcony", "riverside", "forest", "snowfield", "desert", "swimming pool",
        "field", "grassland", "lakeside", "waterfall", "tunnel", "under bridge", "overpass",
        "flower field", "bamboo forest", "maple forest", "tea plantation", "ancient town",
        "temple", "church", "botanical garden", "zoo", "amusement park", "aquarium",
        "square", "beach", "courtyard", "playground", "farmhouse",
    ]
}
