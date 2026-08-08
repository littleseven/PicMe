package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.params.additionalPropertiesOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Phase 0 PoC：DeepSeek `thinking.type=disabled` 注入路径（:agent-core → Koog 迁移生死点之一）。
 *
 * flatten 到请求体 JSON 顶层已**源码级证实**：Koog 的
 * `OpenAIChatCompletionRequestSerializer = AdditionalPropertiesFlatteningSerializer<OpenAIChatCompletionRequest>`
 * 会把 `LLMParams.additionalProperties` 的每个键展开到请求体根。
 * （`OpenAIChatCompletionRequest(Serializer)` 为 Koog internal，跨模块不可直引，
 *   故本测试只守护公开 API；wire 级实跑验证留待 Phase 4 真机 + Ktor Logging 抓包。）
 *
 * 本测试守护：Koog 公开 API（LLMParams / additionalPropertiesOf）在本工具链(Kotlin 2.3.10)可用，
 * 且 DeepSeek 配方构造的 additionalProperties 结构正确。
 */
class DeepSeekThinkingParamsTest {

    @Test
    fun `DeepSeek thinking disabled 注入 additionalProperties 结构正确`() {
        val params = LLMParams(
            temperature = 0.7,
            additionalProperties = additionalPropertiesOf(
                "thinking" to mapOf("type" to "disabled")
            )
        )

        val thinking = params.additionalProperties?.get("thinking")
        assertTrue(thinking is JsonObject, "thinking 必须存在于 additionalProperties")
        assertEquals(
            JsonPrimitive("disabled"),
            (thinking as JsonObject)["type"]
        )
    }

    @Test
    fun `additionalProperties 为空时不注入 thinking`() {
        val params = LLMParams(temperature = 0.7)
        assertTrue(params.additionalProperties == null)
    }
}
