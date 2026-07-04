package com.mamba.picme.features.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.PhotoProcessException
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.beauty.api.toAndroidColorMatrix
import com.mamba.picme.beauty.api.toBeautyParams
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RecipeApplier"

class RecipeApplier(
    private val photoProcessor: PhotoProcessor,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /**
     * Apply crop/rotate/flip to a bitmap.
     */
    fun applyCrop(bitmap: Bitmap, crop: CropRecipe): Bitmap {
        val matrix = Matrix().apply {
            postRotate(crop.rotation.toFloat())
            if (crop.flippedH) postScale(-1f, 1f)
            if (crop.flippedV) postScale(1f, -1f)
        }

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            matrix, true
        )

        val rect = crop.cropRect ?: computeAspectRatioRect(rotated.width, rotated.height, crop.aspectRatio)
        return if (rect != null && !rect.isEmpty) {
            val left = (rect.left * rotated.width).toInt().coerceIn(0, rotated.width)
            val top = (rect.top * rotated.height).toInt().coerceIn(0, rotated.height)
            val right = (rect.right * rotated.width).toInt().coerceIn(left, rotated.width)
            val bottom = (rect.bottom * rotated.height).toInt().coerceIn(top, rotated.height)
            Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
        } else {
            rotated
        }
    }

    private fun computeAspectRatioRect(width: Int, height: Int, aspectRatio: AspectRatio): RectF? {
        val ratio = aspectRatio.ratio
        if (ratio == null || ratio < 0f) return null
        val imageRatio = width.toFloat() / height.toFloat()
        return if (imageRatio > ratio) {
            val w = height * ratio
            val left = (width - w) / 2f
            RectF(left / width, 0f, (left + w) / width, 1f)
        } else {
            val h = width / ratio
            val top = (height - h) / 2f
            RectF(0f, top / height, 1f, (top + h) / height)
        }
    }

    /**
     * Apply adjustments + beauty + filters via GPU pipeline.
     *
     * 运行在 [processingDispatcher] 上，默认使用独立单线程，避免 EGL 上下文在协程线程池间切换而失效。
     * 若 GPU 路径抛出异常或输出全黑，则降级为 CPU 滤镜兜底，确保用户不会看到黑屏。
     */
    suspend fun applyGpuEffects(
        bitmap: Bitmap,
        recipe: EditRecipe,
        faceData: com.mamba.picme.beauty.api.FaceData?
    ): Bitmap = withContext(processingDispatcher) {
        val settings = recipe.beauty.copy(
            enabled = true,
            brightness = recipe.adjustments.brightness,
            exposure = recipe.adjustments.exposure,
            contrast = recipe.adjustments.contrast,
            saturation = recipe.adjustments.saturation,
            temperature = recipe.adjustments.temperature,
            tint = recipe.adjustments.tint,
            colorFilter = recipe.colorFilter,
            styleFilter = recipe.styleFilter
        )
        try {
            val result = photoProcessor.process(bitmap, settings.toBeautyParams(), faceData)
            if (isEffectivelyBlack(result)) {
                throw PhotoProcessException("GPU output is effectively black, fallback to CPU")
            }
            result
        } catch (e: PhotoProcessException) {
            Logger.e(TAG, "GPU path failed or returned black, fallback to CPU filter", e)
            applyCpuFilterFallback(bitmap, recipe)
        }
    }

    /**
     * 检测 Bitmap 是否几乎全黑（采样检查，避免全图遍历）。
     */
    private fun isEffectivelyBlack(bitmap: Bitmap, sampleStep: Int = 16): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true

        var sampleCount = 0
        var darkCount = 0
        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                sampleCount++
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r < 8 && g < 8 && b < 8) {
                    darkCount++
                }
            }
        }
        return sampleCount > 0 && darkCount > sampleCount * 0.95
    }

    /**
     * CPU 滤镜兜底：至少应用色调滤镜，避免黑屏。
     * 美颜/调色细节在 GPU 失效时无法精确复刻，因此仅保留滤镜风格。
     */
    private fun applyCpuFilterFallback(bitmap: Bitmap, recipe: EditRecipe): Bitmap {
        val hasFilter = recipe.colorFilter != FilterType.NONE || recipe.styleFilter != StyleFilter.NONE
        if (!hasFilter) {
            // 没有任何可用 CPU 路径的效果，直接返回原图副本，避免黑屏
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colorMatrix = ColorMatrix()
        if (recipe.colorFilter != FilterType.NONE) {
            colorMatrix.postConcat(recipe.colorFilter.toAndroidColorMatrix())
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    /**
     * Overlay markup actions on top of processed bitmap.
     */
    fun applyMarkup(bitmap: Bitmap, actions: List<MarkupAction>): Bitmap {
        if (actions.isEmpty()) return bitmap
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        actions.forEach { action ->
            when (action) {
                is MarkupAction.Doodle -> {
                    paint.color = action.color
                    paint.strokeWidth = action.strokeWidth
                    canvas.drawPath(action.path, paint)
                }
                is MarkupAction.Mosaic -> {
                    // Phase 2: implement mosaic shader overlay
                }
                is MarkupAction.Text -> {
                    paint.color = action.color
                    paint.textSize = action.sizePx
                    paint.style = Paint.Style.FILL
                    canvas.drawText(action.text, action.position.x, action.position.y, paint)
                }
            }
        }
        return result
    }
}
