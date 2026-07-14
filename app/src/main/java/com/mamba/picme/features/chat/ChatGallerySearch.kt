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
        return assets.filter { a ->
            listOfNotNull(a.labels, a.ocrText, a.locationName, a.fileName)
                .any { it.contains(kw, ignoreCase = true) }
        }
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
