import Foundation

// MARK: - 受控词表·搜索域（双端契约 SSOT: contracts.md §6.3）
//
// 从 bundle 资源 `controlled_vocab.json` 加载（与 Android assets 同一份文件）。
// ⚠️ 范围说明：Android `ControlledVocab.kt` 有 27 个顶层 key，其中 scene/objects/...
// 类别与 blocked_tags 服务于**打标写入侧**（Qwen 规范化 / MobileCLIP 候选），
// 搜索召回只消费 `synonyms` 映射（实测 83 条，如 美女→女性）及其反向分组
// `reverseSynonyms`（canonical → 所有 synonyms）。本类型当前只承载搜索域两个字段；
// 打标管线落地 iOS 时可在此扩展类别字段（对齐 Android 全量 schema）。

struct ControlledVocab {

    /// 同义词映射：非标准词 → 标准词（synonym → canonical）
    let synonyms: [String: String]

    /// 反向同义词映射：标准词 → 所有同义词（canonical → synonyms）。
    /// 派生规则对齐 Android：canonical ≠ synonym 才入（ControlledVocab.kt:132-140）。
    let reverseSynonyms: [String: [String]]

    init(synonyms: [String: String]) {
        self.synonyms = synonyms
        var rev: [String: [String]] = [:]
        for (synonym, canonical) in synonyms where synonym != canonical {
            rev[canonical, default: []].append(synonym)
        }
        self.reverseSynonyms = rev
    }

    static func empty() -> ControlledVocab {
        ControlledVocab(synonyms: [:])
    }

    /// 从 bundle 加载（对齐 Android loadFromAssets；资源缺失/解析失败 → nil，由调用方降级）。
    static func loadFromBundle(_ bundle: Bundle = .main) -> ControlledVocab? {
        guard let url = bundle.url(forResource: "controlled_vocab", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let vocab = parse(jsonData: data) else {
            print("⚠️ [ControlledVocab] controlled_vocab.json 加载失败，同义词扩展不可用")
            return nil
        }
        return vocab
    }

    /// 从 JSON 解析（测试入口；只读 `synonyms` key）。
    static func parse(jsonData: Data) -> ControlledVocab? {
        guard let root = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            return nil
        }
        return ControlledVocab(synonyms: (root["synonyms"] as? [String: String]) ?? [:])
    }
}
