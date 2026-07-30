package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.diag.DiagBundle
import com.mamba.picme.core.diag.DiagJobStatus
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
 */
class DiagClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    suspend fun reportDiagnosis(token: String, description: String, bundle: DiagBundle): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/diag/report")
                    .header("X-App-Token", token)
                    .post(buildReportBody(description, bundle).toRequestBody(jsonMedia))
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
                val json = JSONObject(body)
                DiagJobStatus(
                    jobId = json.getInt("jobId"),
                    status = json.getString("status"),
                    rootCause = json.optString("rootCause").takeIf { it.isNotBlank() },
                    fixBranch = json.optString("fixBranch").takeIf { it.isNotBlank() },
                    compareUrl = json.optString("compareUrl").takeIf { it.isNotBlank() },
                    tested = json.optBoolean("tested", false),
                )
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

        /** 构造 /diag/report 请求体（抽出以便单测契约）。 */
        fun buildReportBody(description: String, bundle: DiagBundle): String =
            JSONObject()
                .put("description", description)
                .put("bundle", bundle.toJsonObject())
                .toString()
    }
}
