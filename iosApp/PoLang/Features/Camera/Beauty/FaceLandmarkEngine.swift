import Foundation
import CoreVideo
import simd

/// 人脸关键点引擎抽象（MediaPipe / MNN 双引擎可切换）。
///
/// 输出契约（所有实现必须一致）：106 点归一化 [0,1]、Y-down、已含前置摄像头镜像，
/// 直接喂给 `BeautyRenderer.updateFacePoints`，与纹理同坐标空间。
protocol FaceLandmarkEngine: AnyObject {
    var isFrontCamera: Bool { get set }
    /// 投递一帧（异步推理；推理中丢帧不排队）。
    func enqueue(pixelBuffer: CVPixelBuffer, timestampMs: Int)
    /// 取 200ms 窗口内的最新结果，无则 nil。
    func latestWithinWindow(currentTimestampMs: Int) -> [SIMD2<Float>]?
}
