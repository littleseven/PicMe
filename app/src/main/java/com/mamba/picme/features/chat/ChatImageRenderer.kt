package com.mamba.picme.features.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.RecipeApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "PoLang:ChatImageRenderer"
private const val MAX_DECODE_DIM = 2048

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

    /** AI 一键优化：分析场景 → 生成 recipe → 渲染 → 落盘，返回结果 uri 与说明。 */
    suspend fun aiOptimize(imageUri: String): Outcome = withContext(dispatcher) {
        try {
            val result = optimizeUseCase.fastOptimize(imageUri)
            val rendered = renderRecipe(imageUri, result.editRecipe)
            Outcome(rendered, result.explanation)
        } catch (e: Exception) {
            Logger.e(TAG, "aiOptimize failed", e)
            Outcome(null, "优化失败：${e.message ?: "未知错误"}")
        }
    }

    /** 按 [recipe] 渲染原图 → 落盘 → 返回结果文件路径；任一步失败返回 null。 */
    suspend fun renderRecipe(imageUri: String, recipe: EditRecipe): String? = withContext(dispatcher) {
        try {
            val bitmap = decodeBitmap(imageUri) ?: return@withContext null
            val applier = RecipeApplier(photoProcessor, dispatcher, mattingEngine)
            val cropped = applier.applyCrop(bitmap, recipe.crop)
            val processed = applier.applyGpuEffects(cropped, recipe, faceData = null)
            val cutout = applier.applyCutout(processed, recipe.cutout)
            val marked = applier.applyMarkup(cutout, recipe.markup)
            saveBitmap(marked)
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
        java.io.FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        "file://${file.absolutePath}"
    } catch (e: Exception) {
        Logger.e(TAG, "saveBitmap failed", e)
        null
    }
}
