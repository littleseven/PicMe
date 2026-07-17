package com.mamba.picme.data.remote.picme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PicMeAuthClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    suspend fun sendVerificationCode(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("email", email).toString()
            val req = Request.Builder()
                .url("$baseUrl/auth/email/send")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw PicMeAuthException(resp.code, errorBody(resp.body?.string()))
            }
        }
    }

    suspend fun verifyCode(email: String, code: String): Result<AuthResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("email", email)
                .put("code", code)
                .toString()
            val req = Request.Builder()
                .url("$baseUrl/auth/email/verify")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw PicMeAuthException(resp.code, errorBody(respBody))
            }
            val json = JSONObject(respBody)
            AuthResult(
                token = json.getString("token"),
                llmCallsUsed = json.optInt("llmCallsUsed", 0),
                llmCallsLimit = json.optInt("llmCallsLimit", 100),
            )
        }
    }

    suspend fun getQuota(token: String): Result<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/auth/quota")
                .header("X-App-Token", token)
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw PicMeAuthException(resp.code, errorBody(respBody))
            }
            val json = JSONObject(respBody)
            QuotaInfo(
                email = json.getString("email"),
                llmCallsUsed = json.optInt("llmCallsUsed", 0),
                llmCallsLimit = json.optInt("llmCallsLimit", 100),
            )
        }
    }

    suspend fun deleteAccount(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/auth/account")
                .header("X-App-Token", token)
                .delete()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw PicMeAuthException(resp.code, errorBody(resp.body?.string()))
            }
        }
    }

    private fun errorBody(raw: String?): String {
        return try {
            JSONObject(raw ?: "").optString("error", "unknown_error")
        } catch (_: Exception) {
            "unknown_error"
        }
    }

    data class AuthResult(
        val token: String,
        val llmCallsUsed: Int,
        val llmCallsLimit: Int,
    )

    data class QuotaInfo(
        val email: String,
        val llmCallsUsed: Int,
        val llmCallsLimit: Int,
    )

    class PicMeAuthException(val code: Int, val errorType: String) : Exception("HTTP $code: $errorType")

    companion object {
        private const val TAG = "PicMeAuth"
        private const val DEFAULT_BASE_URL = "https://api.polang.net"
    }
}
