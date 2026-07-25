package com.mamba.picme.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.beauty.api.toBeautyParams
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.FaceDataConverter
import com.mamba.picme.features.editor.RecipeApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "ChatEditProcessor"

class ChatEditProcessor(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val mediaRepository: MediaRepository,
    private val outputCollectionUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val recipeApplierFactory: (PhotoProcessor, CoroutineDispatcher) -> RecipeApplier = ::RecipeApplier
) {

    private val photoProcessingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * 执行编辑并保存结果图。
     *
     * @return 保存后的图片 URI，失败时返回异常
     */
    suspend fun execute(context: Context, sourceUri: String, recipe: EditRecipe): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUri = normalizeSourceUri(sourceUri)
                val fullBitmap = decodeFullBitmap(context, Uri.parse(normalizedUri))
                    ?: return@withContext Result.failure(IllegalStateException("无法加载原图: $sourceUri"))

                val applier = recipeApplierFactory(photoProcessor, photoProcessingDispatcher)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val faceData = detectFace(cropped)
                val processed = applier.applyGpuEffects(cropped, recipe, faceData)
                val outputUri = saveBitmapToMediaStore(context, processed)

                if (outputUri != null) {
                    mediaRepository.refreshMediaLibrary()
                    Result.success(outputUri)
                } else {
                    Result.failure(IllegalStateException("保存结果图失败"))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Chat edit failed", e)
                Result.failure(e)
            }
        }
    }

    private fun decodeFullBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Decode full bitmap failed", e)
            null
        }
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
        }.getOrNull()
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap): String? {
        val name = "CHAT_EDIT_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
            }
        }
        val uri = context.contentResolver.insert(outputCollectionUri, values)
        return uri?.also {
            context.contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }?.toString()
    }
}
