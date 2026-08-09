import Foundation
import CoreVideo
import simd

/// 人脸引擎路由器：MediaPipe / MNN 双引擎运行时切换。
///
/// 持有两个引擎实例，按 `useMnn` 路由 enqueue / latestWithinWindow。
/// 🔴 仅活跃引擎被 `boot()`（懒加载：非活跃引擎不加载模型、不报错污染 DebugOverlay）。
/// 切回时另一引擎的 latest 自然过期（200ms 窗口）。
/// 默认 MNN（MediaPipe 的 face_landmarker.task 走模型中心下载，Phase 6 才做 → 未内置）。
final class FaceEngineRouter {
    let mediapipe = FaceLandmarkService()
    let mnn = MnnFaceLandmarkService()
    var useMnn = false

    private(set) var isFrontCamera = false

    /// 设置活跃引擎并 boot（幂等）。清掉非活跃引擎遗留的错误遥测，避免误导
    /// （如 MNN 活跃时清 MediaPipe 的 `face.error`，防用户误读为「MNN 模型未找到」）。
    /// 🔴 DebugOverlayState 是 @MainActor；FaceEngineRouter 非 actor（enqueue 走相机采集队列），
    /// 故遥测写入必须 dispatch 到主线程（与引擎内部写法一致）。
    func setUseMnn(_ on: Bool) {
        useMnn = on
        if on {
            mnn.boot()
        } else {
            mediapipe.boot()
        }
        let label = activeLabel
        DispatchQueue.main.async {
            let dbg = DebugOverlayState.shared
            if on {
                dbg.clear("face.error")
                dbg.clear("face.points")
            } else {
                dbg.clear("face.mnn.error")
                dbg.clear("face.mnn")
                dbg.clear("face.mnn.dim")
            }
            dbg.set("face.engine.active", label)
        }
    }

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
