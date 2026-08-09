import Foundation
import CoreVideo
import CoreImage
import ImageIO
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
            // 🔴 捕获 MNN 实际处理的 BGRA 帧 + 标注 106 点（-captureFrame，仅一次）：
            //   frame-bgra.png = MNN 看到的原始画面；frame-landmarks.png = 同一画面叠加 106 点+轮廓。
            //   直接像素级绘制（无 UIImage 重定向）→ 裁决：点是否落在 MNN 所见 buffer 的人脸上。
            //   落上→检测正确，bug 在 overlay/render 映射；落偏→bug 在 BGRA 朝向/检测/remap。
            // 🔴 同时 dump det_500m(Stage1) 检测框（detectAllFaces 二次推理）→ frame-boxes.png/.txt：
            //   框落真脸→Stage1 对，bug 在 Stage2 关键点逆映射；框落平区/偏移→Stage1 检测本身错误
            //   （暗光降质 / 朝向不对 / 假阳）。这是定位「检测错误 vs 关键点映射错误」的 ground truth。
            let detBoxes = self.detector.detectAllFaces(
                base.assumingMemoryBound(to: UInt8.self),
                width: Int32(w), height: Int32(h), bytesPerRow: Int32(bpr))
            Self.captureFrameOnce(yuv: pixelBuffer, bgra: bgra, pts: pts,
                                  boxes: detBoxes, isFrontCamera: isFrontCamera)
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

    // MARK: - 帧捕获诊断（-captureFrame，仅一次；裁决检测/朝向 bug 的 ground truth）

    private static var frameCaptured = false

    /// 捕获 MNN 实际处理的 BGRA 帧：
    ///   1) `frame-bgra.png` — MNN 所见原始画面（det_500m+2d106det 的输入），确认朝向是否已旋正；
    ///   2) `frame-landmarks.png` — 同一画面像素级叠加 106 点 + 轮廓连线（无 UIImage 重定向歧义）。
    /// 裁决：点落人脸→检测正确，bug 在 overlay/render 映射；点偏离→bug 在 BGRA 朝向/检测/remap。
    /// 隐私：仅写到本机 Documents（设备内），不上云；用户本机查看。
    private static func captureFrameOnce(yuv: CVPixelBuffer, bgra: CVPixelBuffer,
                                         pts: [SIMD2<Float>], boxes: [PLDetectedFace],
                                         isFrontCamera: Bool) {
        guard !frameCaptured else { return }
        guard ProcessInfo.processInfo.arguments.contains("-captureFrame") else { return }
        frameCaptured = true
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }

        // 🔴 0) 原始 YUV 的 Y 平面（= Metal 渲染所采样的 luma，top-left 原始布局）→ frame-y.png。
        //   与 frame-bgra.png（CIContext 输出，MNN 所见）逐像素比对，裁决 convertToBGRA 是否
        //   做了翻转/镜像/旋转（两者朝向不同 = bug 根因）。纯 luma，无色度，隐私更轻。
        writeYPlanePng(yuv, to: dir.appendingPathComponent("frame-y.png"))

        let w = CVPixelBufferGetWidth(bgra)
        let h = CVPixelBufferGetHeight(bgra)
        let bpr = CVPixelBufferGetBytesPerRow(bgra)

        CVPixelBufferLockBaseAddress(bgra, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(bgra, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(bgra) else { return }
        let total = bpr * h
        var raw = Data(bytes: base, count: total)

        // 1) 原始 BGRA → PNG（MNN 看到的画面）
        writeBgraPng(raw, w: w, h: h, bpr: bpr, to: dir.appendingPathComponent("frame-bgra.png"))

        // 2) 像素级叠加 106 点 + 轮廓 → PNG
        raw.withUnsafeMutableBytes { rb in
            guard let p = rb.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            // 轮廓连线 0-32（红）
            if pts.count >= 33 {
                for i in 0..<32 {
                    drawLine(p, bpr: bpr, w: w, h: h,
                             from: pts[i], to: pts[i + 1], b: 0, g: 0, r: 255)
                }
            }
            // 全部 106 点（绿点）；关键解剖点（黄：0鬓角/16下巴/49鼻尖/72右眼角/75左眼角）
            for (i, pt) in pts.enumerated() {
                let isKey = (i == 0 || i == 16 || i == 49 || i == 72 || i == 75)
                let bb: UInt8 = isKey ? 0 : 0
                let gg: UInt8 = isKey ? 255 : 255
                let rr: UInt8 = isKey ? 255 : 0
                plotDot(p, bpr: bpr, w: w, h: h, pt: pt, rad: isKey ? 6 : 4, b: bb, g: gg, r: rr)
            }
        }
        writeBgraPng(raw, w: w, h: h, bpr: bpr, to: dir.appendingPathComponent("frame-landmarks.png"))

        // 3) det_500m(Stage1) 检测框叠加（青色框）→ frame-boxes.png；框坐标 + 置信度 → frame-boxes.txt
        //    与 106 点(绿)同图：框包住点云→Stage1/2 一致(检测对)；框在点云外/落平区→Stage1 错位。
        var rawBoxes = Data(bytes: base, count: total)
        rawBoxes.withUnsafeMutableBytes { rb in
            guard let p = rb.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            // 复画 106 点(淡绿小点)作参照
            for (i, pt) in pts.enumerated() {
                let isKey = (i == 0 || i == 16 || i == 49)
                plotDot(p, bpr: bpr, w: w, h: h, pt: pt, rad: isKey ? 5 : 2, b: 0, g: 120, r: 0)
            }
            // Stage1 框（青：B=255 G=255 R=0）；最大框额外加粗(红)
            for (i, face) in boxes.enumerated() {
                let isMax = (i == 0)
                drawBox(p, bpr: bpr, w: w, h: h, roi: face.roi,
                        b: 255, g: 255, r: 0, thick: isMax ? 4 : 2)
                if isMax {
                    // 最大框四角标红，便于辨识
                    drawBox(p, bpr: bpr, w: w, h: h, roi: face.roi, b: 0, g: 0, r: 255, thick: 1)
                }
            }
        }
        writeBgraPng(rawBoxes, w: w, h: h, bpr: bpr, to: dir.appendingPathComponent("frame-boxes.png"))

        // frame-boxes.txt：归一化框坐标 + 置信度（数值裁决用，无像素）
        var boxLines: [String] = ["# det_500m Stage1 boxes (normalized [0,1], pixel roi, confidence)"]
        boxLines.append("buffer=\(w)x\(h) count=\(boxes.count)")
        for (i, face) in boxes.enumerated() {
            let r = face.roi
            boxLines.append(String(format: "box%d roi=(%.0f,%.0f,%.0f,%.0f) norm=(%.3f,%.3f,%.3f,%.3f) conf=%.3f",
                                   i, r.origin.x, r.origin.y, r.size.width, r.size.height,
                                   r.origin.x / Double(w), r.origin.y / Double(h),
                                   r.size.width / Double(w), r.size.height / Double(h),
                                   Double(face.confidence)))
        }
        // 对照：106 点轮廓中心 + bbox（来自 pts）
        let cxs = pts.map { Double($0.x) }, cys = pts.map { Double($0.y) }
        boxLines.append(String(format: "# landmarks contour(0-32) center≈(%.3f,%.3f)",
                                cxs[0..<min(33, cxs.count)].reduce(0, +) / Double(min(33, cxs.count)),
                                cys[0..<min(33, cys.count)].reduce(0, +) / Double(min(33, cys.count))))
        try? boxLines.joined(separator: "\n").write(
            to: dir.appendingPathComponent("frame-boxes.txt"), atomically: true, encoding: .utf8)

        NSLog("[PoLang] face.mnn frame captured: %@ (%dx%d) front=%d pts=%d boxes=%d",
              dir.path, w, h, isFrontCamera ? 1 : 0, pts.count, boxes.count)
        DispatchQueue.main.async { DebugOverlayState.shared.set("face.mnn.capture", "saved \(w)x\(h)") }
    }

    private static func setPx(_ p: UnsafeMutablePointer<UInt8>, bpr: Int, w: Int, h: Int,
                              _ x: Int, _ y: Int, b: UInt8, g: UInt8, r: UInt8) {
        guard x >= 0, x < w, y >= 0, y < h else { return }
        let o = y * bpr + x * 4
        p[o] = b; p[o + 1] = g; p[o + 2] = r; p[o + 3] = 255
    }

    private static func plotDot(_ p: UnsafeMutablePointer<UInt8>, bpr: Int, w: Int, h: Int,
                                pt: SIMD2<Float>, rad: Int, b: UInt8, g: UInt8, r: UInt8) {
        let cx = Int(pt.x * Float(w))
        let cy = Int(pt.y * Float(h))
        for dy in -rad...rad {
            for dx in -rad...rad {
                if dx * dx + dy * dy <= rad * rad {
                    setPx(p, bpr: bpr, w: w, h: h, cx + dx, cy + dy, b: b, g: g, r: r)
                }
            }
        }
    }

    private static func drawLine(_ p: UnsafeMutablePointer<UInt8>, bpr: Int, w: Int, h: Int,
                                 from a: SIMD2<Float>, to end: SIMD2<Float>,
                                 b: UInt8, g: UInt8, r: UInt8) {
        let dx = Int((end.x - a.x) * Float(w))
        let dy = Int((end.y - a.y) * Float(h))
        let steps = max(abs(dx), abs(dy)) + 1
        for s in 0...steps {
            let t = Float(s) / Float(steps)
            setPx(p, bpr: bpr, w: w, h: h,
                  Int((a.x + (end.x - a.x) * t) * Float(w)),
                  Int((a.y + (end.y - a.y) * t) * Float(h)),
                  b: b, g: g, r: r)
        }
    }

    /// 矩形框描边（thickness 像素厚；setPx 自带越界保护）。
    private static func drawBox(_ p: UnsafeMutablePointer<UInt8>, bpr: Int, w: Int, h: Int,
                                roi: CGRect, b: UInt8, g: UInt8, r: UInt8, thick: Int) {
        let x0 = Int(roi.origin.x), y0 = Int(roi.origin.y)
        let x1 = Int(roi.origin.x + roi.size.width)
        let y1 = Int(roi.origin.y + roi.size.height)
        let tk = max(1, thick)
        if x0 <= x1 {
            for t in 0..<tk {
                let yt = y0 + t, yb = y1 - t
                for x in x0...x1 {
                    setPx(p, bpr: bpr, w: w, h: h, x, yt, b: b, g: g, r: r)
                    setPx(p, bpr: bpr, w: w, h: h, x, yb, b: b, g: g, r: r)
                }
            }
        }
        if y0 <= y1 {
            for t in 0..<tk {
                let xl = x0 + t, xr = x1 - t
                for y in y0...y1 {
                    setPx(p, bpr: bpr, w: w, h: h, xl, y, b: b, g: g, r: r)
                    setPx(p, bpr: bpr, w: w, h: h, xr, y, b: b, g: g, r: r)
                }
            }
        }
    }

    /// YUV bi-planar 的 Y 平面（luma，top-left）→ 灰度 PNG。
    /// Y 平面 = Metal 渲染管线所采样的原始 luma（未经 CIContext），故其朝向即「渲染所见的真值」。
    /// 与 frame-bgra.png（CIContext 输出）比对即可裁决 convertToBGRA 是否翻转/镜像/旋转。
    private static func writeYPlanePng(_ yuv: CVPixelBuffer, to url: URL) {
        guard CVPixelBufferGetPlaneCount(yuv) >= 1 else { return }
        let w = CVPixelBufferGetWidthOfPlane(yuv, 0)
        let h = CVPixelBufferGetHeightOfPlane(yuv, 0)
        let bpr = CVPixelBufferGetBytesPerRowOfPlane(yuv, 0)
        CVPixelBufferLockBaseAddress(yuv, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(yuv, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(yuv, 0) else { return }
        let data = Data(bytes: base, count: bpr * h)
        let cs = CGColorSpace(name: CGColorSpace.genericGrayGamma2_2)!
        guard let provider = CGDataProvider(data: data as CFData) else { return }
        guard let cg = CGImage(
            width: w, height: h,
            bitsPerComponent: 8, bitsPerPixel: 8,
            bytesPerRow: bpr, space: cs,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        ) else { return }
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(dest, cg, nil)
        CGImageDestinationFinalize(dest)
    }

    /// BGRA 字节 → PNG（行 0 在顶，与 buffer 内存布局一致；无 UIImage 重定向）。
    /// bitmapInfo = premultipliedFirst | byteOrder32Little 对应 BGRA 内存序。
    private static func writeBgraPng(_ data: Data, w: Int, h: Int, bpr: Int, to url: URL) {
        let cs = CGColorSpaceCreateDeviceRGB()
        guard let provider = CGDataProvider(data: data as CFData) else { return }
        guard let cg = CGImage(
            width: w, height: h,
            bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: bpr, space: cs,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        ) else { return }
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(dest, cg, nil)
        CGImageDestinationFinalize(dest)
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
