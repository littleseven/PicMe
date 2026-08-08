import SwiftUI
import SharedKit

/// 相册网格页（对齐 Android `GalleryScreen.kt` + `MediaGrid.kt` 重做，非打补丁）：
/// - 自建 48pt `AppTopBar`（去系统 NavigationStack 大标题），操作组对齐 Android GalleryTopBar：
///   模型中心/扫描/搜索依赖 TAG/VLM 管线（iOS Phase 6 才落地），灰置降级不假造交互；
///   分组菜单实做 NONE/DATE（FACE 等依赖 Phase 6 索引数据，菜单内列出但灰置）。
/// - 网格：`GridItem(.adaptive(minimum: 110))` + 间距 2 + contentPadding 2，按日 pinned 分组头。
/// - 冷启动 `SplashPlaceholder`（isLoading）、空相册 `EmptyGalleryMessage`、
///   权限四态 `PermissionMessageView`（notDetermined/denied/addOnly 引导，Limited 网格+常驻管理条）。
/// [I18N] 所有文案走 String(localized:)，键为英文原文，入 Localizable.xcstrings 三语。
struct GalleryGridView: View {
    @StateObject private var vm: GalleryViewModel
    @StateObject private var permission = GalleryPermissionStore()
    /// 全屏大图页：nil = 关闭，否则为初始素材 localIdentifier
    @State private var pagerInitial: String? = nil

    /// Preview 专用：注入权限态与跳过系统权限查询（Preview 环境无授权上下文）
    private let permissionOverride: GalleryAccessState?

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 2)]

    init(repository: IosMediaRepository, permissionOverride: GalleryAccessState? = nil) {
        _vm = StateObject(wrappedValue: GalleryViewModel(repository: repository))
        self.permissionOverride = permissionOverride
    }

    private var accessState: GalleryAccessState { permissionOverride ?? permission.state }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: String(localized: "Gallery")) {
                // ↓ 对齐 Android GalleryTopBar 操作组顺序：模型中心/扫描/搜索/分组/设置
                // 模型中心/扫描/搜索/设置依赖 TAG/VLM/Settings 管线（iOS Phase 6），灰置降级
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
            content
        }
        .background(Color(.systemBackground))
        .fullScreenCover(isPresented: Binding(
            get: { pagerInitial != nil },
            set: { if !$0 { pagerInitial = nil } })) {
                MediaPagerView(items: vm.groups.flatMap(\.items),
                               initial: pagerInitial ?? "")
            }
        .onAppear {
            if permissionOverride == nil { permission.refresh() }
            vm.start()
        }
        .onDisappear { vm.stop() }
    }

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

    /// 分组菜单（对齐 Android GalleryTopBar 的 Sort DropdownMenu）：
    /// NONE/DATE 实做；FACE/PERSON/LANDSCAPE/LOCATION 依赖 Phase 6 人脸/标签/位置索引，列出但灰置。
    private var groupingMenu: some View {
        Menu {
            Section(String(localized: "Group By")) {
                menuItem(String(localized: "All"), mode: .none)
                menuItem(String(localized: "Date"), mode: .date)
            }
            Section {
                Text(String(localized: "Face"))
                Text(String(localized: "Person"))
                Text(String(localized: "Landscape"))
                Text(String(localized: "Location"))
            } header: {
                Text(String(localized: "Requires on-device indexing"))
            }
            .disabled(true)
        } label: {
            Image(systemName: "arrow.up.arrow.down")
                .font(.system(size: 20))
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
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
            .padding(2)  // 对齐 Android contentPadding 2dp
        }
        .accessibilityIdentifier("gallery_grid")
    }

    private func cells(for items: [MediaAsset]) -> some View {
        ForEach(items, id: \.uri) { asset in
            Button {
                pagerInitial = asset.uri
            } label: {
                ThumbnailView(localIdentifier: asset.uri,
                              faceFocusY: asset.faceFocusY?.floatValue,
                              isVideo: asset.type == MediaType.video)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("cell_\(asset.uri)")
        }
    }

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
