@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.image.BitmapSampling
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.features.camera.toDevicePreference
import com.mamba.picme.features.camera.toInferenceBackendType
import com.mamba.picme.features.camera.toLandmarkDetectorType
import com.mamba.picme.features.camera.toRoiDetectorType
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.FaceDataConverter
import com.mamba.picme.features.editor.RecipeApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "ChatEditProcessor"
private const val CHAT_EDIT_MAX_PX = 2048

class ChatEditProcessor(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val chatImageStore: ChatImageStore,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val recipeApplierFactory: (PhotoProcessor, CoroutineDispatcher) -> RecipeApplier = ::RecipeApplier
) {

    private val photoProcessingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** 人脸管线只需配置一次（进程级），未配置时 detectPhoto 会静默返回 null。 */
    @Volatile
    private var facePipelineConfigured = false

    /**
     * 确保人脸检测管线已初始化（镜像 PhotoEditorViewModel 的做法）。
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
            Logger.d(TAG, "Face detection pipeline initialized for chat edit")
        }.onFailure { Logger.e(TAG, "Failed to initialize face detection pipeline for chat edit", it) }
    }

    /**
     * 执行编辑并把结果图交给 [chatImageStore] 落盘到私有缓存（不写入相册）。
     *
     * @return 结果图 file:// 路径，失败时返回异常
     */
    suspend fun execute(context: Context, sourceUri: String, recipe: EditRecipe, sessionId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                ensureFacePipeline()
                val normalizedUri = normalizeSourceUri(sourceUri)
                val fullBitmap = decodeFullBitmap(context, Uri.parse(normalizedUri))
                    ?: return@withContext Result.failure(IllegalStateException("无法加载原图: $sourceUri"))

                val applier = recipeApplierFactory(photoProcessor, photoProcessingDispatcher)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val faceData = detectFace(cropped)
                val processed = applier.applyGpuEffects(cropped, recipe, faceData)
                val outputUri = chatImageStore.writeResult(sessionId, processed, "image/jpeg")
                Result.success(outputUri)
            } catch (e: Exception) {
                Logger.e(TAG, "Chat edit failed", e)
                Result.failure(e)
            }
        }
    }

    private fun decodeFullBitmap(context: Context, uri: Uri): Bitmap? {
        // 聊天修图结果发送至会话（非全分辨率导出）；降采样到 2048 足够且避免 OOM。
        return BitmapSampling.decodeStream(
            { context.contentResolver.openInputStream(uri) },
            CHAT_EDIT_MAX_PX
        )
    }

    /**
     * 兼容 ChatViewModel.persistImage 返回的绝对路径（无 scheme）。
     * 将裸路径转换为 file:// URI，以便 ContentResolver 或 BitmapFactory 正确加载。
     */
    private fun normalizeSourceUri(sourceUri: String): String {
        return when {
            sourceUri.startsWith("file://") || sourceUri.startsWith("content://") ||
                sourceUri.startsWith("android.resource://") -> sourceUri
            sourceUri.startsWith("/") -> "file://$sourceUri"
            else -> sourceUri
        }
    }

    private suspend fun detectFace(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        runCatching {
            faceDetector.detectPhoto(bitmap, lensFacing = 1)?.landmarks106?.let { landmarks ->
                FaceDataConverter.fromLandmarks106(landmarks, bitmap.width, bitmap.height)
            }
        }.onSuccess { faceData ->
            if (faceData == null) {
                Logger.w(TAG, "Face detection returned null, face-driven effects (slimFace/lipColor 等) 将被跳过")
            }
        }.getOrNull()
    }
}
