package com.mamba.picme.domain.tag.i18n

import android.content.Context
import android.util.Log
import com.mamba.picme.domain.tag.ControlledVocab

/**
 * 中文查询翻译器（专为 MobileCLIP 语义搜索优化）
 *
 * 采用分层翻译策略：
 * 1. **词表精确匹配**（零耗时）：通过 BilingualVocab 查找高频词
 * 2. **受控词表同义词扩展**（零耗时）：通过 ControlledVocab 展开中文同义词并翻译
 * 3. **CLIP 优化扩展表**（零耗时）：人工维护的高质量 CLIP 短语映射
 * 4. **OPUS-MT 模型翻译**（~50ms）：词表未命中时，调用轻量 NMT 模型
 * 5. **保留原词兜底**：模型不可用时返回原查询
 *
 * 设计目标：让中文用户搜索 "公园里的猫" 时，MobileCLIP 能收到 "cat in park"
 * 从而正确匹配到语义相近的图像。
 *
 * @param context Application Context
 * @param vocab 双语词表（用于高频词快速匹配）
 * @param translator OPUS-MT 翻译引擎（可选注入）
 * @param controlledVocab 受控词表（用于中文同义词动态扩展）
 */
class ChineseQueryTranslator(
    private val context: Context,
    private val vocab: BilingualVocab = BilingualVocab.empty(),
    private val translator: OpusMtTranslator? = null,
    private val controlledVocab: ControlledVocab? = null
) {
    companion object {
        private const val TAG = "ChineseQueryTranslator"

        /** 中文 Unicode 范围检测正则 */
        private val CHINESE_REGEX = Regex("[\\u4e00-\\u9fff]")

        /** 常见中文停用词（搜索时无需翻译） */
        private val STOP_WORDS = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "那"
        )

        /**
         * MobileCLIP 语义搜索查询扩展表（人工维护的高质量映射）。
         * 关键：同一个中文概念可能对应多个英文表达，取最大相似度可提升召回。
         *
         * 维护策略：仅保留需要特殊 CLIP 短语优化的高频词。
         * 通用词通过 ControlledVocab + translateForClip 动态扩展。
         */
        private val CLIP_QUERY_EXPANSIONS: Map<String, List<String>> = mapOf(
            // 人物查询（需要 CLIP 特化的英文短语）
            "小孩" to listOf("child", "kid", "children", "young child"),
            "儿童" to listOf("child", "children", "young child"),
            "婴儿" to listOf("baby", "infant", "newborn"),
            "宝宝" to listOf("baby", "toddler", "infant"),
            "孩子" to listOf("child", "kid", "children"),
            "男孩" to listOf("boy", "little boy", "young boy"),
            "女孩" to listOf("girl", "little girl", "young girl"),
            "美女" to listOf("beautiful woman", "woman", "female", "portrait of a woman"),
            "帅哥" to listOf("handsome man", "man", "male", "portrait of a man"),
            "女人" to listOf("woman", "female", "adult woman"),
            "男人" to listOf("man", "male", "adult man"),
            "女士" to listOf("woman", "lady", "female"),
            "男士" to listOf("man", "gentleman", "male"),
            "男生" to listOf("boy", "young man", "male"),
            "女生" to listOf("girl", "young woman", "female"),
            "男性" to listOf("male", "man"),
            "女性" to listOf("female", "woman"),
            // 常见物体/场景
            "猫" to listOf("cat", "kitten"),
            "狗" to listOf("dog", "puppy"),
            "花" to listOf("flower", "blossom"),
            "海边" to listOf("beach", "seaside", "shore"),
            "日落" to listOf("sunset", "dusk"),
            "山" to listOf("mountain", "hill"),
            "美食" to listOf("food", "cuisine", "delicious food"),
            "大美女" to listOf("beautiful woman", "woman", "female", "portrait of a woman"),
            "大帅哥" to listOf("handsome man", "man", "male", "portrait of a man")
        )
    }

    /** 翻译引擎（延迟初始化） */
    private val lazyTranslator: OpusMtTranslator by lazy {
        translator ?: OpusMtTranslator(context)
    }

    /**
     * 将用户查询转换为 MobileCLIP 友好的英文查询
     *
     * 策略：
     * 1. 纯英文查询 → 直接返回（无需翻译）
     * 2. 含中文查询 → 先分词，逐词查词表，未命中走模型翻译
     * 3. 混合查询 → 翻译中文部分，保留英文部分
     *
     * @param query 用户原始查询
     * @return 翻译后的英文查询（或原查询）
     */
    fun translateForClip(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return trimmed

        // 1. 纯英文查询直接返回
        if (!containsChinese(trimmed)) {
            Log.d(TAG, "Query is English, no translation needed: $trimmed")
            return trimmed
        }

        // 2. 尝试词表精确匹配（零耗时）
        val vocabResult = tryVocabLookup(trimmed)
        if (vocabResult != null) {
            Log.d(TAG, "Vocab hit: '$trimmed' -> '$vocabResult'")
            return vocabResult
        }

        // 3. 尝试 OPUS-MT 模型翻译
        val modelResult = tryModelTranslate(trimmed)
        if (modelResult != null) {
            Log.d(TAG, "Model translated: '$trimmed' -> '$modelResult'")
            return modelResult
        }

        // 4. 兜底：保留原查询（MobileCLIP 对部分中文有弱泛化）
        Log.d(TAG, "Translation unavailable, returning original: $trimmed")
        return trimmed
    }

    /**
     * 批量翻译（用于扩展多个候选词）
     */
    fun translateBatch(queries: List<String>): List<String> =
        queries.map { translateForClip(it) }

    /**
     * 将用户查询扩展为多个 MobileCLIP 英文候选，语义搜索时取最大相似度。
     *
     * 扩展来源（按优先级）：
     * 1. CLIP_QUERY_EXPANSIONS 硬编码表（人工维护，CLIP 优化短语）
     * 2. ControlledVocab 同义词翻译（动态，覆盖面广）
     * 3. translateForClip 基础翻译（兜底）
     *
     * @param query 用户原始查询
     * @return 候选英文查询列表（去重，至少包含翻译结果）
     */
    fun expandForClip(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 纯英文：直接返回原查询
        if (!containsChinese(trimmed)) {
            return listOf(trimmed)
        }

        val translated = translateForClip(trimmed)
        val hardcodedExpansions = CLIP_QUERY_EXPANSIONS[trimmed]
        val synonymTranslations = expandViaControlledVocab(trimmed)
        val translationValid = isTranslationValid(trimmed, translated)

        val candidates = linkedSetOf<String>()

        // 1. 基础翻译（如果有效）
        if (translationValid) {
            candidates += translated
        }

        // 2. 硬编码 CLIP 优化扩展表（最高质量）
        if (hardcodedExpansions != null) {
            candidates.addAll(hardcodedExpansions)
        }

        // 3. 动态同义词翻译（ControlledVocab）
        candidates.addAll(synonymTranslations)

        return if (candidates.isEmpty()) {
            // 所有路径都失败 → 保留原查询
            listOf(translated)
        } else {
            candidates.toList()
        }
    }

    /**
     * 通过 ControlledVocab 动态扩展中文同义词并翻译为英文。
     *
     * 例如：输入 "美女" → synonyms["美女"] = "女性" → translateForClip("女性") = "female"
     * 这弥补了硬编码 CLIP_QUERY_EXPANSIONS 仅 20 条的不足。
     */
    private fun expandViaControlledVocab(query: String): List<String> {
        val cv = controlledVocab ?: return emptyList()
        val results = mutableListOf<String>()

        // 方向1: 输入是 synonym → 翻译 canonical
        cv.synonyms[query]?.let { canonical ->
            if (canonical != query) {
                val canonicalTranslated = translateForClip(canonical)
                if (canonicalTranslated != canonical && isTranslationValid(canonical, canonicalTranslated)) {
                    results += canonicalTranslated
                    Log.d(TAG, "ControlledVocab expand: '$query' → canonical '$canonical' → EN '$canonicalTranslated'")
                }
            }
        }

        // 方向2: 输入是 canonical → 翻译所有 synonyms
        cv.reverseSynonyms[query]?.let { synonyms ->
            for (syn in synonyms) {
                if (syn != query) {
                    val synTranslated = translateForClip(syn)
                    if (synTranslated != syn && isTranslationValid(syn, synTranslated)) {
                        results += synTranslated
                        Log.d(TAG, "ControlledVocab reverse expand: '$query' → synonym '$syn' → EN '$synTranslated'")
                    }
                }
            }
        }

        return results
    }

    /**
     * 检查文本是否包含中文字符
     */
    fun containsChinese(text: String): Boolean =
        CHINESE_REGEX.containsMatchIn(text)

    /**
     * 尝试词表查找
     *
     * 支持两种模式：
     * 1. 整句精确匹配（如 "公园" → "park"）
     * 2. 分词后逐词匹配（如 "公园 猫" → "park cat"）
     */
    private fun tryVocabLookup(query: String): String? {
        // 1. 整句精确匹配（忽略空字符串值，空字符串表示该词的翻译缺失）
        vocab.zhToEn[query]?.takeIf { it.isNotEmpty() }?.let { return it }

        // 2. 分词后逐词匹配
        val words = segmentQuery(query)
        val translatedWords = words.map { word ->
            when {
                word in STOP_WORDS -> null  // 跳过停用词
                !containsChinese(word) -> word  // 保留英文词
                else -> vocab.zhToEn[word]  // 查词表（可能为 null）
            }
        }

        // 如果所有词都命中词表，返回组合结果
        // 注意：使用 withIndex() 而非 indexOf()，因为 indexOf(null) 在有多个 null
        // 时总返回第一个 null 的位置，导致停用词检查错位
        val allMatched = translatedWords.withIndex().all { (i, translated) ->
            translated != null || words[i] in STOP_WORDS
        }
        return if (allMatched) {
            val result = translatedWords.filterNotNull().joinToString(" ")
            result.ifEmpty { null }  // 全是停用词/未命中 → 回退到模型翻译
        } else {
            null
        }
    }

    /**
     * 尝试模型翻译
     */
    private fun tryModelTranslate(query: String): String? {
        return try {
            val result = lazyTranslator.translate(query)
            // 质量校验：过滤异常输出
            if (!isTranslationValid(query, result)) {
                Log.w(TAG, "Translation quality check failed: '$query' -> '$result'")
                return null
            }
            Log.d(TAG, "Model translated: '$query' -> '$result'")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Model translation failed", e)
            null
        }
    }

    /**
     * 翻译结果质量校验
     *
     * 过滤以下异常输出：
     * 1. 与输入相同（翻译失败）
     * 2. 空字符串或纯空白
     * 3. 包含大量重复单字符（如 "aaaa"）
     * 4. 包含异常噪音字符（如 "♪", "⁇", "▁"）
     * 5. 无意义拟声词（如 "Ooh", "oh", "ho" 占比过高）
     * 6. 结果过短（< 2 个有效字符）
     */
    private fun isTranslationValid(input: String, output: String): Boolean {
        if (output.isBlank()) return false
        if (output == input) return false

        // 过滤噪音字符
        val noiseChars = setOf('♪', '⁇', '▁')
        if (output.any { it in noiseChars }) return false

        // 过滤拟声词占比过高（如 "Ooh, oh, ho...ooh!"）
        val interjectionWords = setOf("ooh", "oh", "ho", "ah", "uh", "um", "ha", "heh")
        val words = output.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val interjectionCount = words.count { it in interjectionWords }
        if (interjectionCount.toFloat() / words.size > 0.5f) return false

        // 过滤宗教/感叹口头禅（如 "Oh, oh, my God.", "Oh my gosh"）
        val meaninglessExclamations = setOf("god", "gosh", "jeez", "lord")
        if (words.any { it in meaninglessExclamations }) return false

        // 过滤重复单字符（如 "aaaa" 或 "a a a a"）
        val clean = output.filter { it.isLetter() }
        if (clean.length < 2) return false
        if (clean.toSet().size == 1) return false // 全是同一个字符

        return true
    }

    /**
     * 简单中文分词（基于词典 + 最大正向匹配）
     *
     * 这是一个简化实现，足够处理搜索查询级别的短文本。
     * 复杂场景可引入 jieba 或类似分词库。
     */
    private fun segmentQuery(query: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = query

        while (remaining.isNotEmpty()) {
            // 先尝试匹配词表中的最长词
            var matched = false
            val maxLen = minOf(remaining.length, 8) // 最大匹配 8 个字

            for (len in maxLen downTo 1) {
                val sub = remaining.substring(0, len)
                if (vocab.zhToEn.containsKey(sub) || sub in STOP_WORDS) {
                    result.add(sub)
                    remaining = remaining.substring(len)
                    matched = true
                    break
                }
            }

            if (!matched) {
                // 未命中词表，按单字切分
                val firstChar = remaining[0].toString()
                result.add(firstChar)
                remaining = remaining.substring(1)
            }
        }

        return result
    }

    /**
     * 释放翻译引擎资源
     */
    fun release() {
        translator?.release() ?: lazyTranslator.release()
    }
}
