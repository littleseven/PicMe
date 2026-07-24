package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 相册搜索的纯逻辑支持：in-set 过滤 + Room 持久化编解码。
 *
 * 抽离自 ChatViewModel 以便 JVM 单测（避免加载 Android 依赖）。
 * 全部基于 runtime-core 的 [MediaAsset]（与 MediaSearchEngine / MediaPager 一致）。
 */
object ChatGallerySearch {

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
     * 多轮 refine 的结果解析：取 searchEngine 全库命中与上一轮结果集 [prior] 的交集；
     * 交集为空时回退到 [filterInSet]（标签子串 + hasFace）兜底。
     *
     * 让 refine 享受 searchEngine 的语义/结构化能力，又不丢 in-set 收敛语义。
     */
    fun resolveRefine(
        prior: List<MediaAsset>,
        searchHits: List<MediaAsset>,
        constraint: String
    ): List<MediaAsset> {
        // 优先 filterInSet（标签子串 + hasFace 等结构化字段，精准）；
        // filterInSet 空（如「女性」标签字面不匹配）时，回退到 searchEngine 语义命中 ∩ prior。
        val inSet = filterInSet(prior, constraint)
        if (inSet.isNotEmpty()) return inSet
        val priorIds = prior.map { asset -> asset.id }.toSet()
        return searchHits.filter { hit -> hit.id in priorIds }
    }

    /**
     * 清理 refine constraint：去掉「只保留/只要/其中的/的照片」等口语前缀后缀，
     * 提取核心词，避免整句（如「只保留女性」）导致下游 searchEngine 翻译/语义失配。
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
        // 性别归一：标签体系用单字「女/男」（labels/summary 里是「一位女士」「女」等），
        // 把「女性/女人/女孩」等归一到单字，使 filterInSet 标签子串命中（否则「女性」≠「女」漏报）。
        val genderMap = mapOf(
            "女性" to "女", "女人" to "女", "女孩" to "女", "少女" to "女", "女生" to "女",
            "男性" to "男", "男人" to "男", "男孩" to "男", "少年" to "男", "男生" to "男"
        )
        genderMap[cleaned]?.let { normalized -> cleaned = normalized }
        return cleaned
    }

    /** 把展示用 assets 序列化为 Room content JSON（id/uri/type/captureDate/fileName）。 */
    fun serializeContent(assets: List<MediaAsset>): String {
        val arr = JSONArray()
        for (a in assets) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("uri", a.uri)
            o.put("type", a.type.name)
            o.put("captureDate", a.captureDate)
            o.put("fileName", a.fileName)
            arr.put(o)
        }
        return arr.toString()
    }

    /** query/totalCount/isRefinement 存入 metadata JSON。 */
    fun serializeMetadata(query: String, totalCount: Int, isRefinement: Boolean): String =
        JSONObject()
            .put("query", query)
            .put("totalCount", totalCount)
            .put("isRefinement", isRefinement)
            .toString()

    /** 从 Room content + metadata 反序列化为 MediaResultsUi（重建最小 MediaAsset）。 */
    fun deserialize(content: String, metadata: String?): MediaResultsUi {
        val arr = JSONArray(content)
        val assets = ArrayList<MediaAsset>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assets.add(
                MediaAsset(
                    id = o.getLong("id"),
                    uri = o.getString("uri"),
                    type = runCatching { MediaType.valueOf(o.optString("type", "PHOTO")) }
                        .getOrDefault(MediaType.PHOTO),
                    captureDate = o.getLong("captureDate"),
                    fileName = o.optString("fileName", "")
                )
            )
        }
        val meta = metadata?.let { runCatching { JSONObject(it) }.getOrNull() }
        return MediaResultsUi(
            query = meta?.optString("query", "") ?: "",
            assets = assets,
            totalCount = meta?.optInt("totalCount", assets.size) ?: assets.size,
            isRefinement = meta?.optBoolean("isRefinement", false) ?: false
        )
    }
}
