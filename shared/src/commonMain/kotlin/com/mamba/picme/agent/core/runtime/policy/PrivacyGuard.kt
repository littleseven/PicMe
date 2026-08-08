package com.mamba.picme.agent.core.runtime.policy

import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel

/**
 * 隐私级别枚举
 *
 * 用于对用户输入进行隐私分级，决定推理路由策略。
 */
enum class PrivacyLevel {
    /**
     * 公开信息：普通相机控制指令，无隐私风险
     */
    PUBLIC,

    /**
     * 敏感信息：包含用户照片、人脸相关描述
     */
    SENSITIVE,

    /**
     * 受限信息：包含精确坐标、人脸数据等必须本地处理的内容
     */
    RESTRICTED
}

/**
 * 隐私守卫
 *
 * 负责运行时隐私策略检查和输入内容隐私分级。
 * 端侧文本 LLM 移除后，文本推理全远程，本类仅保留输入隐私分级能力。
 */
class PrivacyGuard(
    private var privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    private var agentMode: AiAgentMode = AiAgentMode.REMOTE
) {

    /**
     * 对用户输入进行隐私分级
     *
     * 分级规则：
     * - RESTRICTED：全串匹配坐标模式（如 "100,200"）或包含精确人脸数据关键词
     * - SENSITIVE：包含关键词如 "我的照片"、"人脸坐标"、"OCR结果"
     * - PUBLIC：其他所有内容
     *
     * @param input 用户输入文本
     * @return 对应的隐私级别
     */
    fun classify(input: String): PrivacyLevel {
        val trimmed = input.trim()

        // RESTRICTED: 坐标模式（数字,数字）或精确人脸数据
        if (COORDINATE_PATTERN.matches(trimmed) ||
            RESTRICTED_KEYWORDS.any { keyword -> trimmed.contains(keyword) }
        ) {
            return PrivacyLevel.RESTRICTED
        }

        // SENSITIVE: 包含敏感关键词
        if (SENSITIVE_KEYWORDS.any { keyword -> trimmed.contains(keyword) }) {
            return PrivacyLevel.SENSITIVE
        }

        return PrivacyLevel.PUBLIC
    }

    /**
     * 检查是否允许远程 API 调用
     */
    fun isRemoteAllowed(): Boolean {
        return privacyLevel == AiAgentPrivacyLevel.PERMISSIVE && agentMode == AiAgentMode.REMOTE
    }

    /**
     * 更新隐私配置
     */
    fun updateConfig(level: AiAgentPrivacyLevel, mode: AiAgentMode) {
        this.privacyLevel = level
        this.agentMode = mode
    }

    companion object {
        // 坐标模式：数字,数字（如 "100,200"）
        private val COORDINATE_PATTERN = Regex("^\\d+,\\d+")

        // RESTRICTED 级别关键词
        private val RESTRICTED_KEYWORDS = listOf(
            "坐标", "landmark", "bounding_box", "bbox",
            "face_data", "face_data", "关键点"
        )

        // SENSITIVE 级别关键词
        private val SENSITIVE_KEYWORDS = listOf(
            "我的照片", "人脸坐标", "OCR结果", "ocr结果",
            "识别结果", "检测结果", "人脸数据", "我的图片"
        )
    }
}
