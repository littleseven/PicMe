package com.mamba.picme.domain.tag

import com.mamba.picme.domain.model.AppLanguage

/**
 * entry 2（预览页「图像理解」描述）的提示词/翻译策略。
 *
 * - Qwen3-VL-2B：中英文均强，按 UI 语言直接出提示词。
 * - Florence-2：中文弱，走 `Florence2Tagger.summary`（英文 caption），中文 UI 时需 en→zh 翻译。
 *
 * 与「打标」（结构化 JSON，[TagGenerationPipeline] Stage-3）刻意不同：这里是自由文本描述。
 * 模型来源统一为 [TaggerModelSelector]/`taggerModelKey`，保证三个入口同模型。
 */
data class ImageDescriptionStrategy(
    val systemPrompt: String,
    val userPrompt: String,
    val needsZhTranslate: Boolean
)

object ImageDescriptionStrategyResolver {

    private const val QWEN_SYSTEM_ZH =
        "你是一个图像理解助手。请用简洁的中文描述这张图片的内容，包括主要对象、场景、颜色和氛围。"
    private const val QWEN_USER_ZH = "请描述这张图片"

    private const val QWEN_SYSTEM_EN =
        "You are an image understanding assistant. Briefly describe this image " +
            "in concise English, covering the main objects, scene, colors and mood."
    private const val QWEN_USER_EN = "Describe this image"

    fun resolve(modelKey: String, lang: AppLanguage): ImageDescriptionStrategy {
        // 西语/法语无独立打标 prompt，回退英文
        val isZh = lang != AppLanguage.ENGLISH && lang != AppLanguage.SPANISH && lang != AppLanguage.FRENCH
        return if (modelKey == TaggerModelSelector.defaultKey) {
            // Florence-2：走 caption，不用提示词；中文 UI 需翻译。
            ImageDescriptionStrategy(
                systemPrompt = "",
                userPrompt = "",
                needsZhTranslate = isZh
            )
        } else {
            // Qwen3-VL-2B：按 UI 语言直出提示词。
            if (isZh) {
                ImageDescriptionStrategy(QWEN_SYSTEM_ZH, QWEN_USER_ZH, needsZhTranslate = false)
            } else {
                ImageDescriptionStrategy(QWEN_SYSTEM_EN, QWEN_USER_EN, needsZhTranslate = false)
            }
        }
    }
}
