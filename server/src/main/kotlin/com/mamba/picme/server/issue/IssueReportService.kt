package com.mamba.picme.server.issue

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.ReportedIssues
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * 用户上报问题服务：存库 + 自动同步 GitHub issue（失败不阻塞）。
 */
class IssueReportService(private val github: GitHubIssueClient) {

    data class IssueRow(
        val id: Int,
        val category: String,
        val title: String,
        val description: String,
        val status: String,
        val githubIssueNumber: Int?,
        val githubIssueUrl: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /**
     * 提交问题：
     * 1. 对 title/description 脱敏；
     * 2. 入库；
     * 3. 异步创建 GitHub issue，成功后回写 issue number/url。
     */
    suspend fun submit(
        reporterAccountId: Int,
        reporterEmail: String,
        issueCategory: String,
        issueTitle: String,
        issueDescription: String,
    ): Int {
        val safeTitle = IssueSanitizer.sanitize(issueTitle.trim()).take(256)
        val safeDesc = IssueSanitizer.sanitize(issueDescription.trim())
        val safeCategory = issueCategory.trim().lowercase().take(32)
        val now = Instant.now().toEpochMilli()

        val id = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            ReportedIssues.insertAndGetId {
                it[ReportedIssues.accountId] = reporterAccountId
                it[ReportedIssues.reporterEmail] = reporterEmail.lowercase().trim()
                it[ReportedIssues.category] = safeCategory
                it[ReportedIssues.title] = safeTitle
                it[ReportedIssues.description] = safeDesc
                it[ReportedIssues.status] = "open"
                it[ReportedIssues.githubIssueNumber] = null
                it[ReportedIssues.githubIssueUrl] = ""
                it[ReportedIssues.sanitized] = 1
                it[ReportedIssues.createdAt] = now
                it[ReportedIssues.updatedAt] = now
            }.value
        }

        // 入库后再异步同步 GitHub，失败不阻塞用户响应。
        syncToGithub(id, safeTitle, safeDesc)
        return id
    }

    /** 列出上报记录，按时间倒序。 */
    suspend fun list(statusFilter: String? = null, limit: Int = 100, offset: Long = 0): List<IssueRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val query = ReportedIssues.selectAll()
                .orderBy(ReportedIssues.createdAt to SortOrder.DESC)
                .limit(limit, offset)
            statusFilter?.let { filter ->
                query.adjustWhere { with(SqlExpressionBuilder) { ReportedIssues.status eq filter } }
            }
            query.map { row ->
                IssueRow(
                    id = row[ReportedIssues.id].value,
                    category = row[ReportedIssues.category],
                    title = row[ReportedIssues.title],
                    description = row[ReportedIssues.description],
                    status = row[ReportedIssues.status],
                    githubIssueNumber = row[ReportedIssues.githubIssueNumber],
                    githubIssueUrl = row[ReportedIssues.githubIssueUrl],
                    createdAt = row[ReportedIssues.createdAt],
                    updatedAt = row[ReportedIssues.updatedAt],
                )
            }
        }

    /** 后台更新状态。 */
    suspend fun updateStatus(id: Int, newStatus: String): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        require(newStatus in ALLOWED_STATUSES) { "invalid status: $newStatus" }
        val rows = ReportedIssues.update({ with(SqlExpressionBuilder) { ReportedIssues.id eq id } }) {
            it[ReportedIssues.status] = newStatus
            it[ReportedIssues.updatedAt] = Instant.now().toEpochMilli()
        }
        rows > 0
    }

    /** 同步到 GitHub 并回写结果；可被后台「重试同步」复用。 */
    suspend fun syncToGithub(issueId: Int, title: String, description: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val body = buildGithubBody(issueId, title, description)
            val result = github.createIssue(title, body)
            if (result.success) {
                ReportedIssues.update({ with(SqlExpressionBuilder) { ReportedIssues.id eq issueId } }) {
                    it[ReportedIssues.githubIssueNumber] = result.number
                    it[ReportedIssues.githubIssueUrl] = result.url
                    it[ReportedIssues.updatedAt] = Instant.now().toEpochMilli()
                }
                true
            } else {
                false
            }
        }

    private fun buildGithubBody(issueId: Int, title: String, description: String): String {
        return buildString {
            appendLine("**用户描述**")
            appendLine(description)
            appendLine()
            appendLine("**类别**: 问题上报")
            appendLine("**内部 issue ID**: #$issueId")
            appendLine()
            appendLine("_本 issue 内容由用户上报，已做隐私脱敏处理。_")
        }
    }

    companion object {
        val ALLOWED_STATUSES = setOf("open", "investigating", "closed", "ignored")
    }
}
