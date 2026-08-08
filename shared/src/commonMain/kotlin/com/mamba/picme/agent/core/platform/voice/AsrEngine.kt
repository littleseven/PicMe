package com.mamba.picme.agent.core.platform.voice

/**
 * ASR（自动语音识别）引擎抽象接口
 *
 * 支持两种识别模式：
 * 1. 离线模式（Push-to-Talk）：一次性送入完整音频，返回最终文本
 * 2. 实时流式模式（Streaming）：持续送入音频片段，实时返回中间结果
 *
 * 参考 MnnLlmChat AsrService 的流式识别设计
 */
interface AsrEngine {

    /**
     * 将音频数据转录为文本（离线模式）
     *
     * @param audioData PCM 音频数据（16kHz, 16bit, 单声道）
     * @return 识别结果
     */
    suspend fun transcribe(audioData: ByteArray): Result<String>

    /**
     * 当前引擎是否可用
     */
    fun isAvailable(): Boolean

    /**
     * 是否支持实时流式识别
     *
     * 返回 true 时，可通过 [startStreaming] / [stopStreaming] 进行流式识别
     */
    fun supportsStreaming(): Boolean = false

    /**
     * 开始实时流式识别
     *
     * 参考 AsrService.startRecord() + processSamples():
     * - 持续读取麦克风音频
     * - 每 100ms 送入一个 chunk 到 ASR
     * - 检测到端点（endpoint）时返回完整文本
     *
     * @param onPartialResult 中间结果回调（可选，用于实时显示）
     * @param onFinalResult 最终结果回调（检测到端点时触发）
     */
    fun startStreaming(
        onPartialResult: ((String) -> Unit)? = null,
        onFinalResult: (String) -> Unit
    ) {
        // 默认实现：不支持流式识别
        throw UnsupportedOperationException("Streaming not supported by this engine")
    }

    /**
     * 停止实时流式识别
     */
    fun stopStreaming() {
        // 默认实现：空操作
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        // 默认实现：空操作
    }
}
