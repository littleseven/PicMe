import Foundation
import UIKit
import Vision

/// 端侧 OCR 结果（gallery 大图页「提取文字」入口，对齐 Android ML Kit 中文识别）。
enum OcrResult {
    /// 识别到的多行文字（按置信度从高到低，换行连接）。
    case success(text: String)
    /// 识别完成但未找到文字。
    case noText
    /// 推理 / 解码失败。
    case failure
}

/// 端侧文字识别（Apple Vision `VNRecognizeTextRequest`，100% on-device，合规 ADR-008）。
///
/// 对齐 Android `MlKitOcrProcessor`（Google ML Kit 中文识别）：
/// - accurate 级别（`.fast` 不支持中文）；
/// - 识别语言 zh-Hans / zh-Hant / en-US / en-GB；
/// - `usesLanguageCorrection = true`。
///
/// 中文识别自 iOS 16 起可用（部署目标 iOS 16）。
enum OcrRecognizer {
    /// 输入图最大边长（超限按比例缩小），约束内存。
    private static let maxDimension: CGFloat = 2200

    /// 对一张图执行端侧 OCR。
    ///
    /// - Warning: 同步阻塞（~1s 量级），调用方**必须**在后台线程（`Task.detached`）调。
    static func recognize(_ image: UIImage) -> OcrResult {
        guard let upright = normalizedCgImage(image) else {
            NSLog("PoLang:OCR image normalization failed")
            return .failure
        }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US", "en-GB"]
        request.usesLanguageCorrection = true
        do {
            try VNImageRequestHandler(cgImage: upright, orientation: .up).perform([request])
        } catch {
            NSLog("PoLang:OCR Vision handler failed: \(error)")
            return .failure
        }
        guard let observations = request.results, !observations.isEmpty else { return .noText }
        let text = observations
            .compactMap { $0.topCandidates(1).first?.string }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        NSLog("PoLang:OCR recognized \(observations.count) lines, \(text.count) chars")
        return text.isEmpty ? .noText : .success(text: text)
    }

    /// 朝向归一化 + 最大边长约束 → orientation=.up 的 CGImage
    ///（对齐 `StaticFaceDetector.normalizedCgImage`：UIImage 朝向仅是显示元数据，
    /// 需重绘为正向像素，否则 Vision 看到侧躺文字）。
    private static func normalizedCgImage(_ image: UIImage) -> CGImage? {
        if image.imageOrientation == .up, image.scale == 1,
           max(image.size.width, image.size.height) <= maxDimension {
            return image.cgImage
        }
        let scale = min(1.0, maxDimension / max(image.size.width, image.size.height))
        let w = Int((image.size.width * scale).rounded())
        let h = Int((image.size.height * scale).rounded())
        guard w > 0, h > 0 else { return image.cgImage }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format)
        let upright = renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return upright.cgImage
    }
}
