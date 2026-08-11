import XCTest
@testable import PoLang
import SharedKit

/// ChatSearchMapping 映射单测（契约 §9.3）：
/// SearchIntent → SearchFilter 字段映射 + 时间词清洗；SearchMediaRow → IosSearchResultItem。
final class ChatSearchMappingTests: XCTestCase {

    private func makeIntent(
        query: String = "",
        timeRange: SharedKit.TimeRange? = nil,
        keywords: [String] = [],
        ocrKeywords: [String] = [],
        locationKeywords: [String] = [],
        personName: String? = nil,
        hasFaces: KotlinBoolean? = nil
    ) -> SearchIntent {
        SearchIntent(
            query: query,
            timeRange: timeRange,
            keywords: keywords,
            ocrKeywords: ocrKeywords,
            locationKeywords: locationKeywords,
            personName: personName,
            hasFaces: hasFaces
        )
    }

    // MARK: - SearchIntent → SearchFilter（契约 §9.3 字段一一对应）

    func testFilterMapsFieldsOneToOne() {
        let intent = makeIntent(
            query: "海边的小孩",
            timeRange: SharedKit.TimeRange(startMs: 100, endMs: 200),
            keywords: ["海边"],
            ocrKeywords: ["门票"],
            locationKeywords: ["三亚"],
            personName: "女儿",
            hasFaces: KotlinBoolean(bool: true)
        )
        let filter = ChatSearchMapping.filter(from: intent)
        XCTAssertEqual(filter.timeRange, SearchTimeRange(startMs: 100, endMs: 200))
        XCTAssertEqual(filter.keywords, ["海边"])
        XCTAssertEqual(filter.ocrKeywords, ["门票"])
        XCTAssertEqual(filter.locationKeywords, ["三亚"])
        XCTAssertEqual(filter.personName, "女儿")
        XCTAssertEqual(filter.hasFaces, true)
        XCTAssertFalse(filter.needsLlm, "chat 命令层已标准化，needsLlm 恒 false（契约 §9.3）")
    }

    func testFilterWithoutTimeRangeKeepsKeywordsUnsanitized() {
        // timeRange 为 nil 时不做时间词清洗（契约 §9.3：仅当 timeRange 非空才剔除）
        let intent = makeIntent(keywords: ["夏天", "海边"])
        let filter = ChatSearchMapping.filter(from: intent)
        XCTAssertEqual(filter.keywords, ["夏天", "海边"])
        XCTAssertNil(filter.timeRange)
    }

    // MARK: - 时间词清洗 sanitizeTimeKeywords（契约 §9.3）

    func testSanitizeRemovesTimeOnlyKeywordsWhenTimeRangePresent() {
        let intent = makeIntent(
            timeRange: SharedKit.TimeRange(startMs: 0, endMs: 1),
            keywords: ["夏天", "海边"],
            ocrKeywords: ["去年", "发票"],
            locationKeywords: ["4月", "上海"]
        )
        let filter = ChatSearchMapping.filter(from: intent)
        XCTAssertEqual(filter.keywords, ["海边"], "「夏天」是时间专属词，应剔除")
        XCTAssertEqual(filter.ocrKeywords, ["发票"], "「去年」应剔除")
        XCTAssertEqual(filter.locationKeywords, ["上海"], "「4月」命中 monthKeywordRegex，应剔除")
    }

    func testIsTimeOnlyKeywordCoversSetAndMonthRegex() {
        // 集合样本
        for word in ["去年", "今天", "上周", "下个月", "近3个月", "冬季"] {
            XCTAssertTrue(ChatSearchMapping.isTimeOnlyKeyword(word), "\(word) 应命中 timeOnlyKeywords")
        }
        // monthKeywordRegex：数字月 + 中文月
        for word in ["4月", "12月", "四月", "十二月"] {
            XCTAssertTrue(ChatSearchMapping.isTimeOnlyKeyword(word), "\(word) 应命中 monthKeywordRegex")
        }
        // 反例
        for word in ["海边", "夜景", "星期二", "月份", ""] {
            XCTAssertFalse(ChatSearchMapping.isTimeOnlyKeyword(word), "\(word) 不应命中")
        }
    }

    // MARK: - SearchMediaRow → IosSearchResultItem（双 id 口径）

    private func makeRow(
        id: Int64 = 42,
        localIdentifier: String = "ABC-123",
        type: String = "IMAGE",
        duration: Int64? = nil
    ) -> SearchMediaRow {
        SearchMediaRow(
            id: id,
            localIdentifier: localIdentifier,
            type: type,
            captureDate: 1_700_000_000_000,
            fileName: "IMG_0042.HEIC",
            duration: duration,
            hasFace: true,
            faceId: nil,
            labels: #"{"tags":["海边"]}"#,
            labelsEn: nil,
            labelsZh: nil,
            ocrText: "发票",
            latitude: nil,
            longitude: nil,
            locationName: "三亚",
            city: "三亚",
            indexedAt: nil,
            faceFocusY: nil,
            aestheticScore: nil,
            faceQualityScore: nil,
            semanticEmbedding: nil
        )
    }

    func testItemMapsRowFields() {
        let item = ChatSearchMapping.item(from: makeRow(duration: 5000))
        XCTAssertEqual(item.dbId, 42)   // Kotlin Long → Swift Int64，直接比较
        XCTAssertEqual(item.localIdentifier, "ABC-123")
        XCTAssertEqual(item.mediaType, "PHOTO", "iOS 扫描侧 type='IMAGE' → DTO 'PHOTO'")
        XCTAssertEqual(item.captureDateMs, 1_700_000_000_000)
        XCTAssertEqual(item.durationMs?.int64Value, 5000)
        XCTAssertEqual(item.fileName, "IMG_0042.HEIC")
        XCTAssertTrue(item.hasFace)
        XCTAssertEqual(item.labels, #"{"tags":["海边"]}"#)
        XCTAssertEqual(item.ocrText, "发票")
        XCTAssertEqual(item.locationName, "三亚")
        XCTAssertEqual(item.city, "三亚")
    }

    func testItemMapsVideoTypeAndNullables() {
        let item = ChatSearchMapping.item(from: makeRow(type: "VIDEO"))
        XCTAssertEqual(item.mediaType, "VIDEO")
        XCTAssertNil(item.durationMs)
    }
}
