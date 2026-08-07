package com.mamba.picme.domain.tag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.data.download.ModelPathConfig
import java.io.File
import java.nio.FloatBuffer

/**
 * MobileCLIP ONNX Runtime 后端（MobileCLIP-S2 fp16）
 *
 * 直接推理 ONNX 模型，无需 MNN 转换。
 * 预处理与 MobileCLIP-S2 官方一致：resize 短边到 256，中心裁剪 256x256，除以 255。
 */
class MobileClipOnnxBackend(
    private val context: Context
) : MobileClipBackend {

    companion object {
        private const val TAG = "MobileClipOnnxBackend"
        // fp32 版本（两种常见命名都支持）
        private const val VISION_MODEL_NAME_FP32 = "vision_model.onnx"
        private const val TEXT_MODEL_NAME_FP32 = "text_model.onnx"
        private const val VISION_INPUT_NAME = "pixel_values"
        private const val VISION_OUTPUT_NAME = "image_embeds"
        private const val TEXT_INPUT_NAME = "input_ids"
        private const val TEXT_OUTPUT_NAME = "text_embeds"
        private const val VISION_INPUT_SIZE = MobileClipBackend.VISION_INPUT_SIZE
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var visionSession: OrtSession? = null
    private var textSession: OrtSession? = null

    override val isInitialized: Boolean
        get() = visionSession != null

    override val isTextLoaded: Boolean
        get() = textSession != null

    private val modelDir: File
        get() = ModelPathConfig.getMobileClipModelDir(context)

    private fun resolveModelFiles(): Pair<File, File>? {
        // fp16 在 ONNX Runtime Android CPU 上易出现 NaN/Inf，优先使用 fp32
        val candidates = listOf(
            VISION_MODEL_NAME_FP32 to TEXT_MODEL_NAME_FP32
        )
        for ((visionName, textName) in candidates) {
            val visionFile = File(modelDir, visionName)
            val textFile = File(modelDir, textName)
            if (visionFile.exists() && textFile.exists()) {
                return visionFile to textFile
            }
        }
        return null
    }

    override fun initialize(useGpu: Boolean): Boolean {
        if (visionSession != null) return true

        val (visionFile, textFile) = resolveModelFiles() ?: run {
            Log.w(TAG, "ONNX model files not found in ${modelDir.absolutePath}")
            return false
        }

        return try {
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
                if (useGpu) {
                    // ONNX Runtime Android 支持 NNAPI / GPU
                    try {
                        addNnapi()
                        Log.i(TAG, "Using NNAPI GPU for ONNX Runtime")
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI not available, fallback to CPU", e)
                    }
                }
            }

            visionSession = env.createSession(visionFile.absolutePath, options)
            textSession = env.createSession(textFile.absolutePath, options)

            Log.i(TAG, "MobileClipOnnxBackend initialized (vision=${visionFile.name}, text=${textFile.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX sessions", e)
            release()
            false
        }
    }

    override fun encodeImage(bitmap: Bitmap): FloatArray? {
        val session = visionSession ?: run {
            Log.w(TAG, "Vision session not initialized")
            return null
        }

        return try {
            val inputArray = preprocessImage(bitmap)
            val shape = longArrayOf(1, 3, VISION_INPUT_SIZE.toLong(), VISION_INPUT_SIZE.toLong())
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputArray), shape)
            try {
                val inputs = mapOf(VISION_INPUT_NAME to tensor)
                session.run(inputs).use { results ->
                    val output = results.get(0).value as Array<FloatArray>
                    validateAndNormalize(output[0].clone(), "encodeImage")
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode image", e)
            null
        }
    }

    override fun encodeText(tokenIds: LongArray): FloatArray? {
        val session = textSession ?: run {
            Log.w(TAG, "Text session not initialized")
            return null
        }

        return try {
            val inputArray = Array(1) { tokenIds }
            val tensor = OnnxTensor.createTensor(env, inputArray)
            try {
                val inputs = mapOf(TEXT_INPUT_NAME to tensor)
                session.run(inputs).use { results ->
                    val output = results.get(0).value as Array<FloatArray>
                    validateAndNormalize(output[0].clone(), "encodeText")
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode text", e)
            null
        }
    }

    override fun release() {
        visionSession?.close()
        textSession?.close()
        visionSession = null
        textSession = null
        // 注意：OrtEnvironment 是进程级单例，与 ASR/KWS/翻译等 ONNX Runtime 会话共享。
        // 关闭它会杀死应用内所有其他 ONNX Runtime session，因此这里只关闭 session，不关闭 env。
        Log.i(TAG, "MobileClipOnnxBackend sessions released (OrtEnvironment kept alive)")
    }

    /**
     * MobileCLIP-S2 图像预处理：
     * 1. 保持宽高比，短边缩放到 256
     * 2. 中心裁剪 256x256
     * 3. RGB 像素除以 255
     * 4. 排列为一维 NCHW [1, 3, 256, 256] FloatArray，减少嵌套对象分配与 GC 压力。
     */
    private fun preprocessImage(source: Bitmap): FloatArray {
        val cropped = createCenterCroppedBitmap(source, VISION_INPUT_SIZE)
        val width = cropped.width
        val height = cropped.height
        val pixels = IntArray(width * height)
        cropped.getPixels(pixels, 0, width, 0, 0, width, height)

        // NCHW layout: [batch=1, channels=3, height, width]
        val result = FloatArray(1 * 3 * height * width)
        val channelStride = height * width

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val pixel = pixels[i]
                val r = (pixel shr 16 and 0xFF) / 255.0f
                val g = (pixel shr 8 and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                result[0 * channelStride + i] = r
                result[1 * channelStride + i] = g
                result[2 * channelStride + i] = b
            }
        }

        if (cropped !== source) cropped.recycle()
        return result
    }

    private fun createCenterCroppedBitmap(source: Bitmap, targetSize: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width == targetSize && height == targetSize) return source

        // 短边缩放到 targetSize，长边至少为 targetSize，避免浮点舍入导致裁剪越界
        val scale = targetSize.toFloat() / kotlin.math.min(width, height)
        val scaledWidth = kotlin.math.max((width * scale).toInt(), targetSize)
        val scaledHeight = kotlin.math.max((height * scale).toInt(), targetSize)

        if (width == height) {
            return Bitmap.createScaledBitmap(source, targetSize, targetSize, true)
        }

        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val actualWidth = scaled.width
        val actualHeight = scaled.height
        val x = ((actualWidth - targetSize) / 2).coerceIn(0, actualWidth)
        val y = ((actualHeight - targetSize) / 2).coerceIn(0, actualHeight)
        val cropWidth = kotlin.math.min(targetSize, actualWidth - x)
        val cropHeight = kotlin.math.min(targetSize, actualHeight - y)
        val cropped = Bitmap.createBitmap(scaled, x, y, cropWidth, cropHeight)
        scaled.recycle()
        return cropped
    }
}
