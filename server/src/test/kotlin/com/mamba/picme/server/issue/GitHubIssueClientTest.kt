package com.mamba.picme.server.issue

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIssueClientTest {

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `创建成功返回 number 和 url`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
            respond(
                """{"number":42,"html_url":"https://github.com/owner/repo/issues/42"}""",
                HttpStatusCode.Created,
                headersOf("Content-Type", "application/json"),
            )
        }
        val c = GitHubIssueClient(client(engine), "tok", "owner/repo")
        val r = c.createIssue("t", "b")
        assertTrue(r.success)
        assertEquals(42, r.number)
        assertEquals("https://github.com/owner/repo/issues/42", r.url)
    }

    @Test
    fun `GitHub 返回非 201 记录错误`() = runBlocking {
        val engine = MockEngine {
            respond("""{"message":"Validation Failed"}""", HttpStatusCode.UnprocessableEntity)
        }
        val c = GitHubIssueClient(client(engine), "tok", "owner/repo")
        val r = c.createIssue("t", "b")
        assertFalse(r.success)
        assertTrue(r.error?.contains("422") == true)
    }
}
