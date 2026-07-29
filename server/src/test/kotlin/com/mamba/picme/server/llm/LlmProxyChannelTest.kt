package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.TokenUsage
import com.mamba.picme.server.analytics.fromSseStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProxyChannelTest {

    private fun cfg(
        authStyle: AuthStyle = AuthStyle.BEARER,
        token: String = "tok-abc",
        modelMap: Map<String, String> = mapOf("deepseek-chat" to "glm-5.2"),
        defaultModel: String = "glm-5.2",
    ) = ChannelConfig(1, "TestChan", "direct", "http://up.example/chat", authStyle, token, modelMap, defaultModel)

    private fun proxy(engine: MockEngine) = LlmProxy(HttpClient(engine), maxTokensCap = 4096)

    private val usageBody =
        """{"id":"x","usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

    @After
    fun tearDown() {
        ChannelRegistry.setActiveForTesting(null)
    }

    @Test
    fun `forward maps model and forces stream false`() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg())
        val body = buildJsonObject { put("model", "deepseek-chat") }
        val result = proxy(engine).forward("1.2.3.4", body)
        assertTrue(result is ProxyResult.Success)
        result as ProxyResult.Success
        assertEquals("glm-5.2", result.model)
        assertEquals("TestChan", result.provider)
        assertEquals(TokenUsage(10, 5, 15), result.usage)
        val sent = (captured!!.body as TextContent).text
        assertTrue(sent.contains("\"model\":\"glm-5.2\""))
        assertTrue(sent.contains("\"stream\":false"))
    }

    @Test
    fun `bearer auth style sends Authorization header`() = runBlocking {
        var header: String? = null
        val engine = MockEngine { req ->
            header = req.headers["Authorization"]
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg(authStyle = AuthStyle.BEARER, token = "sk-123"))
        proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertEquals("Bearer sk-123", header)
    }

    @Test
    fun `cf_aig auth style sends cf-aig-authorization header`() = runBlocking {
        var header: String? = null
        val engine = MockEngine { req ->
            header = req.headers["cf-aig-authorization"]
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg(authStyle = AuthStyle.CF_AIG, token = "cf-tok"))
        proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertEquals("Bearer cf-tok", header)
    }

    @Test
    fun `unsupported model with blank default returns 400`() = runBlocking {
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        ChannelRegistry.setActiveForTesting(cfg(modelMap = mapOf("a" to "b"), defaultModel = ""))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.BadRequest, result.status)
        assertEquals("unsupported_model", result.logStatus)
    }

    @Test
    fun `unmapped model falls back to default_model`() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg(modelMap = mapOf("a" to "b"), defaultModel = "glm-5.2"))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "kimi-k2.6") })
        assertTrue(result is ProxyResult.Success)
        result as ProxyResult.Success
        assertEquals("glm-5.2", result.model)
        val sent = (captured!!.body as TextContent).text
        assertTrue(sent.contains("\"model\":\"glm-5.2\""))
    }

    @Test
    fun `mapped model takes precedence over default`() = runBlocking {
        val engine = MockEngine { respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        ChannelRegistry.setActiveForTesting(cfg(modelMap = mapOf("deepseek-chat" to "mapped-x"), defaultModel = "fallback-y"))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Success)
        assertEquals("mapped-x", (result as ProxyResult.Success).model)
    }

    @Test
    fun `blank token returns 500 channel_token_missing`() = runBlocking {
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        ChannelRegistry.setActiveForTesting(cfg(token = ""))
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.InternalServerError, result.status)
        assertEquals("channel_token_missing", result.logStatus)
    }

    @Test
    fun `no active channel returns 503 no_active_channel`() = runBlocking {
        ChannelRegistry.setActiveForTesting(null)
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.ServiceUnavailable, result.status)
        assertEquals("no_active_channel", result.logStatus)
    }

    @Test
    fun `max_tokens over cap returns 400`() = runBlocking {
        val engine = MockEngine { respond("""{}""", HttpStatusCode.OK) }
        ChannelRegistry.setActiveForTesting(cfg())
        val body = buildJsonObject {
            put("model", "deepseek-chat")
            put("max_tokens", 99999)
        }
        val result = proxy(engine).forward("1.2.3.4", body)
        assertTrue(result is ProxyResult.Error)
        result as ProxyResult.Error
        assertEquals(HttpStatusCode.BadRequest, result.status)
        assertEquals("bad_request", result.logStatus)
    }

    @Test
    fun `null usage when upstream omits it`() = runBlocking {
        val engine = MockEngine {
            respond("""{"id":"x"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg())
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Success)
        assertNull((result as ProxyResult.Success).usage)
    }

    // ── 流式 SSE 透传 ──

    private val sseBody =
        "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n" +
            "data: [DONE]\n\n"

    @Test
    fun `stream true forwards SSE and preserves stream options`() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        }
        ChannelRegistry.setActiveForTesting(cfg())
        val body = buildJsonObject {
            put("model", "deepseek-chat")
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        val result = proxy(engine).forward("1.2.3.4", body)
        assertTrue(result is ProxyResult.Streaming)
        result as ProxyResult.Streaming
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals("glm-5.2", result.model)
        val text = result.channel.toByteArray().toString(Charsets.UTF_8)
        assertEquals(sseBody, text)
        assertEquals(TokenUsage(10, 5, 15), fromSseStream(text))
        // stream/stream_options 原样转发，不再改写为 false
        val sent = (captured!!.body as TextContent).text
        assertTrue(sent.contains("\"stream\":true"))
        assertTrue(sent.contains("\"include_usage\":true"))
    }

    @Test
    fun `stream true upstream error passes through status and body`() = runBlocking {
        val errBody = """{"error":"upstream boom"}"""
        val engine = MockEngine {
            respond(errBody, HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
        }
        ChannelRegistry.setActiveForTesting(cfg())
        val body = buildJsonObject {
            put("model", "deepseek-chat")
            put("stream", true)
        }
        val result = proxy(engine).forward("1.2.3.4", body)
        assertTrue(result is ProxyResult.Success)
        result as ProxyResult.Success
        assertEquals(HttpStatusCode.InternalServerError, result.status)
        assertEquals(errBody, result.bytes.toString(Charsets.UTF_8))
        assertNull(result.usage)
    }
}
