import Foundation
import UIKit
import ImageIO

// MARK: - HeuristicSceneAnalyzer（端侧启发式场景分析器）
//
// 移植自 androidApp `domain/agent/capability/optimize/analyzer/HeuristicSceneAnalyzer.kt`。
//
// 解码缩略图（≤256px maxDim）做像素统计（亮度/饱和度/色温/对比度）。
// Android 可选注入 FaceDetector 做轻量人脸计数区分 SELFIE/PORTRAIT/GROUP；
// **iOS 暂不接人脸检测**（人脸判据缺失时按无脸路径走，对齐 Android faceDetector == null 行为），
// 后续可接 TagScan 的 RetinaFace 管线补齐人脸场景。
//
// 启发式优先级（高 → 低；1 因无脸检测不可达）：
// 1. 人脸数量 + 尺寸 → SELFIE / PORTRAIT / GROUP（iOS 未接，跳过）
// 2. 低亮度 → LOW_LIGHT
// 3. 高饱和暖色 → FOOD
// 4. 高对比低彩 → DOCUMENT
// 5. 绿色主导（自然） → LANDSCAPE
// 6. 默认 → GENERAL
//
// 隐私红线：全程零网络调用，像素统计 100% 端侧。
//
// 平台差异：Android BitmapFactory inSampleSize 两段解码（maxDim 落在 [128, 256]）；
// iOS CGImageSourceCreateThumbnailAtIndex(maxPixelSize=256)（maxDim ≤ 256）。
// 统计量为均值/跨度类阈值判定，分辨率差异在容差内。
final class HeuristicSceneAnalyzer {

    private static let tag = "[PoLang:HeuristicSceneAnalyzer]"

    /// 缩略图最大边像素，像素统计只用缩略图以控制耗时。
    static let maxThumbnailDim = 256

    // ---- 启发式阈值（逐值对齐 Android）----
    /// 平均亮度低于此值（0-255）判定为暗光。
    static let lowLightBrightness: Float = 50
    /// 平均饱和度高于此值（0-1）进入食物候选。
    static let foodSaturation: Float = 0.32
    /// 暖色偏置（R-B，0-1）下限，叠加高饱和判定食物。
    static let foodWarmBias: Float = 0.02
    /// 明暗对比跨度（0-255）高于此值进入文档候选。
    static let documentContrast: Float = 68
    /// 文档候选需平均饱和度低于此值（0-1，低彩）。
    static let documentSaturation: Float = 0.12
    /// 绿色主导（G-(R+B)/2，0-1）高于此值判定为风景。
    static let landscapeGreenBias: Float = 0.08
    /// 单张人脸最大边占图像最大边比例 ≥ 此值判定自拍（iOS 未接人脸检测，暂不可达）。
    static let selfieFaceRatio: Float = 0.35
    /// 人脸数 ≥ 此值判定合影（iOS 未接人脸检测，暂不可达）。
    static let groupFaceCount = 2

    func analyze(imageFile: URL) async -> OptimizeScene {
        guard let thumbnail = decodeThumbnail(imageFile: imageFile) else {
            NSLog("%@ Failed to decode thumbnail, fallback GENERAL", HeuristicSceneAnalyzer.tag)
            return .general
        }
        return analyzeImage(thumbnail)
    }

    /// 按优先级链对缩略图做场景判定（internal 供单测注入合成图）。
    func analyzeImage(_ image: UIImage) -> OptimizeScene {
        // 1. 人脸数量 + 尺寸（最高优先级；iOS 未接人脸检测 → 恒走无脸路径）

        // 2-5. 像素统计驱动的非脸场景
        guard let rgba = extractPixels(image) else {
            NSLog("%@ pixel extract failed, fallback GENERAL", HeuristicSceneAnalyzer.tag)
            return .general
        }
        let stats = HeuristicSceneAnalyzer.computePixelStats(rgba)

        // 2. 低亮度
        if stats.brightness < HeuristicSceneAnalyzer.lowLightBrightness {
            NSLog("%@ OptimizeScene: LOW_LIGHT (brightness=%f)", HeuristicSceneAnalyzer.tag, stats.brightness)
            return .lowLight
        }

        // 3. 高饱和暖色 → 美食
        if stats.saturation > HeuristicSceneAnalyzer.foodSaturation &&
            stats.warmBias > HeuristicSceneAnalyzer.foodWarmBias {
            NSLog("%@ OptimizeScene: FOOD (saturation=%f, warmBias=%f)",
                  HeuristicSceneAnalyzer.tag, stats.saturation, stats.warmBias)
            return .food
        }

        // 4. 高对比低彩 → 文档
        if stats.contrast > HeuristicSceneAnalyzer.documentContrast &&
            stats.saturation < HeuristicSceneAnalyzer.documentSaturation {
            NSLog("%@ OptimizeScene: DOCUMENT (contrast=%f, saturation=%f)",
                  HeuristicSceneAnalyzer.tag, stats.contrast, stats.saturation)
            return .document
        }

        // 5. 绿色主导 → 风景
        if stats.greenBias > HeuristicSceneAnalyzer.landscapeGreenBias {
            NSLog("%@ OptimizeScene: LANDSCAPE (greenBias=%f)", HeuristicSceneAnalyzer.tag, stats.greenBias)
            return .landscape
        }

        // 6. 默认
        NSLog("%@ OptimizeScene: GENERAL (brightness=%f, saturation=%f)",
              HeuristicSceneAnalyzer.tag, stats.brightness, stats.saturation)
        return .general
    }

    /// 像素统计快照（归一化口径对齐 Android PixelStats）。
    struct PixelStats: Equatable {
        var brightness: Float  // 0-255
        var saturation: Float  // 0-1
        var warmBias: Float    // 0-1，正值偏暖
        var greenBias: Float   // 0-1，正值偏绿
        var contrast: Float    // 0-255，明暗跨度
    }

    /// 单次遍历像素计算亮度/饱和度/色温/对比度（RGBA8 入参；Android 为 ARGB IntArray，语义等价）。
    /// 纯函数供单测复算。
    static func computePixelStats(_ rgba: [UInt8]) -> PixelStats {
        let pixelCount = rgba.count / 4
        if pixelCount == 0 {
            return PixelStats(brightness: 0, saturation: 0, warmBias: 0, greenBias: 0, contrast: 0)
        }

        var sumBrightness: Float = 0
        var sumSaturation: Float = 0
        var sumWarm: Float = 0
        var sumGreen: Float = 0
        var minBrightness: Float = 255
        var maxBrightness: Float = 0

        for i in 0..<pixelCount {
            let base = i * 4
            let r = Float(rgba[base]) / 255
            let g = Float(rgba[base + 1]) / 255
            let b = Float(rgba[base + 2]) / 255

            let luminance = 0.299 * r + 0.587 * g + 0.114 * b
            let maxChannel = max(r, max(g, b))
            let minChannel = min(r, min(g, b))
            let saturation = maxChannel <= 0 ? 0 : (maxChannel - minChannel) / maxChannel

            sumBrightness += luminance * 255
            sumSaturation += saturation
            sumWarm += (r - b)
            sumGreen += (g - (r + b) / 2)

            let luma255 = luminance * 255
            if luma255 < minBrightness { minBrightness = luma255 }
            if luma255 > maxBrightness { maxBrightness = luma255 }
        }

        let count = Float(pixelCount)
        return PixelStats(
            brightness: sumBrightness / count,
            saturation: sumSaturation / count,
            warmBias: sumWarm / count,
            greenBias: sumGreen / count,
            contrast: maxBrightness - minBrightness
        )
    }

    /// 解码缩略图（maxDim ≤ maxThumbnailDim）；失败返回 nil。
    private func decodeThumbnail(imageFile: URL) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(imageFile as CFURL, nil) else {
            NSLog("%@ decodeThumbnail: CGImageSourceCreateWithURL null for %@",
                  HeuristicSceneAnalyzer.tag, imageFile.path)
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceThumbnailMaxPixelSize: HeuristicSceneAnalyzer.maxThumbnailDim,
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else {
            NSLog("%@ decodeThumbnail: thumbnail decode null for %@",
                  HeuristicSceneAnalyzer.tag, imageFile.path)
            return nil
        }
        return UIImage(cgImage: cg)
    }

    private func extractPixels(_ image: UIImage) -> [UInt8]? {
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
}

// MARK: - OptimizePresetRepository（本地预设仓库）
//
// 移植自 androidApp `domain/agent/capability/optimize/preset/AssetPresetRepository.kt`。
// 数据源：Android assets/presets/optimize_presets.json → iOS Bundle resource
// Resources/Gacha/optimize_presets.json（原样复制）。
// 语义偏差：Android 为 PresetRepository 接口 + Moshi 解码；iOS 单实现类 + Codable
// （无第二实现需求，不引入接口）；重复 scene 键后者覆盖（对齐 Kotlin associateBy）。
final class OptimizePresetRepository {

    private static let tag = "[PoLang:AiOptimizeService]"

    private let presets: [OptimizeScene: OptimizePreset]

    init(bundle: Bundle = Bundle.main) {
        self.presets = OptimizePresetRepository.loadPresets(bundle: bundle)
    }

    /// 取场景预设；缺场景回 GENERAL，全空回代码级默认（对齐 Android getPreset 兜底链）。
    func getPreset(_ scene: OptimizeScene) -> OptimizePreset {
        if let preset = presets[scene] { return preset }
        if let general = presets[.general] { return general }
        return OptimizePreset(scene: OptimizeScene.general.rawValue)
    }

    func getAllPresets() -> [OptimizeScene: OptimizePreset] {
        presets
    }

    private static func loadPresets(bundle: Bundle) -> [OptimizeScene: OptimizePreset] {
        // 资源查找：优先指定 bundle 的 Gacha/ 子目录（folder reference），退化为 bundle 根
        // （group 加入，xcodegen 收尾时统一挂载），再退化到任意 bundle（对齐 ModelCatalog 惯例）。
        let url = bundle.url(forResource: "optimize_presets", withExtension: "json", subdirectory: "Gacha")
            ?? bundle.url(forResource: "optimize_presets", withExtension: "json")
            ?? Bundle.allBundles.compactMap { candidate in
                candidate.url(forResource: "optimize_presets", withExtension: "json", subdirectory: "Gacha")
                    ?? candidate.url(forResource: "optimize_presets", withExtension: "json")
            }.first

        guard let url = url else {
            NSLog("%@ optimize_presets.json not found in bundle", OptimizePresetRepository.tag)
            return [:]
        }
        do {
            let data = try Data(contentsOf: url)
            let list = try JSONDecoder().decode([OptimizePreset].self, from: data)
            var map: [OptimizeScene: OptimizePreset] = [:]
            for preset in list {
                let scene = OptimizeScene.allCases.first { scene in
                    scene.rawValue.caseInsensitiveCompare(preset.scene) == .orderedSame
                } ?? .general
                map[scene] = preset
            }
            return map
        } catch {
            NSLog("%@ Failed to load presets: %@ (%@)",
                  OptimizePresetRepository.tag, url.path, "\(error)")
            return [:]
        }
    }
}

// MARK: - AiOptimizeService（AI 一键优化用例）
//
// 移植自 androidApp `domain/usecase/AiOptimizeUseCase.kt` 的 `optimizeWithGacha` 分支
// （AiOptimizeUseCase.kt :120-192 语义）。语义偏差：Android 另有固定预设 `optimize()`
// （批量优化入口）——iOS 暂无批量优化入口未移植，其配方语义已在 Unavailable 兜底内联实现。
//
// 降级链（功能永不阻塞）：
// - 引擎返回 Unavailable → 退回固定预设（与 Android optimize() 一致）
// - KeepOriginal → editRecipe 为 nil，调用方保持原图
//
// 自动选优与 KeepOriginal 均落库反馈（source=auto）；用户手选由 UI 层另行落库。
// 全链路 100% 端侧（[PRIVACY] 红线）。
final class AiOptimizeService {

    private static let tag = "[PoLang:AiOptimizeService]"

    /// 抽卡优化结果（对齐 Android AiOptimizeUseCase.GachaOutcome；
    /// explanation → explanationKey：返回 i18n key 而非硬编码文案，[I18N] 红线）。
    struct GachaOutcome {
        /// 抽卡结果（selected / keepOriginal / unavailable）
        let result: GachaResult
        /// 识别场景
        let scene: OptimizeScene
        /// Selected 时为最优卡配方；Unavailable 时为固定预设兜底配方；
        /// KeepOriginal 时为 nil（调用方保持原图）
        let editRecipe: EditRecipe?
        /// 场景说明文案 i18n key（editor.yaml §16，经 String(localized:) 解析）
        let explanationKey: String
        /// 本次已出现的参数指纹（含传入的 exclude），「换一组」时回传去重
        let usedFingerprints: Set<String>
        /// 处理耗时（毫秒）
        let processingTimeMs: Int64
    }

    static let shared = AiOptimizeService()

    private let presetRepository: OptimizePresetRepository
    private let sceneAnalyzer: HeuristicSceneAnalyzer
    private let gachaEngine: OptimizeGachaEngine
    private let feedbackLogger: OptimizeFeedbackLogger

    init(presetRepository: OptimizePresetRepository = OptimizePresetRepository(),
         sceneAnalyzer: HeuristicSceneAnalyzer = HeuristicSceneAnalyzer(),
         gachaEngine: OptimizeGachaEngine = OptimizeGachaEngine(),
         feedbackLogger: OptimizeFeedbackLogger = OptimizeFeedbackLogger()) {
        self.presetRepository = presetRepository
        self.sceneAnalyzer = sceneAnalyzer
        self.gachaEngine = gachaEngine
        self.feedbackLogger = feedbackLogger
    }

    /// 执行抽卡闭环优化（best-of-N + NIMA 评分守卫）。
    ///
    /// 流程：场景识别 → base preset → OptimizeGachaEngine 抽卡选优。
    ///
    /// - Parameters:
    ///   - imageFile: 原图本地文件 URL
    ///   - savedTo: iOS 增补——候选缩略图落盘目录（chat 候选卡条用，C-G4
    ///     Documents/chat_edit_cache；目录不存在自动创建）；编辑器路径传 nil 走内存态
    ///   - baseRecipe: 基础 Recipe（保留裁剪等既有参数）
    ///   - exclude: 「换一组」时需排除的参数指纹集合
    ///   - imageKey: 反馈落库的稳定图片标识（PHAsset localIdentifier / 原图原始路径）。
    ///     临时导出文件路径含随机 UUID，直接哈希会打破按图聚合——调用方必传稳定标识；
    ///     nil 兜底用 imageFile.path（Android 语义：稳定 content URI）
    func optimizeWithGacha(imageFile: URL,
                           savedTo: URL? = nil,
                           baseRecipe: EditRecipe? = nil,
                           exclude: Set<String> = [],
                           imageKey: String? = nil) async -> GachaOutcome {
        let startTime = CFAbsoluteTimeGetCurrent()
        let sourceUri = imageFile.path
        let feedbackUri = imageKey ?? sourceUri
        let scene = await sceneAnalyzer.analyze(imageFile: imageFile)
        let preset = presetRepository.getPreset(scene)
        let base = baseRecipe ?? EditRecipe(sourceUri: sourceUri)

        var result = await gachaEngine.run(imageFile: imageFile,
                                           scene: scene,
                                           basePreset: preset,
                                           exclude: exclude)
        if let savedTo = savedTo {
            result = Self.persistThumbnails(result, to: savedTo)
        }

        let recipe: EditRecipe?
        let allCandidates: [ScoredCandidate]
        switch result {
        case .selected(let best, _, _):
            recipe = OptimizeRecipeMapper.toEditRecipe(preset: best.candidate.preset,
                                                       sourceUri: sourceUri,
                                                       baseRecipe: base)
            allCandidates = Self.allCandidates(of: result)
        case .keepOriginal:
            recipe = nil
            allCandidates = Self.allCandidates(of: result)
        case .unavailable:
            // 与 Android 固定预设路径一致（AiOptimizeUseCase.kt :156-157 注释：两处实现点，改动需同步）
            recipe = OptimizeRecipeMapper.toEditRecipe(preset: preset,
                                                       sourceUri: sourceUri,
                                                       baseRecipe: base)
            allCandidates = []
        }

        let usedFingerprints = exclude.union(
            allCandidates.map { candidate in
                CandidateSampler.fingerprint(candidate.candidate.preset)
            }
        )

        switch result {
        case .selected(let best, _, _):
            feedbackLogger.log(imageUri: feedbackUri,
                               scene: scene,
                               all: allCandidates,
                               selectedIndex: best.candidate.index,
                               source: OptimizeFeedbackLogger.sourceAuto)
        case .keepOriginal:
            feedbackLogger.log(imageUri: feedbackUri,
                               scene: scene,
                               all: allCandidates,
                               selectedIndex: -1,
                               source: OptimizeFeedbackLogger.sourceAuto)
        case .unavailable:
            break
        }

        let elapsedMs = Int64((CFAbsoluteTimeGetCurrent() - startTime) * 1000)
        let resultName: String
        switch result {
        case .selected: resultName = "Selected"
        case .keepOriginal: resultName = "KeepOriginal"
        case .unavailable: resultName = "Unavailable"
        }
        NSLog("%@ optimizeWithGacha: scene=%@, result=%@, %lldms",
              AiOptimizeService.tag, scene.rawValue, resultName, elapsedMs)

        return GachaOutcome(result: result,
                            scene: scene,
                            editRecipe: recipe,
                            explanationKey: OptimizeRecipeMapper.buildExplanation(scene),
                            usedFingerprints: usedFingerprints,
                            processingTimeMs: elapsedMs)
    }

    // MARK: - 内部工具

    private static func allCandidates(of result: GachaResult) -> [ScoredCandidate] {
        switch result {
        case .selected(_, let all, _): return all
        case .keepOriginal(let all, _): return all
        case .unavailable: return []
        }
    }

    /// 把各候选 512px 缩略图持久化为 JPEG（<dir>/<uuid>.jpg，quality 0.85），
    /// 回填 ScoredCandidate.thumbPath（对齐 C-G4 候选缩略图落盘契约；失败跳过该卡仅日志）。
    private static func persistThumbnails(_ result: GachaResult, to directory: URL) -> GachaResult {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        func persist(_ cards: [ScoredCandidate]) -> [ScoredCandidate] {
            cards.map { card -> ScoredCandidate in
                var out = card
                if let thumbnail = card.thumbnail,
                   let data = thumbnail.jpegData(compressionQuality: 0.85) {
                    let url = directory.appendingPathComponent(UUID().uuidString + ".jpg")
                    do {
                        try data.write(to: url)
                        out.thumbPath = url.path
                    } catch {
                        NSLog("%@ thumbnail persist failed: %@ (%@)",
                              AiOptimizeService.tag, url.path, "\(error)")
                    }
                }
                return out
            }
        }

        switch result {
        case .selected(let best, let all, let originalScore):
            let newAll = persist(all)
            let newBest = newAll.first { card in
                card.candidate.index == best.candidate.index
            } ?? best
            return .selected(best: newBest, all: newAll, originalScore: originalScore)
        case .keepOriginal(let all, let originalScore):
            return .keepOriginal(all: persist(all), originalScore: originalScore)
        case .unavailable:
            return .unavailable
        }
    }
}
