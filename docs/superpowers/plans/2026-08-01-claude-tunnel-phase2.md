# Claude Tunnel Phase 2（server 反代）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans（本环境 subagent 不可用，inline）。Steps 用 `- [ ]` 跟踪。

**Goal:** server 加 `POST /v1/claude-chat`：X-App-Token 鉴权 + 限流 + 反代到本地 chisel 隧道口（127.0.0.1:3001）+ SSE 透传 + 健康推断，让 app 经 `https://api.polang.net/v1/claude-chat` 用上 KimiClaw 的 Claude。

**Architecture:** 新 `ClaudeChatRoute`：取 owner（拦截器已校验 token）→ RateLimiter 限频 → `HttpClient` POST 本地 3001/chat（body 透传）→ 响应 SSE 逐 chunk 透传给 client（`respondBytesWriter`，参考 `LlmRoute`）。3001 连接失败 → 503 `ai_offline`。鉴权由全局拦截器（`/v1/claude-chat` 不在 `publicRoutes`）。

**Tech:** Ktor HttpClient(CIO) + SSE 透传 + 现有 RateLimiter + 现有 AppToken 拦截器。

**关联：** spec `2026-07-31-claude-tunnel-chat-design.md` §7.3/§9；Phase 1 plan `2026-07-31-claude-tunnel-phase1.md`（隧道 + 网关，已验收）。server 为独立 Gradle 项目（`gradlew -p server test/build`）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| Create: `server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeChatRoute.kt` | `POST /v1/claude-chat`：鉴权兜底 + 限流 + 反代 3001 + SSE 透传 + 健康推断 |
| Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt` | routing 注册 `claudeChatRoute(httpClient, rateLimiter)` |
| Create: `server/src/test/kotlin/com/mamba/picme/server/routes/ClaudeChatRouteTest.kt` | 401 无 token / 429 限流 / 503 upstream 断 |

> `ownerTokenHash()` 在 `DiagRoute.kt` 是 private；Phase 2 内联同逻辑到 `ClaudeChatRoute`（小重复，二期可提公共 `AuthExt.kt`，不在本期范围）。

---

## Task 1: ClaudeChatRoute + 单测（TDD）

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeChatRoute.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/routes/ClaudeChatRouteTest.kt`

- [ ] **Step 1: 写失败测试**

`server/src/test/kotlin/com/mamba/picme/server/routes/ClaudeChatRouteTest.kt`:
```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.module
import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeChatRouteTest {
    @Test
    fun `无 token 返回 401`() = testApplication {
        // 拦截器要 X-App-Token；无 token → 401
        application { module(testConfig()) }
        val resp = client.post("/v1/claude-chat") {
            headers["Content-Type"] = "application/json"
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `upstream 连接失败返回 503 ai_offline`() = testApplication {
        // MockEngine 直接抛异常 → 模拟 3001 不通
        val badClient = HttpClient(MockEngine { throw java.net.ConnectException("refused") }) {
            install(ContentNegotiation) { json(appJson) }
        }
        application {
            module(testConfig(), httpClientOverride = badClient, claudeRateLimiter = RateLimiter(100))
        }
        // 带有效 token（拦截器放行）—— 用 testConfig 注册的账号 token
        val resp = client.post("/v1/claude-chat") {
            headers["X-App-Token"] = testToken()
            headers["Content-Type"] = "application/json"
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
    }

    private fun io.ktor.server.application.Application.testConfig(): AppConfig = AppConfig.load()
    private fun testToken(): String = "test-token" // 由 module 的 testConfig/account setup 决定，见现有 DiagRoute 测试模式
}
```

> 测试中 `module(...)` 的入参（httpClientOverride / claudeRateLimiter）需 `module` 暴露 hook（或用与 `DiagRouteTest` 一致的 setup 模式）。**Step 1 先看 `server/src/test/.../DiagRouteTest.kt`（或同目录现有 route 测试），对齐 testConfig/account-token setup 与 module 注入方式**，再落最终测试代码。上面是骨架。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "*ClaudeChatRouteTest" -i 2>&1 | tail -20`
Expected: FAIL（`ClaudeChatRoute` 不存在 / 编译错）

- [ ] **Step 3: 实现 `ClaudeChatRoute.kt`**

`server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeChatRoute.kt`:
```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully

/** 反代到本地 chisel 隧道口（Phase 1 的 KimiClaw Claude 网关）。 */
private const val CLAUDE_UPSTREAM = "http://127.0.0.1:3001/chat"

fun Route.claudeChatRoute(httpClient: HttpClient, rateLimiter: RateLimiter?) {
    post("/v1/claude-chat") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        if (rateLimiter != null && !rateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded")); return@post
        }
        val body = call.receiveText()
        val upstream = try {
            httpClient.post(CLAUDE_UPSTREAM) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Throwable) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "ai_offline", "message" to "tunnel unavailable"),
            )
            return@post
        }
        call.respondBytesWriter(ContentType.Text.EventStream, upstream.status) {
            val ch = upstream.bodyAsChannel()
            val buf = ByteArray(8 * 1024)
            try {
                while (!ch.isClosedForRead) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    writeFully(buf, 0, n)
                    flush()
                }
            } catch (e: Throwable) {
                ch.cancel(e); throw e
            }
        }
    }
}

/** 取 owner tokenHash：优先全局拦截器写入的 TokenHashKey，否则兜底 validateToken（路由单测用）。 */
private suspend fun ApplicationCall.ownerTokenHash(): String? {
    attributes.getOrNull(TokenHashKey)?.let { return it }
    val raw = request.headers[APP_TOKEN_HEADER] ?: return null
    return AccountService.validateToken(raw).takeIf { it.valid }?.tokenHash
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "*ClaudeChatRouteTest" -i 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/routes/ClaudeChatRoute.kt \
        server/src/test/kotlin/com/mamba/picme/server/routes/ClaudeChatRouteTest.kt
git commit -m "feat(server): ClaudeChatRoute 反代 /v1/claude-chat 到隧道 + SSE 透传 + 健康推断"
```

---

## Task 2: Application 注册 + 部署验证

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`

- [ ] **Step 1: routing 注册 claudeChatRoute**

在 `Application.module` 的 `routing { ... }` 里（`llmRoute(...)` 之后）加：
```kotlin
claudeChatRoute(httpClient, rateLimiter)
```
并在文件顶部 import：`import com.mamba.picme.server.routes.claudeChatRoute`。

> 用现有 shared `httpClient`（L146）+ 现有 `rateLimiter`（L154，per-window）。如需 claude 专用限频，可新建 `val claudeLimiter = RateLimiter(N, windowMs)`（spec §10 MVP 用限频，精细化二期）——MVP 先复用 `rateLimiter`。

- [ ] **Step 2: 编译 + 全 server 测试**

Run: `./gradlew -p server test 2>&1 | tail -15`
Expected: 全绿（含现有测试 + 新 ClaudeChatRouteTest）。

- [ ] **Step 3: 部署到 PoLang 服务器 + 验证**

发版（`server/deploy.sh` 或 OpenClaw `~/deploy-switch.sh`，见 [[server-prod-deploy]]）后，带 AppToken 验证：
```bash
# 从 PoLang 服务器（127.0.0.1:3001 经 Ktor 反代）
ssh ubuntu@43.161.201.142 'curl -N -m 120 -X POST http://127.0.0.1:8080/v1/claude-chat \
  -H "X-App-Token: <有效token>" -H "Content-Type: application/json" \
  -d "{\"message\":\"hi\"}"' 
# 或外部经 nginx
curl -N -m 120 -X POST https://api.polang.net/v1/claude-chat \
  -H "X-App-Token: <有效token>" -H "Content-Type: application/json" -d '{"message":"hi"}'
```
Expected: 流式 SSE（`event: session`/`assistant_text`/`done`）。无 token → 401。

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/Application.kt
git commit -m "feat(server): 注册 claudeChatRoute 到 routing"
```

---

## Phase 2 完成标准

- [ ] `ClaudeChatRouteTest` 全绿（401/429/503 覆盖）。
- [ ] `./gradlew -p server test` 全绿。
- [ ] 部署后 `https://api.polang.net/v1/claude-chat` 带 AppToken → 真 Claude 流式；无 token → 401；隧道断 → 503 ai_offline。
- [ ] 现有端点（/v1/chat/completions、/diag、/admin）回归不受影响。

Phase 2 通过后，app 端（Phase 3）可接入。
