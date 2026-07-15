package com.mamba.picme.server.auth

import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * 设备级匿名访客试用额度。与 [AccountService] 的账号额度同构（check/increment/revert），
 * 但按 deviceId 计量、limit 由服务端配置（非每行）。访客调用不写 llm_call_log，
 * 其用量以本表为唯一事实源。
 */
object GuestService {

    data class GuestQuotaResult(val allowed: Boolean, val remaining: Int)

    suspend fun checkAndIncrementQuota(deviceId: String, limit: Int): GuestQuotaResult {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = AnonymousDevices.selectAll()
                .where { AnonymousDevices.deviceId eq deviceId }
                .firstOrNull()

            if (row == null) {
                if (limit <= 0) return@newSuspendedTransaction GuestQuotaResult(false, 0)
                AnonymousDevices.insert {
                    it[AnonymousDevices.deviceId] = deviceId
                    it[AnonymousDevices.llmCallsUsed] = 1
                    it[AnonymousDevices.createdAt] = now
                    it[AnonymousDevices.lastSeenAt] = now
                }
                GuestQuotaResult(true, (limit - 1).coerceAtLeast(0))
            } else {
                val used = row[AnonymousDevices.llmCallsUsed]
                if (used >= limit) {
                    AnonymousDevices.update({ AnonymousDevices.id eq row[AnonymousDevices.id] }) {
                        it[AnonymousDevices.lastSeenAt] = now
                    }
                    GuestQuotaResult(false, 0)
                } else {
                    AnonymousDevices.update({ AnonymousDevices.id eq row[AnonymousDevices.id] }) {
                        with(SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed + 1 }
                        it[AnonymousDevices.lastSeenAt] = now
                    }
                    GuestQuotaResult(true, (limit - used - 1).coerceAtLeast(0))
                }
            }
        }
    }

    suspend fun revertQuota(deviceId: String) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = AnonymousDevices.selectAll()
                .where { AnonymousDevices.deviceId eq deviceId }
                .firstOrNull() ?: return@newSuspendedTransaction
            if (row[AnonymousDevices.llmCallsUsed] > 0) {
                AnonymousDevices.update({ AnonymousDevices.id eq row[AnonymousDevices.id] }) {
                    with(SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed - 1 }
                }
            }
        }
    }

    /** 读取剩余额度（不增量），用于 X-Guest-Remaining 响应头。 */
    suspend fun remainingReadOnly(deviceId: String, limit: Int): Int {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val used = AnonymousDevices.selectAll()
                .where { AnonymousDevices.deviceId eq deviceId }
                .firstOrNull()?.get(AnonymousDevices.llmCallsUsed) ?: 0
            (limit - used).coerceAtLeast(0)
        }
    }
}
