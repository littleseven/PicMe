package com.mamba.picme.domain.matting

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 抠图门面接口（便于在 RecipeApplier/VM 测试中注入 fake）。P1 仅 u2netp 路径。 */
interface MattingEngine {
    suspend fun removeBackground(bitmap: Bitmap): MattingResult?
}

class MattingEngineImpl(context: Context) : MattingEngine {

    private val backend = U2NetOnnxBackend(context, AssetMattingModelResolver(context))
    private var initialized = false

    private suspend fun ensureInitialized(): Boolean {
        if (initialized) return true
        initialized = backend.initialize()
        return initialized
    }

    override suspend fun removeBackground(bitmap: Bitmap): MattingResult? = withContext(Dispatchers.Default) {
        if (!ensureInitialized()) return@withContext null
        val probs = backend.infer(bitmap) ?: return@withContext null
        val maskSize = U2NetPreprocessor.INPUT_SIZE
        // u2netp 输出 320×320；二值化后双线性上采样回原图尺寸
        val binary = MaskPostProcessor.binarize(probs, threshold = 0.5f)
        val upsampled = MaskPostProcessor.upsample(
            binary, srcW = maskSize, srcH = maskSize, dstW = bitmap.width, dstH = bitmap.height
        )
        MattingResult(alpha = upsampled, width = bitmap.width, height = bitmap.height)
    }

    fun release() = backend.release()
}
