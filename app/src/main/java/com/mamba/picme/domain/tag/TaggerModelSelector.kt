package com.mamba.picme.domain.tag

/**
 * 打标模型选择器：把用户设置解析为有效的 tagger model key。
 *
 * - 默认 [defaultKey] = `smolvlm_256m`
 * - 空白 / 未识别 → 回退默认
 * - 白名单内 key 原样返回
 *
 * 新增打标模型时在 [knownKeys] 注册。
 */
object TaggerModelSelector {
    const val defaultKey = "smolvlm_256m"

    private val knownKeys = setOf(
        "smolvlm_256m",
        "smolvlm_500m",
        "qwen3_vl_2b",
        "lfm2_vl_450m"
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
