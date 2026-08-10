import Foundation
import CoreGraphics

// 图片编辑配方数据模型（iOS 侧镜像）。
// contracts.md §B / editor.yaml §13。承载「Android 独占、本轮不经 SharedKit 消费」的纯 Swift 类型。
//
// 关键决策（Stage 3）：
// - FilterType 复用既有 `Features/Camera/Beauty/FilterColorMatrix.swift` 的本地枚举（同模块，含 colorMatrix），
//   遵循代码库「beauty/api 枚举本地重声明」先例，不新增首条跨边界 SharedKit 导入。
// - BeautySettings 本轮本地最小结构（BEAUTY 渲染 DEFER，仅存档）。
// - i18n 键沿用代码库英文文本键约定（如 "Crop"），view 侧 Text(LocalizedStringKey(...))。

// MARK: - AspectRatio（裁剪比例）

enum AspectRatio: String, Codable, CaseIterable, Equatable, Hashable {
    case free, original, square, ratio4_3, ratio3_4, ratio16_9, ratio9_16

    /// nil = FREE（不裁剪）；-1 = 用源图比例；其余为宽/高正值。
    var ratio: Float? {
        switch self {
        case .free: return nil
        case .original: return -1
        case .square: return 1.0
        case .ratio4_3: return 4.0 / 3.0
        case .ratio3_4: return 3.0 / 4.0
        case .ratio16_9: return 16.0 / 9.0
        case .ratio9_16: return 9.0 / 16.0
        }
    }

    /// xcstrings 英文文本键（editor.yaml §16）。
    var labelKey: String {
        switch self {
        case .free: return "Free"
        case .original: return "Original"
        case .square: return "1:1"
        case .ratio4_3: return "4:3"
        case .ratio3_4: return "3:4"
        case .ratio16_9: return "16:9"
        case .ratio9_16: return "9:16"
        }
    }
}

// MARK: - CropRecipe

struct CropRecipe: Codable, Equatable, Hashable {
    var rotation: Int = 0            // 0/90/180/270
    var flippedH: Bool = false
    var flippedV: Bool = false
    var straightenAngle: Float = 0   // 本轮 UI 不暴露，保留字段
    var aspectRatio: AspectRatio = .free
}

// MARK: - AdjustmentRecipe + Param

struct AdjustmentRecipe: Codable, Equatable, Hashable {
    var brightness: Float = 0      // -100...100
    var exposure: Float = 0        // -100...100
    var contrast: Float = 50       // 0...200
    var saturation: Float = 100    // 0...200
    var temperature: Float = 5000  // 2000...8000
    var tint: Float = 0            // -100...100
    var vignette: Float = 0        // 0...100（模型保留，面板不暴露）

    enum Param: String, CaseIterable {
        case brightness, exposure, contrast, saturation, temperature, tint

        var labelKey: String {
            switch self {
            case .brightness: return "Brightness"
            case .exposure: return "Exposure"
            case .contrast: return "Contrast"
            case .saturation: return "Saturation"
            case .temperature: return "Temperature"
            case .tint: return "Tint"
            }
        }
        var range: ClosedRange<Float> {
            switch self {
            case .brightness, .exposure, .tint: return -100 ... 100
            case .contrast, .saturation: return 0 ... 200
            case .temperature: return 2000 ... 8000
            }
        }
        var resetValue: Float {
            switch self {
            case .brightness, .exposure, .tint: return 0
            case .contrast: return 50
            case .saturation: return 100
            case .temperature: return 5000
            }
        }
        func get(_ r: AdjustmentRecipe) -> Float {
            switch self {
            case .brightness: return r.brightness
            case .exposure: return r.exposure
            case .contrast: return r.contrast
            case .saturation: return r.saturation
            case .temperature: return r.temperature
            case .tint: return r.tint
            }
        }
        func set(_ r: inout AdjustmentRecipe, _ v: Float) {
            switch self {
            case .brightness: r.brightness = v
            case .exposure: r.exposure = v
            case .contrast: r.contrast = v
            case .saturation: r.saturation = v
            case .temperature: r.temperature = v
            case .tint: r.tint = v
            }
        }
    }
}

// MARK: - Markup（归一化坐标）

struct NormPoint: Codable, Equatable, Hashable {
    var x: Float   // 0...1
    var y: Float   // 0...1
}

enum MosaicMode: String, Codable, CaseIterable { case pixel, blur }

enum MarkupAction: Codable, Equatable, Identifiable {
    case doodle(id: String, points: [NormPoint], color: Int, strokeWidth: Float)
    case mosaic(id: String, points: [NormPoint], strokeWidth: Float, mode: MosaicMode = .pixel)
    case text(id: String, text: String, position: NormPoint, color: Int, size: Float)

    var id: String {
        switch self {
        case .doodle(let id, _, _, _),
             .mosaic(let id, _, _, _),
             .text(let id, _, _, _, _):
            return id
        }
    }
}

// MARK: - EditorTab

enum EditorTab: String, CaseIterable, Equatable, Hashable {
    case crop, adjust, beauty, filter, markup

    var labelKey: String {
        switch self {
        case .crop: return "Crop"
        case .adjust: return "Adjust"
        case .beauty: return "Beauty"
        case .filter: return "Filter"
        case .markup: return "Markup"
        }
    }
}

enum MarkupConstants {
    static let strokeMin: Float = 0.005
    static let strokeMax: Float = 0.06
    static let defaultStrokeWidth: Float = 0.015
    static let defaultTextSize: Float = 0.05
    /// ARGB Int（对齐 Android MARKUP_COLORS）。
    static let colors: [Int] = [
        0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00,
        0xFF34C759, 0xFF0A84FF, 0xFFFFFFFF, 0xFF000000,
    ]
}

// MARK: - BeautySettings（本轮本地最小结构；BEAUTY 渲染 DEFER）

struct BeautySettings: Codable, Equatable, Hashable {
    var enabled: Bool = true
    var smoothing: Float = 0
    var whitening: Float = 0
    var slimFace: Float = 0
    var bigEyes: Float = 0
}

// MARK: - CutoutRecipe（DEFER 占位；本轮 EditRecipe.cutout 恒 nil）

struct CutoutRecipe: Codable, Equatable, Hashable {
    var bgMode: BgMode = .transparent
    enum BgMode: String, Codable { case transparent, color, blur }
}

// MARK: - EditRecipe（配方根；colorFilter 复用 FilterColorMatrix.FilterType）
// 仅 Equatable：FilterType 非 Codable/Hashable；配方持久化本轮 DEFER，故不强求 Codable。

struct EditRecipe: Equatable {
    var sourceUri: String
    var crop: CropRecipe = .init()
    var adjustments: AdjustmentRecipe = .init()
    var beauty: BeautySettings = .init()
    var colorFilter: FilterType = .none
    var filterIntensity: Float = 1.0
    var markup: [MarkupAction] = []
    var cutout: CutoutRecipe? = nil   // 本轮恒 nil（DEFER）
    var version: Int = 2
    // 注：styleFilter 本轮 DEFER（5 风格滤镜需 Metal kernel），故不在此建模。
}

// MARK: - EditorTarget（MediaPagerView → 编辑页的路由载体）

struct EditorTarget: Identifiable {
    let id: String          // = localIdentifier
    init(localIdentifier: String) { self.id = localIdentifier }
}
