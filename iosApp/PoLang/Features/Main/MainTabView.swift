import SwiftUI
import UIKit

/// 对标 Android MainPagerHost：横滑 Pager（Camera first / Gallery second）
/// 沉浸式全屏，无 tab bar。Chat / People 页留后续 Phase。
struct MainTabView: View {
    @State private var currentPage = 0
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        SwipePager(pages: [
            AnyView(
                CameraPreviewView()
                    .environmentObject(container)
                    .ignoresSafeArea()
            ),
            AnyView(
                GalleryGridView(repository: container.mediaRepository)
                    .environmentObject(container)
            ),
        ], currentPage: $currentPage)
        .ignoresSafeArea()
        .overlay(alignment: .topLeading) {
            DebugOverlayView()
        }
    }
}

/// 轻量横滑 Pager（替代 TabView，对标 Android HorizontalPager）
struct SwipePager<Page: View>: View {
    let pages: [Page]
    @Binding var currentPage: Int

    var body: some View {
        TabView(selection: $currentPage) {
            ForEach(pages.indices, id: \.self) { i in
                pages[i]
                    .tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
    }
}

#Preview {
    MainTabView()
        .environmentObject(AppContainer.shared)
}
