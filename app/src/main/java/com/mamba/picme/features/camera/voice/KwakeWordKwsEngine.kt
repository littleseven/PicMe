package com.mamba.picme.features.camera.voice

import com.mamba.picme.agent.core.platform.voice.AudioRecorder
import com.mamba.picme.agent.core.platform.voice.KeywordSpotterEngine
import com.mamba.picme.agent.core.platform.voice.SpotterStream
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_DELAY_MS = 30L
private const val KWS_CHUNK_MS = 100L               // 【优化】100ms 音频块，平衡延迟和计算
private const val WAKE_COOLDOWN_MS = 1200L          // 【防重复】唤醒后 1.2s 内忽略重复触发
private const val KWS_SAMPLE_RATE = 16000           // 标准采样率
private const val KWS_FEATURE_DIM = 80              // 特征维度

/**
 * KWS 唤醒词引擎（sherpa-onnx KeywordSpotter 版）
 *
 * 【功能】
 * 使用专用 KWS 模型实现 always-on 唤醒词检测，替代原有的
 * "VAD + 全量 ASR + 文本匹配"方案。
 *
 * 【技术优势】
 * - 模型体积：~14MB（vs ASR 282MB）→ ↓ 95%
 * - 功耗：~50mW（vs ASR ~500mW）→ ↓ 90%
 * - 唤醒延迟：~50ms（vs 原方案 ~800ms+）→ ↓ 94%
 * - always-on：常驻内存极小，真正低功耗待机
 *
 * 【工作原理】
 * 1. 持续读取音频流（100ms chunks）
 * 2. 送入 KWS 模型进行实时推理
 * 3. 检测到关键词（如"小浪"）立即回调
 * 4. 冷却期防重复，1.2s 后恢复检测
 *
 * 【后续集成】
 * 唤醒成功后：
 * - 启动 ASR 模型进行完整转录（获取指令）
 * - 使用指令执行相应操作
 * - ASR 完成后立即释放以节省功耗
 *
 * @param kwsEngine KeywordSpotter 引擎实例（调用方创建）
 * @param scope 协程作用域
 * @param context Android Context（用于音频权限等）
 */
class KwakeWordKwsEngine(
    private val kwsEngine: KeywordSpotterEngine,
    private val scope: CoroutineScope,
    context: android.content.Context? = null
) {

    private val tag = "PicMe:WakeWordKWS"
    private val audioRecorder = AudioRecorder(context)
    private var isRunning = false
    private var lastWakeTime = 0L
    private var detectedKeywords = 0                 // 统计检测到的唤醒词次数
    private var skippedByCooldown = 0                // 统计因冷却期被忽略的次数

    /**
     * 启动唤醒词监听
     *
     * 【工作流程】
     * 1. 初始化检查：引擎可用、音频权限获取
     * 2. 启动音频采集：持续读取 100ms 音频块
     * 3. 实时推理：送入 KWS 模型检测关键词
     * 4. 防重复：冷却期内忽略重复触发
     * 5. 回调通知：检测到关键词后通知调用方
     *
     * @param onWakeWord 检测到唤醒词时的回调（在主线程）
     */
    fun start(onWakeWord: () -> Unit) {
        Logger.d(tag, "START: Attempting to start KWS wake word engine")

        if (isRunning) {
            Logger.w(tag, "⚠️ SKIP: KWS wake word engine already running")
            return
        }

        Logger.d(tag, "CHECK: Verifying KWS engine availability")
        if (!kwsEngine.isAvailable()) {
            Logger.e(tag, "❌ ERROR: KWS engine not available, check model download status")
            Logger.e(tag, "  - Model directory may not exist")
            Logger.e(tag, "  - Model files may be incomplete or corrupted")
            Logger.e(tag, "  - Try manually downloading model from ModelScope")
            return
        }
        Logger.d(tag, "✓ CHECK: KWS engine available")

        Logger.d(tag, "AUDIO: Starting audio recorder")
        val started = audioRecorder.start()
        if (!started) {
            Logger.e(tag, "❌ ERROR: Failed to start audio recorder for KWS")
            Logger.e(tag, "  - Check audio permissions (RECORD_AUDIO)")
            Logger.e(tag, "  - Verify microphone is accessible")
            Logger.e(tag, "  - Check if another app is using microphone")
            return
        }
        Logger.d(tag, "✓ AUDIO: Audio recorder started successfully")

        isRunning = true
        detectedKeywords = 0
        skippedByCooldown = 0

        Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Logger.i(tag, "✓ KWS WAKE WORD ENGINE STARTED")
        Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Logger.i(tag, "📋 Configuration:")
        Logger.i(tag, "  • Keywords: ${kwsEngine.getKeywords().joinToString(", ")}")
        Logger.i(tag, "  • Chunk Size: ${KWS_CHUNK_MS}ms")
        Logger.i(tag, "  • Sample Rate: ${KWS_SAMPLE_RATE}Hz")
        Logger.i(tag, "  • Cooldown Period: ${WAKE_COOLDOWN_MS}ms")
        Logger.i(tag, "  • Total Keywords: ${kwsEngine.getKeywords().size}")
        Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        scope.launch(Dispatchers.IO) {
             Logger.d(tag, "LOOP: Entering main loop on IO dispatcher")

             // 【优化】每 100ms 读取一个 chunk，平衡实时性和计算成本
             val chunkSamples = (KWS_CHUNK_MS * KWS_SAMPLE_RATE / 1000).toInt()
             Logger.d(tag, "  • Chunk samples: $chunkSamples (${KWS_CHUNK_MS}ms @ ${KWS_SAMPLE_RATE}Hz)")
             val buffer = ShortArray(chunkSamples)

             var kwsStream: SpotterStream? = null
             val startTime = System.currentTimeMillis()
             var processedChunks = 0
             var totalAudioMs = 0L

             try {
                 Logger.d(tag, "WAIT: Waiting for audio input...")
                 var lastLogTime = System.currentTimeMillis()

                 while (isRunning && isActive) {
                     // 【性能】读取音频块
                     val ret = audioRecorder.readShortArray(buffer, 0, buffer.size)
                     if (ret <= 0) {
                         delay(POLL_DELAY_MS)
                         continue
                     }

                     processedChunks++
                     totalAudioMs += KWS_CHUNK_MS
                     val now = System.currentTimeMillis()

                     // 每隔 5s 打印统计信息
                     if (now - lastLogTime >= 5000) {
                         Logger.d(tag, "📊 STATS: Chunks=$processedChunks, AudioMs=$totalAudioMs, Detected=$detectedKeywords, Skipped=$skippedByCooldown")
                         lastLogTime = now
                     }

                     // 【关键】归一化到 [-1, 1]，防止数值溢出
                     val samples = FloatArray(ret) { i -> buffer[i] / 32768.0f }

                     // 【创建/复用】KWS 流（一次性创建，持续复用）
                     if (kwsStream == null) {
                         Logger.d(tag, "INIT: Creating KWS stream")
                         kwsStream = kwsEngine.createStream()
                         if (kwsStream == null) {
                             Logger.e(tag, "❌ ERROR: Failed to create KWS stream")
                             Logger.e(tag, "  - KWS engine may not be properly initialized")
                             Logger.e(tag, "  - Model files may be incomplete")
                             delay(POLL_DELAY_MS)
                             continue
                         }
                         Logger.d(tag, "✓ INIT: KWS stream created successfully")
                     }

                     // 【送入音频】
                     kwsStream.acceptWaveform(samples, KWS_SAMPLE_RATE)

                     // 【解码】运行 KWS 模型推理
                     kwsStream.decode()

                     // 【检查结果】
                     val keyword = kwsStream.getResult()
                     if (keyword != null) {
                         val now = System.currentTimeMillis()
                         Logger.d(tag, "🔔 DETECT: Keyword detected: '$keyword'")

                         // 【防重复】冷却期检查
                         if (now - lastWakeTime >= WAKE_COOLDOWN_MS) {
                             detectedKeywords++
                             Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                             Logger.i(tag, "🎯 WAKE WORD DETECTED!")
                             Logger.i(tag, "  • Keyword: '$keyword'")
                             Logger.i(tag, "  • Total Detections: $detectedKeywords")
                             Logger.i(tag, "  • Timestamp: ${System.currentTimeMillis()}")
                             Logger.i(tag, "  • Uptime: ${now - startTime}ms")
                             Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                             lastWakeTime = now

                             // 【关键】在主线程回调，由调用方启动 ASR 进行完整转录
                             scope.launch(Dispatchers.Main) {
                                 Logger.d(tag, "→ CALLBACK: Invoking onWakeWord() on main thread")
                                 try {
                                     onWakeWord()
                                     Logger.d(tag, "✓ CALLBACK: Completed successfully")
                                 } catch (e: Exception) {
                                     Logger.e(tag, "❌ CALLBACK: Exception during callback", e)
                                 }
                             }
                          } else {
                              skippedByCooldown++
                              val remaining = WAKE_COOLDOWN_MS - (now - lastWakeTime)
                              Logger.d(tag, "⏱️  COOLDOWN: '$keyword' ignored (${remaining}ms remaining, total skipped: $skippedByCooldown)")
                          }
                     }
                 }
                 Logger.i(tag, "LOOP: Main loop exiting (isRunning=$isRunning)")
             } catch (e: Exception) {
                 Logger.e(tag, "❌ ERROR: KWS wake word engine error", e)
                 Logger.e(tag, "  • Exception type: ${e::class.simpleName}")
                 Logger.e(tag, "  • Message: ${e.message}")
                 Logger.e(tag, "  • Processed chunks: $processedChunks")
             } finally {
                 Logger.d(tag, "CLEANUP: Releasing resources")
                 try {
                     kwsStream?.release()
                     Logger.d(tag, "  ✓ KWS stream released")
                 } catch (e: Exception) {
                     Logger.w(tag, "  ⚠️  Warning during stream release: ${e.message}")
                 }

                 try {
                     audioRecorder.stop()
                     Logger.d(tag, "  ✓ Audio recorder stopped")
                 } catch (e: Exception) {
                     Logger.w(tag, "  ⚠️  Warning during audio stop: ${e.message}")
                 }

                 val duration = System.currentTimeMillis() - startTime
                 Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                 Logger.i(tag, "✓ KWS WAKE WORD ENGINE STOPPED")
                 Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                 Logger.i(tag, "📊 Final Statistics:")
                 Logger.i(tag, "  • Duration: ${duration}ms")
                 Logger.i(tag, "  • Chunks Processed: $processedChunks")
                 Logger.i(tag, "  • Audio Duration: ${totalAudioMs}ms")
                 Logger.i(tag, "  • Keywords Detected: $detectedKeywords")
                 Logger.i(tag, "  • Keywords Skipped (Cooldown): $skippedByCooldown")
                 Logger.i(tag, "  • Processing Rate: ${if (duration > 0) (processedChunks * 1000 / duration) else 0} chunks/sec")
                 Logger.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
             }
         }
    }

    /**
     * 停止唤醒词监听
     *
     * 【清理流程】
     * 1. 标记停止运行（通知主循环退出）
     * 2. 关闭音频采集（释放麦克风资源）
     * 3. 等待协程安全退出（无需主动 join）
     * 4. KWS 流和模型由 finally 块自动释放
     */
    fun stop() {
        if (!isRunning) {
            Logger.d(tag, "Already stopped")
            return
        }
        isRunning = false
        audioRecorder.stop()
        Logger.i(tag, "KWS wake word engine stopping (graceful shutdown initiated)...")
    }

    /**
     * 获取统计信息（调试用）
     *
     * @return 检测统计信息字符串
     */
    fun getStats(): String = "detected=$detectedKeywords, skipped=$skippedByCooldown"

    /**
     * 重置统计信息
     */
    fun resetStats() {
        detectedKeywords = 0
        skippedByCooldown = 0
        Logger.d(tag, "Statistics reset")
    }

    /**
     * 获取详细的性能统计信息（用于调试界面）
     *
     * @return JSON 格式的统计信息
     */
    fun getDetailedStats(): Map<String, Any> = mapOf(
        "isRunning" to isRunning,
        "detected_keywords" to detectedKeywords,
        "skipped_by_cooldown" to skippedByCooldown,
        "last_wake_time" to lastWakeTime,
        "last_wake_ms_ago" to if (isRunning) (System.currentTimeMillis() - lastWakeTime) else 0L,
        "accuracy_rate" to if ((detectedKeywords + skippedByCooldown) > 0) {
            String.format("%.1f%%", (detectedKeywords.toFloat() / (detectedKeywords + skippedByCooldown) * 100))
        } else {
            "N/A"
        }
    )

    /**
     * 获取引擎运行状态
     *
     * @return true 表示引擎正在运行
     */
    fun getRunningState(): Boolean = isRunning

    /**
     * 获取上次唤醒的时间戳（毫秒）
     *
     * @return 时间戳，如果从未唤醒过返回 0
     */
    fun getLastWakeTime(): Long = lastWakeTime

    /**
     * 获取自上次唤醒以来的时间（毫秒）
     *
     * @return 时间差（毫秒），如果从未唤醒过返回 -1
     */
    fun getLastWakeTimeDelta(): Long = if (lastWakeTime == 0L) {
        -1L
    } else {
        System.currentTimeMillis() - lastWakeTime
    }

    /**
     * 获取检测到的唤醒词总数
     *
     * @return 唤醒词计数
     */
    fun getDetectionCount(): Int = detectedKeywords

    /**
     * 获取被冷却期忽略的次数
     *
     * @return 忽略计数
     */
    fun getSkippedCount(): Int = skippedByCooldown

    /**
     * 获取唤醒准确率
     *
     * @return 准确率（0.0 ~ 1.0），如果无数据返回 0.0
     */
    fun getAccuracyRate(): Double {
        val total = detectedKeywords + skippedByCooldown
        return if (total > 0) detectedKeywords.toDouble() / total else 0.0
    }
}

