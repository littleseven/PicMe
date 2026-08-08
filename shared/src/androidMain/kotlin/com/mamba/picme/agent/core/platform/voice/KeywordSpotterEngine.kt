package com.mamba.picme.agent.core.platform.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.mamba.picme.agent.core.platform.logging.Logger
import java.io.File

/**
 * 关键词识别（KWS）引擎
 *
 * 使用 sherpa-onnx 的 KeywordSpotter 实现 always-on 唤醒词检测。
 * 比"VAD + 全量 ASR + 文本匹配"方案更轻量、更低功耗。
 *
 * 技术参数：
 * - 模型：~14MB（vs ASR 282MB）
 * - 功耗：~50mW（vs ASR ~500mW）
 * - 延迟：~50ms（vs ASR ~800ms）
 *
 * @param modelDir 模型目录路径（包含 KWS 模型文件和 keywords.txt）
 */
class KeywordSpotterEngine(
    private val modelDir: String,
) {

    private val tag = "KeywordSpotter"
    private var spotter: KeywordSpotter? = null
    private val initLock = Object()

    /**
     * 唤醒词列表（从 keywords.txt 加载，缓存结果）
     */
    @Volatile
    private var cachedKeywords: List<String>? = null

    private fun loadKeywords(): List<String> {
        return cachedKeywords ?: run {
            val result = try {
                val file = File(modelDir, "keywords.txt")
                if (file.exists()) {
                    file.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                } else {
                    Logger.w(tag, "keywords.txt not found in $modelDir")
                    emptyList()
                }
            } catch (e: Exception) {
                Logger.e(tag, "Failed to load keywords", e)
                emptyList()
            }
            cachedKeywords = result
            result
        }
    }

    fun isAvailable(): Boolean {
        return spotter != null || tryInitSpotter()
    }

    /**
     * 创建检测流。每次检测独立使用一个 stream。
     */
    fun createStream(): SpotterStream? {
        val s = spotter ?: run {
            if (!tryInitSpotter()) return null
            spotter ?: return null
        }
        return SpotterStream(s, s.createStream(""))
    }

    /**
     * 释放引擎资源
     */
    fun release() {
        synchronized(initLock) {
            spotter?.release()
            spotter = null
            Logger.i(tag, "KWS released")
        }
    }

    /**
     * 获取实际识别到的关键词列表（调试用）
     */
    fun getKeywords(): List<String> = loadKeywords()

    private fun tryInitSpotter(): Boolean {
        synchronized(initLock) {
            if (spotter != null) return true

            return try {
                val dir = File(modelDir)
                if (!dir.exists() || !dir.isDirectory) {
                    Logger.w(tag, "Model directory not found: $modelDir")
                    return false
                }

                // 【修复】仅使用 KWS 专用模型文件，不 fallback 到 ASR 模型
                // ASR 和 KWS 是不同模型类型（zipformer transducer vs zipformer keyword），
                // 互不兼容。加载 ASR 模型到 KeywordSpotter 会导致 ONNX Runtime 内部线程池
                // mutex 销毁竞态，触发 FORTIFY: pthread_mutex_lock on destroyed mutex 崩溃。
                val encoderFile = KWS_ENCODER_FILE.takeIf { File(dir, it).exists() }
                val decoderFile = KWS_DECODER_FILE.takeIf { File(dir, it).exists() }
                val joinerFile = KWS_JOINER_FILE.takeIf { File(dir, it).exists() }
                val tokensFile = File(dir, "tokens.txt")
                val keywordsFile = File(dir, "keywords.txt")

                // 验证必需文件
                val missingFiles = mutableListOf<String>()
                if (encoderFile == null) missingFiles.add(KWS_ENCODER_FILE)
                if (decoderFile == null) missingFiles.add(KWS_DECODER_FILE)
                if (joinerFile == null) missingFiles.add(KWS_JOINER_FILE)
                if (!tokensFile.exists()) missingFiles.add("tokens.txt")
                if (!keywordsFile.exists()) missingFiles.add("keywords.txt")

                if (missingFiles.isNotEmpty()) {
                    Logger.w(tag, "Missing KWS model files: $missingFiles")
                    return false
                }

                Logger.d(tag, "Found KWS model files:")
                Logger.d(tag, "  Encoder: $encoderFile")
                Logger.d(tag, "  Decoder: $decoderFile")
                Logger.d(tag, "  Joiner: $joinerFile")
                Logger.d(tag, "Initializing KeywordSpotter: model=$modelDir")

                Logger.d(tag, "Building KeywordSpotterConfig...")
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(
                        sampleRate = KWS_SAMPLE_RATE,
                        featureDim = KWS_FEATURE_DIM
                    ),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$modelDir/$encoderFile",
                            decoder = "$modelDir/$decoderFile",
                            joiner = "$modelDir/$joinerFile",
                        ),
                        tokens = "$modelDir/tokens.txt",
                        numThreads = 1,
                        provider = "cpu",
                        // 不指定 modelType，由 sherpa-onnx 自动检测
                    ),
                    keywordsFile = "$modelDir/keywords.txt",
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.5f,
                    numTrailingBlanks = 2,
                )
                Logger.d(tag, "Config built successfully")

                try {
                    Logger.d(tag, "Creating KeywordSpotter instance with config...")
                    spotter = KeywordSpotter(null, config)

                    Logger.d(tag, "KeywordSpotter created, loading keywords...")
                    val kwList = loadKeywords()
                    Logger.i(tag, "✓ KeywordSpotter initialized with ${kwList.size} keywords: $kwList")
                    true
                } catch (configError: Exception) {
                    // 【强制策略】不允许 KWS 初始化失败后继续
                    // 直接抛出异常，阻止应用启动
                    val errorMsg = buildString {
                        append("❌ KWS 引擎初始化失败（配置问题）\n")
                        append("  Error type: ${configError::class.simpleName}\n")
                        append("  Error message: ${configError.message}\n")
                        append("  Model directory: $modelDir\n")
                        append("  Found model files: encoder=$encoderFile, decoder=$decoderFile, joiner=$joinerFile\n")
                        append("  Possible causes:\n")
                        append("    1. Model metadata mismatch (sherpa-onnx 库不兼容)\n")
                        append("    2. 模型版本与 sherpa-onnx 库版本不匹配\n")
                        append("    3. Invalid model file format\n")
                        append("    4. Corrupted model files\n")
                        append("【强制策略】抛出异常以阻止应用启动")
                    }
                    Logger.e(tag, errorMsg)
                    throw IllegalStateException(errorMsg, configError)
                }
            } catch (e: Exception) {
                Logger.e(tag, "Failed to initialize KeywordSpotter (file validation or config error)", e)
                Logger.e(tag, "  Model directory: $modelDir")
                Logger.e(tag, "  Error type: ${e::class.simpleName}")
                Logger.e(tag, "  Error: ${e.message}")
                false
            }
        }
    }

    companion object {
        private const val KWS_SAMPLE_RATE = 16000
        private const val KWS_FEATURE_DIM = 80

        /** KWS 专用模型文件名（不可与 ASR 模型混用） */
        private const val KWS_ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val KWS_DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val KWS_JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    }
}

/**
 * KWS 检测流封装
 *
 * 提供与 OnlineStream 相同的 accept/decode/result 语义。
 */
class SpotterStream(
    private val spotter: KeywordSpotter,
    private val stream: com.k2fsa.sherpa.onnx.OnlineStream
) {
    private val tag = "SpotterStream"

    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000) {
        stream.acceptWaveform(samples, sampleRate)
    }

    fun decode(): Boolean {
        return if (spotter.isReady(stream)) {
            spotter.decode(stream)
            true
        } else {
            false
        }
    }

    /**
     * 获取检测结果。
     *
     * @return 识别到的关键词，未识别返回 null
     */
    fun getResult(): String? {
        val result = spotter.getResult(stream)
        return if (result.keyword.isNotEmpty()) result.keyword else null
    }

    /**
     * 重置流，用于下次检测
     */
    fun reset() {
        spotter.reset(stream)
    }

    fun release() {
        try {
            stream.release()
        } catch (e: Exception) {
            Logger.w(tag, "Stream release error", e)
        }
    }
}

