import CoreGraphics
import Foundation

// MARK: - 证照屏纯逻辑域（对标 specs/screens/idphoto.yaml §6/§7/§9/§10）
// 纯函数集合：掩码后处理 / 构图数学 / 底色合成 / 描边重放 / 规格表。
// 无 UIKit/SwiftUI 依赖，可单测（PoLangTests/IdPhotoDomainTests）。

// ── 基础类型 ────────────────────────────────────────────────────────────────

/// 底部面板四个 tab（顺序契约：底色/尺寸/边缘/修补）
enum IdPhotoTab: String, CaseIterable, Identifiable {
    case bgColor
    case size
    case edge
    case repair

    var id: String { rawValue }
}

/// 边缘调整参数（契约默认值 contrast=2.5 / 0 / 0）
struct EdgeParams: Equatable {
    var contrast: Float = 2.5
    var shrinkExpandPx: Float = 0
    var featherRadiusPx: Float = 0

    static let defaultValue = EdgeParams(contrast: 2.5, shrinkExpandPx: 0, featherRadiusPx: 0)

    static let minContrast: Float = 1.0
    static let maxContrast: Float = 4.0
    static let maxShrinkExpandPx: Float = 20
    static let maxFeatherPx: Float = 20
}

/// 修补描边模式：恢复（alpha→1）/ 擦除（alpha→0）
enum StrokeMode {
    case restore
    case erase
}

/// 源图取景窗（源图像素坐标）
struct CropRect: Equatable {
    var left: Int
    var top: Int
    var width: Int
    var height: Int
}

// ── 规格表（IDPhotoSpecs，国标 @300dpi）────────────────────────────────────

/// 尺寸规格（顺序契约：1寸/2寸/小1寸/小2寸，默认 index 0）
enum IDPhotoSizeSpec: CaseIterable {
    case in1
    case in2
    case small1in
    case small2in

    var pixelW: Int {
        switch self {
        case .in1: return 295
        case .in2: return 413
        case .small1in: return 260
        case .small2in: return 354
        }
    }

    var pixelH: Int {
        switch self {
        case .in1: return 413
        case .in2: return 579
        case .small1in: return 378
        case .small2in: return 472
        }
    }

    var labelKey: String {
        switch self {
        case .in1: return "id_photo_size_1in"
        case .in2: return "id_photo_size_2in"
        case .small1in: return "id_photo_size_small_1in"
        case .small2in: return "id_photo_size_small_2in"
        }
    }
}

/// 底色规格（顺序契约：标准蓝/标准红/白，默认 index 0；argb 与 token idphoto.color* 同值）
enum IDPhotoColorSpec: CaseIterable {
    case blue
    case red
    case white

    /// 合成用 sRGB 分量（0...255）
    var rgb: (r: UInt8, g: UInt8, b: UInt8) {
        switch self {
        case .blue: return (0x43, 0x8E, 0xDB)
        case .red: return (0xD9, 0x00, 0x1B)
        case .white: return (0xFF, 0xFF, 0xFF)
        }
    }

    var labelKey: String {
        switch self {
        case .blue: return "id_photo_color_blue"
        case .red: return "id_photo_color_red"
        case .white: return "id_photo_color_white"
        }
    }
}

// MARK: - MaskPostProcessor（掩码后处理，纯函数）

enum MaskPostProcessor {

    /// 二值化（阈值 0.5）
    static func binarize(_ mask: [Float], threshold: Float = 0.5) -> [Float] {
        mask.map { $0 >= threshold ? 1.0 : 0.0 }
    }

    /// 双线性上采样（半像素中心对齐 + 边缘钳位）
    static func upsample(_ mask: [Float], srcW: Int, srcH: Int, dstW: Int, dstH: Int) -> [Float] {
        if srcW == dstW && srcH == dstH { return mask }
        var out = [Float](repeating: 0, count: dstW * dstH)
        for dy in 0..<dstH {
            // 目标像素中心 → 源坐标（半像素中心），钳到 [0, size-1]
            let srcYf = min(Float(srcH - 1), max(0, (Float(dy) + 0.5) * Float(srcH) / Float(dstH) - 0.5))
            let y0 = Int(srcYf.rounded(.down))
            let y1 = min(y0 + 1, srcH - 1)
            let fy = srcYf - Float(y0)
            for dx in 0..<dstW {
                let srcXf = min(Float(srcW - 1), max(0, (Float(dx) + 0.5) * Float(srcW) / Float(dstW) - 0.5))
                let x0 = Int(srcXf.rounded(.down))
                let x1 = min(x0 + 1, srcW - 1)
                let fx = srcXf - Float(x0)
                let v00 = mask[y0 * srcW + x0]
                let v10 = mask[y0 * srcW + x1]
                let v01 = mask[y1 * srcW + x0]
                let v11 = mask[y1 * srcW + x1]
                out[dy * dstW + dx] = (v00 * (1 - fx) + v10 * fx) * (1 - fy)
                    + (v01 * (1 - fx) + v11 * fx) * fy
            }
        }
        return out
    }

    /// 锐化：v' = clamp((v−0.5)×contrast+0.5)；contrast=1 恒等
    static func sharpenAlpha(_ mask: [Float], contrast: Float) -> [Float] {
        guard contrast != 1.0 else { return mask }
        return mask.map { v in
            let out = (v - 0.5) * contrast + 0.5
            return min(1, max(0, out))
        }
    }

    /// 可分离 box 羽化（水平+垂直，半径 r，边缘钳位）
    static func feather(_ mask: [Float], w: Int, h: Int, radius r: Int) -> [Float] {
        guard r > 0 else { return mask }
        var tmp = [Float](repeating: 0, count: w * h)
        var out = [Float](repeating: 0, count: w * h)
        let window = Float(2 * r + 1)
        // 水平 pass
        for y in 0..<h {
            let row = y * w
            for x in 0..<w {
                let lo = max(0, x - r)
                let hi = min(w - 1, x + r)
                var sum: Float = 0
                for i in lo...hi { sum += mask[row + i] }
                tmp[row + x] = sum / window
            }
        }
        // 垂直 pass
        for y in 0..<h {
            let lo = max(0, y - r)
            let hi = min(h - 1, y + r)
            for x in 0..<w {
                var sum: Float = 0
                for i in lo...hi { sum += tmp[i * w + x] }
                out[y * w + x] = sum / window
            }
        }
        return out
    }

    /// 腐蚀（窗口 min，边缘钳位）——前景收缩
    static func erode(_ mask: [Float], w: Int, h: Int, radius r: Int) -> [Float] {
        guard r > 0 else { return mask }
        var tmp = [Float](repeating: 0, count: w * h)
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            let row = y * w
            for x in 0..<w {
                let lo = max(0, x - r)
                let hi = min(w - 1, x + r)
                var m: Float = .greatestFiniteMagnitude
                for i in lo...hi { m = min(m, mask[row + i]) }
                tmp[row + x] = m
            }
        }
        for y in 0..<h {
            let lo = max(0, y - r)
            let hi = min(h - 1, y + r)
            for x in 0..<w {
                var m: Float = .greatestFiniteMagnitude
                for i in lo...hi { m = min(m, tmp[i * w + x]) }
                out[y * w + x] = m
            }
        }
        return out
    }

    /// 膨胀（窗口 max，边缘钳位）——前景扩张
    static func dilate(_ mask: [Float], w: Int, h: Int, radius r: Int) -> [Float] {
        guard r > 0 else { return mask }
        var tmp = [Float](repeating: 0, count: w * h)
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            let row = y * w
            for x in 0..<w {
                let lo = max(0, x - r)
                let hi = min(w - 1, x + r)
                var m: Float = -.greatestFiniteMagnitude
                for i in lo...hi { m = max(m, mask[row + i]) }
                tmp[row + x] = m
            }
        }
        for y in 0..<h {
            let lo = max(0, y - r)
            let hi = min(h - 1, y + r)
            for x in 0..<w {
                var m: Float = -.greatestFiniteMagnitude
                for i in lo...hi { m = max(m, tmp[i * w + x]) }
                out[y * w + x] = m
            }
        }
        return out
    }

    /// 边缘调整管线（契约顺序）：锐化 → (px>0 膨胀 / px<0 腐蚀) → 羽化
    static func adjustEdges(_ mask: [Float], w: Int, h: Int, params: EdgeParams) -> [Float] {
        var out = sharpenAlpha(mask, contrast: params.contrast)
        let px = Int(params.shrinkExpandPx.rounded())
        if px > 0 {
            out = dilate(out, w: w, h: h, radius: px)
        } else if px < 0 {
            out = erode(out, w: w, h: h, radius: -px)
        }
        let fr = Int(params.featherRadiusPx.rounded())
        if fr > 0 {
            out = feather(out, w: w, h: h, radius: fr)
        }
        return out
    }
}

// MARK: - IDPhotoComposer（构图数学，纯函数——砍头修复回归基线）

enum IDPhotoComposer {

    static let subjectAlphaThreshold: Float = 0.5
    static let headroomRatio: Float = 0.08

    /// 主体包围：头顶行（首个含 alpha≥0.5 的行）+ 前景 x 均值；无前景 → nil
    static func subjectBounds(_ alpha: [Float], w: Int, h: Int) -> (top: Int, centerX: Float)? {
        var top = -1
        var sumX: Double = 0
        var count: Double = 0
        for y in 0..<h {
            let row = y * w
            for x in 0..<w where alpha[row + x] >= subjectAlphaThreshold {
                if top < 0 { top = y }
                sumX += Double(x)
                count += 1
            }
        }
        guard top >= 0, count > 0 else { return nil }
        return (top, Float(sumX / count))
    }

    /// 居中 cover 裁切窗（无主体时的基准）：宽图裁左右/高图裁上下
    static func coverCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int) -> CropRect {
        let srcRatio = Float(srcW) / Float(srcH)
        let dstRatio = Float(dstW) / Float(dstH)
        var cropW = srcW
        var cropH = srcH
        if srcRatio > dstRatio {
            // 源更宽 → 裁左右
            cropW = max(1, Int(Float(srcH) * dstRatio))
        } else {
            // 源更高 → 裁上下
            cropH = max(1, Int(Float(srcW) / dstRatio))
        }
        let left = (srcW - cropW) / 2
        let top = (srcH - cropH) / 2
        return CropRect(left: left, top: top, width: cropW, height: cropH)
    }

    /// 归一化构图（zoom≥1；offset 钳位在可行域，防过拖死区）
    static func clampFraming(subject: (top: Int, centerX: Float)?,
                             offsetX: Float, offsetY: Float, zoom: Float,
                             srcW: Int, srcH: Int, dstW: Int, dstH: Int)
        -> (offsetX: Float, offsetY: Float, zoom: Float) {
        let z = max(1, min(4, zoom))
        let cover = coverCropRect(srcW: srcW, srcH: srcH, dstW: dstW, dstH: dstH)
        let cropW = max(1, min(Float(srcW), Float(cover.width) / z))
        let cropH = max(1, min(Float(srcH), Float(cover.height) / z))
        let autoLeft = subject.map { $0.centerX - cropW / 2 } ?? (Float(srcW) - cropW) / 2
        let autoTop = subject.map { Float($0.top) - cropH * headroomRatio } ?? (Float(srcH) - cropH) / 2
        let minX = -autoLeft / cropW
        let maxX = (Float(srcW) - cropW - autoLeft) / cropW
        let minY = -autoTop / cropH
        let maxY = (Float(srcH) - cropH - autoTop) / cropH
        let cx = min(maxX, max(minX, offsetX))
        let cy = min(maxY, max(minY, offsetY))
        return (cx, cy, z)
    }

    /// 主体感知取景窗：头顶留 8% + 主体水平居中 + 用户偏移（最终像素钳位）
    static func subjectAwareCropRect(subject: (top: Int, centerX: Float)?,
                                     offsetX: Float, offsetY: Float, zoom: Float,
                                     srcW: Int, srcH: Int, dstW: Int, dstH: Int) -> CropRect {
        let z = max(1, zoom)
        let cover = coverCropRect(srcW: srcW, srcH: srcH, dstW: dstW, dstH: dstH)
        let cropW = max(1, min(Float(srcW), Float(cover.width) / z))
        let cropH = max(1, min(Float(srcH), Float(cover.height) / z))
        let autoLeft = subject.map { $0.centerX - cropW / 2 } ?? (Float(srcW) - cropW) / 2
        let autoTop = subject.map { Float($0.top) - cropH * headroomRatio } ?? (Float(srcH) - cropH) / 2
        let leftF = min(Float(srcW) - cropW, max(0, autoLeft + offsetX * cropW))
        let topF = min(Float(srcH) - cropH, max(0, autoTop + offsetY * cropH))
        return CropRect(left: Int(leftF.rounded()), top: Int(topF.rounded()),
                        width: Int(cropW.rounded()), height: Int(cropH.rounded()))
    }

    /// 预览框像素 → 源图像素
    static func frameToSource(px: Float, py: Float, frameW: Float, frameH: Float, crop: CropRect)
        -> (x: Float, y: Float) {
        let sx = Float(crop.left) + px / frameW * Float(crop.width)
        let sy = Float(crop.top) + py / frameH * Float(crop.height)
        return (sx, sy)
    }

    /// 半径换算（仅按宽度轴——屏幕上圆形笔刷）
    static func frameRadiusToSource(radiusPx: Float, frameW: Float, crop: CropRect) -> Float {
        guard frameW > 0 else { return radiusPx }
        return radiusPx / frameW * Float(crop.width)
    }
}

// MARK: - StrokeLayer（修补描边重放，纯函数——输入不可变）

struct StrokePoint {
    var x: Float
    var y: Float
}

struct BrushStroke {
    var mode: StrokeMode
    var radiusPx: Float          // 源图像素
    var softness: Float          // 0...1（0=硬边）
    var points: [StrokePoint]
}

enum StrokeLayer {

    /// 重放描边到 alpha 掩码：沿折线按 radius/2 步进盖章，权重 (1−d)/softness 钳位（硬边=1）
    /// target：restore→1 / erase→0；仅写描边包围盒；base 不被修改（返回新数组）
    static func replay(strokes: [BrushStroke], base: [Float], w: Int, h: Int) -> [Float] {
        guard !strokes.isEmpty else { return base }
        var out = base
        for stroke in strokes {
            guard stroke.points.count > 0, stroke.radiusPx > 0 else { continue }
            let target: Float = stroke.mode == .restore ? 1.0 : 0.0
            let softness = max(0, min(1, stroke.softness))
            let r = stroke.radiusPx
            let step = max(1, r / 2)

            // 折线采样点（含端点）
            var stamps: [StrokePoint] = []
            if stroke.points.count == 1 {
                stamps.append(stroke.points[0])
            } else {
                for i in 0..<(stroke.points.count - 1) {
                    let a = stroke.points[i]
                    let b = stroke.points[i + 1]
                    let dx = b.x - a.x
                    let dy = b.y - a.y
                    let len = (dx * dx + dy * dy).squareRoot()
                    let n = max(1, Int(len / step))
                    for j in 0..<n {
                        let t = Float(j) / Float(n)
                        stamps.append(StrokePoint(x: a.x + dx * t, y: a.y + dy * t))
                    }
                }
                stamps.append(stroke.points[stroke.points.count - 1])
            }

            // 盖章（仅写包围盒内）
            for stamp in stamps {
                let x0 = max(0, Int((stamp.x - r).rounded(.down)))
                let x1 = min(w - 1, Int((stamp.x + r).rounded(.up)))
                let y0 = max(0, Int((stamp.y - r).rounded(.down)))
                let y1 = min(h - 1, Int((stamp.y + r).rounded(.up)))
                guard x0 <= x1, y0 <= y1 else { continue }
                for y in y0...y1 {
                    for x in x0...x1 {
                        let ddx = Float(x) - stamp.x
                        let ddy = Float(y) - stamp.y
                        let d = (ddx * ddx + ddy * ddy).squareRoot() / r
                        guard d < 1 else { continue }
                        let weight: Float
                        if softness > 0 {
                            weight = min(1, max(0, (1 - d) / softness))
                        } else {
                            weight = 1
                        }
                        let i = y * w + x
                        out[i] = out[i] * (1 - weight) + target * weight
                    }
                }
            }
        }
        return out
    }
}

// MARK: - BackgroundComposer（底色合成，纯核心）

enum BackgroundComposer {

    /// alpha over 纯色：out = round(bg×(1−a) + src×a)，输出不透明（a=255）
    /// pixels: RGBA 交错（每像素 4 字节）
    static func composeOnColor(pixels: [UInt8], alpha: [Float], bgColor: (r: UInt8, g: UInt8, b: UInt8))
        -> [UInt8] {
        var out = pixels
        let n = min(pixels.count / 4, alpha.count)
        for i in 0..<n {
            let a = min(1, max(0, alpha[i]))
            let j = i * 4
            out[j] = UInt8(min(255, max(0, (Float(bgColor.r) * (1 - a) + Float(pixels[j]) * a).rounded())))
            out[j + 1] = UInt8(min(255, max(0, (Float(bgColor.g) * (1 - a) + Float(pixels[j + 1]) * a).rounded())))
            out[j + 2] = UInt8(min(255, max(0, (Float(bgColor.b) * (1 - a) + Float(pixels[j + 2]) * a).rounded())))
            out[j + 3] = 255
        }
        return out
    }
}

// MARK: - 位图工具（CGImage ↔ 缓冲区）

enum IdPhotoBitmap {

    /// CGImage → RGBA8 交错缓冲（不透明化：丢弃原 alpha）
    static func rgbaBuffer(from image: CGImage) -> (pixels: [UInt8], width: Int, height: Int)? {
        let w = image.width
        let h = image.height
        guard w > 0, h > 0 else { return nil }
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let ok = pixels.withUnsafeMutableBytes { ptr -> Bool in
            guard let ctx = CGContext(
                data: ptr.baseAddress,
                width: w, height: h,
                bitsPerComponent: 8, bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            ctx.setAlpha(1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }
        // 强制不透明（源 JPEG 本就不透明；防 PNG 透明引入 RGB=0）
        for i in 0..<(w * h) {
            pixels[i * 4 + 3] = 255
        }
        return (pixels, w, h)
    }

    /// RGBA8 缓冲 → CGImage
    static func cgImage(from pixels: [UInt8], width: Int, height: Int) -> CGImage? {
        var buf = pixels
        return buf.withUnsafeMutableBytes { ptr -> CGImage? in
            guard let ctx = CGContext(
                data: ptr.baseAddress,
                width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return nil }
            return ctx.makeImage()
        }
    }

    /// 长边降采样到 maxDim 内（保持纵横比；小于上限则原样返回）
    static func downscale(_ image: CGImage, maxDim: Int) -> CGImage {
        let w = image.width
        let h = image.height
        let longEdge = max(w, h)
        guard longEdge > maxDim else { return image }
        let scale = Float(maxDim) / Float(longEdge)
        let nw = max(1, Int(Float(w) * scale))
        let nh = max(1, Int(Float(h) * scale))
        var pixels = [UInt8](repeating: 0, count: nw * nh * 4)
        let made = pixels.withUnsafeMutableBytes { ptr -> CGImage? in
            guard let ctx = CGContext(
                data: ptr.baseAddress,
                width: nw, height: nh,
                bitsPerComponent: 8, bytesPerRow: nw * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return nil }
            ctx.interpolationQuality = .high
            ctx.setAlpha(1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: nw, height: nh))
            return ctx.makeImage()
        }
        return made ?? image
    }
}
