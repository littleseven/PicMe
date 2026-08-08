import SwiftUI
import SharedKit

/// 大图浏览（对标 Android MediaPager）：左右滑动切换，原图档异步加载。
struct MediaPagerView: View {
    let items: [MediaAsset]
    @State private var selection: String

    init(items: [MediaAsset], initial: String) {
        self.items = items
        _selection = State(initialValue: initial)
    }

    var body: some View {
        TabView(selection: $selection) {
            ForEach(items, id: \.uri) { asset in
                FullImageView(localIdentifier: asset.uri)
                    .tag(asset.uri)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .automatic))
        .background(.black)
        .accessibilityIdentifier("media_pager")
    }
}

private struct FullImageView: View {
    let localIdentifier: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                ProgressView()
            }
        }
        .task {
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 1200, height: 1200),  // 接近屏宽的原图档
                highQuality: true)                        // 大图要高清档（🟡-8）
        }
    }
}
