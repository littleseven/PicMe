package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.DIAG_WORKER_TOKEN_HEADER
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.DiagJobs
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagRouteTest {

    private val workerToken = "w-secret"

    /** 每个用例：建临时库（默认 DiagJobs，可追加 Accounts 等）+ 装 ContentNegotiation + 注册 diagRoute。 */
    private fun TestApplicationBuilder.diagApp(vararg extra: Table) {
        TestDb.init(DiagJobs, *extra)
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { diagRoute(workerToken) }
        }
    }

    private fun jsonField(text: String, key: String): String =
        appJson.parseToJsonElement(text).jsonObject[key]!!.jsonPrimitive.content

    @Test
    fun `worker GET rejects missing worker token`() = testApplication {
        diagApp()
        val resp = client.get("/diag/work/jobs")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `worker GET returns 204 when queue empty`() = testApplication {
        diagApp()
        val resp = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }
        assertEquals(HttpStatusCode.NoContent, resp.status)
    }

    @Test
    fun `worker POST result without token is unauthorized`() = testApplication {
        diagApp()
        val resp = client.post("/diag/work/jobs/1/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"phase":"diagnose","status":"DIAGNOSED","rootCause":"x"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `worker POST result rejects unknown phase`() = testApplication {
        diagApp()
        val resp = client.post("/diag/work/jobs/1/result") {
            header(DIAG_WORKER_TOKEN_HEADER, workerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"phase":"bogus","status":"DIAGNOSED"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("unknown phase"))
    }

    @Test
    fun `phone report without token is unauthorized`() = testApplication {
        diagApp()
        val resp = client.post("/diag/report") {
            contentType(ContentType.Application.Json)
            setBody("""{"description":"crash","bundle":{"logs":"x","gitSha":"sha1"}}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `full flow report diagnose confirm fix`() = testApplication {
        diagApp(Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }

        // 1) phone reports
        val report = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"crash on open gallery","bundle":{"logs":"PoLang:Gallery boom","gitSha":"sha1"}}""")
        }
        assertEquals(HttpStatusCode.OK, report.status)
        val jobId = appJson.parseToJsonElement(report.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.int

        // 2) worker claims (diagnose) + posts diagnosis
        val claim = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }
        assertEquals(HttpStatusCode.OK, claim.status)
        assertEquals("diagnose", jsonField(claim.bodyAsText(), "phase"))
        val diag = client.post("/diag/work/jobs/$jobId/result") {
            header(DIAG_WORKER_TOKEN_HEADER, workerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"phase":"diagnose","status":"DIAGNOSED","rootCause":"NPE GalleryScreen.kt:88"}""")
        }
        assertEquals(HttpStatusCode.OK, diag.status)

        // 3) phone reads DIAGNOSED + root cause
        val s1 = client.get("/diag/jobs/$jobId") { header(APP_TOKEN_HEADER, token) }.bodyAsText()
        assertEquals("DIAGNOSED", jsonField(s1, "status"))
        assertEquals("NPE GalleryScreen.kt:88", jsonField(s1, "rootCause"))

        // 4) phone confirms (push)
        val confirm = client.post("/diag/jobs/$jobId/confirm") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"push"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm.status)

        // 5) worker claims (fix) + posts FIXED
        val claim2 = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }.bodyAsText()
        assertEquals("fix", jsonField(claim2, "phase"))
        client.post("/diag/work/jobs/$jobId/result") {
            header(DIAG_WORKER_TOKEN_HEADER, workerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"phase":"fix","status":"FIXED","fixBranch":"diag-fix/$jobId","tested":true}""")
        }

        // 6) phone reads FIXED
        val s2 = appJson.parseToJsonElement(
            client.get("/diag/jobs/$jobId") { header(APP_TOKEN_HEADER, token) }.bodyAsText(),
        ).jsonObject
        assertEquals("FIXED", s2["status"]!!.jsonPrimitive.content)
        assertEquals("diag-fix/$jobId", s2["fixBranch"]!!.jsonPrimitive.content)
        assertTrue(s2["tested"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `phone cannot read another owners job`() = testApplication {
        diagApp(Accounts)
        val tokenA = runBlocking { AccountService.createOrRefresh("a@x.com", 100).token }
        val tokenB = runBlocking { AccountService.createOrRefresh("b@x.com", 100).token }

        val report = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, tokenA)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"d","bundle":{"gitSha":"s"}}""")
        }
        val jobId = appJson.parseToJsonElement(report.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.int

        val resp = client.get("/diag/jobs/$jobId") { header(APP_TOKEN_HEADER, tokenB) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `report stores conversationSummary and diagnose claim exposes it`() = testApplication {
        diagApp(Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val report = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"crash on open","conversationSummary":"现象: 打开相册崩溃","bundle":{"gitSha":"s"}}""")
        }
        assertEquals(HttpStatusCode.OK, report.status)
        val claim = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }.bodyAsText()
        assertTrue(jsonField(claim, "conversationSummary").contains("打开相册崩溃"))
    }

    @Test
    fun `suggestedFix from diagnose result reaches fix claim`() = testApplication {
        diagApp(Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val report = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"crash","bundle":{"gitSha":"s"}}""")
        }
        val jobId = appJson.parseToJsonElement(report.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.int
        client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }
        client.post("/diag/work/jobs/$jobId/result") {
            header(DIAG_WORKER_TOKEN_HEADER, workerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"phase":"diagnose","status":"DIAGNOSED","rootCause":"rc","suspectFiles":"GalleryScreen.kt:88","suggestedFix":"null check before use"}""")
        }
        client.post("/diag/jobs/$jobId/confirm") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"push"}""")
        }
        val fixClaim = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }.bodyAsText()
        assertEquals("fix", jsonField(fixClaim, "phase"))
        assertEquals("null check before use", jsonField(fixClaim, "suggestedFix"))
    }

    @Test
    fun `report without conversationSummary stays accepted (backward compatible)`() = testApplication {
        diagApp(Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val report = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"old client report","bundle":{"gitSha":"s"}}""")
        }
        assertEquals(HttpStatusCode.OK, report.status)
    }
}
