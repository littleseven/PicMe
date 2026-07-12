package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.EmailVerifications
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

    const val TOKEN_PREFIX = "picme_at_"
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
                    it[Accounts.status] = "active"
                    it[llmCallsUsed] = 0
                    it[llmCallsLimit] = freeQuota
                }
            } else {
                Accounts.insert {
                    it[Accounts.email] = email
                    it[Accounts.tokenHash] = tokenHash
                    it[Accounts.status] = "active"
                    it[llmCallsUsed] = 0
                    it[llmCallsLimit] = freeQuota
                    it[createdAt] = now
                }
            }
        }
        return AccountInfo(token, email, 0, freeQuota)
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
