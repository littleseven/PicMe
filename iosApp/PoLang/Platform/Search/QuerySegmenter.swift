import Foundation

// MARK: - 查询分段器（双端契约 SSOT: contracts.md §3.3）
//
// 照抄 Android `app/domain/search/QuerySegmenter.kt` + SegmentType.kt / SegmentedQuery.kt /
// Segment.kt / ExplicitFilter.kt / ContentFilter.kt（这些类型为分段管线私有，集中在本文件）。
//
// 词典匹配顺序以代码实现为准（契约 §14 R2）：停用词 → SCENE → LOCATION → PERSON → OBJECT → ACTIVITY → OCR
// （Android 文件头 KDoc 写的优先级 ...OCR > PERSON 与实现不一致，PERSON 在 OBJECT 之前）。

/// 分段类型（契约 §3.3 / SegmentType.kt）。
enum SegmentType: Equatable, Sendable {
    case time, location, person, object, scene, activity, ocr, unknown

    /// explicit（收窄候选集）：TIME/LOCATION/PERSON
    var isExplicit: Bool { self == .time || self == .location || self == .person }
    /// content：OBJECT/SCENE/ACTIVITY/OCR；UNKNOWN 两者皆不是
    var isContent: Bool { self == .object || self == .scene || self == .activity || self == .ocr }
}

/// 单个分段（契约 §3.3 / Segment.kt）。
struct Segment: Equatable, Sendable {
    let text: String
    let type: SegmentType
}

/// 分段结果（契约 §3.3 / SegmentedQuery.kt）。
struct SegmentedQuery: Equatable, Sendable {
    let originalQuery: String
    let segments: [Segment]

    /// Layer 0.5 短路条件：存在 TIME 或 LOCATION 段（契约 §2.2 step 3）。
    /// 纯人物/概念查询（如"小孩"）不短路。
    var hasNarrowingExplicit: Bool {
        segments.contains { $0.type == .time || $0.type == .location }
    }
}

/// 显式约束过滤（契约 §3.3 / ExplicitFilter.kt）。
struct ExplicitFilter: Equatable, Sendable {
    var timeRange: SearchTimeRange? = nil
    var locationKeywords: [String] = []
    var hasFaces: Bool? = nil
    var personKeywords: [String] = []
}

/// 内容过滤（契约 §3.3 / ContentFilter.kt）。
struct ContentFilter: Equatable, Sendable {
    var keywords: [String] = []
    var ocrKeywords: [String] = []
    var semanticQuery: String = ""
}

enum QuerySegmenter {

    static let maxSegmentLength = 8

    /// 分段器自有停用词表（契约 §3.3；注意与 QueryParser 的停用词表不同——本表含 里/中/上/下/与/及/还有）。
    private static let stopWords: Set<String> = [
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "都", "一",
        "把", "一个", "上面", "下面", "可以", "这个", "那个", "拍", "找", "搜索",
        "显示", "查看", "包含", "给我", "帮我", "里", "中", "上", "下", "与", "及", "还有"
    ]

    /// TIME_PATTERN（契约 §3.3，锚定 ^，交替顺序照抄）。
    /// 注：本正则在 Android 侧 \d 写法本来就是正确的（契约 §14 R4 的转义失效仅影响 QueryParser.removeTimeWords）。
    private static let timePattern: NSRegularExpression = {
        let chineseMonth = "[一二三四五六七八九十]{1,3}"
        let relativeYearLike = "近半年|最近半年|半年内|近一年|最近一年|一年内|近两年|最近两年|两年内"
        let relativeMonths = #"(?:近|最近)\d{1,2}个月|\d{1,2}个月内|(?:近|最近)"# + chineseMonth + "个月"
        let pattern = "^(?:"
            + #"\d{4}年\d{1,2}月"# + "|"
            + #"\d{4}年"# + chineseMonth + "月" + "|"
            + "去年" + chineseMonth + "月" + "|" + #"去年\d{1,2}月"# + "|"
            + "今年" + chineseMonth + "月" + "|" + #"今年\d{1,2}月"# + "|"
            + "前年" + chineseMonth + "月" + "|" + #"前年\d{1,2}月"# + "|"
            + "去年[春夏秋冬]天" + "|" + "今年[春夏秋冬]天" + "|" + "前年[春夏秋冬]天" + "|"
            + chineseMonth + "月" + "|"
            + relativeYearLike + "|"
            + relativeMonths + "|"
            + "去年|今年|前年|上个月|本周|上周|今天|昨天|前天" + "|"
            + "春天|夏天|秋天|冬天"
            + ")"
        return try! NSRegularExpression(pattern: pattern)
    }()

    /// 贪心最长匹配分段（契约 §3.3 分段算法）。
    static func segment(_ query: String) -> SegmentedQuery {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        var segments: [Segment] = []
        var remaining = trimmed

        while !remaining.isEmpty {
            // 1. 先尝试 TIME_PATTERN（锚定 ^ = 当前剩余串首）
            let nsRange = NSRange(remaining.startIndex..<remaining.endIndex, in: remaining)
            if let match = Self.timePattern.firstMatch(in: remaining, range: nsRange),
               let range = Range(match.range, in: remaining) {
                segments.append(Segment(text: String(remaining[range]), type: .time))
                remaining = String(remaining[range.upperBound...])
                continue
            }

            // 2. 按长度 8→1 取前缀子串，依次查：停用词 → SCENE → LOCATION → PERSON → OBJECT → ACTIVITY → OCR
            var consumed = false
            let maxLen = min(Self.maxSegmentLength, remaining.count)
            for len in stride(from: maxLen, through: 1, by: -1) {
                let prefix = String(remaining.prefix(len))
                if Self.stopWords.contains(prefix) {
                    // 停用词段直接丢弃（不产出）
                    remaining = String(remaining.dropFirst(len))
                    consumed = true
                    break
                }
                if let type = Self.dictionaryType(for: prefix) {
                    segments.append(Segment(text: prefix, type: type))
                    remaining = String(remaining.dropFirst(len))
                    consumed = true
                    break
                }
            }
            if consumed { continue }

            // 3. 都不命中：取首字符，非停用词则产出 UNKNOWN 段，前进 1 字
            let first = String(remaining.first!)
            if !Self.stopWords.contains(first) {
                segments.append(Segment(text: first, type: .unknown))
            }
            remaining = String(remaining.dropFirst())
        }

        // 4. 结尾合并相邻 UNKNOWN 段为一个段
        return SegmentedQuery(originalQuery: trimmed, segments: Self.mergeAdjacentUnknowns(segments))
    }

    /// 词典查找顺序（契约 §14 R2，以代码顺序为准）：SCENE → LOCATION → PERSON → OBJECT → ACTIVITY → OCR。
    private static func dictionaryType(for word: String) -> SegmentType? {
        if SearchVocabulary.scene.contains(word) { return .scene }
        if SearchVocabulary.location.contains(word) { return .location }
        if SearchVocabulary.person.contains(word) { return .person }
        if SearchVocabulary.object.contains(word) { return .object }
        if SearchVocabulary.activity.contains(word) { return .activity }
        if SearchVocabulary.ocr.contains(word) { return .ocr }
        return nil
    }

    private static func mergeAdjacentUnknowns(_ segments: [Segment]) -> [Segment] {
        var merged: [Segment] = []
        for segment in segments {
            if segment.type == .unknown, let last = merged.last, last.type == .unknown {
                merged[merged.count - 1] = Segment(text: last.text + segment.text, type: .unknown)
            } else {
                merged.append(segment)
            }
        }
        return merged
    }
}

extension SegmentedQuery {

    /// 分段 → ExplicitFilter/ContentFilter（契约 §3.3 toFilters）。
    /// - Parameter now: 时间词解析基准（透传 QueryParser.parseTimeRange，可注入便于测试）。
    func toFilters(now: Date = Date()) -> (explicit: ExplicitFilter, content: ContentFilter) {
        var explicit = ExplicitFilter()
        var content = ContentFilter(semanticQuery: originalQuery) // semanticQuery = 原始查询串

        // timeRange：所有 TIME 段文本拼接后走 QueryParser.parseTimeRange
        let timeText = segments.filter { $0.type == .time }.map(\.text).joined()
        explicit.timeRange = timeText.isEmpty ? nil : QueryParser.parseTimeRange(timeText, now: now)

        for segment in segments {
            switch segment.type {
            case .location:
                explicit.locationKeywords.append(segment.text)
            case .person:
                explicit.personKeywords.append(segment.text)
                // 剔除 PERSON_GENERIC_TRIGGERS 后并入 content.keywords
                if !SearchVocabulary.personGenericTriggers.contains(segment.text) {
                    content.keywords.append(segment.text)
                }
            case .object, .scene, .activity:
                content.keywords.append(segment.text)
            case .ocr:
                content.ocrKeywords.append(segment.text)
            case .time, .unknown:
                break
            }
        }
        // hasFaces：存在 PERSON 段 → true，否则 nil
        explicit.hasFaces = explicit.personKeywords.isEmpty ? nil : true
        return (explicit, content)
    }
}
