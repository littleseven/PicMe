import XCTest
@testable import PoLang

/// QueryParser 单元测试（契约 §3.1/§3.2/§3.4/§3.6/§3.9）。
/// 固定 now = 2026-08-11 15:30:45（周二）本地时区；期望值用 DateComponents 显式构造，
/// 与实现共用 Calendar.current，因此任意设备时区下断言稳定。
final class QueryParserTests: XCTestCase {

    private func makeNow() -> Date {
        Calendar.current.date(from: DateComponents(year: 2026, month: 8, day: 11, hour: 15, minute: 30, second: 45))!
    }

    /// 显式日期组件 → 毫秒（本地时区）
    private func ms(
        _ year: Int, _ month: Int, _ day: Int,
        _ hour: Int = 0, _ minute: Int = 0, _ second: Int = 0, milli: Int = 0
    ) -> Int64 {
        var c = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        c.nanosecond = milli * 1_000_000
        let d = Calendar.current.date(from: c)!
        return Int64((d.timeIntervalSince1970 * 1000).rounded())
    }

    private func range(
        _ sy: Int, _ sm: Int, _ sd: Int,
        _ ey: Int, _ em: Int, _ ed: Int,
        endMilli: Int = 999
    ) -> SearchTimeRange {
        SearchTimeRange(
            startMs: ms(sy, sm, sd),
            endMs: ms(ey, em, ed, 23, 59, 59, milli: endMilli)
        )
    }

    private func timeRange(of query: String, lang: String = "zh", now: Date? = nil) -> SearchTimeRange? {
        QueryParser.parse(query, lang: lang, now: now ?? makeNow())?.timeRange
    }

    // MARK: - 契约 §3.2 规则 1：相对年+月

    func testRule1_relativeYearChineseMonth() {
        XCTAssertEqual(timeRange(of: "去年三月"), range(2025, 3, 1, 2025, 3, 31))
        XCTAssertEqual(timeRange(of: "前年十一月"), range(2024, 11, 1, 2024, 11, 30))
    }

    func testRule1_relativeYearNumericMonth() {
        XCTAssertEqual(timeRange(of: "今年12月"), range(2026, 12, 1, 2026, 12, 31))
        XCTAssertEqual(timeRange(of: "去年3月"), range(2025, 3, 1, 2025, 3, 31))
    }

    // MARK: - 契约 §3.2 规则 2/5：绝对年+月 / 绝对整年

    func testRule2_absoluteYearMonth() {
        XCTAssertEqual(timeRange(of: "2024年5月"), range(2024, 5, 1, 2024, 5, 31))
        XCTAssertEqual(timeRange(of: "2024年五月"), range(2024, 5, 1, 2024, 5, 31))
    }

    func testRule5_absoluteYearOnly() {
        XCTAssertEqual(timeRange(of: "2024年"), range(2024, 1, 1, 2024, 12, 31))
    }

    // MARK: - 契约 §3.2 规则 3：独立中文月份

    func testRule3_standaloneChineseMonth() {
        let filter = QueryParser.parse("三月的照片", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.timeRange, range(2026, 3, 1, 2026, 3, 31))
        // 保真：「三月」不在 removeTimeWords 列表（§3.4），残留为内容关键词——Android 现网同款
        XCTAssertEqual(filter?.keywords, ["三月"])
    }

    // MARK: - 契约 §3.2 规则 4：相对年+季节

    func testRule4_relativeYearSeason() {
        XCTAssertEqual(timeRange(of: "去年夏天"), range(2025, 6, 1, 2025, 8, 31))
        XCTAssertEqual(timeRange(of: "今年秋天"), range(2026, 9, 1, 2026, 11, 30))
    }

    func testRule4_relativeYearWinterCrossYear() {
        // 带年前缀的冬天：endMonth < startMonth → endYear = year + 1（Android 也正常）
        XCTAssertEqual(timeRange(of: "去年冬天"), range(2025, 12, 1, 2026, 2, 28))
    }

    // MARK: - 契约 §3.2 规则 6/7/8：去年/今年/前年整年

    func testRule6_lastYear() {
        XCTAssertEqual(timeRange(of: "去年"), range(2025, 1, 1, 2025, 12, 31))
    }

    func testRule7_thisYear() {
        XCTAssertEqual(timeRange(of: "今年"), range(2026, 1, 1, 2026, 12, 31))
    }

    func testRule8_yearBeforeLast() {
        XCTAssertEqual(timeRange(of: "前年"), range(2024, 1, 1, 2024, 12, 31))
    }

    // MARK: - 契约 §3.2 规则 9/10：裸季节

    func testRule9_bareSummer() {
        XCTAssertEqual(timeRange(of: "夏天"), range(2026, 6, 1, 2026, 8, 31))
    }

    func testRule10_bareSpringAutumn() {
        XCTAssertEqual(timeRange(of: "春天"), range(2026, 3, 1, 2026, 5, 31))
        XCTAssertEqual(timeRange(of: "秋天"), range(2026, 9, 1, 2026, 11, 30))
    }

    /// DEVIATION（契约 §14 R5）：裸「冬天」跨年修正（endYear = 2027），
    /// 不复刻 Android startMs > endMs 恒空 bug。
    func testRule10_bareWinter_DEVIATION_R5() {
        let tr = timeRange(of: "冬天")
        XCTAssertEqual(tr, range(2026, 12, 1, 2027, 2, 28))
        XCTAssertLessThan(tr!.startMs, tr!.endMs, "裸冬天必须跨年：startMs < endMs（Android 此处恒空，iOS 修复）")
    }

    // MARK: - 契约 §3.2 规则 11：上个月

    func testRule11_lastMonth() {
        XCTAssertEqual(timeRange(of: "上个月"), range(2026, 7, 1, 2026, 7, 31))
    }

    func testRule11_lastMonthJanuaryRollover() {
        let janNow = Calendar.current.date(from: DateComponents(year: 2026, month: 1, day: 15, hour: 12))!
        XCTAssertEqual(timeRange(of: "上个月", now: janNow), range(2025, 12, 1, 2025, 12, 31))
    }

    // MARK: - 契约 §3.2 规则 12：本周/上周（endMs 毫秒未置 999 —— R12 Android 现网行为）

    func testRule12_thisWeek() {
        // 2026-08-11 是周二，本周周一 = 08-10，周日 = 08-16 23:59:59.000（R12：毫秒 .000）
        XCTAssertEqual(timeRange(of: "本周"), range(2026, 8, 10, 2026, 8, 16, endMilli: 0))
    }

    func testRule12_lastWeek() {
        XCTAssertEqual(timeRange(of: "上周"), range(2026, 8, 3, 2026, 8, 9, endMilli: 0))
    }

    // MARK: - 契约 §3.2 规则 13：前天/昨天/今天（endMs 毫秒未置 999 —— R12）

    func testRule13_days() {
        XCTAssertEqual(timeRange(of: "今天"), range(2026, 8, 11, 2026, 8, 11, endMilli: 0))
        XCTAssertEqual(timeRange(of: "昨天"), range(2026, 8, 10, 2026, 8, 10, endMilli: 0))
        XCTAssertEqual(timeRange(of: "前天"), range(2026, 8, 9, 2026, 8, 9, endMilli: 0))
    }

    // MARK: - 契约 §3.2 规则 14：近半年/近一年/近两年/近三年

    func testRule14_relativePeriods() {
        // monthsAgoRange：startMs = 当前年月 - N 个月的 1 日；endMs = 当前月月末（2026-08-31 23:59:59.999）
        XCTAssertEqual(timeRange(of: "近半年"), range(2026, 2, 1, 2026, 8, 31))
        XCTAssertEqual(timeRange(of: "最近一年"), range(2025, 8, 1, 2026, 8, 31))
        XCTAssertEqual(timeRange(of: "两年内"), range(2024, 8, 1, 2026, 8, 31))
        XCTAssertEqual(timeRange(of: "近三年"), range(2023, 8, 1, 2026, 8, 31))
    }

    // MARK: - 契约 §3.2 规则 15：近N个月 / N个月内

    func testRule15_relativeNumericMonths() {
        XCTAssertEqual(timeRange(of: "近3个月"), range(2026, 5, 1, 2026, 8, 31))
        XCTAssertEqual(timeRange(of: "3个月内"), range(2026, 5, 1, 2026, 8, 31))
    }

    func testRule15_monthOutOfBoundRejected() {
        // N=0 超出 1...99 → 时间词不命中；DEVIATION R4 的清洗把「近0个月」也剔除 → 无任何约束 → nil
        XCTAssertNil(QueryParser.parse("近0个月", lang: "zh", now: makeNow()))
    }

    // MARK: - 契约 §3.2 规则 16：近[中文数字]个月

    func testRule16_relativeChineseMonths() {
        XCTAssertEqual(timeRange(of: "近三个月"), range(2026, 5, 1, 2026, 8, 31))
        XCTAssertEqual(timeRange(of: "最近十二个月"), range(2025, 8, 1, 2026, 8, 31))
    }

    // MARK: - DEVIATION（契约 §14 R4）：近N个月关键词清洗

    /// Android removeTimeWords 正则转义失效（\\d 成字面反斜杠+d），「近3个月」残留进关键词；
    /// iOS 用正确的 \d{1,2} 语义清洗。
    func testRemoveTimeWords_numericMonths_DEVIATION_R4() {
        let filter = QueryParser.parse("近3个月的猫", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.timeRange, range(2026, 5, 1, 2026, 8, 31))
        XCTAssertEqual(filter?.keywords, ["猫"], "「近3个月」必须被清洗掉，不残留进关键词（Android 此处残留，iOS 修复）")
    }

    // MARK: - 关键词三分类（契约 §3.1 step 3/4 + §3.7）

    func testLocationKeyword() {
        let filter = QueryParser.parse("上海的照片", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.locationKeywords, ["上海"])
        XCTAssertEqual(filter?.keywords, [])
        XCTAssertNil(filter?.timeRange)
    }

    func testContentKeywords() {
        XCTAssertEqual(QueryParser.parse("猫和狗", lang: "zh", now: makeNow())?.keywords, ["猫", "狗"])
    }

    func testMixedLocationAndContent() {
        let filter = QueryParser.parse("北京 猫", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.locationKeywords, ["北京"])
        XCTAssertEqual(filter?.keywords, ["猫"])
    }

    func testTimePlusKeyword() {
        let filter = QueryParser.parse("去年夏天的猫", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.timeRange, range(2025, 6, 1, 2025, 8, 31))
        XCTAssertEqual(filter?.keywords, ["猫"])
        XCTAssertTrue(filter?.ocrKeywords.isEmpty ?? false, "规则解析恒不产 OCR 关键词")
    }

    // MARK: - 人物触发与 hasFaces（契约 §3.1 step 4/5 + §3.8/§3.9）

    func testChildTriggersHasFaces() {
        let filter = QueryParser.parse("小孩", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.hasFaces, true)
        XCTAssertEqual(filter?.keywords, ["小孩"], "「小孩」不是通用触发词，保留为内容关键词")
    }

    func testGenericTriggerOnlySetsHasFaces() {
        let filter = QueryParser.parse("合影", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.hasFaces, true)
        XCTAssertEqual(filter?.keywords, [], "「合影」是通用人物触发词，剔除出关键词")
    }

    func testGenericTriggerStopwordResidueFidelity() {
        // 保真（契约 §3.6 照抄）：中文停用词「拍」做子串替换，「自拍」先被剥成「自」再进关键词——Android 同款
        let filter = QueryParser.parse("自拍", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.hasFaces, true)
        XCTAssertEqual(filter?.keywords, ["自"])
    }

    func testEnglishGenericTrigger() {
        let filter = QueryParser.parse("people", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.hasFaces, true)
        XCTAssertEqual(filter?.keywords, [])
    }

    func testNoFaceByDefault() {
        XCTAssertNil(QueryParser.parse("猫", lang: "zh", now: makeNow())?.hasFaces)
    }

    // MARK: - needsLlm（契约 §3.1 step 6：返回 nil 即需要 LLM 兜底）

    func testNeedsLlmReturnsNil() {
        XCTAssertNil(QueryParser.parse("", lang: "zh", now: makeNow()))
        XCTAssertNil(QueryParser.parse("   ", lang: "zh", now: makeNow()))
        XCTAssertNil(QueryParser.parse("我的照片", lang: "zh", now: makeNow()), "全是停用词 → 无约束 → nil（= needsLlm）")
    }

    func testParsedFilterNeverNeedsLlm() {
        let filter = QueryParser.parse("asdfqwer", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.keywords, ["asdfqwer"], "非停用词原样成为内容关键词")
        XCTAssertEqual(filter?.needsLlm, false, "规则解析返回的 filter needsLlm 恒为 false")
    }

    // MARK: - lang 行为差异（契约 §3.6：仅停用词表选择）

    func testEnglishStopwordsOnlyInEnglish() {
        XCTAssertEqual(QueryParser.parse("my dog", lang: "en", now: makeNow())?.keywords, ["dog"])
        XCTAssertEqual(
            QueryParser.parse("my dog", lang: "zh", now: makeNow())?.keywords,
            ["my", "dog"],
            "中文界面不套用英文停用词"
        )
    }

    func testEnglishStopwordSubstringResidueFidelity() {
        // 保真（契约 §3.6 照抄）：停用词子串替换按表序执行，「to」先于「photos」，
        // 故 "photos" 被剥成 "pho" + "s"——Android 同款行为，不是实现错误
        XCTAssertEqual(QueryParser.parse("photos of dog", lang: "en", now: makeNow())?.keywords, ["pho", "s", "dog"])
    }

    func testChineseStopwordsOnlyInChinese() {
        XCTAssertNil(QueryParser.parse("我的照片", lang: "zh", now: makeNow()))
        XCTAssertEqual(
            QueryParser.parse("我的照片", lang: "en", now: makeNow())?.keywords,
            ["我的照片"],
            "英文界面不套用中文停用词，原文成关键词"
        )
    }

    // MARK: - 保真用例（Android 现网同款行为，非 deviation）

    func testRecentHalfYearKeywordResidueFidelity() {
        // 契约 §3.4 替换顺序照抄：「近半年」先于「最近半年」被替换 → 「最近半年」残留「最」字（Android 同款）
        let filter = QueryParser.parse("最近半年的猫", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.timeRange, range(2026, 2, 1, 2026, 8, 31))
        XCTAssertEqual(filter?.keywords, ["最", "猫"])
    }

    func testQianNianNotRemovedFromKeywordsFidelity() {
        // 契约 §3.4 列表不含「前年」→ 时间词照解析，但「前年」残留为内容关键词（Android 同款）
        let filter = QueryParser.parse("前年的猫", lang: "zh", now: makeNow())
        XCTAssertEqual(filter?.timeRange, range(2024, 1, 1, 2024, 12, 31))
        XCTAssertEqual(filter?.keywords, ["前年", "猫"])
    }

    // MARK: - SearchSynonyms（契约 §3.5）

    func testSynonymsExpand() {
        XCTAssertEqual(SearchSynonyms.expand("夜景"), ["夜", "夜景"], "expand = map[query] + query 本身")
        XCTAssertEqual(SearchSynonyms.expand("不存在的词"), ["不存在的词"])
        XCTAssertEqual(SearchSynonyms.expand("海边"), ["海", "沙滩", "海边"])
    }
}
