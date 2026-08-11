import Foundation

// MARK: - 本地双语 TAG 词表（双端契约 SSOT: contracts.md §6.1）
//
// 逐字对齐 Android `app/domain/tag/i18n/BilingualVocab.kt`：
// 从 bundle 资源 `tag_translations.json` 加载（与 Android assets 同一份文件，
// 已 diff 校验一致）。顶层 4 key 中 `_meta` 忽略，只读三个 map key：
// - zh_to_en：中文标准词 → 英文标准词（实测 619 条）
// - en_to_zh：英文标准词 → 中文标准词（实测 686 条）
// - en_synonyms：英文口语/同义词 → 英文标准词（实测 79 条）
//
// 用途：展示时把中文 TAG 翻译成界面语言；搜索时把英文查询扩展为中文 canonical 词。
// 加载失败降级为空词表（系统仍可用，只是无翻译）。

struct BilingualVocab {

    let zhToEn: [String: String]
    let enToZh: [String: String]
    let enSynonyms: [String: String]

    static func empty() -> BilingualVocab {
        BilingualVocab(zhToEn: [:], enToZh: [:], enSynonyms: [:])
    }

    /// 从 bundle 加载双语词表（对齐 Android loadFromAssets；资源缺失/解析失败 → 空词表）。
    static func loadFromBundle(_ bundle: Bundle = .main) -> BilingualVocab {
        guard let url = bundle.url(forResource: "tag_translations", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let vocab = parse(jsonData: data) else {
            print("⚠️ [BilingualVocab] tag_translations.json 加载失败，降级为空词表")
            return .empty()
        }
        return vocab
    }

    /// 从 JSON 解析（测试入口，对齐 Android parseJson；`_meta` 忽略）。
    static func parse(jsonData: Data) -> BilingualVocab? {
        guard let root = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            return nil
        }
        func stringMap(_ key: String) -> [String: String] {
            (root[key] as? [String: String]) ?? [:]
        }
        return BilingualVocab(
            zhToEn: stringMap("zh_to_en"),
            enToZh: stringMap("en_to_zh"),
            enSynonyms: stringMap("en_synonyms"))
    }
}
