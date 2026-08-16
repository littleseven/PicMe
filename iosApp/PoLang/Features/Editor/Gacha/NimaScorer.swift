import Foundation
import UIKit
import CoreGraphics
import onnxruntime_objc

// MARK: - AestheticScorer（整图美学评分器抽象）
//
// 移植自 androidApp `domain/aesthetic/AestheticScorer.kt`。
// 抽卡链路（optimize/gacha）依赖本协议而非具体实现，便于单测 mock 与未来替换评分模型。
protocol AestheticScorer: AnyObject {

    /// 初始化模型；不可用（模型未下载等）返回 false，调用方走降级。已初始化则复用会话。
    func initialize() async -> Bool

    /// 给整图打分，分数越高越美；推理失败返回 nil。
    func score(_ image: UIImage) -> Float?

    /// 释放模型资源。由持有本实例的 DI 容器在销毁时调用；引擎/run 周期内不应调用。
    func release()
}

// MARK: - NimaScorer（NIMA 美学评分，ONNX Runtime）
//
// 移植自 androidApp `domain/aesthetic/NimaScorer.kt`。
//
// 模型走模型中心 `nima-aesthetic-onnx`；未下载时 `initialize` 返回 false，调用方跳过（不抛出）。
//
// 权重来源：idealo `weights_mobilenet_aesthetic_0.07`（MobileNet V1 + Dropout + Dense(10, softmax)），
// 经 parity 验证 HF `cromsc/nima-mobilenet-aesthetic` 与之逐位相同。
//
// I/O（实测，与 Android 相同）：输入 `input_1` 名义 NCHW 实为 **NHWC** `1x224x224x3`
// （逐像素交错 RGB，归一化 (x-127.5)/127.5）；输出 `dense_1` `1x10` softmax 分布。
// 分数 = Σ p_i·(i+1)（i=0..9）∈ [1,10]，越高越美。
//
// 隐私：NIMA 推理 100% 端侧（[PRIVACY] 红线，媒体处理不出设备）。
//
// 平台差异（contracts.md C-G5）：
// - 执行提供者：Android NNAPI（GPU/DSP）+ CPU 兜底；iOS CPU/默认 EP（ORT objc 静态注册 CPU）。
// - 像素源：Android `Bitmap.getPixels` IntArray（ARGB）；iOS RGBA8 字节缓冲（见 `preprocessPixels`）。
// - 会话环境：复用 `ORTSharedEnv` 进程级单例 ORTEnv（多 env 会触发 ORT 崩溃，见 ORTSharedEnv.swift）。
final class NimaScorer: AestheticScorer {

    static let modelId = "nima-aesthetic-onnx"
    static let fileName = "nima_mobilenet_aesthetic.onnx"
    static let inputSize = 224

    private static let tag = "[PoLang:Aesthetic]"

    /// NHWC 交错（逐像素 RGB 连续）+ (x-127.5)/127.5。纯数组变换，便于单测。
    /// 入参为 RGBA8 字节缓冲（4 字节/像素，Android 版入参为 ARGB IntArray——语义等价换载体）。
    static func preprocessPixels(_ rgba: [UInt8]) -> [Float] {
        let pixelCount = rgba.count / 4
        var out = [Float](repeating: 0, count: pixelCount * 3)
        for i in 0..<pixelCount {
            let src = i * 4
            let dst = i * 3
            out[dst] = (Float(rgba[src]) - 127.5) / 127.5         // R
            out[dst + 1] = (Float(rgba[src + 1]) - 127.5) / 127.5 // G
            out[dst + 2] = (Float(rgba[src + 2]) - 127.5) / 127.5 // B
        }
        return out
    }

    /// softmax 10-bin 分布 → 期望分 Σ p_i·(i+1) ∈ [1,10]。
    static func expectedScore(_ distribution: [Float]) -> Float {
        var s: Float = 0
        for (i, p) in distribution.enumerated() {
            s += p * Float(i + 1)
        }
        return s
    }

    /// 模型目录 Documents/llm_models/<modelId>/（对齐 ModelDownloadManager 落盘布局；
    /// 注入 modelsRoot 供测试/替代装载源）。
    private let modelDirectory: URL

    private var session: ORTSession?

    init(modelsRoot: URL? = nil) {
        // Documents 目录必然存在（惯例对齐 TagDatabase.defaultPath 的 .first! 用法）
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let root = modelsRoot ?? docs.appendingPathComponent("llm_models")
        self.modelDirectory = root.appendingPathComponent(NimaScorer.modelId)
    }

    func initialize() async -> Bool {
        if session != nil { return true } // 已就绪则复用，避免重复建会话
        let modelPath = modelDirectory.appendingPathComponent(NimaScorer.fileName).path
        guard FileManager.default.fileExists(atPath: modelPath) else {
            NSLog("%@ NIMA model not present: %@", NimaScorer.tag, modelPath)
            return false
        }
        do {
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(2)
            try options.setGraphOptimizationLevel(.all)
            // C-G5：Android NNAPI 加速；iOS CPU/默认 EP，不追加执行提供者。
            session = try ORTSession(env: ORTSharedEnv.env,
                                     modelPath: modelPath,
                                     sessionOptions: options)
            NSLog("%@ NIMA session initialized", NimaScorer.tag)
            return true
        } catch {
            NSLog("%@ NIMA initialize failed: %@", NimaScorer.tag, "\(error)")
            session = nil
            return false
        }
    }

    /// 给一张整图打美学分；失败返回 nil。内部 resize 到 224×224，无需人脸对齐。
    func score(_ image: UIImage) -> Float? {
        guard let currentSession = session else {
            NSLog("%@ NIMA session not initialized", NimaScorer.tag)
            return nil
        }
        guard let input = preprocess(image) else { return nil }
        let tensorData = NSMutableData(bytes: input,
                                       length: input.count * MemoryLayout<Float>.size)
        let shape: [NSNumber] = [1,
                                 NSNumber(value: NimaScorer.inputSize),
                                 NSNumber(value: NimaScorer.inputSize),
                                 3]
        do {
            // 模型单输入/单输出；对齐 Android sess.inputNames.first() / 首输出取值。
            guard let inputName = try currentSession.inputNames().first else { return nil }
            let outputNames = Set(try currentSession.outputNames())
            let tensor = try ORTValue(tensorData: tensorData, elementType: .float, shape: shape)
            return try withExtendedLifetime(tensorData) { () -> Float? in
                let outputs = try currentSession.run(withInputs: [inputName: tensor],
                                                     outputNames: outputNames,
                                                     runOptions: nil)
                guard let outValue = outputs.values.first else { return nil }
                let data = try outValue.tensorData()
                let floatCount = data.length / MemoryLayout<Float>.size
                guard floatCount > 0 else { return nil }
                var floats = [Float](repeating: 0, count: floatCount)
                data.getBytes(&floats, length: data.length)
                return NimaScorer.expectedScore(floats)
            }
        } catch {
            NSLog("%@ NIMA inference failed: %@", NimaScorer.tag, "\(error)")
            return nil
        }
    }

    func release() {
        session = nil
        NSLog("%@ NIMA session released", NimaScorer.tag)
    }

    /// 缩放到 224×224（对齐 Android `Bitmap.createScaledBitmap`：不保持宽高比、双线性过滤）
    /// 后提取 RGBA8 并归一化为 NHWC float。
    private func preprocess(_ image: UIImage) -> [Float]? {
        guard let cg = image.cgImage else { return nil }
        var rgba = [UInt8](repeating: 0, count: NimaScorer.inputSize * NimaScorer.inputSize * 4)
        let ok = rgba.withUnsafeMutableBytes { rawBuffer -> Bool in
            guard let base = rawBuffer.baseAddress,
                  let ctx = CGContext(data: base,
                                      width: NimaScorer.inputSize,
                                      height: NimaScorer.inputSize,
                                      bitsPerComponent: 8,
                                      bytesPerRow: NimaScorer.inputSize * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0,
                                    width: NimaScorer.inputSize,
                                    height: NimaScorer.inputSize))
            return true
        }
        guard ok else { return nil }
        return NimaScorer.preprocessPixels(rgba)
    }
}
