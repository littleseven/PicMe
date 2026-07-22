package com.mamba.picme.domain.search

/**
 * 搜索同义词扩展表：把用户的自然语言词映射到相册标签体系实际使用的词。
 *
 * 背景：refine 的「constraint 词」与「labels 标签词」常粒度/词面不一致
 * （如用户说「女性」，标签是「女」；用户说「动物」，标签是「猫/狗」）。
 * 朴素子串 `labels.contains("女性")` 命中 0。本表把这些用户词扩展为标签候选词，
 * 交给 [MediaSearchEngine] 的标签 LIKE + 语义召回匹配。
 *
 * 标签词来自 ML Kit / Qwen 实际产物，按需扩充；不追求零维护
 * （自然语言↔离散标签本质是 NLP 问题，本表是务实的集中兜底）。
 */
object SearchSynonyms {

    private val map: Map<String, List<String>> = mapOf(
        // 性别（标签用单字「女/男」，qwenSummary 里是「一位女士」等）
        "女性" to listOf("女"), "女人" to listOf("女"), "女孩" to listOf("女"), "少女" to listOf("女"), "女生" to listOf("女"),
        "男性" to listOf("男"), "男人" to listOf("男"), "男孩" to listOf("男"), "少年" to listOf("男"), "男生" to listOf("男"),
        // 动物 / 宠物：仅扩英文（匹配 Qwen/SmolVLM 生成的英文标签）。
        // 不扩中文「狗/猫」——避免命中 labels.activity「遛狗」等（人遛狗照片，人/车为主，非动物特写）。
        "动物" to listOf("cat", "dog", "pet", "bird", "fish", "horse"),
        "宠物" to listOf("cat", "dog", "pet"),
        // 场景 / 时间
        "夜景" to listOf("夜"), "夜晚" to listOf("夜"),
        "海边" to listOf("海", "沙滩"), "海滩" to listOf("沙滩"),
        // 食物
        "食物" to listOf("面条", "米饭", "菜"), "美食" to listOf("面条", "米饭", "菜")
    )

    /**
     * 扩展查询词：返回标签候选词 + 原词。未命中同义词表时只返回原词。
     */
    fun expand(query: String): Set<String> = (map[query] ?: emptyList()).toSet() + query
}
