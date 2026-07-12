package com.mamba.picme.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("picme-email")

class EmailService(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val fromEmail: String = "noreply@polang.net",
) {
    suspend fun sendVerificationCode(email: String, code: String): Boolean {
        if (apiKey.isBlank()) {
            logger.warn("RESEND_API_KEY not configured, skipping email to {}", email)
            return false
        }

        return try {
            val resp = httpClient.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("from", fromEmail)
                    put("to", email)
                    put("subject", "PicMe 验证码")
                    put("html", """
                        <p>你的 PicMe 验证码是：</p>
                        <h2 style="font-size:32px;letter-spacing:4px;">$code</h2>
                        <p>验证码 10 分钟内有效。</p>
                    """.trimIndent())
                }.toString())
            }
            logger.info("Verification email sent to {}, status={}", email, resp.status.value)
            resp.status.value in 200..299
        } catch (e: Exception) {
            logger.error("Failed to send email to {}", email, e)
            false
        }
    }
}
