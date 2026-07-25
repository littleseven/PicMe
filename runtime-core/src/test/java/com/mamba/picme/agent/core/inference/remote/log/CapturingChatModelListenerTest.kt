package com.mamba.picme.agent.core.inference.remote.log

import com.mamba.data.message.AiMessage
import com.mamba.data.message.SystemMessage
import com.mamba.data.message.UserMessage
import com.mamba.model.ModelProvider
import com.mamba.model.chat.listener.ChatModelErrorContext
import com.mamba.model.chat.listener.ChatModelRequestContext
import com.mamba.model.chat.listener.ChatModelResponseContext
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.chat.response.ChatResponse
import com.mamba.model.output.TokenUsage
import com.mamba.tool.ToolExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturingChatModelListenerTest {

    @Test
    fun `onResponse captures structured request and response fields`() {
        val recorded = mutableListOf<LlmCallRecord>()
        val listener = CapturingChatModelListener("react", { recorded += it })

        val attrs = java.util.HashMap<Any, Any>()
        val request = ChatRequest.builder()
            .messages(UserMessage.from("hello"))
            .modelName("test-model")
            .build()

        listener.onRequest(ChatModelRequestContext(request, ModelProvider.OTHER, attrs))

        val response = ChatResponse.builder()
            .aiMessage(AiMessage.from("ok"))
            .tokenUsage(TokenUsage(10, 5, 15))
            .build()
        listener.onResponse(ChatModelResponseContext(response, request, ModelProvider.OTHER, attrs))

        assertEquals(1, recorded.size)
        val r = recorded.first()
        assertEquals("react", r.source)
        assertEquals("test-model", r.model)
        assertTrue(r.success)
        assertEquals(10, r.promptTokens)
        assertEquals(5, r.completionTokens)
        assertEquals(15, r.totalTokens)
        assertNotNull(r.latencyMs)
        assertTrue(r.requestJson.contains("test-model"))
        assertTrue(r.requestJson.contains("hello"))
        assertTrue(r.responseJson!!.contains("ok"))
        assertNull(r.errorMessage)
    }

    @Test
    fun `onError captures failure without response`() {
        val recorded = mutableListOf<LlmCallRecord>()
        val listener = CapturingChatModelListener("agent_stream", { recorded += it })

        val attrs = java.util.HashMap<Any, Any>()
        val request = ChatRequest.builder()
            .messages(UserMessage.from("q"))
            .modelName("m")
            .build()
        listener.onError(ChatModelErrorContext(RuntimeException("boom"), request, ModelProvider.OTHER, attrs))

        assertEquals(1, recorded.size)
        val r = recorded.first()
        assertFalse(r.success)
        assertEquals("boom", r.errorMessage)
        assertNull(r.responseJson)
        assertNull(r.promptTokens)
    }

    @Test
    fun `long payloads are truncated to the cap`() {
        val recorded = mutableListOf<LlmCallRecord>()
        val listener = CapturingChatModelListener("react", { recorded += it })

        val attrs = java.util.HashMap<Any, Any>()
        val big = "x".repeat(LlmCallRecord.MAX_FIELD_CHARS + 1000)
        val request = ChatRequest.builder()
            .messages(UserMessage.from(big))
            .modelName("m")
            .build()
        listener.onRequest(ChatModelRequestContext(request, ModelProvider.OTHER, attrs))
        listener.onResponse(
            ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("r")).build(),
                request,
                ModelProvider.OTHER,
                attrs
            )
        )

        val r = recorded.first()
        assertTrue(
            "requestJson should be capped, was ${r.requestJson.length}",
            r.requestJson.length <= LlmCallRecord.MAX_FIELD_CHARS + 64
        )
    }

    @Test
    fun `recorder exceptions do not propagate to the caller`() {
        val listener = CapturingChatModelListener("react", { error("boom in recorder") })
        val attrs = java.util.HashMap<Any, Any>()
        val request = ChatRequest.builder().messages(UserMessage.from("hi")).modelName("m").build()
        listener.onRequest(ChatModelRequestContext(request, ModelProvider.OTHER, attrs))
        // Must not throw even though the recorder throws.
        listener.onResponse(
            ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
                request,
                ModelProvider.OTHER,
                attrs
            )
        )
    }

    @Test
    fun `metrics-only mode captures no message content`() {
        val recorded = mutableListOf<LlmCallRecord>()
        val listener = CapturingChatModelListener("react", { recorded += it }, captureContent = false)

        val attrs = java.util.HashMap<Any, Any>()
        val request = ChatRequest.builder()
            .messages(SystemMessage.from("secret system prompt"), UserMessage.from("hello"))
            .modelName("test-model")
            .build()
        listener.onRequest(ChatModelRequestContext(request, ModelProvider.OTHER, attrs))

        val response = ChatResponse.builder()
            .aiMessage(AiMessage.from("ok secret answer"))
            .tokenUsage(TokenUsage(10, 5, 15))
            .build()
        listener.onResponse(ChatModelResponseContext(response, request, ModelProvider.OTHER, attrs))

        assertEquals(1, recorded.size)
        val r = recorded.first()
        // 纯指标保留
        assertTrue(r.requestJson.contains("\"messageCount\":2"))
        assertTrue(r.requestJson.contains("\"hasSystemPrompt\":true"))
        assertTrue(r.requestJson.contains("\"toolsCount\":0"))
        assertTrue(r.requestJson.contains("test-model"))
        assertEquals(10, r.promptTokens)
        assertEquals(15, r.totalTokens)
        assertTrue(r.responseJson!!.contains("\"textLength\":16"))
        // 消息内容绝不落库（隐私红线）
        assertFalse(r.requestJson.contains("\"messages\""))
        assertFalse(r.requestJson.contains("hello"))
        assertFalse(r.requestJson.contains("secret system prompt"))
        assertFalse(r.responseJson!!.contains("ok secret answer"))
    }

    @Test
    fun `metrics-only mode keeps tool call names but drops arguments`() {
        val recorded = mutableListOf<LlmCallRecord>()
        val listener = CapturingChatModelListener("react", { recorded += it }, captureContent = false)

        val attrs = java.util.HashMap<Any, Any>()
        val request = ChatRequest.builder().messages(UserMessage.from("q")).modelName("m").build()
        listener.onRequest(ChatModelRequestContext(request, ModelProvider.OTHER, attrs))

        val toolReq = ToolExecutionRequest.builder()
            .id("1")
            .name("search_media")
            .arguments("{\"query\":\"my cat\"}")
            .build()
        val response = ChatResponse.builder()
            .aiMessage(AiMessage.from(toolReq))
            .build()
        listener.onResponse(ChatModelResponseContext(response, request, ModelProvider.OTHER, attrs))

        val r = recorded.first()
        assertTrue(r.responseJson!!.contains("\"toolCallNames\":[\"search_media\"]"))
        assertFalse(r.responseJson!!.contains("arguments"))
        assertFalse(r.responseJson!!.contains("my cat"))
    }

    @Test
    fun `metrics-only mode caps error message to 500 chars`() {
        val recorded = mutableListOf<LlmCallRecord>()
        val listener = CapturingChatModelListener("react", { recorded += it }, captureContent = false)

        val attrs = java.util.HashMap<Any, Any>()
        val request = ChatRequest.builder().messages(UserMessage.from("q")).modelName("m").build()
        val longMessage = "e".repeat(2000)
        listener.onError(
            ChatModelErrorContext(RuntimeException(longMessage), request, ModelProvider.OTHER, attrs)
        )

        val r = recorded.first()
        assertNotNull(r.errorMessage)
        assertTrue(
            "errorMessage should be capped near 500 chars, was ${r.errorMessage!!.length}",
            r.errorMessage!!.length <=
                CapturingChatModelListener.ERROR_MESSAGE_MAX_CHARS + 64
        )
        assertFalse(r.errorMessage!!.contains(longMessage))
    }
}
