@file:Suppress("SwallowedException") // 解析失败返回 null 是预期行为

package com.mamba.picme.features.tagviewer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 将 [com.mamba.picme.data.model.MediaEntity.labels] 中的 JSON 字符串解析为 [ParsedTags]。
 *
 * 字段名对齐 TagGenerationScheduler.unifiedTagToJson 的输出：
 * face / scene / activity / objects / tags / summary。
 *
 * 容错：null / 空 / 非 JSON / 缺字段均不抛异常，返回 null（表示未打标或解析失败）。
 */
object TagJsonParser {

    fun parse(labels: String?): ParsedTags? {
        if (labels.isNullOrBlank()) return null
        val trimmed = labels.trim()
        return try {
            if (trimmed.startsWith("[")) {
                // 兼容旧版 MediaIndexingWorker 写入的 JSON 数组格式，如 ["猫","户外","食物"]
                val tags = JSONArray(trimmed).toStringList()
                if (tags.isEmpty()) return null
                ParsedTags(tags = tags)
            } else {
                val obj = JSONObject(trimmed)
                ParsedTags(
                    scene = obj.optString("scene").trim(),
                    activity = obj.optString("activity").trim(),
                    objects = obj.optJSONArray("objects").toStringList(),
                    tags = obj.optJSONArray("tags").toStringList(),
                    summary = obj.optString("summary").trim(),
                    face = obj.optJSONObject("face")?.let { faceObj ->
                        ParsedFaceInfo(
                            count = faceObj.optInt("count", 0),
                            selfie = faceObj.optBoolean("selfie", false),
                            groupPhoto = faceObj.optBoolean("groupPhoto", false),
                            personIds = faceObj.optJSONArray("personIds").toLongList()
                        )
                    }
                )
            }
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
