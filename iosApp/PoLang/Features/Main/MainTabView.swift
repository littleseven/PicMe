import SwiftUI
import UIKit

/// 对标 Android MainPagerHost：横滑 Pager（Camera first / Gallery second）
/// 沉浸式全屏，无 tab bar。
struct MainTabView: View {
    @State private var currentPage = 0
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        PagerContainer(currentPage: $currentPage) { pageIndex in
            switch pageIndex {
            case 0:
                AnyView(
                    CameraPreviewView()
                        .environmentObject(container)
                )
            default:
                AnyView(
                    GalleryGridView(repository: container.mediaRepository)
                        .environmentObject(container)
                )
            }
        }
        .ignoresSafeArea(.all)
        .overlay(alignment: .top) {
            DebugOverlayView()
        }
        .preferredColorScheme(.dark)
    }
}

/// 🔴 P0.1: UIPageViewController wrapper — 不像 TabView 那样限制 safe area
/// 每页用 UIHostingController 承载，ignoresSafeArea 由各页自行决定
struct PagerContainer: UIViewControllerRepresentable {
    @Binding var currentPage: Int
    let content: (Int) -> AnyView
    private let pageCount = 2

    func makeUIViewController(context: Context) -> UIPageViewController {
        let pvc = UIPageViewController(
            transitionStyle: .scroll,
            navigationOrientation: .horizontal
        )
        pvc.dataSource = context.coordinator
        pvc.delegate = context.coordinator
        pvc.view.backgroundColor = .black
        context.coordinator.contentBuilder = content
        let initial = context.coordinator.pageVC(0, content: content)
        pvc.setViewControllers([initial], direction: .forward, animated: false)
        return pvc
    }

    func updateUIViewController(_ uiViewController: UIPageViewController, context: Context) {
        // currentPage 变化时跳页
        let current = context.coordinator.currentIndex
        if current != currentPage {
            let dir: UIPageViewController.NavigationDirection = currentPage > current ? .forward : .reverse
            let vc = context.coordinator.pageVC(currentPage, content: content)
            uiViewController.setViewControllers([vc], direction: dir, animated: true)
            context.coordinator.currentIndex = currentPage
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        var currentIndex = 0
        var contentBuilder: ((Int) -> AnyView)?
        private var cache: [Int: UIViewController] = [:]

        func pageVC(_ index: Int, content: (Int) -> AnyView) -> UIViewController {
            if let cached = cache[index] { return cached }
            let view = content(index)
            let hosting = UIHostingController(rootView: view)
            hosting.view.backgroundColor = .black
            cache[index] = hosting
            return hosting
        }

        func pageViewController(_ pvc: UIPageViewController, viewControllerBefore vc: UIViewController) -> UIViewController? {
            guard let idx = cache.first(where: { $0.value === vc })?.key, idx > 0 else { return nil }
            if let cached = cache[idx - 1] { return cached }
            guard let content = contentBuilder else { return nil }
            return pageVC(idx - 1, content: content)
        }

        func pageViewController(_ pvc: UIPageViewController, viewControllerAfter vc: UIViewController) -> UIViewController? {
            guard let idx = cache.first(where: { $0.value === vc })?.key, idx < 1 else { return nil }
            if let cached = cache[idx + 1] { return cached }
            guard let content = contentBuilder else { return nil }
            return pageVC(idx + 1, content: content)
        }

        func pageViewController(_ pvc: UIPageViewController, didFinishAnimating finished: Bool,
                                previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
            if completed, let current = pvc.viewControllers?.first,
               let idx = cache.first(where: { $0.value === current })?.key {
                currentIndex = idx
            }
        }
    }
}

#Preview {
    MainTabView()
        .environmentObject(AppContainer.shared)
}
