package com.mamba.picme.server.config

data class AppConfig(
    val host: String,
    val port: Int,
    val dbPath: String,
    // Auth
    val freeLlmQuota: Int,
    // LLM proxy
    val cloudflareAigUrl: String,
    val cloudflareAigToken: String,
    val tokenhubUrl: String,
    val tokenhubApiToken: String,
    val forceProvider: String,
    val maxTokensCap: Int,
    // Rate limit
    val rateLimitPerMin: Int,
    // Email
    val resendApiKey: String,
    val emailFrom: String,
    // COS
    val cosSecretId: String,
    val cosSecretKey: String,
    val cosRegion: String,
    val cosBucket: String,
    val cosPresignTtlMin: Int,
) {
    companion object {
        fun load(): AppConfig = AppConfig(
            host = env("HOST", "127.0.0.1"),
            port = envInt("PORT", 8080),
            dbPath = env("DB_PATH", "picme.db"),
            freeLlmQuota = envInt("FREE_LLM_QUOTA", 100),
            // Cloudflare AI Gateway (DeepSeek)
            cloudflareAigUrl = env(
                "CLOUDFLARE_AIG_URL",
                "https://gateway.ai.cloudflare.com/v1/a7656feec717409a19fa5217f0f7b2f9/picme/compat/chat/completions",
            ),
            cloudflareAigToken = env("CLOUDFLARE_AIG_TOKEN", ""),
            // Tencent TokenHub
            tokenhubUrl = env("TOKENHUB_URL", "https://tokenhub.tencentmaas.com/v1/chat/completions"),
            tokenhubApiToken = env("TOKENHUB_API_TOKEN", ""),
            forceProvider = env("FORCE_PROVIDER", ""),
            maxTokensCap = envInt("MAX_TOKENS_CAP", 4096),
            rateLimitPerMin = envInt("RATE_LIMIT_PER_MIN", 20),
            // Email (Resend)
            resendApiKey = env("RESEND_API_KEY", ""),
            emailFrom = env("EMAIL_FROM", "noreply@polang.net"),
            // COS
            cosSecretId = env("COS_SECRET_ID", ""),
            cosSecretKey = env("COS_SECRET_KEY", ""),
            cosRegion = env("COS_REGION", "ap-hongkong"),
            cosBucket = env("COS_BUCKET", ""),
            cosPresignTtlMin = envInt("COS_PRESIGN_TTL_MIN", 60),
        )

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

        private fun envInt(key: String, default: Int): Int =
            System.getenv(key)?.toIntOrNull() ?: default
    }
}
