package com.mamba.picme.server.admin

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.db.LlmChannels
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminChannelsRoutesTest {

    private val token = "test-admin-token"
    private val cookieVal get() = AdminAuth.expectedCookieValue(token)

    @Before
    fun setUp() {
        // /admin 概览页查 Accounts/LlmCallLogs，故一并建表；渠道测试只需 LlmChannels。
        TestDb.init(Accounts, LlmCallLogs, LlmChannels)
        ChannelRegistry.setActiveForTesting(null)
    }

    private fun formBody(
        name: String = "DeepSeek 直连",
        kind: String = "direct",
        baseUrl: String = "https://api.deepseek.com/v1/chat/completions",
        authStyle: String = "bearer",
        apiToken: String = "sk-test-1234",
        modelMap: String = "deepseek-v4-flash=deepseek-v4-flash",
        enabled: String = "1",
        defaultModel: String = "deepseek-v4-flash",
    ) = "name=$name&kind=$kind&base_url=$baseUrl&auth_style=$authStyle" +
        "&api_token=$apiToken&model_map=$modelMap&enabled=$enabled&default_model=$defaultModel"

    @Test
    fun `channels page requires cookie`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        val r = c.get("/admin/channels")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/login", r.headers[HttpHeaders.Location])
    }

    @Test
    fun `create channel then it appears and token is masked`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        assertEquals(HttpStatusCode.Found, r.status)

        val page = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("DeepSeek 直连"))
        assertTrue("token 不得明文出现", !html.contains("sk-test-1234"))
        assertTrue("应显示掩码", html.contains("••••"))
        assertTrue("应显示默认模型", html.contains("deepseek-v4-flash"))
        // 编辑页回填默认模型
        val editHtml = c.get("/admin/channels/1/edit") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(editHtml.contains("deepseek-v4-flash"))
    }

    @Test
    fun `activate sets channel active`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        c.post("/admin/channels/1/activate") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        val html = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(html.contains("生效中"))
    }

    @Test
    fun `delete active channel is rejected`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        c.post("/admin/channels") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody())
        }
        c.post("/admin/channels/1/activate") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        c.post("/admin/channels/1/delete") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("")
        }
        val html = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue("生效渠道应仍存在", html.contains("DeepSeek 直连"))
    }

    @Test
    fun `nav has channels link`() = testApplication {
        application { routing { adminRoute(token) } }
        val c = createClient { followRedirects = false }
        val html = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }.bodyAsText()
        assertTrue(html.contains("/admin/channels"))
    }
}
