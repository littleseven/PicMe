package com.mamba.picme.agent.core.platform.voice

import kotlin.math.sqrt

/**
 * 简单音量阈值 VAD（语音活动检测）器
 *
 * 基于 RMS（均方根）能量检测语音活动。
 *
 * @param thresholdDb 检测阈值（dB），默认 40dB
 * @param minSpeechMs 判定为语音的最小持续时长（毫秒），默认 300ms
 */
class VadDetector(
    private val thresholdDb: Float = 40f,
    private val minSpeechMs: Int = 300
) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2 // 16bit
        private const val DB_CONVERSION_FACTOR = 20
        private const val MILLISECONDS_PER_SECOND = 1000
        private const val BYTE_SHIFT = 8
        private const val BYTE_MASK = 0xFF
    }

    private var consecutiveSpeechFrames = 0
    private var isCurrentlySpeech = false

    /**
     * 处理一帧音频数据
     *
     * @param audioData 16bit PCM 音频数据
     * @return 是否检测到有效语音
     */
    fun process(audioData: ByteArray): Boolean {
        if (audioData.size < BYTES_PER_SAMPLE) return false

        val rms = calculateRms(audioData)
        val db = if (rms > 0) DB_CONVERSION_FACTOR * kotlin.math.log10(rms) else 0f

        val isSpeech = db > thresholdDb

        if (isSpeech) {
            consecutiveSpeechFrames++
        } else {
            consecutiveSpeechFrames = 0
            isCurrentlySpeech = false
        }

        val minFrames = (SAMPLE_RATE * minSpeechMs / MILLISECONDS_PER_SECOND) * BYTES_PER_SAMPLE /
            audioData.size.coerceAtLeast(1)

        if (consecutiveSpeechFrames >= minFrames.coerceAtLeast(1) && !isCurrentlySpeech) {
            isCurrentlySpeech = true
            return true
        }

        return isCurrentlySpeech
    }

    /**
     * 重置检测状态
     */
    fun reset() {
        consecutiveSpeechFrames = 0
        isCurrentlySpeech = false
    }

    private fun calculateRms(audioData: ByteArray): Float {
        var sum = 0.0
        var count = 0

        var index = 0
        while (index < audioData.size - 1) {
            val sample = (audioData[index + 1].toInt() shl BYTE_SHIFT or
                (audioData[index].toInt() and BYTE_MASK)).toShort()
            sum += sample * sample
            count++
            index += BYTES_PER_SAMPLE
        }

        return if (count > 0) {
            sqrt(sum / count).toFloat()
        } else {
            0f
        }
    }
}
