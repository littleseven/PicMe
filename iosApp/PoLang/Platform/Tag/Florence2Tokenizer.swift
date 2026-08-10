import Foundation

/// Florence-2 BART BPE tokenizer（仅解码方向）。
///
/// 移植自 Android `Florence2Tokenizer.kt`（91 行）。
/// Task prompt 的 token ids 已在 Florence2Tagger 中硬编码（预计算），
/// 这里只做输出解码：token ids → 文本字符串。
///
/// 从 vocab.json 加载 id→token 映射，用 BPE 规则合并子词（Ġ→空格）。
final class Florence2Tokenizer {

    /// id → token 字符串（从 vocab.json + added_tokens.json 加载）
    private var idToToken: [Int64: String] = [:]

    private(set) var isLoaded = false

    /// 从模型目录加载 vocab.json + added_tokens.json。
    /// - Parameter modelDir: Florence-2 模型目录路径
    /// - Returns: 是否成功加载
    @discardableResult
    func load(modelDir: String) -> Bool {
        if isLoaded { return true }

        // vocab.json: {token: id}，反转成 {id: token}
        let vocabURL = URL(fileURLWithPath: modelDir).appendingPathComponent("vocab.json")
        guard let vocabData = try? Data(contentsOf: vocabURL),
              let vocab = try? JSONSerialization.jsonObject(with: vocabData) as? [String: Int] else {
            NSLog("PoLang:Florence2Tok Failed to load vocab.json at \(vocabURL.path)")
            return false
        }

        var map: [Int64: String] = [:]
        map.reserveCapacity(vocab.count)
        for (token, id) in vocab {
            map[Int64(id)] = token
        }

        // 加载 added_tokens（special tokens，如 <od>, <loc_0> 等）
        let addedURL = URL(fileURLWithPath: modelDir).appendingPathComponent("added_tokens.json")
        if let addedData = try? Data(contentsOf: addedURL),
           let added = try? JSONSerialization.jsonObject(with: addedData) as? [String: Int] {
            for (token, id) in added {
                map[Int64(id)] = token
            }
        }

        idToToken = map
        isLoaded = true
        NSLog("PoLang:Florence2Tok Vocab loaded: \(idToToken.count) tokens")
        return true
    }

    /// 将 token ids 解码为文本（BPE detokenize）。
    ///
    /// BART BPE 子词用 `Ġ` 表示词间空格，`Ċ` 表示换行。
    /// 特殊 token（<loc_*>、</od> 等）保留原样（parser 会处理）。
    ///
    /// 移植自 Florence2Tokenizer.kt:62-83。
    func decode(_ tokenIds: [Int64]) -> String {
        if !isLoaded {
            NSLog("PoLang:Florence2Tok Vocab not loaded, returning raw ids")
            return tokenIds.map { String($0) }.joined(separator: ",")
        }

        var sb = ""
        sb.reserveCapacity(tokenIds.count * 8)
        for id in tokenIds {
            guard let token = idToToken[id] else { continue }
            if token.hasPrefix("<") && token.hasSuffix(">") {
                // 特殊 token：保留原样（parser 处理）
                sb.append(token)
            } else {
                // BPE 子词：Ġ→空格，Ċ→换行，▁→空格
                let decoded = token
                    .replacingOccurrences(of: "Ġ", with: " ")
                    .replacingOccurrences(of: "Ċ", with: "\n")
                    .replacingOccurrences(of: "▁", with: " ")
                sb.append(decoded)
            }
        }
        return sb.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
