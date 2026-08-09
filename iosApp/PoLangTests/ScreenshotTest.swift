import XCTest
@testable import PoLang

/// 截图工具：截取指定页面保存到 App Documents 目录，
/// 供 devicectl 拉取后做 Android/iOS 对比。
///
/// 用法：
/// xcodebuild test -only-testing:PoLangTests/ScreenshotTest/captureSettings
/// → 截图保存到 Documents/screenshots/settings.png
/// → xcrun devicectl device copy from --domain-type appDataContainer 拉取
final class ScreenshotTest: XCTestCase {

    private var screenshotsDir: String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("screenshots").path
    }

    override func setUp() {
        super.setUp()
        try? FileManager.default.createDirectory(
            atPath: screenshotsDir, withIntermediateDirectories: true)
    }

    /// 截取当前 App 屏幕并保存到 Documents/screenshots/<name>.png
    private func capture(_ name: String) {
        // 单元测试 host 在 App 进程内，可以用 UIKit 截图
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow }) else {
            print("⚠️ No key window found for screenshot")
            return
        }

        let renderer = UIGraphicsImageRenderer(size: window.bounds.size)
        let image = renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }

        let path = "\(screenshotsDir)/\(name).png"
        if let data = image.pngData() {
            try? data.write(to: URL(fileURLWithPath: path))
            print("📸 Screenshot saved: \(path) (\(data.count) bytes)")
        }
    }

    /// 截取相册页
    func testCaptureGallery() {
        capture("gallery")
    }

    /// 截取设置页
    func testCaptureSettings() {
        capture("settings")
    }

    /// 截取聊天页
    func testCaptureChat() {
        capture("chat")
    }

    /// 截取所有页面（需要手动导航）
    func testCaptureAll() {
        // 当前页面截图
        capture("current")

        // 列出所有已保存的截图
        let files = (try? FileManager.default.contentsOfDirectory(atPath: screenshotsDir)) ?? []
        print("📸 All screenshots: \(files)")
    }
}
