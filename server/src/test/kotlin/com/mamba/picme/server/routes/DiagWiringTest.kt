package com.mamba.picme.server.routes

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.auth.DIAG_WORKER_TOKEN_HEADER
import com.mamba.picme.server.db.DiagJobs
import com.mamba.picme.server.module
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 T6 接线：真实 Application.module() 装好全局 AppToken 拦截器 + 全部路由后，
 * /diag/work 路径被拦截器放行走 worker token，而 /diag/report 无 token 仍被 401。
 */
class DiagWiringTest {

    private val testConfig = AppConfig(
        host = "127.0.0.1", port = 0, dbPath = "ignored",
        freeLlmQuota = 100, guestLlmQuota = 100,
        cloudflareAigUrl = "", cloudflareAigToken = "",
        tokenhubUrl = "", tokenhubApiToken = "", forceProvider = "", maxTokensCap = 4096,
        rateLimitPerMin = 0, resendApiKey = "", emailFrom = "",
        cosSecretId = "", cosSecretKey = "", cosRegion = "", cosBucket = "", cosPresignTtlMin = 60,
        adminToken = "", diagWorkerToken = "testw", llmPrices = emptyMap(),
    )

    @Test
    fun `worker diag path bypasses AppToken interceptor and reaches diagRoute`() = testApplication {
        TestDb.init(DiagJobs)
        application { module(testConfig) }

        // worker 带 token → 拦截器放行 → diagRoute → 空队列 204
        val ok = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, "testw") }
        assertEquals(HttpStatusCode.NoContent, ok.status)

        // worker 无 token → diagRoute 自身 401
        val none = client.get("/diag/work/jobs")
        assertEquals(HttpStatusCode.Unauthorized, none.status)
    }

    @Test
    fun `phone diag path without token is rejected by AppToken interceptor`() = testApplication {
        TestDb.init(DiagJobs)
        application { module(testConfig) }

        val resp = client.post("/diag/report") {
            contentType(ContentType.Application.Json)
            setBody("""{"description":"x","bundle":{}}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
