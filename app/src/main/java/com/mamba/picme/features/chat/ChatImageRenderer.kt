@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.features.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.RecipeApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "PoLang:ChatImageRenderer"
private const val MAX_DECODE_DIM = 2048
private const val CONTRAST_DEFAULT = 50f
private const val TEMPERATURE_NEUTRAL = 5000f
private const val JPEG_QUALITY = 95

/**
 * Chat 内图像渲染器：把编辑 recipe 直接渲染成结果图并落盘，返回可展示的本地路径。
 *
 * 背景：原先 chat 的「图像编辑 / AI 优化」指令会跳转 PhotoEditor 页执行；本类把
 * [RecipeApplier] 的渲染管线（裁剪 → GPU 调色/美颜/滤镜 → 抠图 → 标注）搬进 chat，
 * 使结果图作为一条 AI 图片消息直接在对话内返回，无需离开 chat 页。
 *
 * 注：当前 faceData=null（不做人脸检测），美型中依赖人脸定位的瘦脸/大眼等不生效，
 * 但磨皮 / 调色 / 滤镜等全图效果正常；如需完整美型，后续注入 FaceDetector 即可。
 */
class ChatImageRenderer(
    private val context: Context,
    private val photoProcessor: PhotoProcessor,
    private val mattingEngine: MattingEngine,
    private val optimizeUseCase: AiOptimizeUseCase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    data class Outcome(val imageUri: String?, val explanation: String)

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
        temperature: Float? = null
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
            val rendered = renderRecipe(imageUri, recipe)
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
    suspend fun aiOptimize(imageUri: String): Outcome = withContext(dispatcher) {
        try {
            val result = optimizeUseCase.fastOptimize(imageUri)
            val rendered = renderRecipe(imageUri, result.editRecipe)
            Logger.i(TAG, "aiOptimize: uri=$imageUri, rendered=$rendered, explanation=${result.explanation}")
            Outcome(rendered, result.explanation)
        } catch (e: Exception) {
            Logger.e(TAG, "aiOptimize failed", e)
            Outcome(null, "优化失败：${e.message ?: "未知错误"}")
        }
    }

    /** 按 [recipe] 渲染原图 → 落盘 → 返回结果文件路径；任一步失败返回 null。 */
    suspend fun renderRecipe(imageUri: String, recipe: EditRecipe): String? = withContext(dispatcher) {
        try {
            val bitmap = decodeBitmap(imageUri)
            Logger.i(TAG, "renderRecipe: decodeBitmap=${bitmap?.width}x${bitmap?.height}")
            if (bitmap == null) return@withContext null
            val applier = RecipeApplier(photoProcessor, dispatcher, mattingEngine)
            val cropped = applier.applyCrop(bitmap, recipe.crop)
            Logger.i(TAG, "renderRecipe: afterCrop=${cropped.width}x${cropped.height}")
            val processed = applier.applyGpuEffects(cropped, recipe, faceData = null)
            Logger.i(TAG, "renderRecipe: afterGpu=${processed?.width}x${processed?.height}")
            if (processed == null) return@withContext null
            val cutout = applier.applyCutout(processed, recipe.cutout)
            val marked = applier.applyMarkup(cutout, recipe.markup)
            val saved = saveBitmap(marked)
            Logger.i(TAG, "renderRecipe: saved=$saved")
            saved
        } catch (e: Exception) {
            Logger.e(TAG, "renderRecipe failed", e)
            null
        }
    }

    private fun decodeBitmap(imageUri: String): Bitmap? = try {
        val resolver = context.contentResolver
        val uri = if (imageUri.startsWith("/")) {
            Uri.fromFile(java.io.File(imageUri))
        } else {
            Uri.parse(imageUri)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = if (maxDim > MAX_DECODE_DIM) maxDim / MAX_DECODE_DIM else 1
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, opts) }
    } catch (e: Exception) {
        Logger.e(TAG, "decodeBitmap failed", e)
        null
    }

    private fun saveBitmap(bitmap: Bitmap): String? = try {
        val dir = java.io.File(context.filesDir, "picme_images").apply { mkdirs() }
        val file = java.io.File(dir, "edit_${UUID.randomUUID()}.jpg")
        java.io.FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        // 同时写入系统相册（MediaStore），让用户在相册中可见
        val displayName = "PoLang_edit_${UUID.randomUUID()}.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.provider.MediaStore.Images.Media.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
        } else {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val itemUri = context.contentResolver.insert(collection, values)
        if (itemUri != null) {
            context.contentResolver.openOutputStream(itemUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
            }
            Logger.i(TAG, "Saved to gallery: $itemUri")
        }
        "file://${file.absolutePath}"
    } catch (e: Exception) {
        Logger.e(TAG, "saveBitmap failed", e)
        null
    }
}
