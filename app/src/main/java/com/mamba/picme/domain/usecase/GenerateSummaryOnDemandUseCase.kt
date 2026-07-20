package com.mamba.picme.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.data.local.AppDatabase
import org.json.JSONObject

/**
 * 按需生成照片 summary。
 *
 * 用户点开照片详情时，若 labels.summary 为空，触发 SmolVLM 单张推理生成中文描述并写回
 * labels.summary（缓存，后续秒开）。
 *
 * 批量扫描不调用此 UseCase（批量 Pass3 用 ML Kit 标签，不加载 SmolVLM → 不发热）。
 */
class GenerateSummaryOnDemandUseCase(private val context: Context) {

    private val tag = "PoLang:SummaryOnDemand"

    suspend fun generateIfMissing(mediaId: Long): String? {
        val dao = AppDatabase.getDatabase(context).mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return null

        // 缓存命中：已有 summary 直接返回
        val existing = parseSummary(entity.labels)
        if (existing.isNotBlank()) return existing

        val uri = entity.uri
        val bitmap = loadBitmap(uri) ?: run {
            Logger.w(tag, "Failed to load bitmap for mediaId=$mediaId")
            return null
        }

        // 按需加载 SmolVLM（批量扫描不加载，仅此处按需）
        val orchestrator = AgentOrchestrator.getInstance(context)
        val engine = orchestrator.getLlmEngine()
        if (!engine.isLoaded) {
            val result = orchestrator.ensureModelLoaded(
                modelId = "smolvlm_500m",
                useOpencl = false,
                caller = "GenerateSummaryOnDemand"
            )
            if (result.isFailure) {
                Logger.w(tag, "SmolVLM load failed: ${result.exceptionOrNull()?.message}")
                return null
            }
        }

        val summary = engine.imageInference(
            bitmap = bitmap,
            systemPrompt = "你是一个照片描述助手，用一句流畅的中文描述照片的主要内容。",
            userPrompt = "请描述这张照片。",
            maxTokens = 128
        )

        if (summary.isBlank()) {
            Logger.w(tag, "Empty summary for mediaId=$mediaId")
            return null
        }

        // 写回 labels.summary（合并到现有 labels JSON，保留 ML Kit tags）
        val merged = mergeSummaryIntoLabels(entity.labels, summary)
        dao.updateLabels(mediaId, merged)
        Logger.i(tag, "Summary generated for mediaId=$mediaId: ${summary.take(60)}")
        return summary
    }

    private fun parseSummary(labelsJson: String?): String {
        if (labelsJson.isNullOrBlank()) return ""
        return try {
            JSONObject(labelsJson).optString("qwenSummary", "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun mergeSummaryIntoLabels(labelsJson: String?, summary: String): String {
        val obj = if (labelsJson.isNullOrBlank()) JSONObject() else JSONObject(labelsJson)
        obj.put("qwenSummary", summary)
        return obj.toString()
    }

    private fun loadBitmap(uri: String): Bitmap? = try {
        val contentUri = Uri.parse(uri)
        context.contentResolver.openInputStream(contentUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    } catch (e: Exception) {
        null
    }
}
