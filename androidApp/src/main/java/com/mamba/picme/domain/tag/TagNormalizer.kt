package com.mamba.picme.domain.tag

import android.util.Log

/**
 * 标签后处理规范化器
 *
 * 将 Qwen 自由文本输出匹配到受控词表，同时保留未匹配的原始文本。
 * 匹配策略：精确匹配 → 包含匹配 → 编辑距离容错 → 同义词映射 → 保留原始值
 *
 * 同义词映射（synonyms）用于将非标准口语词映射到标准词表词：
 * 例如 "帅哥" → "男性"，"美女" → "女性"，"宝宝" → "婴儿"
 */
class TagNormalizer(private val vocab: ControlledVocab) {

    companion object {
        private const val TAG = "TagNormalizer"
    }

    /**
     * 规范化 Qwen 输出的原始标签
     */
    fun normalize(raw: QwenTags): QwenTagsNormalized {
        val allCategories = vocab.allCategories
        val blockedSet = vocab.blockedTags.toSet() + vocab.blockedTagsEn.toSet()
        val nonStandard = mutableListOf<String>()

        // 一次遍历同时得到 normalized 结果和未匹配词，避免对同一张图重复遍历 700+ 词词表。
        fun normalizeItem(item: String, logLabel: String): String {
            if (item.isBlank()) return item
            val matched = bestMatchWithSynonyms(item)
            if (matched == item && item !in allCategories) {
                nonStandard.add(item)
            } else if (matched != item) {
                Log.d(TAG, "Normalized $logLabel: '$item' -> '$matched'")
            }
            return matched
        }

        val normalizedTags = raw.tags.map { normalizeItem(it, "tag") }.filter { it !in blockedSet }
        val normalizedObjects = raw.objects.map { normalizeItem(it, "object") }.filter { it !in blockedSet }

        return QwenTagsNormalized(
            scene = bestMatchWithSynonyms(raw.scene).takeIf { it !in blockedSet } ?: "",
            activity = bestMatchWithSynonyms(raw.activity).takeIf { it !in blockedSet } ?: "",
            objects = normalizedObjects,
            tags = normalizedTags,
            summary = raw.summary,
            nonStandard = nonStandard.distinct()
        )
    }

    /**
     * 带同义词映射的最佳匹配
     * 1. 精确匹配
     * 2. 包含匹配
     * 3. 编辑距离 ≤ 1 容错
     * 4. 同义词查找（synonym → canonical）
     * 5. 同义词映射后再与词表匹配
     * 6. 未匹配则返回原始值
     */
    private fun bestMatchWithSynonyms(input: String): String {
        if (input.isBlank()) return input
        val trimmed = input.trim()

        // Step 1-3: 先尝试直接匹配
        val directMatch = bestMatch(trimmed, vocab.allCategories)
        if (directMatch in vocab.allCategories) return directMatch

        // Step 4: 检查输入本身是否是同义词
        val synonymCanonical = vocab.synonyms[trimmed]
        if (synonymCanonical != null) {
            Log.d(TAG, "Synonym match: '$trimmed' -> '$synonymCanonical' (direct)")
            return synonymCanonical
        }

        // Step 5: 检查输入是否包含同义词或同义词包含输入
        for ((synonym, canonical) in vocab.synonyms) {
            if (trimmed.contains(synonym) || synonym.contains(trimmed)) {
                Log.d(TAG, "Synonym match: '$trimmed' -> '$canonical' (via synonym '$synonym')")
                return canonical
            }
        }

        // Step 6: 同义词的标准词再试一次词表匹配
        for ((_, canonical) in vocab.synonyms) {
            if (canonical in vocab.allCategories &&
                (trimmed.contains(canonical) || canonical.contains(trimmed))
            ) {
                return canonical
            }
        }

        return trimmed
    }

    /**
     * 从指定候选集中查找最佳匹配
     * 1. 精确匹配
     * 2. 包含匹配（输入包含候选词 或 候选词包含输入）
     * 3. 编辑距离 ≤ 1 容错
     * 4. 未匹配则返回原始值
     */
    private fun bestMatch(input: String, candidates: List<String>): String {
        if (input.isBlank()) return input

        val trimmed = input.trim()

        // 1. 精确匹配
        if (trimmed in candidates) return trimmed

        // 2. 包含匹配
        for (candidate in candidates) {
            if (trimmed.contains(candidate) || candidate.contains(trimmed)) {
                return candidate
            }
        }

        // 3. 未匹配：保留原词。
        // 曾用「编辑距离≤1容错」，但对中文 2 字词替换 1 字语义全变（"珠宝"→"宝宝"、"职场"→"商场"、
        // "人像"→"佛像"、"专业"→"毕业"、"执行"→"旅行"），对中文弊大于利，已移除。
        // SmolVLM/Qwen 输出的规范标签应保留原词，而非强行映射到词表里的近形词。
        return trimmed
    }

    /**
     * 跨所有类别模糊匹配（用于 objects / tags 字段）
     */
    private fun bestMatchAcrossCategories(input: String): String {
        return bestMatch(input, vocab.allCategories)
    }

    /**
     * 计算两个字符串的编辑距离（Levenshtein 距离）
     */
    internal fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // 删除
                    dp[i][j - 1] + 1,       // 插入
                    dp[i - 1][j - 1] + cost  // 替换
                )
            }
        }

        return dp[a.length][b.length]
    }
}
