package com.mamba.picme.domain.usecase

import android.content.Context
import android.net.Uri

/**
 * OCR 处理器接口（Domain 层契约）
 *
 * 实现类位于 data/local/MlKitOcrProcessor.kt，
 * 通过 DI 注入，Domain 层不直接依赖 ML Kit。
 */
interface OcrProcessor {
    suspend fun recognizeFromUri(context: Context, uri: Uri): String?

    fun close()
}
