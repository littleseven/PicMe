import SwiftUI
import SharedKit

/// 相册网格页（对齐 Android `GalleryScreen.kt` + `MediaGrid.kt`，量化基准 = dump 1200px/360dp）：
/// - 自建 48pt `AppTopBar`（去系统 NavigationStack 大标题），操作组对齐 GalleryTopBar：
///   模型中心/扫描/搜索/设置依赖 TAG/VLM 管线（iOS Phase 6 才落地），灰置降级不假造交互；
///   分组菜单扁平 6 项（对齐 dump 下拉：全部/日期/人脸/人物/风景/地点），NONE/DATE 实做，
///   其余依赖 Phase 6 索引数据灰置。
/// - 网格：**固定 3 列**（dump 实测 3 列、间距/边距 7px≈2dp；列数固定、格宽 = 屏宽/列数导出）。
/// - 长按进选择模式（对齐 gallery_longpress dump）：顶栏 morph 为 返回/已选 N 项/全选/分享/删除，
///   缩略图右上角勾选圈（未选灰圈/选中蓝底白勾+浅蓝遮罩）。
/// - 冷启动 `SplashPlaceholder`、空相册 `EmptyGalleryMessage`、权限四态 `PermissionMessageView`。
/// [I18N] 所有文案走 String(localized:)，键为英文原文，入 Localizable.xcstrings 三语。
struct GalleryGridView: View {
    @StateObject private var vm: GalleryViewModel
    @StateObject private var permission = GalleryPermissionStore()
    /// 全屏大图页：nil = 关闭，否则为初始素材 localIdentifier
    @State private var pagerInitial: String? = nil
    /// 选择模式（gallery_longpress 对齐）
    @State private var isSelectionMode = false
    @State private var selected: Set<String> = []
    @State private var sharePayload: SharePayload? = nil
    @State private var showDeleteConfirm = false
    /// 删除直调 Swift 桥（PHAssetChangeRequest 自带系统确认；成功后观察者驱动网格刷新）
    private let bridge = PhMediaBridge()

    /// Preview 专用：注入权限态与跳过系统权限查询（Preview 环境无授权上下文）
    private let permissionOverride: GalleryAccessState?

    /// 固定 3 列（dump：1200px 屏宽 3×391px 列 + 7px 间距/边距 ≈ 2dp）
    private let columns = [GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2)]

    init(repository: IosMediaRepository, permissionOverride: GalleryAccessState? = nil) {
        _vm = StateObject(wrappedValue: GalleryViewModel(repository: repository))
        self.permissionOverride = permissionOverride
    }

    private var accessState: GalleryAccessState { permissionOverride ?? permission.state }
    private var allItems: [MediaAsset] { vm.groups.flatMap(\.items) }

    var body: some View {
        ZStack {
            // §1.3：状态栏区填 surface 色（勿露黑底），内容仍锚 safe area 下缘
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 0) {
                if isSelectionMode {
                    selectionTopBar
                } else {
                    normalTopBar
                }
                content
            }
        }
        .fullScreenCover(isPresented: Binding(
            get: { pagerInitial != nil },
            set: { if !$0 { pagerInitial = nil } })) {
                MediaPagerView(items: allItems, initial: pagerInitial ?? "")
            }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: payload.images)
        }
        .confirmationDialog(deleteConfirmTitle, isPresented: $showDeleteConfirm,
                            titleVisibility: .visible) {
            Button(String(localized: "Delete"), role: .destructive) { deleteSelected() }
            Button(String(localized: "Cancel"), role: .cancel) {}
        }
        .onAppear {
            if permissionOverride == nil { permission.refresh() }
            vm.start()
        }
        .onDisappear { vm.stop() }
    }

    // MARK: - 顶栏（常态 / 选择态 morph，对齐 gallery_grid / gallery_longpress dump）

    /// 常态顶栏：标题 + 5 操作组（模型中心/扫描/搜索/分组/设置；无管线者灰置）
    private var normalTopBar: some View {
        AppTopBar(title: String(localized: "Gallery")) {
            AppTopBarAction(systemName: "icloud.and.arrow.down",
                            accessibilityID: "topbar_model_center", isEnabled: false) {}
            AppTopBarAction(systemName: "play.circle",
                            accessibilityID: "topbar_scan", isEnabled: false) {}
            AppTopBarAction(systemName: "magnifyingglass",
                            accessibilityID: "topbar_search", isEnabled: false) {}
            groupingMenu
            AppTopBarAction(systemName: "gearshape",
                            accessibilityID: "topbar_settings", isEnabled: false) {}
        }
    }

    /// 选择态顶栏（dump：返回 ← + "已选择 N 项" + 全选/分享/删除，原位 morph）
    private var selectionTopBar: some View {
        AppTopBar(title: String(format: String(localized: "Selected %lld"), selected.count),
                  showsBackButton: true,
                  onBack: { exitSelectionMode() }) {
            AppTopBarAction(systemName: "square.dashed",
                            accessibilityID: "topbar_select_all") { toggleSelectAll() }
            AppTopBarAction(systemName: "square.and.arrow.up",
                            accessibilityID: "topbar_share",
                            isEnabled: !selected.isEmpty) { shareSelected() }
            AppTopBarAction(systemName: "trash",
                            accessibilityID: "topbar_delete",
                            isEnabled: !selected.isEmpty) { showDeleteConfirm = true }
        }
    }

    /// 分组菜单（对齐 dump gallery_grouping_dropdown：扁平 6 项、当前项带 ✓；
    /// 人脸/人物/风景/地点依赖 Phase 6 人脸/标签/位置索引数据，列出但灰置）
    private var groupingMenu: some View {
        Menu {
            menuItem(String(localized: "All"), mode: .none)
            menuItem(String(localized: "Date"), mode: .date)
            Button {} label: { Text(String(localized: "Face")) }.disabled(true)
            Button {} label: { Text(String(localized: "Person")) }.disabled(true)
            Button {} label: { Text(String(localized: "Landscape")) }.disabled(true)
            Button {} label: { Text(String(localized: "Location")) }.disabled(true)
        } label: {
            Image(systemName: "line.3.horizontal.decrease")  // Android Sort 图标的 SF 语义映射
                .font(.system(size: 22))
                .foregroundStyle(Color.primary)  // dump：图标深色（非 accent）
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        // Menu 内建按钮样式自带横向 padding（会把后续按钮挤出栏外），定死 36 框
        .frame(width: 36, height: 36)
        .menuIndicator(.hidden)
        .accessibilityIdentifier("topbar_grouping")
    }

    private func menuItem(_ title: String, mode: GalleryViewModel.GroupingMode) -> some View {
        Button {
            vm.groupingMode = mode
        } label: {
            if vm.groupingMode == mode {
                Label(title, systemImage: "checkmark")
            } else {
                Text(title)
            }
        }
    }

    // MARK: - 内容区

    @ViewBuilder
    private var content: some View {
        switch accessState {
        case .full, .limited:
            if vm.isLoading {
                SplashPlaceholder()
            } else if vm.groups.isEmpty {
                EmptyGalleryMessage(text: String(localized: "No media found"))
            } else {
                // 显式 VStack 容器：防 ScrollView 贪婪占满把 Limited banner 挤出可视区（🟡-6）
                VStack(spacing: 0) {
                    gridBody
                    if accessState == .limited { limitedBanner }
                }
            }
        case .notDetermined:
            PermissionMessageView(
                title: String(localized: "Gallery Access"),
                description: String(localized: "Allow access to browse and manage your photos and videos"),
                primaryButton: .init(title: String(localized: "Grant Permissions"),
                                     accessibilityID: "gallery_auth_button") {
                    Task { await permission.requestAccess() }
                })
        case .addOnly:
            PermissionMessageView(
                title: String(localized: "Gallery Access"),
                description: String(localized: "Add-Only Access Hint"),
                primaryButton: .init(title: String(localized: "Open Settings"),
                                     accessibilityID: "gallery_addonly_settings") {
                    openSystemSettings()
                })
            .accessibilityIdentifier("gallery_addonly_hint")
        case .denied:
            PermissionMessageView(
                title: String(localized: "Photo Library Unavailable"),
                description: String(localized: "Allow access to browse and manage your photos and videos"),
                primaryButton: .init(title: String(localized: "Open Settings")) {
                    openSystemSettings()
                })
            .accessibilityIdentifier("gallery_denied")
        }
    }

    private var gridBody: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: .sectionHeaders) {
                ForEach(vm.groups) { group in
                    if group.id.isEmpty {
                        Section { cells(for: group.items) }
                    } else {
                        Section(header: GroupHeaderView(title: group.id,
                                                        count: group.items.count)) {
                            cells(for: group.items)
                        }
                    }
                }
            }
            .padding(2)  // dump：边距 7px≈2dp
        }
        .accessibilityIdentifier("gallery_grid")
    }

    private func cells(for items: [MediaAsset]) -> some View {
        ForEach(items, id: \.uri) { asset in
            Button {
                if isSelectionMode {
                    toggleSelection(asset.uri)
                } else {
                    pagerInitial = asset.uri
                }
            } label: {
                ThumbnailView(localIdentifier: asset.uri,
                              faceFocusY: asset.faceFocusY?.floatValue,
                              isVideo: asset.type == MediaType.video)
                    .overlay { selectionOverlay(for: asset.uri) }
            }
            .buttonStyle(.plain)
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                if !isSelectionMode {
                    isSelectionMode = true
                    selected = [asset.uri]
                }
            })
            .accessibilityIdentifier("cell_\(asset.uri)")
        }
    }

    /// 勾选覆盖层（对齐 gallery_longpress 截图：选择态下每格右上角 24dp 圈；
    /// 未选=灰圈描边，选中=accentColor 底白勾 + 浅蓝遮罩）
    @ViewBuilder
    private func selectionOverlay(for uri: String) -> some View {
        if isSelectionMode {
            ZStack {
                if selected.contains(uri) {
                    Color.accentColor.opacity(0.25)
                }
                VStack {
                    HStack {
                        Spacer()
                        Image(systemName: selected.contains(uri)
                              ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 24))
                            .foregroundStyle(selected.contains(uri)
                                             ? Color.accentColor : Color.white.opacity(0.9))
                            .shadow(radius: 1)
                            .padding(6)
                    }
                    Spacer()
                }
            }
            .allowsHitTesting(false)  // 手势归 cell Button，覆盖层纯展示
        }
    }

    // MARK: - 选择模式操作

    private func toggleSelection(_ uri: String) {
        if selected.contains(uri) {
            selected.remove(uri)
        } else {
            selected.insert(uri)
        }
    }

    private func exitSelectionMode() {
        isSelectionMode = false
        selected = []
    }

    private func toggleSelectAll() {
        if selected.count == allItems.count {
            selected = []
        } else {
            selected = Set(allItems.map(\.uri))
        }
    }

    private var deleteConfirmTitle: String {
        selected.count == 1
            ? String(localized: "Delete this photo?")
            : String(format: String(localized: "Delete %lld photos?"), selected.count)
    }

    private func shareSelected() {
        let uris = allItems.map(\.uri).filter { selected.contains($0) }  // 保持网格序
        Task {
            var images: [UIImage] = []
            for uri in uris {
                if let image = await ThumbnailLoader.shared.thumbnail(
                    for: uri, size: CGSize(width: 1000, height: 1000), highQuality: true) {
                    images.append(image)
                }
            }
            if !images.isEmpty {
                sharePayload = SharePayload(images: images)
            }
        }
    }

    private func deleteSelected() {
        _ = bridge.deleteMedia(localIdentifiers: Array(selected))
        exitSelectionMode()  // 网格经 PHPhotoLibraryObserver 自动刷新
    }

    // MARK: - Limited / 权限

    private var limitedBanner: some View {
        Button(String(localized: "Manage Accessible Photos")) {
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let vc = scene.windows.first?.rootViewController {
                permission.presentLimitedLibraryPicker(from: vc)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemBackground))
        .accessibilityIdentifier("gallery_limited_manage")
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

#if DEBUG
/// Preview 桩 bridge：可配数据集与权限态，不走 Photos。
private final class GalleryPreviewBridge: NSObject, IosMediaRepositoryBridge {
    var items: [IosMediaItem] = []
    func currentAccessState() -> AccessState { AccessStateFull.shared }
    func fetchAllMedia() -> [IosMediaItem] { items }
    func requestReadWriteAuthorization() {}
    func addChangeListener(listener: @escaping () -> Void) {}
    func removeChangeListener() {}
    func deleteMedia(localIdentifiers: [String]) -> Bool { true }
}

private func previewRepository(itemCount: Int) -> IosMediaRepository {
    let bridge = GalleryPreviewBridge()
    let dayMs: Int64 = 86_400_000
    let base: Int64 = 1_786_046_400_000  // 2026-08-07 12:00 UTC
    var items: [IosMediaItem] = []
    items.reserveCapacity(itemCount)
    for i in 0..<itemCount {
        let isVideo = i % 7 == 3
        let duration: KotlinLong? = isVideo ? KotlinLong(longLong: 5_000) : nil
        let item = IosMediaItem(localIdentifier: "P-\(i)",
                                mediaType: isVideo ? "VIDEO" : "PHOTO",
                                captureDateMs: base - Int64(i) * dayMs / 3,
                                durationMs: duration,
                                fileName: String(format: "IMG_%04d.jpg", i))
        items.append(item)
    }
    bridge.items = items
    return IosMediaRepository(bridge: bridge)
}

#Preview("空相册") {
    GalleryGridView(repository: previewRepository(itemCount: 0),
                    permissionOverride: .full)
}

#Preview("1000 图按日分组") {
    GalleryGridView(repository: previewRepository(itemCount: 1000),
                    permissionOverride: .full)
}

#Preview("Limited") {
    GalleryGridView(repository: previewRepository(itemCount: 12),
                    permissionOverride: .limited)
}

#Preview("未授权") {
    GalleryGridView(repository: previewRepository(itemCount: 0),
                    permissionOverride: .notDetermined)
}
#endif
