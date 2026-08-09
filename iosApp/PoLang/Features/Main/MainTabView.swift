import SwiftUI
import UIKit

/// 对标 Android MainPagerHost：横滑 Pager（Camera first / Gallery second）
struct MainTabView: View {
    @State private var currentPage = 0
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        TabView(selection: $currentPage) {
            // 🔴 CameraPreviewView 自身 body 已有 .ignoresSafeArea(.all)
            CameraPreviewView()
                .environmentObject(container)
                .tag(0)

            GalleryGridView(repository: container.mediaRepository)
                .environmentObject(container)
                .tag(1)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea(.all) // 🔴 整个 Pager ignoresSafeArea — 允许页面内容延伸到安全区
        .overlay(alignment: .top) {
            DebugOverlayView()
        }
        .preferredColorScheme(.dark)
    }
}

#Preview {
    MainTabView()
        .environmentObject(AppContainer.shared)
}
