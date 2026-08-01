package com.mamba.picme.server.issue

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * 调用 GitHub REST API 创建 issue。
 *
 * - 未配置 token 或 repo 时直接返回「未启用」。
 * - 创建失败不抛异常，返回失败原因字符串，便于上层记录但不阻塞用户。
 */
class GitHubIssueClient(
    private val httpClient: HttpClient,
    private val token: String,
    private val repo: String,
) {

    private val logger = LoggerFactory.getLogger(GitHubIssueClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val success: Boolean,
        val number: Int? = null,
        val url: String = "",
        val error: String? = null,
    )

    /**
     * 创建 GitHub issue。
     *
     * @param title issue 标题（已脱敏）
     * @param body issue 正文（已脱敏）
     */
    suspend fun createIssue(title: String, body: String): Result {
        if (token.isBlank() || repo.isBlank()) {
            return Result(success = false, error = "github not configured")
        }

        val (owner, repoName) = repo.split("/", limit = 2).let {
            if (it.size != 2) return Result(success = false, error = "invalid repo: $repo")
            it[0] to it[1]
        }

        return try {
            val resp = httpClient.post("https://api.github.com/repos/$owner/$repoName/issues") {
                headers {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    set("Accept", "application/vnd.github+json")
                    set("X-GitHub-Api-Version", "2022-11-28")
                }
                setBody(json.encodeToString(CreateIssueRequest.serializer(), CreateIssueRequest(title, body)))
            }
            val text = resp.bodyAsText()
            if (resp.status == HttpStatusCode.Created) {
                val created = json.decodeFromString(CreateIssueResponse.serializer(), text)
                Result(success = true, number = created.number, url = created.htmlUrl)
            } else {
                logger.warn("GitHub create issue failed: ${resp.status}, body=$text")
                Result(success = false, error = "github ${resp.status}: $text")
            }
        } catch (e: Exception) {
            logger.warn("GitHub create issue exception", e)
            Result(success = false, error = e.message ?: "unknown")
        }
    }

    @Serializable
    private data class CreateIssueRequest(
        val title: String,
        val body: String,
    )

    @Serializable
    private data class CreateIssueResponse(
        val number: Int,
        @SerialName("html_url") val htmlUrl: String,
    )
}
