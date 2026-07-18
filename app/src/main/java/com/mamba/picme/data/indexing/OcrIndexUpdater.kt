package com.mamba.picme.data.indexing

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.OcrWordDao
import com.mamba.picme.data.local.entity.OcrWordEntity
import com.mamba.picme.data.local.entity.OcrWordOccurrence

/**
 * OCR 倒排索引更新器
 *
 * 将 OCR 提取的文本分词后写入 [ocr_words] + [ocr_word_occurrences] 倒排索引表。
 *
 * 分词策略：
 * - 中文字符：bigram（双字词）+ 单字，兼顾召回率和精确度
 * - 英文/数字：按空格和标点分词
 * - 归一化：NFC 标准化、小写、去除首尾空白
 */
class OcrIndexUpdater(private val ocrWordDao: OcrWordDao) {

    companion object {
        private const val TAG = "PoLang:OcrIndex"
    }

    /**
     * 更新指定媒体的 OCR 倒排索引。
     * 先清除旧索引，再写入新分词结果。
     *
     * @param mediaId 媒体 ID
     * @param ocrText OCR 提取的原始文本
     */
    suspend fun updateIndex(mediaId: Long, ocrText: String) {
        ocrWordDao.clearWordsForMedia(mediaId)
        if (ocrText.isBlank()) return

        val tokens = tokenize(ocrText)
        if (tokens.isEmpty()) return

        val occurrences = mutableListOf<OcrWordOccurrence>()
        val insertedWordIds = mutableMapOf<String, Long>()

        for ((word, normalized) in tokens) {
            try {
                val wordId = insertedWordIds.getOrPut(normalized) {
                    ocrWordDao.insertWord(
                        OcrWordEntity(word = word, normalizedWord = normalized)
                    )
                }
                occurrences.add(
                    OcrWordOccurrence(wordId = wordId, mediaId = mediaId)
                )
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to insert OCR word '$word': ${e.message}")
            }
        }

        if (occurrences.isNotEmpty()) {
            ocrWordDao.insertOccurrences(occurrences)
            Logger.d(TAG, "OCR index updated: ${tokens.size} tokens for media $mediaId")
        }
    }

    /**
     * 中文 + 英文混合分词
     */
    internal fun tokenize(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val normalized = text.trim().lowercase()

        // 按空白和标点分割成片段
        val segments = normalized.split(Regex("[\\s，。！？、；：\"'（）【】《》\\[\\].,!?;:'\"()\\-_=+]+"))
            .filter { it.isNotBlank() }

        for (segment in segments) {
            val chars = segment.toCharArray()
            var i = 0
            while (i < chars.size) {
                when {
                    // 中文字符 → bigram + 单字
                    chars[i].isCJK() -> {
                        // 单字
                        result.add(chars[i].toString() to chars[i].toString())
                        // bigram
                        if (i + 1 < chars.size && chars[i + 1].isCJK()) {
                            val bigram = "${chars[i]}${chars[i + 1]}"
                            result.add(bigram to bigram)
                        }
                        i++
                    }
                    // 连在一起的字母/数字作为整体
                    chars[i].isLetterOrDigit() -> {
                        val start = i
                        while (i < chars.size && chars[i].isLetterOrDigit()) i++
                        val word = segment.substring(start, i)
                        result.add(word to word)
                    }
                    else -> i++
                }
            }
        }

        return result.distinct()
    }

    private fun Char.isCJK(): Boolean {
        val code = this.code
        return (code in 0x4E00..0x9FFF) ||   // CJK Unified
            (code in 0x3400..0x4DBF) ||        // CJK Extension A
            (code in 0x20000..0x2A6DF) ||      // CJK Extension B
            (code in 0xF900..0xFAFF) ||        // CJK Compatibility
            (code in 0x2F800..0x2FA1F)         // CJK Compatibility Supplement
    }
}
