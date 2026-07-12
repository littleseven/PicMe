package com.mamba.picme.server.config

/**
 * 服务端配置（全部来自环境变量；本地 dev 用 .env，生产用 systemd EnvironmentFile）。
 *
 * LLM 默认接 DeepSeek，经 腾讯 TokenHub 或 Cloudflare AI Gateway（均 OpenAI 兼容），
 * 通过 LLM_BASE_URL 切换。限流放宽到 100 次/分钟、日预算 ¥20。
 */
data class AppConfig(
    val host: String,
    val port: Int,
    val dbPath: String,
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val llmDailyBudgetCny: Double,
    val rateLimitPerMin: Int,
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
            // DeepSeek 直连为兜底默认；生产请改为 TokenHub 或 Cloudflare AI Gateway 端点
            llmBaseUrl = env("LLM_BASE_URL", "https://api.deepseek.com/v1"),
            llmApiKey = env("LLM_API_KEY", ""),
            llmModel = env("LLM_MODEL", "deepseek-chat"),
            llmDailyBudgetCny = envDouble("LLM_DAILY_BUDGET_CNY", 20.0),
            rateLimitPerMin = envInt("RATE_LIMIT_PER_MIN", 100),
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
