package com.mamba.picme.domain.tag

import android.graphics.Bitmap

/**
 * MobileCLIP 推理后端抽象。
 *
 * 当前仅支持 ONNX Runtime 后端（MobileCLIP-S2）。
 */
interface MobileClipBackend {

    /** 后端是否已初始化（vision 模型已加载） */
    val isInitialized: Boolean

    /** text 模型是否已加载 */
    val isTextLoaded: Boolean

    /** 初始化后端，失败返回 false */
    fun initialize(useGpu: Boolean): Boolean

    /** 编码图像为 512 维 L2 归一化 embedding */
    fun encodeImage(bitmap: Bitmap): FloatArray?

    /** 编码文本 token IDs 为 512 维 L2 归一化 embedding */
    fun encodeText(tokenIds: LongArray): FloatArray?

    /** 释放后端资源 */
    fun release()

    /**
     * 计算两个 embedding 的余弦相似度
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) {
            dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        } else 0f
    }

    /**
     * 校验并强制 L2 归一化 embedding
     */
    fun validateAndNormalize(embedding: FloatArray, source: String): FloatArray? {
        if (embedding.size != EMBEDDING_DIM) {
            android.util.Log.w("MobileClipBackend", "$source: invalid embedding dimension ${embedding.size}, expected $EMBEDDING_DIM")
            return null
        }

        var norm = 0f
        for (v in embedding) {
            if (v.isNaN() || v.isInfinite()) {
                android.util.Log.w("MobileClipBackend", "$source: embedding contains NaN/Infinite value")
                return null
            }
            norm += v * v
        }

        if (norm <= 0f) {
            android.util.Log.w("MobileClipBackend", "$source: zero vector embedding rejected")
            return null
        }

        val rawNorm = kotlin.math.sqrt(norm)
        if (rawNorm > 0f) {
            for (i in embedding.indices) {
                embedding[i] /= rawNorm
            }
        }
        return embedding
    }

    companion object {
        const val EMBEDDING_DIM = 512
        const val VISION_INPUT_SIZE = 256
        const val MAX_TEXT_TOKENS = 77
    }
}
