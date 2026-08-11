import Foundation
import Accelerate

// MARK: - SemanticSearchEngine（双端契约 SSOT: contracts.md §5.1-§5.5）
//
// MobileCLIP 语义搜索引擎（文本→图像），逐字对齐 Android
// `SemanticSearchEngine.kt:120-249, 254-268, 270-368, 394-428`。
//
// searchByText 流程（契约 §5.1 照抄）：
// 1. 引擎未就绪则初始化（text_model.onnx ORT session + tokenizer），失败 → 返回空
// 2. ChineseQueryTranslator.expandForClip(query) 得候选英文查询列表（空 → 返回空）
// 3. 每个候选经 encodeTextQuery 编码为 512 维 text embedding（全部失败 → 返回空）
// 4. 取候选媒体（limitToIds 候选集内 / §5.4 filter 规则 / 全量 embedding IDs）
// 5. 每张候选图：SemanticEmbeddingCodec 解码 semanticEmbedding（§5.5）；norm < 1e-6 或
//    NaN 跳过；与所有 text embedding 算余弦相似度，**取最大值**作为该图分数
// 6. 按分数降序 → 过滤 score >= MIN_SIMILARITY = 0.22 → 取 topK（默认 50）
//
// encodeTextQuery 的 prompt 包装（契约 §5.2，关键保真点）：
//   concept = query.trim().trimEnd('.', ' ').lowercase().removePrefix("a "/"an "/"the ")
//   prompted = concept.isEmpty ? query : "a photo of a \(concept)"
//
// ⚠️ 模型获取（C1 调查结论）：text_model.onnx + tokenizer.json 随 `mobileclip-onnx`
//    模型包经 iOS 模型下载中心（ModelDownloadManager，ModelScope budaoshou/MobileCLIP-ONNX，
//    与 Android 同一仓库同一 fp32 文件）下载到 Documents/llm_models/mobileclip-onnx/。
//    模型未下载/加载失败 → 引擎置 unavailable，searchByText 返回 []（不崩、静默降级）。

// 集成说明：本类遵守 `SemanticSearching` 协议（MediaSearchEngine.swift，任务 B 定义的注入点）。
// ⚠️ §5.4 filter 候选集交集在双端各有一份实现：MediaSearchEngine（调用方，实际接线走的
// 是 limitToIds 路径）与本类 `searchByText(_:filter:topK:)`（对齐 Android getCandidates
// 结构，供直接消费方使用）——两处规则一致，若后续修改需同步。

final class SemanticSearchEngine: SemanticSearching {

    // MARK: - 常量（契约 §5.1 照抄）

    /// 最小相似度阈值（MIN_SIMILARITY = 0.22f，SemanticSearchEngine.kt）
    static let minSimilarity: Float = 0.22
    /// 默认 topK（Android searchByText 默认 topK = 50）
    static let defaultTopK: Int = 50
    /// embedding 维度（EMBEDDING_DIM = 512）
    static let embeddingDim: Int = 512
    /// 图像 embedding 最小 L2 norm（低于视为无效，§5.1 步骤 5）
    static let minEmbeddingNorm: Float = 1e-6

    /// 语义搜索结果项
    typealias Scored = (mediaId: Int64, score: Float)

    // MARK: - 依赖

    private let db: TagDatabase
    private let translator: ChineseQueryTranslator
    /// mobileclip-onnx 模型目录提供者（默认同 Pass1Pipeline：模型下载中心目录）
    private let modelDirProvider: () -> URL

    /// 延迟加载的推理件（首次搜索时初始化；首次加载是秒级，故整个搜索在后台执行）
    private var tokenizer: MobileClipTokenizer?
    private var encoder: MobileClipTextEncoder?
    /// 初始化只尝试一次（失败不重试，对齐 Android isReady/initialize 一次性语义）
    private var initializeAttempted = false
    /// 引擎是否可用（text model + tokenizer 均加载成功）
    private(set) var isAvailable = false
    /// initialize 临界区锁（并发搜索时只加载一次）
    private let initLock = NSLock()

    // MARK: - 初始化

    /// 生产构造：模型加载失败 → 内部置 unavailable，searchByText 返回 []。
    /// - Parameters:
    ///   - db: 标签数据库（候选集与 semanticEmbedding 读取）
    ///   - translator: 中文查询翻译器（默认自建，加载 bundle 词表资源）
    ///   - modelDirProvider: mobileclip-onnx 模型目录（默认模型下载中心目录，测试可注入临时目录验证降级）
    init(db: TagDatabase,
         translator: ChineseQueryTranslator = ChineseQueryTranslator(),
         modelDirProvider: @escaping () -> URL = {
             // 与 ModelDownloadManager.modelsDir 同路径（Documents/llm_models），
             // 直接构造以避开其 @MainActor 隔离（引擎在非隔离上下文初始化）。
             FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                 .appendingPathComponent("llm_models")
                 .appendingPathComponent("mobileclip-onnx")
         }) {
        self.db = db
        self.translator = translator
        self.modelDirProvider = modelDirProvider
    }

    // MARK: - 模型加载（契约 §5.1 步骤 1）

    /// 初始化（一次性；失败置 unavailable）。返回是否就绪。
    private func ensureInitialized() -> Bool {
        initLock.lock()
        defer { initLock.unlock() }
        if initializeAttempted { return isAvailable }
        initializeAttempted = true

        let modelDir = modelDirProvider()
        let tok = MobileClipTokenizer(modelDir: modelDir)
        guard tok.load() else {
            #if DEBUG
            print("[SemanticSearchEngine] tokenizer load failed (mobileclip-onnx 未下载?) → unavailable")
            #endif
            isAvailable = false
            return false
        }
        let enc = MobileClipTextEncoder()
        guard enc.load(modelPath: modelDir.appendingPathComponent("text_model.onnx").path) else {
            #if DEBUG
            print("[SemanticSearchEngine] text_model.onnx load failed → unavailable")
            #endif
            isAvailable = false
            return false
        }
        tokenizer = tok
        encoder = enc
        isAvailable = true
        return true
    }

    // MARK: - §5.1 searchByText（limitToIds 候选集内检索）

    /// 文本语义搜索（契约 §5.1 全流程；limitToIds 非空时在候选集内检索）。
    /// - Parameters:
    ///   - query: 用户自然语言查询（中文经 ChineseQueryTranslator 扩展为英文候选）
    ///   - limitToIds: 非 nil 时仅在这些 mediaId 内检索（refine in-set 语义）
    ///   - topK: 返回结果上限（默认 50）
    /// - Returns: 按相似度降序的 (mediaId, score)，score >= 0.22；引擎不可用/无候选 → []
    func searchByText(_ query: String,
                      limitToIds: Set<Int64>? = nil,
                      topK: Int = SemanticSearchEngine.defaultTopK) async -> [Scored] {
        await Task.detached(priority: .userInitiated) {
            self.searchByTextSync(query, limitToIds: limitToIds, topK: topK)
        }.value
    }

    /// §5.4 filter 候选集规则版（时间/人脸/OCR/地点交集；filter.keywords 故意忽略）。
    func searchByText(_ query: String,
                      filter: SearchFilter?,
                      topK: Int = SemanticSearchEngine.defaultTopK) async -> [Scored] {
        await Task.detached(priority: .userInitiated) {
            guard let filter else {
                return self.searchByTextSync(query, limitToIds: nil, topK: topK)
            }
            let ids = self.filteredCandidateIds(filter)
            guard !ids.isEmpty else { return [] }
            return self.searchByTextSync(query, limitToIds: Set(ids), topK: topK)
        }.value
    }

    /// searchByText 同步主体（在 Task.detached 内执行；首次语义搜索加载模型是秒级）。
    private func searchByTextSync(_ query: String, limitToIds: Set<Int64>?, topK: Int) -> [Scored] {
        // 1. 引擎未就绪则初始化，失败 → 空
        guard ensureInitialized(), let tokenizer, let encoder else { return [] }
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }

        // 2. 中文查询翻译 + 同义扩展
        let queryCandidates = translator.expandForClip(query)
        scanDebugLog("SearchDiag expandForClip('\(query)') = \(queryCandidates)") // TEMP
        guard !queryCandidates.isEmpty else { return [] }

        // 3. 每个候选编码为 text embedding（全部失败 → 空）
        let textEmbeddings: [[Float]] = queryCandidates.compactMap {
            Self.encodeTextQuery($0, tokenizer: tokenizer, encoder: encoder)
        }
        guard !textEmbeddings.isEmpty else { return [] }

        // 4. 候选集（limitToIds 内 ∩ 有 embedding 的媒体）
        let candidateEmbeddings = loadCandidateEmbeddings(limitToIds: limitToIds)
        scanDebugLog("SearchDiag candidateEmbeddings=\(candidateEmbeddings.count) (limitToIds=\(limitToIds?.count ?? -1))") // TEMP
        guard !candidateEmbeddings.isEmpty else { return [] }

        // 5-6. 余弦取最大值 → 降序 → 阈值 → topK
        return Self.rankCandidates(textEmbeddings: textEmbeddings,
                                   candidates: candidateEmbeddings,
                                   topK: topK)
    }

    // MARK: - §5.2 encodeTextQuery 的 prompt 包装（关键保真点）

    /// 查询文本 → 512 维 text embedding。
    /// CLIP 训练分布是完整 caption，裸词相似度系统性偏低，包装为 "a photo of a X"。
    static func encodeTextQuery(_ query: String,
                                tokenizer: MobileClipTokenizer,
                                encoder: MobileClipTextEncoder) -> [Float]? {
        let prompted = promptWrap(query)
        guard let tokenIds = tokenizer.encode(prompted) else { return nil }
        return encoder.encodeText(tokenIds)
    }

    /// prompt 包装纯逻辑（契约 §5.2 照抄，可单测）：
    /// concept = trim → trimEnd('.', ' ') → lowercase → 去 "a "/"an "/"the " 前缀；
    /// concept 空 → 原 query，否则 "a photo of a \(concept)"。
    static func promptWrap(_ query: String) -> String {
        var concept = query.trimmingCharacters(in: .whitespaces)
        while concept.hasSuffix(".") || concept.hasSuffix(" ") {
            concept = String(concept.dropLast())
        }
        concept = concept.lowercased()
        for prefix in ["a ", "an ", "the "] where concept.hasPrefix(prefix) {
            concept = String(concept.dropFirst(prefix.count))
            break // Kotlin removePrefix 链各执行一次；实际只有一个前缀能命中
        }
        return concept.isEmpty ? query : "a photo of a \(concept)"
    }

    // MARK: - §5.4 候选集过滤规则

    /// filter != null 的候选 ID 集（契约 §5.4 照抄）：
    /// 基础集（timeRange 非空 → getMediaIdsByTimeRange，否则全量 embedding IDs）
    /// → ∩ hasFaces → ∩ ocrKeywords 各词命中并集 → ∩ locationKeywords 各词命中并集；
    /// 任一步交集为空 → 空。filter.keywords 故意忽略。
    private func filteredCandidateIds(_ filter: SearchFilter) -> [Int64] {
        // 1. 基础集
        var candidateIds: Set<Int64>
        if let timeRange = filter.timeRange {
            candidateIds = Set(db.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs))
        } else {
            candidateIds = Set(db.getMediaWithSemanticEmbeddingIds())
        }

        // 2. hasFaces == true → ∩ getHasFaceIds
        if filter.hasFaces == true {
            candidateIds.formIntersection(db.getHasFaceIds())
            if candidateIds.isEmpty { return [] }
        }

        // 3. ocrKeywords 每词 → searchOcrInIds 命中并集，再 ∩
        for keyword in filter.ocrKeywords {
            let hits = Set(db.searchOcrInIds(Array(candidateIds), keyword: keyword).map { $0.id })
            candidateIds.formIntersection(hits)
            if candidateIds.isEmpty { return [] }
        }

        // 4. locationKeywords 每词 → getMediaIdsByLocationKeyword 命中并集，再 ∩
        for keyword in filter.locationKeywords {
            let hits = Set(db.getMediaIdsByLocationKeyword(keyword))
            candidateIds.formIntersection(hits)
            if candidateIds.isEmpty { return [] }
        }

        return Array(candidateIds)
    }

    // MARK: - 候选 embedding 加载（§5.1 步骤 4-5 + §5.5 解码）

    /// 候选集 → [(mediaId, embedding)]：
    /// getMediaByIds 后过滤 semanticEmbedding 非空（§5.4 步骤 6 同款语义）→ codec 解码
    /// → norm < 1e-6 或含 NaN 跳过（§5.1 步骤 5）。
    private func loadCandidateEmbeddings(limitToIds: Set<Int64>?) -> [(mediaId: Int64, embedding: [Float])] {
        let ids: [Int64]
        if let limitToIds {
            ids = Array(limitToIds)
        } else {
            ids = db.getMediaWithSemanticEmbeddingIds()
        }
        guard !ids.isEmpty else { return [] }

        let rows = db.getMediaByIds(ids)
        var result: [(mediaId: Int64, embedding: [Float])] = []
        result.reserveCapacity(rows.count)
        for row in rows {
            guard let embedding = SemanticEmbeddingCodec.decode(row.semanticEmbedding) else { continue }
            // norm < 1e-6 或 NaN 跳过（契约 §5.1 步骤 5）
            var normSq: Float = 0
            vDSP_svesq(embedding, 1, &normSq, vDSP_Length(embedding.count))
            if normSq.isNaN || normSq < Self.minEmbeddingNorm * Self.minEmbeddingNorm { continue }
            result.append((mediaId: row.id, embedding: embedding))
        }
        return result
    }

    // MARK: - §5.1 步骤 5-6 余弦打分与排序（纯逻辑，可用内存桩单测）

    /// 打分排序纯逻辑（契约 §5.1 步骤 5-6 照抄）：
    /// 每张候选图与所有 text embedding 算余弦取最大值（NaN 丢弃）→ 降序 →
    /// 过滤 score >= MIN_SIMILARITY(0.22) → 取 topK。
    /// - Parameters:
    ///   - textEmbeddings: 查询候选的 text embedding 列表
    ///   - candidates: 候选图 (mediaId, embedding)（调用方已完成 norm/NaN 校验）
    ///   - topK: 返回上限
    static func rankCandidates(textEmbeddings: [[Float]],
                               candidates: [(mediaId: Int64, embedding: [Float])],
                               topK: Int) -> [Scored] {
        var scored: [Scored] = []
        scored.reserveCapacity(candidates.count)

        for candidate in candidates {
            var maxSimilarity = -Float.greatestFiniteMagnitude
            for textEmbedding in textEmbeddings {
                let sim = cosineSimilarity(textEmbedding, candidate.embedding)
                if sim > maxSimilarity { maxSimilarity = sim }
            }
            if maxSimilarity.isNaN { continue } // NaN 相似度丢弃
            scored.append((mediaId: candidate.mediaId, score: maxSimilarity))
        }

        return scored
            .filter { $0.score >= minSimilarity }
            .sorted { $0.score > $1.score }
            .prefix(topK)
            .map { $0 }
    }

    /// 余弦相似度（契约 §5.3：标准 dot/(|a||b|)，零范数返回 0；Accelerate vDSP 实现）。
    static func cosineSimilarity(_ a: [Float], _ b: [Float]) -> Float {
        guard a.count == b.count, !a.isEmpty else { return 0 }
        var dot: Float = 0
        var normA: Float = 0
        var normB: Float = 0
        vDSP_dotpr(a, 1, b, 1, &dot, vDSP_Length(a.count))
        vDSP_svesq(a, 1, &normA, vDSP_Length(a.count))
        vDSP_svesq(b, 1, &normB, vDSP_Length(b.count))
        guard normA > 0, normB > 0 else { return 0 }
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
