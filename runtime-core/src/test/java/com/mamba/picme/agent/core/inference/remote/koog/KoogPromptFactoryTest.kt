package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.params.additionalPropertiesOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护 baseParams 真正经 [polangSystemPrompt] 进入运行时 Prompt。
 *
 * 历史 bug：buildAgent 用 `AIAgent.builder().systemPrompt(String)` 注入 system prompt，而该方法
 * 实现为 `prompt(prompt(config.prompt) { system(it) })`——从初始空 Prompt.Empty 扩展，**永远丢弃 params**。
 * 导致 RemoteModelFactory.createKoogExecutor 返回的 baseParams（DeepSeek thinking=disabled /
 * temperature 钳制 / maxTokens=4096）全部未进请求体。本测试钉死「params 原样落到 Prompt.params」，
 * 与两个 buildAgent 的 `.prompt(polangSystemPrompt(...))` 调用约定共同防止回归。
 */
class KoogPromptFactoryTest {

    private fun deepSeekParams() = LLMParams(
        temperature = 0.7,
        maxTokens = 4096,
        additionalProperties = additionalPropertiesOf("thinking" to mapOf("type" to "disabled")),
    )

    @Test
    fun `baseParams 完整落入 Prompt params`() {
        val params = deepSeekParams()
        val prompt = polangSystemPrompt(id = "polang-chat", systemPrompt = "你是助手", params = params)

        // temperature / maxTokens 必须透传（历史 bug：被 .systemPrompt(String) 丢弃）
        assertEquals(0.7, prompt.params.temperature!!, 1e-9)
        assertEquals(4096, prompt.params.maxTokens)
        // DeepSeek thinking additionalProperties 原样携带（迁移生死点）
        val thinking = prompt.params.additionalProperties?.get("thinking")
        assertNotNull("thinking 必须保留在 additionalProperties", thinking)
        assertTrue("thinking 须为 JsonObject", thinking is JsonObject)
        assertEquals(JsonPrimitive("disabled"), (thinking as JsonObject)["type"])
    }

    @Test
    fun `system message 内容与 prompt id 正确`() {
        val prompt = polangSystemPrompt(id = "polang-react", systemPrompt = "你是相册助手", params = LLMParams())

        assertEquals("polang-react", prompt.id)
        val systemMsg = prompt.messages.firstOrNull { it is Message.System } as? Message.System
        assertNotNull("Prompt 必须包含 System 消息", systemMsg)
        assertEquals("你是相册助手", systemMsg!!.textContent())
    }

    @Test
    fun `kimi temperature 1_0 经 params 透传不被改写`() {
        val params = LLMParams(temperature = 1.0)
        val prompt = polangSystemPrompt(id = "t", systemPrompt = "s", params = params)

        assertEquals(1.0, prompt.params.temperature!!, 1e-9)
    }

    @Test
    fun `空 additionalProperties 时不引入 thinking`() {
        val params = LLMParams(temperature = 0.7)
        val prompt = polangSystemPrompt(id = "t", systemPrompt = "s", params = params)

        assertTrue(prompt.params.additionalProperties == null)
    }
}
