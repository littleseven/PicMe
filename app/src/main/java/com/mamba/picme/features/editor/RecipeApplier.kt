package com.mamba.picme.features.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.PhotoProcessException
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.beauty.api.toAndroidColorMatrix
import com.mamba.picme.beauty.api.toBeautyParams
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.matting.BackgroundComposer
import com.mamba.picme.domain.matting.CutoutComposer
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MaskPostProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RecipeApplier"
private const val FULL_INTENSITY_THRESHOLD = 0.99f
private const val MOSAIC_PIXEL_FACTOR = 24
private const val MOSAIC_BLUR_FACTOR = 48

class RecipeApplier(
    private val photoProcessor: PhotoProcessor,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mattingEngine: MattingEngine? = null
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
        faceData: FaceData?
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

        val intensity = recipe.filterIntensity.coerceIn(0f, 1f)
        if (intensity >= FULL_INTENSITY_THRESHOLD) {
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        } else {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            paint.alpha = (intensity * 255).toInt()
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
        return output
    }

    /**
     * 去背景阶段：u2netp/MODNet 出 Alpha，按 bgMode 生成透明抠图或合成纯色背景。
     * 纯 CPU 像素操作，不绑定 EGL 上下文，可在普通调度器执行。cutout 为 null 或未注入 mattingEngine 时原样返回。
     */
    suspend fun applyCutout(bitmap: Bitmap, cutout: CutoutRecipe?): Bitmap {
        if (cutout == null || mattingEngine == null) return bitmap
        val result = mattingEngine.removeBackground(bitmap, cutout.maskSource) ?: return bitmap
        val alpha = if (cutout.feather > 0) {
            MaskPostProcessor.feather(result.alpha, result.width, result.height, cutout.feather)
        } else {
            result.alpha
        }
        return when (cutout.bgMode) {
            CutoutRecipe.BgMode.TRANSPARENT ->
                CutoutComposer.apply(bitmap, alpha, result.width, result.height)
            CutoutRecipe.BgMode.COLOR ->
                BackgroundComposer.apply(bitmap, alpha, result.width, result.height, cutout.bgColor ?: 0xFFFFFFFF.toInt())
            CutoutRecipe.BgMode.BLUR ->
                BackgroundComposer.apply(bitmap, alpha, result.width, result.height, cutout.bgColor ?: 0xFFFFFFFF.toInt())
        }
    }

    /**
     * Overlay markup actions on top of processed bitmap.
     *
     * 坐标/笔画宽度/文字大小均为归一化值（相对图片宽高），此处换算为像素后绘制。
     * 马赛克以「处理中图片」的降采样 Shader 沿路径涂抹，采样源不含已绘制的标记。
     */
    fun applyMarkup(bitmap: Bitmap, actions: List<MarkupAction>): Bitmap {
        if (actions.isEmpty()) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        actions.forEach { action ->
            when (action) {
                is MarkupAction.Doodle -> {
                    strokePaint.shader = null
                    strokePaint.isFilterBitmap = false
                    strokePaint.color = action.color
                    val strokePx = action.strokeWidth * width
                    strokePaint.strokeWidth = strokePx
                    drawStroke(canvas, action.points, width, height, strokePx, strokePaint)
                }
                is MarkupAction.Mosaic -> {
                    strokePaint.shader = createMosaicShader(bitmap, action.mode)
                    strokePaint.isFilterBitmap = action.mode == MosaicMode.BLUR
                    val strokePx = action.strokeWidth * width
                    strokePaint.strokeWidth = strokePx
                    drawStroke(canvas, action.points, width, height, strokePx, strokePaint)
                    strokePaint.shader = null
                    strokePaint.isFilterBitmap = false
                }
                is MarkupAction.Text -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = action.color
                        textSize = action.size * width
                    }
                    canvas.drawText(
                        action.text,
                        action.position.x * width,
                        action.position.y * height,
                        textPaint
                    )
                }
            }
        }
        return result
    }

    /** 单点触摸画不出零长度 Path，退化为圆点；多点按折线绘制。 */
    private fun drawStroke(
        canvas: Canvas,
        points: List<NormPoint>,
        width: Int,
        height: Int,
        strokePx: Float,
        paint: Paint
    ) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            val p = points.first()
            canvas.drawCircle(p.x * width, p.y * height, strokePx / 2f, paint)
            return
        }
        val path = Path()
        points.forEachIndexed { index, p ->
            val x = p.x * width
            val y = p.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    /**
     * 马赛克 Shader：整图大幅降采样后作为 BitmapShader 沿路径涂抹。
     * PIXEL 关闭采样过滤呈像素块；BLUR 降采样更狠且开启过滤呈模糊效果。
     */
    private fun createMosaicShader(bitmap: Bitmap, mode: MosaicMode): android.graphics.Shader {
        val factor = when (mode) {
            MosaicMode.PIXEL -> MOSAIC_PIXEL_FACTOR
            MosaicMode.BLUR -> MOSAIC_BLUR_FACTOR
        }
        val smallW = (bitmap.width / factor).coerceAtLeast(1)
        val smallH = (bitmap.height / factor).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, mode == MosaicMode.BLUR)
        val shader = android.graphics.BitmapShader(
            small,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP
        )
        shader.setLocalMatrix(Matrix().apply {
            setScale(bitmap.width / smallW.toFloat(), bitmap.height / smallH.toFloat())
        })
        return shader
    }
}
