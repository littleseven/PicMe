package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.OptimizeFeedbackDao
import com.mamba.picme.data.local.entity.OptimizeFeedbackEntity
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 抽卡反馈落库（spec §7）。
 *
 * 自动选优（AiOptimizeUseCase）与用户手选/关闭（PhotoEditorViewModel）共用。
 * 落库失败只记日志，绝不影响主流程。
 */
class OptimizeFeedbackLogger(private val dao: OptimizeFeedbackDao?) {

    companion object {
        private const val TAG = "PoLang:OptimizeGacha"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_USER = "user"
        const val SOURCE_DISMISS = "dismiss"
    }

    /**
     * @param selectedIndex 选中的卡序号；-1 = KeepOriginal / 未选择
     * @param source [SOURCE_AUTO] / [SOURCE_USER] / [SOURCE_DISMISS]
     */
    suspend fun log(
        imageUri: String,
        scene: Scene,
        all: List<ScoredCandidate>,
        selectedIndex: Int,
        source: String
    ) {
        val d = dao ?: return
        try {
            d.insert(
                OptimizeFeedbackEntity(
                    imageKey = imageKey(imageUri),
                    scene = scene.name,
                    candidatesJson = candidatesToJson(all),
                    selectedIndex = selectedIndex,
                    selectionSource = source,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Logger.e(TAG, "feedback insert failed", e)
        }
    }

    /** 图片 URI → SHA-256 前 16 位（不存原始路径，spec §7）。 */
    fun imageKey(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 候选卡组 → JSON（含参数、NIMA 分、护栏淘汰标记，供 Phase 2 个性化消费）。 */
    fun candidatesToJson(all: List<ScoredCandidate>): String {
        val arr = JSONArray()
        all.forEach { sc ->
            val p = sc.candidate.preset
            arr.put(
                JSONObject().apply {
                    put("index", sc.candidate.index)
                    put("direction", sc.candidate.direction)
                    put("nimaScore", sc.nimaScore?.toDouble() ?: JSONObject.NULL)
                    put("rejected", sc.rejected)
                    put("rejectReason", sc.rejectReason ?: JSONObject.NULL)
                    put("beauty", JSONObject().apply {
                        put("smoothing", p.beauty.smoothing.toDouble())
                        put("whitening", p.beauty.whitening.toDouble())
                    })
                    put("filter", p.filter.colorFilter)
                    put("adjustment", JSONObject().apply {
                        put("brightness", p.adjustment.brightness.toDouble())
                        put("exposure", p.adjustment.exposure.toDouble())
                        put("contrast", p.adjustment.contrast.toDouble())
                        put("saturation", p.adjustment.saturation.toDouble())
                        put("temperature", p.adjustment.temperature.toDouble())
                        put("tint", p.adjustment.tint.toDouble())
                    })
                }
            )
        }
        return arr.toString()
    }
}
