import SwiftUI
import UIKit
import SharedKit

/// 对标 Android MainPagerHost：4 页 Pager（相机/相册/聊天/人物）
/// 相册(1)为初始页，悬浮 Tab 切换页
struct MainTabView: View {
    @State private var currentPage = 1 // 初始页 = 相册（对标 Android）
    @EnvironmentObject private var container: AppContainer
    @State private var showPlaceholder: String?

    var body: some View {
        ZStack {
            // 相册页（初始页，常驻 — 含悬浮 Tab）
            GalleryGridView(repository: container.mediaRepository)
                .environmentObject(container)
                .overlay(alignment: .bottom) {
                    if currentPage == 1 {
                        FloatingBottomTab(currentPage: $currentPage, onPlaceholderTap: { icon in
                            showPlaceholder = icon
                        })
                        .padding(.bottom, 16)
                    }
                }

            // 相机页（全出血，覆盖在相册之上时可见）
            if currentPage == 0 {
                CameraPreviewView()
                    .environmentObject(container)
            }

            // 聊天页（Phase 6.2）
            if currentPage == 2 {
                ChatView()
                    .environmentObject(container)
            }
            if currentPage == 3 {
                PlaceholderPage(title: String(localized: "People Coming Soon"))
            }

            // 打标占位 push
            if let ph = showPlaceholder {
                PlaceholderPage(title: ph == "tag" ? String(localized: "Tag Scan Coming Soon") : String(localized: "Coming Soon"))
                    .transition(.opacity)
            }
        }
        // 翻页同步 SceneManager（chat 工具按场景路由，不同步会被入队不执行）
        .onAppear {
            IosAgentComposition.shared.onMainPageChanged(page: Int64(currentPage))
        }
        .onChange(of: currentPage) { page in
            showPlaceholder = nil
            IosAgentComposition.shared.onMainPageChanged(page: Int64(page))
        }
        .overlay(alignment: .top) {
            DebugOverlayView()
        }
    }
}

/// 占位页（诚实占位，不造假功能）
struct PlaceholderPage: View {
    let title: String

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 12) {
                MatIcon(name: "hourglass", size: 36)
                    .foregroundColor(.white.opacity(0.3))
                Text(title)
                    .font(.system(size: 16))
                    .foregroundColor(.white.opacity(0.5))
            }
        }
    }
}

#Preview {
    MainTabView()
        .environmentObject(AppContainer.shared)
}
