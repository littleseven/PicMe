package com.mamba.picme.domain.matting

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 抠图门面接口（便于在 RecipeApplier/VM 测试中注入 fake）。P1 仅 u2netp 路径。 */
interface MattingEngine {
    suspend fun removeBackground(bitmap: Bitmap, maskSource: MaskSource): MattingResult?
}

class MattingEngineImpl(context: Context) : MattingEngine {

    private val u2netBackend = U2NetOnnxBackend(context, AssetMattingModelResolver(context))
    private val modnetBackend = ModNetOnnxBackend(context, AssetMattingModelResolver(context))
    private var u2netReady = false
    private var modnetReady = false

    private suspend fun ensureBackend(source: MaskSource): Boolean = when (source) {
        MaskSource.U2NETP -> {
            if (!u2netReady) u2netReady = u2netBackend.initialize()
            u2netReady
        }
        MaskSource.MODNET -> {
            if (!modnetReady) modnetReady = modnetBackend.initialize()
            modnetReady
        }
    }

    override suspend fun removeBackground(bitmap: Bitmap, maskSource: MaskSource): MattingResult? =
        withContext(Dispatchers.Default) {
            if (!ensureBackend(maskSource)) return@withContext null
            val raw = when (maskSource) {
                MaskSource.U2NETP -> u2netBackend.infer(bitmap)
                MaskSource.MODNET -> modnetBackend.infer(bitmap)
            } ?: return@withContext null
            val maskSize = if (maskSource == MaskSource.U2NETP) {
                U2NetPreprocessor.INPUT_SIZE
            } else {
                ModNetPreprocessor.INPUT_SIZE
            }
            // u2netp：二值化；MODNet：连续 Alpha 直传
            val alpha = if (maskSource == MaskSource.U2NETP) {
                MaskPostProcessor.binarize(raw, threshold = 0.5f)
            } else {
                raw
            }
            val upsampled = MaskPostProcessor.upsample(
                alpha, srcW = maskSize, srcH = maskSize, dstW = bitmap.width, dstH = bitmap.height
            )
            MattingResult(alpha = upsampled, width = bitmap.width, height = bitmap.height)
        }

    fun release() {
        u2netBackend.release()
        modnetBackend.release()
    }
}
