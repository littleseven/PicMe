package com.mamba.picme.domain.tag

/**
 * 打标模型选择器：把用户设置解析为有效的 tagger model key。
 *
 * - 默认 [defaultKey] = `qwen3_vl_2b`（Qwen3-VL-2B-Instruct，质量优先）
 * - 备选 `smolvlm_500m`（SmolVLM-500M）
 * - 空白 / 未识别 → 回退默认
 *
 * LFM2-VL（450M/1.6B）经测试打标效果不佳，已下线。
 */
object TaggerModelSelector {
    /** 默认打标模型：Qwen3-VL-2B-Instruct */
    const val defaultKey = "qwen3_vl_2b"

    private val knownKeys = setOf(
        "qwen3_vl_2b",
        "smolvlm_500m"
    )

    /**
     * @param raw 用户设置里的原始字符串（可能为 null / 空白 / 非法值）
     * @return 合法的打标模型 key；不合法时回退 [defaultKey]
     */
    fun resolve(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed in knownKeys) trimmed else defaultKey
    }
}
