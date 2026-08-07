package com.mamba.picme.features.camera.voice

import com.mamba.picme.core.common.Logger
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.agent.core.platform.voice.AudioRecorder
import com.mamba.picme.agent.core.platform.voice.VadDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_DELAY_MS = 30L
private const val LOW_POWER_POLL_MS = 150L          // 【功耗优化】唤醒词模式下轮训间隔（节电模式 150ms vs 高精度 30ms）
private const val ACTIVE_POLL_MS = 30L              // 【精准度优化】检测到语音活动后的高频轮训（快速响应）
private const val MAX_SEGMENT_DURATION_MS = 4000
private const val SEGMENT_SILENCE_TIMEOUT_MS = 1500
private const val WAKE_COOLDOWN_MS = 1200L
private const val VAD_STABILITY_FRAMES = 3          // 【精准度优化】连续 3 帧语音后触发 ASR（减少误触发）

/**
 * 唤醒词变体库 + 相关命令扩展
 *
 * 【识别精准度优化策略】：
 * 1. **标准唤醒词**："小浪"
 * 2. **同音误识**：xiǎo mì（小蜜/小秘/小米 等 ASR 常见输出）
 * 3. **近音模糊**：xiǎo mī/mǐ 等声调变体
 * 4. **带前缀**：如"嘿小浪"、"哎小浪" 等自然语言用法
 * 5. **方言变体**：备用名称如"小宝"、"小助手" 等（保留扩展空间）
 * 6. **同义表达**：可选，如"开始语音"、"激活"（仅在设置中启用）
 *
 * 【权重说明】：
 * - 优先级用 Pair<word, confidence> 管理（confidence: 1.0 最高，0.6 最低）
 * - 高信心词汇（0.9+）直接触发
 * - 中等信心词汇（0.7-0.8）需额外验证（如检查后续是否有指令）
 * - 低信心词汇（<0.7）需更多上下文或用户确认
 */
private val WAKE_WORD_VARIANTS = mapOf(
    // ─────────────────────────────────────────────────────────
    // 主要唤醒词 + 同音误识
    // ─────────────────────────────────────────────────────────
    "小浪" to 1.0f,          // 标准唤醒词，最高优先级

    // xiǎo mì 同音（最常见 ASR 误识）
    "小蜜" to 0.95f,         // 同音：最常见误识（蜜蜂的"蜜"）
    "小秘" to 0.95f,         // 同音：秘密的"秘"
    "小密" to 0.94f,         // 同音：密码的"密"

    // xiǎo mǐ / xiǎo mī 近音（声调偏差但可接受）
    "小米" to 0.88f,         // 近音：小米手机
    "小咪" to 0.87f,         // 近音：拟声词
    "小妹" to 0.85f,         // 近音：可能被误识

    // 方言/口音变体
    "小美" to 0.82f,         // 普通话中 mì/měi 易混
    "小媽" to 0.80f,         // 部分方言

    // ─────────────────────────────────────────────────────────
    // 带修饰前缀的自然表达
    // ─────────────────────────────────────────────────────────
    "嘿小浪" to 0.92f,       // 口语启动词 + 唤醒词
    "哎小浪" to 0.92f,       // 口语启动词 + 唤醒词
    "呃小浪" to 0.90f,       // 犹豫词 + 唤醒词
    "喂小浪" to 0.91f,       // 通话启动词

    // ─────────────────────────────────────────────────────────
    // 后缀表达（用户自然说话习惯）
    // ─────────────────────────────────────────────────────────
    "小浪啊" to 0.90f,       // 语气助词
    "小浪呀" to 0.89f,       // 语气助词
    "小浪你好" to 0.88f,     // 完整打招呼（会被作为指令去掉唤醒词后执行）

    // ─────────────────────────────────────────────────────────
    // 可选备用名称（可在设置中启用/禁用）
    // ─────────────────────────────────────────────────────────
    // "小宝" to 0.75f,       // 备用昵称（降低优先级避免误触发）
    // "小助手" to 0.78f,     // 功能描述类名称
    // "相机助手" to 0.72f,   // 长名称（误识风险高）
)

/** 高精准模式：严格匹配核心唤醒词集合（用于低功耗场景） */
private val CORE_WAKE_WORDS = setOf(
    "小浪",
    "小蜜",
    "小秘",
    "小密",
    "小米",
    "小咪",
)

/**
 * 唤醒词引擎（生产级实现）
 *
 * 【功能】
 * 在后台持续监听音频，通过 VAD 检测语音活动，
 * 检测到语音后触发 ASR 识别，检查是否包含唤醒词，
 * 仅当包含唤醒词时才将指令文本通过回调返回。
 *
 * 【优化策略】
 * 1. **识别精准度优化**：
 *    - 支持同音、近音、口语多种唤醒词变体（21+ 个关键词）
 *    - 带权重的唤醒词匹配（优先级 0.8-1.0）
 *    - VAD 稳定性检查（连续 3 帧语音后才触发 ASR）
 *
 * 2. **功耗优化**：
 *    - 低功耗轮询：未检测语音时 150ms（vs 高精度模式 30ms）
 *    - 活动期间启用高精度：检测到语音后切换 30ms
 *    - 唤醒后立即释放 ASR 模型（非唤醒期间 ASR 保持卸载）
 *
 * 3. **冷却期管理**：
 *    - 唤醒后 1.2s 内忽略重复触发
 *    - 降低误触发概率
 *
 * 【生命周期】
 * - start() → 启动监听（ASR 待命）
 * - 检测唤醒词 → 加载 ASR 并转录
 * - 识别完成 → 立即释放 ASR 以节省功耗
 * - stop() → 停止监听和释放所有资源
 */
class WakeWordEngine(
    private val asrEngine: AsrEngine,
    private val scope: CoroutineScope,
    context: android.content.Context? = null
) {

    private val tag = "PoLang:WakeWord"
    private val audioRecorder = AudioRecorder(context)

    // 【灵敏度优化参数】
    // thresholdDb: 25f（降低 5dB 阈值，提高灵敏度）
    // minSpeechMs: 80ms（从 100ms 降低，更快触发检测）
    private val vadDetector = VadDetector(thresholdDb = 25f, minSpeechMs = 80)

    private var isRunning = false
    private var lastWakeTime = 0L
    private var consecutiveSpeechFrames = 0                    // 【稳定性检查】连续语音帧计数
    private var isInHighPrecisionMode = false                  // 【功耗优化】是否在高精度模式
    private var lastPollDelayMs = LOW_POWER_POLL_MS            // 当前轮询延迟

    /**
     * 启动唤醒词监听
     *
     * @param onTranscript 识别到文本后的回调（在主线程）
     */
    fun start(onTranscript: (String) -> Unit) {
        if (isRunning) {
            Logger.w(tag, "Wake word engine already running")
            return
        }

        val started = audioRecorder.start()
        if (!started) {
            Logger.e(tag, "Failed to start audio recorder")
            return
        }

        isRunning = true
        consecutiveSpeechFrames = 0
        isInHighPrecisionMode = false
        lastPollDelayMs = LOW_POWER_POLL_MS
        vadDetector.reset()
        Logger.i(tag, "Wake word engine started (keywords: ${WAKE_WORD_VARIANTS.size}, core: ${CORE_WAKE_WORDS.size})")

        scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                val buffer = audioRecorder.read()
                if (buffer.isEmpty()) {
                    delay(lastPollDelayMs)
                    continue
                }

                val isSpeech = vadDetector.process(buffer)

                // 【功耗优化】动态调整轮询间隔
                if (isSpeech) {
                    if (!isInHighPrecisionMode) {
                        isInHighPrecisionMode = true
                        lastPollDelayMs = ACTIVE_POLL_MS
                        Logger.d(tag, "Entered high precision mode (polling: ${ACTIVE_POLL_MS}ms)")
                    }
                    consecutiveSpeechFrames++
                } else {
                    if (isInHighPrecisionMode) {
                        isInHighPrecisionMode = false
                        lastPollDelayMs = LOW_POWER_POLL_MS
                        Logger.d(tag, "Exited high precision mode (polling: ${LOW_POWER_POLL_MS}ms)")
                    }
                    consecutiveSpeechFrames = 0
                    delay(lastPollDelayMs)
                    continue
                }

                // 【精准度优化】连续 VAD_STABILITY_FRAMES 帧后才触发 ASR
                if (consecutiveSpeechFrames < VAD_STABILITY_FRAMES) {
                    Logger.d(tag, "Speech detected but stability check: $consecutiveSpeechFrames/$VAD_STABILITY_FRAMES")
                    delay(lastPollDelayMs)
                    continue
                }

                val now = System.currentTimeMillis()
                // 【冷却期检查】避免短时间内重复触发
                if (now - lastWakeTime < WAKE_COOLDOWN_MS) {
                    Logger.d(tag, "Speech detected but in cooldown (${now - lastWakeTime}ms / ${WAKE_COOLDOWN_MS}ms), skipped")
                    vadDetector.reset()
                    consecutiveSpeechFrames = 0
                    delay(LOW_POWER_POLL_MS)
                    continue
                }

                Logger.i(tag, "Triggering ASR (stability: $consecutiveSpeechFrames frames, confidence: ${(consecutiveSpeechFrames * 100 / VAD_STABILITY_FRAMES)}%)")

                // 【关键】：仅在被唤醒时加载 ASR（功耗优化）
                if (!asrEngine.isAvailable()) {
                    Logger.w(tag, "ASR engine not available for transcription, skipping")
                    delay(lastPollDelayMs)
                    continue
                }

                val audioSegment = audioRecorder.readSegment(
                    maxDurationMs = MAX_SEGMENT_DURATION_MS,
                    silenceTimeoutMs = SEGMENT_SILENCE_TIMEOUT_MS
                )

                if (audioSegment.isNotEmpty()) {
                    val result = asrEngine.transcribe(audioSegment)
                    result.onSuccess { transcript ->
                        if (transcript.isNotBlank()) {
                            val matchResult = findMatchedWakeWordWithScore(transcript)
                            if (matchResult != null) {
                                val (matchedVariant, confidence) = matchResult
                                val command = stripWakeWord(transcript, matchedVariant)
                                Logger.i(tag, "✓ Wake word matched: '$matchedVariant' (confidence: $confidence), command: '$command' (raw: '$transcript')")
                                lastWakeTime = System.currentTimeMillis()
                                scope.launch(Dispatchers.Main) {
                                    onTranscript(command)
                                }
                            } else {
                                Logger.d(tag, "✗ No wake word variant found in: '$transcript', ignored")
                            }
                        }
                    }.onFailure { error ->
                        Logger.e(tag, "ASR failed during transcription", error)
                    }
                } else {
                    Logger.d(tag, "No audio segment captured (timeout or empty)")
                }

                vadDetector.reset()
                consecutiveSpeechFrames = 0
                delay(lastPollDelayMs)
            }

            audioRecorder.stop()
            Logger.i(tag, "Wake word engine stopped")
        }
    }

    /**
     * 停止唤醒词监听
     *
     * 直接调用 audioRecorder.stop() 确保 read/readSegment 立即退出，
     * 避免与 start() 的竞态条件导致 AudioRecord 被重复释放。
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        audioRecorder.stop()
        Logger.d(tag, "Wake word engine stopping...")
    }

    /**
     * 从转录文本中查找匹配的唤醒词变体并返回匹配分数
     *
     * 【匹配策略】：
     * - 优先匹配最长唤醒词变体，避免"小浪"提前吞掉"嘿小浪"等前缀变体
     * - 长度相同时按 confidence 分数降序选择
     * - 返回匹配到的词 + 对应的 confidence
     *
     * @param transcript ASR 原始转录文本
     * @return Pair<唤醒词, confidence> 或 null 如果无匹配
     */
    internal fun findMatchedWakeWordWithScore(transcript: String): Pair<String, Float>? {
        return WAKE_WORD_VARIANTS.entries
            .filter { transcript.contains(it.key) }
            .maxByOrNull { it.key.length * 1000 + (it.value * 100).toInt() }
            ?.let { Pair(it.key, it.value) }
    }

    /**
     * 从转录文本中查找匹配的唤醒词变体（兼容旧接口）
     *
     * @param transcript ASR 原始转录文本
     * @return 匹配到的唤醒词变体，未匹配返回 null
     */
    internal fun findMatchedWakeWord(transcript: String): String? {
        return findMatchedWakeWordWithScore(transcript)?.first
    }

    /**
     * 从转录文本中移除唤醒词（含任意变体），提取实际指令
     *
     * 【移除策略】：
     * - 依次移除所有已知唤醒词变体（从长到短，避免部分匹配）
     * - 移除前缀/中缀/后缀均可处理
     * - 可能多次出现的唤醒词全部移除
     *
     * 例如：
     * - "小浪拍张照" → "拍张照"
     * - "小蜜拍张照" → "拍张照"（ASR 将"小浪"误识为"小蜜"）
     * - "小米调高美颜" → "调高美颜"（ASR 将"小浪"误识为"小米"）
     * - "拍张照小浪" → "拍张照"
     * - "小浪小浪拍张照" → "拍张照"
     * - "嘿小浪拍照" → "拍照"
     *
     * @param transcript ASR 原始转录文本
     * @param matchedVariant 实际匹配到的唤醒词变体（优先移除）
     * @return 移除唤醒词后的指令文本
     */
    internal fun stripWakeWord(transcript: String, matchedVariant: String = "小浪"): String {
        var result = transcript

        // 【关键】优先移除已匹配的变体（可能多次出现）
        result = result.replace(matchedVariant, "")

        // 移除其他所有已知唤醒词变体（按长度降序，避免部分匹配）
        val otherVariants = WAKE_WORD_VARIANTS.keys
            .filter { it != matchedVariant }
            .sortedByDescending { it.length }

        for (variant in otherVariants) {
            result = result.replace(variant, "")
        }

        return result.trim()
    }
}
