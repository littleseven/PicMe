import Foundation
import SharedKit

/// `IosAiOptimizeBridge` 的 Swift 实现：Kotlin `IosAiOptimizeCapability`（ai_optimize 命令）
/// → `AiOptimizeService` 固定预设路径（场景分析 → getPreset → toEditRecipe 映射）。
///
/// 本桥只承担 **capability observation** 路径：产出 explanation（已本地化）+ resultRecipe
/// JSON 给远程 LLM；抽卡（gacha）由 Swift UI 层收到 Success(AiOptimize) 后另行触发
/// （ChatOptimizeGachaController.draw），不经本桥——与 Android「ViewModel 层分流」一致。
///
/// SharedBridge 铁律（同 `ChartRendererBridge` / `RunScriptBridge`）：
/// - 方法绝不抛异常跨 K/N 边界（逃逸会 signal 6 / SIGABRT）；
/// - onResult / onError **恰好调用其一且仅一次**（失败走 onError + ai_optimize_failed 兜底文案；
///   回调参数只用 String——K/N 对 lambda 参数位的 Boolean 装箱为 SharedKitBoolean，规避之）。
///
/// [PRIVACY] 全链路端侧（场景分析/预设映射），无媒体上传。
@objc final class AiOptimizeBridge: NSObject, IosAiOptimizeBridge {

    static let shared = AiOptimizeBridge()

    private static let tag = "[PoLang:AiOptimizeBridge]"

    /// 预设仓库（bundle optimize_presets.json 只解析一次，实例级缓存）。
    private let presetRepository = OptimizePresetRepository()
    private let sceneAnalyzer = HeuristicSceneAnalyzer()

    func optimizeFixed(
        imageUri: String,
        onResult: @escaping (String, String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        // 场景分析含像素统计（解码 256px 缩略图）+ 可能的 PHAsset 原图导出，
        // 派后台 Task 执行，绝不阻塞调用线程（可能为 main）。
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else {
                onError(Self.failureText("bridge released"))
                return
            }
            let outcome = await self.runFixedOptimize(imageUri: imageUri)
            if outcome.ok {
                onResult(outcome.explanation, outcome.recipeJson)
            } else {
                onError(outcome.explanation)
            }
        }
    }

    // MARK: - 固定预设路径（端侧）

    private struct FixedOutcome {
        let ok: Bool
        let explanation: String
        let recipeJson: String
    }

    private func runFixedOptimize(imageUri: String) async -> FixedOutcome {
        // 目标图解析（file:// / 裸路径 / PHAsset id；与 gacha draw 共用解析器）
        guard let sourceFile = await ChatImageUriResolver.resolve(imageUri) else {
            NSLog("%@ optimizeFixed: image not resolvable: %@", Self.tag, imageUri)
            return FixedOutcome(ok: false,
                                explanation: Self.failureText("image not available"),
                                recipeJson: "")
        }

        let scene = await sceneAnalyzer.analyze(imageFile: sourceFile)
        let preset = presetRepository.getPreset(scene)
        let explanation = String(localized: String.LocalizationValue(
            OptimizeRecipeMapper.buildExplanation(scene)))
        let json = Self.buildResultRecipeJson(
            sourceUri: imageUri, scene: scene, explanation: explanation, preset: preset)
        NSLog("%@ optimizeFixed: scene=%@", Self.tag, scene.rawValue)
        return FixedOutcome(ok: true, explanation: explanation, recipeJson: json)
    }

    private static func failureText(_ reason: String) -> String {
        String(format: String(localized: "ai_optimize_failed"), reason)
    }

    /// resultRecipe JSON（对齐 Android OptimizeResultDto 结构：sourceUri/scene/explanation/recipe）。
    /// EditRecipe 非 Codable（FilterType/StyleFilter 枚举），手拼精简 JSON（键序稳定）。
    private static func buildResultRecipeJson(sourceUri: String,
                                              scene: OptimizeScene,
                                              explanation: String,
                                              preset: OptimizePreset) -> String {
        let obj: [String: Any] = [
            "sourceUri": sourceUri,
            "scene": scene.rawValue,
            "explanation": explanation,
            "recipe": [
                "beauty": [
                    "enabled": preset.beauty.enabled,
                    "smoothing": preset.beauty.smoothing,
                    "whitening": preset.beauty.whitening,
                    "slimFace": preset.beauty.slimFace,
                    "bigEyes": preset.beauty.bigEyes,
                    "lipColor": preset.beauty.lipColor,
                    "blush": preset.beauty.blush,
                ],
                "filter": [
                    "colorFilter": preset.filter.colorFilter,
                    "styleFilter": preset.filter.styleFilter,
                ],
                "adjustment": [
                    "brightness": preset.adjustment.brightness,
                    "exposure": preset.adjustment.exposure,
                    "contrast": preset.adjustment.contrast,
                    "saturation": preset.adjustment.saturation,
                    "temperature": preset.adjustment.temperature,
                    "tint": preset.adjustment.tint,
                ],
            ],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8) else {
            NSLog("%@ buildResultRecipeJson: serialization failed", Self.tag)
            return ""
        }
        return json
    }
}
