package com.mamba.picme.server.config

/**
 * 服务端配置（全部来自环境变量；本地 dev 用 .env，生产用 systemd EnvironmentFile）。
 *
 * LLM 代理支持 Cloudflare AI Gateway (DeepSeek) 和腾讯 TokenHub 双后端，
 * 按 model 字段自动路由，与 infra/tencentscf/index.js 逻辑一致。
 */
data class AppConfig(
    val host: String,
    val port: Int,
    val dbPath: String,
    // Auth
    val appToken: String,
    // LLM proxy
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val llmDailyBudgetCny: Double,
    val cloudflareAigUrl: String,
    val cloudflareAigToken: String,
    val tokenhubUrl: String,
    val tokenhubApiToken: String,
    val forceProvider: String,
    val maxTokensCap: Int,
    // Rate limit
    val rateLimitPerMin: Int,
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
            // Auth — empty = dev mode (no auth check); production must set this
            appToken = env("APP_TOKEN", ""),
            // LLM proxy (P2 → promoted: SCF migration)
            llmBaseUrl = env("LLM_BASE_URL", "https://api.deepseek.com/v1"),
            llmApiKey = env("LLM_API_KEY", ""),
            llmModel = env("LLM_MODEL", "deepseek-chat"),
            llmDailyBudgetCny = envDouble("LLM_DAILY_BUDGET_CNY", 20.0),
            // Cloudflare AI Gateway (DeepSeek)
            cloudflareAigUrl = env(
                "CLOUDFLARE_AIG_URL",
                "https://gateway.ai.cloudflare.com/v1/a7656feec717409a19fa5217f0f7b2f9/picme/compat/chat/completions",
            ),
            cloudflareAigToken = env("CLOUDFLARE_AIG_TOKEN", ""),
            // Tencent TokenHub
            tokenhubUrl = env("TOKENHUB_URL", "https://tokenhub.tencentmaas.com/v1/chat/completions"),
            tokenhubApiToken = env("TOKENHUB_API_TOKEN", ""),
            // Force all requests to a specific provider (cloudflare|tokenhub), null = auto-route by model
            forceProvider = env("FORCE_PROVIDER", ""),
            maxTokensCap = envInt("MAX_TOKENS_CAP", 4096),
            // Rate limit
            rateLimitPerMin = envInt("RATE_LIMIT_PER_MIN", 20),
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

        private fun envDouble(key: String, default: Double): Double =
            System.getenv(key)?.toDoubleOrNull() ?: default
    }
}
