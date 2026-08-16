import Foundation

// MARK: - OptimizeRecipeMapper（preset → EditRecipe 映射）
//
// 移植自 androidApp `domain/agent/capability/optimize/recipe/OptimizeRecipeMapper.kt`。
//
// 语义：自 baseRecipe 起拷贝——crop/markup/filterIntensity 等非 AI 字段继承 base；
// beauty / colorFilter / styleFilter / adjustments 由 preset 覆盖。
//
// 语义偏差（相对 Android，contracts.md C-G5）：
// - buildExplanation 返回 **i18n key**（"ai_optimize_explain_*" 8 个，editor.yaml §16），
//   调用方经 `String(localized:)` 解析——Android 硬编码中文为已登记 i18n 违例技术债，
//   iOS 按 [I18N] 红线走 key 本地化，语义对齐、载体升级。
// - Android 另有反向映射 toOptimizePreset/toResultDto（供 AiOptimizeCapability 序列化
//   observation DTO）；iOS chat capability 归属其他任务，本文件未移植。
enum OptimizeRecipeMapper {

    /// 将 OptimizePreset 映射为 EditRecipe。
    ///
    /// - Parameters:
    ///   - preset: AI 优化预设
    ///   - sourceUri: 原图 URI（本地文件路径）
    ///   - baseRecipe: 基础 Recipe（保留裁剪等非 AI 参数）；nil 时自全新 EditRecipe 起
    /// - Returns: 可用于编辑器渲染和保存的 EditRecipe
    static func toEditRecipe(preset: OptimizePreset,
                             sourceUri: String,
                             baseRecipe: EditRecipe? = nil) -> EditRecipe {
        var recipe = baseRecipe ?? EditRecipe(sourceUri: sourceUri)
        recipe.sourceUri = sourceUri
        recipe.beauty = toBeautySettings(preset)
        recipe.colorFilter = resolveFilterType(preset.filter.colorFilter)
        recipe.styleFilter = resolveStyleFilter(preset.filter.styleFilter)
        recipe.adjustments = toAdjustmentRecipe(preset)
        return recipe
    }

    /// 场景说明文案（返回 i18n key，非最终文案）。
    /// key 全表见 specs/screens/editor.yaml §16；调用方 `String(localized:)` 解析。
    static func buildExplanation(_ scene: OptimizeScene) -> String {
        switch scene {
        case .selfie: return "ai_optimize_explain_selfie"
        case .portrait: return "ai_optimize_explain_portrait"
        case .group: return "ai_optimize_explain_group"
        case .food: return "ai_optimize_explain_food"
        case .landscape: return "ai_optimize_explain_landscape"
        case .lowLight: return "ai_optimize_explain_low_light"
        case .document: return "ai_optimize_explain_document"
        case .general: return "ai_optimize_explain_general"
        }
    }

    /// BeautyPreset → 编辑器 BeautySettings（iOS 本地镜像结构；渲染 DEFER 仅存档，对齐 Android 字段拷贝）。
    private static func toBeautySettings(_ preset: OptimizePreset) -> BeautySettings {
        let beauty = preset.beauty
        var out = BeautySettings()
        out.enabled = beauty.enabled
        out.smoothing = beauty.smoothing
        out.whitening = beauty.whitening
        out.slimFace = beauty.slimFace
        out.bigEyes = beauty.bigEyes
        out.lipColor = beauty.lipColor
        out.blush = beauty.blush
        return out
    }

    /// AdjustmentPreset → 编辑器 AdjustmentRecipe（整体替换语义对齐 Android toAdjustmentRecipe：
    /// vignette 不在 AI 预设维度内，全新构造取默认 0，不继承 base 值）。
    private static func toAdjustmentRecipe(_ preset: OptimizePreset) -> AdjustmentRecipe {
        let adjustment = preset.adjustment
        var out = AdjustmentRecipe()
        out.brightness = adjustment.brightness
        out.exposure = adjustment.exposure
        out.contrast = adjustment.contrast
        out.saturation = adjustment.saturation
        out.temperature = adjustment.temperature
        out.tint = adjustment.tint
        return out
    }

    // MARK: - 滤镜名解析（别名表逐字对齐 Android）

    /// 名称归一化：trim + 大写 + 空格/连字符转下划线（对齐 Android normalized 口径）。
    private static func normalize(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "-", with: "_")
    }

    /// 解析色彩滤镜名称。
    ///
    /// 显式别名表覆盖 Android FilterType 全部 9 个枚举名与中英别名，行为等价于
    /// Android（valueOf 兜底命中的枚举名均在表内；未知名 Android 回 FilterType.NONE，此处回 .none）。
    static func resolveFilterType(_ name: String) -> FilterType {
        let normalized = normalize(name)
        switch normalized {
        case "NONE":
            return .none
        case "LEICA_CLASSIC", "徕卡经典", "徕卡经典滤镜":
            return .leicaClassic
        case "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID", "徕卡鲜艳", "徕卡鲜艳滤镜":
            return .leicaVibrant
        case "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME", "徕卡黑白", "徕卡黑白滤镜":
            return .leicaBW
        case "FILM_GOLD", "胶片金", "胶片金滤镜":
            return .filmGold
        case "FILM_FUJI", "胶片富士", "富士", "胶片富士滤镜":
            return .filmFuji
        case "VINTAGE", "RETRO", "OLD", "复古", "怀旧":
            return .vintage
        case "COOL", "COLD", "冷色", "冷色调", "冷色滤镜", "冷调", "冷调滤镜", "冷滤镜":
            return .cool
        case "WARM", "暖色", "暖色调", "暖色滤镜", "暖调", "暖调滤镜", "暖滤镜":
            return .warm
        default:
            return .none
        }
    }

    /// 解析风格特效名称（别名表逐字对齐 Android StyleFilter）。
    static func resolveStyleFilter(_ name: String) -> StyleFilter {
        let normalized = normalize(name)
        switch normalized {
        case "NONE":
            return .none
        case "TOON", "CARTOON", "COMIC", "卡通":
            return .toon
        case "SKETCH", "素描":
            return .sketch
        case "POSTERIZE", "POSTER", "海报":
            return .posterize
        case "EMBOSS", "浮雕":
            return .emboss
        case "CROSSHATCH", "CROSS_HATCH", "交叉线":
            return .crosshatch
        default:
            return .none
        }
    }
}
