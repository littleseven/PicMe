package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.context.MediaAsset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * iOS chat 相册搜索纯逻辑（in-set 过滤 / refine 解析 / 约束清洗 / 标签解析）。
 *
 * 逐字对齐 Android `app/features/chat/ChatGallerySearch.kt`（契约 §9.4 SSOT：
 * `tmp/ios-follow/gallery-search/contracts.md`）——同一规则双端各一份实现，
 * Android 在 androidApp、iOS 在 shared iosMain（commonMain 不承载，零改动红线）。
 */
object IosChatGallerySearch {

    /** in-set 过滤：对已持有的结果集按 constraint 命中 labels/ocr/location/fileName。 */
    fun filterInSet(assets: List<MediaAsset>, constraint: String): List<MediaAsset> {
        val kw = constraint.trim()
        if (kw.isEmpty()) return assets
        val wantsFace = isFaceIntent(kw)
        return assets.filter { a ->
            val textMatch = listOfNotNull(a.labels, a.ocrText, a.locationName, a.fileName)
                .any { text -> text.contains(kw, ignoreCase = true) }
            // 人脸是结构化条件：标签体系不会有"人脸/有人脸"字面词，必须用 hasFace 字段。
            textMatch || (wantsFace && a.hasFace)
        }
    }

    /**
     * 判断 constraint 是否表达"含人脸"意图（如"有人脸""人脸""脸""face"）。
     * 命中时 [filterInSet] 改用 [MediaAsset.hasFace] 结构化字段，而非标签子串。
     */
    private fun isFaceIntent(constraint: String): Boolean {
        val lower = constraint.lowercase()
        return lower.contains("脸") || lower.contains("face")
    }

    /**
     * 多轮 refine 的结果解析：优先 [filterInSet]（精准）；为空时回退搜索引擎命中 ∩ [prior]（按 id）。
     */
    fun resolveRefine(
        prior: List<MediaAsset>,
        searchHits: List<MediaAsset>,
        constraint: String
    ): List<MediaAsset> {
        val inSet = filterInSet(prior, constraint)
        if (inSet.isNotEmpty()) return inSet
        val priorIds = prior.map { asset -> asset.id }.toSet()
        return searchHits.filter { hit -> hit.id in priorIds }
    }

    /**
     * 清理 refine constraint：去口语前缀/后缀，提取核心词；性别归一到单字「女/男」。
     * 前缀/后缀/性别映射逐字照抄契约 §9.4。
     */
    fun cleanConstraint(constraint: String): String {
        var cleaned = constraint.trim()
        val prefixes = listOf("只保留", "只要", "保留", "其中的", "其中", "去掉", "不要", "排除")
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.removePrefix(prefix).trim()
                break
            }
        }
        val suffixes = listOf("的照片", "的图片", "照片", "图片", "的")
        for (suffix in suffixes) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.removeSuffix(suffix).trim()
                break
            }
        }
        // 性别归一：标签体系用单字「女/男」，否则「女性」≠「女」漏报。
        val genderMap = mapOf(
            "女性" to "女", "女人" to "女", "女孩" to "女", "少女" to "女", "女生" to "女",
            "男性" to "男", "男人" to "男", "男孩" to "男", "少年" to "男", "男生" to "男"
        )
        genderMap[cleaned]?.let { normalized -> cleaned = normalized }
        return cleaned
    }

    private val labelsJson = Json { ignoreUnknownKeys = true }

    /**
     * 解析 labels 字段的 `tags` 数组（契约 §4.6：labels 为 JSON 对象字符串，
     * `JSONObject(labels).optJSONArray("tags")`；非法 JSON → 空表，与 Android parseLabels 同语义）。
     */
    fun parseLabelTags(labels: String): List<String> = runCatching {
        labelsJson.parseToJsonElement(labels).jsonObject["tags"]
            ?.jsonArray?.map { it.jsonPrimitive.content }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** feedback/more 的 Description 目标匹配：描述按空白切词，任一词命中 tags 或 fileName。 */
    fun matchesDescription(asset: MediaAsset, description: String): Boolean {
        val labels = asset.labels?.let { parseLabelTags(it) } ?: emptyList()
        val terms = description.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return false
        return terms.any { term ->
            labels.any { label -> label.contains(term, ignoreCase = true) } ||
                asset.fileName.contains(term, ignoreCase = true)
        }
    }
}
