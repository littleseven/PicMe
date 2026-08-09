import SwiftUI
import Photos

/// 相簿列表（对标 Android 相簿页）：系统相簿 + 用户相簿。
/// 相簿取数是 presentation 自治的平台行为（spec S1），Swift 直连 Photos，不经 shared。
struct AlbumListView: View {
    struct Album: Identifiable {
        let id: String        // collection localIdentifier
        let title: String
        let count: Int
    }

    @State private var albums: [Album] = []

    var body: some View {
        List(albums) { album in
            HStack {
                Text(album.title)
                Spacer()
                Text("\(album.count)").foregroundStyle(.secondary)
            }
            .accessibilityIdentifier("album_\(album.id)")
        }
        .navigationTitle(String(localized: "Albums"))
        // 后台取数主线程赋值（🟡-9）：逐相簿 count 是 O(相簿数) 同步 Photos 查询，不得堵主线程
        .task {
            let result = await Task.detached(priority: .userInitiated) {
                Self.fetchAlbums()
            }.value
            albums = result
        }
    }

    private nonisolated static func fetchAlbums() -> [Album] {
        var result: [Album] = []
        let smart = PHAssetCollection.fetchAssetCollections(
            with: .smartAlbum, subtype: .any, options: nil)
        let user = PHAssetCollection.fetchAssetCollections(
            with: .album, subtype: .any, options: nil)
        [smart, user].forEach { list in
            list.enumerateObjects { collection, _, _ in
                let count = PHAsset.fetchAssets(in: collection, options: nil).count
                result.append(Album(id: collection.localIdentifier,
                                    title: collection.localizedTitle ?? "—",
                                    count: count))
            }
        }
        return result
    }
}

#Preview { NavigationStack { AlbumListView() } }
