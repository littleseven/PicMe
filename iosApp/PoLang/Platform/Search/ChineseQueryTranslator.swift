import Foundation

// MARK: - ChineseQueryTranslator（双端契约 SSOT: contracts.md §5.6-§5.8）
//
// 中文查询 → MobileCLIP 英文候选扩展器，逐字移植 Android
// `ChineseQueryTranslator.kt:35-42, 99-180, 188-217, 232-258, 323-351`。
//
// 分层策略（契约 §5.6 translateForClip）：
// 1. 纯英文（无中文字符）→ 原样返回
// 2. BilingualVocab 整句精确匹配 zhToEn[query]（空串值视为未命中）
// 3. 分词后逐词匹配（最大正向匹配，最长 8 字；停用词跳过、英文词保留、中文词查 zhToEn；
//    所有词都命中才返回空格连接的组合，否则落空）
// 4. OPUS-MT 模型翻译 + 质量校验 —— ⚠️ iOS 1.0 不可用（词表-only 降级，见下方说明）
// 5. 兜底：返回原查询
//
// expandForClip（契约 §5.6，语义搜索实际用）：linkedSetOf(
//   translateForClip 结果（若过质量校验）→ CLIP_QUERY_EXPANSIONS[query]（§5.7 硬编码 26 条）
//   → ControlledVocab 双向同义词翻译（各自过质量校验）)；空则 [translateForClip 结果]。
//
// ⚠️ OPUS-MT 降级说明（contracts §13 第 6 项明确允许分阶段：「先词表 + 硬编码扩展，
//    后补 OPUS-MT 模型」）：iOS 当前无 OPUS-MT Marian 推理栈（encoder/decoder/SentencePiece
//    均未移植），`tryModelTranslate` 恒落空，词表未命中时走兜底返回原查询——与 Android
//    OPUS-MT 模型未下载时的行为完全一致（Android 同为懒加载失败 → null → 兜底）。
//
// 词表来源（bundle 资源，与 Android assets 同一份文件；加载复用 B 任务交付的
// BilingualVocab/ControlledVocab，本类不重复实现 JSON 解析）：
// - `tag_translations.json` → `zh_to_en`（619 条；§6.1，`_meta` 忽略）
// - `controlled_vocab.json` → `synonyms`（83 条；§6.3；reverseSynonyms 由
//   ControlledVocab 反向分组派生，canonical ≠ synonym 才入）

final class ChineseQueryTranslator {

    // MARK: - 常量（契约 §5.6/§5.7 照抄）

    /// 翻译器停用词（契约 §5.6 照抄，29 词）
    static let stopWords: Set<String> = [
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
        "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
        "你", "会", "着", "没有", "看", "好", "自己", "这", "那"
    ]

    /// CLIP_QUERY_EXPANSIONS 硬编码表（契约 §5.7 全量照抄，26 条）。
    static let clipQueryExpansions: [String: [String]] = [
        // 人物查询（需要 CLIP 特化的英文短语）
        "小孩": ["child", "kid", "children", "young child"],
        "儿童": ["child", "children", "young child"],
        "婴儿": ["baby", "infant", "newborn"],
        "宝宝": ["baby", "toddler", "infant"],
        "孩子": ["child", "kid", "children"],
        "男孩": ["boy", "little boy", "young boy"],
        "女孩": ["girl", "little girl", "young girl"],
        "美女": ["beautiful woman", "woman", "female", "portrait of a woman"],
        "帅哥": ["handsome man", "man", "male", "portrait of a man"],
        "女人": ["woman", "female", "adult woman"],
        "男人": ["man", "male", "adult man"],
        "女士": ["woman", "lady", "female"],
        "男士": ["man", "gentleman", "male"],
        "男生": ["boy", "young man", "male"],
        "女生": ["girl", "young woman", "female"],
        "男性": ["male", "man"],
        "女性": ["female", "woman"],
        // 常见物体/场景
        "猫": ["cat", "kitten"],
        "狗": ["dog", "puppy"],
        "花": ["flower", "blossom"],
        "海边": ["beach", "seaside", "shore"],
        "日落": ["sunset", "dusk"],
        "山": ["mountain", "hill"],
        "美食": ["food", "cuisine", "delicious food"],
        "大美女": ["beautiful woman", "woman", "female", "portrait of a woman"],
        "大帅哥": ["handsome man", "man", "male", "portrait of a man"]
    ]

    // MARK: - 词表

    /// 中→英词表（tag_translations.json `zh_to_en`，经 BilingualVocab 加载）
    private let zhToEn: [String: String]
    /// 受控词表同义词（controlled_vocab.json `synonyms`：synonym → canonical）
    private let synonyms: [String: String]
    /// 反向同义词（canonical → [synonyms]；canonical ≠ synonym 才入，契约 §6.3，
    /// 由 ControlledVocab 派生）
    private let reverseSynonyms: [String: [String]]

    // MARK: - 初始化

    /// - Parameters:
    ///   - vocab: 双语词表（默认从 bundle `tag_translations.json` 加载）
    ///   - controlledVocab: 受控词表（默认从 bundle `controlled_vocab.json` 加载；nil → 无同义词扩展）
    init(vocab: BilingualVocab = .loadFromBundle(),
         controlledVocab: ControlledVocab? = ControlledVocab.loadFromBundle()) {
        self.zhToEn = vocab.zhToEn
        self.synonyms = controlledVocab?.synonyms ?? [:]
        self.reverseSynonyms = controlledVocab?.reverseSynonyms ?? [:]
    }

    /// 测试便捷构造：直接注入字典。
    convenience init(zhToEn: [String: String], synonyms: [String: String]) {
        self.init(vocab: BilingualVocab(zhToEn: zhToEn, enToZh: [:], enSynonyms: [:]),
                  controlledVocab: ControlledVocab(synonyms: synonyms))
    }

    // MARK: - §5.6 translateForClip

    /// 将用户查询转换为 MobileCLIP 友好的英文查询。
    /// 词表未命中且无 OPUS-MT（iOS 降级）→ 兜底返回原查询。
    func translateForClip(_ query: String) -> String {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return trimmed }

        // 1. 纯英文查询直接返回
        if !Self.containsChinese(trimmed) { return trimmed }

        // 2. 词表查找（整句精确 → 分词逐词）
        if let vocabResult = tryVocabLookup(trimmed) { return vocabResult }

        // 3. OPUS-MT 模型翻译 —— iOS 1.0 不可用（词表-only 降级，同 Android 模型未下载行为）
        //    契约 §13 第 6 项允许分阶段实现；后续补 OPUS-MT 栈后在此接入 + isTranslationValid 校验。

        // 4. 兜底：保留原查询（MobileCLIP 对部分中文有弱泛化）
        return trimmed
    }

    // MARK: - §5.6 expandForClip（语义搜索实际用）

    /// 将用户查询扩展为多个 MobileCLIP 英文候选（去重、保序）。
    /// 纯英文 → [query]；否则候选 = 有序集（translateForClip 有效结果 → 硬编码扩展 → 同义词翻译）；
    /// 全落空 → [translateForClip 结果]。
    func expandForClip(_ query: String) -> [String] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return [] }

        // 纯英文：直接返回原查询
        if !Self.containsChinese(trimmed) { return [trimmed] }

        let translated = translateForClip(trimmed)
        let hardcodedExpansions = Self.clipQueryExpansions[trimmed]
        let synonymTranslations = expandViaControlledVocab(trimmed)
        let translationValid = Self.isTranslationValid(input: trimmed, output: translated)

        // linkedSetOf 语义：去重 + 保插入序
        var seen = Set<String>()
        var candidates: [String] = []
        func appendUnique(_ s: String) {
            if seen.insert(s).inserted { candidates.append(s) }
        }

        // 1. 基础翻译（如果有效）
        if translationValid { appendUnique(translated) }
        // 2. 硬编码 CLIP 优化扩展表（最高质量）
        if let hardcodedExpansions { hardcodedExpansions.forEach(appendUnique) }
        // 3. 动态同义词翻译（ControlledVocab）
        synonymTranslations.forEach(appendUnique)

        return candidates.isEmpty ? [translated] : candidates
    }

    // MARK: - §5.6 中文检测

    /// 文本是否含中文字符（[\u4e00-\u9fff]）
    static func containsChinese(_ text: String) -> Bool {
        text.unicodeScalars.contains { $0.value >= 0x4e00 && $0.value <= 0x9fff }
    }

    // MARK: - 词表查找（Android tryVocabLookup:232-258）

    /// 1. 整句精确匹配（空串值视为未命中）；2. 分词后逐词匹配，全部命中才组合返回。
    private func tryVocabLookup(_ query: String) -> String? {
        // 1. 整句精确匹配
        if let exact = zhToEn[query], !exact.isEmpty { return exact }

        // 2. 分词后逐词匹配
        let words = segmentQuery(query)
        let translatedWords: [String?] = words.map { word in
            if Self.stopWords.contains(word) { return nil }          // 跳过停用词
            if !Self.containsChinese(word) { return word }           // 保留英文词
            return zhToEn[word]                                      // 查词表（可能 nil）
        }

        // 所有词都命中（translated != nil 或该词是停用词）才组合
        let allMatched = zip(words, translatedWords).allSatisfy { word, translated in
            translated != nil || Self.stopWords.contains(word)
        }
        guard allMatched else { return nil }
        let result = translatedWords.compactMap { $0 }.joined(separator: " ")
        return result.isEmpty ? nil : result
    }

    // MARK: - ControlledVocab 同义词扩展（Android expandViaControlledVocab:188-217）

    /// 方向1: query 是 synonym → 翻译其 canonical；方向2: query 是 canonical → 翻译其所有
    /// synonyms（各自过质量校验，且翻译结果须不等于原词）。
    private func expandViaControlledVocab(_ query: String) -> [String] {
        var results: [String] = []

        if let canonical = synonyms[query], canonical != query {
            let canonicalTranslated = translateForClip(canonical)
            if canonicalTranslated != canonical,
               Self.isTranslationValid(input: canonical, output: canonicalTranslated) {
                results.append(canonicalTranslated)
            }
        }

        if let syns = reverseSynonyms[query] {
            for syn in syns where syn != query {
                let synTranslated = translateForClip(syn)
                if synTranslated != syn,
                   Self.isTranslationValid(input: syn, output: synTranslated) {
                    results.append(synTranslated)
                }
            }
        }
        return results
    }

    // MARK: - §5.8 翻译质量校验（Android isTranslationValid:290-315 照抄）

    /// 拒绝条件（任一命中即 invalid）：output 空白；output == input；含噪音字符 ♪ ⁇ ▁；
    /// 按 [^a-z]+ 切词后拟声词占比 > 0.5；含 god/gosh/jeez/lord 任一词；
    /// 纯字母部分 < 2 字符；纯字母去重后只剩 1 种字符。
    static func isTranslationValid(input: String, output: String) -> Bool {
        if output.trimmingCharacters(in: .whitespaces).isEmpty { return false }
        if output == input { return false }

        // 噪音字符
        if output.contains("♪") || output.contains("⁇") || output.contains("▁") { return false }

        // 拟声词占比 > 0.5
        let interjectionWords: Set<String> = ["ooh", "oh", "ho", "ah", "uh", "um", "ha", "heh"]
        let words = output.lowercased()
            .components(separatedBy: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz").inverted)
            .filter { !$0.isEmpty }
        if words.isEmpty { return false }
        let interjectionCount = words.filter { interjectionWords.contains($0) }.count
        if Float(interjectionCount) / Float(words.count) > 0.5 { return false }

        // 宗教/感叹口头禅
        let meaninglessExclamations: Set<String> = ["god", "gosh", "jeez", "lord"]
        if words.contains(where: { meaninglessExclamations.contains($0) }) { return false }

        // 纯字母部分 < 2 字符；纯字母去重只剩 1 种
        let clean = output.filter { $0.isLetter }
        if clean.count < 2 { return false }
        if Set(clean).count == 1 { return false }

        return true
    }

    // MARK: - 分词（Android segmentQuery:323-351，词典 + 最大正向匹配）

    /// 最大正向匹配分词：词表 = zhToEn keys ∪ 停用词，最长 8 字；未命中按单字切分。
    private func segmentQuery(_ query: String) -> [String] {
        var result: [String] = []
        var remaining = Substring(query)

        while !remaining.isEmpty {
            var matched = false
            let maxLen = min(remaining.count, 8)
            for len in stride(from: maxLen, through: 1, by: -1) {
                let sub = String(remaining.prefix(len))
                if zhToEn[sub] != nil || Self.stopWords.contains(sub) {
                    result.append(sub)
                    remaining = remaining.dropFirst(len)
                    matched = true
                    break
                }
            }
            if !matched {
                result.append(String(remaining.prefix(1)))
                remaining = remaining.dropFirst(1)
            }
        }
        return result
    }
}
