package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 图片 AI 标签索引 Worker
 *
 * ## 废弃说明
 * 标签生成功能已整合到 [TagGenerationScheduler] 的 3-Pass 混合管道中（Pass 3: Qwen 标签生成）。
 * 请使用 [TagGenerationScheduler.scanAll] 或 [TagGenerationScheduler.scanIncremental] 替代。
 *
 * 使用本地多模态 LLM (Qwen3.5-2B-MNN) 理解相册图片内容，
 * 生成中文标签并写入 TagEntity + MediaTagCrossRef 规范化表。
 *
 * 流程:
 * 1. 查询 labels IS NULL 的未标记媒体
 * 2. 逐张加载 Bitmap → resize (最长边 MAX_IMAGE_SIZE px)
 * 3. 调用 LocalLlmEngine.imageInference(bitmap, prompt)
 * 4. 解析模型返回的标签文本 → 写入 DB
 * 5. 同时更新 MediaEntity.labels（兼容旧搜索路径）
 *
 * 触发时机: Gallery 首次加载、下拉刷新
 * 约束: 需 Vision 模型已加载、节流 THROTTLE_MS/张
 *
 * @deprecated 标签生成已整合到 TagGenerationScheduler 的 3-Pass 混合管道中
 */
@Deprecated("标签生成已整合到 TagGenerationScheduler 的 3-Pass 混合管道中")
class ImageTagIndexingWorker(
    private val context: Context,
    private val localLlmEngine: LocalLlmEngine,
    private val modelKey: String = "qwen3_5_2b",
    private val useOpencl: Boolean = false
) {

    companion object {
        private const val TAG = "PoLang:ImageTag"
        private const val MAX_IMAGE_SIZE = 420
        private const val THROTTLE_MS = 200L

        /** 图片标签生成的 system prompt */
        val TAGGING_SYSTEM_PROMPT = """
你是一个图像内容分析助手。你的任务是用中文简短描述图片的内容，输出逗号分隔的标签。

规则：
1. 只输出标签，用中文逗号或英文逗号分隔
2. 标签应简短（2-5个字），例如：猫、户外、阳光、草地、食物、建筑
3. 描述图片中的主要物体、场景、氛围
4. 不超过 10 个标签
5. 不要输出任何解释或其他文字
        """.trimIndent()

        /** 标签生成的 user prompt */
        const val TAGGING_USER_PROMPT = "请用中文标签描述这张图片的内容"

        /** 分批加载未标记媒体的批量大小，避免一次性加载全部 [MediaEntity] 导致 Java Heap OOM。 */
        private const val TAGGING_BATCH_SIZE = 50
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    val isRunning: Boolean
        get() = currentJob?.isActive == true

    /** 取消当前标签任务 */
    fun cancel() {
        currentJob?.cancel()
        Logger.i(TAG, "Tag indexing cancelled by user")
    }

    /**
     * 启动批量标签索引（如已有任务运行则忽略）。
     */
    fun start() {
        if (currentJob?.isActive == true) {
            Logger.d(TAG, "Tag indexing already in progress")
            return
        }
        currentJob = scope.launch {
            Logger.i(TAG, "AI tag indexing started")
            doBatchTagging()
            Logger.i(TAG, "AI tag indexing completed")
        }
    }

    /**
     * 强制重新标记所有媒体（清空已有标签后全量重标）。
     */
    fun forceReTag() {
        if (currentJob?.isActive == true) {
            Logger.w(TAG, "Tag indexing in progress, cancelling and restarting in force mode")
            currentJob?.cancel()
        }
        currentJob = scope.launch {
            Logger.i(TAG, "Force re-tag: resetting all labels")
            val db = AppDatabase.getDatabase(context)
            db.mediaDao().resetAllLabels()
            doBatchTagging()
            Logger.i(TAG, "Force re-tag completed")
        }
    }

    private suspend fun doBatchTagging() {
        // 0. 确保模型已加载
        val orchestrator = com.mamba.picme.agent.core.facade.AgentOrchestrator.getInstance(context)
        val loadResult = orchestrator.ensureModelLoaded(
            modelId = modelKey,
            useOpencl = useOpencl,
            caller = "ImageTagIndexingWorker:doBatchTagging"
        )
        if (loadResult.isFailure) {
            Logger.w(TAG, "Cannot start tagging: LLM model not loaded and could not be loaded")
            return
        }

        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaDao()
        val tagDao = db.tagDao()
        val tagIndexUpdater = TagIndexUpdater(tagDao)

        val unlabeledIds = dao.getUnlabeledMediaIds()
        if (unlabeledIds.isEmpty()) {
            Logger.i(TAG, "All media already tagged")
            return
        }

        Logger.i(TAG, "Found ${unlabeledIds.size} media without AI tags")

        var taggedCount = 0
        unlabeledIds.chunked(TAGGING_BATCH_SIZE).forEach { batchIds ->
            val batchEntities = dao.getMediaByIds(batchIds)
            for (entity in batchEntities) {
                if (currentJob?.isActive != true) {
                    Logger.i(TAG, "Tag indexing cancelled after $taggedCount items")
                    return@doBatchTagging
                }

                try {
                    val bitmap = loadBitmapForVision(entity.uri)
                    if (bitmap == null) {
                        Logger.w(TAG, "Failed to load bitmap: ${entity.uri}")
                        continue
                    }

                    val response = try {
                        localLlmEngine.imageInference(
                            bitmap = bitmap,
                            systemPrompt = TAGGING_SYSTEM_PROMPT,
                            userPrompt = TAGGING_USER_PROMPT,
                            maxTokens = 64
                        )
                    } finally {
                        bitmap.recycle()
                    }

                    if (response.isBlank()) {
                        Logger.w(TAG, "Empty response for media ${entity.id}")
                        continue
                    }

                    Logger.i(TAG, "识别结果 [media ${entity.id}]: $response")

                    // 解析标签: "猫, 户外, 阳光, 草地" → ["猫","户外","阳光","草地"]
                    val labels = parseLabels(response)
                    if (labels.isEmpty()) {
                        Logger.d(TAG, "No valid labels parsed from response")
                        continue
                    }

                    // 写入规范化标签表
                    val labelsJson = JSONArray(labels.toList()).toString()
                    tagIndexUpdater.updateIndex(entity.id, labelsJson)

                    // 同时更新 MediaEntity.labels（兼容旧 LIKE 搜索）
                    dao.updateLabels(entity.id, labelsJson)

                    taggedCount++
                    Logger.d(TAG, "Tagged media ${entity.id}: $labels (${taggedCount}/${unlabeledIds.size})")

                    // 节流，防止连续推理导致过热
                    delay(THROTTLE_MS)
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to tag media ${entity.id}: ${e.message}")
                }
            }
        }

        Logger.i(TAG, "Tag indexing done: $taggedCount/${unlabeledIds.size} tagged")
    }

    /**
     * 加载 Bitmap 并缩放到适合 vision encoder 的尺寸（最长边 MAX_IMAGE_SIZE px）。
     *
     * 两次打开 stream：第一次仅解码尺寸计算 sampleSize，第二次实际解码。
     */
    private fun loadBitmapForVision(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            // 第一次：仅解码尺寸
            val dimensions = context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                opts.outWidth to opts.outHeight
            } ?: return null

            val (rawWidth, rawHeight) = dimensions
            if (rawWidth <= 0 || rawHeight <= 0) return null
            val sampleSize = calculateInSampleSize(rawWidth, rawHeight, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)

            Logger.d(TAG, "Loading bitmap: ${rawWidth}x${rawHeight} → sampleSize=$sampleSize")

            // 第二次：实际解码
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load bitmap: $uriString", e)
            null
        }
    }

    /**
     * 计算 BitmapFactory 的 inSampleSize（必须是 2 的幂）。
     */
    private fun calculateInSampleSize(
        rawWidth: Int, rawHeight: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var sampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / sampleSize) >= reqHeight &&
                   (halfWidth / sampleSize) >= reqWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * 解析模型返回的标签文本。
     *
     * 输入示例: "猫, 户外, 阳光, 草地" 或 "猫，户外，阳光，草地"
     * 输出: ["猫", "户外", "阳光", "草地"]
     */
    internal fun parseLabels(response: String): List<String> {
        return response
            .replace("，", ",")
            .replace("\n", ",")
            .replace("、", ",")
            .split(",")
            .map { label ->
                label.trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
                    .removePrefix("「")
                    .removeSuffix("」")
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .replace(Regex("^\\d+[.、．]\\s*"), "")
            }
            .filter { label ->
                label.isNotEmpty() &&
                label.length <= 10 &&
                !label.startsWith("标签") &&
                !label.contains("输出") &&
                !label.contains("以下") &&
                !label.matches(Regex("^[\\d.]+$"))
            }
            .take(10)
    }
}
