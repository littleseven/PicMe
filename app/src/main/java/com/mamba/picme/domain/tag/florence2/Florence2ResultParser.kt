package com.mamba.picme.domain.tag.florence2

/**
 * Florence-2 输出解析器。
 *
 * OD 结果格式: "label1<loc_...>label2<loc_...>" — 正则提取 <loc_ 之前的标签。
 * Caption 结果: 纯文本 — 直接作为 summary，从中抽取 scene/activity 关键词。
 */
object Florence2ResultParser {

    private val LOC_PATTERN = Regex("<loc_\\d+>")

    // 常见 scene 词（从 caption 中匹配）
    private val SCENE_KEYWORDS = listOf(
        "indoor", "outdoor", "park", "street", "restaurant", "office", "home",
        "beach", "mountain", "studio", "city", "countryside", "garden", "kitchen",
        "bedroom", "classroom", "screenshot", "document", "sky", "forest", "river",
        "building", "portrait", "landscape"
    )

    // 常见 activity 词
    private val ACTIVITY_KEYWORDS = listOf(
        "posing", "sitting", "standing", "walking", "eating", "smiling", "looking",
        "running", "reading", "working", "sleeping", "dancing", "cooking", "drinking",
        "holding", "wearing", "playing", "talking", "driving", "traveling", "selfie"
    )

    // OD 结果中过滤掉的通用/无意义标签
    private val FILTERED_LABELS = setOf(
        "human face", "human body", "human hair", "human hand", "human eye",
        "human nose", "human mouth", "human ear", "human head", "human arm",
        "human leg", "human foot", "human skin"
    )

    /**
     * 从 OD 结果中提取物体标签。
     *
     * 输入: "fancy dress<loc_1><loc_2>...necklace<loc_1>..."
     * 输出: ["fancy dress", "necklace"]
     */
    fun parseODLabels(odText: String): List<String> {
        // 去掉所有 <loc_*> token，按分隔拆出标签块
        val cleaned = odText.replace(LOC_PATTERN, "|||")
        val labels = cleaned.split("|||")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it !in FILTERED_LABELS }
            // 去重
            .distinct()
        return labels
    }

    /**
     * 从 caption 中提取 scene（一个词）。
     */
    fun extractScene(caption: String): String {
        val lower = caption.lowercase()
        for (kw in SCENE_KEYWORDS) {
            if (lower.contains(kw)) return kw
        }
        return ""
    }

    /**
     * 从 caption 中提取 activity（一个短语）。
     */
    fun extractActivity(caption: String): String {
        val lower = caption.lowercase()
        for (kw in ACTIVITY_KEYWORDS) {
            if (lower.contains(kw)) return kw
        }
        return ""
    }

    /**
     * 从 caption 中提取关键词（简单分词 + 过滤停用词）。
     */
    fun extractKeywords(caption: String): List<String> {
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "with", "and", "or",
            "in", "on", "at", "to", "of", "for", "it", "this", "that", "image",
            "photo", "picture", "can", "be", "seen", "there", "has", "have",
            "her", "his", "she", "he", "who", "which", "from"
        )
        return caption.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(5)
    }
}
