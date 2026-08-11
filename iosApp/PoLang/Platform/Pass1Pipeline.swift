import UIKit
import Foundation
import simd

/// 扫描诊断日志（写 Documents/scan_debug.log，可 devicectl copy from 拉取；syslog 在本机不可靠）。
func scanDebugLog(_ msg: String) {
    let line = "\(msg)\n"
    guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
    let url = docs.appendingPathComponent("scan_debug.log")
    let data = Data(line.utf8)
    if FileManager.default.fileExists(atPath: url.path) {
        if let h = try? FileHandle(forWritingTo: url) {
            _ = try? h.seekToEnd()
            _ = try? h.write(contentsOf: data)
            try? h.close()
        }
    } else {
        try? data.write(to: url)
    }
}

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
    private let faceEmbedder = ORTFaceEmbedder()
    private let mobileClip = MobileClipEncoder()
    private let database = TagDatabase.shared

    /// 最大人脸检测尺寸（长边像素），对标 Android MAX_FACE_DETECT_SIZE
    private let maxDetectSize: CGFloat = 640

    private init() {}

    // MARK: - Model Loading

    /// 加载所有模型（det_500m/2d106 已 bundled，glintr100 + MobileCLIP 需下载）
    func loadModels() -> Bool { ioQueue.sync { loadModelsImpl() } }
    private func loadModelsImpl() -> Bool {
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
        let glintrPath = modelsDir.appendingPathComponent("face-embedding-glint360k-r100-onnx/glintr100.onnx").path
        guard FileManager.default.fileExists(atPath: glintrPath) else {
            print("⚠️ Pass1: glintr100.onnx not found at \(glintrPath)")
            return false
        }
        guard faceEmbedder.load(modelPath: glintrPath) else {
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

    /// 串行化队列：MNN/PLMnn* 桥为单线程模型（非线程安全）。process/loadModels 必须串行，
    /// 否则并发推理会破坏 MNN 会话状态 → 返回数组损坏 → 下标越界崩溃。
    private let ioQueue = DispatchQueue(label: "com.mamba.picme.pass1", qos: .userInitiated)

    func process(_ image: UIImage, mediaId: Int64) -> Pass1Result {
        ioQueue.sync { processImpl(image, mediaId: mediaId) }
    }

    /// 处理单张照片（对标 stage1WithEmbeddings）—— 须在 ioQueue 上调用。
    private func processImpl(_ image: UIImage, mediaId: Int64) -> Pass1Result {
        let t0 = CFAbsoluteTimeGetCurrent()
        defer {
            let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
            scanDebugLog("P1 done mediaId=\(mediaId) cost=\(ms)ms")
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

        // 多人脸检测：扁平 float 缓冲（避开 NSArray<PLDetectedFace> 桥接——该桥接返回的
        // Swift 数组存储损坏致 faces[0] 越界崩溃）。每脸 15 float：roi(4)+conf+lm(10)。
        let maxFaces = 32
        var faceBuf = [Float](repeating: 0, count: 15 * maxFaces)
        let faceCount: Int = faceBuf.withUnsafeMutableBufferPointer { fbuf -> Int in
            guard let fbase = fbuf.baseAddress else { return 0 }
            return Int(pixelData.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
                guard let bgra = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
                return faceDetector.detectAllFacesFlat(bgra, width: Int32(width), height: Int32(height),
                                                        bytesPerRow: Int32(bytesPerRow),
                                                        outBuf: fbase, maxFaces: Int32(maxFaces))
            })
        }

        // 3. 每人脸: 仿射对齐 → Glint360K embedding
        var embeddings: [Data] = []
        var allLandmarks5: [[Float]] = []

        for fi in 0..<faceCount {
            let off = fi * 15
            let roiX = faceBuf[off + 0]
            let roiY = faceBuf[off + 1]
            let roiW = faceBuf[off + 2]
            let roiH = faceBuf[off + 3]
            let retinaLm5 = Array(faceBuf[(off + 5)..<(off + 15)])  // RetinaFace 原生 5 点(fallback)

            // 方案 B(对标 Android):ROI → 2D106 → adapt(原生→统一)→ convert106To5。
            // 失败回退 RetinaFace 原生 5 点(与 Android fallback 一致)。
            let lm5: [Float]
            var native106 = [Float](repeating: 0, count: 212)
            let ok106: Bool = native106.withUnsafeMutableBufferPointer { pbuf -> Bool in
                guard let pbase = pbuf.baseAddress else { return false }
                return pixelData.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
                    guard let bgra = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return false }
                    return faceDetector.detectLandmarks106(bgra, width: Int32(width), height: Int32(height),
                                                           bytesPerRow: Int32(bytesPerRow),
                                                           roiX: roiX, roiY: roiY, roiW: roiW, roiH: roiH,
                                                           outPoints: pbase)
                }
            }
            if ok106, let unified = MnnLandmarkAdapter.adapt(native106, isFrontCamera: false) {
                // adapt 输出 [SIMD2<Float>](统一序,归一化)→ 扁平化喂 convert106ToLandmarks5
                var flat106 = [Float](repeating: 0, count: 212)
                for i in 0..<106 {
                    flat106[i * 2] = unified[i].x
                    flat106[i * 2 + 1] = unified[i].y
                }
                lm5 = FaceAlignment.convert106ToLandmarks5(landmarks106: flat106, width: width, height: height)
                // 🔬 点序诊断:统一序 49(应=鼻尖) vs RetinaFace nose ground truth。
                // 跨多张脸 pxRel 稳定小 → 点序稳定(adapt+convert 可用);跳/大 → 点序错乱。
                let rnx = retinaLm5[4], rny = retinaLm5[5]
                let u49x = unified[49].x * Float(width), u49y = unified[49].y * Float(height)
                let d = ((u49x - rnx) * (u49x - rnx) + (u49y - rny) * (u49y - rny)).squareRoot()
                scanDebugLog("P1 diag face=\(fi) idx49=(\(u49x),\(u49y)) retinaNose=(\(rnx),\(rny)) pxRel=\(d / Float(width))")
            } else {
                scanDebugLog("P1 face=\(fi) fallback native5pt ok106=\(ok106)")
                lm5 = retinaLm5
            }
            allLandmarks5.append(lm5)

            // 仿射对齐到 112×112
            guard let alignedFace = FaceAlignment.alignFace(image: scaledImage, landmarks5: lm5) else { continue }

            // Glint360K embedding（ONNX Runtime，替代 MNN）
            guard let rgbData = rgbBytesFromImage(alignedFace, size: 112) else { continue }
            if let embedding = rgbData.withUnsafeBytes({ (ptr: UnsafeRawBufferPointer) -> Data? in
                guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return nil }
                return faceEmbedder.extractEmbedding(base, width: 112, height: 112)
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
        let hasFace = faceCount > 0
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
        let scale = min(1.0, maxLongSide / longSide) // >640 缩到 640；<=640 保持原尺寸
        let newWidth = max(1, Int((image.size.width * scale).rounded()))
        let newHeight = max(1, Int((image.size.height * scale).rounded()))
        // 恒以 UIGraphicsImageRenderer(scale=1) 重绘 → 朝向归一化(.up) + 像素尺寸==size，
        // 保证 pixelDataFromImage 的 cg 尺寸与返回 size 一致（避免旋转图维度错配）。
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: newWidth, height: newHeight), format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: newWidth, height: newHeight))
        }
    }

    /// UIImage → **BGRA**（B,G,R,A；PLMnnFaceDetector.detect/detectAllFaces 期望 BGRA，
    /// 与 StaticFaceDetector.rasterizeBGRA / MnnSelfTest 同实现）。
    /// 此前用 RGBA(noneSkipLast) → RetinaFace 通道错位 → 垃圾 ROI → 2d106 裁剪越界 → 堆损坏崩溃。
    private func pixelDataFromImage(_ image: UIImage) -> Data? {
        guard let cgImage = image.cgImage else { return nil }
        let width = cgImage.width
        let height = cgImage.height
        let bytesPerRow = width * 4
        var data = Data(count: bytesPerRow * height)
        data.withUnsafeMutableBytes { rawBuffer in
            guard let base = rawBuffer.baseAddress else { return }
            let ctx = CGContext(
                data: base, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
            )
            ctx?.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return data
    }

    private func rgbBytesFromImage(_ image: UIImage, size: Int) -> Data? {
        guard let cgImage = image.cgImage else { return nil }
        let totalPixels = size * size
        // ⚠️ 必须分配独立的 RGBA 缓冲区给 CGContext（4 字节/像素），
        //    再 strip 到 RGB 输出（3 字节/像素）。此前共用一个 3 字节缓冲区 →
        //    CGContext 写 RGBA 溢出 → 堆损坏 → embedder 输入损坏 → embedding 正交。
        var rgbaData = Data(count: totalPixels * 4)
        var rgbData = Data(count: totalPixels * 3)
        rgbaData.withUnsafeMutableBytes { rgbaPtr in
            guard let rgbaBase = rgbaPtr.bindMemory(to: UInt8.self).baseAddress else { return }
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            guard let context = CGContext(data: rgbaBase, width: size, height: size,
                                          bitsPerComponent: 8, bytesPerRow: size * 4,
                                          space: colorSpace,
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: size, height: size))
            // RGBA → RGB（从独立缓冲区 strip，无溢出）
            rgbData.withUnsafeMutableBytes { rgbPtr in
                guard let rgbBase = rgbPtr.bindMemory(to: UInt8.self).baseAddress else { return }
                for i in 0..<totalPixels {
                    rgbBase[i * 3]     = rgbaBase[i * 4]     // R
                    rgbBase[i * 3 + 1] = rgbaBase[i * 4 + 1] // G
                    rgbBase[i * 3 + 2] = rgbaBase[i * 4 + 2] // B
                }
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

    /// semanticEmbedding 编码（contracts §5.5/R6：大端 float32×512 → Base64.NO_WRAP，
    /// 与 Android 逐字节一致）。此前为原生小端内存拷贝（LE），与契约格式不符——
    /// 本改动前写入库的旧行需重跑 Pass1 覆盖（语义召回此前无消费方，无线上影响）。
    private func floatArrayToBase64(_ floats: [Float]) -> String? {
        SemanticEmbeddingCodec.encode(floats)
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

/// Pass3 诊断标记（UserDefaults，SIGKILL 也可靠持久化）。
func p3mark(_ step: String) {
    let avail = os_proc_available_memory() / 1024 / 1024
    UserDefaults.standard.set("\(step)|mem=\(avail)MB", forKey: "p3_last_step")
}
