import Foundation
import onnxruntime_objc

// MARK: - MobileClipTextEncoder（双端契约 SSOT: contracts.md §5.3）
//
// MobileCLIP-S2 text encoder（ONNX Runtime iOS），逐字对齐 Android
// `MobileClipOnnxBackend.encodeText`（MobileClipOnnxBackend.kt:123-147）+
// `MobileClipBackend.validateAndNormalize`（MobileClipBackend.kt:51-78）。
//
// - 模型：`Documents/llm_models/mobileclip-onnx/text_model.onnx`（fp32，与 Android 同一文件，
//   ModelScope budaoshou/MobileCLIP-ONNX；fp16 在 CPU 上易 NaN，代码只认 fp32 命名）。
// - ONNX 输入输出名（契约 §5.3）：text `input_ids` → `text_embeds`。
// - 输入：int64 token ids，shape [1, 77]（MobileClipTokenizer 输出）。
// - 输出后处理（契约 §5.3，照抄 Android validateAndNormalize）：
//   维度校验（512）+ NaN/Inf 拒绝 + 零向量拒绝（norm <= 0）+ 强制 L2 归一化。
//   ⚠️ 与 MobileClipEncoder（图像路径）的 0.8 norm 下限不同——文本路径 Android 只拒绝零向量，
//   逐字对齐，不加 0.8 阈值。
//
// ORT 调用模式复用 `MobileClipEncoder`：共享 `ORTSharedEnv.env`、intra-op 2 线程、
// graph optimization .all、CPU fp32（不接 CoreML EP）。

final class MobileClipTextEncoder {

    // MARK: - 常量（契约 §5.3）

    /// embedding 维度（EMBEDDING_DIM = 512）
    static let embeddingDim: Int = 512
    /// 文本最大 token 数（MAX_TEXT_TOKENS = 77）
    static let maxTextTokens: Int = 77

    /// ONNX 输入 / 输出 tensor 名（MobileClipOnnxBackend.kt:30-31）
    private static let inputName = "input_ids"
    private static let outputName = "text_embeds"

    // MARK: - 状态

    /// ORT inference session for `text_model.onnx`
    private var session: ORTSession?

    /// 模型是否加载成功
    var isLoaded: Bool { session != nil }

    // MARK: - 加载

    /// 加载 MobileCLIP-S2 text model。
    /// - Parameter modelPath: `text_model.onnx` 绝对路径。
    /// - Returns: 成功 true；文件不存在或 ORT 会话创建失败 false（错误仅 DEBUG 打日志）。
    @discardableResult
    func load(modelPath: String) -> Bool {
        guard FileManager.default.fileExists(atPath: modelPath) else {
            #if DEBUG
            print("[MobileClipTextEncoder] model file not found: \(modelPath)")
            #endif
            return false
        }

        do {
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(2)
            try options.setGraphOptimizationLevel(.all)

            session = try ORTSession(env: ORTSharedEnv.env,
                                     modelPath: modelPath,
                                     sessionOptions: options)
            #if DEBUG
            print("[MobileClipTextEncoder] text model loaded: \(modelPath)")
            #endif
            return true
        } catch {
            #if DEBUG
            print("[MobileClipTextEncoder] failed to load model: \(error)")
            #endif
            session = nil
            return false
        }
    }

    /// 释放 ORT session（共享 ORTEnv 为进程级单例，不释放）。
    func release() {
        session = nil
    }

    // MARK: - 文本编码（Android MobileClipOnnxBackend.encodeText）

    /// token ids → 512 维 L2 归一化 text embedding。
    /// - Parameter tokenIds: MobileClipTokenizer.encode 输出（长度 77）。
    /// - Returns: 512 维 embedding；未加载 / 推理失败 / 输出校验不通过返回 nil。
    func encodeText(_ tokenIds: [Int64]) -> [Float]? {
        guard let session = session else {
            #if DEBUG
            print("[MobileClipTextEncoder] session not loaded")
            #endif
            return nil
        }

        // int64 tensor [1, N]（N 实际恒为 77，tokenizer 固定长输出）
        var ids = tokenIds
        let tensorData = NSMutableData(bytes: &ids,
                                       length: ids.count * MemoryLayout<Int64>.size)
        let shape: [NSNumber] = [1, NSNumber(value: ids.count)]

        do {
            let inputValue = try ORTValue(tensorData: tensorData,
                                          elementType: .int64,
                                          shape: shape)
            // ORTValue 不拷贝数据，tensorData 必须活过 run()（同 MobileClipEncoder 模式）
            return try withExtendedLifetime(tensorData) {
                try runInference(session: session, inputValue: inputValue)
            }
        } catch {
            #if DEBUG
            print("[MobileClipTextEncoder] inference error: \(error)")
            #endif
            return nil
        }
    }

    // MARK: - 推理（private）

    private func runInference(session: ORTSession, inputValue: ORTValue) throws -> [Float]? {
        let inputs: [String: ORTValue] = [Self.inputName: inputValue]
        let outputNames: Set<String> = [Self.outputName]

        let outputs = try session.run(withInputs: inputs,
                                      outputNames: outputNames,
                                      runOptions: nil)

        guard let outputValue = outputs[Self.outputName],
              let outputData = try? outputValue.tensorData() else {
            #if DEBUG
            print("[MobileClipTextEncoder] output '\(Self.outputName)' missing or unreadable")
            #endif
            return nil
        }

        let expectedBytes = Self.embeddingDim * MemoryLayout<Float>.size
        guard outputData.length == expectedBytes else {
            #if DEBUG
            print("[MobileClipTextEncoder] output size mismatch: \(outputData.length), expected \(expectedBytes)")
            #endif
            return nil
        }

        var embedding = [Float](repeating: 0, count: Self.embeddingDim)
        outputData.getBytes(&embedding, length: expectedBytes)

        return Self.validateAndNormalize(&embedding)
    }

    // MARK: - 校验与归一化（契约 §5.3；Android MobileClipBackend.validateAndNormalize 逐字）

    /// 维度校验（512）+ NaN/Inf 拒绝 + 零向量拒绝（norm <= 0）+ 强制 L2 归一化。
    /// ⚠️ 文本路径 Android 只拒绝零向量（norm <= 0f），不像图像路径有 0.8 下限——逐字对齐。
    static func validateAndNormalize(_ embedding: inout [Float]) -> [Float]? {
        guard embedding.count == embeddingDim else { return nil }

        var norm: Float = 0
        for v in embedding {
            if v.isNaN || v.isInfinite { return nil }
            norm += v * v
        }
        guard norm > 0 else { return nil }

        let rawNorm = sqrt(norm)
        for i in embedding.indices {
            embedding[i] /= rawNorm
        }
        return embedding
    }
}
