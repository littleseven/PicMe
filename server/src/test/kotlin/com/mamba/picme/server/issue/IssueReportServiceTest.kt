package com.mamba.picme.server.issue

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.ReportedIssues
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportServiceTest {

    private fun service(number: Int? = 42): IssueReportService {
        val body = if (number != null) {
            """{"number":$number,"html_url":"https://github.com/o/r/issues/$number"}"""
        } else ""
        val engine = MockEngine {
            respond(body, if (number != null) HttpStatusCode.Created else HttpStatusCode.BadRequest)
        }
        val github = GitHubIssueClient(HttpClient(engine), "tok", "o/r")
        return IssueReportService(github)
    }

    @Test
    fun `submit 存库并脱敏`() = runBlocking {
        TestDb.init(ReportedIssues)
        val svc = service()
        val id = svc.submit(1, "User@X.COM", "crash", "联系 user@x.com", "token pl-1234567890abcdef 在 /storage/1.jpg")

        val row = transaction(Db.instance) {
            ReportedIssues.selectAll().where { ReportedIssues.id eq id }.firstOrNull()
        }
        requireNotNull(row)
        assertEquals("user@x.com", row[ReportedIssues.reporterEmail])
        assertEquals("crash", row[ReportedIssues.category])
        assertEquals("联系 <email>", row[ReportedIssues.title])
        assertTrue(row[ReportedIssues.description].contains("<token>"))
        assertTrue(row[ReportedIssues.description].contains("<path>"))
        assertEquals("open", row[ReportedIssues.status])
    }

    @Test
    fun `submit 成功后回写 github issue 信息`() = runBlocking {
        TestDb.init(ReportedIssues)
        val svc = service(number = 7)
        val id = svc.submit(1, "u@x.com", "bug", "title", "desc")

        // 给 MockEngine 协程一点时间回写
        Thread.sleep(100)

        val row = transaction(Db.instance) {
            ReportedIssues.selectAll().where { ReportedIssues.id eq id }.firstOrNull()
        }
        requireNotNull(row)
        assertEquals(7, row[ReportedIssues.githubIssueNumber])
        assertEquals("https://github.com/o/r/issues/7", row[ReportedIssues.githubIssueUrl])
    }

    @Test
    fun `submit github 失败仍返回 id 但不回写`() = runBlocking {
        TestDb.init(ReportedIssues)
        val svc = service(number = null)
        val id = svc.submit(1, "u@x.com", "bug", "title", "desc")

        Thread.sleep(50)

        val row = transaction(Db.instance) {
            ReportedIssues.selectAll().where { ReportedIssues.id eq id }.firstOrNull()
        }
        requireNotNull(row)
        assertNull(row[ReportedIssues.githubIssueNumber])
        assertEquals("", row[ReportedIssues.githubIssueUrl])
    }

    @Test
    fun `list 按时间倒序并可按状态过滤`() = runBlocking {
        TestDb.init(ReportedIssues)
        val svc = service()
        svc.submit(1, "a@x.com", "bug", "t1", "d1")
        svc.submit(1, "a@x.com", "feature", "t2", "d2")
        svc.updateStatus(1, "closed")

        val all = svc.list()
        assertEquals(2, all.size)
        assertEquals("t2", all[0].title)

        val openOnly = svc.list(statusFilter = "open")
        assertEquals(1, openOnly.size)
        assertEquals("t2", openOnly[0].title)
    }

    @Test
    fun `updateStatus 成功`() = runBlocking {
        TestDb.init(ReportedIssues)
        val svc = service()
        val id = svc.submit(1, "u@x.com", "bug", "t", "d")
        assertTrue(svc.updateStatus(id, "investigating"))
        val row = svc.list().first()
        assertEquals("investigating", row.status)
    }

    @Test
    fun `updateStatus 非法状态抛异常`() = runBlocking {
        TestDb.init(ReportedIssues)
        val svc = service()
        val id = svc.submit(1, "u@x.com", "bug", "t", "d")
        var thrown = false
        try {
            svc.updateStatus(id, "bad")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
