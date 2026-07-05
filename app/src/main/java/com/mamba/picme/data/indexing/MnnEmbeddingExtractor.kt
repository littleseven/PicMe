package com.mamba.picme.data.indexing

import android.graphics.Bitmap
import com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceEmbedder
import com.mamba.picme.core.common.Logger
import java.io.File

/**
 * MNN MobileFaceNet 人脸嵌入提取器
 *
 * 使用原生 MnnFaceEmbedder 加载 MNN 版 MobileFaceNet 模型，
 * 直接提取 512 维 L2 归一化 embedding。
 *
 * 模型由模型中心下载，路径: {filesDir}/llm_models/picme-face-embedding-mnn/w600k_mbf.mnn
 * 链接: https://modelscope.cn/models/budaoshou/InsightFace-MobileFaceNet-MNN
 */
class MnnEmbeddingExtractor(
    private val modelFile: File,
    private val inputSize: Int = 112,
    private val embeddingDim: Int = 512
) {
    companion object {
        private const val TAG = "PicMe:MnnEmbedding"
    }

    val isModelReady: Boolean
        get() = modelFile.exists() && modelFile.length() > 100_000

    private var embedder: MnnFaceEmbedder? = null

    /**
     * 初始化 MNN 模型
     */
    fun initialize(): Boolean {
        if (embedder != null) return true
        if (!isModelReady) {
            Logger.w(TAG, "Model not found: ${modelFile.absolutePath}")
            return false
        }
        embedder = MnnFaceEmbedder.create(
            modelPath = modelFile.absolutePath,
            inputSize = inputSize,
            embeddingDim = embeddingDim,
            inputName = "input.1",
            outputName = ""
        )
        if (embedder == null) {
            Logger.e(TAG, "Failed to create MNN face embedder")
            return false
        }
        Logger.i(TAG, "MNN MobileFaceNet loaded via native embedder")
        return true
    }

    /**
     * 提取人脸 embedding
     *
     * @param faceBitmap 112x112 RGB 人脸图片
     * @return 512 维 L2 归一化 embedding，或 null
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val emb = embedder ?: return null

        // 确保输入尺寸
        val resized = if (faceBitmap.width != inputSize || faceBitmap.height != inputSize) {
            Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        } else faceBitmap

        return try {
            val embedding = emb.extract(resized)
            if (embedding == null) {
                Logger.w(TAG, "extractEmbedding: native extract returned null")
                return null
            }
            if (embedding.size != embeddingDim) {
                Logger.w(TAG, "extractEmbedding: unexpected dim ${embedding.size} (expected $embeddingDim)")
                return null
            }

            // [诊断] 打印前 5 个值和 L2 norm
            val previewVals = embedding.take(5).map { "%.4f".format(it) }
            val norm = kotlin.math.sqrt(embedding.map { it * it }.sum().toDouble())
            Logger.d(TAG, "extractEmbedding: dim=${embedding.size}, first5=[${previewVals.joinToString()}], l2=%.4f".format(norm))

            embedding
        } catch (e: Exception) {
            Logger.e(TAG, "Embedding extraction failed", e)
            null
        }
    }

    fun close() {
        embedder?.release()
        embedder = null
    }
}
