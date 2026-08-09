import Foundation

/// 端侧 AI 模型目录（对齐 Android `llm_models.json` + `ModelConfig`）。
///
/// 16 个模型从 ModelScope CDN 下载，分 5 类（must-have/recommended/photo-tagging/beauty-camera/chat）。
/// JSON 零修改从 `androidApp/src/main/res/raw/llm_models.json` 拷贝。

// MARK: - Data Model

struct ModelEntry: Codable, Identifiable {
    let id: String
    let name: String
    let description: String
    let type: String?         // Some entries omit "type" (e.g. ediffiqa/nima)
    let size: Int64           // bytes
    let sources: [String: String]  // {"ModelScope": "MNN/Qwen3-VL-2B-Instruct-MNN"}
    let files: [String]
    let tags: [String]

    // MARK: - Tier（对齐 Android REQUIRED_MODEL_IDS / RECOMMENDED_MODEL_IDS / CHAT_MODEL_IDS）

    private static let requiredIds: Set<String> = [
        "face-det-retina500m-mnn",
        "face-landmark-2d106-mnn",
        "face-embedding-glint360k-r100-mnn",
        "florence2_base",
        "mobileclip-onnx",
        "opus-mt-zh-en",
        "opus-mt-en-zh",
    ]

    private static let recommendedIds: Set<String> = [
        "sherpa-onnx-zipformer-zh-en",   // ASR
        "modnet-onnx",
        "u2netp-onnx",
        "mediapipe-face-landmarker",
        "ediffiqa-face-quality-onnx",
        "nima-aesthetic-onnx",
    ]

    var isRequired: Bool { Self.requiredIds.contains(id) }
    var isRecommended: Bool { Self.recommendedIds.contains(id) }
    var isLightweight: Bool { size < 50 * 1024 * 1024 }  // <50MB

    // MARK: - Category

    /// 按首个匹配 tag 分到 5 个分类 Tab 之一。
    var category: ModelCategory {
        for tag in tags {
            if let cat = ModelCategory(rawValue: tag) { return cat }
        }
        return .recommended
    }

    /// ModelScope 仓库路径（如 "MNN/Qwen3-VL-2B-Instruct-MNN"）
    var modelScopeRepo: String? { sources["ModelScope"] }

    /// 格式化大小
    var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
    }
}

enum ModelCategory: String, CaseIterable, Hashable {
    case mustHave = "must-have"
    case recommended = "recommended"
    case photoTagging = "photo-tagging"
    case beautyCamera = "beauty-camera"
    case chat = "chat"

    var displayName: String {
        switch self {
        case .mustHave: return String(localized: "Must Have")
        case .recommended: return String(localized: "Recommended")
        case .photoTagging: return String(localized: "Photo Tagging")
        case .beautyCamera: return String(localized: "Beauty Camera")
        case .chat: return String(localized: "Chat")
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
        // 搜索所有 bundle（含 PoLang.app + test runner）
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

    func groupByCategory() -> [(ModelCategory, [ModelEntry])] {
        var groups: [ModelCategory: [ModelEntry]] = [:]
        for model in models {
            groups[model.category, default: []].append(model)
        }
        // 按 ModelCategory.allCases 的固定顺序排列
        return ModelCategory.allCases.compactMap { cat in
            groups[cat].map { (cat, $0) }
        }
    }

    func model(byId id: String) -> ModelEntry? {
        models.first { $0.id == id }
    }
}
