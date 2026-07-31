package com.mamba.picme.server.diag

import com.mamba.picme.server.db.DiagJobs
import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

data class DiagJobRow(
    val id: Int,
    val ownerTokenHash: String,
    val status: DiagStatus,
    val description: String,
    val gitSha: String,
    val rootCause: String?,
    val fixMode: String?,
    val fixBranch: String?,
    val compareUrl: String?,
    val tested: Boolean,
    val workerLog: String?,
    val updatedAt: Long,
)

/** worker 领到的任务（phase 决定诊断还是修复）。 */
data class DiagClaim(
    val id: Int,
    val phase: String,        // "diagnose" | "fix"
    val description: String,
    val bundleJson: String,
    val gitSha: String,
    val rootCause: String?,   // 修复阶段带确认过的根因
    val fixMode: String?,     // 修复阶段带用户选的 mode
    val conversationSummary: String?, // 诊断澄清对话摘要（诊断阶段用）
    val suggestedFix: String?,        // 修复阶段带诊断给出的修复方向
)

object DiagService {

    suspend fun createJob(
        ownerTokenHash: String,
        deviceId: String?,
        description: String,
        bundleJson: String,
        gitSha: String,
        conversationSummary: String? = null,
    ): Int {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.insert {
                it[DiagJobs.ownerTokenHash] = ownerTokenHash
                it[DiagJobs.deviceId] = deviceId
                it[DiagJobs.description] = description
                it[DiagJobs.conversationSummary] = conversationSummary
                it[DiagJobs.bundleJson] = bundleJson
                it[DiagJobs.gitSha] = gitSha
                it[DiagJobs.status] = DiagStatus.QUEUED.name
                it[DiagJobs.createdAt] = now
                it[DiagJobs.updatedAt] = now
            }[DiagJobs.id]
        }
    }

    suspend fun getJob(id: Int, ownerTokenHash: String): DiagJobRow? {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.selectAll().where { DiagJobs.id eq id }.firstOrNull()?.let {
                if (it[DiagJobs.ownerTokenHash] != ownerTokenHash) return@let null
                DiagJobRow(
                    id = it[DiagJobs.id],
                    ownerTokenHash = it[DiagJobs.ownerTokenHash],
                    status = DiagStatus.valueOf(it[DiagJobs.status]),
                    description = it[DiagJobs.description],
                    gitSha = it[DiagJobs.gitSha],
                    rootCause = it[DiagJobs.rootCause],
                    fixMode = it[DiagJobs.fixMode],
                    fixBranch = it[DiagJobs.fixBranch],
                    compareUrl = it[DiagJobs.compareUrl],
                    tested = it[DiagJobs.tested] == 1,
                    workerLog = it[DiagJobs.workerLog],
                    updatedAt = it[DiagJobs.updatedAt],
                )
            }
        }
    }

    /**
     * 原子领取一个待处理任务：QUEUED → 诊断；FIX_REQUESTED → 修复。
     * 置 claimedAt；MVP 单 worker，不做悲观锁。
     */
    suspend fun claimNextJob(): DiagClaim? {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = DiagJobs.selectAll()
                .where {
                    (DiagJobs.status eq DiagStatus.QUEUED.name) or
                        (DiagJobs.status eq DiagStatus.FIX_REQUESTED.name)
                }
                .orderBy(DiagJobs.createdAt to SortOrder.ASC)
                .firstOrNull() ?: return@newSuspendedTransaction null
            val id = row[DiagJobs.id]
            val status = DiagStatus.valueOf(row[DiagJobs.status])
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[claimedAt] = now
                it[updatedAt] = now
            }
            DiagClaim(
                id = id,
                phase = if (status == DiagStatus.QUEUED) "diagnose" else "fix",
                description = row[DiagJobs.description],
                bundleJson = row[DiagJobs.bundleJson],
                gitSha = row[DiagJobs.gitSha],
                rootCause = row[DiagJobs.rootCause],
                fixMode = row[DiagJobs.fixMode],
                conversationSummary = row[DiagJobs.conversationSummary],
                suggestedFix = row[DiagJobs.suggestedFix],
            )
        }
    }

    /** 诊断阶段回传：成功→DIAGNOSED，失败→DIAGNOSE_FAILED。suggestedFix 供 fix 阶段 prompt 使用。 */
    suspend fun submitDiagnosis(id: Int, rootCause: String?, status: DiagStatus, error: String?, suggestedFix: String? = null) {
        require(status == DiagStatus.DIAGNOSED || status == DiagStatus.DIAGNOSE_FAILED) {
            "diagnose status must be DIAGNOSED or DIAGNOSE_FAILED"
        }
        val now = Instant.now().toEpochMilli()
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.update({ (DiagJobs.id eq id) and (DiagJobs.status eq DiagStatus.QUEUED.name) }) {
                it[DiagJobs.status] = status.name
                it[DiagJobs.rootCause] = rootCause
                it[DiagJobs.suggestedFix] = suggestedFix
                it[DiagJobs.workerLog] = error
                it[DiagJobs.updatedAt] = now
            }
        }
    }

    /** 用户确认 + 选 mode：仅 owner 且 DIAGNOSED 态可确认。返回是否成功转移。 */
    suspend fun confirmFix(id: Int, ownerTokenHash: String, mode: String): Boolean {
        require(mode == "push" || mode == "pr" || mode == "auto") { "mode must be push, pr or auto" }
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = DiagJobs.selectAll().where { DiagJobs.id eq id }.firstOrNull()
                ?: return@newSuspendedTransaction false
            if (row[DiagJobs.ownerTokenHash] != ownerTokenHash) return@newSuspendedTransaction false
            if (row[DiagJobs.status] != DiagStatus.DIAGNOSED.name) return@newSuspendedTransaction false
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = DiagStatus.FIX_REQUESTED.name
                it[DiagJobs.fixMode] = mode
                it[DiagJobs.updatedAt] = now
            }
            true
        }
    }

    /** 修复阶段回传：FIXED / FIXED_UNVERIFIED / FIX_FAILED。 */
    suspend fun submitFix(
        id: Int,
        status: DiagStatus,
        fixBranch: String?,
        compareUrl: String?,
        tested: Boolean,
        error: String?,
    ) {
        require(status == DiagStatus.FIXED || status == DiagStatus.FIXED_UNVERIFIED || status == DiagStatus.FIX_FAILED) {
            "fix status must be FIXED, FIXED_UNVERIFIED or FIX_FAILED"
        }
        val now = Instant.now().toEpochMilli()
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.update({ (DiagJobs.id eq id) and (DiagJobs.status eq DiagStatus.FIX_REQUESTED.name) }) {
                it[DiagJobs.status] = status.name
                it[DiagJobs.fixBranch] = fixBranch
                it[DiagJobs.compareUrl] = compareUrl
                it[DiagJobs.tested] = if (tested) 1 else 0
                it[DiagJobs.workerLog] = error
                it[DiagJobs.updatedAt] = now
            }
        }
    }

    /**
     * S1 任务回收 sweeper（Application 启动的周期协程调用）：
     * - 整体超时：非终态（QUEUED/DIAGNOSED/FIX_REQUESTED）超 [overallTimeoutMs] 未更新 → TIMED_OUT。
     * - 领取回收：QUEUED/FIX_REQUESTED 且 claimedAt 超 [claimTimeoutMs] 未更新 → claimedAt 置空，重新可领。
     *   15min > worker 侧 DIAG_PHASE_TIMEOUT（≤600s），正常执行不会误回收，无需心跳。
     * 返回 (领取回收数, 整体超时数)。
     */
    suspend fun sweepStaleJobs(
        nowMs: Long,
        claimTimeoutMs: Long = 15 * 60_000L,
        overallTimeoutMs: Long = 60 * 60_000L,
    ): Pair<Int, Int> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val nonTerminal = listOf(
            DiagStatus.QUEUED.name, DiagStatus.DIAGNOSED.name, DiagStatus.FIX_REQUESTED.name,
        )
        val timedOut = DiagJobs.update({
            (DiagJobs.status inList nonTerminal) and (DiagJobs.updatedAt less nowMs - overallTimeoutMs)
        }) {
            it[DiagJobs.status] = DiagStatus.TIMED_OUT.name
            it[DiagJobs.updatedAt] = nowMs
        }
        val claimable = listOf(DiagStatus.QUEUED.name, DiagStatus.FIX_REQUESTED.name)
        // claimedAt 为 NULL 的行在 SQL 比较中自然被排除（NULL < x 为 UNKNOWN），无需显式 isNotNull
        val reclaimed = DiagJobs.update({
            (DiagJobs.status inList claimable) and
                (DiagJobs.claimedAt less nowMs - claimTimeoutMs)
        }) {
            it[DiagJobs.claimedAt] = null
            it[DiagJobs.updatedAt] = nowMs
        }
        reclaimed to timedOut
    }

    /** 管理后台「删除」：物理删除任务记录（不可恢复）。 */
    suspend fun deleteById(id: Int) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.deleteWhere { with(SqlExpressionBuilder) { DiagJobs.id eq id } }
        }
    }

    /** 管理后台「废弃」：标记 ARCHIVED，worker 不再领取；任意源态允许。 */
    suspend fun archive(id: Int) {
        val now = Instant.now().toEpochMilli()
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = DiagStatus.ARCHIVED.name
                it[DiagJobs.updatedAt] = now
            }
        }
    }

    /**
     * 管理后台「激活」：把停摆的任务（ARCHIVED / 失败 / 超时 / 已修复 / 待确认 等）
     * 重置为 QUEUED 并清空已有产出，让 worker 从头重跑诊断。
     * 拒绝 QUEUED（本就在队列）与 FIX_REQUESTED（worker 正在修，避免 race）。返回是否转移成功。
     */
    suspend fun activate(id: Int): Boolean {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = DiagJobs.selectAll().where { DiagJobs.id eq id }.firstOrNull()
                ?: return@newSuspendedTransaction false
            val current = DiagStatus.valueOf(row[DiagJobs.status])
            if (current == DiagStatus.QUEUED || current == DiagStatus.FIX_REQUESTED) {
                return@newSuspendedTransaction false
            }
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = DiagStatus.QUEUED.name
                it[DiagJobs.rootCause] = null
                it[DiagJobs.suggestedFix] = null
                it[DiagJobs.fixMode] = null
                it[DiagJobs.fixBranch] = null
                it[DiagJobs.compareUrl] = null
                it[DiagJobs.workerLog] = null
                it[DiagJobs.tested] = 0
                it[DiagJobs.claimedAt] = null
                it[DiagJobs.updatedAt] = now
            }
            true
        }
    }
}
