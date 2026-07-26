package com.mamba.picme.domain.tag.i18n

import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.tag.UnifiedTagResult

/**
 * 英文统一标签 → 中文统一标签（离线汉化）。
 *
 * 打标恒为 SmolVLM 英文输出（[UnifiedTagResult]），本类把它派生为中文版，写入 `labels_zh`，
 * 使中文 UI 展示与中文搜索直接命中（无需运行时翻译）。
 *
 * - **主路径**：[ControlledVocab] 平行数组（scene/sceneEn … 同下标=同概念）。它本就是双语，
 *   canonical 英文命中即 100% 有对应中文（验证：720 canonical 对，常见照片标签覆盖 ~90%）。
 * - **兜底**：[BilingualVocab.enToZh]（free-form 翻译，补 canonical 之外）。
 * - **未命中**：保留英文原词（中文搜索该词落空，但英文搜索仍命中 `labels_en`）。
 * - **summary**：走注入的 [translateSummary]（en→zh MT；缺省 identity，summary_zh 留英文）。
 * - **face**：语言无关，原样复制。
 *
 * @param translateSummary en→zh 整句翻译（如 OpusMtTranslator(en→zh)）；默认不翻译。
 */
class LabelSinicizer(
    private val controlledVocab: ControlledVocab,
    private val bilingualVocab: BilingualVocab = BilingualVocab.empty(),
    private val translateSummary: (String) -> String = { it }
) {

    private val enToZh: Map<String, String> by lazy { buildEnToZh() }

    private fun buildEnToZh(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        // ControlledVocab 平行数组（canonical，优先）
        val parallelPairs: List<Pair<List<String>, List<String>>> = listOf(
            controlledVocab.scene to controlledVocab.sceneEn,
            controlledVocab.activity to controlledVocab.activityEn,
            controlledVocab.objects to controlledVocab.objectsEn,
            controlledVocab.atmosphere to controlledVocab.atmosphereEn,
            controlledVocab.people to controlledVocab.peopleEn,
            controlledVocab.clothing to controlledVocab.clothingEn,
            controlledVocab.animal to controlledVocab.animalEn,
            controlledVocab.foodDrink to controlledVocab.foodDrinkEn,
            controlledVocab.architecture to controlledVocab.architectureEn,
            controlledVocab.nature to controlledVocab.natureEn,
            controlledVocab.transport to controlledVocab.transportEn,
            controlledVocab.style to controlledVocab.styleEn
        )
        for ((zhList, enList) in parallelPairs) {
            for ((zh, en) in zhList.zip(enList)) {
                val key = en.trim().lowercase()
                if (key.isNotEmpty()) map.putIfAbsent(key, zh)
            }
        }
        // BilingualVocab.enToZh（free-form 兜底，不覆盖 canonical）
        for ((en, zh) in bilingualVocab.enToZh) {
            val key = en.trim().lowercase()
            if (key.isNotEmpty()) map.putIfAbsent(key, zh)
        }
        return map
    }

    private fun toZh(en: String): String {
        if (en.isBlank()) return en
        return enToZh[en.trim().lowercase()] ?: en
    }

    /**
     * 英文统一标签 → 中文统一标签。face 原样复制，summary 走注入翻译（空则不译）。
     */
    fun sinicize(en: UnifiedTagResult): UnifiedTagResult = UnifiedTagResult(
        face = en.face,
        scene = toZh(en.scene),
        activity = toZh(en.activity),
        objects = en.objects.map { toZh(it) },
        tags = en.tags.map { toZh(it) },
        summary = if (en.summary.isBlank()) en.summary else translateSummary(en.summary)
    )
}
