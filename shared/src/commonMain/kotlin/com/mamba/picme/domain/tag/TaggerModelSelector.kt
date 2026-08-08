package com.mamba.picme.domain.tag

/**
 * 打标模型选择器：Florence-2 为默认首选（ONNX INT8，~260MB，轻量稳定），
 * 未下载时回退 Qwen3-VL-2B；手动指定（florence2_base / qwen3_vl_2b）覆盖首选。
 *
 * 语言路由方案已废弃——打标恒英文（见 TagGenerationPipeline.targetLanguage），
 * 模型不再按 UI 语言选；中文由 LabelSinicizer 离线派生到 labelsZh。
 *
 * SmolVLM-500M、LFM2-VL（450M/1.6B）经评估打标效果不佳/被替代，已下线。
 */
object TaggerModelSelector {
    /** 默认打标模型：Florence-2-base。 */
    const val defaultKey = "florence2_base"

    /** 首选打标模型（同默认）：Florence-2。保留常量以兼容既有调用方。 */
    const val preferredKey = "florence2_base"

    /** 自动（无手动偏好）——DataStore 默认值，解析为首选 Florence-2。 */
    const val AUTO = "auto"

    private val knownKeys = setOf(defaultKey, "qwen3_vl_2b")

    /** 兼容入口：无可用性信息时，假定全部可用。 */
    fun resolve(raw: String?): String = resolve(raw) { true }

    /**
     * 解析最终使用的 tagger 模型 key（首选 + 下载感知兜底）。
     *
     * - [raw] 为白名单内显式模型 → 用它（手动覆盖）
     * - [raw] 为 [AUTO] / 空白 / 未识别 → 首选 [preferredKey]（Florence-2）
     * - 选中的模型 [isAvailable]=false → 回退另一个已知可用模型；全不可用 → [defaultKey]
     */
    fun resolve(raw: String?, isAvailable: (String) -> Boolean = { true }): String {
        val explicit = raw?.trim().orEmpty()
        val desired = if (explicit in knownKeys) explicit else preferredKey
        if (isAvailable(desired)) return desired
        val fallback = knownKeys.firstOrNull { candidate -> candidate != desired && isAvailable(candidate) }
        return fallback ?: defaultKey
    }
}
