import Foundation
import CoreVideo
import simd

/// 人脸引擎路由器：MediaPipe（默认）/ MNN 双引擎运行时切换。
///
/// 持有两个引擎实例，按 `useMnn` 路由 enqueue / latestWithinWindow。
/// 仅活跃引擎接收帧并推理；切回时另一引擎的 latest 自然过期（200ms 窗口）。
/// 默认 MediaPipe（与现有行为一致），MNN 通过相机页 toggle 开启（"双引擎可切换"）。
final class FaceEngineRouter {
    let mediapipe = FaceLandmarkService()
    let mnn = MnnFaceLandmarkService()
    var useMnn = false

    private(set) var isFrontCamera = false

    func setFrontCamera(_ front: Bool) {
        isFrontCamera = front
        mediapipe.isFrontCamera = front
        mnn.isFrontCamera = front
    }

    func enqueue(pixelBuffer: CVPixelBuffer, timestampMs: Int) {
        if useMnn {
            mnn.enqueue(pixelBuffer: pixelBuffer, timestampMs: timestampMs)
        } else {
            mediapipe.enqueue(pixelBuffer: pixelBuffer, timestampMs: timestampMs)
        }
    }

    func latestWithinWindow(currentTimestampMs: Int) -> [SIMD2<Float>]? {
        useMnn
            ? mnn.latestWithinWindow(currentTimestampMs: currentTimestampMs)
            : mediapipe.latestWithinWindow(currentTimestampMs: currentTimestampMs)
    }

    var activeLabel: String { useMnn ? "MNN" : "MediaPipe" }
}
