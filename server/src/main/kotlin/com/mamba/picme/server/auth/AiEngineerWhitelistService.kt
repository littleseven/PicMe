package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

/**
 * AI 工程师模式账号白名单。
 *
 * 设计语义：白名单是「显式授权」——空表时没有任何账号能使用 AI 工程师模式；
 * 管理员在后台把用户邮箱加入白名单后才放行。
 */
object AiEngineerWhitelistService {

    data class Entry(val id: Int, val email: String, val createdAt: Long)

    /** 邮箱是否在白名单内（大小写不敏感；入库时已小写）。 */
    suspend fun isAllowed(email: String): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AiEngineerWhitelists.selectAll()
            .where { AiEngineerWhitelists.email eq email.lowercase().trim() }
            .firstOrNull() != null
    }

    /** tokenHash 对应的账号是否在白名单内；找不到账号 → false。 */
    suspend fun isAllowedByTokenHash(tokenHash: String): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val email = Accounts.selectAll().where { Accounts.tokenHash eq tokenHash }
            .firstOrNull()?.let { it[Accounts.email] }
            ?: return@newSuspendedTransaction false
        AiEngineerWhitelists.selectAll()
            .where { AiEngineerWhitelists.email eq email.lowercase().trim() }
            .firstOrNull() != null
    }

    /** 把邮箱加入白名单；已存在则返回 false。 */
    suspend fun allow(email: String): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val normalized = email.lowercase().trim()
        val exists = AiEngineerWhitelists.selectAll()
            .where { AiEngineerWhitelists.email eq normalized }
            .firstOrNull() != null
        if (exists) return@newSuspendedTransaction false
        AiEngineerWhitelists.insert {
            it[AiEngineerWhitelists.email] = normalized
            it[createdAt] = Instant.now().toEpochMilli()
        }
        true
    }

    /** 从白名单移除邮箱；不存在则返回 false。 */
    suspend fun revoke(email: String): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val normalized = email.lowercase().trim()
        val deleted = AiEngineerWhitelists.deleteWhere {
            with(SqlExpressionBuilder) { AiEngineerWhitelists.email eq normalized }
        }
        deleted > 0
    }

    /** 分页列出白名单（默认按加入时间倒序）。 */
    suspend fun list(limit: Int = 1000): List<Entry> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AiEngineerWhitelists.selectAll()
            .orderBy(AiEngineerWhitelists.createdAt to SortOrder.DESC)
            .limit(limit)
            .map {
                Entry(
                    id = it[AiEngineerWhitelists.id],
                    email = it[AiEngineerWhitelists.email],
                    createdAt = it[AiEngineerWhitelists.createdAt],
                )
            }
    }
}
