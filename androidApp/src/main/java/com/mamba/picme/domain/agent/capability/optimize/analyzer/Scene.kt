package com.mamba.picme.domain.agent.capability.optimize.analyzer

/**
 * AI 一键优化可识别的照片场景
 *
 * 每个场景对应一套本地预设配方，用于一键优化参数推荐。
 */
enum class Scene(
    val label: String
) {
    SELFIE("selfie"),
    PORTRAIT("portrait"),
    GROUP("group"),
    FOOD("food"),
    LANDSCAPE("landscape"),
    LOW_LIGHT("low_light"),
    DOCUMENT("document"),
    GENERAL("general")
}
