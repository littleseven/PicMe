import Foundation
import CoreVideo
import simd

/// 人脸关键点引擎抽象（MediaPipe / MNN 双引擎可切换）。
///
/// 输出契约（所有实现必须一致）：106 点归一化 [0,1]、Y-down、已含前置摄像头镜像，
/// 直接喂给 `BeautyRenderer.updateFacePoints`，与纹理同坐标空间。
///
/// 🔴 懒加载（2026-08-09）：引擎构造时不自动初始化/加载模型，仅在被 `FaceEngineRouter`
/// 选为活跃引擎时才 `boot()`。避免非活跃引擎（如 Phase 5 未内置 face_landmarker.task 的
/// MediaPipe）在 init 即报错污染 DebugOverlay（用户曾把 MediaPipe 的 `face.error: model missing`
/// 误读为「MNN 模型未找到」）。boot() 必须幂等（重复调用不重复初始化）。
protocol FaceLandmarkEngine: AnyObject {
    var isFrontCamera: Bool { get set }
    /// 是否已完成模型加载（懒加载；boot() 后置 true，含加载失败）。
    var booted: Bool { get }
    /// 加载模型并准备推理（幂等；仅活跃引擎由路由器调用）。失败时引擎自行写入自身遥测。
    func boot()
    /// 投递一帧（异步推理；推理中丢帧不排队；未 boot 则静默返回）。
    func enqueue(pixelBuffer: CVPixelBuffer, timestampMs: Int)
    /// 取 200ms 窗口内的最新结果，无则 nil。
    func latestWithinWindow(currentTimestampMs: Int) -> [SIMD2<Float>]?
}
