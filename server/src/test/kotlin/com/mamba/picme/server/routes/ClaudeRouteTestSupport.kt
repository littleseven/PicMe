package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import kotlinx.coroutines.runBlocking

/**
 * 共享辅助：三个 Claude 路由测试（chat / tool-result / deliver）的公共 setup。
 *
 * - [claudePipeline]：TestDb 初始化 + ContentNegotiation + APP_TOKEN_HEADER → TokenHash 拦截 + 路由注册。
 * - [seedClaudeToken]：通过 AccountService 种入测试账号并返回明文 token。
 * - [okUpstream] / [badUpstream]：MockEngine 快捷构造。
 */
internal fun TestApplicationBuilder.claudePipeline(route: Routing.() -> Unit) {
    TestDb.init(Accounts, AiEngineerWhitelists)
    application {
        install(ContentNegotiation) { json(appJson) }
        intercept(ApplicationCallPipeline.Plugins) {
            call.request.headers[APP_TOKEN_HEADER]
                ?.let { AccountService.sha256(it) }
                ?.let { call.attributes.put(TokenHashKey, it) }
        }
        routing(route)
    }
}

internal fun seedClaudeToken(email: String = "u@x.com"): String = runBlocking {
    AccountService.createOrRefresh(email, 100).token
}

internal fun okUpstream(body: String, contentType: String): HttpClient = HttpClient(
    MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type", contentType)) },
)

internal fun badUpstream(): HttpClient =
    HttpClient(MockEngine { throw java.net.ConnectException("refused") })
