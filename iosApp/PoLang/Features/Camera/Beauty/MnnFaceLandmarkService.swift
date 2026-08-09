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
            // 🔴 一次性 dump 106 unified 点（-dumpLandmarks）→ Documents/landmarks-dump.txt。
            //   供离线数值重建（无任何人脸像素，隐私安全）：判定点云是正向椭圆(点正确)
            //   还是旋转/歪斜/镜像(坐标 bug)——裁决「瘦脸偏转」根因。
            Self.dumpLandmarksOnce(pts: pts, isFrontCamera: isFrontCamera, width: w, height: h)
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

    // MARK: - 一次性关键点 dump（-dumpLandmarks；离线数值重建用）

    private static var landmarksDumped = false

    /// 把 106 unified 点写到 Documents/landmarks-dump.txt（仅一次）。附关键解剖点速查。
    private static func dumpLandmarksOnce(pts: [SIMD2<Float>], isFrontCamera: Bool, width: Int, height: Int) {
        guard !landmarksDumped else { return }
        guard ProcessInfo.processInfo.arguments.contains("-dumpLandmarks") else { return }
        landmarksDumped = true
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let url = dir.appendingPathComponent("landmarks-dump.txt")

        func f(_ i: Int) -> String {
            guard i < pts.count else { return "(oob)" }
            let p = pts[i]
            return "(\(String(format: "%.4f", p.x)),\(String(format: "%.4f", p.y)))"
        }
        var lines: [String] = []
        lines.append("# polang landmarks dump (unified 106, normalized [0,1], Y-down, post front-mirror)")
        lines.append("buffer=\(width)x\(height) isFrontCamera=\(isFrontCamera) count=\(pts.count)")
        lines.append("# key: p0=右鬓角 p16=下巴 p44/45/46=鼻梁上/中/下 p49=鼻尖中心 p72=右眼内角 p75=左眼内角 p84=左嘴角 p90=右嘴角")
        lines.append("p0=\(f(0)) p16=\(f(16)) p44=\(f(44)) p45=\(f(45)) p46=\(f(46)) p49=\(f(49)) p72=\(f(72)) p75=\(f(75)) p84=\(f(84)) p90=\(f(90))")
        lines.append("# contour 0-32 (右鬓角0→下巴16→左鬓角32)")
        for i in 0..<min(33, pts.count) {
            lines.append("\(i) \(String(format: "%.4f", pts[i].x)) \(String(format: "%.4f", pts[i].y))")
        }
        lines.append("# rest 33-105")
        for i in 33..<pts.count {
            lines.append("\(i) \(String(format: "%.4f", pts[i].x)) \(String(format: "%.4f", pts[i].y))")
        }
        do {
            try lines.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
            NSLog("[PoLang] face.mnn landmarks dumped: %@", url.path)
        } catch {
            NSLog("[PoLang] face.mnn landmarks dump FAIL: %@", error.localizedDescription)
        }
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
