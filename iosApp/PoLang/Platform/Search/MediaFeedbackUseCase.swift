import Foundation

// MARK: - 反馈加权（双端契约 SSOT: contracts.md §8 + §9.6 resolveTarget）
//
// 逐字对齐 Android `MediaFeedbackUseCase.kt:6-41`（LIKE_BONUS/DISLIKE_PENALTY=0.15、
// query_text 精确等值 R10）与 `ChatViewModel.kt:1636-1656, 1775-1788`（resolveTarget /
// onRecordMediaFeedback：mediaId = asset.id.toString()、query = 最近一轮快照 query、
// feedbackType = action.name.lowercase()）。
//
// ⚠️ session_id：并行 agent API 约定的签名不含 sessionId，落库按约定写空串。
//    取分 SQL（§8）只按 query_text 精确等值分组，session_id 不参与，无行为影响。

final class MediaFeedbackUseCase {

    /// 契约 §8：LIKE 加分（MediaFeedbackUseCase.kt:39）
    static let likeBonus: Float = 0.15
    /// 契约 §8：DISLIKE 减分（MediaFeedbackUseCase.kt:40）
    static let dislikePenalty: Float = 0.15

    private let db: TagDatabase

    init(db: TagDatabase) {
        self.db = db
    }

    /// 契约 §8：`query_text` 精确等值取 LIKE/DISLIKE 计数 → 排序调整分
    /// `delta = likeCount * 0.15 - dislikeCount * 0.15`；delta == 0 不出现在结果里（"不动"）。
    /// 返回 mediaId（String，= media_assets.id 的十进制串）→ 调整分。
    func scoreAdjustments(queryText: String) -> [String: Float] {
        var out: [String: Float] = [:]
        for (mediaId, counts) in db.feedbackLikeDislikeCounts(queryText: queryText) {
            let delta = Float(counts.likeCount) * Self.likeBonus
                      - Float(counts.dislikeCount) * Self.dislikePenalty
            if delta != 0 { out[mediaId] = delta }
        }
        return out
    }

    /// 记录反馈（Chat feedback/more 命令落点）：target 在 shownResults（上一轮展示结果）
    /// 内解析出目标媒体后逐条 insert；解析失败静默不写（对齐 Android 返回 false 的调用方语义）。
    /// - Parameters:
    ///   - target: 契约 §1.4 反馈目标（Ordinal 1-based / Description / MediaId / LastShown）
    ///   - action: LIKE / DISLIKE / MORE_LIKE_THIS（落库 feedback_type = rawValue.lowercased()，
    ///             对齐 Android `action.name.lowercase()`；"more" 路径现网不调用 record）
    ///   - queryHint: 最近一轮搜索 query（Android 取最近快照 query，无快照时 ""）
    ///   - shownResults: 上一轮展示结果（等价 Android lastResultAssets[sessionId]）
    func record(target: FeedbackTarget, action: FeedbackAction, queryHint: String?, shownResults: [SearchMediaRow]) {
        guard let row = Self.resolveTarget(target, in: shownResults) else { return }
        db.insertMediaFeedback(
            mediaId: String(row.id),
            feedbackType: action.rawValue.lowercased(),
            queryText: queryHint ?? "",
            sessionId: "")
    }

    // MARK: - resolveTarget（ChatViewModel.kt:1636-1656 逐字对齐）

    /// LastShown → 首项；Ordinal(index) → 第 index-1 项（1-based，负值钳到 0）；
    /// MediaId → id 字符串匹配；Description → 描述按空白切词，任一词 contains 命中
    /// labels.tags 或 fileName（ignoreCase）。
    static func resolveTarget(_ target: FeedbackTarget, in shownResults: [SearchMediaRow]) -> SearchMediaRow? {
        guard !shownResults.isEmpty else { return nil }
        switch target {
        case .lastShown:
            return shownResults.first
        case .ordinal(let index):
            let idx = max(index - 1, 0)
            return idx < shownResults.count ? shownResults[idx] : nil
        case .mediaId(let id):
            return shownResults.first { String($0.id) == id }
        case .description(let text):
            return shownResults.first { matchesTags(row: $0, description: text) }
        }
    }

    /// Description 匹配（ChatViewModel.kt:1648-1656）：按 `\s+` 切词（空描述 → 不命中），
    /// 任一词 contains 命中 labels.tags 任一项或 fileName（ignoreCase）。
    private static func matchesTags(row: SearchMediaRow, description: String) -> Bool {
        let labels = parseLabelTags(row.labels)
        let terms = description.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }
        if terms.isEmpty { return false }
        return terms.contains { term in
            labels.contains { $0.range(of: term, options: .caseInsensitive) != nil }
                || row.fileName.range(of: term, options: .caseInsensitive) != nil
        }
    }

    /// labels 列 JSON 解析（§4.6：`JSONObject(labels).optJSONArray("tags")`）。
    private static func parseLabelTags(_ labels: String?) -> [String] {
        guard let labels = labels,
              let data = labels.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tags = obj["tags"] as? [Any] else { return [] }
        return tags.compactMap { $0 as? String }
    }
}
