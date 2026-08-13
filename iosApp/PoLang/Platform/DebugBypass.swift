#if DEBUG
import SwiftUI
import UIKit

// MARK: - 设备调试旁路（仅 DEBUG）
//
// 背景：iOS 16+/26（CoreDevice 设备）上常规 CLI 调试通道全失——
//   - idevicesyslog 能连上但不再回传第三方 App 的 NSLog（syslog_relay 受限）；
//   - idevicescreenshot 报 "screenshotr service: Invalid service"（该服务被移除）；
//   - xcrun devicectl 无 screenshot / 无 log 流子命令。
// 本对象把【调试日志 + 截屏】落到 App Documents，宿主再用
//   `xcrun devicectl device copy from --device <udid> ...` 拉取读取。
//
// 拉取（宿主侧）：
//   日志：devicectl device copy from ... --source Documents/polang_debug.log --destination <本地>
//   截屏：devicectl device copy from ... --source Documents/polang_shots/<file> --destination <本地.png>
//   （--domain-type appDataContainer --domain-identifier com.mamba.picme 指向本 App 容器）

enum DebugBypass {
    private static let bundleId = "com.mamba.picme"
    private static let docsURL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]

    static var logURL: URL { docsURL.appendingPathComponent("polang_debug.log") }
    static var shotsDir: URL { docsURL.appendingPathComponent("polang_shots") }
    /// devicectl copy 用的 domain 参数（指向本 App 数据容器）。
    static var containerArgs: [String] { ["--domain-type", "appDataContainer", "--domain-identifier", bundleId] }

    // MARK: - 日志（追加，~500KB 上限截前半防膨胀）

    /// 追加一行调试日志：`[HH:mm:ss.SSS] tag: message`。
    static func log(_ tag: String, _ message: String) {
        let line = "[\(Self.timestamp())] \(tag): \(message)\n"
        let url = logURL
        do {
            if FileManager.default.fileExists(atPath: url.path) {
                if let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int,
                   size > 500_000 {
                    let existing = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
                    try String(existing.suffix(250_000)).write(to: url, atomically: true, encoding: .utf8)
                }
                if let handle = try? FileHandle(forWritingTo: url) {
                    defer { try? handle.close() }
                    try? handle.seekToEnd()
                    if let data = line.data(using: .utf8) { try? handle.write(contentsOf: data) }
                }
            } else {
                try line.write(to: url, atomically: true, encoding: .utf8)
            }
        } catch {
            // 尽力而为，调试失败绝不影响 App
        }
    }

    /// 清空日志文件（调试按钮用）。
    static func clearLog() {
        try? Data().write(to: logURL)
    }

    // MARK: - 截屏（App key window → PNG → Documents/polang_shots）

    /// 抓取当前 App key window（含输入栏等 App 内容，**不含系统键盘**——键盘是独立系统窗口）
    /// 为 PNG。返回相对 Documents 的路径（供 devicectl copy）。
    @discardableResult
    static func captureScreen(label: String = "shot") -> String? {
        guard let window = Self.keyWindow else {
            log("DebugBypass", "captureScreen: no key window")
            return nil
        }
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        guard let png = image.pngData() else {
            log("DebugBypass", "captureScreen: pngData failed")
            return nil
        }
        try? FileManager.default.createDirectory(at: shotsDir, withIntermediateDirectories: true)
        // 仅保留最近 20 张，防累积
        if let olds = try? FileManager.default.contentsOfDirectory(at: shotsDir, includingPropertiesForKeys: nil),
           olds.count > 20 {
            olds.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }).prefix(olds.count - 20).forEach { try? FileManager.default.removeItem(at: $0) }
        }
        let fname = "\(label)_\(Self.fileTimestamp()).png"
        let url = shotsDir.appendingPathComponent(fname)
        do {
            try png.write(to: url)
            let rel = "Documents/polang_shots/\(fname)"
            log("DebugBypass", "screenshot saved: \(rel)")
            return rel
        } catch {
            log("DebugBypass", "screenshot FAILED: \(error.localizedDescription)")
            return nil
        }
    }

    private static var keyWindow: UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        return windows.first { $0.isKeyWindow } ?? windows.first
    }

    private static func timestamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f.string(from: Date())
    }
    private static func fileTimestamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f.string(from: Date())
    }
}

// MARK: - DEBUG 浮动截图按钮（叠在 MainTabView，全局可用）

struct DebugCaptureButton: View {
    @State private var pulse = false
    var body: some View {
        Button {
            DebugBypass.captureScreen(label: "screen")
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            withAnimation(.easeOut(duration: 0.15)) { pulse = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                withAnimation(.easeIn(duration: 0.2)) { pulse = false }
            }
        } label: {
            Image(systemName: "camera.viewfinder")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 30, height: 30)
                .background(Color.black.opacity(pulse ? 0.7 : 0.45))
                .clipShape(Circle())
        }
        .accessibilityIdentifier("debug_capture_btn")
        .help("DEBUG: capture screen to Documents (pull via devicectl)")
    }
}
#endif
