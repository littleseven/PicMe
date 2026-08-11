import Foundation
import SharedKit

// MARK: - Chat 搜索链路纯映射（双端契约 SSOT: tmp/ios-follow/gallery-search/contracts.md §9.3）
//
// SharedKit（Kotlin iosMain）与 Swift 搜索引擎之间的两个映射方向：
// - SharedKit `SearchIntent` → 引擎 `SearchFilter`（字段一一对应 + 时间词清洗 sanitizeTimeKeywords）；
// - 引擎 `SearchMediaRow` → SharedKit `IosSearchResultItem`（K/N DTO，dbId/localIdentifier 双 id 口径）。
//
// 纯函数、无状态，供 PhSearchBridge 调用、PoLangTests 单测钉住。

enum ChatSearchMapping {

    // MARK: SearchIntent → SearchFilter（契约 §9.3）

    /// 字段一一对应（timeRange 换类型、keywords/ocrKeywords/locationKeywords/personName/hasFaces 直拷），
    /// `needsLlm = false`（chat 命令层已标准化，不再要 LLM 兜底）。
    /// 当 timeRange 非空时执行时间词清洗（契约 §9.3 sanitizeTimeKeywords）：
    /// 从三类关键词中剔除时间专属词，避免引擎把时间约束与空内容候选集取交集导致 0 结果。
    static func filter(from intent: SearchIntent) -> SearchFilter {
        // 不用 Optional.map：TimeRange 类型名与 SwiftUI.TimeRange 冲突会错误解析到 Gesture.map
        var searchTimeRange: SearchTimeRange? = nil
        if let tr = intent.timeRange {
            // SharedKit 导出 Kotlin Long 为 Swift Int64，直接使用（非 KotlinLong/NSNumber）
            searchTimeRange = SearchTimeRange(startMs: tr.startMs, endMs: tr.endMs)
        }
        var filter = SearchFilter(
            timeRange: searchTimeRange,
            keywords: intent.keywords,
            ocrKeywords: intent.ocrKeywords,
            locationKeywords: intent.locationKeywords,
            personName: intent.personName,
            hasFaces: intent.hasFaces?.boolValue,
            needsLlm: false
        )
        if filter.timeRange != nil {
            filter.keywords = filter.keywords.filter { !isTimeOnlyKeyword($0) }
            filter.ocrKeywords = filter.ocrKeywords.filter { !isTimeOnlyKeyword($0) }
            filter.locationKeywords = filter.locationKeywords.filter { !isTimeOnlyKeyword($0) }
        }
        return filter
    }

    /// 时间专属词判定（契约 §9.3：timeOnlyKeywords 集合 ∪ monthKeywordRegex）。
    static func isTimeOnlyKeyword(_ word: String) -> Bool {
        if timeOnlyKeywords.contains(word) { return true }
        let range = NSRange(word.startIndex..., in: word)
        return monthKeywordRegex.firstMatch(in: word, range: range) != nil
    }

    /// 契约 §9.3 timeOnlyKeywords（逐字照抄，41 词）。
    static let timeOnlyKeywords: Set<String> = [
        "去年", "今年", "明年", "前年", "后年",
        "春天", "夏天", "秋天", "冬天", "春季", "夏季", "秋季", "冬季",
        "上半年", "下半年", "近半年", "最近半年", "半年",
        "近一年", "最近一年", "一年", "近几年", "最近几年",
        "最近", "近三个月", "近3个月",
        "今天", "昨天", "前天", "明天", "后天",
        "上周", "本周", "下周",
        "上星期", "这星期", "下星期", "上个星期", "这个星期", "下个星期",
        "上个月", "这个月", "下个月", "上月", "今月", "下月",
    ]

    /// 契约 §9.3 monthKeywordRegex：`^(\d{1,2}月|[一二三四五六七八九十]{1,3}月)$`。
    private static let monthKeywordRegex = try! NSRegularExpression(
        pattern: #"^(\d{1,2}月|[一二三四五六七八九十]{1,3}月)$"#)

    // MARK: SearchMediaRow → IosSearchResultItem

    /// 行 → K/N DTO。双 id 口径：dbId = media_assets 主键（feedback/limitToIds）；
    /// localIdentifier 供 Kotlin 侧派生 MediaAsset.id（hashCode）与 uri。
    /// iOS `type` 存 'IMAGE'/'VIDEO'（扫描写入侧约定）→ DTO "PHOTO"/"VIDEO"（IosMediaItem 同约定）。
    static func item(from row: SearchMediaRow) -> IosSearchResultItem {
        IosSearchResultItem(
            dbId: row.id,
            localIdentifier: row.localIdentifier,
            mediaType: row.type == "VIDEO" ? "VIDEO" : "PHOTO",
            captureDateMs: row.captureDate,
            durationMs: row.duration.map { KotlinLong(longLong: $0) },
            fileName: row.fileName,
            hasFace: row.hasFace,
            labels: row.labels,
            ocrText: row.ocrText,
            locationName: row.locationName,
            city: row.city
        )
    }
}
