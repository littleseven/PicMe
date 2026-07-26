package com.mamba.picme.domain.tag.florence2

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.domain.tag.UnifiedTagResult
import com.mamba.picme.domain.tag.florence2.Florence2Tokenizer.decode
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Florence-2-base (231M) ONNX ORT 打标器（INT8 量化）。
 *
 * 四模型管道（独立于 OpusMtTranslator，不继承）：
 * 1. vision_encoder (INT8): Bitmap → 768×768 pixel_values → image_features [577, 768]
 * 2. embed_tokens   (INT8): task prompt token ids → text_embeds [L, 768]
 * 3. encoder        (INT8): [image_features ⊕ text_embeds] + attention_mask → encoder_hidden_states [T, 768]
 * 4. decoder (merged, INT8): encoder_hidden_states + 自回归生成 token ids
 *
 * decoder 两种模式（按文件大小自动选择，见 [DecoderMode]）：
 * - **KV cache**：fixed 版 `decoder_model_merged_quantized.onnx`（当前 catalog 分发版）。
 *   旧版 merged decoder 的 `use_cache_branch=true` 缓存分支是 optimum 导出 bug——
 *   If(then) 子图把 12 个 `present.{L}.encoder.{key,value}` 输出导成了 shape=(0,12,1,64)
 *   的空 Constant（fp32/INT8/q4 全坏），fixed 版由 `scripts/florence2_fix_merged_decoder.py`
 *   图手术改为 past 直通（cross-attn K/V 来自 encoder，decode 全程不变）。
 *   PC 验证：与 no-cache 输出一致，解码加速 ~4x。
 * - **no-cache 兜底**：旧版文件（大小不同，如未更新的本地副本）时，
 *   每步 `use_cache_branch=false` 全量重算（O(n²)，正确但慢）。
 *
 * 任务 prompt（**注意：processor 会把 `<OD>` 展开成完整指令句再 BPE 分词，不是裸 special token**）：
 * - `<OD>` → objects/tags（+ bbox loc 坐标）
 * - `<MORE_DETAILED_CAPTION>` → summary + scene/activity
 *
 * @param modelDir Florence-2 模型目录（含 4 个 INT8 ONNX + config/tokenizer 文件）。
 */
class Florence2Tagger(
    private val context: Context,
    private val modelDir: File
) {
    companion object {
        private const val TAG = "PoLang:Florence2"

        // INT8 量化文件（与 catalog / ModelPathConfig.FLORENCE2_MODEL_FILES 一致）
        private const val VISION_ENCODER = "vision_encoder_quantized.onnx"
        private const val TEXT_ENCODER = "encoder_model_quantized.onnx"
        private const val DECODER = "decoder_model_merged_quantized.onnx"
        private const val EMBED_TOKENS = "embed_tokens_int8.onnx"

        // decoder 同名两版按文件大小区分：旧版 98,177,854 B（use_cache_branch 有 optimum 导出 bug，
        // 只能走 no-cache）；fixed 版 98,178,346 B（+492B，12 个空 Constant → Identity 直通，
        // 走 KV cache）。下载管理器按远端 size 校验，会自动把旧版重下为 fixed 版。
        private const val DECODER_FIXED_SIZE = 98178346L

        // 图像预处理（from preprocessor_config.json — resize + ImageNet normalization）
        private const val IMAGE_SIZE = 768
        private val IMAGE_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGE_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        // 生成参数（from generation_config.json）
        private const val DECODER_START_TOKEN_ID = 2L
        private const val EOS_TOKEN_ID = 2L
        private const val MAX_NEW_TOKENS = 256

        // Florence-2-base BART 结构
        private const val NUM_LAYERS = 6
        private const val NUM_HEADS = 12
        private const val HEAD_DIM = 64
        private const val HIDDEN_SIZE = 768
        private const val VOCAB_SIZE = 51289

        // Task prompt token ids —— HF processor 展开 `<task>` 后 BPE 分词的结果（PC 已验证）。
        // <OD> = "<s>Locate the objects with category name in the image.</s>"
        val TASK_OD = longArrayOf(
            0, 574, 22486, 5, 8720, 19, 4120, 766, 11, 5, 2274, 4, 2
        )
        // <MORE_DETAILED_CAPTION> = "<s>Describe with a paragraph what is shown in the image.</s>"
        val TASK_MORE_DETAILED_CAPTION = longArrayOf(
            0, 47066, 21700, 19, 10, 17818, 99, 16, 2343, 11, 5, 2274, 4, 2
        )

        private val ortEnv by lazy { OrtEnvironment.getEnvironment() }
    }

    /** decoder 解码模式：fixed 模型文件存在 → KV cache；否则 no-cache 全量重算兜底。 */
    private enum class DecoderMode { CACHE, NO_CACHE }

    private var visionEnc: OrtSession? = null
    private var textEnc: OrtSession? = null
    private var decoder: OrtSession? = null
    private var embedder: OrtSession? = null
    private var decoderMode = DecoderMode.NO_CACHE

    private val initialized = AtomicBoolean(false)

    val isInit: Boolean get() = initialized.get()

    /**
     * 加载 4 个 OrtSession + tokenizer vocab。任一失败则释放已加载的并返回 false。
     */
    fun init(): Boolean {
        if (initialized.get()) return true
        return try {
            val opt = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
                // INT8 可用 ALL；q4f16 需降到 EXTENDED（LayerNorm fusion bug）
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            visionEnc = ortEnv.createSession(File(modelDir, VISION_ENCODER).absolutePath, opt)
            textEnc = ortEnv.createSession(File(modelDir, TEXT_ENCODER).absolutePath, opt)
            // 同名 decoder 按大小区分版本：fixed 版走 KV cache，旧版（未更新的副本）退回 no-cache
            val decoderFile = File(modelDir, DECODER)
            decoderMode = if (decoderFile.length() == DECODER_FIXED_SIZE) DecoderMode.CACHE else DecoderMode.NO_CACHE
            decoder = ortEnv.createSession(decoderFile.absolutePath, opt)
            embedder = ortEnv.createSession(File(modelDir, EMBED_TOKENS).absolutePath, opt)
            Florence2Tokenizer.load(modelDir)
            initialized.set(true)
            Log.i(TAG, "Florence2Tagger initialized (4 INT8 sessions, decoder=$decoderMode)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Florence2Tagger init failed", e)
            release()
            false
        }
    }

    fun release() {
        visionEnc?.close(); visionEnc = null
        textEnc?.close(); textEnc = null
        decoder?.close(); decoder = null
        embedder?.close(); embedder = null
        initialized.set(false)
    }

    // ═══════════════════════════════════════════════════
    //  完整打标流程
    // ═══════════════════════════════════════════════════

    /**
     * 对一张图片执行 OD + MORE_DETAILED_CAPTION 双任务打标。
     *
     * @param bitmap 输入图片
     * @return UnifiedTagResult（scene/activity 从 caption 抽取，objects/tags 从 OD 提取，summary 取 caption）
     */
    fun tag(bitmap: Bitmap): UnifiedTagResult {
        check(initialized.get()) { "Florence2Tagger not initialized" }

        // ── 1. 图像预处理 → vision encoder（每张图只跑一次）──
        val pixelValues = preprocessBitmap(bitmap)
        val imageFeatures = runVisionEncoder(pixelValues)

        // ── 2. OD 任务（物体检测 → objects/tags）──
        val odText = runTask(imageFeatures, TASK_OD)
        val objects = Florence2ResultParser.parseODLabels(odText)

        // ── 3. MORE_DETAILED_CAPTION 任务（→ summary + scene/activity）──
        val captionText = runTask(imageFeatures, TASK_MORE_DETAILED_CAPTION)
        val summary = captionText

        // ── 4. 组装 UnifiedTagResult ──
        val tags = objects.toMutableList()
        // 从 caption 中补充关键词（简单分词取实词）
        Florence2ResultParser.extractKeywords(captionText).forEach { kw ->
            if (kw !in tags && tags.size < 8) tags.add(kw)
        }

        return UnifiedTagResult(
            scene = Florence2ResultParser.extractScene(captionText),
            activity = Florence2ResultParser.extractActivity(captionText),
            objects = objects,
            tags = tags,
            summary = summary
        )
    }

    // ═══════════════════════════════════════════════════
    //  图像预处理
    // ═══════════════════════════════════════════════════

    /**
     * Bitmap → resize 到 768×768 → ImageNet normalize → [1, 3, 768, 768] float tensor。
     * 注意：Florence-2 的 preprocessor 是 resize（非 center crop）+ ImageNet mean/std。
     */
    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val resized = if (bitmap.width == IMAGE_SIZE && bitmap.height == IMAGE_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        }
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        if (resized !== bitmap) resized.recycle()

        // ImageNet normalize: CHW, (pixel/255 - mean) / std
        val tensor = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            tensor[i] = (r - IMAGE_MEAN[0]) / IMAGE_STD[0]
            tensor[IMAGE_SIZE * IMAGE_SIZE + i] = (g - IMAGE_MEAN[1]) / IMAGE_STD[1]
            tensor[2 * IMAGE_SIZE * IMAGE_SIZE + i] = (b - IMAGE_MEAN[2]) / IMAGE_STD[2]
        }
        return tensor
    }

    // ═══════════════════════════════════════════════════
    //  Vision Encoder
    // ═══════════════════════════════════════════════════

    /**
     * pixel_values [1,3,768,768] → vision_encoder → image_features [N, 768]（N=577）。
     */
    private fun runVisionEncoder(pixelValues: FloatArray): Array<FloatArray> {
        val session = visionEnc!!
        val shape = longArrayOf(1L, 3L, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixelValues), shape)
        val inputs = HashMap<String, OnnxTensor>().apply { put("pixel_values", inputTensor) }
        val output = session.run(inputs)
        inputTensor.close()

        @Suppress("UNCHECKED_CAST")
        val raw = output[0].value as Array<Array<FloatArray>> // [1, N, 768]
        output.close()

        val n = raw[0].size
        val result = Array(n) { FloatArray(HIDDEN_SIZE) }
        for (i in 0 until n) {
            System.arraycopy(raw[0][i], 0, result[i], 0, HIDDEN_SIZE)
        }
        return result
    }

    // ═══════════════════════════════════════════════════
    //  单任务执行（embed + encoder + no-cache decoder loop）
    // ═══════════════════════════════════════════════════

    /**
     * 执行一个 Florence-2 任务（OD / CAPTION），返回解码后的文本。
     */
    private fun runTask(
        imageFeatures: Array<FloatArray>,
        taskTokenIds: LongArray
    ): String {
        // ── embed_tokens: task ids → text_embeds [L, 768] ──
        val textEmbeds = runEmbedTokens(taskTokenIds)

        // ── concat [image_features ⊕ text_embeds] → inputs_embeds [T, 768] ──
        val totalLen = imageFeatures.size + textEmbeds.size
        val inputsEmbeds = FloatArray(totalLen * HIDDEN_SIZE)
        var offset = 0
        for (feat in imageFeatures) {
            System.arraycopy(feat, 0, inputsEmbeds, offset, HIDDEN_SIZE)
            offset += HIDDEN_SIZE
        }
        for (feat in textEmbeds) {
            System.arraycopy(feat, 0, inputsEmbeds, offset, HIDDEN_SIZE)
            offset += HIDDEN_SIZE
        }

        // ── encoder: inputs_embeds + attention_mask → encoder_hidden_states ──
        val encoderHiddenStates = runEncoder(inputsEmbeds, totalLen)

        // ── decoder: no-cache 自回归生成 ──
        val tokenIds = runDecoderLoop(encoderHiddenStates, totalLen)

        // ── decode token ids → text ──
        // 去掉模型强制输出的前导 <s>（BOS, forced_bos_token_id=0）——它不是内容。
        return decode(tokenIds).removePrefix("<s>").trim()
    }

    /**
     * embed_tokens: input_ids → [L, 768] float arrays。
     */
    private fun runEmbedTokens(tokenIds: LongArray): Array<FloatArray> {
        val session = embedder!!
        val shape = longArrayOf(1L, tokenIds.size.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(tokenIds), shape)
        val inputs = HashMap<String, OnnxTensor>().apply { put("input_ids", inputTensor) }
        val output = session.run(inputs)
        inputTensor.close()

        @Suppress("UNCHECKED_CAST")
        val raw = output[0].value as Array<Array<FloatArray>> // [1, L, 768]
        output.close()

        val l = raw[0].size
        val result = Array(l) { FloatArray(HIDDEN_SIZE) }
        for (i in 0 until l) {
            System.arraycopy(raw[0][i], 0, result[i], 0, HIDDEN_SIZE)
        }
        return result
    }

    /**
     * encoder: inputs_embeds [T, 768] + attention_mask [T] → encoder_hidden_states [T, 768]。
     */
    private fun runEncoder(inputsEmbeds: FloatArray, totalLen: Int): Array<FloatArray> {
        val session = textEnc!!
        val embedShape = longArrayOf(1L, totalLen.toLong(), HIDDEN_SIZE.toLong())
        val maskShape = longArrayOf(1L, totalLen.toLong())
        val attentionMask = LongArray(totalLen) { 1L }

        val embedTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputsEmbeds), embedShape)
        val maskTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attentionMask), maskShape)
        val inputs = HashMap<String, OnnxTensor>().apply {
            put("inputs_embeds", embedTensor)
            put("attention_mask", maskTensor)
        }
        val output = session.run(inputs)
        embedTensor.close()
        maskTensor.close()

        @Suppress("UNCHECKED_CAST")
        val raw = output[0].value as Array<Array<FloatArray>> // [1, T, 768]
        output.close()

        val result = Array(totalLen) { FloatArray(HIDDEN_SIZE) }
        for (i in 0 until totalLen) {
            System.arraycopy(raw[0][i], 0, result[i], 0, HIDDEN_SIZE)
        }
        return result
    }

    // ═══════════════════════════════════════════════════
    //  Decoder 自回归生成（KV cache 优先，no-cache 兜底）
    // ═══════════════════════════════════════════════════

    /**
     * 自回归生成入口：准备共享张量（encoder_hidden_states / mask / dummy past），
     * 按 [decoderMode] 分发到 KV-cache 或 no-cache 循环。
     *
     * @return 生成的 token ids（不含 decoder_start_token_id）
     */
    private fun runDecoderLoop(
        encoderHiddenStates: Array<FloatArray>,
        encoderLen: Int
    ): LongArray {
        // encoder_hidden_states → flat [1, encoderLen, 768]（每步复用）
        val encFlat = FloatArray(encoderLen * HIDDEN_SIZE)
        for (i in encoderHiddenStates.indices) {
            System.arraycopy(encoderHiddenStates[i], 0, encFlat, i * HIDDEN_SIZE, HIDDEN_SIZE)
        }
        val encHsTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(encFlat),
            longArrayOf(1L, encoderLen.toLong(), HIDDEN_SIZE.toLong())
        )
        val encMask = LongArray(encoderLen) { 1L }
        val encMaskTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(encMask),
            longArrayOf(1L, encoderLen.toLong())
        )

        // dummy past_key_values（全零；use_cache_branch=false 分支不读内容，仅满足图输入）
        // 形状须与签名一致：decoder.key/value=[1,12,1,64]，encoder.key/value=[1,12,encoderLen,64]
        val dummyDecBuf = FloatArray(NUM_HEADS * HEAD_DIM) // 1×12×1×64
        val dummyDecTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(dummyDecBuf),
            longArrayOf(1L, NUM_HEADS.toLong(), 1L, HEAD_DIM.toLong())
        )
        val dummyEncBuf = FloatArray(NUM_HEADS * encoderLen * HEAD_DIM) // 1×12×T×64
        val dummyEncTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(dummyEncBuf),
            longArrayOf(1L, NUM_HEADS.toLong(), encoderLen.toLong(), HEAD_DIM.toLong())
        )

        try {
            return when (decoderMode) {
                DecoderMode.CACHE -> runDecoderWithCache(encHsTensor, encMaskTensor, dummyDecTensor, dummyEncTensor)
                DecoderMode.NO_CACHE -> runDecoderNoCache(encHsTensor, encMaskTensor, dummyDecTensor, dummyEncTensor)
            }
        } finally {
            encHsTensor.close()
            encMaskTensor.close()
            dummyDecTensor.close()
            dummyEncTensor.close()
        }
    }

    /** 单 token embed → [1, 1, 768] OnnxTensor（调用方负责 close）。 */
    private fun embedOneToken(tokenId: Long): OnnxTensor {
        val embeds = runEmbedTokens(longArrayOf(tokenId))[0]
        return OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(embeds),
            longArrayOf(1L, 1L, HIDDEN_SIZE.toLong())
        )
    }

    /**
     * KV-cache 自回归生成（修复版 merged decoder，PC 验证与 no-cache 输出一致、~4x 加速）。
     *
     * prefill 用 `use_cache_branch=false` + dummy past 产出首个 token 与 24 份 present KV；
     * 之后每步只 embed 最新 token，`use_cache_branch=true` 增量解码。
     * cross-attn 的 encoder KV 来自 encoder，decode 全程不变（fixed 模型中是 past 直通），
     * 所以每步只请求 logits + 12 份 decoder present，encoder past 复用 prefill 的张量。
     * 张量生命周期参照 OpusMtTranslator：旧 past 在新 present 到手后 close。
     */
    private fun runDecoderWithCache(
        encHsTensor: OnnxTensor,
        encMaskTensor: OnnxTensor,
        dummyDecTensor: OnnxTensor,
        dummyEncTensor: OnnxTensor
    ): LongArray {
        val session = decoder!!
        val generatedIds = mutableListOf<Long>()

        val decPast = arrayOfNulls<OnnxTensor>(NUM_LAYERS * 2)  // 每步滚动替换
        val encPast = arrayOfNulls<OnnxTensor>(NUM_LAYERS * 2)  // prefill 产出后全程复用
        val useCacheFalse = OnnxTensor.createTensor(ortEnv, booleanArrayOf(false))
        val useCacheTrue = OnnxTensor.createTensor(ortEnv, booleanArrayOf(true))

        // 每步只请求 logits + decoder present（encoder present 是 past 直通，不必再物化）
        val stepOutputs = HashSet<String>().apply {
            add("logits")
            for (layer in 0 until NUM_LAYERS) {
                add("present.$layer.decoder.key")
                add("present.$layer.decoder.value")
            }
        }

        try {
            // ── prefill：use_cache_branch=false + 全零 dummy past ──
            var stepTensor = embedOneToken(DECODER_START_TOKEN_ID)
            val prefillInputs = HashMap<String, OnnxTensor>().apply {
                put("encoder_attention_mask", encMaskTensor)
                put("encoder_hidden_states", encHsTensor)
                put("inputs_embeds", stepTensor)
                put("use_cache_branch", useCacheFalse)
                for (layer in 0 until NUM_LAYERS) {
                    put("past_key_values.$layer.decoder.key", dummyDecTensor)
                    put("past_key_values.$layer.decoder.value", dummyDecTensor)
                    put("past_key_values.$layer.encoder.key", dummyEncTensor)
                    put("past_key_values.$layer.encoder.value", dummyEncTensor)
                }
            }
            val prefillOut = session.run(prefillInputs)
            stepTensor.close()
            var nextId = argmaxLast(prefillOut[0] as OnnxTensor)
            (prefillOut[0] as OnnxTensor).close()
            // 取出 24 份 present KV 持有（不能 close prefillOut，否则连带释放）。
            // 输出顺序固定：0=logits，之后每层 decoder.key/value + encoder.key/value。
            // 注意：ORT 1.24 的 Result.get(String) 返回 Optional，这里用位置索引取。
            for (layer in 0 until NUM_LAYERS) {
                val b = 1 + layer * 4
                decPast[layer * 2] = prefillOut[b] as OnnxTensor
                decPast[layer * 2 + 1] = prefillOut[b + 1] as OnnxTensor
                encPast[layer * 2] = prefillOut[b + 2] as OnnxTensor
                encPast[layer * 2 + 1] = prefillOut[b + 3] as OnnxTensor
            }

            // ── decode：use_cache_branch=true 增量解码 ──
            while (nextId != EOS_TOKEN_ID && generatedIds.size < MAX_NEW_TOKENS) {
                generatedIds.add(nextId)
                if (generatedIds.size >= MAX_NEW_TOKENS) break

                stepTensor = embedOneToken(nextId)
                val inputs = HashMap<String, OnnxTensor>().apply {
                    put("encoder_attention_mask", encMaskTensor)
                    put("encoder_hidden_states", encHsTensor)
                    put("inputs_embeds", stepTensor)
                    put("use_cache_branch", useCacheTrue)
                    for (layer in 0 until NUM_LAYERS) {
                        put("past_key_values.$layer.decoder.key", decPast[layer * 2]!!)
                        put("past_key_values.$layer.decoder.value", decPast[layer * 2 + 1]!!)
                        put("past_key_values.$layer.encoder.key", encPast[layer * 2]!!)
                        put("past_key_values.$layer.encoder.value", encPast[layer * 2 + 1]!!)
                    }
                }
                val out = session.run(inputs, stepOutputs)
                stepTensor.close()
                // Result.get(String) 返回 Optional<OnnxValue>（ORT 1.24），取 OnnxTensor 需 .get()
                val logits = out.get("logits").get() as OnnxTensor
                nextId = argmaxLast(logits)
                logits.close()
                for (layer in 0 until NUM_LAYERS) {
                    decPast[layer * 2]?.close()
                    decPast[layer * 2 + 1]?.close()
                    decPast[layer * 2] = out.get("present.$layer.decoder.key").get() as OnnxTensor
                    decPast[layer * 2 + 1] = out.get("present.$layer.decoder.value").get() as OnnxTensor
                }
            }
        } finally {
            useCacheFalse.close()
            useCacheTrue.close()
            for (t in decPast) t?.close()
            for (t in encPast) t?.close()
        }

        return generatedIds.toLongArray()
    }

    /**
     * no-cache 自回归生成（旧版 decoder 模型文件的兜底路径）。
     *
     * 每步把 `[DEC_START, *已生成 token]` 整个序列重新 embed 喂给 merged decoder，
     * `use_cache_branch=false`（旧文件的 KV-cache If 子图有 optimum 导出 bug，不能用），
     * 取 logits 最后一位 argmax。O(n²)，正确但慢。
     */
    private fun runDecoderNoCache(
        encHsTensor: OnnxTensor,
        encMaskTensor: OnnxTensor,
        dummyDecTensor: OnnxTensor,
        dummyEncTensor: OnnxTensor
    ): LongArray {
        val session = decoder!!
        val generatedIds = mutableListOf<Long>()
        val seq = mutableListOf(DECODER_START_TOKEN_ID)

        for (step in 0 until MAX_NEW_TOKENS) {
            // embed 当前完整序列 [DEC_START, *gen]
            val seqArr = seq.toLongArray()
            val decLen = seqArr.size
            val decEmbeds = runEmbedTokens(seqArr) // [decLen, 768]
            val decFlat = FloatArray(decLen * HIDDEN_SIZE)
            for (i in 0 until decLen) {
                System.arraycopy(decEmbeds[i], 0, decFlat, i * HIDDEN_SIZE, HIDDEN_SIZE)
            }
            val decTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(decFlat),
                longArrayOf(1L, decLen.toLong(), HIDDEN_SIZE.toLong())
            )
            val useCacheTensor = OnnxTensor.createTensor(ortEnv, booleanArrayOf(false))

            val inputs = HashMap<String, OnnxTensor>().apply {
                put("encoder_attention_mask", encMaskTensor)
                put("encoder_hidden_states", encHsTensor)
                put("inputs_embeds", decTensor)
                put("use_cache_branch", useCacheTensor)
                for (layer in 0 until NUM_LAYERS) {
                    put("past_key_values.$layer.decoder.key", dummyDecTensor)
                    put("past_key_values.$layer.decoder.value", dummyDecTensor)
                    put("past_key_values.$layer.encoder.key", dummyEncTensor)
                    put("past_key_values.$layer.encoder.value", dummyEncTensor)
                }
            }

            val outputs = session.run(inputs)
            decTensor.close()
            useCacheTensor.close()

            val bestId = argmaxLast(outputs[0] as OnnxTensor, decLen)
            outputs.close()

            if (bestId == EOS_TOKEN_ID) break

            generatedIds.add(bestId)
            seq.add(bestId)
        }

        return generatedIds.toLongArray()
    }

    /** logits [1, seqLen, VOCAB] 最后一位的 argmax（FloatBuffer 直读，避免物化整张）。 */
    private fun argmaxLast(logitsTensor: OnnxTensor, seqLen: Int = 1): Long {
        val fb = logitsTensor.floatBuffer
        val rowOffset = (seqLen - 1) * VOCAB_SIZE
        var bestId = 0L
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until VOCAB_SIZE) {
            val v = fb.get(rowOffset + i)
            if (v > bestScore) {
                bestScore = v
                bestId = i.toLong()
            }
        }
        return bestId
    }
}
