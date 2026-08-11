import Foundation

// MARK: - TAG 运行时翻译器（双端契约 SSOT: contracts.md §6.2）
//
// 逐字对齐 Android `app/domain/tag/i18n/TagTranslator.kt:39-149`。
// 职责：展示翻译（中文 canonical TAG → 界面语言）+ 搜索扩展（查询词 → 多语言候选集）。
// 分层策略（搜索扩展路径，按优先级）：
// 1. BilingualVocab 词表精确匹配 → 英文同义词链（零耗时）
// 2. ControlledVocab 中文同义词双向扩展（零耗时）
// 3. OPUS-MT 模型翻译回退（mtTranslate 可选注入；iOS 模型路径属契约清单第 6 项分阶段实现，
//    未注入时跳过该层——对齐 Android mtTranslator = null 行为）
// 4. 保留原词兜底（normalized 恒在结果首位）
//
// lang 约定与 QueryParser 一致："en" → 英文方向，其余（含 "zh"）→ 中文方向。

final class TagTranslator {

    /// 生产单例：从 bundle 加载两份词表（资源与 Android assets 同文件）。
    static let shared = TagTranslator(
        vocab: .loadFromBundle(),
        controlledVocab: .loadFromBundle())

    private let vocab: BilingualVocab
    private let controlledVocab: ControlledVocab?
    /// OPUS-MT zh→en 翻译回退（可选注入；对齐 Android OpusMtTranslator?）。
    private let mtTranslate: ((String) -> String?)?

    init(vocab: BilingualVocab,
         controlledVocab: ControlledVocab? = nil,
         mtTranslate: ((String) -> String?)? = nil) {
        self.vocab = vocab
        self.controlledVocab = controlledVocab
        self.mtTranslate = mtTranslate
    }

    // MARK: - 展示翻译（契约 §6.2 display）

    /// 把中文 canonical TAG 翻译成目标语言；lang != "en" → 原样；未命中词表回退原中文。
    func display(_ chineseTag: String, lang: String) -> String {
        if lang.lowercased() != "en" { return chineseTag }
        return vocab.zhToEn[chineseTag] ?? chineseTag
    }

    /// 批量展示翻译。
    func displayAll(_ tags: [String], lang: String) -> [String] {
        tags.map { display($0, lang: lang) }
    }

    // MARK: - 搜索扩展（契约 §6.2 expandForSearch，逐字照抄）

    /// 把用户输入的查询词扩展为候选词集合（保序去重，对齐 Kotlin linkedSetOf）。
    ///
    /// 英文 UI 输入 "cat" → ["cat", "猫"]；中文 UI 输入 "猫" → ["猫", "cat"]（词表命中）
    /// 或 ["猫", "<MT翻译>"]（词表未命中时模型回退）。
    func expandForSearch(_ query: String, lang: String) -> [String] {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if normalized.isEmpty { return [] }

        var result = [normalized]
        func append(_ s: String) {
            if !result.contains(s) { result.append(s) }
        }

        if lang.lowercased() == "en" {
            // 英文标准词 -> 中文 canonical
            if let zh = vocab.enToZh[normalized] { append(zh) }
            // 英文同义词 -> 英文标准词 -> 中文 canonical
            if let standardEn = vocab.enSynonyms[normalized] {
                append(standardEn)
                if let zh = vocab.enToZh[standardEn] { append(zh) }
            }
        } else {
            // 注意：中文方向用原文（不 lowercase）查 zhToEn
            let rawQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)

            // 1. 中文 canonical -> 英文标准词
            let zhToEnHit = vocab.zhToEn[rawQuery]
            if let hit = zhToEnHit { append(hit.lowercased()) }

            // 2. 中文同义词双向扩展（ControlledVocab，零耗时）：
            //    输入是 synonym（如"美女"）→ 扩展 canonical（"女性"）；
            //    输入是 canonical（如"女性"）→ 扩展所有 synonyms
            if let cv = controlledVocab {
                if let canonical = cv.synonyms[rawQuery], canonical != rawQuery {
                    append(canonical)
                }
                if let syns = cv.reverseSynonyms[rawQuery] {
                    for syn in syns where syn != rawQuery { append(syn) }
                }
            }

            // 3. 无词表命中且无同义词扩展 → OPUS-MT 模型翻译（过 blank/==input/长度<2 校验）
            if zhToEnHit == nil && result.count == 1, let translated = mtTranslate?(rawQuery) {
                if translated.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || translated == rawQuery || translated.count < 2 {
                    print("⚠️ [TagTranslator] MT result rejected for '\(rawQuery)': '\(translated)'")
                } else {
                    append(translated.lowercased())
                }
            }
        }
        return result
    }
}
