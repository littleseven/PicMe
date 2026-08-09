import SwiftUI
import Photos
import AVFoundation

/// 快门按钮（对标 Android CameraControls.kt:196-228）
/// 76pt 外径，4pt 白色边框，6pt 内边距，拍照=白填，录像=红填（Phase 6）
struct ShutterButton: View {
    let action: () -> Void

    @State private var isPressed = false
    @State private var lastClickTime: Date = .distantPast

    var body: some View {
        Button(action: tap) {
            Circle()
                .stroke(Color.white, lineWidth: 4)
                .frame(width: 62, height: 62)
                .overlay(
                    Circle()
                        .fill(isPressed ? Color.white.opacity(0.8) : Color.white)
                        .frame(width: 52, height: 52)
                )
                .scaleEffect(isPressed ? 0.9 : 1.0)
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

    private func tap() {
        // 500ms 防抖（对标 Android CameraControls.kt:210-214）
        let now = Date()
        guard now.timeIntervalSince(lastClickTime) > 0.5 else { return }
        lastClickTime = now
        action()
    }
}

/// 拍照保存管理器（触发系统 AddOnly 授权流）
enum PhotoSaver {
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

    /// 保存成功回调（主线程，供相机页刷新相册入口缩略图）
    var onSaved: (() -> Void)?

    init(photoController: PhotoCaptureController, renderer: BeautyRenderer?) {
        self.photoController = photoController
        self.renderer = renderer
    }

    func captureAndSave() {
        guard !isCapturing else { return }
        isCapturing = true
        let shutterStart = Date()
        DebugOverlayState.shared.set("camera.shutter", "capturing")
        print("[PoLang] shutter.pressed at \(shutterStart.timeIntervalSince1970)")

        Task {
            defer {
                // 只复位忙碌标志——终态(saved/error)保留上屏供自动化/肉眼验收，下次拍照由 capturing 覆盖
                Task { @MainActor in self.isCapturing = false }
            }

            guard let photo = await photoController.capture() else {
                print("[PoLang] shutter.FAIL: capture() returned nil")
                await setError("capture failed")
                return
            }
            let captureMs = Date().timeIntervalSince(shutterStart) * 1000
            print("[PoLang] shutter.captured in \(String(format: "%.1f", captureMs))ms")

            guard let pixelBuffer = PhotoCaptureController.pixelBuffer(from: photo) else {
                print("[PoLang] shutter.FAIL: pixelBuffer conversion nil")
                await setError("pixel buffer failed")
                return
            }

            let renderStart = Date()
            let renderedImage = await Task.detached(priority: .userInitiated) { [renderer] () -> CGImage? in
                renderer?.renderToImage(pixelBuffer: pixelBuffer)
            }.value
            let renderMs = Date().timeIntervalSince(renderStart) * 1000

            guard let image = renderedImage else {
                print("[PoLang] shutter.FAIL: renderToImage nil → 兜底保存原图")
                // 兜底：美颜渲染失败不丢照片——直接保存未美颜原图
                if let data = photo.fileDataRepresentation(),
                   let uiImage = UIImage(data: data), let cg = uiImage.cgImage {
                    do {
                        try await PhotoSaver.saveToLibrary(cg)
                        print("[PoLang] shutter.SAVED(raw) 原图兜底")
                        DebugOverlayState.shared.set("camera.shutter", "saved(raw)")
                        onSaved?()
                    } catch {
                        print("[PoLang] shutter.SAVE_ERROR(raw): \(error.localizedDescription)")
                        await setError("save raw: \(error.localizedDescription)")
                    }
                } else {
                    await setError("render failed")
                }
                return
            }
            print("[PoLang] shutter.rendered in \(String(format: "%.1f", renderMs))ms → CGImage \(image.width)x\(image.height)")

            // 保存前确保 AddOnly 授权（被拒直接报错上屏，不静默失败）
            var addStatus = PHPhotoLibrary.authorizationStatus(for: .addOnly)
            if addStatus == .notDetermined {
                addStatus = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            }
            guard addStatus == .authorized || addStatus == .limited else {
                print("[PoLang] shutter.SAVE_DENIED: addOnly status=\(addStatus.rawValue)")
                await setError("photos add permission denied")
                return
            }

            do {
                try await PhotoSaver.saveToLibrary(image)
                let totalMs = Date().timeIntervalSince(shutterStart) * 1000
                print("[PoLang] shutter.SAVED total=\(String(format: "%.1f", totalMs))ms (capture+\(String(format: "%.1f", captureMs))+render+\(String(format: "%.1f", renderMs)))")
                DebugOverlayState.shared.set("camera.shutter", "saved")
                onSaved?()
            } catch {
                print("[PoLang] shutter.SAVE_ERROR: \(error.localizedDescription)")
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
