package com.mamba.picme.agent.core.platform.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherpa-ONNX ASR 引擎实现（ONNX Runtime 后端）
 *
 * 使用 sherpa-onnx 库（ONNX Runtime 后端）进行端侧语音识别。
 * 与 Sherpa-MNN 版本功能等价，但底层运行时不与 MNN 共享状态。
 *
 * 支持两种模式：
 * 1. 离线模式（Push-to-Talk）：一次性送入所有音频
 * 2. 实时流式模式（Streaming）：持续送入音频 chunk，实时获取结果
 *
 * 与 SherpaMnnAsrEngine 的关键区别：
 * - 不再依赖 MnnResourceManager / MnnGlobalReleaseLock
 * - ONNX Runtime 独立管理自身生命周期
 * - 模型文件为 .onnx 格式（而非 .mnn）
 *
 * @param context Application Context
 * @param modelDir 模型目录路径（包含 encoder/decoder/joiner .onnx 和 tokens.txt）
 */
class SherpaOnnxAsrEngine(
    private val context: Context,
    private val modelDir: String
) : AsrEngine {

    private val tag = "SherpaOnnxAsr"
    private var recognizer: OnlineRecognizer? = null
    private val initLock = Object()

    // 流式识别状态
    private var isStreaming = AtomicBoolean(false)
    private var audioRecorder: AudioRecorder? = null
    private var streamingScope: CoroutineScope? = null
    private var streamingJob: Job? = null

    override fun isAvailable(): Boolean {
        val available = recognizer != null || tryInitRecognizer()
        if (!available) {
            Logger.w(tag, "ASR engine not available: recognizer=$recognizer")
        }
        return available
    }

    /**
     * 是否支持实时流式识别
     */
    override fun supportsStreaming(): Boolean = true

    /**
     * 将音频数据转录为文本（离线模式）
     *
     * 音频数据格式：16kHz, 16bit, 单声道 PCM
     * 使用 OnlineRecognizer 模拟离线识别（一次性送入所有音频）
     */
    override suspend fun transcribe(audioData: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) {
                return@withContext Result.failure(
                    IllegalStateException("Sherpa-ONNX recognizer not initialized")
                )
            }

            val recog = recognizer ?: return@withContext Result.failure(
                IllegalStateException("Recognizer is null")
            )

            @Suppress("TooGenericExceptionCaught")
            try {
                // 1. 将 16bit PCM ByteArray 转为 FloatArray（归一化到 [-1, 1]）
                val samples = pcm16ToFloatArray(audioData)
                Logger.d(tag, "Transcribing ${samples.size} samples (${audioData.size} bytes)")

                // 2. 创建识别流
                val stream = recog.createStream("")
                val text = try {
                    // 3. 送入音频数据
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    stream.inputFinished()

                    // 4. 循环解码直到没有更多结果
                    while (recog.isReady(stream)) {
                        recog.decode(stream)
                    }

                    // 5. 获取最终结果
                    val result = recog.getResult(stream)
                    result.text.trim()
                } finally {
                    stream.release()
                }
                Logger.i(tag, "ASR result: '$text'")
                Result.success(text.deduplicateConsecutiveChars())
            } catch (e: Exception) {
                Logger.e(tag, "ASR transcription failed", e)
                Result.failure(e)
            }
        }

    /**
     * 开始实时流式识别
     *
     * - 启动 AudioRecord 持续录音
     * - 每 100ms 读取一个 chunk（约 1600 samples）
     * - 送入 OnlineStream，循环 decode
     * - 检测到 endpoint 时返回结果并 reset stream
     */
    override fun startStreaming(
        onPartialResult: ((String) -> Unit)?,
        onFinalResult: (String) -> Unit
    ) {
        if (isStreaming.get()) {
            Logger.w(tag, "Streaming already started")
            return
        }
        if (!isAvailable()) {
            Logger.e(tag, "Recognizer not available for streaming")
            return
        }

        val recog = recognizer ?: run {
            Logger.e(tag, "Recognizer is null")
            return
        }

        isStreaming.set(true)
        streamingScope = CoroutineScope(Dispatchers.IO)

        streamingJob = streamingScope?.launch {
            val recorder = AudioRecorder(context)
            audioRecorder = recorder

            val started = recorder.start()
            if (!started) {
                Logger.e(tag, "Failed to start audio recorder for streaming")
                isStreaming.set(false)
                return@launch
            }

            Logger.i(tag, "Streaming ASR started")

            // 创建识别流
            val stream = recog.createStream("")

            val interval = 0.1 // 100ms per chunk
            val bufferSize = (interval * SAMPLE_RATE).toInt() // samples per chunk
            val buffer = ShortArray(bufferSize)

            try {
                while (isStreaming.get()) {
                    val ret = recorder.readShortArray(buffer, 0, buffer.size)
                    if (ret > 0) {
                        // 转换为 FloatArray 并归一化
                        val samples = FloatArray(ret) { i -> buffer[i] / 32768.0f }

                        // 送入音频数据
                        stream.acceptWaveform(samples, SAMPLE_RATE)

                        // 循环解码
                        while (recog.isReady(stream)) {
                            recog.decode(stream)
                        }

                        // 检查端点
                        val isEndpoint = recog.isEndpoint(stream)

                        // 获取当前结果（用于 partial result）
                        val currentResult = recog.getResult(stream)
                        if (currentResult.text.isNotBlank()) {
                            onPartialResult?.invoke(currentResult.text.trim())
                        }

                        if (isEndpoint) {
                            val text = currentResult.text.trim()
                            if (text.isNotEmpty()) {
                                Logger.i(tag, "Streaming ASR endpoint: '$text'")
                                streamingScope?.launch(Dispatchers.Main) {
                                    onFinalResult(text)
                                }
                            }
                            // Reset stream for next utterance
                            recog.reset(stream)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(tag, "Streaming ASR error", e)
            } finally {
                stream.release()
                recorder.stop()
                audioRecorder = null
                Logger.i(tag, "Streaming ASR stopped")
            }
        }
    }

    /**
     * 停止实时流式识别
     */
    override fun stopStreaming() {
        if (!isStreaming.get()) return
        isStreaming.set(false)
        audioRecorder?.stop()

        val job = streamingJob
        if (job != null && job.isActive) {
            try {
                val latch = CountDownLatch(1)
                streamingScope?.launch {
                    job.join()
                    latch.countDown()
                }
                val finished = latch.await(2000, TimeUnit.MILLISECONDS)
                if (!finished) {
                    Logger.w(tag, "Streaming job did not finish within 2s, proceeding anyway")
                }
            } catch (e: Exception) {
                Logger.w(tag, "Error waiting for streaming job", e)
            }
        }

        streamingScope?.launch { }
        streamingJob = null
        Logger.d(tag, "Streaming ASR stopped")
    }

    /**
     * 释放识别器资源
     *
     * ONNX Runtime 不共享全局状态，直接释放即可，无需协调。
     */
    override fun release() {
        stopStreaming()
        synchronized(initLock) {
            recognizer?.release()
            recognizer = null
            Logger.i(tag, "ASR fully released")
        }
    }

    /**
     * 尝试初始化识别器
     */
    private fun tryInitRecognizer(): Boolean {
        synchronized(initLock) {
            if (recognizer != null) return true

            return try {
                val dir = File(modelDir)
                if (!dir.exists() || !dir.isDirectory) {
                    Logger.w(tag, "Model directory not found: $modelDir")
                    return false
                }

                // 检查必需文件是否存在
                val requiredFiles = listOf(
                    "encoder-epoch-99-avg-1.int8.onnx",
                    "decoder-epoch-99-avg-1.int8.onnx",
                    "joiner-epoch-99-avg-1.int8.onnx",
                    "tokens.txt"
                )
                val missingFiles = requiredFiles.filter { !File(dir, it).exists() }
                if (missingFiles.isNotEmpty()) {
                    Logger.w(tag, "Missing model files: $missingFiles")
                    return false
                }

                Logger.i(tag, "Initializing Sherpa-ONNX recognizer with model: $modelDir")

                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                            decoder = "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                            joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                        ),
                        tokens = "$modelDir/tokens.txt",
                        numThreads = 1,
                        provider = "cpu",
                        modelType = "zipformer",
                    ),
                    lmConfig = OnlineLMConfig(),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0.0f),
                        rule2 = EndpointRule(true, 1.4f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 20.0f)
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4,
                    hotwordsFile = "",
                    hotwordsScore = 1.5f,
                )

                recognizer = OnlineRecognizer(null, config)
                Logger.i(tag, "Sherpa-ONNX recognizer initialized successfully")
                true
            } catch (e: Exception) {
                Logger.e(tag, "Failed to initialize Sherpa-ONNX recognizer", e)
                false
            }
        }
    }

    /**
     * 将 16bit PCM ByteArray 转换为 FloatArray（归一化到 [-1, 1]）
     */
    private fun pcm16ToFloatArray(pcmData: ByteArray): FloatArray {
        if (pcmData.size % 2 != 0) {
            return pcm16ToFloatArray(pcmData.copyOf(pcmData.size - 1))
        }

        val sampleCount = pcmData.size / 2
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(sampleCount) { i ->
            buffer.getShort(i * 2).toFloat() / 32768.0f
        }
    }
}

private const val SAMPLE_RATE = 16000
private const val FEATURE_DIM = 80

/**
 * 去除连续重复字符（修复 Sherpa-ONNX Zipformer 短语音重复解码 artifact）
 *
 * 例如 "拍拍照照" → "拍照"，"换换暖暖色色" → "换暖色"
 * 中文正常语音不会有连续相同字符，此清理对中文安全无害。
 */
private fun String.deduplicateConsecutiveChars(): String {
    if (length <= 1) return this
    val sb = StringBuilder(length)
    var last = this[0]
    sb.append(last)
    for (i in 1 until length) {
        val c = this[i]
        if (c != last) {
            sb.append(c)
        }
        last = c
    }
    return sb.toString()
}

