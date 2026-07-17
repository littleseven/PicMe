# 账号删除 + 数据安全合规 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 Google Play 数据安全合规——为邮箱注册账号提供「软删除（90 天保留期）」能力、app 内「数据与隐私」说明页、删除入口与二次确认，并修正官网隐私政策的冲突声明。

**Architecture:** Server 新增 `AccountService.softDelete` + `purgeExpiredDeleted` + `DELETE /auth/account`（复用现有 `X-App-Token` 鉴权 interceptor，注入 `tokenHash`）。Client 新增 `PicMeAuthClient.deleteAccount`、设置页删除按钮 + 二次确认 `AlertDialog`、独立 `DataPrivacyScreen`。官网隐私政策页修正「无账户系统」冲突声明、补账号数据/删除方式/联系邮箱。

**Tech Stack:** Kotlin、Ktor + Exposed（server）、Jetpack Compose + Material3 + OkHttp（app）、HTML（docs-site）。Server 用 TDD（`TestDb` + `testApplication`）；Client UI 因无 mockwebserver 依赖，用 server 集成测试 + 手动验证覆盖。

**Spec:** `docs/superpowers/specs/2026-07-17-account-deletion-data-compliance-design.md`

**全局约定：**
- 提交信息用 Conventional Commits，结尾附 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 不得使用全限定名（`com.mamba.picme.*`）、通配符 import、隐式 `it` lambda 参数；日志 tag 形如 `PicMe:ServerAuth`。
- Task 1–4（server）必须在 Task 5（client）之前完成，因 client 依赖 server 的 `DELETE /auth/account` 契约。

---

## File Structure

### Server（`:server`，独立 Gradle 工程，用 `./gradlew -p server`）
| 文件 | 责任 |
|------|------|
| Create `server/migrations/006_account_soft_delete.sql` | `deleted_at` 列的参考 DDL |
| Modify `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` | `Accounts` 加 `deletedAt` 列定义 |
| Modify `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt` | `RETENTION_MS` 常量 + `softDelete` + `purgeExpiredDeleted` |
| Modify `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt` | `accountDeletionRoute()`（DELETE /auth/account） |
| Modify `server/src/main/kotlin/com/mamba/picme/server/Application.kt` | 注册路由 + 启动清理 |
| Create `server/src/test/.../auth/AccountServiceSoftDeleteTest.kt` | 软删除单元测试 |
| Create `server/src/test/.../auth/PurgeExpiredDeletedTest.kt` | 过期清理单元测试 |
| Create `server/src/test/.../routes/AccountDeletionRouteTest.kt` | DELETE 路由测试 |

### Client（`:app`）
| 文件 | 责任 |
|------|------|
| Modify `app/src/main/java/com/mamba/picme/data/remote/picme/PicMeAuthClient.kt` | `deleteAccount(token)` |
| Modify `app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt` | 删除按钮 + 二次确认对话框 |
| Create `app/src/main/java/com/mamba/picme/features/settings/DataPrivacyScreen.kt` | 数据与隐私说明页 |
| Modify `app/src/main/java/com/mamba/picme/navigation/Screen.kt` | `DataPrivacy` route |
| Modify `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt` | `onNavigateToDataPrivacy` 贯穿 + ACCOUNT 区入口 |
| Modify `app/src/main/java/com/mamba/picme/features/common/auth/EmailCodeAuthForm.kt` | 注册表单底部「数据与隐私」链接 |
| Modify `app/src/main/java/com/mamba/picme/MainActivity.kt` | NavHost 注册 DataPrivacy + 连接回调（两处 SettingsScreen 调用） |
| Modify `app/src/main/res/values/strings.xml`、`values-zh/`、`values-zh-rCN/`、`values-zh-rTW/` | i18n（4 文件） |

### docs-site
| 文件 | 责任 |
|------|------|
| Modify `docs-site/privacy-policy/index.html` | 修正冲突声明 + 账号数据/删除方式/邮箱 |

---

## Task 1: Server — schema 列 + `AccountService.softDelete`（TDD）

**Files:**
- Create: `server/migrations/006_account_soft_delete.sql`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt`（`Accounts` object，约 52–67 行）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt`（object 内）
- Test: `server/src/test/kotlin/com/mamba/picme/server/auth/AccountServiceSoftDeleteTest.kt`

- [ ] **Step 1: 写失败测试** — 创建 `server/src/test/kotlin/com/mamba/picme/server/auth/AccountServiceSoftDeleteTest.kt`：

```kotlin
package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceSoftDeleteTest {

    @Test
    fun `soft delete marks account deleted clears token plain and rewrites email`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("user@example.com", 100)
        val tokenHash = AccountService.sha256(info.token)

        val ok = AccountService.softDelete(tokenHash)

        assertTrue(ok)
        val row = transaction(Db.instance) { Accounts.selectAll().single() }
        assertEquals("deleted", row[Accounts.status])
        assertNotNull(row[Accounts.deletedAt])
        assertEquals("", row[Accounts.tokenPlain])
        assertTrue(row[Accounts.email].startsWith("deleted_"))
        assertTrue(row[Accounts.email].endsWith("__user@example.com"))
    }

    @Test
    fun `soft deleted token no longer validates`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("gone@example.com", 100)

        AccountService.softDelete(AccountService.sha256(info.token))

        assertFalse(AccountService.validateToken(info.token).valid)
    }

    @Test
    fun `soft delete returns false for unknown hash`() = runBlocking {
        TestDb.init(Accounts)
        assertFalse(AccountService.softDelete("no-such-hash"))
    }

    @Test
    fun `same email can register as a new account after soft delete`() = runBlocking {
        TestDb.init(Accounts)
        val first = AccountService.createOrRefresh("again@example.com", 100)
        AccountService.softDelete(AccountService.sha256(first.token))

        val second = AccountService.createOrRefresh("again@example.com", 100)

        assertTrue(first.token != second.token)
        assertEquals(2L, transaction(Db.instance) { Accounts.selectAll().count() })
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.auth.AccountServiceSoftDeleteTest"`
Expected: 编译失败（`Accounts.deletedAt` 与 `AccountService.softDelete` 未定义）。

- [ ] **Step 3: 加 `deleted_at` 列定义** — 修改 `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` 的 `Accounts` object，在 `createdAt` 之后加：

```kotlin
    val createdAt = long("created_at")
    val deletedAt = long("deleted_at").nullable()   // 新增：软删除时间戳；NULL=未删除
    override val primaryKey = PrimaryKey(id)
```

- [ ] **Step 4: 写参考 migration** — 创建 `server/migrations/006_account_soft_delete.sql`：

```sql
-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumn 自动补列）
ALTER TABLE account ADD COLUMN deleted_at INTEGER;
```

- [ ] **Step 5: 实现 `softDelete` + `RETENTION_MS`** — 在 `AccountService.kt` object 内（`// ── Auth check ──` 段之前）加：

```kotlin
    // ── Account deletion（软删除 + 保留期清理）──

    /** 账号软删除后的保留期：90 天，期满由 purgeExpiredDeleted 物理清理。 */
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * 软删除当前 tokenHash 对应的 active 账号：
     * - status -> "deleted"，deleted_at 记录时间
     * - token_plain 清空（明文 token 不再需要）
     * - email 改写为 "deleted_<id>__<原email>"，释放 uniqueIndex(email)，
     *   使同邮箱可重新注册为全新账号
     * 返回是否命中 active 账号（false = tokenHash 无对应 active 账号，幂等）。
     */
    suspend fun softDelete(tokenHash: String): Boolean {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = Accounts.selectAll().where {
                Accounts.tokenHash eq tokenHash and (Accounts.status eq "active")
            }.firstOrNull() ?: return@newSuspendedTransaction false
            val id = row[Accounts.id]
            val origEmail = row[Accounts.email]
            Accounts.update({ Accounts.id eq id }) {
                it[status] = "deleted"
                it[deletedAt] = now
                it[tokenPlain] = ""
                it[email] = "deleted_${id}__${origEmail}"
            }
            true
        }
    }
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.auth.AccountServiceSoftDeleteTest"`
Expected: PASS（4 个测试全绿）。

- [ ] **Step 7: 提交**

```bash
git add server/migrations/006_account_soft_delete.sql \
  server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt \
  server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt \
  server/src/test/kotlin/com/mamba/picme/server/auth/AccountServiceSoftDeleteTest.kt
git commit -m "feat(server): add account soft-delete with 90d retention" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: Server — `AccountService.purgeExpiredDeleted`（TDD）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt`（`softDelete` 之后）
- Test: `server/src/test/kotlin/com/mamba/picme/server/auth/PurgeExpiredDeletedTest.kt`

- [ ] **Step 1: 写失败测试** — 创建 `server/src/test/kotlin/com/mamba/picme/server/auth/PurgeExpiredDeletedTest.kt`：

```kotlin
package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Test

class PurgeExpiredDeletedTest {

    @Test
    fun `purges expired deleted accounts and their call logs`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        val info = AccountService.createOrRefresh("old@example.com", 100)
        val id = AccountService.idForTokenHash(AccountService.sha256(info.token))!!
        AccountService.softDelete(AccountService.sha256(info.token))
        val ancient = 1_000L
        transaction(Db.instance) {
            Accounts.update({ Accounts.id eq id }) { it[Accounts.deletedAt] = ancient }
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = id
                it[LlmCallLogs.model] = "deepseek-chat"
                it[LlmCallLogs.provider] = "CLOUDFLARE"
                it[LlmCallLogs.costCny] = 0.0
                it[LlmCallLogs.respBytes] = 0
                it[LlmCallLogs.status] = "ok"
                it[LlmCallLogs.createdAt] = ancient
            }
        }

        val n = AccountService.purgeExpiredDeleted(1L)

        assertEquals(1, n)
        assertEquals(0L, transaction(Db.instance) { Accounts.selectAll().count() })
        assertEquals(0L, transaction(Db.instance) { LlmCallLogs.selectAll().count() })
    }

    @Test
    fun `does not purge accounts within retention`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("recent@example.com", 100)
        AccountService.softDelete(AccountService.sha256(info.token))

        val n = AccountService.purgeExpiredDeleted(Long.MAX_VALUE / 2)

        assertEquals(0, n)
        assertEquals(1L, transaction(Db.instance) { Accounts.selectAll().count() })
    }

    @Test
    fun `does not purge active accounts`() = runBlocking {
        TestDb.init(Accounts)
        AccountService.createOrRefresh("active@example.com", 100)

        val n = AccountService.purgeExpiredDeleted(1L)

        assertEquals(0, n)
        assertEquals(1L, transaction(Db.instance) { Accounts.selectAll().count() })
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.auth.PurgeExpiredDeletedTest"`
Expected: 编译失败（`purgeExpiredDeleted` 未定义）。

- [ ] **Step 3: 实现 `purgeExpiredDeleted`** — 在 `AccountService.kt` 的 `softDelete` 之后加（`AccountService.kt` 已 import `deleteWhere`，需补 `import org.jetbrains.exposed.sql.deleteWhere` 若未在）：

```kotlin
    /**
     * 物理清理超过保留期的已软删账号 + 其 llm_call_log。
     * 在 server 启动时调用一次（见 Application.kt）；返回清理条数。
     */
    suspend fun purgeExpiredDeleted(retentionMs: Long): Int {
        val cutoff = Instant.now().toEpochMilli() - retentionMs
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val ids = Accounts.selectAll().where {
                (Accounts.status eq "deleted") and (Accounts.deletedAt less cutoff)
            }.map { it[Accounts.id] }
            if (ids.isNotEmpty()) {
                LlmCallLogs.deleteWhere { accountId inList ids }
                Accounts.deleteWhere { Accounts.id inList ids }
            }
            ids.size
        }
    }
```

> 注：`Accounts.deletedAt less cutoff` 中 `less` 由 `where {}` 的 `SqlExpressionBuilder` receiver 提供；若编译报 `less` 未解析，改用 `Accounts.deletedAt.notNull() less cutoff` 或 `lessOp`。

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.auth.PurgeExpiredDeletedTest"`
Expected: PASS（3 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt \
  server/src/test/kotlin/com/mamba/picme/server/auth/PurgeExpiredDeletedTest.kt
git commit -m "feat(server): purge expired deleted accounts on startup" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: Server — `DELETE /auth/account` 路由（TDD）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/routes/AccountDeletionRouteTest.kt`

- [ ] **Step 1: 写失败测试** — 创建 `server/src/test/kotlin/com/mamba/picme/server/routes/AccountDeletionRouteTest.kt`：

```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AccountDeletionRouteTest {

    @Test
    fun `DELETE auth account soft deletes active account`() = testApplication {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("user@example.com", 100)
        val tokenHash = AccountService.sha256(info.token)
        application {
            // 测试用简化 interceptor 注入 tokenHash；真实 auth 链路由 AccountService.validateToken 覆盖
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(TokenHashKey, tokenHash)
            }
            routing { accountDeletionRoute() }
        }

        val resp = client.delete("/auth/account")

        assertEquals(HttpStatusCode.OK, resp.status)
        val row = transaction(Db.instance) { Accounts.selectAll().single() }
        assertEquals("deleted", row[Accounts.status])
        assertNotNull(row[Accounts.deletedAt])
    }

    @Test
    fun `DELETE auth account returns 404 when no active account`() = testApplication {
        TestDb.init(Accounts)
        application {
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(TokenHashKey, "nonexistent-hash")
            }
            routing { accountDeletionRoute() }
        }

        val resp = client.delete("/auth/account")

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.AccountDeletionRouteTest"`
Expected: 编译失败（`accountDeletionRoute` 未定义）。

- [ ] **Step 3: 实现路由** — 修改 `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt`，在文件顶部 import 区加 `import io.ktor.server.routing.delete`，并在 `quotaRoute()` 函数之后追加：

```kotlin
fun Route.accountDeletionRoute() {
    delete("/auth/account") {
        val tokenHash = call.attributes[TokenHashKey]   // 由 auth interceptor 注入
        val ok = AccountService.softDelete(tokenHash)
        if (ok) {
            call.respond(mapOf("deleted" to true))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "account_not_found"))
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.AccountDeletionRouteTest"`
Expected: PASS（2 个测试全绿）。

- [ ] **Step 5: 跑全量 server 测试，确认无回归**

Run: `./gradlew -p server test`
Expected: 全绿（含既有 AdminRoutesTest 等）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt \
  server/src/test/kotlin/com/mamba/picme/server/routes/AccountDeletionRouteTest.kt
git commit -m "feat(server): add DELETE /auth/account endpoint" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: Server — 注册路由 + 启动清理

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`

- [ ] **Step 1: 注册路由 + 启动清理** — 修改 `Application.kt`：

顶部 import 区加：
```kotlin
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.routes.accountDeletionRoute
```
> 注：`AccountService` 已被 interceptor 引用，import 大概率已存在；若已存在则跳过该行。

在 `main()` 中，`runBlocking { ChannelRegistry.reload() }` 之后追加启动清理：
```kotlin
    runBlocking {
        val purged = AccountService.purgeExpiredDeleted(AccountService.RETENTION_MS)
        logger.info("Purged $purged expired deleted accounts (retention=${AccountService.RETENTION_MS}ms)")
    }
```

在 `module()` 的 `routing { ... }` 块内，`quotaRoute()` 之后追加一行：
```kotlin
        accountDeletionRoute()
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew -p server compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 冒烟启动（可选但推荐）** — 本地起 server 验证清理日志 + 路由可达：

Run: `./gradlew -p server run`（看到 `Purged 0 expired...` 日志后 `Ctrl+C` 停止；或用 `./server/run-local.sh start` 然后 `curl -i -X DELETE http://127.0.0.1:8080/auth/account` 应返回 401，因为未带 token）。
Expected: 启动日志含 `Purged N expired deleted accounts`；未带 token 的 DELETE 返回 401。

- [ ] **Step 4: 提交**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/Application.kt
git commit -m "feat(server): wire account deletion route + startup purge" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: Client — `PicMeAuthClient.deleteAccount`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/remote/picme/PicMeAuthClient.kt`

- [ ] **Step 1: 实现 `deleteAccount`** — 在 `PicMeAuthClient.kt` 的 `getQuota` 函数之后追加（复用现有 `errorBody` / `PicMeAuthException` / `baseUrl` / `client` / `jsonMedia`）：

```kotlin
    suspend fun deleteAccount(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/auth/account")
                .header("X-App-Token", token)
                .delete()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw PicMeAuthException(resp.code, errorBody(resp.body?.string()))
            }
        }
    }
```

> 注：`Request.Builder().delete()` 为 OkHttp 无 body 的 DELETE（4.x 起支持）；`Request`、`withContext`、`Dispatchers` 均已在本文件 import。

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/data/remote/picme/PicMeAuthClient.kt
git commit -m "feat(app): add PicMeAuthClient.deleteAccount" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: Client — i18n 字符串（4 个 strings.xml）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（EN 默认）
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: EN** — 在 `values/strings.xml` 的 `auth_logout` 一行之后追加：

```xml
    <string name="auth_delete_account">Delete account</string>
    <string name="auth_delete_account_confirm_title">Delete account?</string>
    <string name="auth_delete_account_confirm_body">Your account will be deactivated immediately. Account data is retained for 90 days, then permanently deleted. You can register again with the same email.</string>
    <string name="auth_delete_account_confirm">Delete</string>
    <string name="auth_delete_account_success">Account deleted</string>
    <string name="auth_delete_account_failed">Failed to delete account. Check your network and try again.</string>
    <string name="data_privacy_title">Data &amp; privacy</string>
    <string name="data_privacy_entry">Data &amp; privacy</string>
    <string name="data_privacy_account_title">Account data</string>
    <string name="data_privacy_account_body">We only collect your email address, for sign-in and to meter your free LLM trial quota (100 calls by default). We do not collect your password — sign-in uses an email verification code.</string>
    <string name="data_privacy_retention_title">Retention</string>
    <string name="data_privacy_retention_body">After deletion, your data is retained for 90 days (for abuse prevention and recovery), then permanently deleted, including usage logs.</string>
    <string name="data_privacy_delete_title">Delete your account</string>
    <string name="data_privacy_delete_body">Go to Settings → Account → Delete account, or email us to request deletion.</string>
    <string name="data_privacy_local_title">On-device processing</string>
    <string name="data_privacy_local_body">Photos, beauty rendering, face landmarks, OCR text, media locations, and chat memory are all processed on your device and never uploaded to our servers. Clear them via system \"Clear app data\" or by uninstalling.</string>
    <string name="data_privacy_remote_title">Remote inference</string>
    <string name="data_privacy_remote_body">When signed in, remote LLM conversations are proxied through api.polang.net to the LLM provider for the current request only. The server records call counts and token usage, but does not store conversation content.</string>
    <string name="data_privacy_contact_title">Contact</string>
    <string name="data_privacy_contact_body">For privacy or deletion requests, contact: %1$s</string>
    <string name="data_privacy_view_full_policy">View full privacy policy</string>
```

- [ ] **Step 2: zh（values-zh）与 zh-rCN** — 在 `values-zh/strings.xml` 与 `values-zh-rCN/strings.xml` 的 `auth_logout` 之后各追加（内容相同，简体）：

```xml
    <string name="auth_delete_account">删除账号</string>
    <string name="auth_delete_account_confirm_title">删除账号？</string>
    <string name="auth_delete_account_confirm_body">账号将立即停用。账号数据将保留 90 天，随后彻底删除。您仍可使用同一邮箱重新注册。</string>
    <string name="auth_delete_account_confirm">删除</string>
    <string name="auth_delete_account_success">账号已删除</string>
    <string name="auth_delete_account_failed">删除账号失败，请检查网络后重试。</string>
    <string name="data_privacy_title">数据与隐私</string>
    <string name="data_privacy_entry">数据与隐私</string>
    <string name="data_privacy_account_title">账号数据</string>
    <string name="data_privacy_account_body">我们仅收集您的邮箱地址，用于账号登录及 LLM 免费试用额度计费（默认 100 次）。我们不收集您的密码——登录使用邮箱验证码。</string>
    <string name="data_privacy_retention_title">数据保留</string>
    <string name="data_privacy_retention_body">删除账号后，数据将保留 90 天（用于反欺诈与找回），随后彻底删除，包括用量日志。</string>
    <string name="data_privacy_delete_title">删除您的账号</string>
    <string name="data_privacy_delete_body">前往「设置 → 账号 → 删除账号」，或邮件联系我们申请删除。</string>
    <string name="data_privacy_local_title">本地处理</string>
    <string name="data_privacy_local_body">照片、美颜渲染、人脸关键点、OCR 文字、媒体地理位置及对话记忆均在您的设备本地处理，绝不上传服务器。可通过系统「清除应用数据」或卸载清除。</string>
    <string name="data_privacy_remote_title">远程推理</string>
    <string name="data_privacy_remote_body">登录后，远程 LLM 对话经 api.polang.net 代理转发至 LLM 供应商，仅用于本次请求。服务端仅记录调用次数与 Token 用量，不存储对话内容。</string>
    <string name="data_privacy_contact_title">联系我们</string>
    <string name="data_privacy_contact_body">如需隐私或删除请求，请联系：%1$s</string>
    <string name="data_privacy_view_full_policy">查看完整隐私政策</string>
```

- [ ] **Step 3: zh-rTW（繁体）** — 在 `values-zh-rTW/strings.xml` 的 `auth_logout` 之后追加：

```xml
    <string name="auth_delete_account">刪除帳號</string>
    <string name="auth_delete_account_confirm_title">刪除帳號？</string>
    <string name="auth_delete_account_confirm_body">帳號將立即停用。帳號資料將保留 90 天，隨後徹底刪除。您仍可使用同一信箱重新註冊。</string>
    <string name="auth_delete_account_confirm">刪除</string>
    <string name="auth_delete_account_success">帳號已刪除</string>
    <string name="auth_delete_account_failed">刪除帳號失敗，請檢查網路後重試。</string>
    <string name="data_privacy_title">資料與隱私</string>
    <string name="data_privacy_entry">資料與隱私</string>
    <string name="data_privacy_account_title">帳號資料</string>
    <string name="data_privacy_account_body">我們僅收集您的電子郵件地址，用於帳號登入及 LLM 免費試用額度計費（預設 100 次）。我們不收集您的密碼——登入使用郵件驗證碼。</string>
    <string name="data_privacy_retention_title">資料保留</string>
    <string name="data_privacy_retention_body">刪除帳號後，資料將保留 90 天（用於反詐欺與找回），隨後徹底刪除，包括用量日誌。</string>
    <string name="data_privacy_delete_title">刪除您的帳號</string>
    <string name="data_privacy_delete_body">前往「設定 → 帳號 → 刪除帳號」，或來信申請刪除。</string>
    <string name="data_privacy_local_title">本機處理</string>
    <string name="data_privacy_local_body">照片、美顏渲染、人臉特徵點、OCR 文字、媒體地理位置及對話記憶均在您的裝置本機處理，絕不上傳伺服器。可透過系統「清除應用程式資料」或解除安裝清除。</string>
    <string name="data_privacy_remote_title">遠端推理</string>
    <string name="data_privacy_remote_body">登入後，遠端 LLM 對話經 api.polang.net 代理轉發至 LLM 供應商，僅用於本次請求。伺服器僅記錄呼叫次數與 Token 用量，不儲存對話內容。</string>
    <string name="data_privacy_contact_title">聯絡我們</string>
    <string name="data_privacy_contact_body">如需隱私或刪除請求，請聯絡：%1$s</string>
    <string name="data_privacy_view_full_policy">查看完整隱私權政策</string>
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(app): add i18n strings for account deletion and data privacy" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 7: Client — `SettingsServerAuth` 删除按钮 + 二次确认对话框

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt`

- [ ] **Step 1: 加 import** — 在 `SettingsServerAuth.kt` import 区追加：

```kotlin
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
```
> `TextButton` 已 import（line 11）；若已存在则跳过。

- [ ] **Step 2: `QuotaDisplay` 增加删除参数** — 把 `QuotaDisplay` 签名改为（新增 `token`、`authClient`）：

```kotlin
@Composable
private fun QuotaDisplay(
    email: String,
    used: Int,
    limit: Int,
    token: String,
    authClient: PicMeAuthClient,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
```

- [ ] **Step 3: 在 `QuotaDisplay` 内加删除按钮 + 对话框** — 在现有 `Row { 刷新 / 登出 }` 之后、函数结尾 `}` 之前插入：

```kotlin
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var showDeleteDialog by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }

        TextButton(
            onClick = { showDeleteDialog = true },
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.auth_delete_account),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { if (!deleting) showDeleteDialog = false },
                title = { Text(stringResource(R.string.auth_delete_account_confirm_title)) },
                text = { Text(stringResource(R.string.auth_delete_account_confirm_body)) },
                confirmButton = {
                    TextButton(
                        enabled = !deleting,
                        onClick = {
                            deleting = true
                            scope.launch {
                                authClient.deleteAccount(token)
                                    .onSuccess {
                                        onLogout() // 清本地 token/email，回到注册态
                                        showDeleteDialog = false
                                        Toast.makeText(
                                            context,
                                            R.string.auth_delete_account_success,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    .onFailure { e ->
                                        val code = (e as? PicMeAuthClient.PicMeAuthException)?.code
                                        // token 已失效（401/404）→ 也清本地，避免卡死
                                        if (code == 401 || code == 404) {
                                            onLogout()
                                            showDeleteDialog = false
                                        }
                                        Toast.makeText(
                                            context,
                                            R.string.auth_delete_account_failed,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                deleting = false
                            }
                        },
                    ) {
                        Text(
                            stringResource(R.string.auth_delete_account_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !deleting,
                        onClick = { showDeleteDialog = false },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
```

- [ ] **Step 4: 更新 `ServerAuthSection` 内的调用** — 把 `QuotaDisplay(...)` 调用补上 `token = serverToken` 与 `authClient = authClient`：

```kotlin
        QuotaDisplay(
            email = serverEmail,
            used = quotaUsed,
            limit = quotaLimit,
            token = serverToken,
            authClient = authClient,
            onRefresh = {
                scope.launch {
                    authClient.getQuota(serverToken)
                        .onSuccess {
                            quotaUsed = it.llmCallsUsed
                            quotaLimit = it.llmCallsLimit
                        }
                        .onFailure { Logger.w(TAG, "Quota refresh failed: ${it.message}") }
                }
            },
            onLogout = {
                scope.launch { repo.clearServerAuth() }
            },
        )
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt
git commit -m "feat(app): add delete-account button with confirm dialog" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 8: Client — `DataPrivacyScreen`（数据与隐私说明页）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/settings/DataPrivacyScreen.kt`

- [ ] **Step 1: 创建文件** — 内容如下（自包含 Scaffold + TopAppBar，风格对齐 `SettingsContent`）：

```kotlin
package com.mamba.picme.features.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

private const val PRIVACY_POLICY_URL = "https://polang.net/privacy-policy/"
private const val TAG = "PicMe:DataPrivacy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPrivacyScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PrivacySection(R.string.data_privacy_account_title, R.string.data_privacy_account_body)
            PrivacySection(R.string.data_privacy_retention_title, R.string.data_privacy_retention_body)
            PrivacySection(R.string.data_privacy_delete_title, R.string.data_privacy_delete_body)
            PrivacySection(R.string.data_privacy_local_title, R.string.data_privacy_local_body)
            PrivacySection(R.string.data_privacy_remote_title, R.string.data_privacy_remote_body)
            PrivacySection(
                titleRes = R.string.data_privacy_contact_title,
                bodyRes = R.string.data_privacy_contact_body,
                email = "budao.gs@gmail.com",
            )
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.data_privacy_view_full_policy))
            }
        }
    }
}

@Composable
private fun PrivacySection(titleRes: Int, bodyRes: Int, email: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (email != null) stringResource(bodyRes, email) else stringResource(bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
```

> 注：`TAG` 常量预留给后续埋点；当前未使用，若 detekt 报 unused 则删除该行。

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/DataPrivacyScreen.kt
git commit -m "feat(app): add DataPrivacyScreen" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 9: Client — 导航接入（Screen + SettingsScreen + SettingsContent + MainActivity + EmailCodeAuthForm）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/navigation/Screen.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`（`SettingsScreen` + `SettingsContent` 两处签名 + ACCOUNT 区入口）
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`（NavHost 注册 + 两处 `SettingsScreen(` 调用）
- Modify: `app/src/main/java/com/mamba/picme/features/common/auth/EmailCodeAuthForm.kt`

- [ ] **Step 1: 加路由** — 在 `Screen.kt`（与 `data object Settings : Screen("settings")` 同级）追加：

```kotlin
    data object DataPrivacy : Screen("data_privacy")
```

- [ ] **Step 2: `SettingsScreen` 主签名加参数** — 在 `SettingsScreen(...)`（约 106 行）参数列表末尾加：

```kotlin
    onNavigateToDataPrivacy: () -> Unit = {},
```

并在 `SettingsScreen` 内调用 `SettingsContent(...)` 处（约 275–292 行）把这个回调透传：

```kotlin
            onNavigateToDataPrivacy = onNavigateToDataPrivacy,
```

- [ ] **Step 3: `SettingsContent` 签名加参数** — 在 `SettingsContent(...)`（约 298 行）参数列表末尾（`onNavigateToSearchTest: () -> Unit = {}` 之后）加：

```kotlin
    onNavigateToDataPrivacy: () -> Unit = {},
```

- [ ] **Step 4: ACCOUNT 区加入口** — 在 `SettingsContent` 的 ACCOUNT 区块（约 412–419 行），`ServerAuthSection()` 之后、区块结束 `}` 之前插入「数据与隐私」入口：

```kotlin
                    ServerAuthSection(onNavigateToDataPrivacy = onNavigateToDataPrivacy)
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    SettingsClickableRow(
                        title = stringResource(R.string.data_privacy_entry),
                        leadingIcon = Icons.Rounded.PrivacyTip,
                        onClick = onNavigateToDataPrivacy,
                    )
```

> 若 `Icons.Rounded.PrivacyTip` 未 import，改用已 import 的图标（如 `Icons.Rounded.Info`），或加 `import androidx.compose.material.icons.rounded.PrivacyTip`。`SettingsClickableRow` 的确切参数以本文件既有调用（如 model_center 一行，约 430 行）为准对齐。

- [ ] **Step 5: `ServerAuthSection` 透传回调** — 修改 `SettingsServerAuth.kt` 的 `ServerAuthSection` 签名与内部 `EmailCodeAuthForm` 调用：

签名加：
```kotlin
@Composable
internal fun ServerAuthSection(onNavigateToDataPrivacy: () -> Unit = {}) {
```
`EmailCodeAuthForm(...)` 调用补 `onOpenDataPrivacy = onNavigateToDataPrivacy`（见 Step 7 改 form 签名后）。

- [ ] **Step 6: MainActivity 注册 NavHost + 连接两处调用** — 在 `MainActivity.kt`：

a) 在 `composable(Screen.Settings.route) { ... }`（约 324 行）所在的 NavHost 块内，追加：
```kotlin
                            composable(Screen.DataPrivacy.route) {
                                DataPrivacyScreen(onNavigateBack = { navController.popBackStack() })
                            }
```
> 需 import `com.mamba.picme.features.settings.DataPrivacyScreen` 与 `com.mamba.picme.navigation.Screen`（后者大概率已 import）。

b) 两处 `SettingsScreen(...)`（332、375 行）调用，各补：
```kotlin
                                    onNavigateToDataPrivacy = { navController.navigate(Screen.DataPrivacy.route) },
```

- [ ] **Step 7: `EmailCodeAuthForm` 加链接** — 在 `EmailCodeAuthForm.kt`：

签名加可选参数：
```kotlin
fun EmailCodeAuthForm(
    sendCode: (email: String, onResult: (Result<Unit>) -> Unit) -> Unit,
    verifyCode: (email: String, code: String, onResult: (Result<*>) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDataPrivacy: () -> Unit = {},
) {
```

在外层 `Column { ... }` 末尾（函数闭合前）追加：
```kotlin
        TextButton(
            onClick = onOpenDataPrivacy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.data_privacy_entry))
        }
```

- [ ] **Step 8: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若报 `SettingsClickableRow` 参数不匹配，按既有调用（model_center）对齐参数。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/mamba/picme/navigation/Screen.kt \
  app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt \
  app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt \
  app/src/main/java/com/mamba/picme/MainActivity.kt \
  app/src/main/java/com/mamba/picme/features/common/auth/EmailCodeAuthForm.kt
git commit -m "feat(app): wire DataPrivacy navigation and entry points" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 10: 官网隐私政策页修正（`docs-site/privacy-policy/index.html`）

**Files:**
- Modify: `docs-site/privacy-policy/index.html`

- [ ] **Step 1: 更新日期** — 把第 116 行 `subtitle` 改为：

```html
        <p class="subtitle">最后更新日期：2026年7月17日 | 生效日期：2026年7月17日</p>
```

- [ ] **Step 2: 第 1 节新增「1.4 账号数据」** — 在「1.3 设备与网络信息」表格（约 195 行 `</table>`）之后插入：

```html
        <h3>1.4 账号数据（邮箱注册）</h3>
        <table>
            <tr><th>数据类型</th><th>用途</th><th>处理方式</th></tr>
            <tr>
                <td><span class="data-type">邮箱地址</span></td>
                <td>账号注册与登录标识、LLM 免费试用额度计费（默认 100 次/账户）</td>
                <td>存于 <strong>api.polang.net</strong>，仅保存邮箱与登录 token 的 SHA-256 哈希；<strong>不收集明文密码</strong>（验证码登录）</td>
            </tr>
        </table>
```

- [ ] **Step 3: 第 3.3 节补充官方网关** — 在「3.3 远程模式（可选）」`<ul>` 末项（约 305 行）之后追加一条：

```html
            <li>登录 PoLang 账号后，可使用官方 <strong>api.polang.net</strong> 网关享免费 LLM 试用额度；该网关仅记录调用次数与 Token 用量，<strong>不存储对话内容</strong></li>
```

- [ ] **Step 4: 第 4 节新增「4.4 账号数据保留」** — 在「4.3 模型文件」（约 330 行）之后插入：

```html
        <h3>4.4 账号数据保留</h3>
        <ul>
            <li>您删除账号后，账号数据将<strong>保留 90 天</strong>（用于反欺诈与误删找回），期满后<strong>彻底删除</strong>，包括用量日志</li>
            <li>删除入口见 app「设置 → 账号 → 删除账号」，亦可邮件 <a href="mailto:budao.gs@gmail.com">budao.gs@gmail.com</a> 申请删除</li>
        </ul>
```

- [ ] **Step 5: 第 6 节删除权补充** — 把「用户权利-删除权」一条（约 372 行）改为：

```html
            <li><strong>删除权</strong>：您可随时删除照片、清除对话记忆、删除下载的模型；登录账号的用户可在 app「设置 → 账号 → 删除账号」删除账号及关联数据，或邮件申请删除</li>
```

- [ ] **Step 6: 第 8 节修正冲突声明** — 把「8. 安全措施」列表中「无账户系统」一条（约 386 行）整行删除，替换为：

```html
            <li><strong>账号数据最小化</strong>：邮箱注册仅保存邮箱与 token 的 SHA-256 哈希，不收集明文密码（验证码登录），并提供账号删除入口</li>
```

- [ ] **Step 7: 第 10 节加邮箱** — 在「联系我们」`<ul>`（约 395 行）末追加：

```html
            <li>邮箱：<a href="mailto:budao.gs@gmail.com">budao.gs@gmail.com</a></li>
```

- [ ] **Step 8: 校验 + 提交**

Run: `grep -n "无账户系统" docs-site/privacy-policy/index.html`
Expected: 无输出（已删除）。

```bash
git add docs-site/privacy-policy/index.html
git commit -m "docs(privacy): align policy with email account system" -m "修正“无账户系统”冲突声明，补账号数据/删除方式/联系邮箱" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 11: 质量门 + 手动验证

- [ ] **Step 1: server 全量测试**

Run: `./gradlew -p server test`
Expected: 全绿。

- [ ] **Step 2: app 单元测试 + 静态检查**

Run: `./gradlew :app:testDebugUnitTest detekt ktlintCheck`
Expected: 全绿（detekt/ktlint 无新增违规；若 `DataPrivacyScreen` 的 `TAG` 报 unused，删除该常量）。

- [ ] **Step 3: 构建 debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/picme-debug.apk`。

- [ ] **Step 4: 安装并手动验证**（`adb install -r app/build/outputs/apk/debug/picme-debug.apk`，server 指向可访问的 `api.polang.net`）：

1. 设置 → 账号：邮箱注册登录成功，看到邮箱 + 配额 + 「刷新/登出」+ 红色「删除账号」
2. 账号区出现「数据与隐私」入口；注册表单底部出现「数据与隐私」链接
3. 点「数据与隐私」→ 说明页正常，含 90 天保留、删除方式、邮箱 `budao.gs@gmail.com`、「查看完整隐私政策」可打开浏览器
4. 点「删除账号」→ 对话框明示 90 天保留 → 确认 → Toast「账号已删除」→ 回到注册态
5. 删除后远程 LLM 对话不可用（回退 guest 模式）；同邮箱可重新注册为新账号
6. `adb logcat -s "PicMe:*"` 无异常

- [ ] **Step 5: 验收标准对照**（spec §12 的 8 条逐条确认；可勾选记录在 PR 描述）。

- [ ] **Step 6: 文档同步（如需）** — 若 `docs/` 下有账号/隐私相关技术文档与本改动冲突，按 `doc-sync-guardian` 同步；否则跳过。

---

## Self-Review（写计划后自检）

**1. Spec 覆盖**：
- §2 根因（无账户系统冲突）→ Task 10 Step 6 ✓
- §5.1 migration + Tables → Task 1 Step 3/4 ✓
- §5.2 softDelete + purgeExpiredDeleted + RETENTION_MS → Task 1/2 ✓
- §5.3 DELETE 路由 → Task 3 ✓
- §5.4 路由注册 + 启动清理 → Task 4 ✓
- §6.1 PicMeAuthClient.deleteAccount → Task 5 ✓
- §6.2 删除按钮 + 二次确认 + 错误码处理（401/404 清本地）→ Task 7 ✓
- §6.3 DataPrivacyScreen（6 段 + 邮箱 + 政策链接）→ Task 8 ✓
- §6.4 入口（SettingsScreen + EmailCodeAuthForm + NavHost）→ Task 9 ✓
- §7 官网（日期/1.4/3.3/4.4/第6节/第8节/第10节）→ Task 10 ✓
- §8 错误处理（网络失败不清、401/404 清、loading 防重、幂等、email 长度）→ Task 7 + Task 1/3 测试 ✓
- §9 测试（server softDelete/purge/route + client 因无 mockwebserver 改手动验证）→ Task 1/2/3 + Task 11 ✓
- §10 i18n 三语（实际同步 4 个文件含 values-zh）→ Task 6 ✓
- §11 Play Console 配套 → 非代码，由用户在 Console 操作（计划不涉代码，验收清单 Step 5 提示）✓
- §12 验收标准 → Task 11 Step 5 ✓

**2. Placeholder 扫描**：
- Task 9 Step 4 对 `SettingsClickableRow` 参数「以既有调用为准对齐」——这是必要的对齐说明（避免编造未知签名），并给了兜底（PrivacyTip/Info 图标）。可接受。
- Task 4 Step 1 的 import 注释「已存在则跳过」——防御性说明，非占位。
- 无 TBD/TODO/"implement later"/"add error handling" 类占位。

**3. 类型一致性**：
- `softDelete(tokenHash: String): Boolean` — Task 1 定义、Task 3 调用、测试一致 ✓
- `purgeExpiredDeleted(retentionMs: Long): Int` — Task 2 定义、Task 4 调用一致 ✓
- `RETENTION_MS` — Task 1 定义、Task 4 用 `AccountService.RETENTION_MS` 一致 ✓
- `accountDeletionRoute()` — Task 3 定义、Task 4 注册一致 ✓
- `PicMeAuthClient.deleteAccount(token)` — Task 5 定义、Task 7 调用一致 ✓
- `DataPrivacyScreen(onNavigateBack)` — Task 8 定义、Task 9 NavHost 调用一致 ✓
- `Screen.DataPrivacy.route` — Task 9 定义、NavHost 与 `onNavigateToDataPrivacy` 调用一致 ✓
- `ServerAuthSection(onNavigateToDataPrivacy)` — Task 9 Step 5 定义、Step 4 调用一致 ✓
- `deletedAt` 列 — Task 1 Tables 定义、Task 1/2 测试与实现引用一致 ✓

**结论**：计划完整、无占位、类型一致，可交付执行。
