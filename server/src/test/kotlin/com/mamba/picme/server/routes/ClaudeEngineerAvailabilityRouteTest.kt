package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.AiEngineerWhitelistService
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeEngineerAvailabilityRouteTest {

    @Test
    fun `无 token 返回 401`() = testApplication {
        TestDb.init(Accounts, AiEngineerWhitelists)
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { claudeEngineerAvailabilityRoute() }
        }
        val resp = client.get("/v1/claude-engineer/available")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `未在白名单返回 available false`() = testApplication {
        TestDb.init(Accounts, AiEngineerWhitelists)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        application {
            install(ContentNegotiation) { json(appJson) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { claudeEngineerAvailabilityRoute() }
        }
        val resp = client.get("/v1/claude-engineer/available") {
            header(APP_TOKEN_HEADER, token)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"available\":false"))
    }

    @Test
    fun `在白名单返回 available true`() = testApplication {
        TestDb.init(Accounts, AiEngineerWhitelists)
        val token = runBlocking {
            AccountService.createOrRefresh("u@x.com", 100).token
                .also { AiEngineerWhitelistService.allow("u@x.com") }
        }
        application {
            install(ContentNegotiation) { json(appJson) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { claudeEngineerAvailabilityRoute() }
        }
        val resp = client.get("/v1/claude-engineer/available") {
            header(APP_TOKEN_HEADER, token)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"available\":true"))
    }
}
