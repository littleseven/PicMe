import Foundation
import CoreVideo
import CoreImage
import simd

/// MNN 人脸关键点引擎（RetinaFace det_500m → 2D106），对标 Android MnnRoiDetector + MnnLandmarkDetector。
///
/// 接收与 MediaPipe 引擎**完全相同**的 CVPixelBuffer（YUV bi-planar，已 portrait 旋正），
/// 经 ObjC++ 桥接（`PLMnnFaceDetector`）完成两阶段端侧推理，输出 106 统一点，
/// 坐标空间与 `FaceLandmarkService`（MediaPipe）一致 → 可无缝切换喂给 BeautyRenderer。
///
/// 100% 端侧推理（隐私红线：人脸检测不上云）。
final class MnnFaceLandmarkService: FaceLandmarkEngine {

    struct Result {
        let points106: [SIMD2<Float>]
        let timestampMs: Int
    }

    private let queue = DispatchQueue(label: "polang.face.mnn")
    private let detector = PLMnnFaceDetector()
    private var busy = false
    private var loaded = false
    private(set) var latest: Result?
    /// 🔴 首帧记录相机 buffer 尺寸（验证 videoOrientation=.portrait 是否生效：portrait 应 720×1280）。
    private var dimLogged = false

    var isFrontCamera: Bool = false

    /// 🔴 懒加载：仅活跃时 boot()。幂等（bootStarted 同步置位）。
    private var bootStarted = false
    var booted: Bool { bootStarted }

    init() {}

    func boot() {
        guard !bootStarted else { return }
        bootStarted = true
        queue.async { [self] in
            self.initDetector()
        }
    }

    private func initDetector() {
        guard let retinaPath = Self.resolveModel(name: "det_500m", ext: "mnn"),
              let landmarkPath = Self.resolveModel(name: "2d106det", ext: "mnn") else {
            let msg = "MNN model missing (det_500m/2d106det)"
            NSLog("[PoLang] face.mnn init FAILED: %@", msg)
            DispatchQueue.main.async { DebugOverlayState.shared.set("face.mnn.error", "model missing") }
            return
        }
        let ok = detector.loadRetinaModel(retinaPath, landmarkModel: landmarkPath)
        loaded = ok
        let status = ok ? "ok" : "fail"
        NSLog("[PoLang] face.mnn init: %@ retina=%@ landmark=%@",
              status, retinaPath, landmarkPath)
        DispatchQueue.main.async {
            DebugOverlayState.shared.set("face.mnn.engine", status)
            DebugOverlayState.shared.set("face.mnn.load", self.detector.debugInfo)
        }
    }

    func enqueue(pixelBuffer: CVPixelBuffer, timestampMs: Int) {
        queue.async { [self] in
            guard !busy, loaded else { return }
            busy = true
            defer { busy = false }

            // 与 MediaPipe 一致：YUV bi-planar → BGRA（CIContext 渲染）
            guard let bgra = Self.convertToBGRA(pixelBuffer) else { return }
            let w = CVPixelBufferGetWidth(bgra)
            let h = CVPixelBufferGetHeight(bgra)
            // 🔴 首帧记录 buffer 尺寸到遥测：portrait=720×1280（videoOrientation 生效）；
            //   landscape=1280×720（未旋正 → RetinaFace 看到侧躺人脸 → 检测不到）
            if !dimLogged {
                dimLogged = true
                let dim = "\(w)x\(h)"
                DispatchQueue.main.async { DebugOverlayState.shared.set("face.mnn.dim", dim) }
            }

            CVPixelBufferLockBaseAddress(bgra, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(bgra, .readOnly) }
            guard let base = CVPixelBufferGetBaseAddress(bgra) else { return }
            let bpr = CVPixelBufferGetBytesPerRow(bgra)

            var raw = [Float](repeating: 0, count: 212)
            let found = raw.withUnsafeMutableBufferPointer { ptr -> Bool in
                guard let out = ptr.baseAddress else { return false }
                return detector.detect(base.assumingMemoryBound(to: UInt8.self),
                                       width: Int32(w), height: Int32(h),
                                       bytesPerRow: Int32(bpr), outPoints: out)
            }
            guard found, let pts = MnnLandmarkAdapter.adapt(raw, isFrontCamera: isFrontCamera) else {
                DispatchQueue.main.async { DebugOverlayState.shared.set("face.mnn", self.detector.debugInfo) }
                return
            }
            latest = Result(points106: pts, timestampMs: timestampMs)
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("face.mnn", "\(pts.count)pts")
                DebugOverlayState.shared.set("face.mnn.dbg", self.detector.debugInfo)
            }
        }
    }

    func latestWithinWindow(currentTimestampMs: Int) -> [SIMD2<Float>]? {
        guard let result = latest,
              abs(currentTimestampMs - result.timestampMs) < 200 else { return nil }
        return result.points106
    }

    // MARK: - 模型路径解析（XcodeGen 将文件铺平到 bundle root，多级 fallback）

    private static func resolveModel(name: String, ext: String) -> String? {
        for dir in ["Assets/Mnn", "Assets", ""] {
            if let p = Bundle.main.path(forResource: name, ofType: ext, inDirectory: dir.isEmpty ? nil : dir) {
                return p
            }
        }
        return Bundle.main.path(forResource: name, ofType: ext)
    }

    // MARK: - YUV → BGRA（与 FaceLandmarkService.convertToBGRA 同实现）

    private static func convertToBGRA(_ yuvBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        let srcFormat = CVPixelBufferGetPixelFormatType(yuvBuffer)
        if srcFormat == kCVPixelFormatType_32BGRA { return yuvBuffer }

        let w = CVPixelBufferGetWidth(yuvBuffer)
        let h = CVPixelBufferGetHeight(yuvBuffer)
        var bgraBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault, w, h, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferCGImageCompatibilityKey: true,
             kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary,
            &bgraBuffer)
        guard status == kCVReturnSuccess, let bgra = bgraBuffer else { return nil }
        let ciImage = CIImage(cvPixelBuffer: yuvBuffer)
        CIContext().render(ciImage, to: bgra)
        return bgra
    }
}
