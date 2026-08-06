package com.mamba.picme.features.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * chat 抽卡候选卡组消息负载（type=[MESSAGE_TYPE] 消息的 metadata JSON）。
 *
 * 只存展示数据（缩略图路径 / NIMA 分 / 方向标签）；候选 preset 保存在
 * [ChatOptimizeGachaController] 的进程级内存态，不落消息——进程重建后
 * 内存态丢失，卡条由 UI 降级为只读展示（spec §4）。
 */
data class OptimizeCandidateGroup(
    val sourceImageUri: String,
    val scene: String,
    /** NIMA 最优卡 index；-1 = KeepOriginal 不预选 */
    val recommendedIndex: Int,
    val candidates: List<Candidate>,
    /** 「换一组」回传 exclude 的去重指纹 */
    val usedFingerprints: List<String>,
    /** 第几组（换一组 +1） */
    val drawIndex: Int
) {
    /**
     * 单张候选卡的展示数据。
     *
     * @property thumbPath ChatImageStore 落盘的 512px 候选图 file:// 路径；空串 = 落盘失败（UI 显示占位）
     * @property nimaScore NIMA 美学分；null = 未评分（护栏淘汰 / 推理失败）
     */
    data class Candidate(
        val direction: String,
        val thumbPath: String,
        val nimaScore: Float?,
        val rejected: Boolean
    )

    fun toJson(): String {
        val arr = JSONArray()
        candidates.forEach { c ->
            arr.put(JSONObject().apply {
                put("direction", c.direction)
                put("thumbPath", c.thumbPath)
                if (c.nimaScore != null) put("nimaScore", c.nimaScore.toDouble())
                put("rejected", c.rejected)
            })
        }
        return JSONObject().apply {
            put("sourceImageUri", sourceImageUri)
            put("scene", scene)
            put("recommendedIndex", recommendedIndex)
            put("drawIndex", drawIndex)
            put("candidates", arr)
            put("usedFingerprints", JSONArray(usedFingerprints))
        }.toString()
    }

    companion object {
        const val MESSAGE_TYPE = "optimize_candidates"

        /** 解析失败 / 必需字段缺失返回 null（调用方按无负载处理，不崩溃）。 */
        fun fromJson(json: String?): OptimizeCandidateGroup? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(json)
                val arr = obj.getJSONArray("candidates")
                val candidates = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    Candidate(
                        direction = c.optString("direction"),
                        thumbPath = c.optString("thumbPath"),
                        nimaScore = if (c.has("nimaScore")) runCatching { c.getDouble("nimaScore").toFloat() }.getOrNull() else null,
                        rejected = c.optBoolean("rejected", false)
                    )
                }
                val fps = obj.optJSONArray("usedFingerprints")
                OptimizeCandidateGroup(
                    sourceImageUri = obj.getString("sourceImageUri"),
                    scene = obj.optString("scene"),
                    recommendedIndex = obj.optInt("recommendedIndex", -1),
                    candidates = candidates,
                    usedFingerprints = if (fps == null) emptyList() else (0 until fps.length()).map { fps.optString(it) },
                    drawIndex = obj.optInt("drawIndex", 1)
                )
            }.getOrNull()
        }
    }
}
