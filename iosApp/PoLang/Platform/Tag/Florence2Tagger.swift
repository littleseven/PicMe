import Foundation
import UIKit
import CoreGraphics
import onnxruntime_objc

// MARK: - Florence2TagResult

/// Florence-2 打标结果（对齐 Android UnifiedTagResult 子集）。
struct Florence2TagResult {
    /// 英文内容标签（OD 物体标签 + caption 关键词，去重，≤8 个）
    let labelsEn: [String]
    /// 完整 caption 文本（MORE_DETAILED_CAPTION 任务输出）
    let summary: String?
    /// 场景词（从 caption 中抽取，如 "indoor" / "outdoor"）
    let scene: String?
    /// 活动词（从 caption 中抽取，如 "standing" / "smiling"）
    let activity: String?
}

// MARK: - Florence2Tagger

/// Florence-2-base (231M) ONNX ORT 打标器（INT8 量化）—— iOS Swift 移植。
///
/// 移植自 Android `Florence2Tagger.kt`（583 行）。
///
/// 四模型管道：
/// 1. vision_encoder (INT8): UIImage → 768×768 pixel_values → image_features [577, 768]
/// 2. embed_tokens   (INT8): task prompt token ids → text_embeds [L, 768]
/// 3. encoder        (INT8): [image_features ⊕ text_embeds] + attention_mask → encoder_hidden_states [T, 768]
/// 4. decoder (merged, INT8): encoder_hidden_states + 自回归生成 token ids（no-cache 路径）
///
/// decoder 使用 no-cache 路径（`use_cache_branch=false`）—— O(n²) 但正确且简单。
///
/// 任务 prompt：
/// - `<OD>` → objects/tags（+ bbox loc 坐标）
/// - `<MORE_DETAILED_CAPTION>` → summary + scene/activity
final class Florence2Tagger {

    // MARK: - Constants（对齐 Florence2Tagger.kt companion）

    static let imageSize = 768
    static let hiddenSize = 768
    static let vocabSize = 51289
    static let maxNewTokens = 256
    static let decoderStartTokenId: Int64 = 2
    static let eosTokenId: Int64 = 2

    /// BART 结构参数（构造 dummy past_key_values 用）
    private static let numLayers = 6
    private static let numHeads = 12
    private static let headDim = 64

    /// 图像预处理 ImageNet 均值/标准差
    private static let imageMean: [Float] = [0.485, 0.456, 0.406]
    private static let imageStd: [Float] = [0.229, 0.224, 0.225]

    /// INT8 量化文件名（与 catalog / ModelPathConfig 一致）
    private static let visionEncoderFile = "vision_encoder_quantized.onnx"
    private static let textEncoderFile = "encoder_model_quantized.onnx"
    private static let decoderFile = "decoder_model_merged_quantized.onnx"
    private static let embedTokensFile = "embed_tokens_int8.onnx"

    /// Task prompt token ids —— HF processor 展开 `<task>` 后 BPE 分词的结果（PC 已验证）。
    /// <OD> = "<s>Locate the objects with category name in the image.</s>"
    static let taskOD: [Int64] = [
        0, 574, 22486, 5, 8720, 19, 4120, 766, 11, 5, 2274, 4, 2,
    ]
    /// <MORE_DETAILED_CAPTION> = "<s>Describe with a paragraph what is shown in the image.</s>"
    static let taskMoreDetailedCaption: [Int64] = [
        0, 47066, 21700, 19, 10, 17818, 99, 16, 2343, 11, 5, 2274, 4, 2,
    ]

    // MARK: - Properties

    /// Process-level ORT environment（与 MobileClipEncoder 同模式：进程级，不释放）
    private var env: ORTEnv?

    /// 4 个 ORT 推理会话
    private var visionEncSession: ORTSession?
    private var textEncSession: ORTSession?
    private var decoderSession: ORTSession?
    private var embedTokensSession: ORTSession?

    /// 各 session 的输出名（load 时自动发现）
    private var visionEncOutputName: String = ""
    private var textEncOutputName: String = ""
    private var decoderOutputName: String = ""
    private var embedTokensOutputName: String = ""

    /// 归一化 LUT（256 项/通道）：替代逐像素浮点除法
    private let rLut: [Float]
    private let gLut: [Float]
    private let bLut: [Float]

    /// 解码专用 tokenizer
    private let tokenizer = Florence2Tokenizer()

    /// 是否已成功加载
    private(set) var isLoaded = false

    // MARK: - Init

    init() {
        rLut = Florence2Tagger.buildNormalizeLut(mean: Self.imageMean[0], std: Self.imageStd[0])
        gLut = Florence2Tagger.buildNormalizeLut(mean: Self.imageMean[1], std: Self.imageStd[1])
        bLut = Florence2Tagger.buildNormalizeLut(mean: Self.imageMean[2], std: Self.imageStd[2])
    }

    // MARK: - Load

    /// 加载 4 个 ORT session + tokenizer vocab。任一失败则释放已加载的并返回 false。
    /// modelDir 格式：Documents/llm_models/florence2_base/
    @discardableResult
    func load(modelDir: String) -> Bool {
        if isLoaded { return true }

        NSLog("PoLang:Florence2 Loading from \(modelDir)")

        // 检查所有模型文件存在
        let files = [
            Self.visionEncoderFile, Self.textEncoderFile,
            Self.decoderFile, Self.embedTokensFile,
        ]
        for f in files {
            let path = (modelDir as NSString).appendingPathComponent(f)
            if !FileManager.default.fileExists(atPath: path) {
                NSLog("PoLang:Florence2 Model file missing: \(f)")
                return false
            }
        }

        do {
            env = try ORTEnv(loggingLevel: .warning)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(4)
            try options.setGraphOptimizationLevel(.all)

            visionEncSession = try ORTSession(
                env: env!,
                modelPath: (modelDir as NSString).appendingPathComponent(Self.visionEncoderFile),
                sessionOptions: options)
            textEncSession = try ORTSession(
                env: env!,
                modelPath: (modelDir as NSString).appendingPathComponent(Self.textEncoderFile),
                sessionOptions: options)
            decoderSession = try ORTSession(
                env: env!,
                modelPath: (modelDir as NSString).appendingPathComponent(Self.decoderFile),
                sessionOptions: options)
            embedTokensSession = try ORTSession(
                env: env!,
                modelPath: (modelDir as NSString).appendingPathComponent(Self.embedTokensFile),
                sessionOptions: options)

            // 发现各 session 的输出名（各模型只有一个输出）
            // ORTSession 的 outputNamesWithError: 被 Swift 导入为 outputNames() throws
            if let names = try? visionEncSession?.outputNames(), let first = names.first {
                visionEncOutputName = first
            }
            if let names = try? textEncSession?.outputNames(), let first = names.first {
                textEncOutputName = first
            }
            if let names = try? decoderSession?.outputNames(), let first = names.first {
                decoderOutputName = first
            }
            if let names = try? embedTokensSession?.outputNames(), let first = names.first {
                embedTokensOutputName = first
            }

            NSLog("PoLang:Florence2 Output names: vis=\(visionEncOutputName) enc=\(textEncOutputName) dec=\(decoderOutputName) emb=\(embedTokensOutputName)")

            // 加载 tokenizer vocab
            guard tokenizer.load(modelDir: modelDir) else {
                NSLog("PoLang:Florence2 Tokenizer load failed")
                release()
                return false
            }

            isLoaded = true
            NSLog("PoLang:Florence2 Initialized (4 INT8 sessions, no-cache decoder)")
            return true
        } catch {
            NSLog("PoLang:Florence2 Init failed: \(error)")
            release()
            return false
        }
    }

    /// 检查模型文件是否存在（不实际加载 session）。
    static func modelsAvailable(modelDir: String) -> Bool {
        let files = [
            visionEncoderFile, textEncoderFile,
            decoderFile, embedTokensFile,
        ]
        for f in files {
            let path = (modelDir as NSString).appendingPathComponent(f)
            if !FileManager.default.fileExists(atPath: path) { return false }
        }
        // vocab.json 也必须存在
        let vocabPath = (modelDir as NSString).appendingPathComponent("vocab.json")
        return FileManager.default.fileExists(atPath: vocabPath)
    }

    func release() {
        visionEncSession = nil
        textEncSession = nil
        decoderSession = nil
        embedTokensSession = nil
        // ORTEnv 进程级保留（与 MobileClipEncoder 同策略）
        isLoaded = false
    }

    // MARK: - 完整打标流程

    /// 对一张图片执行 OD + MORE_DETAILED_CAPTION 双任务打标。
    ///
    /// 移植自 Florence2Tagger.kt:160-194（tag 方法）。
    ///
    /// - Parameter image: 输入图片
    /// - Returns: 打标结果，模型未加载或推理失败返回 nil
    func tag(_ image: UIImage) -> Florence2TagResult? {
        guard isLoaded else {
            NSLog("PoLang:Florence2 Not initialized")
            return nil
        }

        let t0 = CFAbsoluteTimeGetCurrent()

        // ── 1. 图像预处理 → vision encoder（每张图只跑一次）──
        guard let pixelValues = preprocessImage(image) else {
            NSLog("PoLang:Florence2 Image preprocessing failed")
            return nil
        }
        let preprocessMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)

        guard let imageFeatures = runVisionEncoder(pixelValues) else {
            NSLog("PoLang:Florence2 Vision encoder failed")
            return nil
        }
        let visionMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000) - preprocessMs

        // ── 2. OD 任务（物体检测 → objects/tags）──
        let odText = runTask(imageFeatures: imageFeatures, taskTokenIds: Self.taskOD) ?? ""
        let objects = Self.parseODLabels(odText)

        // ── 3. MORE_DETAILED_CAPTION 任务（→ summary + scene/activity）──
        let captionText = runTask(
            imageFeatures: imageFeatures, taskTokenIds: Self.taskMoreDetailedCaption) ?? ""

        let totalMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        NSLog("PoLang:Florence2 [Benchmark] preprocess=\(preprocessMs)ms vision=\(visionMs)ms total=\(totalMs)ms")
        NSLog("PoLang:Florence2 OD labels: \(objects); caption: \(captionText.prefix(80))")

        // ── 4. 组装结果 ──
        var labels = objects
        for kw in Self.extractKeywords(captionText) {
            if !labels.contains(kw) && labels.count < 8 {
                labels.append(kw)
            }
        }

        let scene = Self.extractScene(captionText)
        let activity = Self.extractActivity(captionText)

        return Florence2TagResult(
            labelsEn: labels,
            summary: captionText.isEmpty ? nil : captionText,
            scene: scene.isEmpty ? nil : scene,
            activity: activity.isEmpty ? nil : activity
        )
    }

    // MARK: - 图像预处理

    /// UIImage → resize 768×768 → ImageNet normalize → [3*768*768] Float 数组。
    ///
    /// 移植自 Florence2Tagger.kt:206-218 + Florence2Preprocess.kt。
    /// 注意：Florence-2 的 preprocessor 是 resize（非 center crop）+ ImageNet mean/std。
    private func preprocessImage(_ image: UIImage) -> [Float]? {
        let size = Self.imageSize

        // 获取正向 CGImage（朝向归一化）
        guard let sourceCG = normalizedCGImage(from: image) else {
            NSLog("PoLang:Florence2 UIImage has no CGImage backing")
            return nil
        }

        // 创建 768×768 RGBA8 context
        let bytesPerPixel = 4
        let bytesPerRow = size * bytesPerPixel
        var pixelData = [UInt8](repeating: 0, count: size * size * bytesPerPixel)

        guard let context = CGContext(
            data: &pixelData,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else {
            NSLog("PoLang:Florence2 Failed to create CGContext")
            return nil
        }

        // CGContext origin 是左下角（y-up）；CGImage 是左上角（y-down）。
        // 垂直翻转使输出匹配自然图像朝向。
        context.translateBy(x: 0, y: CGFloat(size))
        context.scaleBy(x: 1, y: -1)
        context.interpolationQuality = .high

        // 缩放绘制到 768×768（非 center crop，与 Florence-2 preprocessor 一致）
        context.draw(sourceCG,
                     in: CGRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size)))

        // RGBA8 → CHW float planes，经 LUT 归一化
        let plane = size * size
        var result = [Float](repeating: 0, count: 3 * plane)
        for i in 0..<plane {
            let pixelIndex = i * bytesPerPixel
            result[i] = rLut[Int(pixelData[pixelIndex + 0])]
            result[plane + i] = gLut[Int(pixelData[pixelIndex + 1])]
            result[2 * plane + i] = bLut[Int(pixelData[pixelIndex + 2])]
        }
        return result
    }

    // MARK: - Vision Encoder

    /// pixel_values [1,3,768,768] → vision_encoder → image_features [577*768] flat Float array。
    ///
    /// 移植自 Florence2Tagger.kt:227-245。
    private func runVisionEncoder(_ pixelValues: [Float]) -> [Float]? {
        guard let session = visionEncSession else { return nil }

        let tensorData = NSMutableData(bytes: pixelValues,
                                       length: pixelValues.count * MemoryLayout<Float>.size)
        let shape: [NSNumber] = [1, 3, NSNumber(value: Self.imageSize), NSNumber(value: Self.imageSize)]

        do {
            let inputValue = try ORTValue(tensorData: tensorData,
                                           elementType: .float,
                                           shape: shape)
            let inputs: [String: ORTValue] = ["pixel_values": inputValue]
            // 保持 tensorData 活跃直到 run 完成
            let outputs = try withExtendedLifetime(tensorData) {
                try session.run(withInputs: inputs,
                                outputNames: [visionEncOutputName],
                                runOptions: nil)
            }
            guard let outputValue = outputs[visionEncOutputName] else { return nil }
            return readFloatTensor(outputValue)
        } catch {
            NSLog("PoLang:Florence2 Vision encoder error: \(error)")
            return nil
        }
    }

    // MARK: - 单任务执行（embed + encoder + no-cache decoder loop）

    /// 执行一个 Florence-2 任务（OD / CAPTION），返回解码后的文本。
    ///
    /// 移植自 Florence2Tagger.kt:254-283（runTask 方法）。
    private func runTask(imageFeatures: [Float], taskTokenIds: [Int64]) -> String? {
        // ── embed_tokens: task ids → text_embeds [L, 768] ──
        guard let textEmbeds = runEmbedTokens(taskTokenIds) else { return nil }
        let textLen = taskTokenIds.count

        // ── concat [image_features ⊕ text_embeds] → inputs_embeds [T, 768] ──
        // imageFeatures 是 577*768 flat array
        var inputsEmbeds = [Float]()
        inputsEmbeds.reserveCapacity(imageFeatures.count + textEmbeds.count)
        inputsEmbeds.append(contentsOf: imageFeatures)
        inputsEmbeds.append(contentsOf: textEmbeds)
        let totalLen = 577 + textLen

        // ── encoder: inputs_embeds + attention_mask → encoder_hidden_states ──
        guard let encoderHiddenStates = runEncoder(inputsEmbeds: inputsEmbeds, totalLen: totalLen) else {
            return nil
        }

        // ── decoder: no-cache 自回归生成 ──
        guard let tokenIds = runDecoderNoCache(
            encoderHiddenStates: encoderHiddenStates, encoderLen: totalLen) else {
            return nil
        }

        // ── decode token ids → text ──
        // 去掉模型强制输出的前导 <s>（BOS, forced_bos_token_id=0）——它不是内容。
        var text = tokenizer.decode(tokenIds)
        if text.hasPrefix("<s>") {
            text = String(text.dropFirst(3))
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Embed Tokens

    /// embed_tokens: input_ids [L] → flat [L*768] float array。
    ///
    /// 移植自 Florence2Tagger.kt:288-306。
    private func runEmbedTokens(_ tokenIds: [Int64]) -> [Float]? {
        guard let session = embedTokensSession else { return nil }
        let l = tokenIds.count

        // 创建 int64 tensor [1, L]
        let tensorData = NSMutableData(bytes: tokenIds,
                                       length: l * MemoryLayout<Int64>.size)
        let shape: [NSNumber] = [1, NSNumber(value: l)]

        do {
            let inputValue = try ORTValue(tensorData: tensorData,
                                           elementType: .int64,
                                           shape: shape)
            let inputs: [String: ORTValue] = ["input_ids": inputValue]
            let outputs = try withExtendedLifetime(tensorData) {
                try session.run(withInputs: inputs,
                                outputNames: [embedTokensOutputName],
                                runOptions: nil)
            }
            guard let outputValue = outputs[embedTokensOutputName] else { return nil }
            return readFloatTensor(outputValue)
        } catch {
            NSLog("PoLang:Florence2 Embed tokens error: \(error)")
            return nil
        }
    }

    // MARK: - Text Encoder

    /// encoder: inputs_embeds [T, 768] + attention_mask [T] → encoder_hidden_states [T*768] flat。
    ///
    /// 移植自 Florence2Tagger.kt:311-336。
    private func runEncoder(inputsEmbeds: [Float], totalLen: Int) -> [Float]? {
        guard let session = textEncSession else { return nil }

        let embedShape: [NSNumber] = [1, NSNumber(value: totalLen), NSNumber(value: Self.hiddenSize)]
        let maskShape: [NSNumber] = [1, NSNumber(value: totalLen)]
        let attentionMask = [Int64](repeating: 1, count: totalLen)

        let embedData = NSMutableData(bytes: inputsEmbeds,
                                      length: inputsEmbeds.count * MemoryLayout<Float>.size)
        let maskData = NSMutableData(bytes: attentionMask,
                                     length: attentionMask.count * MemoryLayout<Int64>.size)

        do {
            let embedTensor = try ORTValue(tensorData: embedData,
                                            elementType: .float,
                                            shape: embedShape)
            let maskTensor = try ORTValue(tensorData: maskData,
                                           elementType: .int64,
                                           shape: maskShape)
            let inputs: [String: ORTValue] = [
                "inputs_embeds": embedTensor,
                "attention_mask": maskTensor,
            ]
            // 保持两个 buffer 活跃直到 run 完成
            let outputs = try withExtendedLifetime(embedData) {
                try withExtendedLifetime(maskData) {
                    try session.run(withInputs: inputs,
                                    outputNames: [textEncOutputName],
                                    runOptions: nil)
                }
            }
            guard let outputValue = outputs[textEncOutputName] else { return nil }
            return readFloatTensor(outputValue)
        } catch {
            NSLog("PoLang:Florence2 Encoder error: \(error)")
            return nil
        }
    }

    // MARK: - Decoder（no-cache 自回归生成）

    /// no-cache 自回归生成。
    ///
    /// 每步把 [DEC_START, *已生成 token] 整个序列重新 embed 喂给 merged decoder，
    /// use_cache_branch=false，取 logits 最后一位 argmax。O(n²)，正确但简单。
    ///
    /// 移植自 Florence2Tagger.kt:512-566（runDecoderNoCache）。
    private func runDecoderNoCache(encoderHiddenStates: [Float], encoderLen: Int) -> [Int64]? {
        guard let session = decoderSession else { return nil }

        // ── 创建可复用的张量（encoder_hidden_states / mask / dummy past）──
        // encoder_hidden_states [1, encoderLen, 768]
        let encHsData = NSMutableData(bytes: encoderHiddenStates,
                                      length: encoderHiddenStates.count * MemoryLayout<Float>.size)
        let encHsShape: [NSNumber] = [1, NSNumber(value: encoderLen), NSNumber(value: Self.hiddenSize)]

        // encoder_attention_mask [1, encoderLen] int64（全 1）
        let encMask = [Int64](repeating: 1, count: encoderLen)
        let encMaskData = NSMutableData(bytes: encMask,
                                        length: encMask.count * MemoryLayout<Int64>.size)
        let encMaskShape: [NSNumber] = [1, NSNumber(value: encoderLen)]

        // dummy decoder past [1, 12, 1, 64]（全零；use_cache_branch=false 不读内容）
        let dummyDecCount = Self.numHeads * Self.headDim // 768
        let dummyDec = [Float](repeating: 0, count: dummyDecCount)
        let dummyDecData = NSMutableData(bytes: dummyDec,
                                         length: dummyDecCount * MemoryLayout<Float>.size)
        let dummyDecShape: [NSNumber] = [1, NSNumber(value: Self.numHeads), 1, NSNumber(value: Self.headDim)]

        // dummy encoder past [1, 12, encoderLen, 64]（全零）
        let dummyEncCount = Self.numHeads * encoderLen * Self.headDim
        let dummyEnc = [Float](repeating: 0, count: dummyEncCount)
        let dummyEncData = NSMutableData(bytes: dummyEnc,
                                         length: dummyEncCount * MemoryLayout<Float>.size)
        let dummyEncShape: [NSNumber] = [1, NSNumber(value: Self.numHeads),
                                         NSNumber(value: encoderLen), NSNumber(value: Self.headDim)]

        // use_cache_branch = false（BOOL 类型标量，通过 C++ helper 创建）
        guard let useCacheFalse = ortCreateBoolTensor(false, nil) else {
            NSLog("PoLang:Florence2 Failed to create use_cache_branch tensor")
            return nil
        }

        do {
            let encHsValue = try ORTValue(tensorData: encHsData,
                                           elementType: .float, shape: encHsShape)
            let encMaskValue = try ORTValue(tensorData: encMaskData,
                                             elementType: .int64, shape: encMaskShape)
            let dummyDecValue = try ORTValue(tensorData: dummyDecData,
                                              elementType: .float, shape: dummyDecShape)
            let dummyEncValue = try ORTValue(tensorData: dummyEncData,
                                              elementType: .float, shape: dummyEncShape)

            // ── 自回归循环 ──
            var generatedIds: [Int64] = []
            var seq: [Int64] = [Self.decoderStartTokenId] // [2]

            // 保持所有复用 buffer 活跃
            return try withExtendedLifetime(encHsData) {
                try withExtendedLifetime(encMaskData) {
                    try withExtendedLifetime(dummyDecData) {
                        try withExtendedLifetime(dummyEncData) {
                            try self.decoderLoop(
                                session: session,
                                encHsValue: encHsValue,
                                encMaskValue: encMaskValue,
                                dummyDecValue: dummyDecValue,
                                dummyEncValue: dummyEncValue,
                                useCacheFalse: useCacheFalse,
                                seq: &seq,
                                generatedIds: &generatedIds
                            )
                        }
                    }
                }
            }
        } catch {
            NSLog("PoLang:Florence2 Decoder init error: \(error)")
            return nil
        }
    }

    /// 实际的 decoder 循环（提取为单独方法避免 withExtendedLifetime 嵌套过深）。
    private func decoderLoop(
        session: ORTSession,
        encHsValue: ORTValue,
        encMaskValue: ORTValue,
        dummyDecValue: ORTValue,
        dummyEncValue: ORTValue,
        useCacheFalse: ORTValue,
        seq: inout [Int64],
        generatedIds: inout [Int64]
    ) throws -> [Int64] {
        let outName = decoderOutputName

        for _ in 0..<Self.maxNewTokens {
            // embed 当前完整序列 [DEC_START, *gen]
            let decLen = seq.count
            guard let decEmbeds = runEmbedTokens(seq) else { return generatedIds }

            // 创建 inputs_embeds tensor [1, decLen, 768]
            let decData = NSMutableData(bytes: decEmbeds,
                                        length: decEmbeds.count * MemoryLayout<Float>.size)
            let decShape: [NSNumber] = [1, NSNumber(value: decLen), NSNumber(value: Self.hiddenSize)]
            let decValue = try ORTValue(tensorData: decData,
                                         elementType: .float, shape: decShape)

            // 构建 inputs（28+ 输入）
            var inputs: [String: ORTValue] = [
                "encoder_attention_mask": encMaskValue,
                "encoder_hidden_states": encHsValue,
                "inputs_embeds": decValue,
                "use_cache_branch": useCacheFalse,
            ]
            for layer in 0..<Self.numLayers {
                inputs["past_key_values.\(layer).decoder.key"] = dummyDecValue
                inputs["past_key_values.\(layer).decoder.value"] = dummyDecValue
                inputs["past_key_values.\(layer).encoder.key"] = dummyEncValue
                inputs["past_key_values.\(layer).encoder.value"] = dummyEncValue
            }

            // run（保持 decData 活跃）
            let outputs = try withExtendedLifetime(decData) {
                try session.run(withInputs: inputs,
                                outputNames: [outName],
                                runOptions: nil)
            }
            guard let logitsValue = outputs[outName] else { return generatedIds }
            guard let logitsData = try? logitsValue.tensorData() else { return generatedIds }

            // argmax 最后一位
            let bestId = argmaxLast(logitsData: logitsData, seqLen: decLen)

            if bestId == Self.eosTokenId { break }

            generatedIds.append(bestId)
            seq.append(bestId)
        }

        return generatedIds
    }

    // MARK: - Argmax

    /// logits [1, seqLen, VOCAB] 最后一位的 argmax。
    /// 直接从 NSMutableData 的字节中读取最后一行，避免物化整张 logits。
    ///
    /// 移植自 Florence2Tagger.kt:569-582。
    private func argmaxLast(logitsData: NSMutableData, seqLen: Int) -> Int64 {
        let vs = Self.vocabSize
        let rowStartByte = (seqLen - 1) * vs * MemoryLayout<Float>.size

        var bestId = 0
        var bestScore: Float = -.infinity

        // NSMutableData.mutableBytes 返回 UnsafeMutableRawPointer，直接读取最后一行
        let baseAddress = logitsData.mutableBytes
        let floatPtr = baseAddress.advanced(by: rowStartByte)
            .assumingMemoryBound(to: Float.self)
        for i in 0..<vs {
            let v = floatPtr[i]
            if v > bestScore {
                bestScore = v
                bestId = i
            }
        }
        return Int64(bestId)
    }

    // MARK: - Tensor 读取工具

    /// 读取 ORTValue float tensor 为 flat [Float] 数组。
    private func readFloatTensor(_ value: ORTValue) -> [Float]? {
        guard let data = try? value.tensorData() else { return nil }
        let floatCount = data.length / MemoryLayout<Float>.size
        var result = [Float](repeating: 0, count: floatCount)
        data.getBytes(&result, length: data.length)
        return result
    }

    // MARK: - UIImage 朝向归一化

    /// 返回朝向归一化为 .up 的 CGImage。
    /// 复用 MobileClipEncoder.normalizedCGImage 的逻辑。
    private func normalizedCGImage(from image: UIImage) -> CGImage? {
        // Fast path: already upright.
        if image.imageOrientation == .up, let cg = image.cgImage {
            return cg
        }
        // Slow path: render through bitmap context with orientation applied.
        let renderer = UIGraphicsImageRenderer(size: image.size)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
        return rendered.cgImage
    }

    // MARK: - 归一化 LUT（移植自 Florence2Preprocess.kt:16-19）

    /// 单通道 256 项归一化查找表：lut[v] = (v/255 - mean) / std
    private static func buildNormalizeLut(mean: Float, std: Float) -> [Float] {
        let invStd = 1.0 / std
        return (0..<256).map { v in
            (Float(v) / 255.0 - mean) * invStd
        }
    }

    // MARK: - 输出解析器（移植自 Florence2ResultParser.kt）

    private static let locPattern = "<loc_\\d+>"

    private static let sceneKeywords = [
        "indoor", "outdoor", "park", "street", "restaurant", "office", "home",
        "beach", "mountain", "studio", "city", "countryside", "garden", "kitchen",
        "bedroom", "classroom", "screenshot", "document", "sky", "forest", "river",
        "building", "portrait", "landscape",
    ]

    private static let activityKeywords = [
        "posing", "sitting", "standing", "walking", "eating", "smiling", "looking",
        "running", "reading", "working", "sleeping", "dancing", "cooking", "drinking",
        "holding", "wearing", "playing", "talking", "driving", "traveling", "selfie",
    ]

    private static let filteredLabels: Set<String> = [
        "human face", "human body", "human hair", "human hand", "human eye",
        "human nose", "human mouth", "human ear", "human head", "human arm",
        "human leg", "human foot", "human skin",
    ]

    private static let stopWords: Set<String> = [
        "the", "a", "an", "is", "are", "was", "were", "with", "and", "or",
        "in", "on", "at", "to", "of", "for", "it", "this", "that", "image",
        "photo", "picture", "can", "be", "seen", "there", "has", "have",
        "her", "his", "she", "he", "who", "which", "from",
    ]

    /// 从 OD 结果中提取物体标签。
    /// 输入: "fancy dress<loc_1><loc_2>...necklace<loc_1>..."
    /// 输出: ["fancy dress", "necklace"]
    ///
    /// 移植自 Florence2ResultParser.kt:41-50。
    static func parseODLabels(_ odText: String) -> [String] {
        let cleaned = odText.replacingOccurrences(
            of: locPattern, with: "|||", options: .regularExpression)
        let labels = cleaned.components(separatedBy: "|||")
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty && !filteredLabels.contains($0) }
        // 去重（保序）
        var seen = Set<String>()
        return labels.filter { seen.insert($0).inserted }
    }

    /// 从 caption 中提取 scene（一个词）。
    /// 移植自 Florence2ResultParser.kt:55-61。
    static func extractScene(_ caption: String) -> String {
        let lower = caption.lowercased()
        for kw in sceneKeywords {
            if lower.contains(kw) { return kw }
        }
        return ""
    }

    /// 从 caption 中提取 activity（一个短语）。
    /// 移植自 Florence2ResultParser.kt:66-72。
    static func extractActivity(_ caption: String) -> String {
        let lower = caption.lowercased()
        for kw in activityKeywords {
            if lower.contains(kw) { return kw }
        }
        return ""
    }

    /// 从 caption 中提取关键词（简单分词 + 过滤停用词）。
    /// 移植自 Florence2ResultParser.kt:77-90。
    static func extractKeywords(_ caption: String) -> [String] {
        let lower = caption.lowercased()
            .replacingOccurrences(of: "[^a-z\\s]", with: " ", options: .regularExpression)
        return lower.components(separatedBy: .whitespacesAndNewlines)
            .filter { $0.count > 2 && !stopWords.contains($0) }
            .reduce(into: [String]()) { result, word in
                if !result.contains(word) { result.append(word) }
            }
            .prefix(5)
            .map { String($0) }
    }
}
