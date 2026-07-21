package com.mamba.picme.domain.matting

import android.content.Context
import android.graphics.Bitmap
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.download.LlmModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 抠图门面接口（便于在 RecipeApplier/VM 测试中注入 fake）。P1 仅 u2netp 路径。 */
interface MattingEngine {
    suspend fun removeBackground(bitmap: Bitmap, maskSource: MaskSource): MattingResult?
}

class MattingEngineImpl(
    context: Context,
    private val downloadManager: LlmModelDownloadManager? = null
) : MattingEngine {

    companion object {
        private const val TAG = "PoLang:Matting"
    }

    private val u2netBackend = U2NetOnnxBackend(context, AssetMattingModelResolver(context))
    private val modnetBackend = ModNetOnnxBackend(context, AssetMattingModelResolver(context))
    private var u2netReady = false
    private var modnetReady = false
    private val selfieBackend = MediaPipeSegmentationBackend(context)
    private var selfieReady = false

    private suspend fun ensureBackend(source: MaskSource): Boolean = when (source) {
        MaskSource.U2NETP -> {
            if (!u2netReady) u2netReady = initializeWithDownloadFallback("u2netp-onnx") {
                u2netBackend.initialize()
            }
            u2netReady
        }
        MaskSource.MODNET -> {
            if (!modnetReady) modnetReady = initializeWithDownloadFallback("modnet-onnx") {
                modnetBackend.initialize()
            }
            modnetReady
        }
        MaskSource.SELFIE_SEGMENTATION -> {
            if (!selfieReady) selfieReady = selfieBackend.initialize()
            selfieReady
        }
    }

    /**
     * 尝试初始化后端；若模型未下载且提供了 [downloadManager]，则后台 enqueue 下载。
     * 下载是异步的，本次调用返回 false，由 UI 层监听下载状态后重试。
     */
    private suspend fun initializeWithDownloadFallback(
        modelId: String,
        initialize: suspend () -> Boolean
    ): Boolean {
        if (initialize()) return true

        val manager = downloadManager ?: run {
            Logger.w(TAG, "$modelId not available and no download manager provided")
            return false
        }

        if (!manager.isModelDownloaded(modelId)) {
            Logger.i(TAG, "$modelId not downloaded, enqueuing download")
            val config = manager.loadAvailableModels().find { it.id == modelId }
            if (config != null) {
                manager.enqueueDownload(modelId, config)
            } else {
                Logger.w(TAG, "$modelId config not found in llm_models.json")
            }
        }
        return false
    }

    override suspend fun removeBackground(bitmap: Bitmap, maskSource: MaskSource): MattingResult? =
        withContext(Dispatchers.Default) {
            if (!ensureBackend(maskSource)) return@withContext null
            val raw = when (maskSource) {
                MaskSource.U2NETP -> u2netBackend.infer(bitmap)
                MaskSource.MODNET -> modnetBackend.infer(bitmap)
                MaskSource.SELFIE_SEGMENTATION -> selfieBackend.infer(bitmap)
            } ?: return@withContext null
            val maskSize = when (maskSource) {
                MaskSource.U2NETP -> U2NetPreprocessor.INPUT_SIZE
                MaskSource.MODNET -> ModNetPreprocessor.INPUT_SIZE
                MaskSource.SELFIE_SEGMENTATION -> MediaPipeSegmentationBackend.OUTPUT_SIZE
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
        selfieBackend.release()
    }
}
