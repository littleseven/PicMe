import XCTest
@testable import PoLang

/// QuerySegmenter 单元测试（契约 §3.3 + §14 R2 词典匹配顺序）。
/// 固定 now = 2026-08-11 15:30:45 本地时区（供 toFilters 时间词解析）。
final class QuerySegmenterTests: XCTestCase {

    private func makeNow() -> Date {
        Calendar.current.date(from: DateComponents(year: 2026, month: 8, day: 11, hour: 15, minute: 30, second: 45))!
    }

    private func ms(
        _ year: Int, _ month: Int, _ day: Int,
        _ hour: Int = 0, _ minute: Int = 0, _ second: Int = 0, milli: Int = 0
    ) -> Int64 {
        var c = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        c.nanosecond = milli * 1_000_000
        let d = Calendar.current.date(from: c)!
        return Int64((d.timeIntervalSince1970 * 1000).rounded())
    }

    // MARK: - 分段：TIME / LOCATION / OBJECT

    func testTimeLocationObjectSegments() {
        let segmented = QuerySegmenter.segment("去年三月上海的猫")
        XCTAssertEqual(segmented.segments, [
            Segment(text: "去年三月", type: .time),
            Segment(text: "上海", type: .location),
            Segment(text: "猫", type: .object)
        ])
        XCTAssertTrue(segmented.hasNarrowingExplicit)

        let filters = segmented.toFilters(now: makeNow())
        XCTAssertEqual(
            filters.explicit.timeRange,
            SearchTimeRange(startMs: ms(2025, 3, 1), endMs: ms(2025, 3, 31, 23, 59, 59, milli: 999))
        )
        XCTAssertEqual(filters.explicit.locationKeywords, ["上海"])
        XCTAssertNil(filters.explicit.hasFaces)
        XCTAssertEqual(filters.explicit.personKeywords, [])
        XCTAssertEqual(filters.content.keywords, ["猫"])
        XCTAssertEqual(filters.content.ocrKeywords, [])
        XCTAssertEqual(filters.content.semanticQuery, "去年三月上海的猫", "semanticQuery = 原始查询串")
    }

    // MARK: - 词典匹配顺序（契约 §14 R2：以代码顺序为准）

    func testSceneBeforeLocation_order_R2() {
        // 「海边」同在 SCENE 与 LOCATION 词表；SCENE 先查 → SCENE 段
        let segmented = QuerySegmenter.segment("海边")
        XCTAssertEqual(segmented.segments, [Segment(text: "海边", type: .scene)])
        XCTAssertFalse(segmented.hasNarrowingExplicit, "SCENE 是 content 段，不构成收窄短路条件")
    }

    func testStopwordBeforePerson_order_R2() {
        // 「我」同在停用词表与 PERSON 词典；停用词先查 → 直接丢弃不产出段
        XCTAssertEqual(QuerySegmenter.segment("我").segments, [])
        // 「我们」不是停用词 → PERSON 段
        XCTAssertEqual(QuerySegmenter.segment("我们").segments, [Segment(text: "我们", type: .person)])
    }

    // MARK: - 分段：PERSON / OCR / ACTIVITY

    func testPersonSegmentFilters() {
        let segmented = QuerySegmenter.segment("小孩")
        XCTAssertEqual(segmented.segments, [Segment(text: "小孩", type: .person)])
        XCTAssertFalse(segmented.hasNarrowingExplicit, "PERSON 是 explicit 但非 TIME/LOCATION，不短路")

        let filters = segmented.toFilters(now: makeNow())
        XCTAssertEqual(filters.explicit.hasFaces, true)
        XCTAssertEqual(filters.explicit.personKeywords, ["小孩"])
        XCTAssertEqual(filters.content.keywords, ["小孩"], "「小孩」非通用触发词，并入 content.keywords")
        XCTAssertNil(filters.explicit.timeRange)
    }

    func testGenericPersonTriggerExcludedFromContent() {
        let filters = QuerySegmenter.segment("自拍").toFilters(now: makeNow())
        XCTAssertEqual(filters.explicit.personKeywords, ["自拍"])
        XCTAssertEqual(filters.explicit.hasFaces, true)
        XCTAssertEqual(filters.content.keywords, [], "「自拍」是 PERSON_GENERIC_TRIGGERS，剔除出 content.keywords")
    }

    func testOcrSegment() {
        let filters = QuerySegmenter.segment("发票").toFilters(now: makeNow())
        XCTAssertEqual(filters.content.ocrKeywords, ["发票"])
        XCTAssertEqual(filters.content.keywords, [])
    }

    func testActivitySegment() {
        let segmented = QuerySegmenter.segment("2024年5月的聚餐")
        XCTAssertEqual(segmented.segments, [
            Segment(text: "2024年5月", type: .time),
            Segment(text: "聚餐", type: .activity)
        ])
        let filters = segmented.toFilters(now: makeNow())
        XCTAssertEqual(filters.content.keywords, ["聚餐"])
    }

    // MARK: - 分段：停用词丢弃 / UNKNOWN 合并

    func testStopwordsDropped() {
        // 「我」「的」是停用词直接丢弃；「猫」OBJECT
        XCTAssertEqual(QuerySegmenter.segment("我的猫").segments, [Segment(text: "猫", type: .object)])
    }

    func testAdjacentUnknownsMerged() {
        XCTAssertEqual(QuerySegmenter.segment("XYZabc").segments, [Segment(text: "XYZabc", type: .unknown)])
    }

    // MARK: - toFilters：TIME 段文本拼接解析

    func testTimeSegmentTextConcatenation() {
        // 「去年」TIME + 空格 UNKNOWN + 「三月」TIME；toFilters 拼接 TIME 段文本 = 「去年三月」再解析
        let segmented = QuerySegmenter.segment("去年 三月")
        XCTAssertEqual(segmented.segments, [
            Segment(text: "去年", type: .time),
            Segment(text: " ", type: .unknown),
            Segment(text: "三月", type: .time)
        ])
        let filters = segmented.toFilters(now: makeNow())
        XCTAssertEqual(
            filters.explicit.timeRange,
            SearchTimeRange(startMs: ms(2025, 3, 1), endMs: ms(2025, 3, 31, 23, 59, 59, milli: 999))
        )
    }

    // MARK: - hasNarrowingExplicit（Layer 0.5 短路条件）

    func testHasNarrowingExplicit() {
        XCTAssertFalse(QuerySegmenter.segment("猫").hasNarrowingExplicit, "纯概念查询不短路")
        XCTAssertFalse(QuerySegmenter.segment("小孩").hasNarrowingExplicit, "纯人物查询不短路")
        XCTAssertTrue(QuerySegmenter.segment("上海").hasNarrowingExplicit, "LOCATION 段 → 短路")
        XCTAssertTrue(QuerySegmenter.segment("上周").hasNarrowingExplicit, "TIME 段 → 短路")
    }

    func testEmptyQuery() {
        let segmented = QuerySegmenter.segment("   ")
        XCTAssertEqual(segmented.segments, [])
        let filters = segmented.toFilters(now: makeNow())
        XCTAssertNil(filters.explicit.timeRange)
        XCTAssertNil(filters.explicit.hasFaces)
        XCTAssertEqual(filters.content.keywords, [])
    }

    // MARK: - SegmentType 语义（契约 §3.3）

    func testSegmentTypeClassification() {
        XCTAssertTrue(SegmentType.time.isExplicit)
        XCTAssertTrue(SegmentType.location.isExplicit)
        XCTAssertTrue(SegmentType.person.isExplicit)
        XCTAssertFalse(SegmentType.object.isExplicit)
        XCTAssertTrue(SegmentType.object.isContent)
        XCTAssertTrue(SegmentType.scene.isContent)
        XCTAssertTrue(SegmentType.activity.isContent)
        XCTAssertTrue(SegmentType.ocr.isContent)
        XCTAssertFalse(SegmentType.unknown.isExplicit)
        XCTAssertFalse(SegmentType.unknown.isContent)
    }
}
