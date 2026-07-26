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

    private val u2netBackend = U2NetOnnxBackend(AssetMattingModelResolver(context))
    private val modnetBackend = ModNetOnnxBackend(AssetMattingModelResolver(context))
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
        // FUSION 由 fusionMatting() 分别 ensure selfie + modnet，不在此处理
        MaskSource.FUSION -> false
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
            if (maskSource == MaskSource.FUSION) return@withContext fusionMatting(bitmap)
            if (!ensureBackend(maskSource)) return@withContext null
            val raw = when (maskSource) {
                MaskSource.U2NETP -> u2netBackend.infer(bitmap)
                MaskSource.MODNET -> modnetBackend.infer(bitmap)
                MaskSource.SELFIE_SEGMENTATION -> selfieBackend.infer(bitmap)
                // FUSION 在 removeBackground 开头已早返回到 fusionMatting，此处不可达
                MaskSource.FUSION -> null
            } ?: return@withContext null
            val maskSize = when (maskSource) {
                MaskSource.U2NETP -> U2NetPreprocessor.INPUT_SIZE
                MaskSource.MODNET -> ModNetPreprocessor.INPUT_SIZE
                MaskSource.SELFIE_SEGMENTATION -> MediaPipeSegmentationBackend.OUTPUT_SIZE
                MaskSource.FUSION -> 0
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
            // selfie_segmenter 软边宽：温和 alpha 锐化收窄过渡（其分割明确、身体 alpha≈1，
            // 锐化只压边缘，不会像 MODNet 那样把身体边缘压成背景）。contrast 可调：虚边残留则调大。
            val refined = if (maskSource == MaskSource.SELFIE_SEGMENTATION) {
                MaskPostProcessor.sharpenAlpha(upsampled, contrast = 3.0f)
            } else {
                upsampled
            }
            MattingResult(alpha = refined, width = bitmap.width, height = bitmap.height)
        }

    /**
     * selfie + MODNet 双模型融合：逐像素 max（服装区取 selfie、面部区取 MODNet），
     * 再 alpha 锐化收窄融合边缘。证件照专用（双推理，离线可接受）。
     */
    private suspend fun fusionMatting(bitmap: Bitmap): MattingResult? {
        if (!ensureBackend(MaskSource.SELFIE_SEGMENTATION) || !ensureBackend(MaskSource.MODNET)) {
            return null
        }
        val rawSelfie = selfieBackend.infer(bitmap) ?: return null
        val rawModnet = modnetBackend.infer(bitmap) ?: return null
        val alphaSelfie = MaskPostProcessor.upsample(
            rawSelfie,
            srcW = MediaPipeSegmentationBackend.OUTPUT_SIZE, srcH = MediaPipeSegmentationBackend.OUTPUT_SIZE,
            dstW = bitmap.width, dstH = bitmap.height
        )
        val alphaModnet = MaskPostProcessor.upsample(
            rawModnet,
            srcW = ModNetPreprocessor.INPUT_SIZE, srcH = ModNetPreprocessor.INPUT_SIZE,
            dstW = bitmap.width, dstH = bitmap.height
        )
        val fused = FloatArray(alphaSelfie.size) { i -> maxOf(alphaSelfie[i], alphaModnet[i]) }
        val refined = MaskPostProcessor.sharpenAlpha(fused, contrast = 2.5f)
        return MattingResult(alpha = refined, width = bitmap.width, height = bitmap.height)
    }

    fun release() {
        u2netBackend.release()
        modnetBackend.release()
        selfieBackend.release()
    }
}
