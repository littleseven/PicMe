import CoreGraphics
import Foundation
import MediaPipeTasksVision
import onnxruntime_objc
import UIKit

// MARK: - 证照抠图引擎（对标 specs/screens/idphoto.yaml §7.2）
// FUSION 固定路由：MediaPipe selfie 分割（256²，mask index 0=person）
//                  + ORT ModNet（1024²，(x/255−0.5)/0.5 NCHW，幂等 sigmoid）
//                  → 各自双线性上采样到源图尺寸 → 逐像素 max 融合。
// 100% 端侧（[PRIVACY]）；modnet.onnx 路径由调用方在 Main 侧从模型下载中心预解析。

struct MattingResult {
    var alpha: [Float]
    var width: Int
    var height: Int
}

enum MattingError: LocalizedError {
    /// 模型未下载（下载 enqueue 由调用方处理，本次失败；下载完成后重进即成功）
    case modelMissing(String)
    case inferenceFailed(String)

    var errorDescription: String? {
        switch self {
        case .modelMissing(let id): return "model not downloaded: \(id)"
        case .inferenceFailed(let reason): return "matting inference failed: \(reason)"
        }
    }
}

final class IDPhotoMattingEngine {

    static let modnetModelId = "modnet-onnx"
    static let modnetInputSize = 1024
    static let selfieInputSize = 256

    private var modnetSession: ORTSession?
    private var modnetInputName: String?
    private var modnetOutputName: String?
    private var selfieSegmenter: ImageSegmenter?

    var isReady: Bool { modnetSession != nil && selfieSegmenter != nil }

    // MARK: 生命周期

    func release() {
        modnetSession = nil
        modnetInputName = nil
        modnetOutputName = nil
        selfieSegmenter = nil
    }

    deinit {
        release()
    }

    // MARK: 主入口

    /// FUSION 抠图：返回与源图同尺寸的 alpha 掩码。
    /// modnetModelPath 由调用方在 Main 侧预解析（下载中心交互属 MainActor 职责，见 ViewModel）。
    func removeBackground(_ image: CGImage, modnetModelPath: String?) throws -> MattingResult {
        let w = image.width
        let h = image.height
        guard w > 0, h > 0 else {
            throw MattingError.inferenceFailed("empty image")
        }

        // ── 1. MediaPipe selfie 分割（256² confidence mask index 0 = person）──
        let selfieSmall = try selfieMask(image)
        // ── 2. MODNet（1024² alpha）──
        let modnetSmall = try modnetAlpha(image, modelPath: modnetModelPath)
        // ── 3. 双端上采样 + 逐像素 max 融合 ──
        let selfieFull = MaskPostProcessor.upsample(selfieSmall, srcW: Self.selfieInputSize,
                                                     srcH: Self.selfieInputSize, dstW: w, dstH: h)
        let modnetFull = MaskPostProcessor.upsample(modnetSmall, srcW: Self.modnetInputSize,
                                                     srcH: Self.modnetInputSize, dstW: w, dstH: h)
        var fused = [Float](repeating: 0, count: w * h)
        for i in 0..<fused.count {
            fused[i] = max(selfieFull[i], modnetFull[i])
        }
        return MattingResult(alpha: fused, width: w, height: h)
    }

    // MARK: MediaPipe selfie segmenter

    private func selfieMask(_ image: CGImage) throws -> [Float] {
        let segmenter = try ensureSelfieSegmenter()
        // 预缩放到 256²（与 Android 输出对齐；分割器内部也做缩放，这里显式固定输入尺寸）
        guard let scaled = scaleTo(image, size: Self.selfieInputSize) else {
            throw MattingError.inferenceFailed("selfie preprocess failed")
        }
        let mpImage = try MPImage(uiImage: UIImage(cgImage: scaled))
        guard let result = try? segmenter.segment(image: mpImage),
              let masks = result.confidenceMasks,
              let person = masks.first else {
            throw MattingError.inferenceFailed("selfie segment no mask")
        }
        return readMask(person)
    }

    private func readMask(_ mask: Mask) -> [Float] {
        let n = mask.width * mask.height
        var out = [Float](repeating: 0, count: n)
        out.withUnsafeMutableBufferPointer { dst in
            let src = mask.float32Data   // 非 Optional（const float* 属性）
            for i in 0..<n { dst[i] = src[i] }
        }
        return out
    }

    private func ensureSelfieSegmenter() throws -> ImageSegmenter {
        if let segmenter = selfieSegmenter { return segmenter }
        // bundle 资产（与 Android assets/matting/selfie_segmenter.tflite 同文件，SHA 一致）
        guard let modelPath = Bundle.main.path(forResource: "selfie_segmenter", ofType: "tflite",
                                               inDirectory: "Assets")
            ?? Bundle.main.path(forResource: "selfie_segmenter", ofType: "tflite") else {
            throw MattingError.inferenceFailed("selfie_segmenter.tflite missing in bundle")
        }
        let opts = ImageSegmenterOptions()
        opts.baseOptions.modelAssetPath = modelPath
        opts.runningMode = .image
        opts.shouldOutputConfidenceMasks = true
        opts.shouldOutputCategoryMask = false
        let segmenter = try ImageSegmenter(options: opts)
        selfieSegmenter = segmenter
        return segmenter
    }

    // MARK: ORT ModNet

    private func modnetAlpha(_ image: CGImage, modelPath: String?) throws -> [Float] {
        let (session, inName, outName) = try ensureModnet(modelPath: modelPath)
        let n = Self.modnetInputSize

        guard let scaled = scaleTo(image, size: n),
              let buffer = IdPhotoBitmap.rgbaBuffer(from: scaled) else {
            throw MattingError.inferenceFailed("modnet preprocess failed")
        }
        // 归一化 (x/255 − 0.5)/0.5 → NCHW
        let px = buffer.pixels
        var nchw = [Float](repeating: 0, count: 3 * n * n)
        let plane = n * n
        for i in 0..<plane {
            nchw[0 * plane + i] = (Float(px[i * 4]) / 255.0 - 0.5) / 0.5
            nchw[1 * plane + i] = (Float(px[i * 4 + 1]) / 255.0 - 0.5) / 0.5
            nchw[2 * plane + i] = (Float(px[i * 4 + 2]) / 255.0 - 0.5) / 0.5
        }

        let tensorData = NSMutableData(bytes: nchw, length: nchw.count * MemoryLayout<Float>.size)
        let shape: [NSNumber] = [1, 3, NSNumber(value: n), NSNumber(value: n)]
        guard let inputValue = try? ORTValue(tensorData: tensorData, elementType: .float, shape: shape) else {
            throw MattingError.inferenceFailed("modnet tensor create failed")
        }
        let inputs = [inName: inputValue]
        let outputs: [String: ORTValue]
        do {
            outputs = try session.run(withInputs: inputs, outputNames: [outName], runOptions: nil)
        } catch {
            throw MattingError.inferenceFailed("modnet run: \(error)")
        }
        guard let outputValue = outputs[outName],
              let outputData = try? outputValue.tensorData() else {
            throw MattingError.inferenceFailed("modnet output read failed")
        }
        let count = plane
        guard outputData.length >= count * MemoryLayout<Float>.size else {
            throw MattingError.inferenceFailed("modnet output size \(outputData.length)")
        }
        var raw = [Float](repeating: 0, count: count)
        outputData.getBytes(&raw, length: count * MemoryLayout<Float>.size)

        // 幂等 sigmoid：值域已在 [0,1] 则透传，否则套 sigmoid（对齐 Android 行为）
        var needsSigmoid = false
        for v in raw where v < 0 || v > 1 {
            needsSigmoid = true
            break
        }
        if needsSigmoid {
            for i in 0..<raw.count {
                raw[i] = 1.0 / (1.0 + exp(-raw[i]))
            }
        }
        return raw
    }

    private func ensureModnet(modelPath: String?) throws -> (ORTSession, String, String) {
        if let session = modnetSession,
           let inName = modnetInputName,
           let outName = modnetOutputName {
            return (session, inName, outName)
        }
        // 路径由 Main 侧预解析；缺失 → modelMissing（下载 enqueue 已由调用方处理）
        guard let modelPath, FileManager.default.fileExists(atPath: modelPath) else {
            throw MattingError.modelMissing(Self.modnetModelId)
        }
        do {
            let options = try ORTSessionOptions()
            // 注：onnxruntime-objc 1.28 无 setInterOpNumThreads（Android interOp=2 无法对等，登记技术债）
            try options.setIntraOpNumThreads(2)
            try options.setGraphOptimizationLevel(.all)
            let session = try ORTSession(env: ORTSharedEnv.env,
                                         modelPath: modelPath,
                                         sessionOptions: options)
            guard let inName = try session.inputNames().first,
                  let outName = try session.outputNames().first else {
                throw MattingError.inferenceFailed("modnet io names")
            }
            modnetSession = session
            modnetInputName = inName
            modnetOutputName = outName
            NSLog("[PoLang:IdPhoto] modnet loaded: %@", modelPath)
            return (session, inName, outName)
        } catch let error as MattingError {
            throw error
        } catch {
            modnetSession = nil
            throw MattingError.inferenceFailed("modnet session: \(error)")
        }
    }

    // MARK: 工具

    private func scaleTo(_ image: CGImage, size n: Int) -> CGImage? {
        var pixels = [UInt8](repeating: 0, count: n * n * 4)
        return pixels.withUnsafeMutableBytes { ptr -> CGImage? in
            guard let ctx = CGContext(
                data: ptr.baseAddress,
                width: n, height: n,
                bitsPerComponent: 8, bytesPerRow: n * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return nil }
            ctx.interpolationQuality = .medium
            ctx.setAlpha(1)
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: n, height: n))
            return ctx.makeImage()
        }
    }
}
