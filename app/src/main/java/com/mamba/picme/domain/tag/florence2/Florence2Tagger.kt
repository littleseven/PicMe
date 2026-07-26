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
 * Florence-2-base (231M) ONNX ORT 打标器（INT8 量化，no-cache 全量重算管道）。
 *
 * 四模型管道（独立于 OpusMtTranslator，不继承）：
 * 1. vision_encoder (INT8): Bitmap → 768×768 pixel_values → image_features [577, 768]
 * 2. embed_tokens   (INT8): task prompt token ids → text_embeds [L, 768]
 * 3. encoder        (INT8): [image_features ⊕ text_embeds] + attention_mask → encoder_hidden_states [T, 768]
 * 4. decoder (merged, INT8): encoder_hidden_states + 自回归生成 token ids
 *
 * ⚠️ **decoder 用 no-cache 全量重算模式**：每步 `use_cache_branch=false`，把
 * `[DEC_START, *已生成]` 整个序列重新 embed 喂入，取 logits 最后一位 argmax。
 *
 * 原因：merged decoder 的 `use_cache_branch=true` 缓存分支在 ORT 上有 cross-attn
 * `MatMul dim0` bug（INT8/q4f16 都坏，PC 1.23 + Android 待验）。no-cache 路径已验证
 * 与 PyTorch 输出一致。O(n²) 但 231M 小模型 + 几十个 token 可接受。
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

    private var visionEnc: OrtSession? = null
    private var textEnc: OrtSession? = null
    private var decoder: OrtSession? = null
    private var embedder: OrtSession? = null

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
            decoder = ortEnv.createSession(File(modelDir, DECODER).absolutePath, opt)
            embedder = ortEnv.createSession(File(modelDir, EMBED_TOKENS).absolutePath, opt)
            Florence2Tokenizer.load(modelDir)
            initialized.set(true)
            Log.i(TAG, "Florence2Tagger initialized (4 INT8 sessions, no-cache decoder)")
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
    //  Decoder 自回归生成（no-cache 全量重算）
    // ═══════════════════════════════════════════════════

    /**
     * no-cache 自回归生成。
     *
     * 每步把 `[DEC_START, *已生成 token]` 整个序列重新 embed 喂给 merged decoder，
     * `use_cache_branch=false`（避开坏掉的 KV-cache If 子图），取 logits 最后一位 argmax。
     * encoder_hidden_states / attention_mask / 24 个 dummy past_key_values 每步复用同一份。
     *
     * @return 生成的 token ids（不含 decoder_start_token_id）
     */
    private fun runDecoderLoop(
        encoderHiddenStates: Array<FloatArray>,
        encoderLen: Int
    ): LongArray {
        val session = decoder!!
        val generatedIds = mutableListOf<Long>()

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

        val seq = mutableListOf(DECODER_START_TOKEN_ID)

        try {
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

                // logits [1, decLen, VOCAB] → 只读最后一位（FloatBuffer 直读，避免物化整张）
                @Suppress("UNCHECKED_CAST")
                val logitsTensor = outputs[0] as OnnxTensor
                val fb = logitsTensor.floatBuffer
                val rowOffset = (decLen - 1) * VOCAB_SIZE
                var bestId = 0L
                var bestScore = Float.NEGATIVE_INFINITY
                for (i in 0 until VOCAB_SIZE) {
                    val v = fb.get(rowOffset + i)
                    if (v > bestScore) {
                        bestScore = v
                        bestId = i.toLong()
                    }
                }
                outputs.close()

                if (bestId == EOS_TOKEN_ID) break

                generatedIds.add(bestId)
                seq.add(bestId)
            }
        } finally {
            encHsTensor.close()
            encMaskTensor.close()
            dummyDecTensor.close()
            dummyEncTensor.close()
        }

        return generatedIds.toLongArray()
    }
}
