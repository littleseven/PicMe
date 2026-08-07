package com.mamba.picme.features.camera.voice

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.agent.core.platform.voice.InputAudioDevice
import com.mamba.picme.agent.core.platform.voice.KeywordSpotterEngine
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.domain.usecase.AiAgentUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音命令协调器
 *
 * 统一管理三种语音交互模式（Push-to-Talk / WakeWord / KWS）、
 * ASR 引擎调用、LLM 意图解析和命令分发。
 *
 * 【工作流程】
 * - Push-to-Talk 模式：用户按住按钮录音 → 识别 → ASR → LLM
 * - WakeWord 模式：检测 VAD → 识别 → ASR → LLM
 * - KWS 模式（新）：专用轻量 KWS 模型检测关键词 → ASR → LLM（低功耗）
 *
 * 参考 MnnLlmChat VoiceChatPresenter 的 Channel 串行任务处理：
 * - 使用 Channel 保证任务按序执行，避免并发问题
 * - 识别 → LLM 推理 → 命令执行 形成串行流水线
 *
 * @param context Application Context
 * @param asrEngine ASR 引擎（优先本地 MNN，回退系统）
 * @param aiAgentUseCase AI Agent 用例，负责 LLM 推理和命令解析
 * @param kwsEngine KWS 引擎（可选，用于低功耗唤醒词检测）
 * @param onCommand 识别到有效命令后的回调
 * @param scope CoroutineScope，用于异步任务
 */
class VoiceCommandCoordinator(
    private val asrEngine: AsrEngine,
    private val aiAgentUseCase: AiAgentUseCase,
    private val onCommand: (AiAgentCommand) -> Unit,
    private val scope: CoroutineScope,
    private val onTranscript: ((String) -> Unit)? = null,
    private val onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null,
    private val kwsEngine: KeywordSpotterEngine? = null,
    context: android.content.Context? = null
) {

    private val tag = "VoiceCommand"

    private val pushToTalkEngine = PushToTalkEngine(asrEngine, scope, context)
    private val wakeWordEngine = WakeWordEngine(asrEngine, scope, context)
    private var kwsWakeWordEngine: KwakeWordKwsEngine? = null  // 延迟初始化 KWS 引擎
    private var kwsWakeInProgress = false  // 标记 KWS 唤醒后的 ASR+LLM 处理进行中

    init {
        // 如果提供了 KWS 引擎，初始化 KWS 唤醒词检测
        if (kwsEngine != null) {
            kwsWakeWordEngine = KwakeWordKwsEngine(kwsEngine, scope, context)
            Logger.i(tag, "KWS engine initialized for low-power wake word detection")
        }
    }

    /**
     * 当前音频输入设备类型
     * 供 UI 层查询以显示耳机状态标记
     */
    val currentInputDevice: InputAudioDevice
        get() = pushToTalkEngine.currentInputDevice

    /**
     * 当前语音命令模式
     */
    var mode: VoiceCommandMode = VoiceCommandMode.PUSH_TO_TALK
        set(value) {
            if (field == value) return
            // 模式切换时停止当前监听
            if (field == VoiceCommandMode.WAKE_WORD) {
                stopWakeWordListening()
            }
            field = value
            Logger.i(tag, "Voice command mode changed to: $value")
        }

    /**
     * 当前相机状态快照，用于构建 LLM 上下文
     */
    var currentCameraState: CameraStateSnapshot = CameraStateSnapshot()

    // 参考 VoiceChatPresenter: 使用 Channel 实现串行任务处理
    private val taskChannel = Channel<VoiceTask>(Channel.UNLIMITED)

    init {
        // 启动串行处理器，确保识别 → LLM → 命令执行按序进行
        scope.launch {
            taskChannel.consumeEach { task ->
                processTask(task)
            }
        }
    }

    /**
     * 语音任务密封类（参考 VoiceChatPresenter.SerialTask）
     */
    private sealed class VoiceTask {
        data class ProcessTranscript(val transcript: String) : VoiceTask()
    }

    /**
     * 开始唤醒词监听
     *
     * 支持两种模式：
     * - WAKE_WORD：VAD + ASR（原有模式）
     * - KWS：专用轻量模型（新增，低功耗）
     *
     * 优先级：KWS > WAKE_WORD
     */
    fun startWakeWordListening() {
        if (mode != VoiceCommandMode.WAKE_WORD) {
            Logger.d(tag, "Not in wake word mode, skipping")
            return
        }

        // 优先使用 KWS 引擎（低功耗）
        if (kwsWakeWordEngine != null) {
            Logger.i(tag, "Starting KWS wake word engine (low-power mode)")
            kwsWakeWordEngine!!.start(onWakeWord = {
                Logger.i(tag, "KWS detected wake word, stopping KWS and starting ASR")
                // 1. 先停止 KWS 释放麦克风，避免与 ASR 的 AudioRecorder 互斥
                kwsWakeWordEngine?.stop()
                // 2. 清理可能残留的 PushToTalk 录制（上次未正常释放导致 Already recording）
                stopPushToTalk()
                kwsWakeInProgress = true
                // 3. 启动 ASR 进行完整转录 → LLM 处理
                startAsrForTranscription()
            })
            return
        }

        // 回退到原有的 VAD + ASR 模式
        if (!asrEngine.isAvailable()) {
            Logger.w(tag, "ASR engine not available")
            return
        }
        Logger.i(tag, "Starting traditional wake word engine (VAD + ASR mode)")
        wakeWordEngine.start { transcript ->
            enqueueTranscript(transcript)
        }
    }

    /**
     * 停止唤醒词监听
     */
    fun stopWakeWordListening() {
        // 停止 KWS 引擎
        kwsWakeWordEngine?.stop()
        // 停止传统引擎
        wakeWordEngine.stop()
    }

    /**
     * 启动 ASR 进行完整转录（在 KWS 检测到唤醒词后调用）
     *
     * 【后续优化】目前是同步启动 ASR，后续可以考虑：
     * - ASR 只录制 1-2 秒的音频（快速命令）
     * - 自动检测命令结束后关闭 ASR
     */
    private fun startAsrForTranscription() {
        if (!asrEngine.isAvailable()) {
            Logger.w(tag, "ASR engine not available after KWS wake")
            // ASR 不可用时立即重启 KWS 继续监听
            restartKwsIfNeeded()
            return
        }

        startPushToTalk(
            onResult = { transcript ->
                Logger.i(tag, "ASR transcription: $transcript")
                if (transcript.isNotBlank()) {
                    // 有效转录 → 送入 LLM 处理（processTranscriptInternal 完成后会自动重启 KWS）
                    enqueueTranscript(transcript)
                } else {
                    // 空转录 → 立即重启 KWS 继续监听
                    Logger.d(tag, "Empty transcript, restarting KWS immediately")
                    restartKwsIfNeeded()
                }
            },
            processAsCommand = false  // startAsrForTranscription 自己处理 enqueue，避免重复入队
        )
    }

    /**
     * ASR+LLM 处理完成后重启 KWS 持续监听
     *
     * 仅在 KWS 唤醒触发的处理流程中调用，且需检查当前模式是否仍为 WAKE_WORD。
     */
    private fun restartKwsIfNeeded() {
        if (!kwsWakeInProgress) return
        kwsWakeInProgress = false
        if (mode == VoiceCommandMode.WAKE_WORD) {
            Logger.i(tag, "Restarting KWS for continuous listening")
            startWakeWordListening()
        } else {
            Logger.d(tag, "KWS restart skipped (mode=$mode)")
        }
    }

    /**
     * 开始按住说话录音
     *
     * @param onResult 识别结果回调（在主线程）
     * @param processAsCommand 是否将识别结果送入内部 LLM 解析为命令。
     *                         默认为 true（相机页面语音命令场景）。
     *                         Chat 面板语音输入时应设为 false，由调用方自行处理 LLM。
     */
    fun startPushToTalk(
        onResult: (String) -> Unit,
        processAsCommand: Boolean = true
    ) {
        if (!asrEngine.isAvailable()) {
            Logger.w(tag, "ASR engine not available")
            onResult("")
            return
        }
        pushToTalkEngine.start { transcript ->
            onResult(transcript)
            if (processAsCommand && transcript.isNotBlank()) {
                enqueueTranscript(transcript)
            }
        }
    }

    /**
     * 停止按住说话录音
     */
    fun stopPushToTalk() {
        pushToTalkEngine.stop()
    }

    /**
     * 释放 ASR 引擎资源
     */
    fun releaseAsr() {
        stopWakeWordListening()
        stopPushToTalk()
        asrEngine.release()
        Logger.i(tag, "ASR released")
    }

    /**
     * 将识别文本加入串行处理队列
     * 参考 VoiceChatPresenter: 通过 Channel 保证按序处理
     */
    private fun enqueueTranscript(transcript: String) {
        if (transcript.isBlank()) {
            Logger.d(tag, "Empty transcript, skipping")
            return
        }
        Logger.i(tag, "Enqueuing transcript: $transcript")
        scope.launch {
            taskChannel.send(VoiceTask.ProcessTranscript(transcript))
        }
    }

    /**
     * 串行处理语音任务
     * 参考 VoiceChatPresenter.processTask()
     */
    private suspend fun processTask(task: VoiceTask) {
        when (task) {
            is VoiceTask.ProcessTranscript -> {
                processTranscriptInternal(task.transcript)
            }
        }
    }

    /**
     * 处理识别到的文本（内部实现，在串行处理器中调用）
     *
     * 1. 送入 AiAgentUseCase 进行 LLM 推理
     * 2. 如果解析为有效命令（非 TextReply），通过 onCommand 回调执行
     */
    private suspend fun processTranscriptInternal(transcript: String) {
        Logger.i(tag, "Processing transcript: $transcript")

        // 通知上层语音文本已识别，可用于添加到对话历史
        onTranscript?.invoke(transcript)

        val result = aiAgentUseCase.processInput(transcript, currentCameraState.toAiAgentSnapshot())

        // 通知上层 Agent 响应结果，可用于添加到对话历史
        onAgentResponse?.invoke(result)

        result.onSuccess { command ->
            if (command is AiAgentCommand.TextReply) {
                Logger.d(tag, "Text reply, no action: ${command.message}")
            } else {
                Logger.i(tag, "Command detected: ${command.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    onCommand(command)
                }
            }
        }.onFailure { error ->
            Logger.e(tag, "Failed to process voice command", error)
        }

        // ASR+LLM 处理完成后重启 KWS 持续监听
        restartKwsIfNeeded()
    }

    /**
     * 释放资源
     *
     * 【释放流程】
     * 1. 停止所有语音引擎（KWS + VAD + PushToTalk）
     * 2. 关闭任务队列
     * 3. 释放 ASR 引擎（需要特别处理 MNN 全局状态）
     *
     * 关键修复：通过 AsrEngine.release() 统一释放，支持 SherpaOnnxAsrEngine。
     * 通过 ResourceManager 协调释放，避免 MNN 全局状态冲突。
     */
    fun release() {
        Logger.i(tag, "Releasing VoiceCommandCoordinator...")

        // 停止所有语音监听引擎
        stopWakeWordListening()
        stopPushToTalk()

        // 关闭任务处理队列
        taskChannel.close()

        // 释放 ASR 引擎（统一管理）
        asrEngine.release()

        Logger.i(tag, "✓ VoiceCommandCoordinator released")
    }

    /**
     * 内部相机状态快照
     */
    data class CameraStateSnapshot(
        val beautySettings: BeautySettings = BeautySettings(),
        val filterType: FilterType = FilterType.NONE,
        val styleFilter: StyleFilter = StyleFilter.NONE,
        val zoomRatio: Float = 1f,
        val exposureCompensation: Int = 0,
        val captureMode: MediaType = MediaType.PHOTO,
        val isRecording: Boolean = false
    ) {
        fun toAiAgentSnapshot(): AiAgentUseCase.CameraStateSnapshot {
            return AiAgentUseCase.CameraStateSnapshot(
                beautySettings = beautySettings,
                filterType = filterType,
                styleFilter = styleFilter,
                zoomRatio = zoomRatio,
                exposureCompensation = exposureCompensation,
                captureMode = captureMode,
                isRecording = isRecording
            )
        }
    }
}
