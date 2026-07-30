# Remote Diagnosis (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the diagnosis rendezvous API + queue + state machine to the picme Ktor server (`server/`), so a phone can report a problem and a cloud-host worker can poll/diagnose/fix it.

**Architecture:** New `DiagRoute` (5 endpoints: 3 phone-side under existing `X-App-Token`, 2 worker-side under a new `X-Diag-Worker-Token`), a `diag_job` Exposed table + migration, and a `DiagService` state machine (`QUEUED → DIAGNOSED → FIX_REQUESTED → FIXED`). Reuses the existing auth interceptor (skip `/diag/work/**`), Exposed/SQLite DB, `Routing.xxxRoute()` pattern, and `RateLimiter`. Worker is NOT IP-allowlisted (its egress IP rotates) — static shared secret only.

**Tech Stack:** Kotlin, Ktor, Exposed (SQLite via `Db.instance`), kotlinx.serialization, JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-30-remote-diagnosis-design.md`

**Scope note:** This is plan 1 of 3 (server). Plan 2 = app (collector/sanitizer/chat trigger). Plan 3 = worker (poller scripts). The server is built first because both app and worker depend on its API contract. This plan produces a working, curl-testable API on its own.

---

## File Structure

**Create:**
- `server/src/main/kotlin/com/mamba/picme/server/diag/DiagStatus.kt` — status enum
- `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt` — state-machine DB ops + DTOs (`DiagJobRow`, `DiagClaim`)
- `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt` — 5 endpoints + request/response DTOs
- `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt` — state-machine JVM tests
- `server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt` — endpoint JVM tests

**Modify:**
- `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` — add `DiagJobs` table
- `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt` — register `DiagJobs` in `SchemaUtils.create`
- `server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt` — add `diagWorkerToken`
- `server/src/main/kotlin/com/mamba/picme/server/auth/AppTokenAuth.kt` — add `DIAG_WORKER_TOKEN_HEADER`
- `server/src/main/kotlin/com/mamba/picme/server/Application.kt` — skip `/diag/work/**` in interceptor; register `diagRoute`
- `server/.env.example` — document `DIAG_WORKER_TOKEN`

---

## Task 1: DiagStatus enum + DiagJobs table + migration

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/diag/DiagStatus.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` (append `DiagJobs`)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt:16-20` (add to `SchemaUtils.create`)
- Test: `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`:

```kotlin
package com.mamba.picme.server.diag

import com.mamba.picme.server.db.DiagJobs
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagServiceTest {

    @Test
    fun `createJob inserts a QUEUED row owned by token hash`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("hash-a", null, "app crashes on open", "{}", "abc123") }
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.QUEUED.name, row[DiagJobs.status])
        assertEquals("hash-a", row[DiagJobs.ownerTokenHash])
        assertEquals("abc123", row[DiagJobs.gitSha])
    }

    @Test
    fun `getJob returns the row only for its owner`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("owner-a", null, "d", "{}", "sha") }
        assertNotNull(runBlocking { DiagService.getJob(id, "owner-a") })
        assertNull(runBlocking { DiagService.getJob(id, "owner-b") })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest" -q`
Expected: COMPILE FAIL — `DiagStatus`, `DiagJobs`, `DiagService` unresolved.

- [ ] **Step 3: Create DiagStatus enum**

Create `server/src/main/kotlin/com/mamba/picme/server/diag/DiagStatus.kt`:

```kotlin
package com.mamba.picme.server.diag

/** 诊断任务状态机。 */
enum class DiagStatus {
    QUEUED,            // 已上报，待 worker 诊断
    DIAGNOSED,         // 已出根因，待用户确认
    FIX_REQUESTED,     // 用户已确认 + 选 mode，待 worker 修复
    FIXED,             // 修复完成且自检通过
    FIXED_UNVERIFIED,  // 修复完成但未跑/未通过测试
    DIAGNOSE_FAILED,   // 诊断失败
    FIX_FAILED,        // 修复失败
    TIMED_OUT,         // 超时
}
```

- [ ] **Step 4: Add DiagJobs table**

Append to `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` (after `ServerSettings`):

```kotlin
// ── 远程诊断任务（chat 触发 → 云主机 worker 诊断/修复）──
object DiagJobs : Table("diag_job") {
    val id = integer("id").autoIncrement()
    val ownerTokenHash = varchar("owner_token_hash", 64)  // X-App-Token 的 hash，owner 身份
    val deviceId = varchar("device_id", 128).nullable()
    val description = text("description")
    val bundleJson = text("bundle_json")                  // 脱敏后的纯文本诊断包
    val gitSha = varchar("git_sha", 64)
    val status = varchar("status", 24)                    // DiagStatus.name
    val rootCause = text("root_cause").nullable()
    val fixMode = varchar("fix_mode", 8).nullable()       // push | pr
    val fixBranch = varchar("fix_branch", 128).nullable()
    val compareUrl = varchar("compare_url", 512).nullable()
    val tested = integer("tested").default(0)
    val workerLog = text("worker_log").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val claimedAt = long("claimed_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, ownerTokenHash)
        index(isUnique = false, status)
    }
}
```

- [ ] **Step 5: Register DiagJobs in Migrations**

In `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`, add `DiagJobs` to the `SchemaUtils.create(...)` list (and the import). Change:

```kotlin
            SchemaUtils.create(
                Rules, Assets, TelemetryEvents, LlmDailyCounters,
                Accounts, EmailVerifications, LlmCallLogs, LlmChannels,
                ApkUploads, AnonymousDevices, ServerSettings,
            )
```
to:
```kotlin
            SchemaUtils.create(
                Rules, Assets, TelemetryEvents, LlmDailyCounters,
                Accounts, EmailVerifications, LlmCallLogs, LlmChannels,
                ApkUploads, AnonymousDevices, ServerSettings, DiagJobs,
            )
```
(`DiagJobs` is in the same `db` package, no new import needed.)

- [ ] **Step 6: Create minimal DiagService so the two tests pass**

Create `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt` (we will extend it in Task 2):

```kotlin
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
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest" -q`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/diag/DiagStatus.kt \
        server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt \
        server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt \
        server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt \
        server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt
git commit -m "feat(server): 诊断任务表 DiagJobs + DiagStatus 状态机骨架"
```

---

## Task 2: DiagService state-machine transitions

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt` (add `DiagClaim` + 4 methods)
- Test: `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt` (add cases)

- [ ] **Step 1: Write the failing tests**

Append to `DiagServiceTest.kt` (add imports `org.jetbrains.exposed.sql.update`, `org.jetbrains.exposed.sql.SortOrder`, `org.junit.Assert.assertTrue`, `org.junit.Assert.assertFalse`):

```kotlin
    @Test
    fun `claimNextJob returns QUEUED job as diagnose phase`() {
        TestDb.init(DiagJobs)
        runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        val claim = runBlocking { DiagService.claimNextJob() }
        assertNotNull(claim)
        assertEquals("diagnose", claim!!.phase)
    }

    @Test
    fun `submitDiagnosis moves QUEUED to DIAGNOSED with root cause`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "NPE in Foo.kt:42", DiagStatus.DIAGNOSED, null) }
        val job = runBlocking { DiagService.getJob(id, "o") }!!
        assertEquals(DiagStatus.DIAGNOSED, job.status)
        assertEquals("NPE in Foo.kt:42", job.rootCause)
    }

    @Test
    fun `confirmFix moves DIAGNOSED to FIX_REQUESTED and stores mode`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
        val ok = runBlocking { DiagService.confirmFix(id, "o", "pr") }
        assertTrue(ok)
        val job = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.FIX_REQUESTED.name, job[DiagJobs.status])
        assertEquals("pr", job[DiagJobs.fixMode])
    }

    @Test
    fun `confirmFix rejects wrong owner and non-DIAGNOSED state`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        // wrong owner
        assertFalse(runBlocking { DiagService.confirmFix(id, "other", "push") })
        // still QUEUED (not DIAGNOSED) → reject even for owner
        assertFalse(runBlocking { DiagService.confirmFix(id, "o", "push") })
    }

    @Test
    fun `submitFix moves FIX_REQUESTED to FIXED with branch`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
        runBlocking { DiagService.confirmFix(id, "o", "push") }
        runBlocking { DiagService.submitFix(id, DiagStatus.FIXED, "diag-fix/1", null, tested = true, error = null) }
        val job = runBlocking { DiagService.getJob(id, "o") }!!
        assertEquals(DiagStatus.FIXED, job.status)
        assertEquals("diag-fix/1", job.fixBranch)
        assertTrue(job.tested)
    }

    @Test
    fun `claimNextJob returns FIX_REQUESTED job as fix phase`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
        runBlocking { DiagService.confirmFix(id, "o", "push") }
        val claim = runBlocking { DiagService.claimNextJob() }
        assertEquals("fix", claim!!.phase)
        assertEquals("rc", claim.rootCause)
        assertEquals("push", claim.fixMode)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest" -q`
Expected: COMPILE FAIL — `claimNextJob`, `submitDiagnosis`, `confirmFix`, `submitFix`, `DiagClaim` unresolved.

- [ ] **Step 3: Add DiagClaim + transition methods to DiagService**

In `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`, add imports and the new code. Add to imports:

```kotlin
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.update
```

Add the `DiagClaim` data class next to `DiagJobRow`:

```kotlin
/** worker 领到的任务（phase 决定诊断还是修复）。 */
data class DiagClaim(
    val id: Int,
    val phase: String,        // "diagnose" | "fix"
    val description: String,
    val bundleJson: String,
    val gitSha: String,
    val rootCause: String?,   // 修复阶段带确认过的根因
    val fixMode: String?,     // 修复阶段带用户选的 mode
)
```

Add these methods inside `object DiagService` (after `getJob`):

```kotlin
    /**
     * 原子领取一个待处理任务：QUEUED → 诊断；FIX_REQUESTED → 修复。
     * 置 claimedAt；MVP 单 worker，不做悲观锁。
     */
    suspend fun claimNextJob(): DiagClaim? {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = DiagJobs.selectAll()
                .where {
                    (DiagJobs.status eq DiagStatus.QUEUED.name) or
                        (DiagJobs.status eq DiagStatus.FIX_REQUESTED.name)
                }
                .orderBy(DiagJobs.createdAt to SortOrder.ASC)
                .firstOrNull() ?: return@newSuspendedTransaction null
            val id = row[DiagJobs.id]
            val status = DiagStatus.valueOf(row[DiagJobs.status])
            DiagJobs.update({ DiagJobs.id eq id }) { it[claimedAt] = now }
            DiagClaim(
                id = id,
                phase = if (status == DiagStatus.QUEUED) "diagnose" else "fix",
                description = row[DiagJobs.description],
                bundleJson = row[DiagJobs.bundleJson],
                gitSha = row[DiagJobs.gitSha],
                rootCause = row[DiagJobs.rootCause],
                fixMode = row[DiagJobs.fixMode],
            )
        }
    }

    /** 诊断阶段回传：成功→DIAGNOSED，失败→DIAGNOSE_FAILED。 */
    suspend fun submitDiagnosis(id: Int, rootCause: String?, status: DiagStatus, error: String?) {
        require(status == DiagStatus.DIAGNOSED || status == DiagStatus.DIAGNOSE_FAILED) {
            "diagnose status must be DIAGNOSED or DIAGNOSE_FAILED"
        }
        val now = Instant.now().toEpochMilli()
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = status.name
                it[DiagJobs.rootCause] = rootCause
                it[DiagJobs.workerLog] = error
                it[DiagJobs.updatedAt] = now
            }
        }
    }

    /** 用户确认 + 选 mode：仅 owner 且 DIAGNOSED 态可确认。返回是否成功转移。 */
    suspend fun confirmFix(id: Int, ownerTokenHash: String, mode: String): Boolean {
        require(mode == "push" || mode == "pr") { "mode must be push or pr" }
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = DiagJobs.selectAll().where { DiagJobs.id eq id }.firstOrNull()
                ?: return@newSuspendedTransaction false
            if (row[DiagJobs.ownerTokenHash] != ownerTokenHash) return@newSuspendedTransaction false
            if (row[DiagJobs.status] != DiagStatus.DIAGNOSED.name) return@newSuspendedTransaction false
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = DiagStatus.FIX_REQUESTED.name
                it[DiagJobs.fixMode] = mode
                it[DiagJobs.updatedAt] = now
            }
            true
        }
    }

    /** 修复阶段回传：FIXED / FIXED_UNVERIFIED / FIX_FAILED。 */
    suspend fun submitFix(
        id: Int,
        status: DiagStatus,
        fixBranch: String?,
        compareUrl: String?,
        tested: Boolean,
        error: String?,
    ) {
        require(status == DiagStatus.FIXED || status == DiagStatus.FIXED_UNVERIFIED || status == DiagStatus.FIX_FAILED) {
            "fix status must be FIXED, FIXED_UNVERIFIED or FIX_FAILED"
        }
        val now = Instant.now().toEpochMilli()
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = status.name
                it[DiagJobs.fixBranch] = fixBranch
                it[DiagJobs.compareUrl] = compareUrl
                it[DiagJobs.tested] = if (tested) 1 else 0
                it[DiagJobs.workerLog] = error
                it[DiagJobs.updatedAt] = now
            }
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest" -q`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt \
        server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt
git commit -m "feat(server): DiagService 状态机（claim/diagnose/confirm/fix）+ 单测"
```

---

## Task 3: Worker-token config + header constant

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/AppTokenAuth.kt`

- [ ] **Step 1: Add diagWorkerToken to AppConfig**

In `server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt`, add the field to the data class (after `adminToken`):

```kotlin
    // 远程诊断 worker（云主机）
    val diagWorkerToken: String,
```

And in `load()`, after `adminToken = env("ADMIN_TOKEN", ""),` add:

```kotlin
            diagWorkerToken = env("DIAG_WORKER_TOKEN", ""),
```

- [ ] **Step 2: Add the worker-token header constant**

In `server/src/main/kotlin/com/mamba/picme/server/auth/AppTokenAuth.kt`, append:

```kotlin
/**
 * 远程诊断 worker（云主机）鉴权 header。值为静态共享密钥（env DIAG_WORKER_TOKEN）。
 * worker 出口 IP 池化轮换，故不按 IP 白名单，仅校验此 token。
 */
const val DIAG_WORKER_TOKEN_HEADER = "X-Diag-Worker-Token"
```

- [ ] **Step 3: Verify server still compiles**

Run: `./gradlew -p server compileKotlin -q`
Expected: BUILD SUCCESS (no test run; just confirms the data-class + const additions don't break anything).

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt \
        server/src/main/kotlin/com/mamba/picme/server/auth/AppTokenAuth.kt
git commit -m "feat(server): DIAG_WORKER_TOKEN 配置 + X-Diag-Worker-Token header"
```

---

## Task 4: DiagRoute — worker endpoints

**Files:**
- Create: `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt`
- Create: `server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt`:

```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.DIAG_WORKER_TOKEN_HEADER
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
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagRouteTest {

    private val workerToken = "w-secret"

    @Test
    fun `worker GET rejects missing worker token`() = testApplication {
        TestDb.init(DiagJobs)
        application {
            install(ContentNegotiation) { json(com.mamba.picme.server.appJson) }
            routing { diagRoute(workerToken) }
        }
        val resp = client.get("/diag/work/jobs")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `worker GET returns 204 when queue empty`() = testApplication {
        TestDb.init(DiagJobs)
        application {
            install(ContentNegotiation) { json(com.mamba.picme.server.appJson) }
            routing { diagRoute(workerToken) }
        }
        val resp = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }
        assertEquals(HttpStatusCode.NoContent, resp.status)
    }

    @Test
    fun `worker POST result without token is unauthorized`() = testApplication {
        TestDb.init(DiagJobs)
        application {
            install(ContentNegotiation) { json(com.mamba.picme.server.appJson) }
            routing { diagRoute(workerToken) }
        }
        val resp = client.post("/diag/work/jobs/1/result") {
            contentType(ContentType.Application.Json)
            setBody(DiagWorkResult(phase = "diagnose", status = "DIAGNOSED", rootCause = "x"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `worker POST result rejects unknown phase`() = testApplication {
        TestDb.init(DiagJobs)
        application {
            install(ContentNegotiation) { json(com.mamba.picme.server.appJson) }
            routing { diagRoute(workerToken) }
        }
        val resp = client.post("/diag/work/jobs/1/result") {
            header(DIAG_WORKER_TOKEN_HEADER, workerToken)
            contentType(ContentType.Application.Json)
            setBody(DiagWorkResult(phase = "bogus", status = "DIAGNOSED"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("unknown phase"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.DiagRouteTest" -q`
Expected: COMPILE FAIL — `diagRoute` unresolved.

- [ ] **Step 3: Create DiagRoute with worker endpoints + all DTOs**

Create `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt`. (Phone endpoints are added in Task 5; for now only the worker pair is registered, but the phone endpoints defined here are harmless to include now. We include everything to avoid re-editing the file.)

```kotlin
package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.auth.DIAG_WORKER_TOKEN_HEADER
import com.mamba.picme.server.diag.DiagService
import com.mamba.picme.server.diag.DiagStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class DiagBundle(
    val logs: String = "",
    val crashTrace: String? = null,
    val appVersion: String = "",
    val gitSha: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
)

@Serializable
data class DiagReportRequest(val description: String, val bundle: DiagBundle)

@Serializable
data class DiagReportResponse(val jobId: Int, val status: String)

@Serializable
data class DiagJobStatus(
    val jobId: Int,
    val status: String,
    val rootCause: String? = null,
    val fixBranch: String? = null,
    val compareUrl: String? = null,
    val tested: Boolean = false,
    val error: String? = null,
)

@Serializable
data class DiagConfirmRequest(val mode: String)

@Serializable
data class DiagClaimResponse(
    val jobId: Int,
    val phase: String,
    val description: String,
    val bundle: DiagBundle,
    val gitSha: String,
    val rootCause: String? = null,
    val fixMode: String? = null,
)

@Serializable
data class DiagWorkResult(
    val phase: String,            // diagnose | fix
    val status: String,           // diagnose: DIAGNOSED|DIAGNOSE_FAILED  fix: FIXED|FIXED_UNVERIFIED|FIX_FAILED
    val rootCause: String? = null,
    val fixBranch: String? = null,
    val compareUrl: String? = null,
    val tested: Boolean = false,
    val error: String? = null,
)

fun Routing.diagRoute(workerToken: String) {
    // ── 手机侧端点（Task 5 实现）──

    // ── worker 侧端点（X-Diag-Worker-Token）──
    get("/diag/work/jobs") {
        if (!call.isWorker(workerToken)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@get
        }
        val claim = DiagService.claimNextJob() ?: run {
            call.respond(HttpStatusCode.NoContent); return@get
        }
        val bundle = try {
            appJson.decodeFromString(DiagBundle.serializer(), claim.bundleJson)
        } catch (e: Exception) {
            DiagBundle()
        }
        call.respond(DiagClaimResponse(
            jobId = claim.id,
            phase = claim.phase,
            description = claim.description,
            bundle = bundle,
            gitSha = claim.gitSha,
            rootCause = claim.rootCause,
            fixMode = claim.fixMode,
        ))
    }

    post("/diag/work/jobs/{id}/result") {
        if (!call.isWorker(workerToken)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest); return@post
        }
        val r = call.receive<DiagWorkResult>()
        when (r.phase) {
            "diagnose" -> {
                val status = parseDiagnoseStatus(r.status)
                if (status == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "bad diagnose status"))
                    return@post
                }
                DiagService.submitDiagnosis(id, r.rootCause, status, r.error)
            }
            "fix" -> {
                val status = parseFixStatus(r.status)
                if (status == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "bad fix status"))
                    return@post
                }
                DiagService.submitFix(id, status, r.fixBranch, r.compareUrl, r.tested, r.error)
            }
            else -> {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "unknown phase"))
                return@post
            }
        }
        call.respond(mapOf("ok" to true))
    }
}

private fun parseDiagnoseStatus(s: String): DiagStatus? = when (s) {
    "DIAGNOSED" -> DiagStatus.DIAGNOSED
    "DIAGNOSE_FAILED" -> DiagStatus.DIAGNOSE_FAILED
    else -> null
}

private fun parseFixStatus(s: String): DiagStatus? = when (s) {
    "FIXED" -> DiagStatus.FIXED
    "FIXED_UNVERIFIED" -> DiagStatus.FIXED_UNVERIFIED
    "FIX_FAILED" -> DiagStatus.FIX_FAILED
    else -> null
}

private fun ApplicationCall.isWorker(expected: String): Boolean {
    if (expected.isBlank()) return false
    return request.headers[DIAG_WORKER_TOKEN_HEADER] == expected
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.DiagRouteTest" -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt \
        server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt
git commit -m "feat(server): DiagRoute worker 端点（claim/result）+ 鉴权单测"
```

---

## Task 5: DiagRoute — phone endpoints

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt` (fill in the 3 phone endpoints + `ownerTokenHash` helper)
- Modify: `server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt` (add E2E-ish cases)

- [ ] **Step 1: Write the failing tests**

Append to `DiagRouteTest.kt` (add imports: `com.mamba.picme.server.auth.APP_TOKEN_HEADER`, `com.mamba.picme.server.auth.AccountService`, `com.mamba.picme.server.db.Accounts`, `kotlinx.coroutines.runBlocking`, `io.ktor.client.call.body`):

```kotlin
    @Test
    fun `phone report without token is unauthorized`() = testApplication {
        TestDb.init(DiagJobs)
        application {
            install(ContentNegotiation) { json(com.mamba.picme.server.appJson) }
            routing { diagRoute(workerToken) }
        }
        val resp = client.post("/diag/report") {
            contentType(ContentType.Application.Json)
            setBody(DiagReportRequest("crash", DiagBundle(logs = "x", gitSha = "sha1")))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `full flow report diagnose confirm fix`() = testApplication {
        TestDb.init(DiagJobs, Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        application {
            install(ContentNegotiation) { json(com.mamba.picme.server.appJson) }
            routing { diagRoute(workerToken) }
        }
        // 1) phone reports
        val report = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(DiagReportRequest("crash on open gallery", DiagBundle(logs = "PoLang:Gallery boom", gitSha = "sha1")))
        }
        assertEquals(HttpStatusCode.OK, report.status)
        val jobId = report.body<DiagReportResponse>().jobId

        // 2) worker claims + posts diagnosis
        val claim = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }
        assertEquals(HttpStatusCode.OK, claim.status)
        assertEquals("diagnose", claim.body<DiagClaimResponse>().phase)
        val diag = client.post("/diag/work/jobs/$jobId/result") {
            header(DIAG_WORKER_TOKEN_HEADER, workerToken)
            contentType(ContentType.Application.Json)
            setBody(DiagWorkResult(phase = "diagnose", status = "DIAGNOSED", rootCause = "NPE GalleryScreen.kt:88"))
        }
        assertEquals(HttpStatusCode.OK, diag.status)

        // 3) phone reads DIAGNOSED
        val s1 = client.get("/diag/jobs/$jobId") { header(APP_TOKEN_HEADER, token) }.body<DiagJobStatus>()
        assertEquals("DIAGNOSED", s1.status)
        assertEquals("NPE GalleryScreen.kt:88", s1.rootCause)

        // 4) phone confirms (push)
        val confirm = client.post("/diag/jobs/$jobId/confirm") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(DiagConfirmRequest("push"))
        }
        assertEquals(HttpStatusCode.OK, confirm.status)

        // 5) worker claims fix + posts FIXED
        val claim2 = client.get("/diag/work/jobs") { header(DIAG_WORKER_TOKEN_HEADER, workerToken) }
        assertEquals("fix", claim2.body<DiagClaimResponse>().phase)
        client.post("/diag/work/jobs/$jobId/result") {
            header(DIAG_WORKER_TOKEN_HEADER, workerToken)
            contentType(ContentType.Application.Json)
            setBody(DiagWorkResult(phase = "fix", status = "FIXED", fixBranch = "diag-fix/$jobId", tested = true))
        }

        // 6) phone reads FIXED
        val s2 = client.get("/diag/jobs/$jobId") { header(APP_TOKEN_HEADER, token) }.body<DiagJobStatus>()
        assertEquals("FIXED", s2.status)
        assertEquals("diag-fix/$jobId", s2.fixBranch)
        assertTrue(s2.tested)
    }

    @Test
    fun `phone cannot read another owners job`() = testApplication {
        TestDb.init(DiagJobs, Accounts)
        val tokenA = runBlocking { AccountService.createOrRefresh("a@x.com", 100).token }
        val tokenB = runBlocking { AccountService.createOrRefresh("b@x.com", 100).token }
        application {
            install(ContentNegotiation) { json(com.mamba.picme.server.appJson) }
            routing { diagRoute(workerToken) }
        }
        val report = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, tokenA)
            contentType(ContentType.Application.Json)
            setBody(DiagReportRequest("d", DiagBundle(gitSha = "s")))
        }
        val jobId = report.body<DiagReportResponse>().jobId
        val resp = client.get("/diag/jobs/$jobId") { header(APP_TOKEN_HEADER, tokenB) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.DiagRouteTest" -q`
Expected: FAIL — phone endpoints `/diag/report`, `/diag/jobs/{id}`, `/diag/jobs/{id}/confirm` return 404 (not registered).

- [ ] **Step 3: Fill in the phone endpoints**

In `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt`, replace the `// ── 手机侧端点（Task 5 实现）──` line with:

```kotlin
    // ── 手机侧端点（X-App-Token；全局拦截器在 prod 已校验，这里兜底取 owner 身份）──
    post("/diag/report") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        val req = call.receive<DiagReportRequest>()
        if (req.description.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "description required"))
            return@post
        }
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        val id = DiagService.createJob(
            ownerTokenHash = owner,
            deviceId = deviceId,
            description = req.description,
            bundleJson = appJson.encodeToString(DiagBundle.serializer(), req.bundle),
            gitSha = req.bundle.gitSha,
        )
        call.respond(DiagReportResponse(id, DiagStatus.QUEUED.name))
    }

    get("/diag/jobs/{id}") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@get
        }
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest); return@get
        }
        val job = DiagService.getJob(id, owner) ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found")); return@get
        }
        call.respond(DiagJobStatus(
            jobId = job.id,
            status = job.status.name,
            rootCause = job.rootCause,
            fixBranch = job.fixBranch,
            compareUrl = job.compareUrl,
            tested = job.tested,
        ))
    }

    post("/diag/jobs/{id}/confirm") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        val id = call.parameters["id"]?.toIntOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest); return@post
        }
        val req = call.receive<DiagConfirmRequest>()
        val ok = try {
            DiagService.confirmFix(id, owner, req.mode)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to (e.message ?: "")))
            return@post
        }
        if (!ok) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "not_diagnosed_or_not_owner")); return@post
        }
        call.respond(mapOf("status" to DiagStatus.FIX_REQUESTED.name))
    }
```

And add the `ownerTokenHash` helper at the file bottom (next to `isWorker`):

```kotlin
/**
 * 解析 owner 身份：优先用全局拦截器写入的 TokenHashKey（prod，免重复校验），
 * 否则自行 validateToken（路由单测无拦截器时走这条）。
 */
private suspend fun ApplicationCall.ownerTokenHash(): String? {
    attributes.getOrNull(TokenHashKey)?.let { return it }
    val raw = request.headers[APP_TOKEN_HEADER] ?: return null
    return AccountService.validateToken(raw).takeIf { it.valid }?.tokenHash
}
```

> Note: `TokenHashKey` is defined in the `routes` package (same package as this file), so no import is needed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.DiagRouteTest" -q`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt \
        server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt
git commit -m "feat(server): DiagRoute 手机端点 report/jobs/confirm + 端到端单测"
```

---

## Task 6: Wire DiagRoute into Application + skip worker path in interceptor

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt`

- [ ] **Step 1: Skip /diag/work/** in the auth interceptor

In `server/src/main/kotlin/com/mamba/picme/server/Application.kt`, the interceptor currently does:

```kotlin
        if (uri in publicRoutes || uri == "/admin" || uri.startsWith("/admin/")) return@intercept
```

Change it to also skip the worker diag path:

```kotlin
        if (uri in publicRoutes || uri == "/admin" || uri.startsWith("/admin/") ||
            uri.startsWith("/diag/work")
        ) return@intercept
```

- [ ] **Step 2: Register diagRoute in routing**

In the same file, in the `routing { ... }` block, add (after `llmRoute(...)`):

```kotlin
        diagRoute(config.diagWorkerToken)
```

And add the import near the other route imports:

```kotlin
import com.mamba.picme.server.routes.diagRoute
```

- [ ] **Step 3: Verify the whole server compiles + all tests pass**

Run: `./gradlew -p server test -q`
Expected: BUILD SUCCESS, all server tests PASS (including existing ones — confirms no regression).

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/Application.kt
git commit -m "feat(server): 接入 DiagRoute，/diag/work 绕过 AppToken 走 worker token"
```

---

## Task 7: Document DIAG_WORKER_TOKEN + manual smoke test

**Files:**
- Modify: `server/.env.example`

- [ ] **Step 1: Add DIAG_WORKER_TOKEN to .env.example**

In `server/.env.example`, append (in an appropriate section):

```bash
# 远程诊断 worker（云主机）共享密钥；worker poll /diag/work/** 时带此 header
DIAG_WORKER_TOKEN=
```

- [ ] **Step 2: Manual smoke test (local server + curl)**

Start the server locally (from `server/`): `bash run-local.sh` (or set `DIAG_WORKER_TOKEN=testw` + `./gradlew -p server run`). Then in another shell:

```bash
# worker GET without token → 401
curl -s -o /dev/null -w "%{http_code}\n" https://api.polang.net/diag/work/jobs
# → 401  (against prod) ; locally use http://127.0.0.1:8080/diag/work/jobs

# worker GET with token, empty queue → 204
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Diag-Worker-Token: testw" http://127.0.0.1:8080/diag/work/jobs
# → 204
```
Expected: 401 without token, 204 with token on empty queue. (Phone endpoints need a real account token — covered by the app plan's E2E later.)

- [ ] **Step 3: Commit**

```bash
git add server/.env.example
git commit -m "docs(server): .env.example 补 DIAG_WORKER_TOKEN"
```

---

## Self-Review (run after all tasks)

- **Spec coverage:** every endpoint in spec §5 maps to a task (report/jobs/confirm = Task 5; work/jobs + result = Task 4). State machine (§4.2) = Task 2. Worker-token auth (§5 note) = Tasks 3+6. Table/migration (§6.2) = Task 1.
- **Refinement vs spec:** (a) jobs keyed by `ownerTokenHash` (not `account_id`) — tokenHash is the identity directly available from `validateToken`, avoids an extra lookup; (b) `/diag/work/jobs/{id}/result` is a single endpoint discriminated by `phase` (spec's design). Both are within plan-level latitude.
- **Type consistency:** `DiagClaim`/`DiagJobRow` fields match across DiagService, DiagRoute DTOs, and tests. `ownerTokenHash()` helper used by all 3 phone endpoints.
- **No placeholders:** every step has complete code or exact commands.

---

## Done criteria for this plan

- [ ] `./gradlew -p server test` passes (DiagServiceTest 8 cases + DiagRouteTest 7 cases + existing tests green).
- [ ] `curl` smoke: worker GET 401 without token, 204 with token on empty queue.
- [ ] All 7 tasks committed (only the files each task lists — do not stage unrelated in-progress files).
