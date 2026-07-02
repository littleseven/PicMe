package com.mamba.picme.features.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.toBeautyParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecipeApplier(
    private val photoProcessor: PhotoProcessor
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
     */
    suspend fun applyGpuEffects(
        bitmap: Bitmap,
        recipe: EditRecipe,
        faceData: com.mamba.picme.beauty.api.FaceData?
    ): Bitmap = withContext(Dispatchers.Default) {
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
        photoProcessor.process(bitmap, settings.toBeautyParams(), faceData)
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
