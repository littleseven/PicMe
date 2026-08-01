package com.mamba.picme.server.routes

import com.mamba.picme.server.issue.IssueReportService
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * `POST /v1/report-issue`：已认证用户上报问题。
 *
 * - 需 AppToken 鉴权；
 * - 每账号每天最多 10 条；
 * - 服务端自动脱敏并同步 GitHub issue（失败不阻塞）。
 */
fun Route.issueReportRoute(service: IssueReportService, rateLimiter: RateLimiter) {
    post("/v1/report-issue") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        if (!rateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded")); return@post
        }
        val email = call.ownerEmail() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        val req = try {
            call.receive<IssueReportRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "invalid body"))
            return@post
        }
        val category = req.category.trim().lowercase().takeIf { it.isNotEmpty() } ?: "other"
        val title = req.title.trim().takeIf { it.isNotEmpty() } ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "title required"))
            return@post
        }
        val description = req.description.trim()

        val issueId = service.submit(
            reporterAccountId = call.ownerAccountId(),
            reporterEmail = email,
            issueCategory = category,
            issueTitle = title,
            issueDescription = description,
        )
        call.respond(HttpStatusCode.OK, IssueReportResponse(ok = true, issueId = issueId))
    }
}

@Serializable
internal data class IssueReportResponse(
    val ok: Boolean,
    val issueId: Int,
)

/** 从已认证的 TokenHashKey 反查 account.id；鉴权通过后必然存在。 */
internal suspend fun ApplicationCall.ownerAccountId(): Int {
    val hash = ownerTokenHash() ?: error("not authenticated")
    return requireNotNull(com.mamba.picme.server.auth.AccountService.idForTokenHash(hash)) { "account not found" }
}

@Serializable
private data class IssueReportRequest(
    val category: String = "other",
    val title: String = "",
    val description: String = "",
)
