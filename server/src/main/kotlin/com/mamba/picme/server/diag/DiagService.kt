package com.mamba.picme.server.diag

import com.mamba.picme.server.db.DiagJobs
import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
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
)

object DiagService {

    suspend fun createJob(
        ownerTokenHash: String,
        deviceId: String?,
        description: String,
        bundleJson: String,
        gitSha: String,
    ): Int {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.insert {
                it[DiagJobs.ownerTokenHash] = ownerTokenHash
                it[DiagJobs.deviceId] = deviceId
                it[DiagJobs.description] = description
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
                )
            }
        }
    }
}
