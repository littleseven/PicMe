import SwiftUI
import UIKit
import SharedKit

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
                ChatView(onBack: {
                    withAnimation(.easeInOut(duration: 0.25)) { currentPage = 1 }
                })
                    .environmentObject(container)
            }
            if currentPage == 3 {
                PersonView(onBack: {
                    withAnimation(.easeInOut(duration: 0.25)) { currentPage = 1 }
                })
                .environmentObject(container)
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
        // 悬浮 Tab：相册/人物页常驻；相机页（沉浸式）与聊天页（避免遮挡输入栏，返回键可出）不显示
        .overlay(alignment: .bottom) {
            if currentPage != 0 && currentPage != 2 {
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
        // 调试悬浮窗仅相机页（排查美颜/快门用），其余页面不干扰观感
        // 🔴 避让相机控件：顶推 116pt 越过左列按钮区（safeTop 系坐标：8 + 2×48+8 + 4 余量），
        //    右缘收 76pt 避开右列按钮（48 按钮 + 16 边距 + 余量），宽度封顶防长行伸进右列
        .overlay(alignment: .topLeading) {
            // SKIE spike：临时全页面显示 overlay（验证 skie.* 遥测，GO 后恢复 currentPage == 0）
            if true {
                DebugOverlayView()
                    .frame(maxWidth: UIScreen.main.bounds.width - 84, alignment: .leading)
                    .padding(.leading, 4)
                    .padding(.top, 116)
            }
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
