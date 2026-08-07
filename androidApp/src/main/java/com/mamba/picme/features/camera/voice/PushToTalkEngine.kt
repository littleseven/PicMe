package com.mamba.picme.features.camera.voice

import com.mamba.picme.core.common.Logger
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.agent.core.platform.voice.AudioRecorder
import com.mamba.picme.agent.core.platform.voice.InputAudioDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_RECORD_DURATION_MS = 10000
private const val SILENCE_TIMEOUT_MS = 1500

/**
 * 按住说话（Push-to-Talk）引擎
 *
 * 用户按住按钮时开始录音，松开后停止并触发 ASR 识别。
 */
class PushToTalkEngine(
    private val asrEngine: AsrEngine,
    private val scope: CoroutineScope,
    context: android.content.Context? = null
) {

    private val tag = "PushToTalk"
    private val audioRecorder = AudioRecorder(context)
    private var isRecording = false

    /**
     * 当前音频输入设备类型
     * 供 UI 层查询以显示耳机状态标记
     */
    val currentInputDevice: InputAudioDevice
        get() = audioRecorder.currentInputDevice

    /**
     * 开始录音
     *
     * @param onResult 识别结果回调（在主线程）
     */
    fun start(onResult: (String) -> Unit) {
        if (isRecording) {
            Logger.w(tag, "Already recording")
            return
        }

        val started = audioRecorder.start()
        if (!started) {
            Logger.e(tag, "Failed to start audio recorder")
            return
        }

        isRecording = true
        Logger.d(tag, "Push-to-talk started")

        scope.launch(Dispatchers.IO) {
            try {
                val audioData = audioRecorder.readSegment(
                    maxDurationMs = MAX_RECORD_DURATION_MS,
                    silenceTimeoutMs = SILENCE_TIMEOUT_MS
                )

                if (audioData.isEmpty()) {
                    Logger.w(tag, "No audio data recorded")
                    withContext(Dispatchers.Main) { onResult("") }
                    return@launch
                }

                Logger.d(tag, "Audio recorded: ${audioData.size} bytes")

                val result = asrEngine.transcribe(audioData)
                withContext(Dispatchers.Main) {
                    result.onSuccess { transcript ->
                        Logger.d(tag, "Transcript: $transcript")
                        onResult(transcript)
                    }.onFailure { error ->
                        Logger.e(tag, "ASR failed", error)
                        onResult("")
                    }
                }
            } finally {
                // 录音+转录完成后释放 AudioRecorder，避免下次唤醒时 "Already recording"
                isRecording = false
                audioRecorder.stop()
                Logger.d(tag, "Push-to-talk completed, recorder released")
            }
        }
    }

    /**
     * 停止录音
     */
    fun stop() {
        if (!isRecording) return
        isRecording = false
        audioRecorder.stop()
        Logger.d(tag, "Push-to-talk stopped")
    }
}
