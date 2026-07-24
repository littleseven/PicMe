package com.mamba.picme.agent.core.inference.remote.log

import com.mamba.data.message.AiMessage
import com.mamba.data.message.UserMessage
import com.mamba.model.ModelProvider
import com.mamba.model.chat.listener.ChatModelErrorContext
import com.mamba.model.chat.listener.ChatModelRequestContext
import com.mamba.model.chat.listener.ChatModelResponseContext
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.chat.response.ChatResponse
import com.mamba.model.output.TokenUsage
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
        val listener = CapturingChatModelListener("react") { recorded += it }

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
        val listener = CapturingChatModelListener("agent_stream") { recorded += it }

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
        val listener = CapturingChatModelListener("react") { recorded += it }

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
        val listener = CapturingChatModelListener("react") { error("boom in recorder") }
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
}
