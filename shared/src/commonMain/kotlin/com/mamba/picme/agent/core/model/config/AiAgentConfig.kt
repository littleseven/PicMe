package com.mamba.picme.agent.core.model.config

/**
 * AI Agent 推理模式
 *
 * 端侧仅保留 VLM 打标/图像理解（LocalLlmEngine.imageInference，TAG 生成 Pass 3），
 * 文本指令与聊天推理一律走远程 API（标准 OpenAI tool_calls，ADR-005）。
 *
 * @see AiAgentPrivacyLevel
 */
enum class AiAgentMode {
    OFF,     // 完全关闭 Agent
    REMOTE,  // 远程 API 推理（默认，支持 OpenAI/Claude 协议）
    FEISHU   // 飞书远程控制专用模式（ReAct 循环，应用内 UI 自动化）
}

/**
 * AI Agent 隐私级别
 * 控制是否允许远程 API 调用
 *
 * 在远程推理优先策略下，PERMISSIVE 为实际默认行为。
 * STRICT 保留作为极端隐私场景的选项。
 */
enum class AiAgentPrivacyLevel {
    STRICT,      // 禁止任何远程调用
    PERMISSIVE   // 允许远程（需用户显式确认）
}
