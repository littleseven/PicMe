import Foundation

/// 端侧 AI 模型目录（对齐 Android `llm_models.json` + `ModelConfig`）。
///
/// 16 个模型从 ModelScope CDN 下载。
/// **关键：一个模型可出现在多个分类 Tab 中**（如 must-have 模型同时出现在
/// must-have Tab 和其 tag 对应的 Tab），与 Android `groupByCategory` 语义一致。

// MARK: - Data Model

struct ModelEntry: Codable, Identifiable {
    let id: String
    let name: String
    let description: String
    let type: String?
    let size: Int64
    let sources: [String: String]
    let files: [String]
    let tags: [String]

    // MARK: - Tier

    private static let requiredIds: Set<String> = [
        "face-det-retina500m-mnn",
        "face-landmark-2d106-mnn",
        "face-embedding-glint360k-r100-onnx",
        "florence2_base",
        "mobileclip-onnx",
        "opus-mt-zh-en",
        "opus-mt-en-zh",
    ]

    private static let recommendedIds: Set<String> = [
        "sherpa-onnx-zipformer-zh-en",
        "sherpa-onnx-kws-zipformer-wenetspeech", // KWS 唤醒词（2026-08-19：语音模型全部归推荐）
        "modnet-onnx",
        "u2netp-onnx",
        "mediapipe-face-landmarker",
        "ediffiqa-face-quality-onnx",
        "nima-aesthetic-onnx",
    ]

    var isRequired: Bool { Self.requiredIds.contains(id) }
    var isRecommended: Bool { Self.recommendedIds.contains(id) }
    var isLightweight: Bool { size < 50 * 1024 * 1024 }

    // MARK: - Categories (一个模型可属多个分类)

    /// 该模型应该出现的所有分类 Tab（对齐 Android getCategories 逻辑）。
    var categories: [ModelCategory] {
        var result: [ModelCategory] = []
        if isRequired { result.append(.mustHave) }
        if isRecommended { result.append(.recommended) }
        for tag in tags {
            if let cat = ModelCategory(rawValue: tag), !result.contains(cat) {
                result.append(cat)
            }
        }
        return result
    }

    /// 首个 tag（用于 TagBadge 显示）
    var primaryTag: String { tags.first ?? "chat" }

    /// ModelScope 仓库路径
    var modelScopeRepo: String? { sources["ModelScope"] }

    /// 格式化大小（对齐 Android formatFileSize）
    var formattedSize: String {
        let bytes = size
        if bytes >= 1024 * 1024 * 1024 {
            return String(format: "%.2f GB", Double(bytes) / (1024 * 1024 * 1024))
        } else if bytes >= 1024 * 1024 {
            return String(format: "%.2f MB", Double(bytes) / (1024 * 1024))
        } else if bytes >= 1024 {
            return String(format: "%.2f KB", Double(bytes) / 1024)
        }
        return "\(bytes) B"
    }
}

// MARK: - Category

enum ModelCategory: String, CaseIterable, Hashable {
    case mustHave = "must-have"
    case recommended = "recommended"
    case photoTagging = "photo-tagging"
    case beautyCamera = "beauty-camera"
    case chat = "chat"

    /// Tab 显示名（对齐 Android tagTranslations）
    var displayName: String {
        switch self {
        case .mustHave: return String(localized: "Must Have")
        case .recommended: return String(localized: "Recommended")
        case .photoTagging: return String(localized: "Photo Tagging")
        case .beautyCamera: return String(localized: "Beauty Camera")
        case .chat: return String(localized: "Voice")  // Android 标签是「语音」非「聊天」
        }
    }

    /// Tab 图标 SF Symbol 名（对齐 Android getCategoryIcon）
    var iconSystemName: String {
        switch self {
        case .mustHave: return "star.fill"
        case .recommended: return "arrow.down.circle"
        case .chat: return "mic.fill"
        case .photoTagging: return "photo"
        case .beautyCamera: return "camera"
        }
    }
}

// MARK: - Tag Colors (对齐 Android getTagColor)

extension ModelEntry {
    /// tag → 显示名（对齐 Android tagTranslations DEFAULT_TAG_TRANSLATIONS）
    static func tagDisplayName(_ tag: String) -> String {
        switch tag.lowercased() {
        case "must-have": return String(localized: "Must Have")
        case "recommended": return String(localized: "Recommended")
        case "chat": return String(localized: "Voice")
        case "photo-tagging": return String(localized: "Photo Tagging")
        case "beauty-camera": return String(localized: "Beauty Camera")
        case "asr": return "ASR"
        case "speech": return String(localized: "Speech")
        case "face": return String(localized: "Face")
        case "detection": return String(localized: "Detection")
        case "landmark": return String(localized: "Landmark")
        case "embedding": return String(localized: "Embedding")
        case "vision": return String(localized: "Vision")
        case "vision-llm": return String(localized: "Vision LLM")
        case "clip": return "CLIP"
        case "semantic": return String(localized: "Semantic")
        case "translation": return String(localized: "Translation")
        case "onnx": return "ONNX"
        case "mnn": return "MNN"
        case "kws": return "KWS"
        case "wake-word": return String(localized: "Wake Word")
        case "keyword": return String(localized: "Keyword")
        case "always-on": return String(localized: "Always-On")
        case "matting": return String(localized: "Matting")
        case "editor": return String(localized: "Editor")
        case "mediapipe": return "MediaPipe"
        case "quality": return String(localized: "Quality")
        case "aesthetic": return String(localized: "Aesthetic")
        case "multilingual": return String(localized: "Multilingual")
        case "structured-output": return String(localized: "Structured Output")
        case "chinese": return String(localized: "Chinese")
        case "english": return String(localized: "English")
        case "nlp": return "NLP"
        case "tagging": return String(localized: "Tagging")
        case "arcface": return "ArcFace"
        case "glint360k": return "Glint360K"
        default: return tag
        }
    }

    /// tag → 颜色 hex（对齐 Android getTagColor）
    static func tagColorHex(_ tag: String) -> UInt32 {
        switch tag.lowercased() {
        case "must-have": return 0xE53935
        case "recommended": return 0xFF9800
        case "chat": return 0x2196F3
        case "photo-tagging": return 0x9C27B0
        case "beauty-camera": return 0xFF9800
        case "voice": return 0x03A9F4
        case "vision": return 0xFF9800
        case "face": return 0xFF9800
        default: return 0x9E9E9E
        }
    }
}

// MARK: - Catalog

struct ModelCatalog {
    static let shared = ModelCatalog()

    let models: [ModelEntry]

    init() {
        models = Self.loadModels()
    }

    private static func loadModels() -> [ModelEntry] {
        var lastError: Error?
        for bundle in Bundle.allBundles {
            guard let url = bundle.url(forResource: "llm_models", withExtension: "json") else { continue }
            do {
                let data = try Data(contentsOf: url)
                let decoded = try JSONDecoder().decode([ModelEntry].self, from: data)
                if !decoded.isEmpty { return decoded }
            } catch {
                lastError = error
            }
        }
        if let lastError { print("⚠️ ModelCatalog: failed to load llm_models.json: \(lastError)") }
        return []
    }

    /// 按分类分组——**一个模型可出现在多个 Tab**（对齐 Android groupByCategory）。
    /// 只返回有模型的 Tab（空 Tab 隐藏）。
    func groupedByCategory() -> [(ModelCategory, [ModelEntry])] {
        var groups: [ModelCategory: [ModelEntry]] = [:]
        for model in models {
            for cat in model.categories {
                groups[cat, default: []].append(model)
            }
        }
        return ModelCategory.allCases.compactMap { cat in
            groups[cat].map { (cat, $0) }
        }
    }

    func model(byId id: String) -> ModelEntry? {
        models.first { $0.id == id }
    }
}
