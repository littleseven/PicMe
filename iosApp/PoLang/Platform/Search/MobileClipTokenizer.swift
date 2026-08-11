import Foundation

// MARK: - MobileClipTokenizer（双端契约 SSOT: contracts.md §5.3 tokenizer 小节）
//
// MobileCLIP-S2 文本 BPE tokenizer，逐字移植 Android `MobileClipTokenizer.kt:28-421`。
// 词表随 `mobileclip-onnx` 模型包下载到 `Documents/llm_models/mobileclip-onnx/tokenizer.json`
// （ModelScope budaoshou/MobileCLIP-ONNX，fp32，与 Android 同一份文件）。
//
// 编码流程（契约 §5.3）：
// 1. normalize：NFC → trim → lowercase → 合并连续空白为单空格
// 2. pre-tokenize 正则（照抄）：'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+
// 3. BPE：GPT-2 byte-encoder（bytes_to_unicode），词尾附加 `</w>`（CLIP 风格），按 merges rank 贪心合并
// 4. 组装：[BOS] + tokens + [EOS]，截断到 77（保留末尾 EOS：取前 76 + EOS），不足补 PAD(0)
//
// 特殊 token 默认（契约 §5.3）：BOS `<|startoftext|>` = 49406，EOS `<|endoftext|>` = 49407，
// PAD = 0，context 77；tokenizer.json `added_tokens` 中的实际 id 可覆盖默认值。
//
// 加载：优先 tokenizer.json（HF 标准格式：model.vocab + model.merges + added_tokens），
// 回退 vocab.txt + merges.txt（传统 BPE 格式）。

final class MobileClipTokenizer {

    // MARK: - 常量（契约 §5.3，逐字照抄 Android MobileClipTokenizer.kt:32-41）

    /// 默认 BOS token id（<|startoftext|>）
    static let defaultBosId: Int64 = 49406
    /// 默认 EOS token id（<|endoftext|>）
    static let defaultEosId: Int64 = 49407
    /// 默认 PAD token id
    static let defaultPadId: Int64 = 0
    /// 默认上下文长度（MAX_TEXT_TOKENS）
    static let defaultContextLength: Int = 77

    // MARK: - 状态

    /// tokenizer.json 所在目录（mobileclip-onnx 模型目录）
    private let modelDir: URL

    /// token → id 映射表
    private var vocab: [String: Int64] = [:]
    /// id → token 反向映射（调试用）
    private var idToToken: [Int64: String] = [:]
    /// BPE 合并规则 → 优先级（rank 越小越优先）
    private var mergeRanks: [MergePair: Int] = [:]

    /// 特殊 token 配置（可被 tokenizer.json added_tokens 覆盖）
    private(set) var bosTokenId: Int64 = defaultBosId
    private(set) var eosTokenId: Int64 = defaultEosId
    private(set) var padTokenId: Int64 = defaultPadId

    /// 是否已加载
    private(set) var isLoaded = false

    /// 字节到 Unicode 字符的映射（GPT-2 / CLIP bytes_to_unicode）
    private lazy var byteEncoder: [UInt8: String] = Self.buildByteEncoder()

    /// 预编译的 pre-tokenize 正则（契约 §5.3 照抄）。
    /// NSRegularExpression 的 `\p{L}`/`\p{N}` 语义与 Java `[\p{L}]` 字符类一致。
    private lazy var preTokenizeRegex: NSRegularExpression? = {
        try? NSRegularExpression(pattern: #"'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+"#)
    }()

    /// BPE pair 键（Swift Tuple 不可哈希，用结构体代替 Kotlin Pair）
    private struct MergePair: Hashable {
        let first: String
        let second: String
    }

    // MARK: - 初始化

    /// - Parameter modelDir: `mobileclip-onnx` 模型目录（含 tokenizer.json / vocab.txt + merges.txt）
    init(modelDir: URL) {
        self.modelDir = modelDir
    }

    // MARK: - 加载（Android MobileClipTokenizer.kt:87-210）

    /// 加载 tokenizer（幂等；优先 tokenizer.json，回退 vocab.txt + merges.txt）
    @discardableResult
    func load() -> Bool {
        if isLoaded { return true }
        let tokenizerJson = modelDir.appendingPathComponent("tokenizer.json")
        if FileManager.default.fileExists(atPath: tokenizerJson.path) {
            return loadFromTokenizerJson(tokenizerJson)
        }
        return loadFromVocabAndMerges()
    }

    /// 从 tokenizer.json 加载（HF 标准格式：model.vocab / model.merges / added_tokens）
    private func loadFromTokenizerJson(_ url: URL) -> Bool {
        do {
            let data = try Data(contentsOf: url)
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let modelObj = json["model"] as? [String: Any],
                  let vocabObj = modelObj["vocab"] as? [String: Any] else {
                return false
            }

            // 1. vocab
            for (token, idValue) in vocabObj {
                let id = Self.jsonInt64(idValue)
                vocab[token] = id
                idToToken[id] = token
            }

            // 2. merges（"a b" 两元组字符串数组，跳过空行与 # 注释）
            var merges: [(String, String)] = []
            if let mergesArray = modelObj["merges"] as? [Any] {
                for item in mergesArray {
                    guard let mergeStr = (item as? String)?.trimmingCharacters(in: .whitespaces),
                          !mergeStr.isEmpty, !mergeStr.hasPrefix("#") else { continue }
                    let parts = mergeStr.split(separator: " ", omittingEmptySubsequences: false)
                    if parts.count == 2 {
                        merges.append((String(parts[0]), String(parts[1])))
                    }
                }
            }
            mergeRanks.removeAll()
            for (index, pair) in merges.enumerated() {
                mergeRanks[MergePair(first: pair.0, second: pair.1)] = index
            }

            // 3. added_tokens（可覆盖 BOS/EOS/PAD 默认 id）
            if let addedTokens = json["added_tokens"] as? [[String: Any]] {
                for tokenObj in addedTokens {
                    guard let token = tokenObj["content"] as? String,
                          let idValue = tokenObj["id"] else { continue }
                    let id = Self.jsonInt64(idValue)
                    vocab[token] = id
                    idToToken[id] = token
                    switch token {
                    case "<|startoftext|>": bosTokenId = id
                    case "<|endoftext|>": eosTokenId = id
                    case "<|pad|>": padTokenId = id
                    default: break
                    }
                }
            }

            isLoaded = true
            #if DEBUG
            print("[MobileClipTokenizer] loaded from tokenizer.json: vocab=\(vocab.count), merges=\(merges.count)")
            #endif
            return true
        } catch {
            #if DEBUG
            print("[MobileClipTokenizer] failed to load tokenizer.json: \(error)")
            #endif
            return false
        }
    }

    /// 从 vocab.txt + merges.txt 加载（传统 BPE 格式回退）
    private func loadFromVocabAndMerges() -> Bool {
        do {
            let vocabFile = modelDir.appendingPathComponent("vocab.txt")
            if FileManager.default.fileExists(atPath: vocabFile.path) {
                let text = try String(contentsOf: vocabFile, encoding: .utf8)
                var index: Int64 = 0
                for line in text.components(separatedBy: .newlines) {
                    let token = line.trimmingCharacters(in: .whitespaces)
                    if !token.isEmpty {
                        vocab[token] = index
                        idToToken[index] = token
                    }
                    // ⚠️ 对齐 Android forEachIndexed：行号即 id（含空行占位）
                    index += 1
                }
            }

            var merges: [(String, String)] = []
            let mergesFile = modelDir.appendingPathComponent("merges.txt")
            if FileManager.default.fileExists(atPath: mergesFile.path) {
                let text = try String(contentsOf: mergesFile, encoding: .utf8)
                for line in text.components(separatedBy: .newlines) {
                    let trimmed = line.trimmingCharacters(in: .whitespaces)
                    guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else { continue }
                    let parts = trimmed.split(separator: " ", omittingEmptySubsequences: false)
                    if parts.count == 2 {
                        merges.append((String(parts[0]), String(parts[1])))
                    }
                }
            }
            mergeRanks.removeAll()
            for (index, pair) in merges.enumerated() {
                mergeRanks[MergePair(first: pair.0, second: pair.1)] = index
            }

            isLoaded = true
            #if DEBUG
            print("[MobileClipTokenizer] loaded from vocab.txt + merges.txt: vocab=\(vocab.count), merges=\(merges.count)")
            #endif
            return true
        } catch {
            #if DEBUG
            print("[MobileClipTokenizer] failed to load vocab/merges: \(error)")
            #endif
            return false
        }
    }

    // MARK: - 编码（Android MobileClipTokenizer.kt:220-261）

    /// 文本 → token ID 数组（固定长 contextLength，BOS/EOS/PAD 组装规则见契约 §5.3）。
    /// - Parameters:
    ///   - text: 用户输入文本
    ///   - contextLength: 最大序列长度（默认 77）
    ///   - addSpecialTokens: 是否添加 BOS/EOS
    /// - Returns: token IDs（长度 == contextLength），tokenizer 未加载或编码异常返回 nil
    func encode(_ text: String,
                contextLength: Int = defaultContextLength,
                addSpecialTokens: Bool = true) -> [Int64]? {
        if !isLoaded && !load() {
            #if DEBUG
            print("[MobileClipTokenizer] not loaded")
            #endif
            return nil
        }

        // 1. normalize：NFC → trim → lowercase → 合并连续空白为单空格
        let normalized = Self.normalizeText(text)

        // 2. pre-tokenize
        let words = preTokenize(normalized)

        // 3. BPE 编码 + 特殊 token 组装
        var tokenIds: [Int64] = []
        if addSpecialTokens { tokenIds.append(bosTokenId) }
        for word in words {
            tokenIds.append(contentsOf: bpeEncode(word))
        }
        if addSpecialTokens { tokenIds.append(eosTokenId) }

        // 4. 截断或填充到固定长度
        return padOrTruncate(tokenIds, maxLength: contextLength)
    }

    /// 文本规范化（契约 §5.3：NFC → trim → **lowercase** → 合并连续空白为单空格）。
    ///
    /// MobileCLIP 配套 tokenizer.json 的 normalizer 明确包含 Lowercase，vocab 中也只包含
    /// 小写形式（如 "usb</w>"），必须 lowercase，否则大写词全部退化为字节级 token。
    static func normalizeText(_ text: String) -> String {
        let nfc = text.precomposedStringWithCanonicalMapping
        let trimmed = nfc.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowered = trimmed.lowercased()
        // \s+ → 单空格
        return lowered.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    /// 预分词：按 CLIP Split pattern 切分；非空白匹配产出 pretoken。
    private func preTokenize(_ text: String) -> [String] {
        guard let regex = preTokenizeRegex else { return [] }
        let ns = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
        var words: [String] = []
        for m in matches {
            let match = ns.substring(with: m.range)
            if !match.trimmingCharacters(in: .whitespaces).isEmpty {
                words.append(match)
            }
        }
        return words
    }

    /// 对单个 pretoken 进行 BPE 编码（CLIP 风格：结尾附加 `</w>`）。
    private func bpeEncode(_ token: String) -> [Int64] {
        guard !token.isEmpty else { return [] }

        // 1. Byte encoder：UTF-8 字节 → GPT-2 / CLIP 风格 Unicode 字符
        var encodedChars: [String] = Array(token.utf8).map { byteEncoder[$0] ?? "" }
        guard !encodedChars.isEmpty else { return [] }

        // 2. CLIP BPE：先把 `</w>` 附加到最后一个字符，再参与 BPE 合并
        encodedChars[encodedChars.count - 1] += "</w>"

        // 3. 标准 BPE 合并
        let bpeTokens = bpeMerge(encodedChars)

        // 4. 映射为 token IDs（未知 token 跳过，与 Android 一致仅告警）
        var tokenIds: [Int64] = []
        for bpeToken in bpeTokens {
            if let id = vocab[bpeToken] {
                tokenIds.append(id)
            } else {
                #if DEBUG
                print("[MobileClipTokenizer] unknown BPE token: '\(bpeToken)' in '\(token)'")
                #endif
            }
        }
        return tokenIds
    }

    /// 标准 BPE 合并（Android MobileClipTokenizer.kt:334-374，与 HF tokenizers BPE 一致）：
    /// 每轮选 rank 最小的相邻 pair，整词全部合并，直到无可合并 pair。
    private func bpeMerge(_ tokens: [String]) -> [String] {
        if tokens.count <= 1 { return tokens }

        func getPairs(_ word: [String]) -> Set<MergePair> {
            var pairs = Set<MergePair>()
            var prev = word[0]
            for i in 1..<word.count {
                pairs.insert(MergePair(first: prev, second: word[i]))
                prev = word[i]
            }
            return pairs
        }

        var word = tokens
        var pairs = getPairs(word)
        while !pairs.isEmpty {
            // 选 rank 最小（最高优先级）的可合并 pair
            var best: MergePair?
            var bestRank = Int.max
            for p in pairs {
                let rank = mergeRanks[p] ?? Int.max
                if rank < bestRank {
                    bestRank = rank
                    best = p
                }
            }
            guard let (first, second) = best.map({ ($0.first, $0.second) }),
                  mergeRanks[MergePair(first: first, second: second)] != nil else { break }

            var newWord: [String] = []
            var i = 0
            while i < word.count {
                if i < word.count - 1 && word[i] == first && word[i + 1] == second {
                    newWord.append(first + second)
                    i += 2
                } else {
                    newWord.append(word[i])
                    i += 1
                }
            }
            word = newWord
            if word.count <= 1 { break }
            pairs = getPairs(word)
        }
        return word
    }

    /// 截断或填充到固定长度（Android MobileClipTokenizer.kt:385-391）：
    /// 超长取前 maxLength-1 个 + EOS；不足补 PAD。
    private func padOrTruncate(_ tokenIds: [Int64], maxLength: Int) -> [Int64] {
        if tokenIds.count > maxLength {
            return Array(tokenIds.prefix(maxLength - 1)) + [eosTokenId]
        } else if tokenIds.count < maxLength {
            return tokenIds + [Int64](repeating: padTokenId, count: maxLength - tokenIds.count)
        }
        return tokenIds
    }

    // MARK: - bytes_to_unicode（Android MobileClipTokenizer.kt:400-421，GPT-2 标准实现）

    /// 构建字节 → Unicode 字符映射表（OpenAI GPT-2 bytes_to_unicode）：
    /// 可打印 ASCII + 部分 Latin-1 保持原码点，其余字节按序映射到 256+ 码点。
    private static func buildByteEncoder() -> [UInt8: String] {
        var initialBytes: [Int] = []
        for b in Int(Character("!").asciiValue!)...Int(Character("~").asciiValue!) { initialBytes.append(b) } // 33...126
        for b in 0xA1...0xAC { initialBytes.append(b) } // ¡...¬
        for b in 0xAE...0xFF { initialBytes.append(b) } // ®...ÿ

        var bytes = initialBytes
        var chars = initialBytes
        var n = 0
        for b in 0..<256 {
            if !initialBytes.contains(b) {
                bytes.append(b)
                chars.append(256 + n)
                n += 1
            }
        }

        var encoder: [UInt8: String] = [:]
        for (index, byte) in bytes.enumerated() {
            encoder[UInt8(byte)] = String(Character(UnicodeScalar(chars[index])!))
        }
        return encoder
    }

    // MARK: - 调试辅助

    /// vocab 大小（调试用）
    var vocabSize: Int { vocab.count }

    /// JSON 数值 → Int64（NSNumber 兼容）
    private static func jsonInt64(_ value: Any) -> Int64 {
        if let n = value as? NSNumber { return n.int64Value }
        if let s = value as? String, let n = Int64(s) { return n }
        return 0
    }
}
