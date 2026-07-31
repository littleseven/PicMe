package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.diag.DiagBundle
import com.mamba.picme.core.diag.DiagJobStatus
import com.mamba.picme.core.diag.DiagSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程诊断 HTTP 客户端，镜像 [PoLangAuthClient] 的风格（OkHttp + org.json + X-App-Token）。
 * 与 server 端 DiagRoute 契约一致：POST /diag/report、GET /diag/jobs/{id}、POST /diag/jobs/{id}/confirm。
 * description / conversationSummary 在此统一过 [DiagSanitizer] 并按 server 上限截断（S3 对齐）。
 */
class DiagClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    suspend fun reportDiagnosis(
        token: String,
        description: String,
        bundle: DiagBundle,
        conversationSummary: String? = null,
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/diag/report")
                    .header("X-App-Token", token)
                    .post(buildReportBody(description, bundle, conversationSummary).toRequestBody(jsonMedia))
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                JSONObject(body).getInt("jobId")
            }
        }

    suspend fun fetchDiagStatus(token: String, jobId: Int): Result<DiagJobStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/diag/jobs/$jobId")
                    .header("X-App-Token", token)
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                parseJobStatus(body)
            }
        }

    suspend fun confirmFix(token: String, jobId: Int, mode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("mode", mode).toString()
                val req = Request.Builder()
                    .url("$baseUrl/diag/jobs/$jobId/confirm")
                    .header("X-App-Token", token)
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            }
        }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"

        /** 与 server S3 护栏一致的长度上限（客户端先截断兜底，避免 413）。 */
        private const val MAX_DESCRIPTION_LEN = 2000
        private const val MAX_SUMMARY_LEN = 4000

        /** 构造 /diag/report 请求体（抽出以便单测契约）。description/summary 统一脱敏 + 截断。 */
        fun buildReportBody(description: String, bundle: DiagBundle, conversationSummary: String? = null): String {
            val o = JSONObject()
                .put("description", DiagSanitizer.sanitize(description).take(MAX_DESCRIPTION_LEN))
                .put("bundle", bundle.toJsonObject())
            conversationSummary?.takeIf { it.isNotBlank() }?.let {
                o.put("conversationSummary", DiagSanitizer.sanitize(it).take(MAX_SUMMARY_LEN))
            }
            return o.toString()
        }

        /** 解析 /diag/jobs/{id} 响应（抽出以便单测契约；未知新状态原样保留为字符串，不 crash）。 */
        fun parseJobStatus(body: String): DiagJobStatus {
            val json = JSONObject(body)
            return DiagJobStatus(
                jobId = json.getInt("jobId"),
                status = json.getString("status"),
                rootCause = json.optString("rootCause").takeIf { it.isNotBlank() },
                fixBranch = json.optString("fixBranch").takeIf { it.isNotBlank() },
                compareUrl = json.optString("compareUrl").takeIf { it.isNotBlank() },
                tested = json.optBoolean("tested", false),
                error = json.optString("error").takeIf { it.isNotBlank() },
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }
    }
}
