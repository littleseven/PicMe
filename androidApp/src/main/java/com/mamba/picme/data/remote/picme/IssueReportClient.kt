package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 用户问题上报客户端：POST /v1/report-issue。
 *
 * - 需要 X-App-Token 鉴权；
 * - 服务端自动脱敏并同步 GitHub issue；
 * - 返回服务端生成的 issueId。
 */
class IssueReportClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    /**
     * 提交问题上报。
     *
     * @param token 用户账号 token（X-App-Token）
     * @param category 问题类别，如 crash / bug / ai / other
     * @param title 问题标题
     * @param description 问题描述
     * @return 成功返回 issueId，失败返回包含 error 字段的 Result
     */
    suspend fun submit(
        token: String,
        category: String,
        title: String,
        description: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("category", category)
                .put("title", title)
                .put("description", description)
                .toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/report-issue")
                .header("X-App-Token", token)
                .post(body.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val error = try {
                    JSONObject(respBody).optString("error", "unknown_error")
                } catch (_: Exception) {
                    "http_${resp.code}"
                }
                throw IssueReportException(resp.code, error)
            }
            val json = JSONObject(respBody)
            json.getInt("issueId")
        }
    }

    class IssueReportException(val code: Int, val errorType: String) : Exception("HTTP $code: $errorType")

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
