import Foundation
import onnxruntime_objc

// MARK: - ORTFaceEmbedder（ONNX Runtime Glint360K R100 人脸 embedding）

/// 替代 MNN `PLMnnFaceEmbedder`——ONNX Runtime 在 Apple 平台上对 Glint360K R100 产出正确判别力 embedding。
///
/// MNN 3.5.0 在 Apple 平台（iOS arm64 + macOS x86）对此模型产出无判别力 embedding（随机 vs 随机 cos=0.92），
/// 同模型同版本在 Android 正常（gap=0.28）。ONNX Runtime 跨平台一致，已验证判别力正常。
///
/// 接口与 `PLMnnFaceEmbedder` 对齐（`load` / `ready` / `extractEmbedding`），Pass1Pipeline 仅改类型即可。
/// 参考模式：`MobileClipEncoder.swift`（同项目已有的 ORT 使用范式）。
final class ORTFaceEmbedder {

    static let embeddingDim = 512
    static let inputSize = 112

    private static let inputName = "input.1"
    private static let outputName = "1333"

    private var session: ORTSession?

    var ready: Bool { session != nil }

    @discardableResult
    func load(modelPath: String) -> Bool {
        guard FileManager.default.fileExists(atPath: modelPath) else {
            NSLog("[PoLang:ORTFaceEmbedder] model not found: %@", modelPath)
            return false
        }
        do {
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(4)
            try options.setGraphOptimizationLevel(.all)
            session = try ORTSession(env: ORTSharedEnv.env,
                                     modelPath: modelPath,
                                     sessionOptions: options)
            NSLog("[PoLang:ORTFaceEmbedder] model loaded: %@", modelPath)
            return true
        } catch {
            NSLog("[PoLang:ORTFaceEmbedder] load failed: %@", "\(error)")
            session = nil
            return false
        }
    }

    func release() {
        session = nil
    }

    /// 从 RGB 像素提取 512 维 L2 归一化 embedding。
    /// - Parameters:
    ///   - rgb: RGB 交错像素（每像素 3 字节），112×112。
    ///   - width: 必须 112。
    ///   - height: 必须 112。
    /// - Returns: Data（512 × 4 = 2048 bytes，Float32 小端，L2 归一化），nil 失败。
    func extractEmbedding(_ rgb: UnsafePointer<UInt8>, width: Int, height: Int) -> Data? {
        guard let session = session, width == Self.inputSize, height == Self.inputSize else { return nil }

        let totalPixels = width * height
        var nchw = [Float](repeating: 0, count: 3 * totalPixels)

        // RGB 交错 → NCHW 平面 + 归一化 (x-127.5)/128.0
        for i in 0..<totalPixels {
            let r = Float(rgb[i * 3])
            let g = Float(rgb[i * 3 + 1])
            let b = Float(rgb[i * 3 + 2])
            nchw[0 * totalPixels + i] = (r - 127.5) / 128.0
            nchw[1 * totalPixels + i] = (g - 127.5) / 128.0
            nchw[2 * totalPixels + i] = (b - 127.5) / 128.0
        }

        let tensorData = NSMutableData(bytes: nchw,
                                      length: nchw.count * MemoryLayout<Float>.size)
        let shape: [NSNumber] = [1, 3, NSNumber(value: width), NSNumber(value: height)]

        do {
            let inputValue = try ORTValue(tensorData: tensorData,
                                          elementType: .float,
                                          shape: shape)
            return try withExtendedLifetime(tensorData) {
                try self.runInference(session: session, inputValue: inputValue)
            }
        } catch {
            NSLog("[PoLang:ORTFaceEmbedder] inference error: %@", "\(error)")
            return nil
        }
    }

    private func runInference(session: ORTSession, inputValue: ORTValue) throws -> Data? {
        let inputs: [String: ORTValue] = [Self.inputName: inputValue]
        let outputNames: Set<String> = [Self.outputName]

        let outputs = try session.run(withInputs: inputs,
                                      outputNames: outputNames,
                                      runOptions: nil)

        guard let outputValue = outputs[Self.outputName] else { return nil }
        guard let outputData = try? outputValue.tensorData() else { return nil }

        let expectedBytes = Self.embeddingDim * MemoryLayout<Float>.size
        guard outputData.length == expectedBytes else { return nil }

        // 拷贝到 [Float] 做 L2 归一化 + NaN/Inf 过滤
        var embedding = [Float](repeating: 0, count: Self.embeddingDim)
        outputData.getBytes(&embedding, length: expectedBytes)

        var sumSq: Double = 0
        for v in embedding {
            if v.isNaN || v.isInfinite { return nil }
            sumSq += Double(v) * Double(v)
        }
        let norm = sumSq.squareRoot()
        guard norm > 0.5 else { return nil }

        for i in 0..<Self.embeddingDim {
            embedding[i] = Float(Double(embedding[i]) / norm)
        }

        return Data(bytes: embedding, count: expectedBytes)
    }
}
