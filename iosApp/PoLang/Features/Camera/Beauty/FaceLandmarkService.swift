import Foundation
import AVFoundation

#if canImport(MediaPipeTasksVision)
import MediaPipeTasksVision

/// 人脸 468 点检测（video 模式）→ 106 点输出。
///
/// ⚠️ 单线程串行队列（shared iOS DispatcherProvider.modelDispatcher 不保证串行的教训）；
/// 跳帧策略：推理中丢帧不排队（alwaysDiscardsLateVideoFrames 同义）。
///
/// 输出经 MediaPipe468Adapter.map() 转为 106 点归一化坐标。
///
/// ⚠️ 当 MediaPipeTasksVision SPM 未集成时，本类整体编译排除；
/// BeautyRenderer 的 facePointsBuffer 保持 nil，warp shader 直通不形变。
final class FaceLandmarkService {
    struct Result {
        let points106: [SIMD2<Float>]
        let timestampMs: Int
    }

    private let queue = DispatchQueue(label: "polang.face.landmark")
    private var landmarker: FaceLandmarker?
    private var busy = false
    private(set) var latest: Result?

    var isFrontCamera: Bool = false

    init() {
        queue.async { [self] in
            guard let modelPath = Bundle.main.path(
                    forResource: "face_landmarker", ofType: "task",
                    inDirectory: "Assets") else {
                DebugOverlayState.shared.set("face.error", "model missing")
                return
            }
            let opts = FaceLandmarkerOptions()
            opts.baseOptions.modelAssetPath = modelPath
            opts.runningMode = .video
            opts.numFaces = 1
            landmarker = try? FaceLandmarker(options: opts)
            DebugOverlayState.shared.set("face.engine", landmarker != nil ? "ok" : "init failed")
        }
    }

    func enqueue(pixelBuffer: CVPixelBuffer, timestampMs: Int) {
        queue.async { [self] in
            guard !busy, let landmarker else { return }
            busy = true
            defer { busy = false }

            do {
                let mpImage = try MPImage(pixelBuffer: pixelBuffer)
                let result = try landmarker.detect(videoFrame: mpImage,
                                                    timestampInMilliseconds: timestampMs)
                guard let faceLandmarks = result.faceLandmarks?.first else { return }
                let landmarks468 = faceLandmarks.map { landmark in
                    MediaPipe468Adapter.Landmark(x: landmark.x, y: landmark.y)
                }
                guard let points106 = MediaPipe468Adapter.map(
                    landmarks468, isFrontCamera: isFrontCamera) else { return }
                latest = Result(points106: points106, timestampMs: timestampMs)
                DebugOverlayState.shared.set("face.points", "\(points106.count)")
            } catch {
                DebugOverlayState.shared.set("face.error", "\(error.localizedDescription.prefix(40))")
            }
        }
    }

    func latestWithinWindow(currentTimestampMs: Int) -> [SIMD2<Float>]? {
        guard let result = latest,
              abs(currentTimestampMs - result.timestampMs) < 200 else { return nil }
        return result.points106
    }
}

#endif // canImport(MediaPipeTasksVision)
