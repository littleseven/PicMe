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
    @Environment(\.scenePhase) private var scenePhase
    @State private var showPlaceholder: String?

    var body: some View {
        ZStack {
            // 🔴 主页面容器：TabView(.page) 原生跟手 pager（对标 Android HorizontalPager）——
            // 手指拖动 offset 实时跟随、松手物理吸附。替换原「ZStack 条件渲染 + 仅 onEnded 手势」
            // （拖动期零位移、松手才跳 → 不跟手）。全 4 页常驻组合（对标 beyondViewportPageCount=N-1）。
            TabView(selection: $currentPage) {
                CameraPreviewView(isActive: currentPage == 0)
                    .environmentObject(container)
                    .tag(0)
                GalleryGridView(repository: container.mediaRepository)
                    .environmentObject(container)
                    .tag(1)
                ChatView(onBack: { currentPage = 1 })
                    .environmentObject(container)
                    .tag(2)
                PersonView(onBack: { currentPage = 1 })
                    .environmentObject(container)
                    .tag(3)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))  // 去页码点（Android 无指示器）
            .ignoresSafeArea()  // 容器全出血；各页自理 safe area（相机页已 ignoresSafeArea）

            // 打标页 push（覆盖在 pager 之上）：TAG tab → TagScanScreen（SP-B）；其余占位 Coming Soon
            if let ph = showPlaceholder {
                if ph == "tag" {
                    TagScanScreen(onDismiss: { showPlaceholder = nil })
                        .transition(.opacity)
                        .zIndex(10)
                } else {
                    PlaceholderPage(title: String(localized: "Coming Soon"))
                        .transition(.opacity)
                }
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
        .onChange(of: scenePhase) { phase in
            // 前台优先：进后台协作暂停扫描（SP-B）；回前台不自动续，由用户在扫描页点恢复
            if phase == .background { TagScanOrchestrator.shared.pauseForBackground() }
        }
        // 悬浮 Tab：相册/人物页显示；相机页（沉浸式）与聊天页（避免遮挡输入栏，返回键可出）隐藏
        .overlay(alignment: .bottom) {
            if currentPage != 0 && currentPage != 2 {
                FloatingBottomTab(currentPage: $currentPage, onPlaceholderTap: { icon in
                    showPlaceholder = icon
                })
                .padding(.bottom, 16)
            }
        }
        // 🔴 左右滑切页改由 TabView(.page) 原生跟手处理（见上方 TabView），不再用 onEnded 手势。
        // 调试悬浮窗仅相机页（排查美颜/快门用），其余页面不干扰观感
        // 🔴 避让相机控件：顶推 116pt 越过左列按钮区（safeTop 系坐标：8 + 2×48+8 + 4 余量），
        //    右缘收 76pt 避开右列按钮（48 按钮 + 16 边距 + 余量），宽度封顶防长行伸进右列
        .overlay(alignment: .topLeading) {
            if currentPage == 0 {
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
