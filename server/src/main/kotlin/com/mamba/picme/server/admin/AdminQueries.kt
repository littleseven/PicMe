package com.mamba.picme.server.admin

import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.ApkUploads
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.ZoneOffset

// ── DTO ──────────────────────────────────────────────

data class OverviewRow(
    val totalUsers: Long,
    val newUsersToday: Long,
    val callsToday: Long,
    val tokensToday: Long,
    val costToday: Double,
    val bytesToday: Long,
    val blockedToday: Long,
)

data class DayBucket(
    val day: String, // YYYY-MM-DD (UTC)
    val calls: Long,
    val blocked: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cost: Double,
    val bytes: Long,
)

data class UserRow(
    val id: Int,
    val email: String,
    val status: String,
    val createdAt: Long,
    val calls: Long,
    val totalTokens: Long,
    val cost: Double,
    val lastActive: Long?,
    val apiTokenMasked: String,
    val hasToken: Boolean,
    val deviceIdMasked: String,
)

data class UserDetail(
    val id: Int,
    val email: String,
    val status: String,
    val createdAt: Long,
    val calls: Long,
    val totalTokens: Long,
    val cost: Double,
    val blocked: Long,
    val bytes: Long,
    val lastActive: Long?,
)

data class CallRow(
    val id: Long,
    val model: String,
    val provider: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val costCny: Double,
    val respBytes: Int,
    val status: String,
    val latencyMs: Int?,
    val createdAt: Long,
)

data class DeviceRow(
    val id: Int,
    val deviceIdMasked: String,
    val llmCallsUsed: Int,
    val createdAt: Long,
    val lastSeenAt: Long,
)

// ── Queries（自然日按 UTC；内部后台够用。聚合在内存做，trial 规模毫秒级）──

object AdminQueries {
    private const val DAY_MS = 86_400_000L

    private fun startOfTodayMs(now: Long): Long = now - (now % DAY_MS)

    private fun epochDay(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

    suspend fun overview(now: Long): OverviewRow = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val startToday = startOfTodayMs(now)
        val totalUsers = Accounts.selectAll().count()
        val newToday = Accounts.selectAll().where { Accounts.createdAt greaterEq startToday }.count()
        var calls = 0L
        var blocked = 0L
        var tokens = 0L
        var cost = 0.0
        var bytes = 0L
        LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq startToday }.forEach { r ->
            val s = r[LlmCallLogs.status]
            if (s == "ok") calls += 1
            if (s.startsWith("blocked_")) blocked += 1
            tokens += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            cost += r[LlmCallLogs.costCny]
            bytes += r[LlmCallLogs.respBytes].toLong()
        }
        OverviewRow(totalUsers, newToday, calls, tokens, cost, bytes, blocked)
    }

    suspend fun dailySeries(days: Int, now: Long): List<DayBucket> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val startToday = startOfTodayMs(now)
            val since = startToday - (days - 1) * DAY_MS
            val acc = LinkedHashMap<String, DayAcc>()
            for (i in 0 until days) {
                val dayStart = startToday - (days - 1 - i) * DAY_MS
                acc[epochDay(dayStart)] = DayAcc()
            }
            LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq since }.forEach { r ->
                val a = acc[epochDay(r[LlmCallLogs.createdAt])] ?: return@forEach
                val status = r[LlmCallLogs.status]
                if (status == "ok") a.calls += 1L
                if (status.startsWith("blocked_")) a.blocked += 1L
                a.promptTokens += r[LlmCallLogs.promptTokens]?.toLong() ?: 0L
                a.completionTokens += r[LlmCallLogs.completionTokens]?.toLong() ?: 0L
                a.totalTokens += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
                a.cost += r[LlmCallLogs.costCny]
                a.bytes += r[LlmCallLogs.respBytes].toLong()
            }
            acc.map { (day, a) ->
                DayBucket(day, a.calls, a.blocked, a.promptTokens, a.completionTokens, a.totalTokens, a.cost, a.bytes)
            }
        }

    suspend fun usersList(): List<UserRow> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val calls = HashMap<Int, Long>()
        val tokens = HashMap<Int, Long>()
        val cost = HashMap<Int, Double>()
        val lastDevTime = HashMap<Int, Long>()
        val lastDeviceId = HashMap<Int, String>()
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            val id = r[LlmCallLogs.accountId]
            calls[id] = (calls[id] ?: 0L) + 1
            tokens[id] = (tokens[id] ?: 0L) + (r[LlmCallLogs.totalTokens]?.toLong() ?: 0L)
            cost[id] = (cost[id] ?: 0.0) + r[LlmCallLogs.costCny]
            val dev = r[LlmCallLogs.deviceId]
            if (dev != null) {
                val t = r[LlmCallLogs.createdAt]
                if (lastDevTime[id]?.let { t > it } != false) {
                    lastDevTime[id] = t
                    lastDeviceId[id] = dev
                }
            }
        }
        val lastActive = HashMap<Int, Long>()
        LlmCallLogs.selectAll().forEach { r ->
            val id = r[LlmCallLogs.accountId]
            val t = r[LlmCallLogs.createdAt]
            val cur = lastActive[id]
            if (cur == null || t > cur) lastActive[id] = t
        }
        Accounts.selectAll().orderBy(Accounts.createdAt to SortOrder.DESC).map { a ->
            val id = a[Accounts.id]
            UserRow(
                id = id,
                email = a[Accounts.email],
                status = a[Accounts.status],
                createdAt = a[Accounts.createdAt],
                calls = calls[id] ?: 0L,
                totalTokens = tokens[id] ?: 0L,
                cost = cost[id] ?: 0.0,
                lastActive = lastActive[id],
                apiTokenMasked = maskToken(a[Accounts.tokenPlain]),
                hasToken = a[Accounts.tokenPlain].isNotEmpty(),
                deviceIdMasked = lastDeviceId[id]?.let { maskDeviceId(it) } ?: "—",
            )
        }
    }

    suspend fun userDetail(id: Int): UserDetail? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val acc = Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction null
        var calls = 0L
        var blocked = 0L
        var tokens = 0L
        var cost = 0.0
        var bytes = 0L
        var lastActive: Long? = null
        LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq id }.forEach { r ->
            val s = r[LlmCallLogs.status]
            if (s == "ok") calls += 1
            if (s.startsWith("blocked_")) blocked += 1
            tokens += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            cost += r[LlmCallLogs.costCny]
            bytes += r[LlmCallLogs.respBytes].toLong()
            val t = r[LlmCallLogs.createdAt]
            if (lastActive == null || t > lastActive!!) lastActive = t
        }
        UserDetail(
            id = id,
            email = acc[Accounts.email],
            status = acc[Accounts.status],
            createdAt = acc[Accounts.createdAt],
            calls = calls,
            totalTokens = tokens,
            cost = cost,
            blocked = blocked,
            bytes = bytes,
            lastActive = lastActive,
        )
    }

    suspend fun recentCalls(accountId: Int, limit: Int): List<CallRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq accountId }
                .orderBy(LlmCallLogs.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { r ->
                    CallRow(
                        id = r[LlmCallLogs.id],
                        model = r[LlmCallLogs.model],
                        provider = r[LlmCallLogs.provider],
                        promptTokens = r[LlmCallLogs.promptTokens],
                        completionTokens = r[LlmCallLogs.completionTokens],
                        totalTokens = r[LlmCallLogs.totalTokens],
                        costCny = r[LlmCallLogs.costCny],
                        respBytes = r[LlmCallLogs.respBytes],
                        status = r[LlmCallLogs.status],
                        latencyMs = r[LlmCallLogs.latencyMs],
                        createdAt = r[LlmCallLogs.createdAt],
                    )
                }
        }

    suspend fun devicesList(limit: Int = 1000): List<DeviceRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.selectAll()
                .orderBy(AnonymousDevices.lastSeenAt to SortOrder.DESC)
                .limit(limit)
                .map { r ->
                    DeviceRow(
                        id = r[AnonymousDevices.id],
                        deviceIdMasked = maskDeviceId(r[AnonymousDevices.deviceId]),
                        llmCallsUsed = r[AnonymousDevices.llmCallsUsed],
                        createdAt = r[AnonymousDevices.createdAt],
                        lastSeenAt = r[AnonymousDevices.lastSeenAt],
                    )
                }
        }

    suspend fun deviceRawId(id: Int): String? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AnonymousDevices.selectAll().where { AnonymousDevices.id eq id }
            .firstOrNull()?.get(AnonymousDevices.deviceId)
    }

    suspend fun usersCount(): Long = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        Accounts.selectAll().count()
    }

    suspend fun devicesCount(): Long = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AnonymousDevices.selectAll().count()
    }

    private class DayAcc {
        var calls = 0L
        var blocked = 0L
        var promptTokens = 0L
        var completionTokens = 0L
        var totalTokens = 0L
        var cost = 0.0
        var bytes = 0L
    }

    data class ApkUploadRow(
        val id: Int,
        val version: String,
        val fileName: String,
        val fileSize: Long,
        val status: String,
        val message: String?,
        val createdAt: Long,
    )

    suspend fun apkUploadHistory(limit: Int = 50): List<ApkUploadRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            ApkUploads.selectAll()
                .orderBy(ApkUploads.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { r ->
                    ApkUploadRow(
                        id = r[ApkUploads.id],
                        version = r[ApkUploads.version],
                        fileName = r[ApkUploads.fileName],
                        fileSize = r[ApkUploads.fileSize],
                        status = r[ApkUploads.status],
                        message = r[ApkUploads.message],
                        createdAt = r[ApkUploads.createdAt],
                    )
                }
        }

    /** 与 ChannelRepository.maskToken 同形：前4+••••+后4 便于辨认；空 → 「—」。 */
    private fun maskToken(token: String): String = when {
        token.isEmpty() -> "—"
        token.length <= 8 -> "••••" + token.takeLast(4)
        else -> token.take(4) + "••••" + token.takeLast(4)
    }

    /** device_id 掩码:前 6 + •••• + 后 4;长度 ≤ 10 时只露后 4。与 [maskToken] 同形。 */
    private fun maskDeviceId(deviceId: String): String = when {
        deviceId.length <= 10 -> "••••" + deviceId.takeLast(4)
        else -> deviceId.take(6) + "••••" + deviceId.takeLast(4)
    }
}
