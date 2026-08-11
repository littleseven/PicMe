import XCTest
@testable import PoLang

/// 跨语言扩展测试（contracts.md §6：BilingualVocab / ControlledVocab / TagTranslator）。
final class TagTranslatorTests: XCTestCase {

    private func makeVocab() -> BilingualVocab {
        let json = """
            {
              "_meta": {"note": "ignored"},
              "zh_to_en": {"猫": "cat", "狗": "dog"},
              "en_to_zh": {"cat": "猫", "car": "汽车"},
              "en_synonyms": {"kitty": "cat", "automobile": "car"}
            }
            """.data(using: .utf8)!
        return BilingualVocab.parse(jsonData: json)!
    }

    // MARK: - §6.2 expandForSearch 英文方向

    func testExpandEnglishDirection() {
        let t = TagTranslator(vocab: makeVocab())
        // enToZh 命中：normalized "cat" → += 中文 canonical
        XCTAssertEqual(t.expandForSearch(" Cat ", lang: "en"), ["cat", "猫"])
        // enSynonyms 链：kitty → 标准词 cat → enToZh[cat] = 猫
        XCTAssertEqual(t.expandForSearch("kitty", lang: "en"), ["kitty", "cat", "猫"])
        // 未命中 → 仅原词（normalized）
        XCTAssertEqual(t.expandForSearch("zebra", lang: "en"), ["zebra"])
    }

    // MARK: - §6.2 expandForSearch 中文方向

    func testExpandChineseDirection() {
        let cv = ControlledVocab(synonyms: ["大猫": "猫"])
        let t = TagTranslator(vocab: makeVocab(), controlledVocab: cv)
        // zhToEn 命中（注意：用原文不 lowercase 查表）→ += 英文.lowercase()；
        // 同时 canonical 输入 → reverseSynonyms 扩展 synonyms（"猫" → "大猫"）
        XCTAssertEqual(t.expandForSearch("猫", lang: "zh"), ["猫", "cat", "大猫"])
        // synonym 输入 → 扩展 canonical（zhToEn 未命中"大猫"，但同义词扩展 → 不走 MT）
        XCTAssertEqual(t.expandForSearch("大猫", lang: "zh"), ["大猫", "猫"])
    }

    func testExpandChineseMtFallback() {
        // zhToEn 未命中且无同义词 → mtTranslate 回退（注入 closure 模拟 OPUS-MT）
        let t = TagTranslator(vocab: makeVocab()) { input in
            input == "斑马" ? "Zebra" : nil
        }
        XCTAssertEqual(t.expandForSearch("斑马", lang: "zh"), ["斑马", "zebra"])
        // MT 返回与输入相同 → 质量校验拒绝
        let t2 = TagTranslator(vocab: makeVocab()) { $0 }
        XCTAssertEqual(t2.expandForSearch("斑马", lang: "zh"), ["斑马"])
        // MT 未注入 → 跳过该层（对齐 Android mtTranslator = null）
        let t3 = TagTranslator(vocab: makeVocab())
        XCTAssertEqual(t3.expandForSearch("斑马", lang: "zh"), ["斑马"])
    }

    func testExpandEmptyAndBlank() {
        let t = TagTranslator(vocab: makeVocab())
        XCTAssertTrue(t.expandForSearch("", lang: "zh").isEmpty)
        XCTAssertTrue(t.expandForSearch("   ", lang: "en").isEmpty)
    }

    // MARK: - §6.2 display

    func testDisplay() {
        let t = TagTranslator(vocab: makeVocab())
        XCTAssertEqual(t.display("猫", lang: "en"), "cat")
        XCTAssertEqual(t.display("猫", lang: "zh"), "猫", "非英文 UI 原样返回")
        XCTAssertEqual(t.display("未知", lang: "en"), "未知", "未命中词表回退原中文")
        XCTAssertEqual(t.displayAll(["猫", "狗"], lang: "en"), ["cat", "dog"])
    }

    // MARK: - §6.1/§6.3 bundle 资源加载（hosted 测试环境：Bundle.main = PoLang.app）

    func testBundleResourcesLoad() {
        let vocab = BilingualVocab.loadFromBundle()
        XCTAssertGreaterThan(vocab.zhToEn.count, 500, "契约 §6.1：zh_to_en 实测 619 条")
        XCTAssertGreaterThan(vocab.enToZh.count, 500, "契约 §6.1：en_to_zh 实测 686 条")
        XCTAssertGreaterThan(vocab.enSynonyms.count, 50, "契约 §6.1：en_synonyms 实测 79 条")
        // 与 Android 同一份文件的抽样锚点
        XCTAssertEqual(vocab.enToZh["Aircraft"], "飞机")

        let cv = ControlledVocab.loadFromBundle()
        XCTAssertNotNil(cv)
        XCTAssertGreaterThan(cv?.synonyms.count ?? 0, 80, "契约 §6.3：synonyms 实测 83 条")
        XCTAssertEqual(cv?.synonyms["美女"], "女性")
        XCTAssertEqual(cv?.reverseSynonyms["男性"]?.contains("帅哥"), true)
    }
}
