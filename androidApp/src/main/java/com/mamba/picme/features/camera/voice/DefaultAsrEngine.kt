package com.mamba.picme.features.camera.voice

import android.content.Context
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.agent.core.platform.voice.SherpaOnnxAsrEngine
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.download.ModelPathConfig

private const val TAG = "DefaultAsrEngine"

/**
 * 创建默认 ASR 引擎：优先系统 ASR，不可用时回退到已下载的本地 Sherpa-ONNX 模型。
 *
 * 背景：部分机型（如无可用语音识别服务的 ROM）SpeechRecognizer.isRecognitionAvailable
 * 返回 false，导致「按住说话」直接提示语音识别未初始化；此时若本地 ASR 模型已下载
 * 就绪则使用本地模型，否则仍返回系统引擎（调用方按不可用提示）。
 */
fun createDefaultAsrEngine(context: Context): AsrEngine {
    val systemEngine = SystemAsrEngine(context)
    if (systemEngine.isAvailable()) {
        return systemEngine
    }
    if (ModelPathConfig.isAsrModelReady(context)) {
        // 不调用 sherpa.isAvailable()（会立即加载 ONNX 模型）：
        // 引擎内部首次使用时才懒加载模型，避免页面进入时的内存/CPU 峰值
        Logger.i(TAG, "System ASR unavailable, defer to local Sherpa-ONNX ASR model (lazy init on first use)")
        return SherpaOnnxAsrEngine(context, ModelPathConfig.getAsrModelDir(context).absolutePath)
    }
    Logger.w(TAG, "System ASR unavailable and no local ASR model downloaded")
    return systemEngine
}
