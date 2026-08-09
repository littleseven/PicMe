import SwiftUI
import UIKit

/// 对标 Android MainPagerHost：4 页 Pager（相机/相册/聊天/人物）
/// 相册(1)为初始页，悬浮 Tab 切换页
struct MainTabView: View {
    // 初始页 = 相册（对标 Android）；UI 自动化可用 launch arg `-startPage <0-3>` 指定起始页
    @State private var currentPage: Int = {
        guard let idx = ProcessInfo.processInfo.arguments.firstIndex(of: "-startPage"),
              ProcessInfo.processInfo.arguments.count > idx + 1,
              let page = Int(ProcessInfo.processInfo.arguments[idx + 1]),
              (0...3).contains(page) else { return 1 }
        return page
    }()
    @EnvironmentObject private var container: AppContainer
    @State private var showPlaceholder: String?

    var body: some View {
        ZStack {
            // 相册页（初始页，常驻 — 含悬浮 Tab）
            GalleryGridView(repository: container.mediaRepository)
                .environmentObject(container)

            // 相机页（全出血，覆盖在相册之上时可见）
            if currentPage == 0 {
                CameraPreviewView(onGalleryTap: {
                    withAnimation(.easeInOut(duration: 0.25)) { currentPage = 1 }
                })
                    .environmentObject(container)
            }

            // 聊天/人物占位页
            if currentPage == 2 {
                PlaceholderPage(title: String(localized: "Chat Coming Soon"))
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
        .preferredColorScheme(.dark)
        // 切页时关闭打标占位 push，避免残留遮罩盖住目标页
        .onChange(of: currentPage) { _ in showPlaceholder = nil }
        // 悬浮 Tab：除相机页（沉浸式）外常驻，占位页也能切出
        .overlay(alignment: .bottom) {
            if currentPage != 0 {
                FloatingBottomTab(currentPage: $currentPage, onPlaceholderTap: { icon in
                    showPlaceholder = icon
                })
                .padding(.bottom, 16)
            }
        }
        // 全局左右滑切页（对标 Android HorizontalPager）：水平主导滑动手势切页；
        // 用 simultaneousGesture 保证不抢相机页的对焦/变焦/曝光手势（垂直拖动仍归曝光）
        .simultaneousGesture(
            DragGesture(minimumDistance: 40)
                .onEnded { value in
                    let dx = value.translation.width, dy = value.translation.height
                    guard abs(dx) > abs(dy) * 1.5, abs(dx) > 60 else { return }
                    withAnimation(.easeInOut(duration: 0.25)) {
                        if dx < 0, currentPage < 3 { currentPage += 1 }
                        else if dx > 0, currentPage > 0 { currentPage -= 1 }
                    }
                }
        )
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
        .accessibilityIdentifier("page_placeholder")
    }
}

#Preview {
    MainTabView()
        .environmentObject(AppContainer.shared)
}
