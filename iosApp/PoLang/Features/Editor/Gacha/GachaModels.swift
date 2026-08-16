import UIKit

// MARK: - Gacha 数据模型
//
// 移植自 androidApp `domain/agent/capability/optimize/gacha/GachaModels.kt`；
// `OptimizePreset` 系列镜像自 `domain/agent/capability/optimize/preset/OptimizePreset.kt`；
// `OptimizeScene` 镜像自 `domain/agent/capability/optimize/analyzer/OptimizeScene.kt`。
//
// 语义偏差（相对 Android）：
// - `OptimizeScene` 从 Kotlin enum 改为 String rawValue 枚举，rawValue 对齐 Android 枚举名
//   （SELFIE/PORTRAIT/...，presets JSON、落库 scene 字段、指纹场景无关路径共用）。
// - `ScoredCandidate.thumbnail` 由 Bitmap 改为 UIImage；增补 `thumbPath`
//   （chat 候选卡条缩略图落盘路径，C-G4；编辑器内存态路径下为 nil，Android 无此字段）。

// MARK: OptimizeScene（场景枚举）

/// AI 一键优化可识别的照片场景，每个场景对应一套本地预设配方。
enum OptimizeScene: String, CaseIterable, Equatable {
    case selfie = "SELFIE"
    case portrait = "PORTRAIT"
    case group = "GROUP"
    case food = "FOOD"
    case landscape = "LANDSCAPE"
    case lowLight = "LOW_LIGHT"
    case document = "DOCUMENT"
    case general = "GENERAL"
}

// MARK: - OptimizePreset（AI 一键优化预设配方）

/// 预设配方根。字段与取值单位对齐 Android `OptimizePreset.kt`。
struct OptimizePreset: Equatable, Codable {
    var scene: String
    var beauty: BeautyPreset
    var filter: FilterPreset
    var adjustment: AdjustmentPreset

    init(scene: String,
         beauty: BeautyPreset = BeautyPreset(),
         filter: FilterPreset = FilterPreset(),
         adjustment: AdjustmentPreset = AdjustmentPreset()) {
        self.scene = scene
        self.beauty = beauty
        self.filter = filter
        self.adjustment = adjustment
    }
}

/// 美颜预设参数。单位与 `BeautySettings` 一致（Android `BeautyPreset.kt`）。
struct BeautyPreset: Equatable, Codable {
    var enabled: Bool = true
    var smoothing: Float = 0      // 0...100
    var whitening: Float = 0      // 0...100
    var slimFace: Float = 0       // -50...50（Android 注释口径；beauty-api 渲染范围 0...100）
    var bigEyes: Float = 0        // 0...100
    var lipColor: Float = 0       // 0...100
    var blush: Float = 0          // 0...100

    init(enabled: Bool = true,
         smoothing: Float = 0,
         whitening: Float = 0,
         slimFace: Float = 0,
         bigEyes: Float = 0,
         lipColor: Float = 0,
         blush: Float = 0) {
        self.enabled = enabled
        self.smoothing = smoothing
        self.whitening = whitening
        self.slimFace = slimFace
        self.bigEyes = bigEyes
        self.lipColor = lipColor
        self.blush = blush
    }

    // JSON 缺字段时取默认值（对齐 Moshi + KotlinJsonAdapterFactory 的默认值语义；
    // Android assets JSON 中的 "eyebrow" 为 Kotlin data class 之外的多余键，同样忽略）。
    private enum CodingKeys: String, CodingKey {
        case enabled, smoothing, whitening, slimFace, bigEyes, lipColor, blush
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            enabled: try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true,
            smoothing: try c.decodeIfPresent(Float.self, forKey: .smoothing) ?? 0,
            whitening: try c.decodeIfPresent(Float.self, forKey: .whitening) ?? 0,
            slimFace: try c.decodeIfPresent(Float.self, forKey: .slimFace) ?? 0,
            bigEyes: try c.decodeIfPresent(Float.self, forKey: .bigEyes) ?? 0,
            lipColor: try c.decodeIfPresent(Float.self, forKey: .lipColor) ?? 0,
            blush: try c.decodeIfPresent(Float.self, forKey: .blush) ?? 0
        )
    }
}

/// 滤镜预设参数。colorFilter / styleFilter 用字符串名称，映射时经
/// `OptimizeRecipeMapper.resolveFilterType/resolveStyleFilter` 解析为编辑器枚举。
struct FilterPreset: Equatable, Codable {
    var colorFilter: String = "NONE"
    var styleFilter: String = "NONE"

    init(colorFilter: String = "NONE", styleFilter: String = "NONE") {
        self.colorFilter = colorFilter
        self.styleFilter = styleFilter
    }

    private enum CodingKeys: String, CodingKey {
        case colorFilter, styleFilter
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            colorFilter: try c.decodeIfPresent(String.self, forKey: .colorFilter) ?? "NONE",
            styleFilter: try c.decodeIfPresent(String.self, forKey: .styleFilter) ?? "NONE"
        )
    }
}

/// 调节预设参数。单位与 `AdjustmentRecipe` 一致（Android `AdjustmentPreset.kt`）。
struct AdjustmentPreset: Equatable, Codable {
    var brightness: Float = 0     // -100...100
    var exposure: Float = 0       // -100...100
    var contrast: Float = 50      // 0...200
    var saturation: Float = 100   // 0...200
    var temperature: Float = 5000 // 2000...8000
    var tint: Float = 0           // -100...100

    init(brightness: Float = 0,
         exposure: Float = 0,
         contrast: Float = 50,
         saturation: Float = 100,
         temperature: Float = 5000,
         tint: Float = 0) {
        self.brightness = brightness
        self.exposure = exposure
        self.contrast = contrast
        self.saturation = saturation
        self.temperature = temperature
        self.tint = tint
    }

    private enum CodingKeys: String, CodingKey {
        case brightness, exposure, contrast, saturation, temperature, tint
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            brightness: try c.decodeIfPresent(Float.self, forKey: .brightness) ?? 0,
            exposure: try c.decodeIfPresent(Float.self, forKey: .exposure) ?? 0,
            contrast: try c.decodeIfPresent(Float.self, forKey: .contrast) ?? 50,
            saturation: try c.decodeIfPresent(Float.self, forKey: .saturation) ?? 100,
            temperature: try c.decodeIfPresent(Float.self, forKey: .temperature) ?? 5000,
            tint: try c.decodeIfPresent(Float.self, forKey: .tint) ?? 0
        )
    }
}

// MARK: - 候选卡

/// 单张抽卡候选卡。
///
/// - index: 卡组内序号（0 为 base preset 锚点）
/// - direction: 扰动方向标签（"base" / "clarity" / "warm" / ...），UI 展示与落库用
/// - preset: 候选参数
struct OptimizeCandidate: Equatable {
    let index: Int
    let direction: String
    let preset: OptimizePreset
}

/// 评分后的候选卡。
///
/// - nimaScore: NIMA 美学分（1~10），nil = 未评分（护栏淘汰或推理失败）
/// - rejected: 是否被护栏/评分失败淘汰
/// - rejectReason: 淘汰原因（日志与落库用）
/// - thumbnail: 512px 渲染结果（「换一组」对比条展示用）
/// - thumbPath: iOS 增补——缩略图落盘路径（chat 候选卡条用，C-G4）；内存态为 nil
struct ScoredCandidate {
    let candidate: OptimizeCandidate
    let nimaScore: Float?
    let rejected: Bool
    var rejectReason: String? = nil
    var thumbnail: UIImage? = nil
    var thumbPath: String? = nil
}

// MARK: - 抽卡结果

/// 抽卡结果（对齐 Android sealed interface GachaResult）。
enum GachaResult {
    /// 最优候选过退化守卫，可应用。
    case selected(best: ScoredCandidate, all: [ScoredCandidate], originalScore: Float?)
    /// 全部候选未显著优于原图，保持原图。
    case keepOriginal(all: [ScoredCandidate], originalScore: Float?)
    /// 抽卡不可用（NIMA 未下载 / 解码失败 / 有效卡不足），调用方退回固定预设路径。
    case unavailable
}
