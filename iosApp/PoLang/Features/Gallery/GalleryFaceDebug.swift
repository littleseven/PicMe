import Foundation
import UIKit
import CoreGraphics
import SwiftUI
import simd
import Photos

/// 相册大图页静态人脸检测诊断（对标相机 live LandmarkDebugOverlay，但用于静态图）。
///
/// 目的：在**静态图**上跑 MNN（det_500m + 2d106det），把 106 点画到图上，裁决检测/remap/adapter
/// 是否正确——与 live 相机 buffer 朝向解耦。
///   - 静态点正好落在脸（眉/眼/鼻/嘴/下颌）→ MNN+remap+adapter 正确 → live「位置不对」根因在
///     相机 buffer 管线（朝向/镜像/aspect/crop 映射），不在检测本身。
///   - 静态点也偏离 → 根因在共享检测/remap 路径。
///
/// 触发：启动参数 `-galleryFace`（仅自动化验收用）。仅对当前显示的大图跑一次。
/// 100% 端侧推理（隐私红线：人脸检测不上云）。

enum StaticFaceDetector {
    struct Outcome {
        let points: [SIMD2<Float>]      // 统一 106 点，归一化 [0,1]，Y-down（图像像素空间）
        let imageSize: CGSize           // 原图像素尺寸
        let stage1Dump: String          // Stage-1 box/ROI 诊断（裁决压扁根因用）
        let debugInfo: String           // 模型加载诊断（含 retina/landmark 的 NCHW/NHWC 布局）
    }

    /// 在 UIImage 上跑 MNN 两阶段检测 → 统一 106 点。复用 MnnSelfTest 的 rasterizeBGRA + 检测流程，
    /// 但参数化输入图（非固定 face_test.jpg）。
    static func detect(_ image: UIImage) -> Outcome? {
        // 🔴 归一化 UIImage 朝向 → orientation=.up 的 CGImage，再光栅化喂给 MNN。
        //   根因：image.cgImage 是「原始传感器像素」（相机照片常见为横向/旋转），
        //   UIImage.imageOrientation 只是显示元数据。若直接光栅化 cgImage，MNN 看到的是
        //   **侧躺的人脸** → Stage1 ROI 与 Stage2 关键点全部旋转错位（用户报「位置不对」），
        //   且 overlay 用原始 cgImage 尺寸映射，与 SwiftUI Image（应用了朝向）显示帧不符。
        //   对标 Android：Bitmap 加载时已应用 EXIF 朝向，bitmap.width/height 即显示尺寸；
        //   iOS UIImage 把朝向(元数据)与像素(cgImage)分离 → 必须显式归一化。
        //   自检(face_test.jpg)因朝向=.up 不受影响：归一化对 .up 原样返回 cgImage。
        guard let cg = normalizedCgImage(image) else { return nil }
        let w = cg.width
        let h = cg.height
        guard let bgra = rasterizeBGRA(cg, width: w, height: h) else { return nil }

        let det = PLMnnFaceDetector()
        guard let retinaPath = resolveModel(name: "det_500m", ext: "mnn"),
              let landmarkPath = resolveModel(name: "2d106det", ext: "mnn") else { return nil }
        guard det.loadRetinaModel(retinaPath, landmarkModel: landmarkPath) else { return nil }

        var native = [Float](repeating: 0, count: 212)
        let found = bgra.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return false }
            return det.detect(
                base,
                width: Int32(w),
                height: Int32(h),
                bytesPerRow: Int32(w * 4),
                outPoints: &native
            )
        }
        guard found, let pts = MnnLandmarkAdapter.adapt(native, isFrontCamera: false) else { return nil }
        return Outcome(points: pts, imageSize: CGSize(width: w, height: h),
                       stage1Dump: det.stage1Dump, debugInfo: det.debugInfo)
    }

    /// 把 UIImage 归一化为 orientation=.up 的 CGImage（显示方向 == 像素方向）。
    /// image.size 已含朝向（.right/.left 时宽高互换），用 UIGraphicsImageRenderer 以 scale=1
    /// 重绘即得到正向 upright 图，其 cgImage 方向恒 .up、尺寸 == image.size。
    private static func normalizedCgImage(_ image: UIImage) -> CGImage? {
        if image.imageOrientation == .up && image.scale == 1 { return image.cgImage }
        let w = Int(image.size.width.rounded())
        let h = Int(image.size.height.rounded())
        guard w > 0, h > 0 else { return image.cgImage }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format)
        let upright = renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return upright.cgImage
    }

    private static func resolveModel(name: String, ext: String) -> String? {
        for dir in ["Assets/Mnn", "Assets", ""] {
            if let p = Bundle.main.path(forResource: name, ofType: ext, inDirectory: dir.isEmpty ? nil : dir) {
                return p
            }
        }
        return Bundle.main.path(forResource: name, ofType: ext)
    }

    /// CGImage → BGRA（B,G,R,A；与 PLMnnFaceDetector.detect 期望一致，与 MnnSelfTest 同实现）。
    private static func rasterizeBGRA(_ cg: CGImage, width: Int, height: Int) -> Data? {
        let bytesPerRow = width * 4
        var data = Data(count: bytesPerRow * height)
        let ctx: CGContext? = data.withUnsafeMutableBytes { rawBuffer in
            CGContext(
                data: rawBuffer.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
            )
        }
        ctx?.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        return ctx != nil ? data : nil
    }
}

/// `-galleryFace` 启动时自动验证：拉真实相册照片跑 `StaticFaceDetector.detect`，**无需 UI 导航**，
/// 写 Documents/gallery-auto.txt，裁决 UIImage 朝向假设（PHImageManager 缩略图是否带非 .up 朝向）。
/// 仅坐标/尺寸（无人脸像素），隐私安全。
enum GalleryFaceAutoCheck {
    static func run() async {
        guard ProcessInfo.processInfo.arguments.contains("-galleryFace") else { return }
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        var lines: [String] = []
        lines.append("# polang gallery auto face check (real device photos, no UI nav)")
        lines.append("# 裁决：rawCg 与 oriented 宽高比不一致 / orient!=0 → cgImage 侧躺 → 需归一化(本次已修)")
        // 🔴 立即落盘 START 标记：证明 task 已触发且 -galleryFace 已收到。
        //   避免下方权限/加载阻塞时连空文件都没有，无法判断是否运行。
        finalize(lines + ["START"], dir)
        // 1) 相册权限：用 authorizationStatus（非阻塞、不弹窗）。
        //   requestAuthorization(for:) 在权限未决时会弹系统对话框，无人值守会永久阻塞——故改用只读状态查询。
        let status = PHPhotoLibrary.authorizationStatus()
        lines.append("photoAuth=\(status.rawValue) (2=limited,3=authorized,0=notDetermined)")
        guard status == .authorized || status == .limited else {
            lines.append("result: NO_PERMISSION (跳过——相册权限=\(status.rawValue)，需系统设置授予)")
            finalize(lines, dir)
            return
        }
        // 2) 拉最新 N 张照片（ThumbnailLoader 同相册预览路径）
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.fetchLimit = 12
        let fetch = PHAsset.fetchAssets(with: .image, options: opts)
        var tried = 0
        var firstFace: (idx: Int, orient: Int, points: [SIMD2<Float>], imgSize: CGSize, stage1: String, debug: String)? = nil
        for i in 0..<fetch.count {
            tried += 1
            let asset = fetch.object(at: i)
            guard let img = await ThumbnailLoader.shared.thumbnail(
                for: asset.localIdentifier,
                size: CGSize(width: 1600, height: 1600),
                highQuality: true) else {
                lines.append("photo[\(i)] load=FAIL")
                continue
            }
            let orient = img.imageOrientation.rawValue
            let rawW = img.cgImage?.width ?? -1
            let rawH = img.cgImage?.height ?? -1
            let ow = Int(img.size.width.rounded())
            let oh = Int(img.size.height.rounded())
            let rawAspect = Double(rawW) / max(Double(rawH), 1)
            let orientedAspect = Double(ow) / max(Double(oh), 1)
            let sideways = (rawW > rawH && oh > ow) || (rawW < rawH && ow > oh) || orient != 0
            lines.append(String(format: "photo[%d] orient=%d rawCg=%dx%d(%.2f) oriented=%dx%d(%.2f) %@",
                                i, orient, rawW, rawH, rawAspect, ow, oh, orientedAspect,
                                sideways ? "SIDEWAYS→需归一化" : "upright"))
            // run() 已在后台执行器（PoLangApp 经 Task.detached 启动）；detect 同步推理，直接调用
            if firstFace == nil, let outcome = StaticFaceDetector.detect(img) {
                firstFace = (i, orient, outcome.points, outcome.imageSize, outcome.stage1Dump, outcome.debugInfo)
            }
        }
        lines.append("tried=\(tried)")
        if let f = firstFace {
            let pts = f.points
            let iw = Int(f.imgSize.width)
            let ih = Int(f.imgSize.height)
            lines.append(String(format: "firstFace photo[%d] orient=%d imgSize=%dx%d → %d pts",
                                f.idx, f.orient, iw, ih, pts.count))
            // Stage-1 诊断：box320 aspect≈1 → 压扁在 Stage-2；aspect>1.5 → 压扁在 Stage-1（模型/输入）
            lines.append("stage1: " + f.stage1)
            lines.append("model: " + f.debug)
            // 全 106 点包围盒 = overlay/LandmarkDebugOverlay 实际画的「人脸框」。正向脸应 aspect≈0.7-1.0（高>宽）。
            let xs = pts.map { Double($0.x) }, ys = pts.map { Double($0.y) }
            let bx0 = xs.min() ?? 0, bx1 = xs.max() ?? 0, by0 = ys.min() ?? 0, by1 = ys.max() ?? 0
            lines.append(String(format: "facebox(all106) aspect(w/h)=%.2f norm=[%.3f,%.3f - %.3f,%.3f]",
                                (bx1 - bx0) / max(by1 - by0, 1e-9), bx0, by0, bx1, by1))
            appendAnatomy(&lines, pts)
            // 完整 106 点（归一化 [0,1]，Y-down）供离线几何重建
            lines.append("# unified 106 pts: idx x y (0=右鬓角 16=下巴 32=左鬓角 49=鼻尖 72/75=眼角)")
            for k in 0..<pts.count {
                lines.append(String(format: "%d %.4f %.4f", k, pts[k].x, pts[k].y))
            }
        } else {
            lines.append("result: NO_FACE in \(tried) photos（朝向信息见上，仍可裁决归一化假设）")
        }
        finalize(lines, dir)
    }

    private static func appendAnatomy(_ lines: inout [String], _ pts: [SIMD2<Float>]) {
        guard pts.count >= 106 else { return }
        let xs = pts.map { Double($0.x) }
        let ys = pts.map { Double($0.y) }
        let cw = (xs.prefix(33).max() ?? 0) - (xs.prefix(33).min() ?? 0)
        let ch = (ys.prefix(33).max() ?? 0) - (ys.prefix(33).min() ?? 0)
        lines.append(String(format: "contour aspect(w/h)=%.2f (正向脸≈0.7-1.0；>1.5=侧躺/错位)",
                            cw / max(ch, 1e-9)))
        lines.append(String(format: "anatomy: chinBelowTemples=%@ noseBetweenEyes=%@",
                            (pts[16].y > pts[0].y && pts[16].y > pts[32].y) ? "Y" : "N",
                            (pts[72].x < pts[49].x && pts[49].x < pts[75].x) ? "Y" : "N"))
    }

    private static func finalize(_ lines: [String], _ dir: URL) {
        try? lines.joined(separator: "\n").write(
            to: dir.appendingPathComponent("gallery-auto.txt"), atomically: true, encoding: .utf8)
        NSLog("[PoLang] gallery.auto dumped: %@", dir.path)
    }
}

/// 把 106 点叠加到大图上（scaledToFit 映射；与 MediaPagerView 的 Image.resizable().scaledToFit() 同几何）。
/// 可用于自动验收截图（无真人脸像素泄露风险时）或肉眼判断点是否落在脸上。
struct GalleryFaceOverlay: View {
    let points: [SIMD2<Float>]
    let imageSize: CGSize

    var body: some View {
        GeometryReader { geo in
            Canvas { ctx, size in
                guard points.count >= 106, imageSize.width > 0, imageSize.height > 0 else { return }
                // scaledToFit：等比缩小 + 居中
                let scale = min(size.width / imageSize.width, size.height / imageSize.height)
                let drawW = imageSize.width * scale
                let drawH = imageSize.height * scale
                let offX = (size.width - drawW) / 2
                let offY = (size.height - drawH) / 2

                func toScreen(_ p: SIMD2<Float>) -> CGPoint {
                    CGPoint(x: offX + CGFloat(p.x) * drawW, y: offY + CGFloat(p.y) * drawH)
                }

                // 人脸框（橙）+ 角点（黄）
                let xs = points.map { $0.x }
                let ys = points.map { $0.y }
                var minX = (xs.min() ?? 0), maxX = (xs.max() ?? 0)
                var minY = (ys.min() ?? 0), maxY = (ys.max() ?? 0)
                let padX = (maxX - minX) * 0.05, padY = (maxY - minY) * 0.05
                minX = max(0, minX - padX); maxX = min(1, maxX + padX)
                minY = max(0, minY - padY); maxY = min(1, maxY + padY)
                let tl = toScreen(SIMD2<Float>(minX, minY))
                let br = toScreen(SIMD2<Float>(maxX, maxY))
                let box = CGRect(x: tl.x, y: tl.y, width: br.x - tl.x, height: br.y - tl.y)
                ctx.stroke(Path(box), with: .color(.orange), lineWidth: 2)

                // 轮廓连线 0-32（青）
                var contour = Path()
                for i in 0..<min(33, points.count) {
                    let c = toScreen(points[i])
                    if i == 0 { contour.move(to: c) } else { contour.addLine(to: c) }
                }
                ctx.stroke(contour, with: .color(.cyan.opacity(0.8)), lineWidth: 1.5)

                // 全部点（蓝）；关键点（黄标签）
                for i in 0..<points.count {
                    let c = toScreen(points[i])
                    let r: CGFloat = 3
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                             with: .color(.blue.opacity(0.9)))
                }
                let keys: [(Int, String)] = [(16, "下巴"), (49, "鼻尖"), (0, "鬓角")]
                for (idx, label) in keys where idx < points.count {
                    let c = toScreen(points[idx])
                    ctx.draw(
                        Text("\(idx):\(label)").font(.system(size: 11, weight: .bold)).foregroundColor(.yellow),
                        at: CGPoint(x: c.x, y: c.y - 12)
                    )
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// 关键点检测的加载/无脸反馈（居中半透明胶囊），让「点击后人脸关键点」有可见响应，
/// 不再像旧版那样检测无结果时静默空白。对齐 Android FaceLandmarkFeedback。
struct GalleryFaceFeedback: View {
    enum Phase { case loading, noFace }
    let phase: Phase

    var body: some View {
        VStack(spacing: 8) {
            if phase == .loading {
                ProgressView().tint(.white)
            }
            Text(phase == .loading
                 ? String(localized: "landmark_loading")
                 : String(localized: "landmark_no_face_detected"))
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 18)
        .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
    }
}
