package com.mamba.picme.data.local

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.usecase.OcrProcessor
import kotlinx.coroutines.tasks.await

/**
 * ML Kit 驱动的 OCR 处理器实现（Data 层）
 *
 * 职责：封装 ML Kit 中文文本识别能力，对外提供统一的 [OcrProcessor] 接口。
 * 由 [AppContainer] 通过依赖注入提供实例，features 层不应直接引用此类。
 *
 * 设计要点：
 * - 延迟初始化：TextRecognizer 在首次识别时才创建，避免构造时 ML Kit 模型未就绪导致 NPE
 * - 空安全：初始化失败时返回 null，不阻塞调用方
 * - 资源管理：close() 安全释放底层资源
 */
class MlKitOcrProcessor : OcrProcessor {

    // region 状态

    private var textRecognizer: TextRecognizer? = null

    // endregion

    // region OcrProcessor 实现

    override suspend fun recognizeFromUri(context: Context, uri: Uri): String? {
        val recognizer = getRecognizer() ?: return null

        return try {
            val inputImage = InputImage.fromFilePath(context, uri)
            val visionText = recognizer.process(inputImage).await()
            visionText.text.trim().takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            Logger.e(TAG, "OCR failed: ${error.message}")
            null
        }
    }

    override fun close() {
        textRecognizer?.close()
        textRecognizer = null
    }

    // endregion

    // region 私有方法

    /**
     * 获取或创建 TextRecognizer 实例。
     * 首次调用时初始化，失败返回 null 并记录日志。
     */
    private fun getRecognizer(): TextRecognizer? {
        if (textRecognizer != null) return textRecognizer

        textRecognizer = try {
            TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize TextRecognizer: ${e.message}")
            null
        }

        return textRecognizer
    }

    // endregion

    companion object {
        private const val TAG = "MlKitOcrProcessor"
    }
}
