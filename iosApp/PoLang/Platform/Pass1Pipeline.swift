import UIKit
import Foundation

/// Pass 1 编排器——对标 Android TagGenerationPipeline.stage1WithEmbeddings。
///
/// 数据流：
/// ```
/// 照片 (UIImage)
///   → 640px 缩放
///   → RetinaFace 多脸检测 → [FaceROI + 5pt landmarks]
///   → 每人脸: 仿射对齐 112×112 → Glint360K embedding → 512d (L2 normalized)
///   → MobileCLIP 编码 → 512d 语义 embedding (Base64)
///   → 存储: face_embeddings + media_assets
/// ```
///
/// 模型依赖（需通过 ModelDownloadCenter 预先下载）：
/// - det_500m.mnn + 2d106det.mnn（已 bundled）
/// - glintr100.mnn（需下载，260MB）
/// - vision_model.onnx MobileCLIP（需下载，399MB）

// MARK: - Result Types

struct Pass1Result {
    let hasFace: Bool
    let faceCount: Int
    let isSelfie: Bool
    let isGroupPhoto: Bool
    let embeddings: [Data]          // 每个 512×4=2048 bytes Float32 LE
    let semanticEmbeddingBase64: String?
    let faceFocusY: Double?
    let faceRoiJson: String?

    var faceRoiResultJson: String? {
        guard faceRoiJson != nil else { return nil }
        return """
        {"hasFace":\(hasFace),"faceCount":\(faceCount),"isSelfie":\(isSelfie),"isGroupPhoto":\(isGroupPhoto)}
        """
    }
}

// MARK: - Pipeline

class Pass1Pipeline {
    static let shared = Pass1Pipeline()

    private let faceDetector = PLMnnFaceDetector()
    private let faceEmbedder = PLMnnFaceEmbedder()
    private let mobileClip = MobileClipEncoder()
    private let database = TagDatabase.shared

    /// 最大人脸检测尺寸（长边像素），对标 Android MAX_FACE_DETECT_SIZE
    private let maxDetectSize: CGFloat = 640

    private init() {}

    // MARK: - Model Loading

    /// 加载所有模型（det_500m/2d106 已 bundled，glintr100 + MobileCLIP 需下载）
    func loadModels() -> Bool {
        // 1. RetinaFace + 2D106（bundled assets）
        guard let retinaPath = resolveBundledModel(name: "det_500m", ext: "mnn"),
              let landmarkPath = resolveBundledModel(name: "2d106det", ext: "mnn") else {
            print("⚠️ Pass1: det_500m/2d106det not found in bundle")
            return false
        }
        guard faceDetector.loadRetinaModel(retinaPath, landmarkModel: landmarkPath) else {
            print("⚠️ Pass1: failed to load RetinaFace/2D106 models")
            return false
        }

        // 2. Glint360K R100（从 Documents/llm_models/ 加载）
        let modelsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("llm_models")
        let glintrPath = modelsDir.appendingPathComponent("face-embedding-glint360k-r100-mnn/glintr100.mnn").path
        guard FileManager.default.fileExists(atPath: glintrPath) else {
            print("⚠️ Pass1: glintr100.mnn not downloaded. Use ModelDownloadCenter to download 'face-embedding-glint360k-r100-mnn'")
            return false
        }
        guard faceEmbedder.loadModel(glintrPath) else {
            print("⚠️ Pass1: failed to load Glint360K model")
            return false
        }

        // 3. MobileCLIP（从 Documents/llm_models/ 加载）
        let mobileClipPath = modelsDir.appendingPathComponent("mobileclip-onnx/vision_model.onnx").path
        guard FileManager.default.fileExists(atPath: mobileClipPath) else {
            print("⚠️ Pass1: vision_model.onnx not downloaded. Use ModelDownloadCenter to download 'mobileclip-onnx'")
            return false
        }
        guard mobileClip.load(modelPath: mobileClipPath) else {
            print("⚠️ Pass1: failed to load MobileCLIP model")
            return false
        }

        print("✅ Pass1: all models loaded")
        return true
    }

    /// 检查所有模型是否就绪
    var modelsReady: Bool {
        faceDetector.ready && faceEmbedder.ready && mobileClip.isLoaded
    }

    // MARK: - Core Processing

    /// 处理单张照片（对标 stage1WithEmbeddings）
    func process(_ image: UIImage, mediaId: Int64) -> Pass1Result {
        let t0 = CFAbsoluteTimeGetCurrent()
        defer {
            let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
            print("PoLang:TagScan pass1 mediaId=\(mediaId) cost=\(ms)ms")
        }
        // 1. 缩放到 640px（保持宽高比）
        guard let scaledImage = resizeIfNeeded(image, maxLongSide: maxDetectSize) else {
            return Pass1Result(hasFace: false, faceCount: 0, isSelfie: false,
                              isGroupPhoto: false, embeddings: [],
                              semanticEmbeddingBase64: nil, faceFocusY: nil,
                              faceRoiJson: nil)
        }

        // 2. RetinaFace 多脸检测
        guard let pixelData = pixelDataFromImage(scaledImage) else {
            return Pass1Result(hasFace: false, faceCount: 0, isSelfie: false,
                              isGroupPhoto: false, embeddings: [],
                              semanticEmbeddingBase64: nil, faceFocusY: nil,
                              faceRoiJson: nil)
        }

        let width = Int(scaledImage.size.width)
        let height = Int(scaledImage.size.height)
        let bytesPerRow = width * 4

        let faces = pixelData.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) -> [PLDetectedFace] in
            guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return [] }
            return faceDetector.detectAllFaces(base, width: Int32(width), height: Int32(height), bytesPerRow: Int32(bytesPerRow))
        }

        // 3. 每人脸: 仿射对齐 → Glint360K embedding
        var embeddings: [Data] = []
        var allLandmarks5: [[Float]] = []

        for face in faces {
            // 获取 5pt landmarks（像素坐标）
            var lm5 = [Float](repeating: 0, count: 10)
            face.getLandmarks(&lm5)
            allLandmarks5.append(lm5)

            // 仿射对齐到 112×112
            guard let alignedFace = FaceAlignment.alignFace(image: scaledImage, landmarks5: lm5) else { continue }

            // Glint360K embedding
            guard let rgbData = rgbBytesFromImage(alignedFace, size: 112) else { continue }
            if let embedding = rgbData.withUnsafeBytes({ (ptr: UnsafeRawBufferPointer) -> Data? in
                guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return nil }
                guard let nsdata = faceEmbedder.extractEmbedding(base, width: 112, height: 112) else { return nil }
                return nsdata as Data
            }) {
                // 验证 embedding 非零非 NaN
                if isValidEmbedding(embedding) {
                    embeddings.append(embedding as Data)
                }
            }
        }

        // 4. MobileCLIP 语义编码
        let semanticBase64 = mobileClip.encode(scaledImage).flatMap { floats in
            floatArrayToBase64(floats)
        }

        // 5. 计算 faceFocusY（人脸垂直焦点）
        let faceFocusY = computeFaceFocusY(allLandmarks5, imageHeight: height)

        // 6. 构建 faceRoiResult JSON
        let hasFace = !faces.isEmpty
        let faceCount = faces.count
        let isSelfie = faceCount == 1
        let isGroupPhoto = faceCount >= 2

        let result = Pass1Result(
            hasFace: hasFace,
            faceCount: faceCount,
            isSelfie: isSelfie,
            isGroupPhoto: isGroupPhoto,
            embeddings: embeddings,
            semanticEmbeddingBase64: semanticBase64,
            faceFocusY: faceFocusY,
            faceRoiJson: hasFace ? "placeholder" : nil
        )

        // 7. 存储到 SQLite（embeddings → face_embeddings；扫描列 → media_assets，对齐 Android）
        database.insertEmbeddings(mediaId: mediaId, embeddings: embeddings)
        database.updateMediaAssetsScanFields(
            mediaId: mediaId,
            hasFace: hasFace,
            faceRoiResult: result.faceRoiResultJson,
            faceFocusY: faceFocusY,
            semanticEmbedding: semanticBase64,
            lastTagScanPasses: "{\"1\":\(Int64(Date().timeIntervalSince1970 * 1000))}"
        )

        return result
    }

    // MARK: - Helpers

    private func resolveBundledModel(name: String, ext: String) -> String? {
        for dir in ["Assets/Mnn", "Assets", ""] {
            if let path = Bundle.main.path(forResource: name, ofType: ext, inDirectory: dir) {
                return path
            }
        }
        return Bundle.main.path(forResource: name, ofType: ext)
    }

    private func resizeIfNeeded(_ image: UIImage, maxLongSide: CGFloat) -> UIImage? {
        let longSide = max(image.size.width, image.size.height)
        if longSide <= maxLongSide { return image }

        let scale = maxLongSide / longSide
        let newWidth = Int(image.size.width * scale)
        let newHeight = Int(image.size.height * scale)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0  // 像素精确
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: newWidth, height: newHeight), format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: newWidth, height: newHeight))
        }
    }

    private func pixelDataFromImage(_ image: UIImage) -> Data? {
        guard let cgImage = image.cgImage else { return nil }
        let width = Int(image.size.width)
        let height = Int(image.size.height)
        let bytesPerRow = width * 4
        var data = Data(count: bytesPerRow * height)
        data.withUnsafeMutableBytes { ptr in
            guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return }
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            guard let context = CGContext(data: base, width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                                          space: colorSpace,
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return data
    }

    private func rgbBytesFromImage(_ image: UIImage, size: Int) -> Data? {
        guard let cgImage = image.cgImage else { return nil }
        var rgbData = Data(count: size * size * 3)
        rgbData.withUnsafeMutableBytes { ptr in
            guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return }
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            guard let context = CGContext(data: base, width: size, height: size,
                                          bitsPerComponent: 8, bytesPerRow: size * 4,
                                          space: colorSpace,
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: size, height: size))
            // RGBA → RGB (strip alpha)
            for i in 0..<(size * size) {
                base[i * 3 + 0] = base[i * 4 + 0]  // R
                base[i * 3 + 1] = base[i * 4 + 1]  // G
                base[i * 3 + 2] = base[i * 4 + 2]  // B
            }
        }
        return rgbData
    }

    private func isValidEmbedding(_ data: Data) -> Bool {
        guard data.count == 512 * 4 else { return false }
        return data.withUnsafeBytes { (ptr: UnsafeRawBufferPointer) -> Bool in
            let floats = ptr.bindMemory(to: Float.self)
            var sum: Double = 0
            for i in 0..<512 {
                let v = floats[i]
                if v.isNaN || v.isInfinite { return false }
                sum += Double(v) * Double(v)
            }
            let norm = sum.squareRoot()
            return norm > 0.5  // 非全零
        }
    }

    private func floatArrayToBase64(_ floats: [Float]) -> String? {
        var data = Data(count: floats.count * 4)
        data.withUnsafeMutableBytes { ptr in
            floats.withUnsafeBufferPointer { src in
                ptr.copyMemory(from: UnsafeRawBufferPointer(src))
            }
        }
        return data.base64EncodedString()
    }

    /// 对标 Android computeFaceFocusY：人脸 ROI 垂直中心的并集
    private func computeFaceFocusY(_ landmarks5: [[Float]], imageHeight: Int) -> Double? {
        guard !landmarks5.isEmpty else { return nil }
        var minY = Float(imageHeight)
        var maxY: Float = 0
        for lm in landmarks5 {
            // landmarks5 = [lex,ley,rex,rey,nosex,nosey,lmx,lmy,rmx,rmy]
            // 使用眼睛和嘴巴的 Y 坐标范围
            let ys = [lm[1], lm[3], lm[5], lm[7], lm[9]]
            minY = min(minY, ys.min() ?? 0)
            maxY = max(maxY, ys.max() ?? 0)
        }
        let center = (Double(minY) + Double(maxY)) / 2.0
        return center / Double(imageHeight)
    }
}
