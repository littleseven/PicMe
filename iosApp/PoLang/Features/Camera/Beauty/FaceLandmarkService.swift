import Foundation
import AVFoundation
import CoreImage
import MediaPipeTasksVision

/// 人脸 468 点检测（video 模式）→ 106 点输出。
///
/// ⚠️ 单线程串行队列；跳帧策略：推理中丢帧不排队。
/// 输出经 MediaPipe468Adapter.map() 转为 106 点归一化坐标。
final class FaceLandmarkService: FaceLandmarkEngine {
    struct Result {
        let points106: [SIMD2<Float>]
        let timestampMs: Int
    }

    private let queue = DispatchQueue(label: "polang.face.landmark")
    private var landmarker: FaceLandmarker?
    private var busy = false
    private(set) var latest: Result?

    var isFrontCamera: Bool = false

    /// 🔴 懒加载：仅在被路由器选为活跃引擎时 boot()。避免非活跃时加载 face_landmarker.task
    /// （Phase 5 未内置）报错污染 DebugOverlay。bootStarted 同步置位 → 幂等。
    private var bootStarted = false
    var booted: Bool { bootStarted }

    init() {}

    func boot() {
        guard !bootStarted else { return }
        bootStarted = true
        queue.async { [self] in
            // 模型路径：bundle resource（Task 6 收编布局）
            guard let modelPath = Bundle.main.path(
                    forResource: "face_landmarker", ofType: "task",
                    inDirectory: "Assets") else {
                // fallback：直接在 bundle root 找（Xcode folder reference 差异）
                guard let modelPath2 = Bundle.main.path(
                        forResource: "face_landmarker", ofType: "task") else {
                    DispatchQueue.main.async {
                        DebugOverlayState.shared.set("face.error", "MediaPipe: no face_landmarker.task")
                    }
                    return
                }
                do {
                    try initLandmarker(modelPath: modelPath2)
                    return
                } catch {
                    DispatchQueue.main.async {
                        DebugOverlayState.shared.set("face.error", "init: \(error.localizedDescription.prefix(40))")
                    }
                    return
                }
            }
            do {
                try initLandmarker(modelPath: modelPath)
            } catch {
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("face.error", "init: \(error.localizedDescription.prefix(40))")
                }
            }
        }
    }

    private func initLandmarker(modelPath: String) throws {
        let opts = FaceLandmarkerOptions()
        opts.baseOptions.modelAssetPath = modelPath
        opts.runningMode = .video
        opts.numFaces = 1
        landmarker = try FaceLandmarker(options: opts)
        let status = landmarker != nil ? "ok" : "nil"
        print("[PoLang] face.engine init: \(status) modelPath=\(modelPath)")
        DispatchQueue.main.async {
            DebugOverlayState.shared.set("face.engine", status)
        }
    }

    func enqueue(pixelBuffer: CVPixelBuffer, timestampMs: Int) {
        queue.async { [self] in
            guard !busy, let landmarker else { return }
            busy = true
            defer { busy = false }

            do {
                // 🔴 #9: MediaPipe MPImage 要求 BGRA 格式，相机输出是 YUV bi-planar
                // 需先转换 pixelBuffer 格式
                let bgraBuffer = Self.convertToBGRA(pixelBuffer) ?? pixelBuffer
                let mpImage = try MPImage(pixelBuffer: bgraBuffer)
                let result = try landmarker.detect(
                    videoFrame: mpImage,
                    timestampInMilliseconds: timestampMs)
                guard let faceLandmarks = result.faceLandmarks.first else { return }
                // MPPNormalizedLandmark.x/.y 是 float 属性（非方法调用）
                let landmarks468 = faceLandmarks.map { landmark in
                    MediaPipe468Adapter.Landmark(x: landmark.x, y: landmark.y)
                }
                guard let points106 = MediaPipe468Adapter.map(
                    landmarks468, isFrontCamera: isFrontCamera) else { return }
                latest = FaceLandmarkService.Result(
                    points106: points106, timestampMs: timestampMs)
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("face.points", "\(points106.count)")
                }
            } catch {
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("face.error", "\(error.localizedDescription.prefix(40))")
                }
            }
        }
    }

    func latestWithinWindow(currentTimestampMs: Int) -> [SIMD2<Float>]? {
        guard let result = latest,
              abs(currentTimestampMs - result.timestampMs) < 200 else { return nil }
        return result.points106
    }

    // MARK: - YUV → BGRA 转换（MediaPipe 要求 BGRA）

    /// 🔴 #9: MediaPipe MPImage 不支持 YUV bi-planar（kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange）
    /// 用 vImage 将 YUV 转 BGRA 后再喂给 FaceLandmarker
    private static let bgraQueue = DispatchQueue(label: "polang.face.bgra")

    private static func convertToBGRA(_ yuvBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        let srcFormat = CVPixelBufferGetPixelFormatType(yuvBuffer)
        if srcFormat == kCVPixelFormatType_32BGRA { return yuvBuffer } // 已是 BGRA

        let w = CVPixelBufferGetWidth(yuvBuffer)
        let h = CVPixelBufferGetHeight(yuvBuffer)

        var bgraBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault, w, h,
            kCVPixelFormatType_32BGRA,
            [kCVPixelBufferCGImageCompatibilityKey: true,
             kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary,
            &bgraBuffer
        )
        guard status == kCVReturnSuccess, let bgra = bgraBuffer else { return nil }

        CVPixelBufferLockBaseAddress(yuvBuffer, .readOnly)
        CVPixelBufferLockBaseAddress(bgra, [])
        defer {
            CVPixelBufferUnlockBaseAddress(yuvBuffer, .readOnly)
            CVPixelBufferUnlockBaseAddress(bgra, [])
        }

        // 用 CoreImage 做 YUV→RGB（最简洁可靠）
        let ciImage = CIImage(cvPixelBuffer: yuvBuffer)
        let ciContext = CIContext()
        ciContext.render(ciImage, to: bgra)

        return bgra
    }
}
