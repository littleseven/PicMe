package com.mamba.picme.domain.usecase

import android.content.Context
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.TagGenerationScheduler
import org.json.JSONObject

/**
 * 按需为单张照片生成「统一规格」标签。
 *
 * 照片详情打开时触发：若 labels 不满足统一规格（face/scene/activity/objects/tags/summary），
 * 走与批量 Pass3、「重新打标」同源的 [TagGenerationScheduler.processSingleSync] 全量管道，
 * 产出完整统一 JSON 并写回 labels；已具备统一 tags + summary 的照片直接缓存返回，不重复推理。
 *
 * 历史背景：本 UseCase 曾（已回退的 ML Kit 方案）只写 `{"summary":"…"}` 自然语言桩，
 * 导致新增/生成图片的标签只有摘要、不符合统一规格——现统一改走完整管道。
 *
 * @param tagGenerationScheduler 统一打标调度器。为 null（未注入）时无法生成统一标签，
 * 返回 null 且不写半成品，留待批量扫描或模型就绪后再打标。
 */
class GenerateSummaryOnDemandUseCase(
    private val context: Context,
    private val tagGenerationScheduler: TagGenerationScheduler? = null
) {

    private val tag = "PoLang:SummaryOnDemand"

    /**
     * 若 [mediaId] 尚未具备统一规格标签，则按需生成并写回。
     *
     * @return 写入后的 summary 文本；已缓存时直接返回；管道不可用/失败返回 null（不写半成品）。
     */
    suspend fun generateIfMissing(mediaId: Long): String? {
        val dao = AppDatabase.getDatabase(context).mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return null

        // 缓存命中：已具备统一 tags 结构且 summary 非空。
        if (isFullyTagged(entity.labels)) {
            return parseSummary(entity.labels)
        }

        val uri = entity.uri
        val scheduler = tagGenerationScheduler
        if (scheduler == null) {
            Logger.w(tag, "TagGenerationScheduler not wired, skip unified tagging for mediaId=$mediaId")
            return null
        }

        // 走完整统一规格管道（与 Pass3 / 重新打标同源），产出
        // {face,scene,activity,objects,tags,summary} 并写回 labels。
        val unifiedJson = scheduler.processSingleSync(uri)
        if (unifiedJson == null) {
            Logger.w(tag, "processSingleSync returned null for mediaId=$mediaId (model unavailable?)")
            return null
        }
        Logger.i(tag, "Unified tags generated for mediaId=$mediaId: ${unifiedJson.take(80)}")
        return parseSummary(unifiedJson)
    }

    companion object {
        /**
         * 判断 labels 是否已满足「统一规格」：含 tags 数组且 summary 非空。
         *
         * 用于区分：
         * - 统一规格（Pass3/processSingleSync 产物）→ 含 tags 数组；
         * - summary-only 半成品桩（`{"summary":"…"}`）→ 无 tags 数组 → 需重打标。
         */
        internal fun isFullyTagged(labelsJson: String?): Boolean {
            if (labelsJson.isNullOrBlank()) return false
            return try {
                val obj = JSONObject(labelsJson)
                obj.optJSONArray("tags") != null && obj.optString("summary", "").isNotBlank()
            } catch (e: Exception) {
                false
            }
        }

        /** 从统一规格 labels JSON 中提取 summary 字段（缺失/异常返回空串）。 */
        internal fun parseSummary(labelsJson: String?): String {
            if (labelsJson.isNullOrBlank()) return ""
            return try {
                JSONObject(labelsJson).optString("summary", "")
            } catch (e: Exception) {
                ""
            }
        }
    }
}
