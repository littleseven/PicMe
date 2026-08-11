import Foundation

// MARK: - 规则查询解析器（双端契约 SSOT: contracts.md §3.1/§3.2/§3.4/§3.6/§3.9）
//
// 照抄 Android `app/domain/search/QueryParser.kt`。要点：
// - parse 返回 nil 即「规则引擎无法解析」= needsLlm（走 LLM/兜底层）；返回的 SearchFilter.needsLlm 恒为 false。
// - 规则解析不产 OCR 关键词：返回的 SearchFilter.ocrKeywords 恒为空。
// - 所有毫秒值用 Calendar.current（设备本地时区），对齐 Android 本地时区 Calendar 语义。
// - lang 行为差异仅体现在停用词表选择（契约 §3.6）："en" → 英文表，其余（含 "zh"）→ 中文表。
//
// DEVIATION（契约 §14 R5，specs/screens/gallery-grid.yaml allowed_differences.search_engine_edge_cases）：
//   裸「冬天」（无年前缀）做跨年修正（endYear = year + 1），不复刻 Android startMs > endMs 恒空 bug。
// DEVIATION（契约 §14 R4，同上）：
//   removeTimeWords 的「近N个月」清洗使用正确的 \d{1,2} 正则语义
//   （Android Kotlin raw string 转义失效、数字分支是死代码），不复刻。

enum QueryParser {

    static let threeYearsInMonths = 36
    static let maxRelativeMonths = 99

    // MARK: - 入口（契约 §3.1 总流程）

    /// - Parameters:
    ///   - query: 原始查询文本
    ///   - lang: 界面语言（"en" 用英文停用词表，其余用中文表）
    ///   - now: 当前时刻（可注入便于测试；currentYear/currentMonth 由此推导）
    static func parse(_ query: String, lang: String, now: Date = Date()) -> SearchFilter? {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }

        let timeRange = parseTimeRange(trimmed, now: now)

        // extractCategorizedKeywords：移除时间词（§3.4）与停用词（§3.6）后切词，命中地点词表进 locationKeywords
        var contentKeywords: [String] = []
        var locationKeywords: [String] = []
        for token in extractKeywords(trimmed, lang: lang) {
            if SearchVocabulary.location.contains(token) {
                locationKeywords.append(token)
            } else {
                contentKeywords.append(token)
            }
        }
        // 通用人物触发词只用于触发 hasFaces，不做标签/OCR 匹配（契约 §3.1 step 4）
        contentKeywords.removeAll { SearchVocabulary.personGenericTriggers.contains($0) }

        let isPeople = isPeopleSearch(trimmed)

        // 无任何约束 → 返回 nil（= needsLlm，走 Layer 2/兜底；契约 §3.1 step 6）
        if timeRange == nil && contentKeywords.isEmpty && locationKeywords.isEmpty && !isPeople {
            return nil
        }
        return SearchFilter(
            timeRange: timeRange,
            keywords: contentKeywords,
            ocrKeywords: [], // 规则解析恒不产 OCR 关键词（契约 §3.1 step 7）
            locationKeywords: locationKeywords,
            personName: nil,
            hasFaces: isPeople ? true : nil,
            needsLlm: false
        )
    }

    // MARK: - 时间词规则（契约 §3.2，判定顺序即优先级，先命中先返回）

    static func parseTimeRange(_ query: String, now: Date = Date()) -> SearchTimeRange? {
        let cal = Calendar.current
        let currentYear = cal.component(.year, from: now)
        let currentMonth = cal.component(.month, from: now) // 1-based

        // 规则 1：相对年+月（去年|今年|前年 后紧跟 ^(\d{1,2})月 或 ^([一二三四五六七八九十]{1,3})月）
        for (word, offset) in [("去年", -1), ("今年", 0), ("前年", -2)] {
            guard let range = query.range(of: word) else { continue }
            let rest = String(query[range.upperBound...])
            if let month = parseLeadingMonth(rest) {
                return monthRange(year: currentYear + offset, month: month, cal: cal)
            }
        }

        // 规则 2 + 规则 5（合并实现：(\d{4})年 后可解析月份 → 该月整月；无月份 → 该整年，即规则 5 语义）
        if let match = firstMatch(Self.absoluteYearRegex, in: query), let year = Int(match.groups[0] ?? "") {
            let rest = String(query[match.range.upperBound...])
            if let month = parseLeadingMonth(rest) {
                return monthRange(year: year, month: month, cal: cal)
            }
            return yearRange(year: year, cal: cal)
        }

        // 规则 3：独立中文月份（^([一二三四五六七八九十]{1,3})月，锚定查询开头）→ 当前年该月
        if let month = parseLeadingChineseMonth(query) {
            return monthRange(year: currentYear, month: month, cal: cal)
        }

        // 规则 4：相对年+季节（startMonth 0-based：春=2、夏=5、秋=8、冬=11；
        // endMonth=(startMonth+2)%12，endMonth<startMonth 时 endYear=year+1，冬天跨年）
        let seasons: [(String, Int)] = [("春天", 2), ("夏天", 5), ("秋天", 8), ("冬天", 11)]
        for (word, offset) in [("去年", -1), ("今年", 0), ("前年", -2)] where query.contains(word) {
            for (season, startMonth) in seasons where query.contains(season) {
                return seasonRange(year: currentYear + offset, startMonthZeroBased: startMonth, cal: cal)
            }
        }

        // 规则 6：去年 → 去年整年
        if query.contains("去年") {
            return yearRange(year: currentYear - 1, cal: cal)
        }
        // 规则 7：今年 → 今年整年；若同时含"夏天" → 今年 6-8 月
        // （注：规则 4 已先命中"今年+季节"，此处的夏天分支实际不可达，照抄保留以对齐 Android 结构）
        if query.contains("今年") {
            if query.contains("夏天") {
                return SearchTimeRange(
                    startMs: monthStartMs(currentYear, 6, cal),
                    endMs: monthEndMs(currentYear, 8, cal)
                )
            }
            return yearRange(year: currentYear, cal: cal)
        }
        // 规则 8：前年 → 前年整年
        if query.contains("前年") {
            return yearRange(year: currentYear - 2, cal: cal)
        }
        // 规则 9：夏天（无前缀）→ 今年 6-8 月
        if query.contains("夏天") {
            return SearchTimeRange(
                startMs: monthStartMs(currentYear, 6, cal),
                endMs: monthEndMs(currentYear, 8, cal)
            )
        }
        // 规则 10：春天/秋天/冬天（无前缀）→ 今年对应季节
        // DEVIATION（契约 §14 R5）：裸「冬天」做跨年修正（endYear = year + 1）。
        // Android 此分支 endYear 不跨年修正，导致 startMs（今年12月1日）> endMs（今年2月末）、
        // SQL BETWEEN 恒空；iOS 按登记决策修复，不复刻该 bug。
        for (season, startMonth) in seasons where query.contains(season) {
            return seasonRange(year: currentYear, startMonthZeroBased: startMonth, cal: cal)
        }

        // 规则 11：上个月 → 当前年月减 1 个月的整月
        if query.contains("上个月") {
            let firstOfCurrent = cal.date(from: DateComponents(year: currentYear, month: currentMonth, day: 1))!
            let firstOfPrev = cal.date(byAdding: .month, value: -1, to: firstOfCurrent)!
            return SearchTimeRange(startMs: ms(firstOfPrev), endMs: ms(firstOfCurrent) - 1)
        }

        // 规则 12：本周/上周 → 该周周一 00:00:00.000 ~ 周日 23:59:59
        // （毫秒未置 999 —— Android 现网行为，契约 §14 R12，不是笔误）
        if query.contains("本周") || query.contains("上周") {
            var weekCal = cal
            weekCal.firstWeekday = 2 // 周一为一周之始（契约 §3.2）
            var ref = now
            if query.contains("上周") {
                ref = weekCal.date(byAdding: .weekOfYear, value: -1, to: now)!
            }
            let weekStart = weekCal.dateInterval(of: .weekOfYear, for: ref)!.start
            let weekEnd = weekCal.date(
                byAdding: DateComponents(day: 6, hour: 23, minute: 59, second: 59),
                to: weekStart
            )!
            return SearchTimeRange(startMs: ms(weekStart), endMs: ms(weekEnd))
        }

        // 规则 13：前天/昨天/今天 → 当日 00:00:00.000 ~ 23:59:59
        // （毫秒未置 999 —— Android 现网行为，契约 §14 R12）
        for (word, dayOffset) in [("前天", -2), ("昨天", -1), ("今天", 0)] where query.contains(word) {
            let day = cal.date(byAdding: .day, value: dayOffset, to: now)!
            let start = cal.date(from: cal.dateComponents([.year, .month, .day], from: day))!
            let end = cal.date(byAdding: DateComponents(hour: 23, minute: 59, second: 59), to: start)!
            return SearchTimeRange(startMs: ms(start), endMs: ms(end))
        }

        // 规则 14：近半年/近一年/近两年/近三年（近X|最近X|X内；半年:6、一年:12、两年:24、三年:36 个月）
        for (word, months) in [("半年", 6), ("一年", 12), ("两年", 24), ("三年", Self.threeYearsInMonths)] {
            if query.contains("近\(word)") || query.contains("最近\(word)") || query.contains("\(word)内") {
                return monthsAgoRange(months, currentYear: currentYear, currentMonth: currentMonth, cal: cal)
            }
        }

        // 规则 15：近N个月 / N个月内（(?:近|最近)(\d{1,2})个月|(\d{1,2})个月内，N ∈ 1...99）
        if let match = firstMatch(Self.relativeNumericMonthsRegex, in: query) {
            let nText = match.groups[0] ?? match.groups[1]
            if let nText, let n = Int(nText), (1...Self.maxRelativeMonths).contains(n) {
                return monthsAgoRange(n, currentYear: currentYear, currentMonth: currentMonth, cal: cal)
            }
        }

        // 规则 16：近[中文数字]个月（(?:近|最近)([一二三四五六七八九十]{1,3})个月）
        if let match = firstMatch(Self.relativeChineseMonthsRegex, in: query),
           let group = match.groups[0],
           let n = parseChineseNumber(group) {
            return monthsAgoRange(n, currentYear: currentYear, currentMonth: currentMonth, cal: cal)
        }

        return nil
    }

    // MARK: - isPeopleSearch（契约 §3.9，contains 子串 + ignoreCase）

    private static let peopleSearchTriggers = [
        "人", "人物", "人脸", "合照", "合影", "people", "person",
        "face", "portrait", "selfie", "自拍", "头像",
        "小孩", "儿童", "婴儿", "宝宝", "孩子",
        "child", "children", "kid", "kids", "baby", "infant", "toddler"
    ]

    static func isPeopleSearch(_ query: String) -> Bool {
        peopleSearchTriggers.contains { query.range(of: $0, options: .caseInsensitive) != nil }
    }

    // MARK: - 关键词提取（契约 §3.4 removeTimeWords + §3.6 停用词）

    /// 直接字符串替换移除（顺序照抄契约 §3.4 / Android 源码链式 replace 顺序）。
    /// ⚠️ 保真说明：列表中「近半年」先于「最近半年」，故「最近半年」会先被剥成「最」——
    /// Android 现网同款残留行为（见 QueryParserTests.testRecentHalfYearKeywordResidueFidelity）。
    private static let literalTimeWords = [
        "去年", "今年", "上个月", "本周", "上周", "今天", "昨天", "前天",
        "春天", "夏天", "秋天", "冬天",
        "近半年", "最近半年", "半年内", "近一年", "最近一年", "一年内", "近两年", "最近两年", "两年内"
    ]

    // DEVIATION（契约 §14 R4）：数字月份分支使用正确的 `\d{1,2}` 正则语义。
    // Android Kotlin raw string 中写成 `\\d{1,2}`（正则语义 = 字面反斜杠 + 字母 d 重复），
    // 「近3个月」「3个月内」实际不会被从关键词中剔除；iOS 按登记决策修复，不复刻。
    private static let relativeMonthsRemovalRegex = try! NSRegularExpression(
        pattern: #"(?:近|最近)\d{1,2}个月|\d{1,2}个月内|(?:近|最近)[一二三四五六七八九十]{1,3}个月|[一二三四五六七八九十]{1,3}个月内"#
    )

    private static func removeTimeWords(_ query: String) -> String {
        var text = query
        for word in Self.literalTimeWords {
            text = text.replacingOccurrences(of: word, with: "")
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return Self.relativeMonthsRemovalRegex.stringByReplacingMatches(in: text, range: range, withTemplate: "")
    }

    /// 中文停用词（lang ≠ "en" 时用）。契约 §3.6。
    private static let stopWordsZh = [
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "都", "一", "把",
        "一个", "上面", "下面", "可以", "这个", "那个", "拍", "照片", "图片",
        "找", "搜索", "显示", "查看", "包含", "给我", "帮我"
    ]

    /// 英文停用词（lang == "en" 时用，替换时 ignoreCase）。契约 §3.6。
    private static let stopWordsEn = [
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "i", "you", "he", "she", "it", "we", "they", "my", "your", "his", "her",
        "its", "our", "their", "me", "him", "them", "us",
        "of", "in", "on", "at", "to", "for", "with", "about", "from", "by",
        "and", "or", "but", "so", "if", "because", "as",
        "show", "find", "search", "look", "see", "view", "display", "give", "help",
        "photo", "photos", "picture", "pictures", "image", "images"
    ]

    /// 切分分隔符（契约 §3.6）：`[\s，,。.!！？?]+`
    private static let tokenSeparatorRegex = try! NSRegularExpression(
        pattern: #"[\s，,。.!！？?]+"#
    )

    private static func extractKeywords(_ query: String, lang: String) -> [String] {
        var text = removeTimeWords(query)
        // lang 参数行为差异仅体现在停用词表选择（契约 §3.6）
        let stopWords = (lang.lowercased() == "en") ? Self.stopWordsEn : Self.stopWordsZh
        for word in stopWords {
            // 子串替换（非整词）、ignoreCase —— 照抄 Android text.replace(word, " ", ignoreCase = true)
            text = text.replacingOccurrences(of: word, with: " ", options: .caseInsensitive)
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        let spaced = Self.tokenSeparatorRegex.stringByReplacingMatches(in: text, range: range, withTemplate: " ")
        var seen = Set<String>()
        return spaced.split(separator: " ").map(String.init)
            .filter { seen.insert($0).inserted } // 去空（split 已省略空段）+ 去重保序
    }

    // MARK: - 时间计算 helper（monthStartMs = 该月1日 00:00:00.000；monthEndMs = 该月最后一日 23:59:59.999）

    private static func monthRange(year: Int, month: Int, cal: Calendar) -> SearchTimeRange {
        SearchTimeRange(startMs: monthStartMs(year, month, cal), endMs: monthEndMs(year, month, cal))
    }

    private static func yearRange(year: Int, cal: Calendar) -> SearchTimeRange {
        SearchTimeRange(startMs: monthStartMs(year, 1, cal), endMs: monthEndMs(year, 12, cal))
    }

    /// 季节范围：startMonthZeroBased ∈ {春=2, 夏=5, 秋=8, 冬=11}；endMonth=(start+2)%12，跨年修正 endYear=year+1。
    private static func seasonRange(year: Int, startMonthZeroBased: Int, cal: Calendar) -> SearchTimeRange {
        let endMonthZeroBased = (startMonthZeroBased + 2) % 12
        let endYear = endMonthZeroBased < startMonthZeroBased ? year + 1 : year
        return SearchTimeRange(
            startMs: monthStartMs(year, startMonthZeroBased + 1, cal),
            endMs: monthEndMs(endYear, endMonthZeroBased + 1, cal)
        )
    }

    /// monthsAgoRange(N)（契约 §3.2）：endMs = 当前月最后一日 23:59:59.999；
    /// startMs =（当前年月 - N 个月）的 1 日 00:00:00.000。注意 endMs 是当前月月末，不是"现在"。
    private static func monthsAgoRange(
        _ months: Int,
        currentYear: Int,
        currentMonth: Int,
        cal: Calendar
    ) -> SearchTimeRange {
        let firstOfCurrent = cal.date(from: DateComponents(year: currentYear, month: currentMonth, day: 1))!
        let start = cal.date(byAdding: .month, value: -months, to: firstOfCurrent)!
        let firstOfNext = cal.date(byAdding: .month, value: 1, to: firstOfCurrent)!
        return SearchTimeRange(startMs: ms(start), endMs: ms(firstOfNext) - 1)
    }

    /// month 为 1-based；月份越界时依赖 Calendar 宽容语义滚动（与 Android lenient Calendar 对齐）。
    private static func monthStartMs(_ year: Int, _ month: Int, _ cal: Calendar) -> Int64 {
        ms(cal.date(from: DateComponents(year: year, month: month, day: 1))!)
    }

    private static func monthEndMs(_ year: Int, _ month: Int, _ cal: Calendar) -> Int64 {
        let start = cal.date(from: DateComponents(year: year, month: month, day: 1))!
        let nextMonthStart = cal.date(byAdding: .month, value: 1, to: start)!
        return ms(nextMonthStart) - 1 // = 该月最后一日 23:59:59.999
    }

    /// 整秒 Date → 毫秒（调用点均为整秒时刻，整数秒在 Double 内精确表示）。
    private static func ms(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970) * 1000
    }

    // MARK: - 正则与数字解析 helper

    private static let absoluteYearRegex = try! NSRegularExpression(pattern: #"(\d{4})年"#)
    private static let leadingNumericMonthRegex = try! NSRegularExpression(pattern: #"^(\d{1,2})月"#)
    private static let leadingChineseMonthRegex = try! NSRegularExpression(
        pattern: #"^([一二三四五六七八九十]{1,3})月"#
    )
    private static let relativeNumericMonthsRegex = try! NSRegularExpression(
        pattern: #"(?:近|最近)(\d{1,2})个月|(\d{1,2})个月内"#
    )
    private static let relativeChineseMonthsRegex = try! NSRegularExpression(
        pattern: #"(?:近|最近)([一二三四五六七八九十]{1,3})个月"#
    )

    /// 解析串首月份：^(\d{1,2})月 或 ^([一二三四五六七八九十]{1,3})月
    private static func parseLeadingMonth(_ text: String) -> Int? {
        if let match = firstMatch(Self.leadingNumericMonthRegex, in: text), let g = match.groups[0] {
            return Int(g)
        }
        return parseLeadingChineseMonth(text)
    }

    private static func parseLeadingChineseMonth(_ text: String) -> Int? {
        guard let match = firstMatch(Self.leadingChineseMonthRegex, in: text), let g = match.groups[0] else {
            return nil
        }
        return parseChineseNumber(g)
    }

    /// 中文数字映射（契约 §3.2）：一二三四五六七八九十→1-10、十一→11、十二→12；其余 → nil。
    private static func parseChineseNumber(_ text: String) -> Int? {
        let digits: [Character: Int] = [
            "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
            "六": 6, "七": 7, "八": 8, "九": 9
        ]
        let chars = Array(text)
        if chars.count == 1 {
            return chars[0] == "十" ? 10 : digits[chars[0]]
        }
        if chars.count == 2, chars[0] == "十", let d = digits[chars[1]] {
            return 10 + d
        }
        return nil
    }

    private static func firstMatch(
        _ regex: NSRegularExpression,
        in text: String
    ) -> (range: Range<String.Index>, groups: [String?])? {
        let nsRange = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let result = regex.firstMatch(in: text, range: nsRange),
              let range = Range(result.range, in: text) else { return nil }
        var groups: [String?] = []
        for i in 1..<result.numberOfRanges {
            groups.append(Range(result.range(at: i), in: text).map { String(text[$0]) })
        }
        return (range, groups)
    }
}
