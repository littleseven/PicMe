import CoreImage
import CoreImage.CIFilterBuiltins
import CoreGraphics
import CoreText
import UIKit

/// 配方应用器（iOS lite actual，对齐 androidApp `RecipeApplier.kt` 处理顺序）。
/// 顺序：crop → adjust → filterColor → markup（beauty 本轮 no-op；styleFilter DEFER 需 Metal kernel）。
/// 任一步异常→退回上一步结果，绝不产出黑屏（editor.yaml §12 fallback）。
/// contracts.md §C。
enum RecipeApplier {
    /// 共享 CIContext（创建昂贵、缓存 shader；渲染热路径与 ViewModel 复用，#3）。
    static let context = CIContext(options: [.useSoftwareRenderer: false])

    static func apply(_ recipe: EditRecipe, to source: CIImage) -> CIImage {
        var img = source
        img = crop(img, recipe.crop)
        img = adjust(img, recipe.adjustments)
        img = filterColor(img, recipe.colorFilter, intensity: recipe.filterIntensity)
        img = markup(img, recipe.markup)
        return img
    }

    // MARK: Crop（旋转/翻转/比例居中裁剪）

    static func crop(_ image: CIImage, _ c: CropRecipe) -> CIImage {
        var img = image
        if c.flippedH || c.flippedV {
            let sx: CGFloat = c.flippedH ? -1 : 1
            let sy: CGFloat = c.flippedV ? -1 : 1
            let t = CGAffineTransform(scaleX: sx, y: sy)
            img = img.transformed(by: t)
            img = img.transformed(by: CGAffineTransform(translationX: -img.extent.minX,
                                                         y: -img.extent.minY))
        }
        if c.rotation != 0 {
            let rad = CGFloat(c.rotation) * .pi / 180
            img = img.transformed(by: CGAffineTransform(rotationAngle: rad))
            img = img.transformed(by: CGAffineTransform(translationX: -img.extent.minX,
                                                         y: -img.extent.minY))
        }
        if let ratio = c.aspectRatio.ratio, ratio > 0 {
            img = aspectCrop(img, target: CGFloat(ratio))
        }
        return img
    }

    private static func aspectCrop(_ image: CIImage, target: CGFloat) -> CIImage {
        let e = image.extent
        guard e.width > 0, e.height > 0 else { return image }
        let cur = e.width / e.height
        let rect: CGRect
        if cur > target {
            let newW = e.height * target
            rect = CGRect(x: e.midX - newW / 2, y: e.minY, width: newW, height: e.height)
        } else {
            let newH = e.width / target
            rect = CGRect(x: e.minX, y: e.midY - newH / 2, width: e.width, height: newH)
        }
        return image.cropped(to: rect)
    }

    // MARK: Adjust（CIFilter 链；映射为近似值，视觉对齐在验收校验）

    static func adjust(_ image: CIImage, _ a: AdjustmentRecipe) -> CIImage {
        var img = image
        // 亮度/对比/饱和：CIColorControls
        let cc = CIFilter.colorControls()
        cc.inputImage = img
        cc.brightness = a.brightness / 100           // -1..1
        cc.contrast = a.contrast / 50                 // default 50 → 1.0
        cc.saturation = a.saturation / 100            // default 100 → 1.0
        if let out = cc.outputImage { img = out }
        // 曝光：CIExposureAdjustment
        if a.exposure != 0, let exp = CIFilter(name: "CIExposureAdjustment") {
            exp.setValue(img, forKey: kCIInputImageKey)
            exp.setValue(Double(a.exposure / 50), forKey: "inputEV")
            if let out = exp.outputImage { img = out }
        }
        // 色温/色调：CITemperatureAndTint
        if a.temperature != 5000 || a.tint != 0 {
            let tt = CIFilter.temperatureAndTint()
            tt.inputImage = img
            tt.neutral = CIVector(x: 5000, y: 0)
            tt.targetNeutral = CIVector(x: CGFloat(a.temperature), y: CGFloat(a.tint))
            if let out = tt.outputImage { img = out }
        }
        return img
    }

    // MARK: FilterColor（CIColorMatrix，复用 FilterColorMatrix.colorMatrix）

    static func filterColor(_ image: CIImage, _ filter: FilterType, intensity: Float) -> CIImage {
        guard let m = filter.colorMatrix else { return image }
        // intensity 插值（1.0=全效果，0=原图）。简化：>0 即全应用（lite 版不做逐像素插值）。
        let cm = CIFilter.colorMatrix()
        cm.inputImage = image
        cm.rVector = CIVector(x: CGFloat(m.rows.0.x), y: CGFloat(m.rows.0.y),
                              z: CGFloat(m.rows.0.z), w: CGFloat(m.rows.0.w))
        cm.gVector = CIVector(x: CGFloat(m.rows.1.x), y: CGFloat(m.rows.1.y),
                              z: CGFloat(m.rows.1.z), w: CGFloat(m.rows.1.w))
        cm.bVector = CIVector(x: CGFloat(m.rows.2.x), y: CGFloat(m.rows.2.y),
                              z: CGFloat(m.rows.2.z), w: CGFloat(m.rows.2.w))
        cm.aVector = CIVector(x: CGFloat(m.rows.3.x), y: CGFloat(m.rows.3.y),
                              z: CGFloat(m.rows.3.z), w: CGFloat(m.rows.3.w))
        // offset 语义 Android 0–255 范围，CI bias 取 -1..1 → /255
        cm.biasVector = CIVector(x: CGFloat(m.offset.x / 255),
                                 y: CGFloat(m.offset.y / 255),
                                 z: CGFloat(m.offset.z / 255),
                                 w: CGFloat(m.offset.w / 255))
        return cm.outputImage ?? image
    }

    // MARK: Markup（Core Graphics 烘焙：doodle/mosaic/text，归一化坐标）

    static func markup(_ image: CIImage, _ actions: [MarkupAction]) -> CIImage {
        guard !actions.isEmpty else { return image }
        let extent = image.extent
        guard extent.width > 0, extent.height > 0,
              let cgSrc = context.createCGImage(image, from: extent) else { return image }
        let w = cgSrc.width
        let h = cgSrc.height
        guard let ctx = CGContext(data: nil, width: w, height: h,
                                  bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return image
        }
        // CGContext 原点左下；CGImage 顶部对齐：翻转绘制源图
        ctx.saveGState()
        ctx.translateBy(x: 0, y: CGFloat(h))
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(cgSrc, in: CGRect(x: 0, y: 0, width: w, height: h))
        ctx.restoreGState()

        for action in actions {
            drawAction(ctx, action, w: w, h: h)
        }
        guard let composed = ctx.makeImage() else { return image }
        return CIImage(cgImage: composed)
    }

    private static func drawAction(_ ctx: CGContext, _ action: MarkupAction, w: Int, h: Int) {
        let W = CGFloat(w), H = CGFloat(h)
        switch action {
        case .doodle(_, let points, let color, let strokeWidth):
            let path = path(from: points, w: W, h: H)
            ctx.setStrokeColor(uiColor(color))
            ctx.setLineWidth(CGFloat(strokeWidth) * W)
            ctx.setLineCap(.round)
            ctx.setLineJoin(.round)
            ctx.addPath(path)
            ctx.strokePath()
        case .mosaic(_, let points, let strokeWidth, let mode):
            // lite 版：mosaic 以半透明粗线近似（像素化/模糊需采样，本轮降级可见即可）
            let path = path(from: points, w: W, h: H)
            ctx.setStrokeColor(mode == .blur
                               ? UIColor.black.withAlphaComponent(0.35).cgColor
                               : UIColor.systemGray.withAlphaComponent(0.55).cgColor)
            ctx.setLineWidth(CGFloat(strokeWidth) * W)
            ctx.setLineCap(.round)
            ctx.setLineJoin(.round)
            ctx.addPath(path)
            ctx.strokePath()
        case .text(_, let text, let position, let color, let size):
            let fontSize = CGFloat(size) * W
            let uiFont = UIFont.systemFont(ofSize: max(10, fontSize), weight: .semibold)
            let px = CGFloat(position.x) * W
            // 归一化 y=0 图片顶部 → CGContext 顶部 = H；翻转
            let py = (1 - CGFloat(position.y)) * H
            let attr: [NSAttributedString.Key: Any] = [
                .font: uiFont,
                .foregroundColor: uiColor(color),
            ]
            let attributed = NSAttributedString(string: text, attributes: attr)
            let line = CTLineCreateWithAttributedString(attributed as CFAttributedString)
            // 用排版度量把文字「视觉中心」对齐到 (px, py)，与 SwiftUI .position 中心一致（#4 修正：
            // textPosition 是基线，py - fontSize/2 会偏低 ~0.2·fontSize）。
            var ascent: CGFloat = 0, descent: CGFloat = 0, leading: CGFloat = 0
            CTLineGetTypographicBounds(line, &ascent, &descent, &leading)
            let baselineY = py - (ascent - descent) / 2
            ctx.saveGState()
            ctx.textPosition = CGPoint(x: px, y: baselineY)
            CTLineDraw(line, ctx)
            ctx.restoreGState()
        }
    }

    private static func path(from points: [NormPoint], w: CGFloat, h: CGFloat) -> CGPath {
        let p = CGMutablePath()
        for (i, pt) in points.enumerated() {
            let x = CGFloat(pt.x) * w
            let y = (1 - CGFloat(pt.y)) * h
            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
        }
        return p
    }

    private static func uiColor(_ argb: Int) -> CGColor {
        UIColor(argb: argb).cgColor
    }
}
