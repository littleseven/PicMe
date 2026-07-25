# Admin Quota & Channel Insights Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four admin-backend capabilities to `server/`: (1) quota reset for accounts & guest devices (history preserved); (2) runtime-adjustable call limit (global default via settings page + per-account override); (3) cumulative metrics on the overview page; (4) per-channel consumption + upstream balance (cached, manual refresh) on the channels page.

**Architecture:** New `server_setting` table backs a cached `SettingsService` singleton (hot-path reads stay in-memory; SQLite single-connection safe), replacing the env-static `freeLlmQuota`/`guestLlmQuota` Int params threaded through `authRoute`/`llmRoute`/`adminRoute`. Quota reset zeroes `llm_calls_used` (history in `llm_call_log` untouched). Per-account limit override uses the existing `account.llm_calls_limit` column (quota check already reads it per-row). Channel balance adds 3 columns to `llm_channel` + a `ChannelBalanceService` that calls the upstream balance API on demand and caches the response.

**Tech Stack:** Kotlin 2.0.21, Ktor 3.0.3, Exposed 0.55.0, SQLite (HikariCP pool=1, WAL), kotlinx.html SSR, JUnit4. Build: `./gradlew -p server build`.

**Spec:** `docs/superpowers/specs/2026-07-25-admin-quota-and-channel-insights-design.md`

**Working tree note:** Implementation happens on branch `feat/admin-quota-channel-insights` (already created, spec committed). The working tree has ~21 unrelated uncommitted files (release-observability work) — never `git add .`; only stage the files each task lists.

**Phases (each commits independently and yields working software):**
- **A** — Settings foundation (Task 1–3)
- **B** — Quota reset + per-account limit (Task 4–6)
- **C** — Settings page UI (Task 7)
- **D** — Overview cumulative (Task 8)
- **E** — Channel consumption + balance (Task 9–13)
- **F** — Docs sync (Task 14)

---

## Task 1: `server_setting` table + seed

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` (append new object)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt` (register in `create`, add `seedSettings`)
- Create: `server/migrations/007_server_setting.sql` (reference DDL)
- Test: `server/src/test/kotlin/com/mamba/picme/server/db/MigrationsSettingsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/kotlin/com/mamba/picme/server/db/MigrationsSettingsTest.kt`:

```kotlin
package com.mamba.picme.server.db

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationsSettingsTest {

    @Test
    fun `seedSettings writes env defaults when rows absent`() = runBlocking {
        TestDb.init(ServerSettings)
        val config = AppConfig.load() // 真实环境变量或默认；测试只断言「行被写入且值一致」
        Migrations.seedSettings(config)

        transaction(Db.instance) {
            val rows = ServerSettings.selectAll().associate { it[ServerSettings.key] to it[ServerSettings.value] }
            assertEquals(config.freeLlmQuota, rows[SettingsService.KEY_FREE])
            assertEquals(config.guestLlmQuota, rows[SettingsService.KEY_GUEST])
        }
    }

    @Test
    fun `seedSettings is idempotent when rows present`() = runBlocking {
        TestDb.init(ServerSettings)
        val config = AppConfig.load()
        Migrations.seedSettings(config)
        // 再次播种不覆盖、不重复
        Migrations.seedSettings(config)
        transaction(Db.instance) {
            val count = ServerSettings.selectAll().count()
            assertEquals(2L, count)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server :server:testDebugUnitTest --tests '*.MigrationsSettingsTest' -i` (or `./gradlew -p server test --tests '*.MigrationsSettingsTest'`)
Expected: FAIL — `ServerSettings` / `SettingsService.KEY_*` / `Migrations.seedSettings` unresolved.

- [ ] **Step 3: Add the `ServerSettings` table**

Append to `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` (after `ApkUploads`):

```kotlin
// ── 服务端运行时设置（key-value；当前仅额度默认值，env 仅作首次播种）──
object ServerSettings : Table("server_setting") {
    val key = varchar("key", 48)
    val value = integer("value")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(key)
}
```

- [ ] **Step 4: Create `SettingsService` constants (stub for keys; full impl in Task 2)**

Create `server/src/main/kotlin/com/mamba/picme/server/config/SettingsService.kt`:

```kotlin
package com.mamba.picme.server.config

object SettingsService {
    const val KEY_FREE = "free_llm_quota"
    const val KEY_GUEST = "guest_llm_quota"
}
```

(Full `Snapshot`/`load`/`update` added in Task 2.)

- [ ] **Step 5: Register table + add `seedSettings` in Migrations**

In `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`:

5a. Add `ServerSettings` to the imports block and the `SchemaUtils.create(...)` list (and to `createMissingTablesAndColumns`):

```kotlin
            SchemaUtils.create(
                Rules, Assets, TelemetryEvents, LlmDailyCounters,
                Accounts, EmailVerifications, LlmCallLogs, LlmChannels,
                ApkUploads, AnonymousDevices, ServerSettings,
            )
            SchemaUtils.createMissingTablesAndColumns(Accounts, LlmChannels, LlmCallLogs, ServerSettings)
```

5b. Add the import `import com.mamba.picme.server.config.SettingsService` at the top.

5c. In `Migrations.run`, after `backfillDefaultModels()` add:

```kotlin
        seedSettings(config)
```

5d. Add the `seedSettings` function inside `object Migrations` (after `backfillDefaultModels`):

```kotlin
    /**
     * 幂等播种额度默认值：仅当对应行缺失时写入 env 值。之后由后台 /admin/settings 管理，env 降级为「首次默认」。
     */
    internal fun seedSettings(config: AppConfig) {
        transaction(Db.instance) {
            val now = System.currentTimeMillis()
            seedIfAbsent(SettingsService.KEY_FREE, config.freeLlmQuota, now)
            seedIfAbsent(SettingsService.KEY_GUEST, config.guestLlmQuota, now)
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.seedIfAbsent(key: String, value: Int, now: Long) {
        val exists = ServerSettings.selectAll().where { ServerSettings.key eq key }.firstOrNull() != null
        if (!exists) {
            ServerSettings.insert {
                it[ServerSettings.key] = key
                it[ServerSettings.value] = value
                it[ServerSettings.updatedAt] = now
            }
        }
    }
```

- [ ] **Step 6: Create reference DDL file**

Create `server/migrations/007_server_setting.sql`:

```sql
-- 参考 DDL（运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对）
CREATE TABLE IF NOT EXISTS server_setting (
  key        VARCHAR(48) PRIMARY KEY,
  value      INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew -p server test --tests '*.MigrationsSettingsTest'`
Expected: PASS (both tests).

- [ ] **Step 8: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt \
        server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt \
        server/src/main/kotlin/com/mamba/picme/server/config/SettingsService.kt \
        server/migrations/007_server_setting.sql \
        server/src/test/kotlin/com/mamba/picme/server/db/MigrationsSettingsTest.kt
git commit -m "feat(server): server_setting 表 + 额度默认值播种"
```

---

## Task 2: `SettingsService` snapshot (load / snapshot / update)

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/config/SettingsService.kt` (full impl)
- Test: `server/src/test/kotlin/com/mamba/picme/server/config/SettingsServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/kotlin/com/mamba/picme/server/config/SettingsServiceTest.kt`:

```kotlin
package com.mamba.picme.server.config

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.ServerSettings
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsServiceTest {

    @Test
    fun `load reads seeded rows into snapshot`() = runBlocking {
        TestDb.init(ServerSettings)
        transaction(Db.instance) {
            ServerSettings.insert {
                it[key] = SettingsService.KEY_FREE; it[value] = 555; it[updatedAt] = 1L
            }
            ServerSettings.insert {
                it[key] = SettingsService.KEY_GUEST; it[value] = 77; it[updatedAt] = 1L
            }
        }
        SettingsService.load()
        val snap = SettingsService.snapshot()
        assertEquals(555, snap.freeLlmQuota)
        assertEquals(77, snap.guestLlmQuota)
    }

    @Test
    fun `update writes both fields and refreshes snapshot`() = runBlocking {
        TestDb.init(ServerSettings)
        SettingsService.load() // 空 → 默认值
        val snap = SettingsService.update(free = 300, guest = 50)
        assertEquals(300, snap.freeLlmQuota)
        assertEquals(50, snap.guestLlmQuota)
        assertEquals(300, SettingsService.snapshot().freeLlmQuota) // 缓存已刷新

        transaction(Db.instance) {
            val rows = ServerSettings.selectAll().associate { it[ServerSettings.key] to it[ServerSettings.value] }
            assertEquals(300, rows[SettingsService.KEY_FREE])
            assertEquals(50, rows[SettingsService.KEY_GUEST])
        }
    }

    @Test
    fun `update with null leaves the other field untouched`() = runBlocking {
        TestDb.init(ServerSettings)
        SettingsService.update(free = 200, guest = 40)
        SettingsService.update(free = null, guest = 9)
        assertEquals(200, SettingsService.snapshot().freeLlmQuota)
        assertEquals(9, SettingsService.snapshot().guestLlmQuota)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server test --tests '*.SettingsServiceTest'`
Expected: FAIL — `SettingsService.load/update/snapshot` unresolved (stub only has constants).

- [ ] **Step 3: Write the full `SettingsService`**

Replace the entire contents of `server/src/main/kotlin/com/mamba/picme/server/config/SettingsService.kt`:

```kotlin
package com.mamba.picme.server.config

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.ServerSettings
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * 运行时可调服务端设置的内存快照。读命中 [@Volatile] 快照（热路径零 DB 读，SQLite 单连接安全）；
 * 写（后台 /admin/settings）在同一事务内 UPSERT 并重灌快照，立即对后续请求生效。
 *
 * 当前承载两项：[KEY_FREE] / [KEY_GUEST]。env 仅在首次播种时用作默认值，之后以 server_setting 表为准。
 */
object SettingsService {
    const val KEY_FREE = "free_llm_quota"
    const val KEY_GUEST = "guest_llm_quota"

    data class Snapshot(val freeLlmQuota: Int, val guestLlmQuota: Int)

    @Volatile
    private var current = Snapshot(freeLlmQuota = 1000, guestLlmQuota = 100)

    fun snapshot(): Snapshot = current

    /** 启动时从 DB 灌入快照；行缺失时保留默认值（首次启动尚未 seed 的兜底）。 */
    suspend fun load() {
        current = newSuspendedTransaction(Dispatchers.IO, Db.instance) { readAll() }
    }

    /**
     * 更新额度默认值；null 表示该项不改。UPSERT 命中行后重灌快照，返回新快照。
     */
    suspend fun update(free: Int?, guest: Int?): Snapshot {
        current = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val now = Instant.now().toEpochMilli()
            free?.let { upsert(KEY_FREE, it, now) }
            guest?.let { upsert(KEY_GUEST, it, now) }
            readAll()
        }
        return current
    }

    private fun readAll(): Snapshot {
        val rows = ServerSettings.selectAll().associate { it[ServerSettings.key] to it[ServerSettings.value] }
        return Snapshot(
            freeLlmQuota = rows[KEY_FREE] ?: 1000,
            guestLlmQuota = rows[KEY_GUEST] ?: 100,
        )
    }

    private fun upsert(key: String, value: Int, now: Long) {
        val updated = ServerSettings.update({ ServerSettings.key eq key }) {
            it[ServerSettings.value] = value
            it[ServerSettings.updatedAt] = now
        }
        if (updated == 0) {
            ServerSettings.insert {
                it[ServerSettings.key] = key
                it[ServerSettings.value] = value
                it[ServerSettings.updatedAt] = now
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew -p server test --tests '*.SettingsServiceTest'`
Expected: PASS (all three).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/config/SettingsService.kt \
        server/src/test/kotlin/com/mamba/picme/server/config/SettingsServiceTest.kt
git commit -m "feat(server): SettingsService 内存快照(load/snapshot/update)"
```

---

## Task 3: Wire `SettingsService` into runtime + replace static Int params

This task removes the `freeLlmQuota`/`guestLlmQuota` Int params from `authRoute`/`llmRoute`/`adminRoute` and points them at `SettingsService.snapshot()`. It ripples to 11 call sites — update ALL of them in this task so the module compiles.

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt` (load snapshot; drop Int args)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt` (drop param)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt` (drop param)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt` (signature: drop guestLlmQuota, **add** `balanceService` placeholder param for Task 9+) — NOTE: to keep this task focused, we add `balanceService` now but it is unused until Task 13. Alternative: defer signature change of adminRoute to Task 13. **We choose: change it now** so all call sites are fixed once.
- Modify (tests): `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt`, `server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt`
- Create: `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelBalanceService.kt` (minimal stub; full impl in Task 11)
- No new test (wiring change; covered by existing route tests continuing to pass)

- [ ] **Step 1: Create `ChannelBalanceService` stub (so adminRoute compiles)**

Create `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelBalanceService.kt`:

```kotlin
package com.mamba.picme.server.llm

import io.ktor.client.HttpClient

/**
 * 调用上游 balance API 并缓存结果。Task 11 补全 refresh/cached；此处仅占位以便 adminRoute 签名先稳定。
 */
class ChannelBalanceService(
    val httpClient: HttpClient,
    val timeoutMs: Long = 8_000,
)
```

- [ ] **Step 2: Change `adminRoute` signature**

In `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`:

2a. Add imports:

```kotlin
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.llm.ChannelBalanceService
```

2b. Change the signature (line ~39) — replace `guestLlmQuota: Int = 100` with `balanceService`:

```kotlin
fun Route.adminRoute(adminToken: String, cosService: CosService, balanceService: ChannelBalanceService) {
```

2c. In the `GET /admin/devices` handler (line ~110), replace the literal `guestLlmQuota` arg:

```kotlin
            val rows = AdminQueries.devicesList()
            call.respondText(
                AdminViews.devicesPage(rows, AdminQueries.usersCount(), SettingsService.snapshot().guestLlmQuota),
                ContentType.Text.Html,
            )
```

- [ ] **Step 3: Change `authRoute` — drop `freeLlmQuota`**

In `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt`:

3a. Add import:

```kotlin
import com.mamba.picme.server.config.SettingsService
```

3b. Change signature (remove the param):

```kotlin
fun Route.authRoute(
    emailService: EmailService,
) {
```

3c. Change the `createOrRefresh` call (line ~56):

```kotlin
        val account = AccountService.createOrRefresh(req.email, SettingsService.snapshot().freeLlmQuota)
```

- [ ] **Step 4: Change `llmRoute` — drop `guestLlmQuota`**

In `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt`:

4a. Add import:

```kotlin
import com.mamba.picme.server.config.SettingsService
```

4b. Change signature — remove `guestLlmQuota: Int,`:

```kotlin
fun Route.llmRoute(
    proxy: LlmProxy,
    rateLimiter: RateLimiter?,
    prices: Map<String, Price>,
) {
```

4c. Replace the two usages (lines ~55 and ~95) — define a local once at the top of the handler and use it:

After `val isGuest = tokenHash == null` (line ~50), add:

```kotlin
            val guestLlmQuota = SettingsService.snapshot().guestLlmQuota
```

(The existing `GuestService.checkAndIncrementQuota(deviceId!!, guestLlmQuota)` and `GuestService.remainingReadOnly(deviceId!!, guestLlmQuota)` lines now resolve to this local — leave them unchanged.)

- [ ] **Step 5: Update `Application.kt`**

In `server/src/main/kotlin/com/mamba/picme/server/Application.kt`:

5a. Add imports:

```kotlin
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.llm.ChannelBalanceService
```

5b. After `runBlocking { ChannelRegistry.reload() }` (line ~55), add:

```kotlin
    runBlocking { SettingsService.load() }
```

5c. After `val cosService = CosService(config)` (line ~128), add:

```kotlin
    val balanceService = ChannelBalanceService(httpClient)
```

5d. Update the routing calls (drop the Int args; add balanceService):

```kotlin
        authRoute(emailService)
```

```kotlin
        llmRoute(llmProxy, rateLimiter, config.llmPrices)
```

```kotlin
        adminRoute(config.adminToken, cosService, balanceService)
```

- [ ] **Step 6: Update test call sites**

6a. In `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt`:
- Add imports:

```kotlin
import com.mamba.picme.server.llm.ChannelBalanceService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
```

- Add a field after `private val cos = ...`:

```kotlin
    private val balance = ChannelBalanceService(HttpClient(CIO))
```

- Replace every `adminRoute(token, cos)` → `adminRoute(token, cos, balance)`; replace `adminRoute("", cos)` → `adminRoute("", cos, balance)`; replace `adminRoute(token, cos, 100)` → `adminRoute(token, cos, balance)`.

6b. In `server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt`:
- Add the same imports (`ChannelBalanceService`, `HttpClient`, `CIO`).
- Add the same field `private val balance = ChannelBalanceService(HttpClient(CIO))`.
- Replace all 7 occurrences of `adminRoute(token, cos)` → `adminRoute(token, cos, balance)`.

- [ ] **Step 7: Run the full server test suite**

Run: `./gradlew -p server test`
Expected: PASS — all existing tests still green (behaviour unchanged; params now come from snapshot). If `ChannelRegistry.setActiveForTesting` or other test helpers error, they are unrelated — fix only signature mismatches introduced here.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/Application.kt \
        server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt \
        server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt \
        server/src/main/kotlin/com/mamba/picme/server/llm/ChannelBalanceService.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt
git commit -m "refactor(server): 额度默认值改读 SettingsService 快照，去 Int 硬参"
```

---

## Task 4: `AccountService.resetQuota` + `setLimit`

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/auth/AccountServiceAdminLifecycleTest.kt` (append)

- [ ] **Step 1: Write the failing tests**

Append to `AccountServiceAdminLifecycleTest.kt` (inside the class, after the last `@Test`):

```kotlin
    @Test
    fun `resetQuota zeroes used but keeps limit and call logs`() = runBlocking {
        val (id, _) = seedAccount()
        // 人为把 used 抬高
        transaction(Db.instance) {
            Accounts.update({ Accounts.id eq id }) {
                with(SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed + 30 }
            }
        }

        assertTrue(AccountService.resetQuota(id))

        transaction(Db.instance) {
            val acc = Accounts.selectAll().where { Accounts.id eq id }.single()
            assertEquals(0, acc[Accounts.llmCallsUsed])
            assertEquals(100, acc[Accounts.llmCallsLimit]) // limit 不变
            // 历史调用日志仍在
            assertEquals(1L, LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq id }.count())
        }
    }

    @Test
    fun `resetQuota returns false for missing account`() = runBlocking {
        assertFalse(AccountService.resetQuota(9999))
    }

    @Test
    fun `setLimit updates limit`() = runBlocking {
        val (id, _) = seedAccount()
        assertTrue(AccountService.setLimit(id, 500))
        transaction(Db.instance) {
            assertEquals(500, Accounts.selectAll().where { Accounts.id eq id }.single()[Accounts.llmCallsLimit])
        }
    }

    @Test
    fun `setLimit rejects negative limit`() = runBlocking {
        seedAccount()
        try {
            AccountService.setLimit(1, -1)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
```

This test uses `Accounts.update` and `SqlExpressionBuilder` — add these imports to the test file if missing:

```kotlin
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.update
```

(`update` here is `org.jetbrains.exposed.sql.update`; `import org.jetbrains.exposed.sql.insert` / `selectAll` / `transactions.transaction` are already present.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests '*.AccountServiceAdminLifecycleTest'`
Expected: FAIL — `resetQuota` / `setLimit` unresolved.

- [ ] **Step 3: Implement both functions**

In `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt`, in the `// ── Quota ──` section (after `revertQuota`), add:

```kotlin
    /**
     * 管理员重置已用额度：仅清零 llm_calls_used，保留 llm_calls_limit 与 llm_call_log 历史。
     * 返回是否命中账号（false = id 不存在）。
     */
    suspend fun resetQuota(id: Int): Boolean {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()
                ?: return@newSuspendedTransaction false
            Accounts.update({ Accounts.id eq id }) { it[llmCallsUsed] = 0 }
            true
        }
    }

    /**
     * 管理员修改单账号调用上限。limit=0 即禁用（checkAndIncrementQuota: used(0) >= limit(0) → 恒拦截），
     * 等价于 revoke 但不失效 token。返回是否命中。
     */
    suspend fun setLimit(id: Int, limit: Int): Boolean {
        require(limit >= 0) { "limit must be >= 0" }
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val rows = Accounts.update({ Accounts.id eq id }) { it[llmCallsLimit] = limit }
            rows > 0
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p server test --tests '*.AccountServiceAdminLifecycleTest'`
Expected: PASS (all, including the 4 new ones).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt \
        server/src/test/kotlin/com/mamba/picme/server/auth/AccountServiceAdminLifecycleTest.kt
git commit -m "feat(server): AccountService.resetQuota/setLimit"
```

---

## Task 5: `GuestService.resetQuota`

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt` (append)

- [ ] **Step 1: Write the failing test**

Append to `GuestServiceTest.kt` (read the file first to match its existing seed helper style; below assumes a helper that inserts a device row. If the file lacks one, seed inline as shown):

```kotlin
    @Test
    fun `resetQuota zeroes used for a device by id`() = runBlocking {
        TestDb.init(AnonymousDevices)
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = 3
                it[AnonymousDevices.deviceId] = "dev-reset-id-1234"
                it[AnonymousDevices.llmCallsUsed] = 42
                it[AnonymousDevices.createdAt] = 1_000L
                it[AnonymousDevices.lastSeenAt] = 2_000L
            }
        }
        GuestService.resetQuota(3)
        val used = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.selectAll().where { AnonymousDevices.id eq 3 }.single()[AnonymousDevices.llmCallsUsed]
        }
        assertEquals(0, used)
    }

    @Test
    fun `resetQuota on missing id is a no-op`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.resetQuota(9999) // 不抛
    }
```

Add any missing imports (`AnonymousDevices` from `com.mamba.picme.server.db`, `newSuspendedTransaction`, `insert`, `assertEquals`, `runBlocking`) — mirror what the file already uses.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server test --tests '*.GuestServiceTest'`
Expected: FAIL — `GuestService.resetQuota` unresolved.

- [ ] **Step 3: Implement**

In `server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt`, after `deleteById`, add:

```kotlin
    /** 管理员重置某访客设备的已用额度（按数据库 id）。与账号 resetQuota 同义：清零计数、保留设备记录。 */
    suspend fun resetQuota(id: Int) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.update({ AnonymousDevices.id eq id }) {
                it[AnonymousDevices.llmCallsUsed] = 0
            }
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew -p server test --tests '*.GuestServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt \
        server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt
git commit -m "feat(server): GuestService.resetQuota"
```

---

## Task 6: Admin routes + UI for reset / setLimit

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt` (append)

- [ ] **Step 1: Write the failing route tests**

Append to `AdminRoutesTest.kt`:

```kotlin
    @Test
    fun `reset user quota zeroes used and redirects to detail`() = testApplication {
        seed()
        // 抬高 used
        transaction(Db.instance) {
            Accounts.update({ Accounts.id eq 1 }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed + 20 }
            }
        }
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/users/1/reset-quota") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/users/1", r.headers[HttpHeaders.Location])

        val used = transaction(Db.instance) {
            Accounts.selectAll().where { Accounts.id eq 1 }.single()[Accounts.llmCallsUsed]
        }
        assertEquals(0, used)
    }

    @Test
    fun `set user limit updates limit and redirects`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/users/1/limit") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("limit=250")
        }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/users/1", r.headers[HttpHeaders.Location])
        val limit = transaction(Db.instance) {
            Accounts.selectAll().where { Accounts.id eq 1 }.single()[Accounts.llmCallsLimit]
        }
        assertEquals(250, limit)
    }

    @Test
    fun `reset guest device quota redirects to devices`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1; it[Accounts.email] = "a@x.com"; it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"; it[Accounts.llmCallsUsed] = 0; it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = 5; it[AnonymousDevices.deviceId] = "abcdef1234567890"
                it[AnonymousDevices.llmCallsUsed] = 9; it[AnonymousDevices.createdAt] = 1L; it[AnonymousDevices.lastSeenAt] = 2L
            }
        }
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.post("/admin/devices/5/reset-quota") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/devices", r.headers[HttpHeaders.Location])
        val used = transaction(Db.instance) {
            AnonymousDevices.selectAll().where { AnonymousDevices.id eq 5 }.single()[AnonymousDevices.llmCallsUsed]
        }
        assertEquals(0, used)
    }
```

Ensure `org.jetbrains.exposed.sql.update` is imported in the test file (for the `Accounts.update` in test 1).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests '*.AdminRoutesTest.reset*' --tests '*.AdminRoutesTest.set*'`
Expected: FAIL — 404 (routes not registered yet).

- [ ] **Step 3: Add the three routes**

In `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`, add these handlers inside `route("/admin")` (place after the existing `post("/users/{id}/unrevoke")` block):

```kotlin
        // 重置单账号已用额度（清零计数、保留 llm_call_log 历史）。
        post("/users/{id}/reset-quota") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.resetQuota(id)
            call.respondRedirect("/admin/users/$id")
        }

        // 修改单账号调用上限（limit=0 等价禁用但保留 token）。
        post("/users/{id}/limit") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            val limit = call.receiveParameters()["limit"]?.toIntOrNull()
            if (id != null && limit != null && limit >= 0) {
                AccountService.setLimit(id, limit)
            }
            call.respondRedirect("/admin/users/$id")
        }
```

And add the guest reset route near the existing `post("/devices/{id}/delete")`:

```kotlin
        // 重置访客设备已用额度。
        post("/devices/{id}/reset-quota") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) GuestService.resetQuota(id)
            call.respondRedirect("/admin/devices")
        }
```

- [ ] **Step 4: Add UI — user detail reset button + limit form**

4a. In `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`, the `userDetailPage` needs the current `used`/`limit`. Change its signature and the call site.

First extend `UserDetail` in `AdminQueries.kt` — add two fields:

```kotlin
data class UserDetail(
    val id: Int,
    val email: String,
    val status: String,
    val createdAt: Long,
    val llmCallsUsed: Int,      // 新增
    val llmCallsLimit: Int,     // 新增
    val calls: Long,
    val totalTokens: Long,
    val cost: Double,
    val blocked: Long,
    val bytes: Long,
    val lastActive: Long?,
)
```

In `AdminQueries.userDetail`, populate them from `acc` (the loaded account row). The existing code reads `val acc = Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()`. In the returned `UserDetail(...)` add:

```kotlin
            llmCallsUsed = acc[Accounts.llmCallsUsed],
            llmCallsLimit = acc[Accounts.llmCallsLimit],
```

4b. Update `userDetailPage` signature + body in `AdminViews.kt`. In the `actions-bar` (after the delete form), add a reset button:

```kotlin
                if (d.status != "deleted") {
                    form(action = "/admin/users/${d.id}/reset-quota", method = FormMethod.post, classes = "inline") {
                        attributes["onsubmit"] = "return confirm('重置已用额度？\\n\\n仅清零当前计数器（${d.llmCallsUsed}/${d.llmCallsLimit}），历史调用记录保留。')"
                        input(type = InputType.submit, classes = "btn") { value = "重置已用额度" }
                    }
                }
```

And in the `cards` div, replace the "状态" card block to also show quota and add a limit form after the cards div. Concretely, after the existing `div("cards") { ... }` closing, add:

```kotlin
            div("card limit-card") {
                div("card-label") { +"额度上限（当前 ${d.llmCallsUsed} / ${d.llmCallsLimit}）" }
                form(action = "/admin/users/${d.id}/limit", method = FormMethod.post, classes = "inline") {
                    input(type = InputType.number, name = "limit") {
                        value = d.llmCallsLimit.toString()
                        attributes["min"] = "0"
                        style = "width:96px"
                    }
                    +" "
                    input(type = InputType.submit, classes = "btn-sm btn-primary") { value = "改上限" }
                }
            }
```

- [ ] **Step 5: Add UI — device page reset button**

In `AdminViews.devicesPage`, in the per-row 操作 `td` (where the delete form lives), add a reset form before it:

```kotlin
                                form(action = "/admin/devices/${d.id}/reset-quota", method = FormMethod.post, classes = "inline") {
                                    attributes["onsubmit"] = "return confirm('重置该设备已用额度？')"
                                    input(type = InputType.submit, classes = "btn-sm") { value = "重置" }
                                }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew -p server test --tests '*.AdminRoutesTest' --tests '*.AdminQueriesTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt
git commit -m "feat(server): 后台额度重置 + 单用户改上限入口"
```

---

## Task 7: Settings page (`/admin/settings`)

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt` (append)

- [ ] **Step 1: Write the failing test**

Append to `AdminRoutesTest.kt`:

```kotlin
    @Test
    fun `settings page round-trips free and guest quota`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices, ServerSettings)
        SettingsService.load()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        // GET 渲染当前值
        val get = c.get("/admin/settings") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, get.status)
        assertTrue(get.bodyAsText().contains("额度默认值"))

        // POST 改值
        val post = c.post("/admin/settings") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("free_llm_quota=888&guest_llm_quota=66")
        }
        assertEquals(HttpStatusCode.Found, post.status)
        assertEquals("/admin/settings", post.headers[HttpHeaders.Location])
        assertEquals(888, SettingsService.snapshot().freeLlmQuota)
        assertEquals(66, SettingsService.snapshot().guestLlmQuota)
    }
```

Add imports in the test file: `com.mamba.picme.server.db.ServerSettings`, `com.mamba.picme.server.config.SettingsService`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server test --tests '*.AdminRoutesTest.settings*'`
Expected: FAIL — 404 (`/admin/settings` not registered).

- [ ] **Step 3: Add the view**

In `AdminViews.kt`, add a new page function (place after `trafficPage`):

```kotlin
    fun settingsPage(snap: SettingsService.Snapshot, message: String? = null): String = createHTML().html {
        adminHead("设置 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"额度默认值（全局）" }
            if (message != null) p("err") { +message }
            div("card apk-info-card") {
                p("meta") {
                    +"影响：free 用于新注册账号初始上限；guest 用于未注册访客设备上限。"
                    br(); +"已注册账号的上限按行独立，请在「用户详情」页单独调整。"
                }
            }
            form(action = "/admin/settings", method = FormMethod.post, classes = "chan-form") {
                p {
                    label { +"新注册账号上限（free，>0）" }
                    br()
                    input(type = InputType.number, name = "free_llm_quota") {
                        value = snap.freeLlmQuota.toString()
                        attributes["min"] = "1"
                        style = "width:160px"
                    }
                }
                p {
                    label { +"访客设备上限（guest，>0）" }
                    br()
                    input(type = InputType.number, name = "guest_llm_quota") {
                        value = snap.guestLlmQuota.toString()
                        attributes["min"] = "1"
                        style = "width:160px"
                    }
                }
                div("form-actions") {
                    span("form-actions-right") {
                        input(type = InputType.submit, classes = "btn") { value = "保存" }
                    }
                }
            }
        }
    }
```

Add imports in `AdminViews.kt`: `import com.mamba.picme.server.config.SettingsService`.

- [ ] **Step 4: Add the routes + nav link**

In `AdminRoutes.kt`:

4a. Add handlers (place near the other GET pages, e.g. after `get("/traffic")`):

```kotlin
        get("/settings") {
            if (!call.adminGuard(adminToken)) return@get
            val msg = call.request.queryParameters["err"]
            call.respondText(AdminViews.settingsPage(SettingsService.snapshot(), msg), ContentType.Text.Html)
        }

        post("/settings") {
            if (!call.adminGuard(adminToken)) return@post
            val params = call.receiveParameters()
            val free = params["free_llm_quota"]?.toIntOrNull()
            val guest = params["guest_llm_quota"]?.toIntOrNull()
            if (free == null || guest == null || free <= 0 || guest <= 0) {
                call.respondText(
                    AdminViews.settingsPage(SettingsService.snapshot(), "参数错误：两个值都必须是正整数"),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            SettingsService.update(free, guest)
            call.respondRedirect("/admin/settings")
        }
```

4b. Add the nav link. In `AdminViews.navBar()`, between the "渠道" and "APK" links, insert:

```kotlin
                a("/admin/settings", classes = "nav-link") { +"设置" }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew -p server test --tests '*.AdminRoutesTest.settings*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt
git commit -m "feat(server): /admin/settings 全局额度默认值页"
```

---

## Task 8: Overview cumulative metrics

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt` (`OverviewRow` + `overview()`)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt` (`overviewPage` cards)
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt` (update `TestDb.init` + add assertions)

- [ ] **Step 1: Update existing tests + add cumulative assertions**

In `AdminQueriesTest.kt`:

1a. Both overview tests currently call `TestDb.init(Accounts, LlmCallLogs)`. `overview()` will now also count `AnonymousDevices`, so add that table. Change both to:

```kotlin
TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
```

(2 occurrences: in `overview users detail...` and `empty db overview...`).

1b. In `overview users detail recent and daily aggregates`, after the existing overview assertions, add cumulative assertions. The seeded data has: 2 accounts (both active → totalUsers 2), 0 devices, 3 ok logs total (today-A, yesterday-A, today-B) → totalCalls 3; tokens 150+300+15=465; cost 1.0+2.0+0.5=3.5. Add:

```kotlin
        assertEquals(2L, o.totalUsers)      // 两账号均 active（已有断言；保留）
        assertEquals(0L, o.totalDevices)
        assertEquals(3L, o.totalCalls)      // 三条 ok（今日×2 + 昨日×1）
        assertEquals(465L, o.totalTokens)   // 150 + 300 + 15
        assertEquals(3.5, o.totalCost, 0.000001) // 1.0 + 2.0 + 0.5
```

(Remove the now-duplicate `assertEquals(2L, o.totalUsers)` if it collides — keep one.)

1c. In `empty db overview is zeros and no exceptions`, add:

```kotlin
        assertEquals(0L, o.totalDevices)
        assertEquals(0L, o.totalCalls)
        assertEquals(0L, o.totalTokens)
        assertEquals(0.0, o.totalCost, 0.0)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests '*.AdminQueriesTest'`
Expected: FAIL — `totalDevices`/`totalCalls`/`totalTokens`/`totalCost` unresolved on `OverviewRow`.

- [ ] **Step 3: Extend `OverviewRow` + `overview()`**

In `AdminQueries.kt`, add the import `import org.jetbrains.exposed.sql.or` (needed for the active/revoked filter).

Replace the `OverviewRow` data class:

```kotlin
data class OverviewRow(
    // 今日
    val totalUsers: Long,
    val newUsersToday: Long,
    val callsToday: Long,
    val tokensToday: Long,
    val costToday: Double,
    val bytesToday: Long,
    val blockedToday: Long,
    // 累计（新增）
    val totalDevices: Long,
    val totalCalls: Long,
    val totalTokens: Long,
    val totalCost: Double,
)
```

Replace the `overview(now)` body. Keep the today-scan byte-for-byte identical to the original (so existing test expectations hold), and add a separate all-time scan restricted to `ok`:

```kotlin
    suspend fun overview(now: Long): OverviewRow = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val startToday = startOfTodayMs(now)
        val totalUsers = Accounts.selectAll().where {
            (Accounts.status eq "active") or (Accounts.status eq "revoked")
        }.count()
        val totalDevices = AnonymousDevices.selectAll().count()
        val newToday = Accounts.selectAll().where { Accounts.createdAt greaterEq startToday }.count()

        // 今日（与原逻辑一致：跨所有今日行累加 tokens/cost/bytes）
        var callsToday = 0L
        var blockedToday = 0L
        var tokensToday = 0L
        var costToday = 0.0
        var bytesToday = 0L
        LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq startToday }.forEach { r ->
            val s = r[LlmCallLogs.status]
            if (s == "ok") callsToday += 1
            if (s.startsWith("blocked_")) blockedToday += 1
            tokensToday += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            costToday += r[LlmCallLogs.costCny]
            bytesToday += r[LlmCallLogs.respBytes].toLong()
        }

        // 累计（仅 ok 行，全量）
        var totalCalls = 0L
        var totalTokens = 0L
        var totalCost = 0.0
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            totalCalls += 1
            totalTokens += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            totalCost += r[LlmCallLogs.costCny]
        }

        OverviewRow(
            totalUsers = totalUsers,
            newUsersToday = newToday,
            callsToday = callsToday,
            tokensToday = tokensToday,
            costToday = costToday,
            bytesToday = bytesToday,
            blockedToday = blockedToday,
            totalDevices = totalDevices,
            totalCalls = totalCalls,
            totalTokens = totalTokens,
            totalCost = totalCost,
        )
    }
```

> Two scans of `llm_call_log` is intentional — clarity over a micro-optimization at research-project scale. Today-semantics are preserved exactly, so the existing `AdminQueriesTest` today-assertions (tokens 165, cost 1.5, bytes 1124, blocked 1, calls 2) still hold.

- [ ] **Step 4: Update `overviewPage` cards**

In `AdminViews.kt` `overviewPage`, split into two card groups. Replace the existing `div("cards") { ... }` block with:

```kotlin
            h2 { +"累计" }
            div("cards") {
                statCard("总用户数", ov.totalUsers.toString())
                statCard("总设备数", ov.totalDevices.toString())
                statCard("累计调用", compactCount(ov.totalCalls.toDouble()))
                statCard("累计 Token", compactCount(ov.totalTokens.toDouble()))
                statCard("累计成本 ¥", compactCost(ov.totalCost))
            }
            h2 { +"今日（UTC 自然日）" }
            div("cards") {
                statCard("今日新增", ov.newUsersToday.toString())
                statCard("今日调用", ov.callsToday.toString())
                statCard("今日 Token", compactCount(ov.tokensToday.toDouble()))
                statCard("今日成本 ¥", compactCost(ov.costToday))
                statCard("今日出口字节", compactCount(ov.bytesToday.toDouble()))
                statCard("今日 blocked", ov.blockedToday.toString())
            }
```

(`compactCount`/`compactCost` are private in `AdminViews`; they are accessible inside the object.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew -p server test --tests '*.AdminQueriesTest'`
Expected: PASS (all overview assertions).

- [ ] **Step 6: Run AdminRoutesTest overview sanity**

Run: `./gradlew -p server test --tests '*.AdminRoutesTest.full*'`
Expected: PASS (overview page renders, contains "概览" — the h1 still says 概览; the body contains "累计").

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "feat(server): 概览页累计指标(用户/设备/Token/调用/成本)"
```

---

## Task 9: `llm_channel` balance columns + DeepSeek backfill

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` (`LlmChannels` +3 cols)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt` (backfill)
- Create: `server/migrations/008_llm_channel_balance.sql` (reference DDL)
- Test: `server/src/test/kotlin/com/mamba/picme/server/db/LlmChannelsTableTest.kt` (append; this file already exists — read it first to match style)

- [ ] **Step 1: Write the failing test**

Append to `LlmChannelsTableTest.kt` (read it first; below assumes it inits `TestDb.init(LlmChannels)` similarly to `ChannelRepositoryTest`). If the file uses a different helper, mirror it:

```kotlin
    @Test
    fun `balance columns default empty and nullable checked_at`() {
        TestDb.init(LlmChannels)
        transaction(Db.instance) {
            val id = LlmChannels.insert {
                it[LlmChannels.name] = "T"
                it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://x"
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = "{}"
                it[LlmChannels.createdAt] = 1L
                it[LlmChannels.updatedAt] = 1L
            } get LlmChannels.id
            val row = LlmChannels.selectAll().where { LlmChannels.id eq id }.single()
            assertEquals("", row[LlmChannels.balanceUrl])
            assertEquals("", row[LlmChannels.balanceJson])
            assertNull(row[LlmChannels.balanceCheckedAt])
        }
    }
```

Add imports as needed (`assertNull`, `insert`, `selectAll`, `transaction`, `TestDb`, `LlmChannels`, `Db`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server test --tests '*.LlmChannelsTableTest*'`
Expected: FAIL — `balanceUrl`/`balanceJson`/`balanceCheckedAt` unresolved.

- [ ] **Step 3: Add the 3 columns**

In `Tables.kt`, inside `object LlmChannels` (after `defaultModel`), add:

```kotlin
    val balanceUrl = varchar("balance_url", 512).default("")        // 空 = 该渠道无余额 API
    val balanceJson = text("balance_json").default("")              // 上游响应原文（缓存）
    val balanceCheckedAt = long("balance_checked_at").nullable()    // 上次成功刷新时间
```

(`createMissingTablesAndColumns(LlmChannels)` already runs in `Migrations`, so existing DBs get the columns on next boot.)

- [ ] **Step 4: Create reference DDL**

Create `server/migrations/008_llm_channel_balance.sql`:

```sql
-- 参考 DDL（运行时由 Exposed SchemaUtils.createMissingTablesAndColumns 自动补列）
ALTER TABLE llm_channel ADD COLUMN balance_url        VARCHAR(512) DEFAULT '';
ALTER TABLE llm_channel ADD COLUMN balance_json       TEXT         DEFAULT '';
ALTER TABLE llm_channel ADD COLUMN balance_checked_at INTEGER;
```

- [ ] **Step 5: Add DeepSeek balance URL backfill**

In `Migrations.kt`, add a backfill called from `run()` (after `backfillDefaultModels()`):

5a. In `run()`, after `backfillDefaultModels()`, add:

```kotlin
        backfillBalanceUrls()
```

5b. Add the function (next to `backfillDefaultModels`) and the constant map:

```kotlin
    private val CHANNEL_BALANCE_URL = mapOf(
        "DeepSeek 直连" to "https://api.deepseek.com/user/balance",
    )

    /**
     * 幂等回填：DeepSeek 直连渠道若 balance_url 为空则补上，让老库升级后即可用余额刷新。
     */
    internal fun backfillBalanceUrls() {
        transaction(Db.instance) {
            LlmChannels.selectAll().toList().forEach { row ->
                if (row[LlmChannels.balanceUrl].isBlank()) {
                    val url = CHANNEL_BALANCE_URL[row[LlmChannels.name]] ?: return@forEach
                    LlmChannels.update({ LlmChannels.id eq row[LlmChannels.id] }) {
                        it[LlmChannels.balanceUrl] = url
                    }
                }
            }
        }
    }
```

(`LlmChannels` is already imported; `update`/`insert`/`selectAll`/`transaction` already imported.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew -p server test --tests '*.LlmChannelsTableTest*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt \
        server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt \
        server/migrations/008_llm_channel_balance.sql \
        server/src/test/kotlin/com/mamba/picme/server/db/LlmChannelsTableTest.kt
git commit -m "feat(server): llm_channel 加 balance 列 + DeepSeek 余额 URL 回填"
```

---

## Task 10: `ChannelRepository` extensions for balance

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRepository.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelConfig.kt` (no — balance is not a runtime-proxy concern; keep ChannelConfig untouched)
- Test: `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRepositoryTest.kt` (append)

- [ ] **Step 1: Write the failing tests**

Append to `ChannelRepositoryTest.kt`:

```kotlin
    @Test
    fun `create and update carry balanceUrl`() = runBlocking {
        val id = ChannelRepository.create(input().copy(balanceUrl = "https://api.deepseek.com/user/balance"))
        assertEquals("https://api.deepseek.com/user/balance", ChannelRepository.get(id)!!.balanceUrl)

        ChannelRepository.update(id, input().copy(balanceUrl = ""))
        assertEquals("", ChannelRepository.get(id)!!.balanceUrl)
    }

    @Test
    fun `balanceConfig returns url token and authStyle for balance call`() = runBlocking {
        val id = ChannelRepository.create(input().copy(apiToken = "sk-bal-1234"))
        val cfg = ChannelRepository.balanceConfig(id)
        assertEquals("direct", cfg?.kind) // 仅作存在性断言；关键字段在下面
        assertNotNull(cfg)
        assertEquals("sk-bal-1234", cfg!!.apiToken)
        assertEquals(AuthStyle.BEARER, cfg.authStyle)
    }

    @Test
    fun `balanceConfig returns null for missing channel`() = runBlocking {
        assertNull(ChannelRepository.balanceConfig(9999))
    }

    @Test
    fun `saveBalanceCache writes json and checkedAt and cached reads back`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.saveBalanceCache(id, """{"x":1}""", 1_700_000_000_000L)
        val cached = ChannelRepository.cachedBalance(id)
        assertEquals("""{"x":1}""", cached?.json)
        assertEquals(1_700_000_000_000L, cached?.checkedAt)
    }
```

Add imports: `org.junit.Assert.assertNotNull`, and ensure `input()` helper in the file can take `.copy(balanceUrl = ...)` — since `ChannelInput` is a data class, adding `balanceUrl` with a default keeps existing `input()` calls compiling.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests '*.ChannelRepositoryTest'`
Expected: FAIL — `balanceUrl`/`balanceConfig`/`saveBalanceCache`/`cachedBalance` unresolved.

- [ ] **Step 3: Extend the repository**

In `ChannelRepository.kt`:

3a. `ChannelRow` — add fields:

```kotlin
data class ChannelRow(
    val id: Int,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val authStyle: String,
    val apiTokenMasked: String,
    val modelMap: Map<String, String>,
    val enabled: Boolean,
    val isActive: Boolean,
    val defaultModel: String,
    val hasToken: Boolean,
    val balanceUrl: String,        // 新增
    val balanceJson: String,       // 新增
    val balanceCheckedAt: Long?,   // 新增
)
```

3b. `ChannelInput` — add field with default (so existing callers compile):

```kotlin
data class ChannelInput(
    val name: String,
    val kind: String,
    val baseUrl: String,
    val authStyle: String,
    val apiToken: String,
    val modelMap: Map<String, String>,
    val enabled: Boolean,
    val defaultModel: String = "",
    val balanceUrl: String = "",
)
```

3c. A small DTO for the balance call + cache read:

```kotlin
/** 余额调用所需的最小配置（明文 token，仅供 ChannelBalanceService）。 */
data class BalanceConfig(
    val kind: String,
    val balanceUrl: String,
    val apiToken: String,
    val authStyle: AuthStyle,
)

/** 缓存的余额响应。 */
data class BalanceCache(val json: String, val checkedAt: Long?)
```

3d. `create` and `update` — write `balanceUrl`. In `create`'s `insert { }` add:

```kotlin
            it[LlmChannels.balanceUrl] = input.balanceUrl
```

In `update`'s `update { }` add:

```kotlin
            it[LlmChannels.balanceUrl] = input.balanceUrl
```

3e. `toRow()` — populate the new fields:

```kotlin
        balanceUrl = this[LlmChannels.balanceUrl],
        balanceJson = this[LlmChannels.balanceJson],
        balanceCheckedAt = this[LlmChannels.balanceCheckedAt],
```

3f. Add three new functions (place after `rawToken`):

```kotlin
    /** 取余额调用所需配置（明文 token）；balance_url 空 → 返回的 balanceUrl 为空，调用方据此跳过。 */
    suspend fun balanceConfig(id: Int): BalanceConfig? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()?.let {
            BalanceConfig(
                kind = it[LlmChannels.kind],
                balanceUrl = it[LlmChannels.balanceUrl],
                apiToken = it[LlmChannels.apiToken],
                authStyle = AuthStyle.valueOf(it[LlmChannels.authStyle].uppercase()),
            )
        }
    }

    /** 落缓存：写 balance_json + balance_checked_at。 */
    suspend fun saveBalanceCache(id: Int, json: String, checkedAt: Long): Boolean =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val rows = LlmChannels.update({ LlmChannels.id eq id }) {
                it[LlmChannels.balanceJson] = json
                it[LlmChannels.balanceCheckedAt] = checkedAt
            }
            rows > 0
        }

    /** 读缓存。 */
    suspend fun cachedBalance(id: Int): BalanceCache? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        LlmChannels.selectAll().where { LlmChannels.id eq id }.firstOrNull()?.let {
            BalanceCache(it[LlmChannels.balanceJson], it[LlmChannels.balanceCheckedAt])
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p server test --tests '*.ChannelRepositoryTest'`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/ChannelRepository.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/ChannelRepositoryTest.kt
git commit -m "feat(server): ChannelRepository 余额列读写 + balanceConfig"
```

---

## Task 11: `ChannelBalanceService` — refresh + parse

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/ChannelBalanceService.kt` (full impl)
- Test: `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelBalanceServiceTest.kt` (create)

- [ ] **Step 1: Write the failing tests**

Create `server/src/test/kotlin/com/mamba/picme/server/llm/ChannelBalanceServiceTest.kt`:

```kotlin
package com.mamba.picme.server.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelBalanceServiceTest {

    @Test
    fun `parses deepseek balance into cny display`() {
        val json = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"10.03","granted_balance":"10.03","topped_up_balance":"0.00"}]}"""
        assertEquals("¥10.03", parseDeepSeekBalance(json))
    }

    @Test
    fun `parses usd balance with currency code`() {
        val json = """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"5.5"}]}"""
        assertEquals("USD 5.5", parseDeepSeekBalance(json))
    }

    @Test
    fun `is_available false returns dash`() {
        val json = """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"0"}]}"""
        assertEquals("—", parseDeepSeekBalance(json))
    }

    @Test
    fun `missing balance_infos returns null`() {
        val json = """{"is_available":true}"""
        assertNull(parseDeepSeekBalance(json))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(parseDeepSeekBalance("not json"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests '*.ChannelBalanceServiceTest'`
Expected: FAIL — `parseDeepSeekBalance` unresolved.

- [ ] **Step 3: Write the full service**

Replace the entire contents of `ChannelBalanceService.kt`:

```kotlin
package com.mamba.picme.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger("picme-llm")

/**
 * 调用上游 balance API 并把响应缓存进 [LlmChannels.balanceJson]。
 * 仅由后台「刷新余额」按钮触发（POST /admin/channels/{id}/refresh-balance）；
 * 页面加载只读缓存，不发起外部调用。
 */
class ChannelBalanceService(
    val httpClient: HttpClient,
    val timeoutMs: Long = 8_000,
) {
    /**
     * 拉取并缓存余额。返回是否成功更新缓存。
     * balance_url 空 / 上游非 2xx / 超时 / 异常 → 不覆盖旧缓存，返回 false。
     */
    suspend fun refresh(channelId: Int): Boolean {
        val cfg = ChannelRepository.balanceConfig(channelId) ?: return false
        if (cfg.balanceUrl.isBlank() || cfg.apiToken.isBlank()) return false

        val (headerName, headerValue) = when (cfg.authStyle) {
            AuthStyle.BEARER -> "Authorization" to "Bearer ${cfg.apiToken}"
            AuthStyle.CF_AIG -> "cf-aig-authorization" to "Bearer ${cfg.apiToken}"
        }
        return try {
            val resp = httpClient.get(cfg.balanceUrl) {
                header(headerName, headerValue)
            }
            if (!resp.status.isSuccess()) {
                logger.info("Balance refresh channel={} status={}", channelId, resp.status.value)
                return false
            }
            val body = resp.bodyAsText()
            ChannelRepository.saveBalanceCache(channelId, body, Instant.now().toEpochMilli())
            true
        } catch (e: Exception) {
            logger.warn("Balance refresh channel={} failed: {}", channelId, e.message)
            false
        }
    }

    /** 读缓存的展示串 + 检查时间；无缓存或无法解析返回 null/(null)。 */
    suspend fun cached(channelId: Int): Cached {
        val c = ChannelRepository.cachedBalance(channelId) ?: return Cached(null, null)
        val display = parseDeepSeekBalance(c.json)
        return Cached(display ?: if (c.json.isBlank()) null else "(解析失败)", c.checkedAt)
    }

    data class Cached(val display: String?, val checkedAt: Long?)
}

/**
 * 解析 DeepSeek 形态余额响应。返回展示串（如 "¥10.03"），无可用信息返回 null。
 * is_available=false → "—"；currency=CNY → "¥" 前缀，否则原样附币种。
 */
fun parseDeepSeekBalance(json: String): String? {
    return try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val available = (obj["is_available"] as? JsonPrimitive)?.content
        if (available == "false") return "—"
        val infos = obj["balance_infos"] as? JsonArray ?: return null
        val first = infos.firstOrNull() as? JsonObject ?: return null
        val total = (first["total_balance"] as? JsonPrimitive)?.content ?: return null
        val currency = (first["currency"] as? JsonPrimitive)?.content ?: ""
        when (currency.uppercase()) {
            "CNY" -> "¥$total"
            "USD" -> "USD $total"
            else -> if (currency.isBlank()) total else "$currency $total"
        }
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p server test --tests '*.ChannelBalanceServiceTest'`
Expected: PASS (all five).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/ChannelBalanceService.kt \
        server/src/test/kotlin/com/mamba/picme/server/llm/ChannelBalanceServiceTest.kt
git commit -m "feat(server): ChannelBalanceService 上游余额拉取+解析+缓存"
```

---

## Task 12: `AdminQueries.channelUsage`

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt` (append)

- [ ] **Step 1: Write the failing test**

Append to `AdminQueriesTest.kt`:

```kotlin
    @Test
    fun `channelUsage aggregates by provider for ok calls`() = runBlocking {
        TestDb.init(LlmCallLogs)
        logRow(1, "deepseek-chat", "DeepSeek 直连", 100, 50, 150, 1.0, 100, "ok", 1_000L)
        logRow(1, "deepseek-chat", "DeepSeek 直连", 10, 5, 15, 0.1, 50, "ok", 2_000L)
        logRow(1, "deepseek-chat", "DeepSeek 直连", null, null, null, 0.0, 0, "blocked_quota", 3_000L)
        logRow(1, "kimi-k2.6", "Kimi 直连", 10, 5, 15, 0.5, 80, "ok", 4_000L)

        val usage = AdminQueries.channelUsage()
        val ds = usage.getValue("DeepSeek 直连")
        assertEquals(2L, ds.calls)        // 不计 blocked
        assertEquals(165L, ds.tokens)     // 150 + 15
        assertEquals(1.1, ds.cost, 0.000001)
        assertEquals(1L, usage.getValue("Kimi 直连").calls)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server test --tests '*.AdminQueriesTest.channelUsage*'`
Expected: FAIL — `channelUsage` unresolved.

- [ ] **Step 3: Implement**

In `AdminQueries.kt`, add the DTO near `OverviewRow`:

```kotlin
data class ChannelUsage(
    val provider: String,
    val calls: Long,
    val tokens: Long,
    val cost: Double,
)
```

And the query (place after `overview`):

```kotlin
    /** 按 provider 聚合成功调用消耗（全量）。供渠道页展示。 */
    suspend fun channelUsage(): Map<String, ChannelUsage> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val acc = HashMap<String, ChannelUsage>()
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            val p = r[LlmCallLogs.provider]
            val tokens = r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            val cost = r[LlmCallLogs.costCny]
            val cur = acc[p]
            acc[p] = if (cur == null) ChannelUsage(p, 1, tokens, cost)
            else ChannelUsage(p, cur.calls + 1, cur.tokens + tokens, cur.cost + cost)
        }
        acc
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew -p server test --tests '*.AdminQueriesTest.channelUsage*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "feat(server): AdminQueries.channelUsage 按渠道聚合消耗"
```

---

## Task 13: Channels page UI — consumption, balance, refresh route

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt` (channels GET + refresh-balance POST; parse `balance_url` in `parseChannelInput`)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt` (`channelsPage` columns; `channelFormPage` field)
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt` (append)

- [ ] **Step 1: Write the failing test**

Append to `AdminChannelsRoutesTest.kt`:

```kotlin
    @Test
    fun `channels page shows consumption columns and balance header`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, LlmChannels)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1; it[Accounts.email] = "a@x.com"; it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"; it[Accounts.llmCallsUsed] = 0; it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1L
            }
            LlmChannels.insert {
                it[LlmChannels.name] = "DeepSeek 直连"; it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.deepseek.com/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"; it[LlmChannels.apiToken] = "sk-1"
                it[LlmChannels.modelMapJson] = "{}"; it[LlmChannels.enabled] = 1; it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = 1L; it[LlmChannels.updatedAt] = 1L
                it[LlmChannels.balanceUrl] = "https://api.deepseek.com/user/balance"
            }
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = 1; it[LlmCallLogs.model] = "deepseek-chat"
                it[LlmCallLogs.provider] = "DeepSeek 直连"; it[LlmCallLogs.totalTokens] = 200
                it[LlmCallLogs.costCny] = 1.5; it[LlmCallLogs.respBytes] = 0; it[LlmCallLogs.status] = "ok"
                it[LlmCallLogs.createdAt] = 2L
            }
        }
        ChannelRegistry.reload()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        val r = c.get("/admin/channels") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r.status)
        val html = r.bodyAsText()
        assertTrue(html.contains("余额"))
        assertTrue(html.contains("消耗"))
        assertTrue(html.contains("refresh-balance")) // DeepSeek 渠道有 balance_url → 渲染刷新按钮
    }

    @Test
    fun `refresh balance redirects back to channels`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, LlmChannels)
        transaction(Db.instance) {
            LlmChannels.insert {
                it[LlmChannels.name] = "DeepSeek 直连"; it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.deepseek.com/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"; it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = "{}"; it[LlmChannels.enabled] = 1; it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = 1L; it[LlmChannels.updatedAt] = 1L
                it[LlmChannels.balanceUrl] = "https://api.deepseek.com/user/balance"
            }
        }
        ChannelRegistry.reload()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        // 没有真实上游 / 无 token → refresh 返回 false，但路由仍 302 回列表（不报错）
        val r = c.post("/admin/channels/1/refresh-balance") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/admin/channels", r.headers[HttpHeaders.Location])
    }
```

Add the `balance` field + imports to `AdminChannelsRoutesTest` as already done in Task 3 (if not present). Add imports for `LlmChannels`, `LlmCallLogs`, `Accounts`, `insert`, `transaction`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests '*.AdminChannelsRoutesTest.channels*' --tests '*.AdminChannelsRoutesTest.refresh*'`
Expected: FAIL — routes/text missing.

- [ ] **Step 3: Parse `balance_url` in `parseChannelInput`**

In `AdminRoutes.kt`, in `parseChannelInput()`, the returned `ChannelInput(...)` — add the new field:

```kotlin
    return ChannelInput(
        name = name,
        kind = (params["kind"] ?: "direct").trim(),
        baseUrl = baseUrl,
        authStyle = (params["auth_style"] ?: "bearer").trim(),
        apiToken = (params["api_token"] ?: "").trim(),
        modelMap = modelMap,
        enabled = (params["enabled"] ?: "0") == "1",
        defaultModel = (params["default_model"] ?: "").trim(),
        balanceUrl = (params["balance_url"] ?: "").trim(),
    )
```

- [ ] **Step 4: Add the refresh-balance route**

In `AdminRoutes.kt`, near the other channel POST routes (after `/channels/{id}/activate`):

```kotlin
        // 刷新上游余额（缓存+手动刷新策略）。失败不报错，列表显「—」。
        post("/channels/{id}/refresh-balance") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) balanceService.refresh(id)
            call.respondRedirect("/admin/channels")
        }
```

- [ ] **Step 5: Pass consumption + balance into the channels page**

In the `get("/channels")` handler, change to fetch usage + per-channel cached balance and pass them to the view:

```kotlin
        get("/channels") {
            if (!call.adminGuard(adminToken)) return@get
            val channels = ChannelRepository.list()
            val usage = AdminQueries.channelUsage()
            val balances = channels.associate { it.id to balanceService.cached(it.id) }
            call.respondText(AdminViews.channelsPage(channels, usage, balances), ContentType.Text.Html)
        }
```

Update the two error-path callers of `channelsPage` in `post("/channels")` to the new signature. The input-parse-failure branch:

```kotlin
                call.respondText(
                    AdminViews.channelsPage(
                        ChannelRepository.list(), AdminQueries.channelUsage(), emptyMap(),
                        error = "表单参数错误：检查 model_map 格式（每行 请求名=上游名）",
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
```

The create-exception branch:

```kotlin
                call.respondText(
                    AdminViews.channelsPage(
                        ChannelRepository.list(), AdminQueries.channelUsage(), emptyMap(),
                        error = "创建失败：名称可能重复",
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
```

- [ ] **Step 6: Extend `channelsPage` signature + columns**

In `AdminViews.kt`, change `channelsPage`:

```kotlin
    fun channelsPage(
        channels: List<ChannelRow>,
        usage: Map<String, AdminQueries.ChannelUsage>,
        balances: Map<Int, ChannelBalanceService.Cached>,
        error: String? = null,
    ): String = createHTML().html {
```

Add import: `import com.mamba.picme.server.llm.ChannelBalanceService`.

In the table header, add two `<th>` before `col-actions`:

```kotlin
                    th { +"消耗(调用/Token/¥)" }
                    th(classes = "col-balance") { +"余额" }
```

In each row's `forEach { ch -> ... }`, after the `defaultModel` `td` and before the 启用 `td`, add:

```kotlin
                        td {
                            val u = usage[ch.name]
                            if (u == null) {
                                +"0 / 0 / 0.00"
                            } else {
                                +"${u.calls} / ${compactCount(u.tokens.toDouble())} / ${compactCost(u.cost)}"
                            }
                        }
                        td {
                            val b = balances[ch.id]
                            val display = b?.display ?: "—"
                            if (display != "—") span("active-badge") { +display } else +display
                            b?.checkedAt?.let {
                                br(); span("meta-inline") { +fmtTs(it) }
                            }
                        }
```

In the 操作 `td` (the `div("row-actions")`), add a refresh button when `ch.balanceUrl` is non-blank:

```kotlin
                                if (ch.balanceUrl.isNotBlank()) {
                                    form(action = "/admin/channels/${ch.id}/refresh-balance", method = FormMethod.post, classes = "inline") {
                                        input(type = InputType.submit, classes = "btn-sm") { value = "刷新余额" }
                                    }
                                }
```

Add a CSS class for the balance column width (in the `<style>` raw block, near `.col-active`):

```css
                        .col-balance{width:120px}
                        .meta-inline{font-size:11px;color:#999}
```

- [ ] **Step 7: Add `balance_url` field to the channel form**

In `AdminViews.channelFormPage`, after the `默认模型` `p { ... }` block, add:

```kotlin
                p {
                    label { +"余额 URL（留空=不支持余额查询）" }
                    br()
                    textInput(name = "balance_url") {
                        value = existing?.balanceUrl ?: ""
                        placeholder = "https://api.deepseek.com/user/balance"
                    }
                }
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew -p server test --tests '*.AdminChannelsRoutesTest'`
Expected: PASS (all channel tests, old and new).

- [ ] **Step 9: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminChannelsRoutesTest.kt
git commit -m "feat(server): 渠道页消耗聚合 + 余额缓存展示 + 刷新按钮"
```

---

## Task 14: Docs sync

**Files:**
- Modify: `server/AGENTS.md`
- Modify: `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`

- [ ] **Step 1: Update `server/AGENTS.md` route table (§3)**

Add rows to the route table:

```markdown
| GET | `/admin/settings` | P1 | ✅ | ADMIN_TOKEN | 全局额度默认值（free/guest） |
| POST | `/admin/settings` | P1 | ✅ | ADMIN_TOKEN | 更新全局额度默认值 |
| POST | `/admin/users/{id}/reset-quota` | P1 | ✅ | ADMIN_TOKEN | 清零单账号已用额度（保留历史） |
| POST | `/admin/users/{id}/limit` | P1 | ✅ | ADMIN_TOKEN | 改单账号调用上限 |
| POST | `/admin/devices/{id}/reset-quota` | P1 | ✅ | ADMIN_TOKEN | 清零访客设备已用额度 |
| POST | `/admin/channels/{id}/refresh-balance` | P1 | ✅ | ADMIN_TOKEN | 刷新上游余额缓存 |
```

- [ ] **Step 2: Update `server/AGENTS.md` §4/§5**

In the quota/auth section, add a line:

```markdown
- 额度默认值持久化于 `server_setting` 表（`free_llm_quota` / `guest_llm_quota`）；env 仅在首次启动播种，之后由 `/admin/settings` 管理，运行时经 `SettingsService` 内存快照下发（热路径零 DB 读）。
```

Bump the version line: `**版本**：0.6.4` → `**版本**：0.6.5` and `**最后更新**` → `2026-07-25`.

- [ ] **Step 3: Update `SERVER_IMPLEMENTATION_PLAN.md`**

If the file has an admin-backend section, add a bullet summarizing the four capabilities and the new `server_setting` table + `llm_channel` balance columns. If no such section exists, append a short subsection. (Read the file first; keep edits proportional.)

- [ ] **Step 4: Final full build + test**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL — all tests pass, no compile errors. (`detekt`/`ktlint` are not part of `:server build`; the repo-wide gates are noted as broken in project memory — `build` is the real gate here.)

- [ ] **Step 5: Commit**

```bash
git add server/AGENTS.md docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md
git commit -m "docs(server): 同步额度/上限/概览/渠道余额路由与设置项"
```

---

## Verification Checklist

- `./gradlew -p server build` — green.
- Manual smoke (local `./server/run-local.sh start` + admin cookie): overview shows 累计 + 今日; settings page round-trips; user detail reset/limit work; device reset works; channels page shows consumption + balance column; DeepSeek channel shows a 刷新余额 button.
- No `git add .` anywhere — each task stages only its listed files (working tree has unrelated release-observability changes that must stay uncommitted).
