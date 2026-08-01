package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.auth.DIAG_WORKER_TOKEN_HEADER
import com.mamba.picme.server.diag.DiagService
import com.mamba.picme.server.diag.DiagStatus
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** S3 上报护栏长度上限（超限 413）。 */
private const val MAX_DESCRIPTION_LEN = 2000
private const val MAX_SUMMARY_LEN = 4000
private const val MAX_LOGS_LEN = 200 * 1024

@Serializable
data class DiagBundle(
    val logs: String = "",
    val crashTrace: String? = null,
    val appVersion: String = "",
    val gitSha: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
)

@Serializable
data class DiagReportRequest(
    val description: String,
    val bundle: DiagBundle,
    val conversationSummary: String? = null, // 可选：诊断澄清对话摘要（向后兼容旧客户端）
)

@Serializable
data class DiagReportResponse(val jobId: Int, val status: String)

@Serializable
data class DiagJobStatus(
    val jobId: Int,
    val status: String,
    val rootCause: String? = null,
    val fixBranch: String? = null,
    val compareUrl: String? = null,
    val tested: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class DiagConfirmRequest(val mode: String)

@Serializable
data class DiagClaimResponse(
    val jobId: Int,
    val phase: String,
    val description: String,
    val bundle: DiagBundle,
    val gitSha: String,
    val rootCause: String? = null,
    val fixMode: String? = null,
    val conversationSummary: String? = null,
    val suggestedFix: String? = null,
)

@Serializable
data class DiagWorkResult(
    val phase: String,            // diagnose | fix
    val status: String,           // diagnose: DIAGNOSED|DIAGNOSE_FAILED  fix: FIXED|FIXED_UNVERIFIED|FIX_FAILED
    val rootCause: String? = null,
    val fixBranch: String? = null,
    val compareUrl: String? = null,
    val tested: Boolean = false,
    val error: String? = null,
    val suspectFiles: String? = null,   // diagnose：疑似文件（写入 worker_log）
    val suggestedFix: String? = null,   // diagnose：修复方向（存 suggested_fix 列）
    val log: String? = null,            // fix：changedFiles/summary 摘要（写入 worker_log）
)

fun Routing.diagRoute(workerToken: String, reportRateLimiter: RateLimiter? = null) {
    // ── 手机侧端点（X-App-Token；全局拦截器在 prod 已校验，这里兜底取 owner 身份）──
    post("/diag/report") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        // S3 限频：每账号 5 次/小时（key=owner tokenHash），先于 body 解析
        if (reportRateLimiter != null && !reportRateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded"))
            return@post
        }
        val req = call.receive<DiagReportRequest>()
        if (req.description.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "description required"))
            return@post
        }
        // S3 限长：description ≤ 2000、conversationSummary ≤ 4000、logs ≤ 200KB
        if (req.description.length > MAX_DESCRIPTION_LEN ||
            (req.conversationSummary?.length ?: 0) > MAX_SUMMARY_LEN ||
            req.bundle.logs.length > MAX_LOGS_LEN
        ) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "payload_too_large"))
            return@post
        }
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        val id = DiagService.createJob(
            ownerTokenHash = owner,
            deviceId = deviceId,
            description = req.description,
            bundleJson = appJson.encodeToString(DiagBundle.serializer(), req.bundle),
            gitSha = req.bundle.gitSha,
            conversationSummary = req.conversationSummary,
        )
        call.respond(DiagReportResponse(id, DiagStatus.QUEUED.name))
    }

    get("/diag/jobs/{id}") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@get
        }
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest); return@get
        }
        val job = DiagService.getJob(id, owner) ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found")); return@get
        }
        call.respond(
            DiagJobStatus(
                jobId = job.id,
                status = job.status.name,
                rootCause = job.rootCause,
                fixBranch = job.fixBranch,
                compareUrl = job.compareUrl,
                tested = job.tested,
                error = job.workerLog?.takeLast(500),
                updatedAt = job.updatedAt,
            ),
        )
    }

    post("/diag/jobs/{id}/confirm") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest); return@post
        }
        val req = call.receive<DiagConfirmRequest>()
        val ok = try {
            DiagService.confirmFix(id, owner, req.mode)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to (e.message ?: "")))
            return@post
        }
        if (!ok) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "not_diagnosed_or_not_owner")); return@post
        }
        call.respond(mapOf("status" to DiagStatus.FIX_REQUESTED.name))
    }

    // ── worker 侧端点（X-Diag-Worker-Token）──
    get("/diag/work/jobs") {
        if (!call.isWorker(workerToken)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@get
        }
        val claim = DiagService.claimNextJob() ?: run {
            call.respond(HttpStatusCode.NoContent); return@get
        }
        val bundle = try {
            appJson.decodeFromString(DiagBundle.serializer(), claim.bundleJson)
        } catch (e: Exception) {
            DiagBundle()
        }
        call.respond(
            DiagClaimResponse(
                jobId = claim.id,
                phase = claim.phase,
                description = claim.description,
                bundle = bundle,
                gitSha = claim.gitSha,
                rootCause = claim.rootCause,
                fixMode = claim.fixMode,
                conversationSummary = claim.conversationSummary,
                suggestedFix = claim.suggestedFix,
            ),
        )
    }

    post("/diag/work/jobs/{id}/result") {
        if (!call.isWorker(workerToken)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest); return@post
        }
        val r = call.receive<DiagWorkResult>()
        when (r.phase) {
            "diagnose" -> {
                val status = parseDiagnoseStatus(r.status)
                if (status == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "bad diagnose status"))
                    return@post
                }
                DiagService.submitDiagnosis(
                    id, r.rootCause, status,
                    error = r.error ?: r.suspectFiles?.let { "suspectFiles: $it" },
                    suggestedFix = r.suggestedFix,
                )
            }
            "fix" -> {
                val status = parseFixStatus(r.status)
                if (status == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "bad fix status"))
                    return@post
                }
                DiagService.submitFix(id, status, r.fixBranch, r.compareUrl, r.tested, r.log ?: r.error)
            }
            else -> {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "unknown phase"))
                return@post
            }
        }
        call.respond(mapOf("ok" to true))
    }
}

private fun parseDiagnoseStatus(s: String): DiagStatus? = when (s) {
    "DIAGNOSED" -> DiagStatus.DIAGNOSED
    "DIAGNOSE_FAILED" -> DiagStatus.DIAGNOSE_FAILED
    else -> null
}

private fun parseFixStatus(s: String): DiagStatus? = when (s) {
    "FIXED" -> DiagStatus.FIXED
    "FIXED_UNVERIFIED" -> DiagStatus.FIXED_UNVERIFIED
    "FIX_FAILED" -> DiagStatus.FIX_FAILED
    else -> null
}

private fun ApplicationCall.isWorker(expected: String): Boolean {
    if (expected.isBlank()) return false
    return request.headers[DIAG_WORKER_TOKEN_HEADER] == expected
}

