package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProxyUsageTest {

    private fun proxy(engine: MockEngine) = LlmProxy(
        httpClient = HttpClient(engine),
        cloudflareUrl = "http://cf.example/chat",
        cloudflareAigToken = "t",
        tokenhubUrl = "http://th.example/chat",
        tokenhubApiToken = "t",
        forceProvider = "cloudflare",
        maxTokensCap = 4096,
    )

    private val usageBody =
        """{"id":"x","usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

    @Test
    fun `forward parses usage from upstream success`() = runBlocking {
        val engine = MockEngine {
            respond(usageBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val body = buildJsonObject { put("model", "deepseek-chat") }
        val result = proxy(engine).forward("1.2.3.4", body)
        assertTrue(result is ProxyResult.Success)
        result as ProxyResult.Success
        assertEquals(TokenUsage(10, 5, 15), result.usage)
        assertEquals(LlmProvider.CLOUDFLARE, result.provider)
        assertEquals("deepseek/deepseek-chat", result.model) // alias 已解析
    }

    @Test
    fun `forward returns null usage when upstream omits it`() = runBlocking {
        val engine = MockEngine {
            respond("""{"id":"x"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val result = proxy(engine).forward("1.2.3.4", buildJsonObject { put("model", "deepseek-chat") })
        assertTrue(result is ProxyResult.Success)
        assertNull((result as ProxyResult.Success).usage)
    }
}
