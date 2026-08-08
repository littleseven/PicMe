import SwiftUI
import SharedKit

/// 相册网格页（对标 Android MediaGrid）：3 列 LazyVGrid + 按日分组 pinned header。
/// 权限四态 UI：Full/Limited 显网格（Limited 常驻管理入口）、notDetermined 引导授权、
/// addOnly 引导开权限、denied 空态 + 跳设置。
/// [I18N] 所有文案走 String(localized:)，键为英文原文，入 Localizable.xcstrings 三语。
struct GalleryGridView: View {
    @StateObject private var vm: GalleryViewModel
    @StateObject private var permission = GalleryPermissionStore()
    private let columns = [GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2)]

    init(repository: IosMediaRepository) {
        _vm = StateObject(wrappedValue: GalleryViewModel(repository: repository))
    }

    var body: some View {
        NavigationStack {
            Group {
                switch permission.state {
                case .full, .limited:
                    // 显式 VStack 容器：防 ScrollView 贪婪占满把 Limited banner 挤出可视区（🟡-6）
                    VStack(spacing: 0) {
                        gridBody
                        if permission.state == .limited { limitedBanner }
                    }
                case .notDetermined:
                    Button(String(localized: "Authorize Photo Access")) {
                        Task { await permission.requestAccess() }
                    }
                    .accessibilityIdentifier("gallery_auth_button")
                case .addOnly:
                    Text(String(localized: "Add-Only Access Hint"))
                        .accessibilityIdentifier("gallery_addonly_hint")
                case .denied:
                    deniedBody
                }
            }
            .navigationTitle(String(localized: "Gallery"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(String(localized: "Albums")) { AlbumListView() }
                        .accessibilityIdentifier("gallery_albums_entry")
                }
            }
        }
        .onAppear { permission.refresh(); vm.start() }
        .onDisappear { vm.stop() }
    }

    private var gridBody: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: .sectionHeaders) {
                ForEach(vm.groups) { group in
                    Section(header: Text(group.id)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8).background(.background)
                        .accessibilityIdentifier("group_\(group.id)")) {
                        ForEach(group.items, id: \.uri) { asset in
                            NavigationLink {
                                MediaPagerView(items: group.items, initial: asset.uri)
                            } label: {
                                ThumbnailView(localIdentifier: asset.uri,
                                              size: CGSize(width: 200, height: 200))
                            }
                            .accessibilityIdentifier("cell_\(asset.uri)")
                        }
                    }
                }
            }
        }
        .accessibilityIdentifier("gallery_grid")
    }

    private var limitedBanner: some View {
        Button(String(localized: "Manage Accessible Photos")) {
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let vc = scene.windows.first?.rootViewController {
                permission.presentLimitedLibraryPicker(from: vc)
            }
        }
        .accessibilityIdentifier("gallery_limited_manage")
    }

    private var deniedBody: some View {
        VStack(spacing: 12) {
            Text(String(localized: "Photo Library Unavailable"))
            Button(String(localized: "Open Settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        }
        .accessibilityIdentifier("gallery_denied")
    }
}
