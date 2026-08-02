package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ClaudeToolResultRoute 测试：JSON 透传（错误场景见 [ClaudeRouteErrorTest]）。
 * tool-result 是诊断只读链路，不校验白名单。
 */
class ClaudeToolResultRouteTest {

    private val upstreamBody = """{"ok":true}"""

    @Test
    fun `已认证用户未在白名单仍可回传 tool 结果`() = testApplication {
        val upstream = okUpstream(upstreamBody, "application/json")
        claudePipeline { claudeToolResultRoute(upstream, null) }
        val token = seedClaudeToken()
        val resp = client.post("/v1/claude-tool-result") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"toolCallId":"t1","result":"ok"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(upstreamBody, resp.bodyAsText())
    }
}
