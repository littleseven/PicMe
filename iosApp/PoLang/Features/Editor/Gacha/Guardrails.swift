import Foundation

// MARK: - Guardrails（候选技术护栏）
//
// 移植自 androidApp `domain/agent/capability/optimize/gacha/Guardrails.kt`（纯函数直译）。
//
// NIMA 偏好高对比高饱和，护栏用于淘汰过曝/亮度异常漂移的候选（见 spec §5.1）。
// 阈值均为初始值，按离线样张验证结果调整。
//
// 像素字节序约定（与 Android 的关键差异，测试覆盖）：
// Android 传入 Bitmap.getPixels 的 IntArray（ARGB 打包 int；r = (p >> 16) & 0xFF，
// g = (p >> 8) & 0xFF，b = p & 0xFF）。iOS 统一约定传入 RGBA8 交错字节缓冲
// （每像素 4 字节 R,G,B,A，由 `CandidateRenderer.extractPixels` 产出）：
// R = base，G = base+1，B = base+2，A = base+3 不参与统计。
// `step` 语义对齐 Android：按「像素」为步长采样（默认每 4 个像素采 1 个），非字节步长。
enum Guardrails {

    /// 高光裁剪增量上限：候选裁剪率相对原图的增量超过该值则淘汰
    /// （防候选把高光推爆，不惩罚天然偏亮的照片）。
    static let highlightClipDeltaLimit: Float = 0.05

    /// 平均亮度漂移上限：候选均亮度相对原图漂移超过该比例则淘汰。
    static let luminanceDriftLimit: Float = 0.15

    /// 高光裁剪率，∈[0,1]；step 为采样步长（默认每 4 像素采 1 个）。
    static func highlightClipRatio(_ rgba: [UInt8], step: Int = 4) -> Float {
        let pixelCount = rgba.count / 4
        if pixelCount == 0 { return 0 }
        var clipped = 0
        var sampled = 0
        var i = 0
        while i < pixelCount {
            sampled += 1
            let base = i * 4
            let r = rgba[base]
            let g = rgba[base + 1]
            let b = rgba[base + 2]
            if r >= 250 && g >= 250 && b >= 250 { clipped += 1 }
            i += step
        }
        return sampled == 0 ? 0 : Float(clipped) / Float(sampled)
    }

    /// 平均亮度（Rec.601 luma 归一化到 [0,1]）。
    static func meanLuminance(_ rgba: [UInt8], step: Int = 4) -> Float {
        let pixelCount = rgba.count / 4
        if pixelCount == 0 { return 0 }
        var sum = 0.0
        var sampled = 0
        var i = 0
        while i < pixelCount {
            sampled += 1
            let base = i * 4
            let r = Double(rgba[base])
            let g = Double(rgba[base + 1])
            let b = Double(rgba[base + 2])
            sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            i += step
        }
        return sampled == 0 ? 0 : Float(sum / Double(sampled))
    }

    /// 护栏检查。
    ///
    /// - Parameters:
    ///   - candidatePx: 候选渲染结果像素（RGBA8）
    ///   - originalMeanLuminance: 原图平均亮度
    ///   - originalClipRatio: 原图高光裁剪率（增量判定基准）
    /// - Returns: nil 表示通过；否则为淘汰原因（日志与落库用，"highlight_clip:x" / "luminance_drift:x"）。
    static func check(candidatePx: [UInt8],
                      originalMeanLuminance: Float,
                      originalClipRatio: Float) -> String? {
        let clip = highlightClipRatio(candidatePx)
        if clip - originalClipRatio > highlightClipDeltaLimit {
            return "highlight_clip:\(clip)"
        }
        let lum = meanLuminance(candidatePx)
        if originalMeanLuminance > 0 &&
            abs(lum - originalMeanLuminance) / originalMeanLuminance > luminanceDriftLimit {
            return "luminance_drift:\(lum)"
        }
        return nil
    }
}
