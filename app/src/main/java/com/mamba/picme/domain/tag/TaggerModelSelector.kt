package com.mamba.picme.domain.tag

/**
 * 打标模型选择器：恒英文打标下，首选 SmolVLM-500M（英文原生 + 轻量省电），
 * 未下载回退 Qwen3-VL-2B；手动指定（qwen3_vl_2b / smolvlm_500m）覆盖首选。
 *
 * 语言路由方案已废弃——打标恒英文（见 TagGenerationPipeline.targetLanguage），
 * 模型不再按 UI 语言选；中文由 LabelSinicizer 离线派生到 labelsZh。详见 spec §6。
 *
 * LFM2-VL（450M/1.6B）经测试打标效果不佳，已下线。
 */
object TaggerModelSelector {
    /** 回退默认（全不可用时）：Qwen3-VL-2B-Instruct */
    const val defaultKey = "qwen3_vl_2b"

    /** 首选打标模型：SmolVLM-500M（英文原生 + 省电） */
    const val preferredKey = "smolvlm_500m"

    /** 自动（无手动偏好）——DataStore 默认值，解析为首选 SmolVLM。 */
    const val AUTO = "auto"

    private val knownKeys = setOf(defaultKey, preferredKey, "florence2_base")

    /** 兼容入口：无可用性信息时，假定全部可用。 */
    fun resolve(raw: String?): String = resolve(raw) { true }

    /**
     * 解析最终使用的 tagger 模型 key（首选 + 下载感知兜底）。
     *
     * - [raw] 为白名单内显式模型 → 用它（手动覆盖）
     * - [raw] 为 [AUTO] / 空白 / 未识别 → 首选 [preferredKey]（SmolVLM）
     * - 选中的模型 [isAvailable]=false → 回退另一个已知可用模型；全不可用 → [defaultKey]
     */
    fun resolve(raw: String?, isAvailable: (String) -> Boolean = { true }): String {
        val explicit = raw?.trim().orEmpty()
        val desired = if (explicit in knownKeys) explicit else preferredKey
        if (isAvailable(desired)) return desired
        val fallback = knownKeys.firstOrNull { it != desired && isAvailable(it) }
        return fallback ?: defaultKey
    }
}
