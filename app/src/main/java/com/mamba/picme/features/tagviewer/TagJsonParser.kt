package com.mamba.picme.features.tagviewer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 将 [com.mamba.picme.data.model.MediaEntity.labels] 中的 JSON 字符串解析为 [ParsedTags]。
 *
 * 字段名对齐 TagGenerationScheduler.unifiedTagToJson 的输出：
 * face / scene / activity / objects / tags / qwenSummary。
 *
 * 容错：null / 空 / 非 JSON / 缺字段均不抛异常，返回 null（表示未打标或解析失败）。
 */
object TagJsonParser {

    fun parse(labels: String?): ParsedTags? {
        if (labels.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(labels)
            ParsedTags(
                scene = obj.optString("scene").trim(),
                activity = obj.optString("activity").trim(),
                objects = obj.optJSONArray("objects").toStringList(),
                tags = obj.optJSONArray("tags").toStringList(),
                summary = obj.optString("qwenSummary").trim(),
                face = obj.optJSONObject("face")?.let { faceObj ->
                    ParsedFaceInfo(
                        count = faceObj.optInt("count", 0),
                        selfie = faceObj.optBoolean("selfie", false),
                        groupPhoto = faceObj.optBoolean("groupPhoto", false),
                        personIds = faceObj.optJSONArray("personIds").toLongList()
                    )
                }
            )
        } catch (e: org.json.JSONException) {
            null
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val result = ArrayList<String>(length())
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) result.add(value)
        }
        return result
    }

    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        val result = ArrayList<Long>(length())
        for (index in 0 until length()) {
            result.add(optLong(index))
        }
        return result
    }
}
