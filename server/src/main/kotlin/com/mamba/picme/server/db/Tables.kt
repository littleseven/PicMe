package com.mamba.picme.server.db

import org.jetbrains.exposed.sql.Table

object Rules : Table("rule") {
    val id = integer("id").autoIncrement()
    val scene = varchar("scene", 64)       // night/portrait/food/...
    val locale = varchar("locale", 16)     // zh/en/...
    val conditionJson = text("condition_json").nullable()
    val paramsJson = text("params_json")   // 推荐参数包
    val version = integer("version")
    val enabled = integer("enabled").default(1)
    override val primaryKey = PrimaryKey(id)

    init {
        // (scene, locale, version) 唯一：让 seed 的 INSERT OR IGNORE 真正幂等，重启不重复；
        // version 进键，仍允许同一 (scene, locale) 新版本热更（RuleEngine 取 version DESC）。
        uniqueIndex(scene, locale, version)
    }
}

object Assets : Table("asset") {
    val key = varchar("key", 128)
    val kind = varchar("kind", 32)         // model/filter/preset
    val version = integer("version")
    val size = long("size").nullable()
    val md5 = varchar("md5", 64).nullable()
    val cosBucket = varchar("cos_bucket", 128)
    val cosKey = varchar("cos_key", 256)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(key)
}

object TelemetryEvents : Table("telemetry_event") {
    val id = long("id").autoIncrement()
    val type = varchar("type", 64)
    val payloadJson = text("payload_json").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object LlmDailyCounters : Table("llm_daily_counter") {
    val day = varchar("day", 16)           // '2026-07-12'
    val tokens = long("tokens").default(0L)
    val costCny = double("cost_cny").default(0.0)
    val blocked = integer("blocked").default(0)
    override val primaryKey = PrimaryKey(day)
}

// ── 邮箱注册 + 试用额度 ──────────────────────────────────

object Accounts : Table("account") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 256)
    val tokenHash = varchar("token_hash", 64)          // SHA-256(token)
    val status = varchar("status", 16).default("active") // active | revoked
    val llmCallsUsed = integer("llm_calls_used").default(0)
    val llmCallsLimit = integer("llm_calls_limit").default(100) // 试用额度（次）
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(email)
        uniqueIndex(tokenHash)
    }
}

object EmailVerifications : Table("email_verification") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 256)
    val code = varchar("code", 6)
    val status = varchar("status", 16).default("pending") // pending | used | expired
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
