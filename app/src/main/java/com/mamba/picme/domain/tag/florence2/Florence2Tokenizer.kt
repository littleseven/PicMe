package com.mamba.picme.domain.tag.florence2

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Florence-2 BART BPE tokenizer（仅解码方向）。
 *
 * Task prompt 的 token ids 已在 [Florence2Tagger] 中硬编码（预计算），
 * 这里只做输出解码：token ids → 文本字符串。
 *
 * 从 vocab.json 加载 id→token 映射，用 BPE 规则合并子词（Ġ→空格）。
 */
object Florence2Tokenizer {

    private const val TAG = "PoLang:Florence2Tok"

    /** id → token 字符串（从 vocab.json + special_tokens_map 加载） */
    private var idToToken: Map<Int, String> = emptyMap()

    private var loaded = false

    /**
     * 从模型目录加载 vocab.json。
     */
    fun load(modelDir: File) {
        if (loaded) return
        try {
            val vocabJson = JSONObject(File(modelDir, "vocab.json").readText())
            // vocab.json: {token: id}，反转成 {id: token}
            val map = mutableMapOf<Int, String>()
            for (key in vocabJson.keys()) {
                val id = vocabJson.getInt(key)
                map[id] = key
            }

            // 加载 added_tokens（special tokens，如 <od>, <loc_0> 等）
            val addedFile = File(modelDir, "added_tokens.json")
            if (addedFile.exists()) {
                val added = JSONObject(addedFile.readText())
                for (key in added.keys()) {
                    map[added.getInt(key)] = key
                }
            }

            idToToken = map
            loaded = true
            Log.i(TAG, "Vocab loaded: ${idToToken.size} tokens")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab", e)
        }
    }

    /**
     * 将 token ids 解码为文本（BPE detokenize）。
     *
     * BART BPE 子词用 `Ġ` 表示词间空格，`Ċ` 表示换行。
     * 特殊 token（<loc_*>、</od> 等）保留原样（parser 会处理）。
     */
    fun decode(tokenIds: LongArray): String {
        if (!loaded) {
            Log.w(TAG, "Vocab not loaded, returning raw ids")
            return tokenIds.joinToString(",")
        }
        val sb = StringBuilder()
        for (id in tokenIds) {
            val token = idToToken[id.toInt()] ?: continue
            when {
                token.startsWith("<") && token.endsWith(">") -> {
                    // 特殊 token：保留原样（parser 处理）
                    sb.append(token)
                }
                else -> {
                    // BPE 子词：Ġ→空格，Ċ→换行
                    val decoded = token
                        .replace("Ġ", " ")
                        .replace("Ċ", "\n")
                        .replace("▁", " ") // 备用
                    sb.append(decoded)
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * 占位方法（task prompt 的 token ids 已在 Tagger 中硬编码）。
     */
    fun tokenizeTask(task: String): LongArray {
        throw UnsupportedOperationException("Task token ids are hardcoded in Florence2Tagger")
    }
}
