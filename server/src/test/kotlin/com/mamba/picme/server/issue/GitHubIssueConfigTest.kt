package com.mamba.picme.server.issue

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GitHubIssueConfigTest(
    private val token: String,
    private val repo: String,
    private val expectedError: String,
) {

    companion object {
        @Parameterized.Parameters(name = "{index}")
        @JvmStatic
        fun data(): Collection<Array<out Any>> = listOf(
            arrayOf("", "owner/repo", "github not configured"),
            arrayOf("tok", "", "github not configured"),
            arrayOf("tok", "badrepo", "invalid repo"),
        )
    }

    @Test
    fun `config validation fails`() = runBlocking {
        val c = GitHubIssueClient(HttpClient(MockEngine { respond("") }), token, repo)
        val r = c.createIssue("t", "b")
        assertFalse(r.success)
        assertTrue(r.error?.contains(expectedError) == true)
    }
}
