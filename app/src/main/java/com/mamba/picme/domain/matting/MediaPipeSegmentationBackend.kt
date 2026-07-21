package com.mamba.picme.domain.matting

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

/**
 * MediaPipe Selfie Segmentation 后端：用 Google 官方 selfie_segmenter.tflite 出前景掩码，
 * 作为 MODNet 的 A/B 对照（验证 Google 模型在证件照发丝/衣领边缘是否更优）。
 *
 * 输出连续置信度 alpha（0..1，未二值化），尺寸 [OUTPUT_SIZE]²；由调用方上采样到原图。
 * 复用项目已集成的 MediaPipe Tasks Vision（无新增 native 依赖）。
 */
class MediaPipeSegmentationBackend(
    context: Context
) {
    companion object {
        private const val TAG = "PoLang:Matting"
        private const val MODEL_ASSET = "matting/selfie_segmenter.tflite"
        const val OUTPUT_SIZE = 256
    }

    private val appContext = context.applicationContext
    private var segmenter: ImageSegmenter? = null

    val isInitialized: Boolean
        get() = segmenter != null

    fun initialize(): Boolean {
        if (segmenter != null) return true
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setOutputConfidenceMasks(true)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            segmenter = ImageSegmenter.createFromOptions(appContext, options)
            Log.i(TAG, "MediaPipeSegmentationBackend initialized (selfie_segmenter)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init selfie segmenter", e)
            release()
            false
        }
    }

    /**
     * 推理；返回连续前景 alpha（0..1），尺寸 [OUTPUT_SIZE]*[OUTPUT_SIZE]。
     * 合并所有非 background 类（index>=1）逐像素取 max 作为前景，兼容 general(2类) 与 multiclass(6类) 模型。
     */
    fun infer(bitmap: Bitmap): FloatArray? {
        val s = segmenter ?: run {
            Log.w(TAG, "selfie segmenter not initialized")
            return null
        }
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = s.segment(mpImage)
            val masks = result.confidenceMasks().orElse(null)?.takeIf { it.isNotEmpty() } ?: return null
            val w = masks[0].width
            val h = masks[0].height
            val alpha = FloatArray(w * h)
            // selfie_segmenter: [background, person] → 取 person(index1)；multiclass: 合并非 background
            for (cls in 1 until masks.size) {
                val vals = FloatArray(w * h)
                ByteBufferExtractor.extract(masks[cls]).asFloatBuffer().get(vals)
                var i = 0
                while (i < alpha.size) {
                    if (vals[i] > alpha[i]) alpha[i] = vals[i]
                    i++
                }
            }
            alpha
        } catch (e: Exception) {
            Log.e(TAG, "selfie segmentation infer failed", e)
            null
        }
    }

    fun release() {
        segmenter?.close()
        segmenter = null
        Log.i(TAG, "MediaPipeSegmentationBackend released")
    }
}
