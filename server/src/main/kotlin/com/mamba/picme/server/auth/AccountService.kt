package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.EmailVerifications
import com.mamba.picme.server.db.LlmCallLogs
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

object AccountService {

    private val random = SecureRandom()

    const val TOKEN_PREFIX = "pl-"
    private const val CODE_EXPIRY_MS = 10 * 60 * 1000L

    fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return TOKEN_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isTokenFormat(raw: String): Boolean =
        raw.startsWith(TOKEN_PREFIX) && raw.length > TOKEN_PREFIX.length + 16

    // ── Verification code ──

    suspend fun createVerification(email: String): String {
        val code = String.format("%06d", random.nextInt(1000000))
        val now = Instant.now().toEpochMilli()
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            EmailVerifications.update({
                EmailVerifications.email eq email and (EmailVerifications.status eq "pending")
            }) {
                it[status] = "expired"
            }
            EmailVerifications.insert {
                it[EmailVerifications.email] = email
                it[EmailVerifications.code] = code
                it[EmailVerifications.status] = "pending"
                it[EmailVerifications.expiresAt] = now + CODE_EXPIRY_MS
                it[EmailVerifications.createdAt] = now
            }
        }
        return code
    }

    suspend fun verifyCode(email: String, code: String): Boolean {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = EmailVerifications.selectAll()
                .where {
                    EmailVerifications.email eq email and
                        (EmailVerifications.code eq code) and
                        (EmailVerifications.status eq "pending")
                }
                .orderBy(EmailVerifications.createdAt to SortOrder.DESC)
                .firstOrNull() ?: return@newSuspendedTransaction false

            if (row[EmailVerifications.expiresAt] < now) {
                EmailVerifications.update({ EmailVerifications.id eq row[EmailVerifications.id] }) {
                    it[status] = "expired"
                }
                return@newSuspendedTransaction false
            }

            EmailVerifications.update({ EmailVerifications.id eq row[EmailVerifications.id] }) {
                it[status] = "used"
            }
            true
        }
    }

    // ── Account lifecycle ──

    data class AccountInfo(
        val token: String,
        val email: String,
        val llmCallsUsed: Int,
        val llmCallsLimit: Int,
    )

    suspend fun createOrRefresh(email: String, freeQuota: Int): AccountInfo {
        val token = generateToken()
        val tokenHash = sha256(token)
        val now = Instant.now().toEpochMilli()

        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val existing = Accounts.selectAll().where { Accounts.email eq email }.firstOrNull()
            if (existing != null) {
                Accounts.update({ Accounts.id eq existing[Accounts.id] }) {
                    it[Accounts.tokenHash] = tokenHash
                    it[Accounts.tokenPlain] = token
                    it[Accounts.status] = "active"
                    it[llmCallsUsed] = 0
                    it[llmCallsLimit] = freeQuota
                }
            } else {
                Accounts.insert {
                    it[Accounts.email] = email
                    it[Accounts.tokenHash] = tokenHash
                    it[Accounts.tokenPlain] = token
                    it[Accounts.status] = "active"
                    it[llmCallsUsed] = 0
                    it[llmCallsLimit] = freeQuota
                    it[createdAt] = now
                }
            }
        }
        return AccountInfo(token, email, 0, freeQuota)
    }

    // ── Account deletion（软删除 + 保留期清理）──

    /** 账号软删除后的保留期：90 天，期满由 purgeExpiredDeleted 物理清理。 */
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * 软删除当前 tokenHash 对应的 active 账号：
     * - status -> "deleted"，deleted_at 记录时间
     * - token_plain 清空（明文 token 不再需要）
     * - email 改写为 "deleted_<id>__<原email>"，释放 uniqueIndex(email)，
     *   使同邮箱可重新注册为全新账号
     * 返回是否命中 active 账号（false = tokenHash 无对应 active 账号，幂等）。
     */
    suspend fun softDelete(tokenHash: String): Boolean {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = Accounts.selectAll().where {
                Accounts.tokenHash eq tokenHash and (Accounts.status eq "active")
            }.firstOrNull() ?: return@newSuspendedTransaction false
            val id = row[Accounts.id]
            val origEmail = row[Accounts.email]
            Accounts.update({ Accounts.id eq id }) {
                it[status] = "deleted"
                it[deletedAt] = now
                it[tokenPlain] = ""
                it[email] = "deleted_${id}__${origEmail}"
            }
            true
        }
    }

    /**
     * 物理清理超过保留期的已软删账号 + 其 llm_call_log。
     * 在 server 启动时调用一次（见 Application.kt）；返回清理条数。
     */
    suspend fun purgeExpiredDeleted(retentionMs: Long): Int {
        val cutoff = Instant.now().toEpochMilli() - retentionMs
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val ids = Accounts.selectAll().where {
                (Accounts.status eq "deleted") and (Accounts.deletedAt less cutoff)
            }.map { it[Accounts.id] }
            // deleteWhere lambda 内需显式 SqlExpressionBuilder scope（见 ChannelRepository 既有写法）
            ids.forEach { id ->
                LlmCallLogs.deleteWhere { with(SqlExpressionBuilder) { LlmCallLogs.accountId eq id } }
                Accounts.deleteWhere { with(SqlExpressionBuilder) { Accounts.id eq id } }
            }
            ids.size
        }
    }

    // ── Auth check ──

    data class AuthResult(val valid: Boolean, val tokenHash: String? = null)

    suspend fun validateToken(rawToken: String): AuthResult {
        if (!isTokenFormat(rawToken)) return AuthResult(false)
        val hash = sha256(rawToken)
        val row = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            Accounts.selectAll().where {
                Accounts.tokenHash eq hash and (Accounts.status eq "active")
            }.firstOrNull()
        } ?: return AuthResult(false)
        return AuthResult(true, hash)
    }

    /** tokenHash → account.id；用于 llm_call_log 写入归属。 */
    suspend fun idForTokenHash(tokenHash: String): Int? {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            Accounts.selectAll().where { Accounts.tokenHash eq tokenHash }
                .firstOrNull()?.let { it[Accounts.id] }
        }
    }

    /** 取账户完整 token（仅供后台用户列表「复制」端点；空明文返回 null）。 */
    suspend fun rawToken(id: Int): String? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()
            ?.let { it[Accounts.tokenPlain].takeIf { plain -> plain.isNotEmpty() } }
    }

    // ── Quota ──

    data class QuotaInfo(val email: String, val llmCallsUsed: Int, val llmCallsLimit: Int)

    suspend fun getQuota(tokenHash: String): QuotaInfo? {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            Accounts.selectAll().where { Accounts.tokenHash eq tokenHash }.firstOrNull()?.let {
                QuotaInfo(
                    email = it[Accounts.email],
                    llmCallsUsed = it[Accounts.llmCallsUsed],
                    llmCallsLimit = it[Accounts.llmCallsLimit],
                )
            }
        }
    }

    suspend fun checkAndIncrementQuota(tokenHash: String): Boolean {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = Accounts.selectAll().where {
                Accounts.tokenHash eq tokenHash and (Accounts.status eq "active")
            }.firstOrNull() ?: return@newSuspendedTransaction false

            if (row[Accounts.llmCallsUsed] >= row[Accounts.llmCallsLimit]) {
                return@newSuspendedTransaction false
            }

            Accounts.update({ Accounts.id eq row[Accounts.id] }) {
                with(SqlExpressionBuilder) {
                    it[llmCallsUsed] = llmCallsUsed + 1
                }
            }
            true
        }
    }

    suspend fun revertQuota(tokenHash: String) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = Accounts.selectAll().where { Accounts.tokenHash eq tokenHash }.firstOrNull() ?: return@newSuspendedTransaction
            if (row[Accounts.llmCallsUsed] > 0) {
                Accounts.update({ Accounts.id eq row[Accounts.id] }) {
                    with(SqlExpressionBuilder) {
                        it[llmCallsUsed] = llmCallsUsed - 1
                    }
                }
            }
        }
    }
}
