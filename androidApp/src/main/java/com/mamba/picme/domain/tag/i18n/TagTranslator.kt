package com.mamba.picme.domain.tag.i18n

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.tag.ControlledVocab

/**
 * TAG 运行时翻译器
 *
 * 承担两类职责：
 * 1. **展示翻译**：把数据库中存储的中文 canonical TAG 翻译成当前界面语言。
 * 2. **搜索扩展**：把用户输入的查询词扩展为数据库中可能存在的多语言形式，
 *    使英文用户搜 "cat" 也能命中中文标签 "猫"。
 *
 * 分层翻译策略（搜索扩展路径，按优先级）：
 * 1. [BilingualVocab] 词表精确匹配 → 英文同义词链（零耗时）
 * 2. [ControlledVocab] 中文同义词扩展（零耗时）
 * 3. [OpusMtTranslator] 模型翻译回退（~50ms，词表未命中时）
 * 4. 保留原词兜底（以上均不可用时）
 *
 * 实现上完全依赖本地资源，不调用任何云端服务，符合 [PRIVACY] 红线。
 */
class TagTranslator(
    private val vocab: BilingualVocab,
    private val mtTranslator: OpusMtTranslator? = null,
    private val controlledVocab: ControlledVocab? = null
) {
    companion object {
        private const val TAG = "PoLang:TagTranslator"
    }

    /**
     * 把中文 canonical TAG 翻译成目标语言，用于 UI 展示。
     *
     * @param chineseTag 数据库中存储的中文标签（如 "猫"）
     * @param lang 当前用户界面语言
     * @return 翻译后的标签；未命中词表时回退原中文
     */
    fun display(chineseTag: String, lang: AppLanguage): String {
        // 西语/法语无独立标签词表，展示回退英文
        if (lang != AppLanguage.ENGLISH && lang != AppLanguage.SPANISH && lang != AppLanguage.FRENCH) {
            return chineseTag
        }
        return vocab.zhToEn[chineseTag] ?: chineseTag
    }

    /**
     * 把用户输入的查询词扩展为一组候选词，用于跨语言搜索。
     *
     * 英文用户输入 "cat" 时，会扩展为 ["cat", "猫"]；
     * 中文用户输入 "猫" 时，会扩展为 ["猫", "cat"]（词表命中）
     * 或 ["猫", "<OPUS-MT翻译结果>"]（词表未命中时模型回退）。
     *
     * 中文同义词扩展：
     * - 输入是 synonym（如 "美女"）→ 扩展 canonical "女性"
     * - 输入是 canonical（如 "女性"）→ 扩展所有 synonyms ["美女", "大美女"]
     *
     * @param query 用户输入的原始查询词
     * @param uiLang 当前界面语言
     * @return 需要去重的候选词集合
     */
    fun expandForSearch(query: String, uiLang: AppLanguage): Set<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptySet()

        val result = linkedSetOf(normalized)

        if (uiLang == AppLanguage.ENGLISH || uiLang == AppLanguage.SPANISH || uiLang == AppLanguage.FRENCH) {
            // 英文标准词 -> 中文 canonical（西语/法语无独立词表，回退英文同义词链）
            vocab.enToZh[normalized]?.let { result += it }
            // 英文同义词 -> 英文标准词 -> 中文 canonical
            vocab.enSynonyms[normalized]?.let { standardEn ->
                result += standardEn
                vocab.enToZh[standardEn]?.let { result += it }
            }
        } else {
            val rawQuery = query.trim()

            // 1. 中文 canonical -> 英文标准词（兼容未来存储的英文标签）
            val zhToEnHit = vocab.zhToEn[rawQuery]
            if (zhToEnHit != null) {
                result += zhToEnHit.lowercase()
            }

            // 2. 中文同义词扩展（ControlledVocab，零耗时）
            expandChineseSynonyms(rawQuery, result)

            // 3. 无词表命中且无同义词 → 尝试 OPUS-MT 模型翻译
            if (zhToEnHit == null && result.size == 1) {
                val mtResult = tryMtTranslate(rawQuery)
                if (mtResult != null) {
                    result += mtResult.lowercase()
                    Logger.d(TAG, "MT fallback: '$query' -> '$mtResult'")
                }
            }
        }

        return result
    }

    /**
     * 中文同义词双向扩展。
     *
     * - 输入是 synonym（如 "美女"）→ 扩展为 canonical（"女性"）
     * - 输入是 canonical（如 "女性"）→ 扩展为所有 synonyms（"美女", "大美女"）
     *
     * 这确保了无论标签存储的是 canonical 还是 synonym 形式，都能命中。
     */
    private fun expandChineseSynonyms(rawQuery: String, result: LinkedHashSet<String>) {
        val cv = controlledVocab ?: return

        // 方向1: 输入是 synonym → 加入 canonical
        cv.synonyms[rawQuery]?.let { canonical ->
            if (canonical != rawQuery) {
                result += canonical
                Logger.d(TAG, "Synonym expand: '$rawQuery' -> canonical '$canonical'")
            }
        }

        // 方向2: 输入是 canonical → 加入所有 synonyms
        cv.reverseSynonyms[rawQuery]?.let { synonyms ->
            for (syn in synonyms) {
                if (syn != rawQuery) {
                    result += syn
                }
            }
            if (synonyms.isNotEmpty()) {
                Logger.d(TAG, "Reverse synonym expand: '$rawQuery' -> $synonyms")
            }
        }
    }

    /**
     * 尝试使用 OPUS-MT 模型将中文查询翻译为英文。
     *
     * @return 翻译结果；模型不可用或翻译失败时返回 null
     */
    private fun tryMtTranslate(chineseText: String): String? {
        val translator = mtTranslator ?: return null
        return try {
            val result = translator.translate(chineseText)
            // 质量校验：过滤与输入相同、空白、异常短的结果
            if (result.isBlank() || result == chineseText || result.length < 2) {
                Logger.w(TAG, "MT result rejected for '$chineseText': '$result'")
                return null
            }
            result
        } catch (e: Exception) {
            Logger.w(TAG, "MT translation failed for '$chineseText'", e)
            null
        }
    }

    /**
     * 批量展示翻译，常用于把 labels 列表整体映射后显示。
     */
    fun displayAll(tags: List<String>, lang: AppLanguage): List<String> =
        tags.map { display(it, lang) }
}
