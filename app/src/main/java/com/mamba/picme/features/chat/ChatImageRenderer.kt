@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.features.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.features.camera.toDevicePreference
import com.mamba.picme.features.camera.toInferenceBackendType
import com.mamba.picme.features.camera.toLandmarkDetectorType
import com.mamba.picme.features.camera.toRoiDetectorType
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.FaceDataConverter
import com.mamba.picme.features.editor.RecipeApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "PoLang:ChatImageRenderer"
private const val MAX_DECODE_DIM = 2048
private const val BYTE_COUNT_DENOMINATOR = 8
private const val CONTRAST_DEFAULT = 50f
private const val TEMPERATURE_NEUTRAL = 5000f

/**
 * Chat 内图像渲染器：把编辑 recipe 渲染成结果图并经 [chatImageStore] 落盘到私有缓存，
 * 返回可展示的 file:// 路径（不写入相册）。用户在预览页主动保存后才进相册。
 *
 * 背景：原先 chat 的「图像编辑 / AI 优化」指令会跳转 PhotoEditor 页执行；本类把
 * [RecipeApplier] 的渲染管线（裁剪 → GPU 调色/美颜/滤镜 → 抠图 → 标注）搬进 chat，
 * 使结果图作为一条 AI 图片消息直接在对话内返回，无需离开 chat 页。
 *
 * 美型生效：注入 [faceDetector] 后，[renderRecipe] 会先 [ensureFacePipeline] 初始化人脸
 * 检测管线，再对裁剪后的图做 [detectFace]，把真实关键点传入 GPU 管线，使瘦脸/大眼/唇色
 * 等依赖人脸定位的效果在 chat 内与编辑器入口行为一致。
 */
class ChatImageRenderer(
    private val context: Context,
    private val photoProcessor: PhotoProcessor,
    private val mattingEngine: MattingEngine,
    private val optimizeUseCase: AiOptimizeUseCase,
    private val chatImageStore: ChatImageStore,
    private val faceDetector: FaceDetector,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    data class Outcome(val imageUri: String?, val explanation: String)

    /** 人脸管线只需配置一次（进程级），未配置时 detectPhoto 会静默返回 null。 */
    @Volatile
    private var facePipelineConfigured = false

    /**
     * URI → 解码位图内存缓存：同一图片重复渲染（多次调整/优化同一张图）时命中缓存，
     * 跳过 openInputStream 与 BitmapFactory 解码。按字节计数，上限为堆内存 1/8。
     * 生命周期 = 本实例（AppContainer 单例），会话内有效。
     */
    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / BYTE_COUNT_DENOMINATOR).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 指令驱动调整：按 brightness/contrast/saturation/temperature 等显式参数渲染 → 落盘。
     *
     * 与 [aiOptimize]（场景检测+预设）不同——本方法直接把 LLM 传来的参数应用到 AdjustmentRecipe。
     *
     * @param brightness -100(暗)..100(亮)，0=不变
     * @param contrast 0..200，50=默认（不变）
     * @param saturation 0..200，100=默认（不变）
     * @param temperature 2000(冷)..8000(暖)，5000=默认（不变）
     */
    suspend fun adjustImage(
        imageUri: String,
        brightness: Float? = null,
        contrast: Float? = null,
        saturation: Float? = null,
        temperature: Float? = null,
        sessionId: String
    ): Outcome = withContext(dispatcher) {
        try {
            val recipe = EditRecipe(
                sourceUri = imageUri,
                adjustments = AdjustmentRecipe(
                    brightness = brightness ?: 0f,
                    contrast = contrast ?: 50f,
                    saturation = saturation ?: 100f,
                    temperature = temperature ?: 5000f
                )
            )
            val rendered = renderRecipe(imageUri, recipe, sessionId)
            val desc = buildString {
                brightness?.takeIf { it != 0f }?.let { append("亮度${if (it > 0) "+" else ""}${it.toInt()} ") }
                contrast?.takeIf { it != CONTRAST_DEFAULT }?.let { append("对比度${it.toInt()} ") }
                saturation?.takeIf { it != 100f }?.let { append("饱和度${it.toInt()} ") }
                temperature?.takeIf { it != TEMPERATURE_NEUTRAL }?.let {
                    append(if (it > TEMPERATURE_NEUTRAL) "暖色" else "冷色")
                    append(" ")
                }
            }.trim().ifBlank { "已调整" }
            Logger.i(TAG, "adjustImage: uri=$imageUri, rendered=$rendered, desc=$desc")
            Outcome(rendered, desc)
        } catch (e: Exception) {
            Logger.e(TAG, "adjustImage failed", e)
            Outcome(null, "调整失败：${e.message ?: "未知错误"}")
        }
    }

    /** AI 一键优化：分析场景 → 生成 recipe → 渲染 → 落盘，返回结果 uri 与说明。 */
    suspend fun aiOptimize(imageUri: String, sessionId: String): Outcome = withContext(dispatcher) {
        try {
            val result = optimizeUseCase.optimize(imageUri)
            val rendered = renderRecipe(imageUri, result.editRecipe, sessionId)
            Logger.i(TAG, "aiOptimize: uri=$imageUri, rendered=$rendered, explanation=${result.explanation}")
            Outcome(rendered, result.explanation)
        } catch (e: Exception) {
            Logger.e(TAG, "aiOptimize failed", e)
            Outcome(null, "优化失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 确保人脸检测管线已初始化（镜像 ChatEditProcessor.ensureFacePipeline 的做法）。
     *
     * 关键背景：FaceDetectorFactory.create() 后必须调 updatePipelineConfig()，
     * 否则 isPipelineInitialized=false，detectPhoto() 静默返回 null，
     * 导致瘦脸/大眼/唇色等依赖人脸关键点的效果被静默跳过。
     */
    private suspend fun ensureFacePipeline() {
        if (facePipelineConfigured) return
        val repository = userSettingsRepository ?: return
        runCatching {
            val roiStageConfig = repository.roiStageConfigFlow.first()
            val landmarkStageConfig = repository.landmarkStageConfigFlow.first()
            faceDetector.updatePipelineConfig(
                DetectionPipelineConfig(
                    roiDetector = roiStageConfig.modelType.toRoiDetectorType(),
                    landmarkDetector = landmarkStageConfig.modelType.toLandmarkDetectorType(),
                    roiEngine = roiStageConfig.engineType.toInferenceBackendType(),
                    landmarkEngine = landmarkStageConfig.engineType.toInferenceBackendType(),
                    roiDevice = roiStageConfig.devicePreference.toDevicePreference(),
                    landmarkDevice = landmarkStageConfig.devicePreference.toDevicePreference()
                )
            )
            facePipelineConfigured = true
            Logger.d(TAG, "Face detection pipeline initialized for chat image render")
        }.onFailure { error -> Logger.e(TAG, "Failed to initialize face detection pipeline for chat image render", error) }
    }

    /**
     * 对裁剪后的图做人脸检测，返回 GPU 管线所需的 [FaceData]；检测异常或无人脸返回 null。
     */
    private suspend fun detectFace(bitmap: Bitmap): FaceData? = withContext(dispatcher) {
        runCatching {
            faceDetector.detectPhoto(bitmap, lensFacing = 1)?.landmarks106?.let { landmarks ->
                FaceDataConverter.fromLandmarks106(landmarks, bitmap.width, bitmap.height)
            }
        }.onFailure { error -> Logger.w(TAG, "detectFace failed, face-driven effects will be skipped: ${error.message}") }
            .getOrNull()
    }

    /** 按 [recipe] 渲染原图 → 经 [chatImageStore] 落盘到私有缓存 → 返回 file:// 路径；任一步失败返回 null。 */
    suspend fun renderRecipe(imageUri: String, recipe: EditRecipe, sessionId: String): String? = withContext(dispatcher) {
        try {
            val bitmap = decodeBitmap(imageUri)
            Logger.i(TAG, "renderRecipe: decodeBitmap=${bitmap?.width}x${bitmap?.height}")
            if (bitmap == null) return@withContext null
            val applier = RecipeApplier(photoProcessor, dispatcher, mattingEngine)
            val cropped = applier.applyCrop(bitmap, recipe.crop)
            Logger.i(TAG, "renderRecipe: afterCrop=${cropped.width}x${cropped.height}")
            ensureFacePipeline()
            val faceData = detectFace(cropped)
            val processed = applier.applyGpuEffects(cropped, recipe, faceData)
            Logger.i(TAG, "renderRecipe: afterGpu=${processed?.width}x${processed?.height}")
            if (processed == null) return@withContext null
            val cutout = applier.applyCutout(processed, recipe.cutout)
            val marked = applier.applyMarkup(cutout, recipe.markup)
            val saved = chatImageStore.writeResult(sessionId, marked, "image/jpeg")
            Logger.i(TAG, "renderRecipe: saved=$saved")
            saved
        } catch (e: Exception) {
            Logger.e(TAG, "renderRecipe failed", e)
            null
        }
    }

    private fun decodeBitmap(imageUri: String): Bitmap? {
        bitmapCache.get(imageUri)?.let { cached ->
            Logger.d(TAG, "decodeBitmap cache hit: $imageUri")
            return cached
        }
        return try {
            val resolver = context.contentResolver
            val uri = if (imageUri.startsWith("/")) {
                Uri.fromFile(java.io.File(imageUri))
            } else {
                Uri.parse(imageUri)
            }
            resolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                val sample = if (maxDim > MAX_DECODE_DIM) maxDim / MAX_DECODE_DIM else 1
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }?.also { decoded -> bitmapCache.put(imageUri, decoded) }
        } catch (e: Exception) {
            Logger.e(TAG, "decodeBitmap failed", e)
            null
        }
    }
}
