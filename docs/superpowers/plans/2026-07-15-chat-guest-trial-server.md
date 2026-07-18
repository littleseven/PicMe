# Chat Guest Trial — Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a device-bound anonymous guest trial quota (100/device) to the PoLang server so unregistered clients can try the Remote LLM, and raise the registered quota to 1000/account; also rebrand the auth token prefix `picme_at_` → `pl-` and admin cookie `picme_admin` → `pl_admin`.

**Architecture:** A new `AnonymousDevices` table + `GuestService` mirror the existing account-quota mechanism. The auth interceptor allows `X-Device-Id` only on `/chat/completions` paths when no account token is present; `LlmRoute` branches account-quota vs guest-quota. Quotas: guest 100/device, registered 1000/account.

**Tech Stack:** Kotlin, Ktor, Exposed ORM, SQLite (WAL), JUnit (JVM), standalone Gradle project (`gradlew -p server`).

**Spec:** `docs/superpowers/specs/2026-07-15-chat-onboarding-guest-trial-design.md` §4.1, §4.2.

**Run tests:** `gradlew -p server test` (or `gradlew -p server test --tests "*GuestService*"`).

---

## File Structure

- **Create** `server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt` — device-keyed quota (check/increment/revert/remaining).
- **Create** `server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt` — quota behavior.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` — add `AnonymousDevices` table.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt` — register table in `SchemaUtils.create`.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt` — `guestLlmQuota` + raise `freeLlmQuota` default to 1000.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/auth/AppTokenAuth.kt` — `DEVICE_ID_HEADER` constant + doc comment update.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt` — `DeviceIdKey` attribute key.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/Application.kt` — interceptor guest branch; pass `guestLlmQuota` to `llmRoute`.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt` — guest-quota branch + `X-Guest-Remaining` header.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt` — `TOKEN_PREFIX = "pl-"`.
- **Modify** `server/src/main/kotlin/com/mamba/picme/server/admin/AdminAuth.kt` — `COOKIE_NAME = "pl_admin"`.

---

### Task 1: `AnonymousDevices` table + migration

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` (after `EmailVerifications`, ~line 77)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt:15-19`
- Test: covered by Task 2 (TestDb.init uses the table)

- [ ] **Step 1: Add the table object**

In `Tables.kt`, after the `EmailVerifications` object (before the LLM logs comment block), add:

```kotlin
// ── 设备级匿名试用额度（未注册访客）─────────────────────────
object AnonymousDevices : Table("anonymous_device") {
    val id = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 128)
    val llmCallsUsed = integer("llm_calls_used").default(0)
    val createdAt = long("created_at")
    val lastSeenAt = long("last_seen_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(deviceId)
    }
}
```

- [ ] **Step 2: Register it in the migration**

In `Migrations.kt`, extend the `SchemaUtils.create(...)` call to include `AnonymousDevices`:

```kotlin
SchemaUtils.create(
    Rules, Assets, TelemetryEvents, LlmDailyCounters,
    Accounts, EmailVerifications, LlmCallLogs, LlmChannels,
    ApkUploads, AnonymousDevices,
)
```

`SchemaUtils.create` is idempotent → adds the table on next boot, no data loss.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt
git commit -m "feat(server): add AnonymousDevices table for guest trial quota"
```

---

### Task 2: `GuestService` (device-keyed quota)

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt`

- [ ] **Step 1: Write the failing test**

`server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt`:

```kotlin
package com.mamba.picme.server.auth

import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestServiceTest {

    private val limit = 3

    @Test
    fun `first call is allowed and creates a row with used 1`() = runBlocking {
        TestDb.init(AnonymousDevices)
        val r = GuestService.checkAndIncrementQuota("dev-1", limit)
        assertTrue(r.allowed)
        assertEquals(2, r.remaining) // limit(3) - used(1)
        val used = transaction { AnonymousDevices.selectAll().single()[AnonymousDevices.llmCallsUsed] }
        assertEquals(1, used)
    }

    @Test
    fun `allows exactly limit calls then blocks`() = runBlocking {
        TestDb.init(AnonymousDevices)
        repeat(limit) { i ->
            val r = GuestService.checkAndIncrementQuota("dev-2", limit)
            assertTrue("call ${i + 1} should be allowed", r.allowed)
        }
        val blocked = GuestService.checkAndIncrementQuota("dev-2", limit)
        assertFalse(blocked.allowed)
        assertEquals(0, blocked.remaining)
    }

    @Test
    fun `devices are independent`() = runBlocking {
        TestDb.init(AnonymousDevices)
        repeat(limit) { GuestService.checkAndIncrementQuota("dev-a", limit) }
        val other = GuestService.checkAndIncrementQuota("dev-b", limit)
        assertTrue(other.allowed) // dev-b unaffected by dev-a exhaustion
    }

    @Test
    fun `revert decrements used`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.checkAndIncrementQuota("dev-3", limit)
        GuestService.revertQuota("dev-3")
        val used = transaction { AnonymousDevices.selectAll().single()[AnonymousDevices.llmCallsUsed] }
        assertEquals(0, used)
    }

    @Test
    fun `revert is a no-op for unknown device`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.revertQuota("never-seen") // must not throw / not go negative
        assertEquals(0, transaction { AnonymousDevices.selectAll().count() })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
gradlew -p server test --tests "com.mamba.picme.server.auth.GuestServiceTest"
```
Expected: FAIL — `GuestService` unresolved.

- [ ] **Step 3: Implement `GuestService`**

`server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt`:

```kotlin
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
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
gradlew -p server test --tests "com.mamba.picme.server.auth.GuestServiceTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt
git commit -m "feat(server): add GuestService device-keyed quota"
```

---

### Task 3: Quota config (guest 100, registered 1000)

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt:15,43`
- Test: `server/src/test/kotlin/com/mamba/picme/server/config/AppConfigTest.kt` (extend)

- [ ] **Step 1: Add `guestLlmQuota` field + raise `freeLlmQuota` default**

In `AppConfig.kt` data class, after `val freeLlmQuota: Int,` add:

```kotlin
    val freeLlmQuota: Int,
    val guestLlmQuota: Int,
```

In `load()`, change the `freeLlmQuota` line and add `guestLlmQuota`:

```kotlin
            freeLlmQuota = envInt("FREE_LLM_QUOTA", 1000),
            guestLlmQuota = envInt("GUEST_LLM_QUOTA", 100),
```

- [ ] **Step 2: Add/extend a test for the defaults**

In `AppConfigTest.kt`, add (constructing via env — follow the existing test's pattern for loading defaults; if the existing test loads from empty env, assert the two fields):

```kotlin
    @Test
    fun `default quotas are guest 100 and registered 1000`() {
        val config = AppConfig.load() // env unset in test JVM
        assertEquals(1000, config.freeLlmQuota)
        assertEquals(100, config.guestLlmQuota)
    }
```

(If `AppConfig.load()` reads real env and is non-deterministic in tests, instead assert via the `envInt` defaults by constructing `AppConfig(...)` directly with the documented default values. Match whatever the existing `AppConfigTest` already does.)

- [ ] **Step 3: Run + commit**

```bash
gradlew -p server test --tests "com.mamba.picme.server.config.AppConfigTest"
git add server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt server/src/test/kotlin/com/mamba/picme/server/config/AppConfigTest.kt
git commit -m "feat(server): guest quota 100, registered quota 1000"
```

---

### Task 4: `DEVICE_ID_HEADER` + `DeviceIdKey`

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/AppTokenAuth.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt:14`

- [ ] **Step 1: Add the header constant + update doc comment**

`AppTokenAuth.kt` full new content:

```kotlin
package com.mamba.picme.server.auth

/**
 * 客户端账号认证 header。值为邮箱注册下发的动态 token（pl-* 前缀），
 * 由 AccountService.validateToken 按 account.token_hash 校验，无静态 env。
 */
const val APP_TOKEN_HEADER = "X-App-Token"

/**
 * 未注册访客的设备标识 header。仅 /chat/completions 路径在缺少有效 token 时接受，
 * 命中 AnonymousDevices 设备级试用额度（GUEST_LLM_QUOTA，默认 100）。
 */
const val DEVICE_ID_HEADER = "X-Device-Id"
```

- [ ] **Step 2: Add `DeviceIdKey` next to `TokenHashKey`**

In `AuthRoute.kt`, after `val TokenHashKey = AttributeKey<String>("tokenHash")`:

```kotlin
val DeviceIdKey = AttributeKey<String>("deviceId")
```

Add the import for `AttributeKey` if not present (it already is, since `TokenHashKey` uses it).

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/auth/AppTokenAuth.kt server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt
git commit -m "feat(server): add DEVICE_ID_HEADER + DeviceIdKey attribute"
```

---

### Task 5: Auth interceptor guest branch

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt:6,78-99,125`

- [ ] **Step 1: Wire guest fallback into the interceptor**

In `Application.kt`:

1. Add imports:
```kotlin
import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.routes.DeviceIdKey
```

2. Replace the auth interceptor body (the block starting `val rawToken = call.request.headers[APP_TOKEN_HEADER]`) with:

```kotlin
        val rawToken = call.request.headers[APP_TOKEN_HEADER]
        val authResult = rawToken?.let { AccountService.validateToken(it) }
        if (authResult?.valid == true) {
            authResult.tokenHash?.let { call.attributes.put(TokenHashKey, it) }
            return@intercept
        }

        // 无有效账号 token → 仅在 LLM 代理路径上允许设备级访客试用
        val isLlmPath = uri == "/chat/completions" || uri == "/v1/chat/completions"
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        if (isLlmPath && !deviceId.isNullOrBlank()) {
            call.attributes.put(DeviceIdKey, deviceId)
            return@intercept
        }

        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
        finish()
        return@intercept
```

(Note: `uri` is already computed at line 79 as `call.request.local.uri.substringBefore("?")`. Keep that line. `publicRoutes`/`/admin` early-return above stays unchanged.)

- [ ] **Step 2: Pass `guestLlmQuota` into `llmRoute`**

Change the routing call (~line 125):

```kotlin
        llmRoute(llmProxy, rateLimiter, config.llmPrices, config.guestLlmQuota)
```

- [ ] **Step 3: Build to confirm it compiles** (LlmRoute signature updated in Task 6)

This task and Task 6 are coupled by the `llmRoute` signature — implement Task 6 before compiling.

- [ ] **Step 4: Commit (together with Task 6)**

Commit after Task 6 compiles.

---

### Task 6: `LlmRoute` guest-quota branch + remaining header

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt`

- [ ] **Step 1: Update signature + imports**

Add imports:
```kotlin
import com.mamba.picme.server.auth.GuestService
import com.mamba.picme.server.routes.DeviceIdKey
```

Change the function signature to accept `guestLlmQuota`:

```kotlin
fun Route.llmRoute(
    proxy: LlmProxy,
    rateLimiter: RateLimiter?,
    prices: Map<String, Price>,
    guestLlmQuota: Int,
) {
```

- [ ] **Step 2: Add `remainingReadOnly` to `GuestService`**

The success path reports remaining without mutating quota. Append to `GuestService.kt`:

```kotlin
    /** Read-only remaining for the X-Guest-Remaining header (does not mutate). */
    suspend fun remainingReadOnly(deviceId: String, limit: Int): Int {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val used = AnonymousDevices.selectAll()
                .where { AnonymousDevices.deviceId eq deviceId }
                .firstOrNull()?.get(AnonymousDevices.llmCallsUsed) ?: 0
            (limit - used).coerceAtLeast(0)
        }
    }
```

- [ ] **Step 3: Replace the quota + dispatch section in `LlmRoute`**

Replace the block from `val tokenHash = call.attributes[TokenHashKey]` through the end of the `when (result)` with this clean version (single increment per call; remaining is read-only):

```kotlin
            val tokenHash = call.attributes.getOrNull(TokenHashKey)
            val deviceId = call.attributes.getOrNull(DeviceIdKey)

            if (tokenHash == null && deviceId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }

            val body = call.receive<JsonObject>()
            val requestedModel = (body["model"] as? JsonPrimitive)?.content ?: ""
            val isGuest = tokenHash == null
            val accountId = tokenHash?.let { AccountService.idForTokenHash(it) }

            // Quota check — account OR guest (single increment each)
            if (isGuest) {
                val guest = GuestService.checkAndIncrementQuota(deviceId!!, guestLlmQuota)
                if (!guest.allowed) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "quota_exceeded", "tier" to "guest", "message" to "guest quota used up"),
                    )
                    return@post
                }
            } else if (!AccountService.checkAndIncrementQuota(tokenHash)) {
                accountId?.let {
                    UsageRecorder.log(it, requestedModel, "", null, 0, "blocked_quota", null, prices)
                }
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "quota_exceeded", "tier" to "account", "message" to "free quota used up"),
                )
                return@post
            }

            val started = System.currentTimeMillis()
            val result = proxy.forward(clientIp, body)
            val latencyMs = (System.currentTimeMillis() - started).toInt()
            when (result) {
                is ProxyResult.Success -> {
                    accountId?.let {
                        UsageRecorder.log(
                            accountId = it,
                            model = result.model,
                            provider = result.provider,
                            usage = result.usage,
                            respBytes = result.bytes.size,
                            status = "ok",
                            latencyMs = latencyMs,
                            prices = prices,
                        )
                    }
                    if (isGuest) {
                        call.response.headers.append(
                            "X-Guest-Remaining",
                            GuestService.remainingReadOnly(deviceId!!, guestLlmQuota).toString(),
                        )
                    }
                    call.respondBytes(result.bytes, ContentType.Application.Json, result.status)
                }
                is ProxyResult.Error -> {
                    if (isGuest) GuestService.revertQuota(deviceId!!) else AccountService.revertQuota(tokenHash)
                    accountId?.let {
                        UsageRecorder.log(it, requestedModel, "", null, 0, result.logStatus, null, prices)
                    }
                    call.respond(result.status, result.body)
                }
            }
```

(`guestRemaining` returns `Int`; `.toString()` for the header value.)

- [ ] **Step 4: Build + run all server tests**

```bash
gradlew -p server test
```
Expected: PASS (existing tests + GuestServiceTest + AppConfigTest).

- [ ] **Step 5: Commit (Tasks 5 + 6 together)**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/Application.kt server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt
git commit -m "feat(server): guest-quota branch in auth interceptor + LlmRoute, X-Guest-Remaining header"
```

---

### Task 7: Token-prefix + cookie rebrand

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt:23`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminAuth.kt:10`
- Test: `server/src/test/kotlin/com/mamba/picme/server/auth/AccountServiceIdForTokenHashTest.kt` (extend) or a new focused test

- [ ] **Step 1: Write the failing test**

Add to a new `AccountServiceTokenPrefixTest.kt`:

```kotlin
package com.mamba.picme.server.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceTokenPrefixTest {

    @Test
    fun `new tokens use pl- prefix`() {
        val token = AccountService.generateToken()
        assertTrue("token should start with pl-", token.startsWith("pl-"))
    }

    @Test
    fun `legacy picme_at tokens are rejected`() {
        assertFalse(AccountService.isTokenFormat("picme_at_" + "a".repeat(64)))
    }

    @Test
    fun `pl- tokens of sufficient length are accepted`() {
        assertTrue(AccountService.isTokenFormat("pl-" + "a".repeat(64)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
gradlew -p server test --tests "com.mamba.picme.server.auth.AccountServiceTokenPrefixTest"
```
Expected: FAIL (current prefix is `picme_at_`).

- [ ] **Step 3: Change the prefix**

`AccountService.kt:23`:
```kotlin
    const val TOKEN_PREFIX = "pl-"
```

`AdminAuth.kt:10`:
```kotlin
    const val COOKIE_NAME = "pl_admin"
```

- [ ] **Step 4: Run to verify pass + run full suite**

```bash
gradlew -p server test
```
Expected: PASS. (If any existing test hard-codes `picme_at_` expectations, update them to `pl-`.)

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt server/src/main/kotlin/com/mamba/picme/server/admin/AdminAuth.kt server/src/test/kotlin/com/mamba/picme/server/auth/AccountServiceTokenPrefixTest.kt
git commit -m "feat(server): rebrand auth token prefix picme_at_ -> pl- and admin cookie -> pl_admin"
```

---

## Self-Review (run after writing)

- **Spec coverage §4.1:** AnonymousDevices (T1) ✓, GuestService (T2) ✓, config quotas (T3) ✓, interceptor guest branch (T5) ✓, LlmRoute branch + remaining header (T6) ✓.
- **Spec coverage §4.2:** token prefix + cookie + comment (T4 comment, T7) ✓.
- **Placeholder scan:** none.
- **Type consistency:** `GuestService.checkAndIncrementQuota(deviceId, limit): GuestQuotaResult(allowed, remaining)`; `remainingReadOnly(deviceId, limit): Int`; `DeviceIdKey`/`TokenHashKey` AttributeKeys; `llmRoute(..., guestLlmQuota: Int)`. All referenced consistently.
- **Known follow-up:** deploy server (user) before client guest mode is useful; client degrades to nudge on 401 until then.

## Deploy note

Server is a standalone project deployed by the user. After merging + `gradlew -p server build`, deploy to `api.polang.net`. Set `GUEST_LLM_QUOTA` / `FREE_LLM_QUOTA` env vars only to override the 100/1000 defaults. Existing `picme_at_*` tokens stop validating on deploy (users re-register via email OTP).
