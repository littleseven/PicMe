import SwiftUI
import UIKit

/// 对标 Android MainPagerHost：横滑 Pager（Camera first / Gallery second）
/// 沉浸式全屏，无 tab bar。Chat / People 页留后续 Phase。
struct MainTabView: View {
    @State private var currentPage = 0
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        SwipePager(pages: [
            // 🔴 相机页：ignoresSafeArea 仅作用于相机页，不污染相册页
            AnyView(
                CameraPreviewView()
                    .environmentObject(container)
                    .ignoresSafeArea(.all)
            ),
            // 相册页（K3 domain）：不 ignoresSafeArea，保留正常 safe area
            AnyView(
                GalleryGridView(repository: container.mediaRepository)
                    .environmentObject(container)
            ),
        ], currentPage: $currentPage)
        .overlay(alignment: .topLeading) {
            DebugOverlayView()
        }
        .preferredColorScheme(.dark) // 全局暗色主题（对标 Android PoLangForcedDarkTheme）
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
