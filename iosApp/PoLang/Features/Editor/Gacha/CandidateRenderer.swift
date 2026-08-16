import UIKit
import CoreImage
import ImageIO

// MARK: - CandidateRenderer（抽卡候选渲染器）
//
// 移植自 androidApp `domain/agent/capability/optimize/gacha/CandidateRenderer.kt`。
// 降采样解码 + 经 `RecipeApplier` 渲染候选 preset。
//
// 候选一律在 `candidateMaxEdge` 小图上渲染与评分（速度），
// 最终应用的全分辨率渲染走编辑器现有路径，不在本类职责内。
//
// 平台差异（contracts.md C-G5 / editor.yaml §15）：
// - 解码：Android BitmapFactory inSampleSize 两段解码；iOS `CGImageSourceCreateThumbnailAtIndex`
//   （kCGImageSourceThumbnailMaxPixelSize=512）一次降采样，且 withTransform 应用 EXIF 方向
//   （Android decodeStream 不转 EXIF；原图/候选同向自洽，护栏统计不受影响）。
// - 渲染：Android RecipeApplier.applyGpuEffects（自研 GL 引擎，含 beauty 渲染）；
//   iOS RecipeApplier CIImage 管线，beauty 维度因 B1 DEFER 不渲染——人像候选视觉差异仅剩调色，
//   指纹仍含 beauty 维度。滤镜失败兜底在 RecipeApplier 内部（每步退回上一步结果，绝不黑屏）。
final class CandidateRenderer {

    static let candidateMaxEdge = 512
    static let tag = "[PoLang:OptimizeGacha]"

    /// 解码长边不超过 maxEdge 的降采样图；失败返回 nil（不抛出）。
    /// 仅支持本地 file URL（iOS 侧原图统一为 chat/相册落盘文件路径，无 Android content:// 形态）。
    func decodeDownscaled(imageFile: URL,
                          maxEdge: Int = CandidateRenderer.candidateMaxEdge) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(imageFile as CFURL, nil) else {
            NSLog("%@ decodeDownscaled: CGImageSourceCreateWithURL null: %@", CandidateRenderer.tag, imageFile.path)
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceThumbnailMaxPixelSize: maxEdge,
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else {
            NSLog("%@ decodeDownscaled: thumbnail decode null: %@", CandidateRenderer.tag, imageFile.path)
            return nil
        }
        return UIImage(cgImage: cg)
    }

    /// 提取整图像素（RGBA8 字节缓冲，护栏计算用）。
    ///
    /// 字节序：premultipliedLast = RGBA8 交错（R,G,B,A 每像素 4 字节），与 Android
    /// `Bitmap.getPixels` 的 ARGB IntArray 语义等价（Guardrails 按 RGBA 布局取通道）。
    /// 本管线候选/原图均为不透明照片（JPEG 无 alpha），预乘不影响 RGB 统计。
    func extractPixels(_ image: UIImage) -> [UInt8]? {
        guard let cg = image.cgImage else { return nil }
        let w = cg.width
        let h = cg.height
        guard w > 0, h > 0 else { return nil }
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let ok = buf.withUnsafeMutableBytes { rawBuffer -> Bool in
            guard let base = rawBuffer.baseAddress,
                  let ctx = CGContext(data: base,
                                      width: w,
                                      height: h,
                                      bitsPerComponent: 8,
                                      bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? buf : nil
    }

    /// 渲染单个候选 preset。
    ///
    /// 对齐 Android `CandidateRenderer.render`：候选缩略图一律自全新 `EditRecipe` 起
    /// （不继承用户 crop/markup——对比条只呈现 AI 调色差异）；用户配方的继承发生在
    /// `OptimizeRecipeMapper.toEditRecipe(baseRecipe:)`（AiOptimizeService 落最终配方）。
    /// 滤镜链异常由 RecipeApplier 内部兜底；此处仅捕获未预期失败返回 nil，由编排层丢弃该卡。
    func render(candidate: OptimizeCandidate, base: UIImage, sourceUri: String) -> UIImage? {
        let recipe = OptimizeRecipeMapper.toEditRecipe(
            preset: candidate.preset,
            sourceUri: sourceUri,
            baseRecipe: EditRecipe(sourceUri: sourceUri)
        )
        guard let input = CIImage(image: base) else {
            NSLog("%@ render candidate %d (%@) failed: CIImage conversion null",
                  CandidateRenderer.tag, candidate.index, candidate.direction)
            return nil
        }
        let output = RecipeApplier.apply(recipe, to: input)
        guard let cg = RecipeApplier.context.createCGImage(output, from: output.extent) else {
            NSLog("%@ render candidate %d (%@) failed: createCGImage null",
                  CandidateRenderer.tag, candidate.index, candidate.direction)
            return nil
        }
        return UIImage(cgImage: cg)
    }
}
