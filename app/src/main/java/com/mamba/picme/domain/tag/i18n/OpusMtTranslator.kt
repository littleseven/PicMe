package com.mamba.picme.domain.tag.i18n

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import com.mamba.picme.sentencepiece.SentencePieceProcessor
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

class OpusMtTranslator(
    private val context: Context,
    private val modelDir: File? = null,
    initialSrcTag: String = ">>zho<<"
) {
    companion object {
        private const val TAG = "OpusMtTranslator"
        private const val ENCODER_MODEL = "encoder_model_quantized.onnx"
        private const val DECODER_MODEL = "decoder_model_quantized.onnx"
        private const val DECODER_PAST_MODEL = "decoder_with_past_model_quantized.onnx"
        private const val MAX_OUTPUT = 32
        private const val NUM_LAYERS = 6
        private val ortEnv by lazy { OrtEnvironment.getEnvironment() }
    }

    private var enc: OrtSession? = null
    private var dec: OrtSession? = null
    private var decPast: OrtSession? = null
    private var srcTok: SentencePieceProcessor? = null
    private var tgtTok: SentencePieceProcessor? = null

    // tokenizer.json 词表映射
    private var spToHf: IntArray? = null
    private var hfToPiece: Array<String?>? = null

    private var padId = 0L; private var eosId = 0L
    private var decStartId = 65000L; private var srcTag = initialSrcTag

    val isInit: Boolean get() = enc != null && dec != null && srcTok != null

    private val dir get() = modelDir ?: com.mamba.picme.data.download.ModelPathConfig.getModelDir(context, "opus-mt-zh-en")

    fun init(): Boolean {
        if (isInit) return true
        try {
            srcTok = SentencePieceProcessor().also { it.loadModel(File(dir, "source.spm").absolutePath) }
            tgtTok = SentencePieceProcessor().also { it.loadModel(File(dir, "target.spm").absolutePath) }
            Log.i(TAG, "SP: srcVocab=${srcTok!!.vocabSize()}, tgtVocab=${tgtTok!!.vocabSize()}")

            // 加载 tokenizer.json 词表映射
            val tjFile = File(dir, "tokenizer.json")
            if (tjFile.exists()) {
                val vocab = JSONObject(tjFile.readText()).getJSONObject("model").getJSONArray("vocab")
                val hfSize = vocab.length()
                val spSize = srcTok!!.vocabSize()
                spToHf = IntArray(spSize) { it }
                hfToPiece = arrayOfNulls(hfSize)
                for (i in 0 until hfSize) {
                    val piece = vocab.getJSONArray(i).getString(0)
                    hfToPiece!![i] = piece
                    val spId = srcTok!!.pieceToId(piece)
                    if (spId in 0 until spSize) spToHf!![spId] = i
                }
                Log.i(TAG, "Vocab mapping: SP($spSize) ↔ HF($hfSize)")
            }

            // 语言标签
            val tcfg = File(dir, "tokenizer_config.json")
            if (tcfg.exists()) {
                val sl = JSONObject(tcfg.readText()).optString("source_lang", "")
                if (sl.isNotEmpty()) srcTag = ">>$sl<<"
            }
            eosId = 0L; padId = 65000L; decStartId = 65000L
            Log.i(TAG, "Config: pad=$padId, eos=$eosId, decStart=$decStartId, srcTag=$srcTag")

            val opt = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1); setInterOpNumThreads(1)
                setMemoryPatternOptimization(true)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            enc = ortEnv.createSession(File(dir, ENCODER_MODEL).absolutePath, opt)
            dec = ortEnv.createSession(File(dir, DECODER_MODEL).absolutePath, opt)
            val pf = File(dir, DECODER_PAST_MODEL)
            if (pf.exists()) decPast = ortEnv.createSession(pf.absolutePath, opt)
            Log.i(TAG, "OpusMtTranslator initialized")
            return true
        } catch (e: Exception) { Log.e(TAG, "Init failed", e); release(); return false }
    }

    fun translate(text: String): String {
        if (!isInit) init()
        if (!isInit || text.isBlank()) return text
        return try { translateInternal(text.trim()) } catch (e: Exception) { Log.w(TAG, "Translation failed", e); text }
    }

    fun release() {
        srcTok?.close(); tgtTok?.close(); srcTok = null; tgtTok = null
        enc?.close(); dec?.close(); decPast?.close()
        enc = null; dec = null; decPast = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun translateInternal(input: String): String {
        val encoder = enc!!; val decoder = dec!!; val decoderPast = decPast
        val src = srcTok!!; val tgt = tgtTok!!

        // SP encode → HF 映射 → encoder input（末尾加 EOS=0）
        val useLangTag = true
        val fullInput = if (useLangTag) "$srcTag $input" else input
        val spIds = src.encode(fullInput)
        val inputIds = spToHf?.let { s2h ->
            LongArray(spIds.size + 1) { i -> if (i < spIds.size) s2h[spIds[i]].toLong() else 0L }
        } ?: (spIds.map { it.toLong() } + 0L).toLongArray()
        Log.d(TAG, "Encoder: '$fullInput' → ${inputIds.size} tokens")

        val inTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong()))
        val attnMask = LongArray(inputIds.size) { 1 }
        val attnTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attnMask), longArrayOf(1, inputIds.size.toLong()))
        val encHidden = (encoder.run(mapOf("input_ids" to inTensor, "attention_mask" to attnTensor))[0] as OnnxTensor)

        // Decoder: KV Cache 优化
        val outIds = mutableListOf(decStartId)
        val decKv: Array<OnnxTensor?> = arrayOfNulls(NUM_LAYERS * 2)
        val encKv: Array<OnnxTensor?> = arrayOfNulls(NUM_LAYERS * 2)
        for (step in 0 until MAX_OUTPUT) {
            val isFirst = step == 0
            val decIn = if (isFirst) LongArray(outIds.size) { outIds[it] } else longArrayOf(outIds.last())

            val dTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(decIn), longArrayOf(1, decIn.size.toLong()))
            val usePast = !isFirst && decoderPast != null
            val dInputs = java.util.HashMap<String, OnnxTensor>().apply {
                put("input_ids", dTensor); put("encoder_attention_mask", attnTensor)
                if (!usePast) put("encoder_hidden_states", encHidden)
            }
            // KV cache
            if (usePast) {
                for (layer in 0 until NUM_LAYERS) {
                    val dk = decKv[layer * 2]; val dv = decKv[layer * 2 + 1]
                    val ek = encKv[layer * 2]; val ev = encKv[layer * 2 + 1]
                    if (dk != null && dv != null && ek != null && ev != null) {
                        dInputs["past_key_values.$layer.decoder.key"] = dk
                        dInputs["past_key_values.$layer.decoder.value"] = dv
                        dInputs["past_key_values.$layer.encoder.key"] = ek
                        dInputs["past_key_values.$layer.encoder.value"] = ev
                    }
                }
            }
            val activeDec = if (usePast) decoderPast else decoder
            val dOut = activeDec.run(dInputs)
            val rawLogits = (dOut[0].value as Array<Array<FloatArray>>)[0][decIn.size - 1]

            // 重复惩罚
            val logits = rawLogits.copyOf()
            val gen = outIds.drop(1)
            for (id in gen) if (id.toInt() in logits.indices) logits[id.toInt()] *= 0.5f
            if (gen.size >= 2) {
                val last = gen.last().toInt()
                if (gen[gen.size - 2].toInt() == last) logits[last] *= 0.2f
            }

            // 前 3 步禁止 EOS（短输入模型不自信，EOS 容易误选）
            val eosIdx = eosId.toInt()
            val searchRange = if (step < 3 && eosIdx in logits.indices) {
                logits[eosIdx] = -Float.MAX_VALUE; logits.indices
            } else logits.indices
            val next = searchRange.maxByOrNull { logits[it] } ?: eosIdx
            if (step < 3) {
                val t3 = rawLogits.indices.sortedByDescending { rawLogits[it] }.take(3)
                    .joinToString { "${it}=${rawLogits[it].toInt()}" }
                Log.d(TAG, "Step $step: token=$next, top3=[$t3]")
            }

            // KV Cache 更新
            if (dOut.size() > 1 && isFirst) {
                for (layer in 0 until NUM_LAYERS) {
                    val b = 1 + layer * 4
                    if (b + 3 < dOut.size()) {
                        decKv[layer * 2] = dOut[b] as OnnxTensor
                        decKv[layer * 2 + 1] = dOut[b + 1] as OnnxTensor
                        encKv[layer * 2] = dOut[b + 2] as OnnxTensor
                        encKv[layer * 2 + 1] = dOut[b + 3] as OnnxTensor
                    }
                }
            } else if (dOut.size() > 1 && usePast) {
                for (layer in 0 until NUM_LAYERS) {
                    decKv[layer * 2]?.close()
                    decKv[layer * 2 + 1]?.close()
                    val b = 1 + layer * 2
                    if (b + 1 < dOut.size()) {
                        decKv[layer * 2] = dOut[b] as OnnxTensor
                        decKv[layer * 2 + 1] = dOut[b + 1] as OnnxTensor
                    }
                }
            }
            dOut[0].close(); dTensor.close()
            if (next.toLong() == eosId) break
            outIds.add(next.toLong())
        }

        for (i in decKv.indices) { decKv[i]?.close(); encKv[i]?.close() }
        encHidden.close(); attnTensor.close(); inTensor.close()

        // 只过滤 EOS + PAD，让 decoder 自然输出，后续用正则清理
        val resultIds = outIds.drop(1).filter { it != padId && it != eosId }
        if (resultIds.isEmpty()) return ""
        val h2p = hfToPiece
        val spOut = if (h2p != null) {
            resultIds.mapNotNull { hfId ->
                val piece = h2p.getOrNull(hfId.toInt()) ?: return@mapNotNull null
                if (piece.length == 1 && piece[0] in setOf('▁', '♪', '⁇')) null  // 已知噪音字符
                else {
                    val spId = tgt.pieceToId(piece)
                    if (spId >= 0) spId else null
                }
            }
        } else resultIds.filter { it < tgt.vocabSize() }.map { it.toInt() }
        var result = if (spOut.isEmpty()) "" else tgt.decode(spOut.toIntArray())
        // 清理 SentencePiece 空格标记 → 普通空格 + 去前导非字母字符
        result = result.replace('▁', ' ').replace(Regex("^[^a-zA-Z0-9]*"), "").trim()
        return result
    }
}
