package com.mamba.picme.data.indexing

import android.graphics.Bitmap
import com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceEmbedder
import com.mamba.picme.core.common.Logger
import java.io.File

/**
 * MNN 人脸嵌入提取器
 *
 * 使用原生 MnnFaceEmbedder 加载 MNN 版人脸特征模型，
 * 直接提取 512 维 L2 归一化 embedding。
 *
 * 当前默认模型：ArcFace R100（budaoshou/ArcFace-R100-MNN）
 * 路径: {filesDir}/llm_models/picme-face-embedding-r100-mnn/arcface_r100.mnn
 * 历史模型：MobileFaceNet（budaoshou/InsightFace-MobileFaceNet-MNN）
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
     *
     * @param inputName 输入层名称，MobileFaceNet 为 "input.1"，ArcFace R100 为 "data"
     * @param outputName 输出层名称，ArcFace R100 为 "fc1"；空字符串则自动查找
     * @param useGpu 是否优先尝试 OpenCL GPU 后端（失败自动回退 CPU）
     */
    fun initialize(
        inputName: String = "input.1",
        outputName: String = "",
        useGpu: Boolean = false
    ): Boolean {
        if (embedder != null) return true
        if (!isModelReady) {
            Logger.w(TAG, "Model not found: ${modelFile.absolutePath}")
            return false
        }

        // 优先尝试 GPU；失败时回退 CPU（仅当 useGpu=true 时）
        if (useGpu) {
            embedder = MnnFaceEmbedder.create(
                modelPath = modelFile.absolutePath,
                inputSize = inputSize,
                embeddingDim = embeddingDim,
                inputName = inputName,
                outputName = outputName,
                useGpu = true
            )
            if (embedder != null) {
                Logger.i(TAG, "MNN face embedder loaded with OpenCL GPU: inputName=$inputName, outputName=$outputName")
                return true
            }
            Logger.w(TAG, "OpenCL GPU embedder failed, falling back to CPU")
        }

        embedder = MnnFaceEmbedder.create(
            modelPath = modelFile.absolutePath,
            inputSize = inputSize,
            embeddingDim = embeddingDim,
            inputName = inputName,
            outputName = outputName,
            useGpu = false
        )
        if (embedder == null) {
            Logger.e(TAG, "Failed to create MNN face embedder")
            return false
        }
        Logger.i(TAG, "MNN face embedder loaded with CPU: inputName=$inputName, outputName=$outputName")
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
