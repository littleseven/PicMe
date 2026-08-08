package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams

/**
 * 将 system prompt 与基础推理参数 [params]（temperature / maxTokens / DeepSeek `thinking.type=disabled`
 * 等 additionalProperties）一次性烘焙进 Koog [Prompt]，供 AIAgent 的 `.prompt(...)` 注入。
 *
 * ⚠️ **必须用 [prompt] DSL 的 `params` 形参注入，不能用 `AIAgent.builder().systemPrompt(String)`**：
 * 后者实现为 `prompt(prompt(config.prompt) { system(it) })`，从初始空 `Prompt.Empty` 扩展，
 * **永远丢弃 params**——曾导致 RemoteModelFactory.createKoogExecutor 返回的 baseParams 沦为死代码
 * （DeepSeek thinking / temperature / maxTokens 均未进请求体，见 buildAgent 调用点注释）。
 *
 * 本函数是「params 真正进入运行时 Prompt」的唯一组装点，由 KoogPromptFactoryTest 钉死。
 */
internal fun polangSystemPrompt(
    id: String,
    systemPrompt: String,
    params: LLMParams,
): Prompt = prompt(id = id, params = params) {
    system(systemPrompt)
}
