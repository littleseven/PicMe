package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.data.download.ModelPathConfig
import java.io.File

/**
 * MobileCLIP 语义编码引擎
 *
 * 使用 ONNX Runtime 后端（MobileCLIP-S2）直接推理 ONNX 模型，无需 MNN 转换。
 *
 * 生成 512 维 L2 归一化 embedding，用于语义搜索和图像理解。
 */
class MobileClipEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "MobileClipEngine"
        private const val ONNX_VISION_MODEL_FP32 = "vision_model.onnx"
        private const val ONNX_TEXT_MODEL_FP32 = "text_model.onnx"
        private const val ONNX_VISION_MODEL_FP32_ALT = "vision_model_fp32.onnx"
        private const val ONNX_TEXT_MODEL_FP32_ALT = "text_model_fp32.onnx"
        private const val ONNX_VISION_MODEL_FP16 = "vision_model_fp16.onnx"
        private const val ONNX_TEXT_MODEL_FP16 = "text_model_fp16.onnx"
    }

    private val backend: MobileClipBackend by lazy {
        Log.i(TAG, "Using ONNX Runtime backend for MobileCLIP-S2")
        MobileClipOnnxBackend(context)
    }

    /** 引擎是否已初始化（vision 模型已加载） */
    val isInitialized: Boolean
        get() = backend.isInitialized

    /** text 模型是否已加载 */
    val isTextLoaded: Boolean
        get() = backend.isTextLoaded

    /**
     * 模型是否已下载到本地
     */
    fun isModelReady(): Boolean {
        if (!isOnnxModelAvailable()) return false
        val modelDir = ModelPathConfig.getMobileClipModelDir(context)
        val onnxFiles = when {
            File(modelDir, ONNX_VISION_MODEL_FP32).exists() ->
                listOf(ONNX_VISION_MODEL_FP32, ONNX_TEXT_MODEL_FP32, "tokenizer.json")
            File(modelDir, ONNX_VISION_MODEL_FP32_ALT).exists() ->
                listOf(ONNX_VISION_MODEL_FP32_ALT, ONNX_TEXT_MODEL_FP32_ALT, "tokenizer.json")
            File(modelDir, ONNX_VISION_MODEL_FP16).exists() ->
                listOf(ONNX_VISION_MODEL_FP16, ONNX_TEXT_MODEL_FP16, "tokenizer.json")
            else -> listOf(ONNX_VISION_MODEL_FP32, ONNX_TEXT_MODEL_FP32, "tokenizer.json")
        }
        return ModelPathConfig.validateModelFiles(modelDir, onnxFiles)
    }

    /**
     * 初始化引擎
     *
     * @param useGpu 是否尝试使用 GPU（ONNX NNAPI）
     * @return 是否成功
     */
    fun initialize(useGpu: Boolean = false): Boolean {
        return backend.initialize(useGpu)
    }

    /**
     * 初始化引擎并自动 GPU → CPU 回退
     */
    fun initializeWithFallback(): Boolean {
        if (backend.isInitialized) return true
        Log.i(TAG, "Initializing MobileClipEngine with GPU fallback...")
        if (initialize(useGpu = true)) {
            Log.i(TAG, "MobileClipEngine initialized on GPU")
            return true
        }
        Log.w(TAG, "GPU initialization failed, falling back to CPU")
        return initialize(useGpu = false)
    }

    /**
     * 编码图像为语义 embedding
     */
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        return backend.encodeImage(bitmap)
    }

    /**
     * 编码文本为语义 embedding
     */
    fun encodeText(tokenIds: LongArray): FloatArray? {
        return backend.encodeText(tokenIds)
    }

    /**
     * 计算两个 embedding 的余弦相似度
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        return backend.cosineSimilarity(a, b)
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        backend.release()
    }

    private fun isOnnxModelAvailable(): Boolean {
        val modelDir = ModelPathConfig.getMobileClipModelDir(context)
        return (File(modelDir, ONNX_VISION_MODEL_FP32).exists() &&
            File(modelDir, ONNX_TEXT_MODEL_FP32).exists()) ||
            (File(modelDir, ONNX_VISION_MODEL_FP32_ALT).exists() &&
            File(modelDir, ONNX_TEXT_MODEL_FP32_ALT).exists()) ||
            (File(modelDir, ONNX_VISION_MODEL_FP16).exists() &&
            File(modelDir, ONNX_TEXT_MODEL_FP16).exists())
    }
}
