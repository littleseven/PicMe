import SwiftUI
import Photos
import AVFoundation

/// 快门按钮（对标 Android 相机主按钮）
///
/// [PERF] 快门 <50ms：按下即刻回弹，美颜离屏渲染 + 保存异步进行。
struct ShutterButton: View {
    let action: () -> Void

    @State private var isPressed = false

    var body: some View {
        Button(action: action) {
            Circle()
                .stroke(Color.white, lineWidth: 4)
                .frame(width: 72, height: 72)
                .overlay(
                    Circle()
                        .fill(isPressed ? Color.white.opacity(0.8) : Color.white)
                        .frame(width: 58, height: 58)
                )
                .scaleEffect(isPressed ? 0.85 : 1.0)
                .animation(.easeInOut(duration: 0.1), value: isPressed)
        }
        .accessibilityIdentifier("camera_shutter")
        .buttonStyle(.plain)
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in isPressed = true }
                .onEnded { _ in isPressed = false }
        )
    }
}

/// 拍照保存管理器（触发系统 AddOnly 授权流）
enum PhotoSaver {
    /// 保存 CGImage 到系统相册
    /// - Parameter image: 美颜离屏渲染后的 CGImage
    static func saveToLibrary(_ image: CGImage) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: UIImage(cgImage: image))
        }
    }
}

/// 拍照流程封装（capture → renderToImage → save，全异步）
@MainActor
final class CaptureFlow: ObservableObject {
    @Published var isCapturing = false
    @Published var lastError: String?

    private let photoController: PhotoCaptureController
    private let renderer: BeautyRenderer?

    init(photoController: PhotoCaptureController, renderer: BeautyRenderer?) {
        self.photoController = photoController
        self.renderer = renderer
    }

    /// 完整拍照流程：按下快门 → 捕获全分辨率 → 离屏美颜 → 保存
    func captureAndSave() {
        guard !isCapturing else { return }
        isCapturing = true
        DebugOverlayState.shared.set("camera.shutter", "capturing")

        Task {
            defer {
                Task { @MainActor in
                    self.isCapturing = false
                    DebugOverlayState.shared.set("camera.shutter", "idle")
                }
            }

            // 1. 全分辨率捕获
            guard let photo = await photoController.capture() else {
                await setError("capture failed")
                return
            }

            // 2. 转 CVPixelBuffer
            guard let pixelBuffer = PhotoCaptureController.pixelBuffer(from: photo) else {
                await setError("pixel buffer failed")
                return
            }

            // 3. 离屏美颜渲染（异步，不阻塞 UI）
            let renderedImage = await Task.detached(priority: .userInitiated) { [renderer] () -> CGImage? in
                renderer?.renderToImage(pixelBuffer: pixelBuffer)
            }.value

            guard let image = renderedImage else {
                await setError("render failed")
                return
            }

            // 4. 保存到相册
            do {
                try await PhotoSaver.saveToLibrary(image)
                DebugOverlayState.shared.set("camera.shutter", "saved")
            } catch {
                await setError("save: \(error.localizedDescription)")
            }
        }
    }

    @MainActor
    private func setError(_ msg: String) {
        lastError = msg
        DebugOverlayState.shared.set("camera.shutter", "error: \(msg)")
    }
}
