import XCTest
@testable import PoLang

// MARK: - SemanticSearchEngineTests（双端契约 SSOT: contracts.md §5）
//
// 覆盖：
// 1. MobileClipTokenizer：fixture tokenizer.json 全链路对拍（normalize/pre-tokenize/
//    byte-encoder/</w>/BPE merge/BOS/EOS/PAD/截断保留 EOS）
// 2. encodeTextQuery prompt 包装（§5.2）
// 3. 余弦相似度 / rankCandidates topK / 0.22 阈值过滤（§5.1 步骤 5-6，内存桩替代真实模型）
// 4. SemanticEmbeddingCodec 联调（§5.5/R6：编码→解码→余弦 集成闭环）
// 5. ChineseQueryTranslator（§5.6-§5.8：纯英文直通、CLIP_QUERY_EXPANSIONS、质量校验）
//
// ⚠️ 真实 mobileclip-onnx tokenizer.json（8MB HF 词表）随模型包下载到设备 Documents，
//    不随仓库分发；tokenizer 对拍用小型 fixture 验证算法保真度，真实词表金标值需设备端验证。

final class SemanticSearchEngineTests: XCTestCase {

    // MARK: - fixture：小型 tokenizer.json（对齐 HF 格式：model.vocab/model.merges/added_tokens）

    /// 词表设计（byte-encoder 后 ASCII 可打印字符保持原码点）：
    /// - "a"     → ["a</w>"]
    /// - "photo" → p,h,o,t,o</w> → merges → ["photo</w>"]
    /// - "of"    → o,f</w> → merge → ["of</w>"]
    /// - "cat"   → c,a,t</w> → merges → ["cat</w>"]
    private func makeFixtureTokenizer(contextLength: Int = 77) throws -> MobileClipTokenizer {
        let vocab: [String: Int] = [
            "!": 0,
            "a</w>": 1,
            "p": 2, "h": 3, "o": 4,
            "photo</w>": 5,
            "to</w>": 6, "oto</w>": 7, "hoto</w>": 8,
            "of</w>": 9, "f</w>": 10,
            "c": 11, "at</w>": 12, "cat</w>": 13, "t</w>": 14
        ]
        // merges rank（索引即优先级）：photo 链 → of 链 → cat 链
        let merges: [String] = [
            "t o</w>", "o to</w>", "h oto</w>", "p hoto</w>",
            "o f</w>",
            "a t</w>", "c at</w>"
        ]
        let json: [String: Any] = [
            "model": [
                "vocab": vocab,
                "merges": merges
            ],
            "added_tokens": [
                ["content": "<|startoftext|>", "id": 49406],
                ["content": "<|endoftext|>", "id": 49407]
            ]
        ]
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("tokenizer-fixture-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let data = try JSONSerialization.data(withJSONObject: json)
        try data.write(to: dir.appendingPathComponent("tokenizer.json"))
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let tokenizer = MobileClipTokenizer(modelDir: dir)
        XCTAssertTrue(tokenizer.load(), "fixture tokenizer 应加载成功")
        return tokenizer
    }

    // MARK: - C2 tokenizer 对拍

    func testTokenizerSpecialTokenIdsFromAddedTokens() throws {
        let tokenizer = try makeFixtureTokenizer()
        XCTAssertEqual(tokenizer.bosTokenId, 49406) // <|startoftext|>
        XCTAssertEqual(tokenizer.eosTokenId, 49407) // <|endoftext|>
        XCTAssertEqual(tokenizer.padTokenId, 0)     // 默认 PAD
    }

    func testTokenizerEncodeKnownInput() throws {
        let tokenizer = try makeFixtureTokenizer()
        // "a photo of a cat" → [BOS, a</w>, photo</w>, of</w>, a</w>, cat</w>, EOS, PAD×70]
        let ids = try XCTUnwrap(tokenizer.encode("a photo of a cat"))
        XCTAssertEqual(ids.count, 77)
        XCTAssertEqual(Array(ids.prefix(7)), [49406, 1, 5, 9, 1, 13, 49407])
        XCTAssertTrue(ids.suffix(70).allSatisfy { $0 == 0 }, "尾部应补 PAD(0)")
    }

    func testTokenizerNormalizeLowercaseAndWhitespace() throws {
        let tokenizer = try makeFixtureTokenizer()
        // 契约 §5.3：NFC → trim → lowercase → 合并连续空白
        XCTAssertEqual(MobileClipTokenizer.normalizeText("  A   Photo \n OF "), "a photo of")
        let ids = try XCTUnwrap(tokenizer.encode("A  Photo"))
        XCTAssertEqual(Array(ids.prefix(4)), [49406, 1, 5, 49407], "大写/多空白应 normalize 后命中同一词表")
    }

    func testTokenizerTruncateKeepsEos() throws {
        let tokenizer = try makeFixtureTokenizer()
        // 7 个 token 截到 contextLength=4：取前 3 + EOS（契约 §5.3）
        let ids = try XCTUnwrap(tokenizer.encode("a photo of a cat", contextLength: 4))
        XCTAssertEqual(ids, [49406, 1, 5, 49407])
    }

    func testTokenizerUnknownTokenSkipped() throws {
        let tokenizer = try makeFixtureTokenizer()
        // "xyz" 字符不在词表 → BPE token 查不到 id → 跳过（不 crash，对齐 Android 仅告警）
        let ids = try XCTUnwrap(tokenizer.encode("cat xyz"))
        XCTAssertEqual(Array(ids.prefix(3)), [49406, 13, 49407])
    }

    func testTokenizerBytesToUnicodeSpaceMapping() throws {
        // GPT-2 bytes_to_unicode：空格(0x20) 不在可打印集合 → 映射到 256+n 码点（Ġ）。
        // 空格在 pre-tokenize 已被剔除不进 BPE，此处验证多字节 UTF-8 不 crash。
        // Android bpeEncode 是「逐个 BPE token 查表，未知则跳过（Log.w）」而非整词丢弃：
        // "café" → [c,a,f,Ą,©</w>] 不可合并，已知 "c"=11 仍产出，其余未知 token 跳过。
        let tokenizer = try makeFixtureTokenizer()
        let ids = try XCTUnwrap(tokenizer.encode("cat café"))
        XCTAssertEqual(Array(ids.prefix(4)), [49406, 13, 11, 49407],
                       "未登录 BPE token 逐个跳过，已知子 token 保留（对齐 Android per-token skip）")
    }

    // MARK: - §5.2 prompt 包装

    func testPromptWrap() {
        XCTAssertEqual(SemanticSearchEngine.promptWrap("cat"), "a photo of a cat")
        XCTAssertEqual(SemanticSearchEngine.promptWrap("Cat."), "a photo of a cat")
        XCTAssertEqual(SemanticSearchEngine.promptWrap("A cat"), "a photo of a cat")
        XCTAssertEqual(SemanticSearchEngine.promptWrap("an old house"), "a photo of a old house")
        XCTAssertEqual(SemanticSearchEngine.promptWrap("the beach"), "a photo of a beach")
        XCTAssertEqual(SemanticSearchEngine.promptWrap("young child"), "a photo of a young child")
        // concept 为空 → 返回原 query
        XCTAssertEqual(SemanticSearchEngine.promptWrap("  "), "  ")
    }

    // MARK: - §5.1 余弦相似度

    func testCosineSimilarity() {
        XCTAssertEqual(SemanticSearchEngine.cosineSimilarity([1, 0], [1, 0]), 1, accuracy: 1e-6)
        XCTAssertEqual(SemanticSearchEngine.cosineSimilarity([1, 0], [0, 1]), 0, accuracy: 1e-6)
        XCTAssertEqual(SemanticSearchEngine.cosineSimilarity([1, 0], [-1, 0]), -1, accuracy: 1e-6)
        XCTAssertEqual(SemanticSearchEngine.cosineSimilarity([3, 4], [3, 4]), 1, accuracy: 1e-6)
        // 零范数返回 0（契约 §5.3）
        XCTAssertEqual(SemanticSearchEngine.cosineSimilarity([0, 0], [1, 0]), 0)
        // 维度不等返回 0
        XCTAssertEqual(SemanticSearchEngine.cosineSimilarity([1], [1, 0]), 0)
    }

    // MARK: - §5.1 rankCandidates：最大值/阈值/topK/降序（内存桩替代真实模型）

    private func unit(_ index: Int, dim: Int = 8) -> [Float] {
        var v = [Float](repeating: 0, count: dim)
        v[index % dim] = 1
        return v
    }

    func testRankCandidatesThresholdAndTopK() {
        let query = unit(0)
        // 构造不同相似度：与 query 夹角递增
        let candidates: [(mediaId: Int64, embedding: [Float])] = [
            (1, [1, 0, 0, 0, 0, 0, 0, 0]),                    // sim = 1.0
            (2, [0.6, 0.8, 0, 0, 0, 0, 0, 0]),                // sim = 0.6
            (3, [0.3, 0.9539392, 0, 0, 0, 0, 0, 0]),          // sim ≈ 0.3（>= 0.22 保留）
            (4, [0.2, 0.9797959, 0, 0, 0, 0, 0, 0]),          // sim = 0.2（< 0.22 过滤）
            (5, [0, 1, 0, 0, 0, 0, 0, 0])                     // sim = 0（过滤）
        ]
        let ranked = SemanticSearchEngine.rankCandidates(
            textEmbeddings: [query], candidates: candidates, topK: 50)
        XCTAssertEqual(ranked.map { $0.mediaId }, [1, 2, 3], "score < 0.22 应被过滤，结果降序")
        XCTAssertEqual(ranked[0].score, 1.0, accuracy: 1e-5)
        XCTAssertEqual(ranked[2].score, 0.3, accuracy: 1e-5)

        let top2 = SemanticSearchEngine.rankCandidates(
            textEmbeddings: [query], candidates: candidates, topK: 2)
        XCTAssertEqual(top2.map { $0.mediaId }, [1, 2], "topK 截断")
    }

    func testRankCandidatesMaxAcrossTextEmbeddings() {
        // 多候选英文查询：同一图取所有 text embedding 中的最大相似度（§5.1 步骤 5）
        let textA = unit(0) // "cat"
        let textB = unit(1) // "kitten"
        let candidates: [(mediaId: Int64, embedding: [Float])] = [
            (1, [0.1, 0.9949874, 0, 0, 0, 0, 0, 0]), // vs A: 0.1，vs B: ≈0.995 → 取 max
            (2, [0.5, 0.8660254, 0, 0, 0, 0, 0, 0])  // vs A: 0.5，vs B: ≈0.866 → 取 max
        ]
        let ranked = SemanticSearchEngine.rankCandidates(
            textEmbeddings: [textA, textB], candidates: candidates, topK: 50)
        XCTAssertEqual(ranked[0].mediaId, 1)
        XCTAssertEqual(ranked[0].score, 0.995, accuracy: 1e-3)
        XCTAssertEqual(ranked[1].score, 0.866, accuracy: 1e-3)
    }

    // MARK: - §5.5 / R6 SemanticEmbeddingCodec 联调

    func testEmbeddingCodecRoundtripIntegratesWithCosine() throws {
        // 编码 → Base64 → 解码 → 余弦：全链路应与原始向量一致（R6 格式钉扎）
        var vec = [Float](repeating: 0, count: SemanticEmbeddingCodec.dimension)
        vec[0] = 0.6; vec[1] = 0.8
        let base64 = try XCTUnwrap(SemanticEmbeddingCodec.encode(vec))
        XCTAssertEqual(base64.count, 2732, "2048 字节 Base64 无换行应 2732 字符（ceil(2048/3)*4）")

        let decoded = try XCTUnwrap(SemanticEmbeddingCodec.decode(base64))
        XCTAssertEqual(decoded.count, 512)
        XCTAssertEqual(SemanticSearchEngine.cosineSimilarity(vec, decoded), 1, accuracy: 1e-6)

        // 大端序钉扎：float 1.0 的 IEEE-754 = 0x3F800000，Base64 前缀应为大端字节 3F 80 00 00
        let oneBase64 = try XCTUnwrap(SemanticEmbeddingCodec.encode([1.0] + [Float](repeating: 0, count: 511)))
        let raw = try XCTUnwrap(Data(base64Encoded: oneBase64))
        XCTAssertEqual([raw[0], raw[1], raw[2], raw[3]], [0x3F, 0x80, 0x00, 0x00], "float32 必须大端序")

        // 解码拒绝（契约 §5.5 校验照抄）
        XCTAssertNil(SemanticEmbeddingCodec.decode(nil))
        XCTAssertNil(SemanticEmbeddingCodec.decode(""))
        XCTAssertNil(SemanticEmbeddingCodec.decode("!!!not-base64!!!"))
        XCTAssertNil(SemanticEmbeddingCodec.encode([1, 2, 3]), "维度 != 512 拒绝")
    }

    func testRankCandidatesSkipsLowNormAndNaN() {
        // §5.1 步骤 5：norm < 1e-6 或 NaN 的候选图跳过（在 engine 加载侧；
        // rankCandidates 侧对应 NaN 相似度丢弃）
        let query = unit(0)
        let candidates: [(mediaId: Int64, embedding: [Float])] = [
            (1, [Float.nan, 0, 0, 0, 0, 0, 0, 0]), // NaN → 相似度 NaN → 丢弃
            (2, unit(0))
        ]
        let ranked = SemanticSearchEngine.rankCandidates(
            textEmbeddings: [query], candidates: candidates, topK: 50)
        XCTAssertEqual(ranked.map { $0.mediaId }, [2])
    }

    // MARK: - C4 ChineseQueryTranslator（§5.6-§5.8，注入词表替代 bundle 资源）

    private func makeTranslator() -> ChineseQueryTranslator {
        ChineseQueryTranslator(
            zhToEn: ["公园": "park", "的猫": "", "猫": "cat", "公园里的猫": "cat in park"],
            synonyms: ["美女": "女性", "小孩子": "小孩"])
    }

    func testExpandForClipPureEnglishPassthrough() {
        let t = makeTranslator()
        XCTAssertEqual(t.expandForClip("sunset"), ["sunset"])
        XCTAssertEqual(t.expandForClip(""), [])
    }

    func testExpandForClipHardcodedExpansions() {
        let t = makeTranslator()
        // 契约 §5.7：整词精确匹配 CLIP_QUERY_EXPANSIONS
        let candidates = t.expandForClip("小孩")
        XCTAssertTrue(candidates.contains("child"))
        XCTAssertTrue(candidates.contains("kid"))
        XCTAssertTrue(candidates.contains("children"))
        XCTAssertTrue(candidates.contains("young child"))
        // "小孩" 在注入词表无整句翻译 → 候选仅硬编码扩展
        XCTAssertEqual(candidates, ["child", "kid", "children", "young child"])
    }

    func testTranslateForClipVocabExactMatch() {
        let t = makeTranslator()
        XCTAssertEqual(t.translateForClip("公园"), "park")
        XCTAssertEqual(t.translateForClip("公园里的猫"), "cat in park")
        // 纯英文直通
        XCTAssertEqual(t.translateForClip("beach"), "beach")
        // 词表未命中（无 OPUS-MT 降级）→ 兜底返回原查询
        XCTAssertEqual(t.translateForClip("未收录词xyz"), "未收录词xyz")
    }

    func testExpandForClipControlledVocabSynonyms() {
        let t = makeTranslator()
        // "美女" 硬编码表命中（§5.7）+ 同义词 "女性"（synonym→canonical 方向，§5.6）
        let candidates = t.expandForClip("美女")
        XCTAssertTrue(candidates.contains("beautiful woman"), "硬编码扩展应命中")
        XCTAssertTrue(candidates.contains("female"), "canonical「女性」硬编码翻译应并入")
    }

    func testIsTranslationValid() {
        // §5.8 拒绝条件逐条
        XCTAssertFalse(ChineseQueryTranslator.isTranslationValid(input: "猫", output: ""))
        XCTAssertFalse(ChineseQueryTranslator.isTranslationValid(input: "猫", output: "猫"))
        XCTAssertFalse(ChineseQueryTranslator.isTranslationValid(input: "猫", output: "cat ♪"))
        XCTAssertFalse(ChineseQueryTranslator.isTranslationValid(input: "猫", output: "oh oh uh"))
        XCTAssertFalse(ChineseQueryTranslator.isTranslationValid(input: "猫", output: "oh my god"))
        XCTAssertFalse(ChineseQueryTranslator.isTranslationValid(input: "猫", output: "c"))
        XCTAssertFalse(ChineseQueryTranslator.isTranslationValid(input: "猫", output: "aaaa"))
        XCTAssertTrue(ChineseQueryTranslator.isTranslationValid(input: "猫", output: "cat"))
    }

    // MARK: - C5 引擎降级路径（模型不可得 → unavailable → 空结果）

    func testSearchByTextReturnsEmptyWhenModelUnavailable() async {
        // 注入空目录 → tokenizer/text model 加载失败 → unavailable → []（C1 降级语义）
        let emptyDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("no-model-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: emptyDir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: emptyDir) }

        let dbPath = emptyDir.appendingPathComponent("test.db").path
        let db = TagDatabase(dbPath: dbPath)
        let engine = SemanticSearchEngine(db: db, modelDirProvider: { emptyDir })
        let results = await engine.searchByText("小孩", limitToIds: nil, topK: 50)
        XCTAssertTrue(results.isEmpty)
        XCTAssertFalse(engine.isAvailable)
    }
}
