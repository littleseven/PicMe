package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import com.mamba.picme.data.download.ModelPathConfig
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * MobileCLIP BPE Tokenizer
 *
 * 解析 Hugging Face 格式的 tokenizer.json，实现文本 → token IDs 编码。
 * 模型配套文件已随 MobileCLIP-ONNX 下载到 llm_models/mobileclip-onnx/ 目录。
 *
 * 编码流程：
 * 1. 文本预处理（NFC 规范化、小写、去除多余空格）
 * 2. 按 pre_tokenizer 规则切分（通常是空格/标点分词）
 * 3. 对每个词进行 BPE 合并（加载 merges.txt 或 tokenizer.json 中的 merges）
 * 4. 将 token 映射为 id（加载 vocab）
 * 5. 添加特殊 token（BOS/EOS/PAD）
 *
 * @param context Application Context
 */
class MobileClipTokenizer(context: Context) {

    companion object {
        private const val TAG = "MobileClipTokenizer"

        /** 默认 BOS token id（<|startoftext|>） */
        private const val DEFAULT_BOS_ID = 49406L

        /** 默认 EOS token id（<|endoftext|>） */
        private const val DEFAULT_EOS_ID = 49407L

        /** 默认 PAD token id */
        private const val DEFAULT_PAD_ID = 0L

        /** 默认上下文长度 */
        private const val DEFAULT_CONTEXT_LENGTH = 77
    }

    private val modelDir: File = ModelPathConfig.getMobileClipModelDir(context)

    /** token → id 映射表 */
    private val vocab: MutableMap<String, Long> = mutableMapOf()

    /** id → token 反向映射（调试用） */
    private val idToToken: MutableMap<Long, String> = mutableMapOf()

    /** BPE 合并规则列表（按优先级排序） */
    private val merges: MutableList<Pair<String, String>> = mutableListOf()

    /** BPE 合并规则 -> 优先级（rank 越小越优先） */
    private val mergeRanks: MutableMap<Pair<String, String>, Int> = mutableMapOf()

    /** 特殊 token 配置 */
    private var bosTokenId: Long = DEFAULT_BOS_ID
    private var eosTokenId: Long = DEFAULT_EOS_ID
    private var padTokenId: Long = DEFAULT_PAD_ID
    private var contextLength: Int = DEFAULT_CONTEXT_LENGTH

    /** 是否已加载 */
    private var isLoaded: Boolean = false

    /** 字节到 Unicode 字符的映射（用于 BPE 处理字节序列） */
    private val byteEncoder: Map<Int, String> by lazy { buildByteEncoder() }

    /**
     * 预编译的正则：Hugging Face CLIPTokenizer 的 Split pre-tokenizer。
     *
     * 注意：该 pattern 不保留前导空格，空格由 ByteLevel pre-tokenizer 处理；
     * 与 GPT-2 tokenizer 不同，CLIP 模型使用 `</w>` 词尾标记。
     */
    private val preTokenizePattern: Pattern by lazy {
        Pattern.compile("""'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""")
    }

    /**
     * 加载 tokenizer 配置（优先 tokenizer.json，回退 vocab.txt + merges.txt）
     *
     * @return 是否加载成功
     */
    /** 加载 tokenizer（幂等；@Synchronized 防并发重复加载 8MB+ 词表 JSON） */
    @Synchronized
    fun load(): Boolean {
        if (isLoaded) return true

        val tokenizerJsonFile = File(modelDir, "tokenizer.json")
        return if (tokenizerJsonFile.exists()) {
            loadFromTokenizerJson(tokenizerJsonFile)
        } else {
            loadFromVocabAndMerges()
        }
    }

    /**
     * 从 tokenizer.json 加载（Hugging Face 标准格式）
     */
    private fun loadFromTokenizerJson(file: File): Boolean {
        return try {
            val json = JSONObject(file.readText())

            // 1. 加载 vocab
            val modelObj = json.getJSONObject("model")
            val vocabObj = modelObj.getJSONObject("vocab")
            vocabObj.keys().forEach { token ->
                val id = vocabObj.getLong(token)
                vocab[token] = id
                idToToken[id] = token
            }

            // 2. 加载 merges
            if (modelObj.has("merges")) {
                val mergesArray = modelObj.getJSONArray("merges")
                for (i in 0 until mergesArray.length()) {
                    val mergeStr = mergesArray.getString(i).trim()
                    if (mergeStr.isNotEmpty() && !mergeStr.startsWith("#")) {
                        val parts = mergeStr.split(" ")
                        if (parts.size == 2) {
                            merges.add(parts[0] to parts[1])
                        }
                    }
                }
            }

            // 构建 merge 优先级表
            mergeRanks.clear()
            merges.forEachIndexed { index, pair -> mergeRanks[pair] = index }

            // 3. 加载特殊 token 配置
            if (json.has("added_tokens")) {
                val addedTokens = json.getJSONArray("added_tokens")
                for (i in 0 until addedTokens.length()) {
                    val tokenObj = addedTokens.getJSONObject(i)
                    val token = tokenObj.getString("content")
                    val id = tokenObj.getLong("id")
                    vocab[token] = id
                    idToToken[id] = token
                    when (token) {
                        "<|startoftext|>" -> bosTokenId = id
                        "<|endoftext|>" -> eosTokenId = id
                        "<|pad|>" -> padTokenId = id
                    }
                }
            }

            // 4. 加载 post_processor / 配置
            if (json.has("post_processor")) {
                val postProcessor = json.getJSONObject("post_processor")
                // 解析 special_tokens 获取 bos/eos
            }

            isLoaded = true
            Log.i(TAG, "Tokenizer loaded from tokenizer.json: vocab=${vocab.size}, merges=${merges.size}")
            true
        } catch (e: OutOfMemoryError) {
            // 堆耗尽时返回加载失败（调用方会跳过语义搜索），避免 OOM(Error) 直接 crash
            Log.e(TAG, "Failed to load tokenizer.json: out of memory", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokenizer.json", e)
            false
        }
    }

    /**
     * 从 vocab.txt + merges.txt 加载（传统 BPE 格式）
     */
    private fun loadFromVocabAndMerges(): Boolean {
        return try {
            // 1. 加载 vocab.txt
            val vocabFile = File(modelDir, "vocab.txt")
            if (vocabFile.exists()) {
                vocabFile.readLines().forEachIndexed { index, line ->
                    val token = line.trim()
                    if (token.isNotEmpty()) {
                        vocab[token] = index.toLong()
                        idToToken[index.toLong()] = token
                    }
                }
            }

            // 2. 加载 merges.txt
            val mergesFile = File(modelDir, "merges.txt")
            if (mergesFile.exists()) {
                mergesFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split(" ")
                        if (parts.size == 2) {
                            merges.add(parts[0] to parts[1])
                        }
                    }
                }
            }

            // 构建 merge 优先级表
            mergeRanks.clear()
            merges.forEachIndexed { index, pair -> mergeRanks[pair] = index }

            isLoaded = true
            Log.i(TAG, "Tokenizer loaded from vocab.txt + merges.txt: vocab=${vocab.size}, merges=${merges.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab/merges", e)
            false
        }
    }

    /**
     * 将文本编码为 token ID 数组（LongArray，适配 MNN text 模型输入）
     *
     * @param text 用户输入文本
     * @param contextLength 最大序列长度（默认 77）
 * @param addSpecialTokens 是否添加 BOS/EOS
     * @return LongArray token IDs，失败返回 null
     */
    fun encode(
        text: String,
        contextLength: Int = this.contextLength,
        addSpecialTokens: Boolean = true
    ): LongArray? {
        if (!isLoaded && !load()) {
            Log.w(TAG, "Tokenizer not loaded")
            return null
        }

        return try {
            // 1. 文本预处理
            val normalized = normalizeText(text)

            // 2. 预分词（按空格/标点切分）
            val words = preTokenize(normalized)

            // 3. 对每个词进行 BPE 编码
            val tokenIds = mutableListOf<Long>()

            if (addSpecialTokens) {
                tokenIds.add(bosTokenId)
            }

            for (word in words) {
                val wordTokenIds = bpeEncode(word)
                tokenIds.addAll(wordTokenIds)
            }

            if (addSpecialTokens) {
                tokenIds.add(eosTokenId)
            }

            // 4. 截断或填充到固定长度
            val result = padOrTruncate(tokenIds, contextLength)

            result.toLongArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode text: $text", e)
            null
        }
    }

    /**
     * 文本规范化：NFC 规范化、合并空白、小写。
     *
     * MobileCLIP 配套 tokenizer.json 的 normalizer 明确包含 Lowercase，
     * vocab 中也只包含小写形式（如 "usb</w>"、"iphone</w>"）。
     * 因此必须 lowercase，否则 "USB" / "iPhone" 等词会全部退化为字节级 token。
     */
    private fun normalizeText(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * 预分词：按 CLIP Split pattern 切分，ByteLevel 空格处理交给 BPE 阶段的 byte encoder。
     */
    private fun preTokenize(text: String): List<String> {
        val matcher = preTokenizePattern.matcher(text)
        val words = mutableListOf<String>()
        while (matcher.find()) {
            val match = matcher.group()
            if (match.isNotBlank()) {
                words.add(match)
            }
        }
        return words
    }

    /**
     * 对单个 pretoken 进行 BPE 编码（CLIP 风格：结尾附加 `</w>`）。
     */
    private fun bpeEncode(token: String): List<Long> {
        if (token.isEmpty()) return emptyList()

        // 1. Byte encoder：将字节映射为 GPT-2 / CLIP 风格的 Unicode 字符
        val encodedChars = token.encodeToByteArray()
            .map { byteEncoder[it.toInt() and 0xFF] ?: "" }
            .toMutableList()

        if (encodedChars.isEmpty()) return emptyList()

        // 2. CLIP BPE：先把 `</w>` 附加到最后一个字符，再参与 BPE 合并。
        // 这与 GPT-2（不加 </w>）和 RoBERTa（加 Ġ 前缀）都不同。
        encodedChars[encodedChars.size - 1] += "</w>"

        // 3. 标准 BPE 合并
        val bpeTokens = bpeMerge(encodedChars)

        // 4. 映射为 token IDs
        val tokenIds = mutableListOf<Long>()
        for (bpeToken in bpeTokens) {
            val id = vocab[bpeToken]
            if (id != null) {
                tokenIds.add(id)
            } else {
                Log.w(TAG, "Unknown BPE token: '$bpeToken' in token '$token'")
            }
        }
        return tokenIds
    }

    /**
     * 标准 BPE 合并：按 merges.txt / tokenizer.json 中的优先级逐步合并字符对。
     *
     * 算法与 Hugging Face tokenizers BPE 一致：
     * 1. 每个 byte encoder 后的 Unicode 字符作为一个 token
     * 2. 每次选择优先级最高（rank 最小）且存在于 mergeRanks 的相邻 pair
     * 3. 将该 pair 在整词中全部合并为单个 token
     * 4. 重复直到没有可合并的 pair
     */
    private fun bpeMerge(tokens: List<String>): List<String> {
        if (tokens.size <= 1) return tokens

        var word = tokens.toMutableList()

        fun getPairs(tokens: List<String>): Set<Pair<String, String>> {
            val pairs = mutableSetOf<Pair<String, String>>()
            var prev = tokens[0]
            for (i in 1 until tokens.size) {
                pairs.add(prev to tokens[i])
                prev = tokens[i]
            }
            return pairs
        }

        var pairs = getPairs(word)
        while (pairs.isNotEmpty()) {
            // 选择优先级最高（rank 最小）的可合并 pair
            val (first, second) = pairs.minByOrNull { mergeRanks.getOrDefault(it, Int.MAX_VALUE) }
                ?: break

            if ((first to second) !in mergeRanks) break

            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i++
                }
            }
            word = newWord
            if (word.size <= 1) break
            pairs = getPairs(word)
        }

        return word
    }

    /**
     * 截断或填充到固定长度。
     *
     * 使用模型配套的 pad token（通常为 "!"，id=0）。
     * CLIP text encoder 从 EOS 位置取特征，因此 pad token 的具体值不影响最终 embedding，
     * 但需与训练时保持一致。
     *
     * 截断时保留末尾 EOS：先取前 maxLength-1 个 token，再追加 EOS。
     */
    private fun padOrTruncate(tokenIds: List<Long>, maxLength: Int): List<Long> {
        return when {
            tokenIds.size > maxLength -> tokenIds.take(maxLength - 1) + eosTokenId
            tokenIds.size < maxLength -> tokenIds + List(maxLength - tokenIds.size) { padTokenId }
            else -> tokenIds
        }
    }

    /**
     * 构建字节到 Unicode 的映射表（标准 GPT-2 / CLIP 实现）
     *
     * 参考 OpenAI GPT-2 bytes_to_unicode：
     * 1. 初始集合：可打印 ASCII + 部分 Latin-1
     * 2. 剩余 0-255 字节按顺序映射到 256+ 的 Unicode 码点
     */
    private fun buildByteEncoder(): Map<Int, String> {
        val initialBytes = mutableListOf<Int>()
        for (b in '!'.code..'~'.code) initialBytes.add(b)
        for (b in '¡'.code..'¬'.code) initialBytes.add(b)
        for (b in '®'.code..'ÿ'.code) initialBytes.add(b)

        val bytes = initialBytes.toMutableList()
        val chars = initialBytes.toMutableList()

        var n = 0
        for (b in 0 until 256) {
            if (b !in initialBytes) {
                bytes.add(b)
                chars.add(256 + n)
                n++
            }
        }

        return bytes.mapIndexed { index, byte ->
            byte to chars[index].toChar().toString()
        }.toMap()
    }

    /**
     * 检查 tokenizer 是否已加载
     */
    fun isReady(): Boolean = isLoaded

    /**
     * 获取 vocab 大小（调试用）
     */
    fun vocabSize(): Int = vocab.size

    /**
     * 将 token IDs 解码回文本（调试用）
     */
    fun decode(tokenIds: LongArray): String {
        val result = StringBuilder()
        for (id in tokenIds) {
            val token = idToToken[id]
            if (token != null) {
                result.append(token)
            }
        }
        return result.toString()
    }
}
