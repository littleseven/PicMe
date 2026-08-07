package com.mamba.picme.domain.agent.capability.optimize.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.openImageInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 端侧启发式场景分析器
 *
 * 解码缩略图（≤256px maxDim）做像素统计（亮度/饱和度/色温/对比度），
 * 并可选注入 [FaceDetector] 做轻量人脸计数区分 SELFIE/PORTRAIT/GROUP。
 *
 * 启发式优先级（高 → 低）：
 * 1. 人脸数量 + 尺寸 → SELFIE / PORTRAIT / GROUP
 * 2. 低亮度 → LOW_LIGHT
 * 3. 高饱和暖色 → FOOD
 * 4. 高对比低彩 → DOCUMENT
 * 5. 绿色主导（自然） → LANDSCAPE
 * 6. 默认 → GENERAL
 *
 * 隐私红线：全程零网络调用，所有处理 100% 端侧。
 */
class HeuristicSceneAnalyzer(
    private val context: Context,
    private val faceDetector: FaceDetector? = null
) : SceneAnalyzer {

    companion object {
        private const val TAG = "PoLang:HeuristicSceneAnalyzer"

        /** 缩略图最大边像素，像素统计只用缩略图以控制耗时 */
        private const val MAX_THUMBNAIL_DIM = 256

        // ---- 启发式阈值 ----
        /** 平均亮度低于此值（0-255）判定为暗光 */
        private const val LOW_LIGHT_BRIGHTNESS = 50f
        /** 平均饱和度高于此值（0-1）进入食物候选 */
        private const val FOOD_SATURATION = 0.32f
        /** 暖色偏置（R-B，0-1）下限，叠加高饱和判定食物 */
        private const val FOOD_WARM_BIAS = 0.02f
        /** 明暗对比跨度（0-255）高于此值进入文档候选 */
        private const val DOCUMENT_CONTRAST = 68f
        /** 文档候选需平均饱和度低于此值（0-1，低彩） */
        private const val DOCUMENT_SATURATION = 0.12f
        /** 绿色主导（G-(R+B)/2，0-1）高于此值判定为风景 */
        private const val LANDSCAPE_GREEN_BIAS = 0.08f

        /** 单张人脸最大边占图像最大边比例 ≥ 此值判定自拍 */
        private const val SELFIE_FACE_RATIO = 0.35f
        /** 人脸数 ≥ 此值判定合影 */
        private const val GROUP_FACE_COUNT = 2
    }

    override suspend fun analyze(imageUri: String): Scene {
        return withContext(Dispatchers.Default) {
            try {
                val bitmap = decodeThumbnail(imageUri)
                if (bitmap == null) {
                    Logger.w(TAG, "Failed to decode thumbnail, fallback GENERAL")
                    return@withContext Scene.GENERAL
                }
                try {
                    analyzeBitmap(bitmap)
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Scene analyze failed, fallback GENERAL", e)
                Scene.GENERAL
            }
        }
    }

    /**
     * 按优先级链对缩略图做场景判定。
     */
    private fun analyzeBitmap(bitmap: Bitmap): Scene {
        // 1. 人脸数量 + 尺寸（最高优先级）
        val faceScene = detectFaceScene(bitmap)
        if (faceScene != null) {
            Logger.d(TAG, "Scene by face: ${faceScene.name}")
            return faceScene
        }

        // 2-5. 像素统计驱动的非脸场景
        val stats = computePixelStats(bitmap)

        // 2. 低亮度
        if (stats.brightness < LOW_LIGHT_BRIGHTNESS) {
            Logger.d(TAG, "Scene: LOW_LIGHT (brightness=${stats.brightness})")
            return Scene.LOW_LIGHT
        }

        // 3. 高饱和暖色 → 美食
        if (stats.saturation > FOOD_SATURATION && stats.warmBias > FOOD_WARM_BIAS) {
            Logger.d(TAG, "Scene: FOOD (saturation=${stats.saturation}, warmBias=${stats.warmBias})")
            return Scene.FOOD
        }

        // 4. 高对比低彩 → 文档
        if (stats.contrast > DOCUMENT_CONTRAST && stats.saturation < DOCUMENT_SATURATION) {
            Logger.d(TAG, "Scene: DOCUMENT (contrast=${stats.contrast}, saturation=${stats.saturation})")
            return Scene.DOCUMENT
        }

        // 5. 绿色主导 → 风景
        if (stats.greenBias > LANDSCAPE_GREEN_BIAS) {
            Logger.d(TAG, "Scene: LANDSCAPE (greenBias=${stats.greenBias})")
            return Scene.LANDSCAPE
        }

        // 6. 默认
        Logger.d(
            TAG,
            "Scene: GENERAL (brightness=${stats.brightness}, saturation=${stats.saturation})"
        )
        return Scene.GENERAL
    }

    /**
     * 基于人脸检测区分 SELFIE/PORTRAIT/GROUP；无人脸或检测不可用返回 null。
     */
    private fun detectFaceScene(bitmap: Bitmap): Scene? {
        val detector = faceDetector ?: return null
        return try {
            val faces = detector.detectFacesOnly(bitmap)
            when {
                faces.size >= GROUP_FACE_COUNT -> Scene.GROUP
                faces.size == 1 -> {
                    val ratio = faceSizeRatio(faces[0], bitmap.width, bitmap.height)
                    if (ratio >= SELFIE_FACE_RATIO) Scene.SELFIE else Scene.PORTRAIT
                }
                else -> null
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Face detect failed, skip face heuristics", e)
            null
        }
    }

    /** 人脸 ROI 最大边 / 图像最大边，用于估算人脸占比。 */
    private fun faceSizeRatio(face: RectF, width: Int, height: Int): Float {
        val faceDim = max(face.width(), face.height())
        val imageDim = max(width.toFloat(), height.toFloat())
        return if (imageDim > 0f) faceDim / imageDim else 0f
    }

    /** 像素统计快照（归一化到 0-1 便于阈值复用）。 */
    private data class PixelStats(
        val brightness: Float, // 0-255
        val saturation: Float, // 0-1
        val warmBias: Float,   // 0-1，正值偏暖
        val greenBias: Float,  // 0-1，正值偏绿
        val contrast: Float    // 0-255，明暗跨度
    )

    /** 单次遍历像素计算亮度/饱和度/色温/对比度。 */
    private fun computePixelStats(bitmap: Bitmap): PixelStats {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var sumBrightness = 0f
        var sumSaturation = 0f
        var sumWarm = 0f
        var sumGreen = 0f
        var minBrightness = 255f
        var maxBrightness = 0f

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            val maxChannel = max(r, max(g, b))
            val minChannel = min(r, min(g, b))
            val saturation = if (maxChannel <= 0f) 0f else (maxChannel - minChannel) / maxChannel

            sumBrightness += luminance * 255f
            sumSaturation += saturation
            sumWarm += (r - b)
            sumGreen += (g - (r + b) / 2f)

            val luma255 = luminance * 255f
            if (luma255 < minBrightness) minBrightness = luma255
            if (luma255 > maxBrightness) maxBrightness = luma255
        }

        val count = pixels.size.toFloat()
        return PixelStats(
            brightness = sumBrightness / count,
            saturation = sumSaturation / count,
            warmBias = sumWarm / count,
            greenBias = sumGreen / count,
            contrast = maxBrightness - minBrightness
        )
    }

    /**
     * 解码缩略图：先探边界算 inSampleSize，再按比例缩放解码，保证最大边 ≤ [MAX_THUMBNAIL_DIM]。
     */
    private fun decodeThumbnail(imageUri: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openImageInputStream(context, imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Logger.w(TAG, "decodeThumbnail: invalid bounds for $imageUri")
                return null
            }

            val sampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            openImageInputStream(context, imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "decodeThumbnail failed for $imageUri", e)
            null
        }
    }

    /** 计算 inSampleSize，使缩放后最大边仍 ≥ [MAX_THUMBNAIL_DIM]/2 且尽量靠近上限。 */
    private fun calcInSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var current = max(width, height)
        while (current / 2 >= MAX_THUMBNAIL_DIM) {
            current /= 2
            sample *= 2
        }
        return sample
    }
}
