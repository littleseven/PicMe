# 诊断多轮澄清对话 + 三方链路加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ① App 侧诊断模式升级为「注入诊断 system prompt 的多轮流式澄清对话」：LLM 收敛后输出 `[DIAG_READY]` + 结构化摘要，气泡内嵌「提交诊断」按钮（用户手动提交，`conversationSummary` 随脱敏诊断包上报）；② Server 加固 S1-S4（任务回收 sweeper、error/updatedAt 透出、限频限长、启动自检）；③ Worker 加固 W1-W3（诊断→修复三字段传递链、修复产出校验、python3 模板渲染）；④ App 加固 A1-A5（确认绑定 jobId、轮询超时、崩溃栈链路、i18n、死代码清理）。

**Architecture:** worker↔server 保持 HTTP 轮询（spec §1 决策）；多轮澄清走**方案 A**（App 侧对话，worker 保持单轮）。App 新增 `DiagChatSession`（复用 agent-core `OpenAiStreamingChatModel` 流式通道，无 ReAct 工具，内存历史）+ `DiagPrompts`（system prompt 常量 + `[DIAG_READY]` 解析）；上报后完全走现有 diag 链路（report → poll → 三按钮确认 → fix → 结果回气泡）。Server `diag_job` 加 `conversation_summary` / `suggested_fix` 两列（`createMissingTablesAndColumns` 自动迁移）；sweeper 为 `Application.kt` 启动的周期协程。Worker 模板渲染由 sed 改为 python3 + 环境变量。

**Tech Stack:** Kotlin/Jetpack Compose + Room（app）、Ktor + Exposed/SQLite（server）、Bash + python3 + jq（worker）、JUnit4 + mockk + kotlinx-coroutines-test（app 单测）、Ktor testApplication（server 单测）。

**关联 spec：** `docs/superpowers/specs/2026-07-31-diag-multiturn-and-hardening-design.md`（§9 YAGNI 清单内的事项一律不做：jobId 进程恢复、方案 B 追问协议、跨设备恢复、admin CSRF、WebSocket）。

**验证命令：** app `./gradlew :app:testDebugUnitTest --tests "<class>"`；server `./gradlew -p server test --tests "<class>"`；worker `bash scripts/diag-worker/smoke/run-smoke.sh`；编译 `./gradlew :app:assembleDebug` 与 `./gradlew -p server build`。

---

## 文件结构

| 文件 | 责任 | 动作 |
|------|------|------|
| `server/.../db/Tables.kt`（~164-187） | `DiagJobs` 表定义 | 加 `conversation_summary` / `suggested_fix` 两列 |
| `server/.../db/Migrations.kt`（~22） | 迁移入口 | `createMissingTablesAndColumns` 补 `DiagJobs` |
| `server/.../routes/DiagRoute.kt` | diag 全部端点 | report 加 summary + 护栏；jobs/{id} 透出 error/updatedAt；claim/result 加字段 |
| `server/.../diag/DiagService.kt` | 任务状态机 | 新列透传、sweeper、getJob 补字段、claim 同时刷 updatedAt |
| `server/.../Application.kt`（~54-67, 131, 150） | 启动装配 | sweeper 协程、S4 WARN、diag 限流器装配 |
| `server/src/test/.../DiagRouteTest.kt`、`diag/DiagServiceTest.kt` | server 单测 | 新增覆盖 |
| `scripts/diag-worker/lib.sh` | 共享工具 | 加 `render_template`（python3） |
| `scripts/diag-worker/run-diagnose.sh` | 诊断执行 | 模板渲染改 python3、回传三字段 |
| `scripts/diag-worker/run-fix.sh` | 修复执行 | 模板渲染改 python3、suggestedFix 取值、空改动 FIX_FAILED、log 回传 |
| `scripts/diag-worker/prompts/diagnose.md` | 诊断模板 | 加 `__CONVERSATION_SUMMARY__` 段 |
| `scripts/diag-worker/smoke/run-smoke.sh`、`stub-claude.sh` | 冒烟 | 注入/三字段/空改动三用例 |
| `scripts/diag-worker/README.md` | 部署文档 | python3 依赖 |
| `app/.../features/chat/DiagController.kt` + 单测 | 死代码 | 删除（A5） |
| `app/.../core/diag/CrashTraceStore.kt`（新） | 崩溃栈落盘/读取/删除 | 新建（A3） |
| `app/.../PoLangApplication.kt`（onCreate ~137） | 安装 crash handler | 加一行（A3） |
| `app/.../data/remote/picme/DiagClient.kt` | 上报/轮询客户端 | summary 字段、sanitizer、截断、error/updatedAt 解析 |
| `app/.../core/diag/DiagBundle.kt` | `DiagJobStatus` | 加 `error` / `updatedAt` |
| `app/.../features/chat/DiagPrompts.kt`（新） | 诊断 prompt + `[DIAG_READY]` 解析 | 新建 |
| `app/.../features/chat/DiagChatSession.kt`（新） | 诊断流式对话封装 | 新建 |
| `app/.../features/chat/ChatViewModel.kt`（~207-323, 649-663, 1712+） | 诊断会话/确认/轮询 | A1/A2/A3 接线 + §2 会话模式 |
| `app/.../features/chat/ChatScreen.kt`（~500, 828, 993-1012, 1132, 1203, 1225, 1846-1850） | toggle/按钮 UI | §2 提交按钮 + A1 接线 |
| `app/src/main/res/values{,-zh,-zh-rCN,-zh-rTW}/strings.xml` | diag_* 文案 | A4 + 新 key |
| `app/src/test/.../DiagClientTest.kt`、`DiagSanitizerTest.kt`、`DiagBundleCollectorTest.kt`、`ChatViewModelDiagTest.kt`（新）、`DiagPromptsTest.kt`（新）、`CrashTraceStoreTest.kt`（新） | app 单测 | 新增/扩展 |

---

## Task 1: server —— diag_job 两列迁移 + conversationSummary/suggestedFix 契约打通

**Files:**
- Modify `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt`（DiagJobs，~164-187）
- Modify `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt`（~22）
- Modify `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt`
- Modify `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`
- Test `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`、`server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt`

- [ ] **Step 1: 写失败测试**

`DiagServiceTest.kt` 末尾（`submitFix is ignored after the job is archived` 之后）追加：

```kotlin
    @Test
    fun `createJob stores conversationSummary and claim returns it`() {
        TestDb.init(DiagJobs)
        runBlocking { DiagService.createJob("o", null, "d", "{}", "sha", conversationSummary = "现象: 打开相册崩溃") }
        val claim = runBlocking { DiagService.claimNextJob() }!!
        assertEquals("现象: 打开相册崩溃", claim.conversationSummary)
    }

    @Test
    fun `submitDiagnosis stores suggestedFix and fix claim returns it`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null, suggestedFix = "null check") }
        runBlocking { DiagService.confirmFix(id, "o", "push") }
        val claim = runBlocking { DiagService.claimNextJob() }!!
        assertEquals("fix", claim.phase)
        assertEquals("null check", claim.suggestedFix)
    }
```

`DiagRouteTest.kt` 末尾（`phone cannot read another owners job` 之后）追加：

```kotlin
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest" --tests "com.mamba.picme.server.routes.DiagRouteTest"`
Expected: 新增 5 个测试 FAIL（编译错误：`conversationSummary` / `suggestedFix` 未定义）。

- [ ] **Step 3: 表结构 + 迁移**

`Tables.kt` `DiagJobs` 中，`val description = text("description")` 之后加一列、`val rootCause = text("root_cause").nullable()` 之后加一列：

```kotlin
    val description = text("description")
    val conversationSummary = text("conversation_summary").nullable() // 诊断澄清对话摘要（可选，旧客户端为 NULL）
```
```kotlin
    val rootCause = text("root_cause").nullable()
    val suggestedFix = text("suggested_fix").nullable()     // 诊断给出的修复方向（供 fix 阶段 prompt）
```

`Migrations.kt` 第 22 行改为（把 `DiagJobs` 加入补列清单，否则存量库新列不会自动迁移）：

```kotlin
            SchemaUtils.createMissingTablesAndColumns(Accounts, LlmChannels, LlmCallLogs, ServerSettings, DiagJobs)
```

- [ ] **Step 4: DiagService 透传**

`DiagService.kt`：

`DiagClaim` 改为：

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
    val conversationSummary: String?, // 诊断澄清对话摘要（诊断阶段用）
    val suggestedFix: String?,        // 修复阶段带诊断给出的修复方向
)
```

`createJob` 签名与 insert 改为：

```kotlin
    suspend fun createJob(
        ownerTokenHash: String,
        deviceId: String?,
        description: String,
        bundleJson: String,
        gitSha: String,
        conversationSummary: String? = null,
    ): Int {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.insert {
                it[DiagJobs.ownerTokenHash] = ownerTokenHash
                it[DiagJobs.deviceId] = deviceId
                it[DiagJobs.description] = description
                it[DiagJobs.conversationSummary] = conversationSummary
                it[DiagJobs.bundleJson] = bundleJson
                it[DiagJobs.gitSha] = gitSha
                it[DiagJobs.status] = DiagStatus.QUEUED.name
                it[DiagJobs.createdAt] = now
                it[DiagJobs.updatedAt] = now
            }[DiagJobs.id]
        }
    }
```

`claimNextJob` 的 `DiagClaim(...)` 构造改为：

```kotlin
            DiagClaim(
                id = id,
                phase = if (status == DiagStatus.QUEUED) "diagnose" else "fix",
                description = row[DiagJobs.description],
                bundleJson = row[DiagJobs.bundleJson],
                gitSha = row[DiagJobs.gitSha],
                rootCause = row[DiagJobs.rootCause],
                fixMode = row[DiagJobs.fixMode],
                conversationSummary = row[DiagJobs.conversationSummary],
                suggestedFix = row[DiagJobs.suggestedFix],
            )
```

`submitDiagnosis` 改为：

```kotlin
    /** 诊断阶段回传：成功→DIAGNOSED，失败→DIAGNOSE_FAILED。suggestedFix 供 fix 阶段 prompt 使用。 */
    suspend fun submitDiagnosis(id: Int, rootCause: String?, status: DiagStatus, error: String?, suggestedFix: String? = null) {
        require(status == DiagStatus.DIAGNOSED || status == DiagStatus.DIAGNOSE_FAILED) {
            "diagnose status must be DIAGNOSED or DIAGNOSE_FAILED"
        }
        val now = Instant.now().toEpochMilli()
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            DiagJobs.update({ (DiagJobs.id eq id) and (DiagJobs.status eq DiagStatus.QUEUED.name) }) {
                it[DiagJobs.status] = status.name
                it[DiagJobs.rootCause] = rootCause
                it[DiagJobs.suggestedFix] = suggestedFix
                it[DiagJobs.workerLog] = error
                it[DiagJobs.updatedAt] = now
            }
        }
    }
```

`activate` 的 `DiagJobs.update(...)` 块中，`it[DiagJobs.rootCause] = null` 之后加一行（激活重跑时清空诊断产出）：

```kotlin
                it[DiagJobs.suggestedFix] = null
```

- [ ] **Step 5: DiagRoute 契约**

`DiagRoute.kt` 数据类改为：

```kotlin
@Serializable
data class DiagReportRequest(
    val description: String,
    val bundle: DiagBundle,
    val conversationSummary: String? = null, // 可选：诊断澄清对话摘要（向后兼容旧客户端）
)
```

```kotlin
@Serializable
data class DiagClaimResponse(
    val jobId: Int,
    val phase: String,
    val description: String,
    val bundle: DiagBundle,
    val gitSha: String,
    val rootCause: String? = null,
    val fixMode: String? = null,
    val conversationSummary: String? = null,
    val suggestedFix: String? = null,
)
```

```kotlin
@Serializable
data class DiagWorkResult(
    val phase: String,            // diagnose | fix
    val status: String,           // diagnose: DIAGNOSED|DIAGNOSE_FAILED  fix: FIXED|FIXED_UNVERIFIED|FIX_FAILED
    val rootCause: String? = null,
    val fixBranch: String? = null,
    val compareUrl: String? = null,
    val tested: Boolean = false,
    val error: String? = null,
    val suspectFiles: String? = null,   // diagnose：疑似文件（写入 worker_log）
    val suggestedFix: String? = null,   // diagnose：修复方向（存 suggested_fix 列）
    val log: String? = null,            // fix：changedFiles/summary 摘要（写入 worker_log）
)
```

`post("/diag/report")` 中 `DiagService.createJob(...)` 调用改为：

```kotlin
        val id = DiagService.createJob(
            ownerTokenHash = owner,
            deviceId = deviceId,
            description = req.description,
            bundleJson = appJson.encodeToString(DiagBundle.serializer(), req.bundle),
            gitSha = req.bundle.gitSha,
            conversationSummary = req.conversationSummary,
        )
```

`get("/diag/work/jobs")` 中 `DiagClaimResponse(...)` 构造改为：

```kotlin
        call.respond(
            DiagClaimResponse(
                jobId = claim.id,
                phase = claim.phase,
                description = claim.description,
                bundle = bundle,
                gitSha = claim.gitSha,
                rootCause = claim.rootCause,
                fixMode = claim.fixMode,
                conversationSummary = claim.conversationSummary,
                suggestedFix = claim.suggestedFix,
            ),
        )
```

`post("/diag/work/jobs/{id}/result")` 两个分支改为（suspectFiles 并入 worker_log；fix 的 log 优先于 error 写入 worker_log）：

```kotlin
            "diagnose" -> {
                val status = parseDiagnoseStatus(r.status)
                if (status == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "bad diagnose status"))
                    return@post
                }
                DiagService.submitDiagnosis(
                    id, r.rootCause, status,
                    error = r.error ?: r.suspectFiles?.let { "suspectFiles: $it" },
                    suggestedFix = r.suggestedFix,
                )
            }
            "fix" -> {
                val status = parseFixStatus(r.status)
                if (status == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "bad fix status"))
                    return@post
                }
                DiagService.submitFix(id, status, r.fixBranch, r.compareUrl, r.tested, r.log ?: r.error)
            }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest" --tests "com.mamba.picme.server.routes.DiagRouteTest"`
Expected: 全部 PASS（含既有用例回归）。

- [ ] **Step 7: Commit**

```
feat(server): diag_job 增加 conversation_summary/suggested_fix 列并打通 report→claim 契约
```

---

## Task 2: server —— S2 失败原因透出（error + updatedAt）

**Files:**
- Modify `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`（DiagJobRow / getJob，~17-83）
- Modify `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt`（DiagJobStatus / get 端点，~37-45, 94-114）
- Test `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`、`server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt`

- [ ] **Step 1: 写失败测试**

`DiagServiceTest.kt` 末尾追加：

```kotlin
    @Test
    fun `getJob exposes workerLog and updatedAt`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, null, DiagStatus.DIAGNOSE_FAILED, "claude_exit=1 boom") }
        val job = runBlocking { DiagService.getJob(id, "o") }!!
        assertEquals("claude_exit=1 boom", job.workerLog)
        assertTrue(job.updatedAt > 0)
    }
```

`DiagRouteTest.kt` 末尾追加（需新增 import `kotlinx.serialization.json.long`）：

```kotlin
    @Test
    fun `job status exposes error tail and updatedAt after failure`() = testApplication {
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
            setBody("""{"phase":"diagnose","status":"DIAGNOSE_FAILED","error":"claude_exit=1 boom"}""")
        }
        val s = appJson.parseToJsonElement(
            client.get("/diag/jobs/$jobId") { header(APP_TOKEN_HEADER, token) }.bodyAsText(),
        ).jsonObject
        assertEquals("DIAGNOSE_FAILED", s["status"]!!.jsonPrimitive.content)
        assertEquals("claude_exit=1 boom", s["error"]!!.jsonPrimitive.content)
        assertTrue(s["updatedAt"]!!.jsonPrimitive.long > 0)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.getJob*" --tests "com.mamba.picme.server.routes.DiagRouteTest.job status*"`
Expected: 编译 FAIL（`workerLog` / `updatedAt` 未定义）。

- [ ] **Step 3: DiagJobRow + getJob 补字段**

`DiagService.kt` `DiagJobRow` 改为：

```kotlin
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
    val workerLog: String?,
    val updatedAt: Long,
)
```

`getJob` 的 `DiagJobRow(...)` 构造尾部追加两行：

```kotlin
                    tested = it[DiagJobs.tested] == 1,
                    workerLog = it[DiagJobs.workerLog],
                    updatedAt = it[DiagJobs.updatedAt],
```

- [ ] **Step 4: DiagRoute 透出**

`DiagRoute.kt` `DiagJobStatus` 改为：

```kotlin
@Serializable
data class DiagJobStatus(
    val jobId: Int,
    val status: String,
    val rootCause: String? = null,
    val fixBranch: String? = null,
    val compareUrl: String? = null,
    val tested: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
)
```

`get("/diag/jobs/{id}")` 的 respond 改为（error 取 workerLog 尾部 ~500 字符）：

```kotlin
        call.respond(
            DiagJobStatus(
                jobId = job.id,
                status = job.status.name,
                rootCause = job.rootCause,
                fixBranch = job.fixBranch,
                compareUrl = job.compareUrl,
                tested = job.tested,
                error = job.workerLog?.takeLast(500),
                updatedAt = job.updatedAt,
            ),
        )
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.*" --tests "com.mamba.picme.server.routes.DiagRouteTest"`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

```
feat(server): GET /diag/jobs/{id} 透出 error（workerLog 尾部）与 updatedAt（S2）
```

---

## Task 3: server —— S1 任务回收 sweeper + S4 启动自检

**Files:**
- Modify `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`（claimNextJob ~89-112 微调 + 新增 sweepStaleJobs）
- Modify `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（main，~54-67）
- Test `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`

- [ ] **Step 1: 写失败测试**

`DiagServiceTest.kt` 末尾追加：

```kotlin
    @Test
    fun `sweepStaleJobs reclaims jobs claimed over 15 minutes ago`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.claimNextJob() } // 置 claimedAt
        val now = System.currentTimeMillis()
        // 把 claimedAt 改到 16 分钟前（updatedAt 保持当前 → 不触发整体超时）
        transaction(Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id }) { it[DiagJobs.claimedAt] = now - 16 * 60_000L }
        }
        val (reclaimed, timedOut) = runBlocking { DiagService.sweepStaleJobs(now) }
        assertEquals(1, reclaimed)
        assertEquals(0, timedOut)
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertNull(row[DiagJobs.claimedAt])
        // 回收后任务重新可领
        assertNotNull(runBlocking { DiagService.claimNextJob() })
    }

    @Test
    fun `sweepStaleJobs marks non-terminal jobs idle over 1 hour as TIMED_OUT`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        val now = System.currentTimeMillis()
        transaction(Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id }) { it[DiagJobs.updatedAt] = now - 61 * 60_000L }
        }
        val (reclaimed, timedOut) = runBlocking { DiagService.sweepStaleJobs(now) }
        assertEquals(0, reclaimed)
        assertEquals(1, timedOut)
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.TIMED_OUT.name, row[DiagJobs.status])
    }

    @Test
    fun `sweepStaleJobs leaves terminal jobs untouched`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        val now = System.currentTimeMillis()
        transaction(Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = DiagStatus.FIXED.name
                it[DiagJobs.updatedAt] = now - 61 * 60_000L
            }
        }
        val (reclaimed, timedOut) = runBlocking { DiagService.sweepStaleJobs(now) }
        assertEquals(0, reclaimed)
        assertEquals(0, timedOut)
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.FIXED.name, row[DiagJobs.status])
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.sweepStaleJobs*"`
Expected: 编译 FAIL（`sweepStaleJobs` 未定义）。

- [ ] **Step 3: 实现 sweepStaleJobs + claim 刷 updatedAt**

`DiagService.kt` 中 `claimNextJob` 的 `DiagJobs.update(...)` 一行改为（领取也算活动，刷新 updatedAt，避免「排队 56 分钟 + 执行 5 分钟」被整体超时误杀）：

```kotlin
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[claimedAt] = now
                it[updatedAt] = now
            }
```

`DiagService` 内（`archive` 函数之前）新增：

```kotlin
    /**
     * S1 任务回收 sweeper（Application 启动的周期协程调用）：
     * - 整体超时：非终态（QUEUED/DIAGNOSED/FIX_REQUESTED）超 [overallTimeoutMs] 未更新 → TIMED_OUT。
     * - 领取回收：QUEUED/FIX_REQUESTED 且 claimedAt 超 [claimTimeoutMs] 未更新 → claimedAt 置空，重新可领。
     *   15min > worker 侧 DIAG_PHASE_TIMEOUT（≤600s），正常执行不会误回收，无需心跳。
     * 返回 (领取回收数, 整体超时数)。
     */
    suspend fun sweepStaleJobs(
        nowMs: Long,
        claimTimeoutMs: Long = 15 * 60_000L,
        overallTimeoutMs: Long = 60 * 60_000L,
    ): Pair<Int, Int> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val nonTerminal = listOf(
            DiagStatus.QUEUED.name, DiagStatus.DIAGNOSED.name, DiagStatus.FIX_REQUESTED.name,
        )
        val timedOut = DiagJobs.update({
            (DiagJobs.status inList nonTerminal) and (DiagJobs.updatedAt less nowMs - overallTimeoutMs)
        }) {
            it[DiagJobs.status] = DiagStatus.TIMED_OUT.name
            it[DiagJobs.updatedAt] = nowMs
        }
        val claimable = listOf(DiagStatus.QUEUED.name, DiagStatus.FIX_REQUESTED.name)
        val reclaimed = DiagJobs.update({
            (DiagJobs.status inList claimable) and
                DiagJobs.claimedAt.isNotNull() and
                (DiagJobs.claimedAt less nowMs - claimTimeoutMs)
        }) {
            it[DiagJobs.claimedAt] = null
            it[DiagJobs.updatedAt] = nowMs
        }
        reclaimed to timedOut
    }
```

- [ ] **Step 4: Application 装配 sweeper + S4 WARN**

`Application.kt` import 区追加：

```kotlin
import com.mamba.picme.server.diag.DiagService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

`main()` 中，`runBlocking { SettingsService.load() }` 之后、`embeddedServer(...)` 之前插入：

```kotlin
    // S4：worker token 未配置时打 WARN（消除静默 401）
    if (config.diagWorkerToken.isBlank()) {
        logger.warn("DIAG_WORKER_TOKEN 未配置：/diag/work/** 端点已禁用（worker 请求一律 401）")
    }
    // S1：diag 任务回收 sweeper —— 每 5 分钟扫一次（领取回收 15min + 整体超时 1h → TIMED_OUT）
    val diagSweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    diagSweepScope.launch {
        while (true) {
            delay(5 * 60_000L)
            runCatching {
                val (reclaimed, timedOut) = DiagService.sweepStaleJobs(System.currentTimeMillis())
                if (reclaimed > 0 || timedOut > 0) {
                    logger.info("diag sweeper: reclaimed=$reclaimed timedOut=$timedOut")
                }
            }.onFailure { logger.warn("diag sweeper failed", it) }
        }
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest"`
Expected: 全部 PASS（含 3 个 sweeper 新用例）。

- [ ] **Step 6: Commit**

```
feat(server): diag sweeper 领取回收+整体超时（S1）与 DIAG_WORKER_TOKEN 启动自检（S4）
```

---

## Task 4: server —— S3 上报护栏（限频 429 + 限长 413）

**Files:**
- Modify `server/src/main/kotlin/com/mamba/picme/server/routes/DiagRoute.kt`（~72-92）
- Modify `server/src/main/kotlin/com/mamba/picme/server/Application.kt`（~131, 150）
- Test `server/src/test/kotlin/com/mamba/picme/server/routes/DiagRouteTest.kt`

- [ ] **Step 1: 写失败测试**

`DiagRouteTest.kt` import 区追加 `import com.mamba.picme.server.ratelimit.RateLimiter`；在 `diagApp` helper 后追加限流版 helper：

```kotlin
    /** 带限流器的变体（S3 限频测试用）。 */
    private fun TestApplicationBuilder.diagAppLimited(limiter: RateLimiter, vararg extra: Table) {
        TestDb.init(DiagJobs, *extra)
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { diagRoute(workerToken, limiter) }
        }
    }
```

文件末尾追加：

```kotlin
    @Test
    fun `report with overlong description returns 413`() = testApplication {
        diagApp(Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val longDesc = "d".repeat(2001)
        val resp = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"$longDesc","bundle":{"gitSha":"s"}}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `report with overlong conversationSummary returns 413`() = testApplication {
        diagApp(Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val longSummary = "s".repeat(4001)
        val resp = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"d","conversationSummary":"$longSummary","bundle":{"gitSha":"s"}}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `report with overlong logs returns 413`() = testApplication {
        diagApp(Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val longLogs = "x".repeat(200 * 1024 + 1)
        val resp = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"d","bundle":{"logs":"$longLogs","gitSha":"s"}}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `report rate limit returns 429 after 5 reports per hour`() = testApplication {
        diagAppLimited(RateLimiter(5, 3_600_000L), Accounts)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        repeat(5) { i ->
            val resp = client.post("/diag/report") {
                header(APP_TOKEN_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"description":"d$i","bundle":{"gitSha":"s"}}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
        val sixth = client.post("/diag/report") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"d6","bundle":{"gitSha":"s"}}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.DiagRouteTest.report*"`
Expected: 前 3 个 FAIL（当前返回 200），第 4 个编译 FAIL（`diagRoute` 只收 1 个参数）。

- [ ] **Step 3: DiagRoute 加护栏**

`DiagRoute.kt` import 区追加 `import com.mamba.picme.server.ratelimit.RateLimiter`；文件顶部（`@Serializable data class DiagBundle` 之前）加常量：

```kotlin
/** S3 上报护栏长度上限（超限 413）。 */
private const val MAX_DESCRIPTION_LEN = 2000
private const val MAX_SUMMARY_LEN = 4000
private const val MAX_LOGS_LEN = 200 * 1024
```

`diagRoute` 签名与 `post("/diag/report")` 改为：

```kotlin
fun Routing.diagRoute(workerToken: String, reportRateLimiter: RateLimiter? = null) {
    // ── 手机侧端点（X-App-Token；全局拦截器在 prod 已校验，这里兜底取 owner 身份）──
    post("/diag/report") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        // S3 限频：每账号 5 次/小时（key=owner tokenHash），先于 body 解析
        if (reportRateLimiter != null && !reportRateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded"))
            return@post
        }
        val req = call.receive<DiagReportRequest>()
        if (req.description.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "description required"))
            return@post
        }
        // S3 限长：description ≤ 2000、conversationSummary ≤ 4000、logs ≤ 200KB
        if (req.description.length > MAX_DESCRIPTION_LEN ||
            (req.conversationSummary?.length ?: 0) > MAX_SUMMARY_LEN ||
            req.bundle.logs.length > MAX_LOGS_LEN
        ) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "payload_too_large"))
            return@post
        }
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        val id = DiagService.createJob(
            ownerTokenHash = owner,
            deviceId = deviceId,
            description = req.description,
            bundleJson = appJson.encodeToString(DiagBundle.serializer(), req.bundle),
            gitSha = req.bundle.gitSha,
            conversationSummary = req.conversationSummary,
        )
        call.respond(DiagReportResponse(id, DiagStatus.QUEUED.name))
    }
```

- [ ] **Step 4: Application 装配限流器**

`Application.kt` 中 `val rateLimiter = ...` 一行之后加：

```kotlin
    // S3：diag 上报护栏（每账号 5 次/小时）
    val diagReportLimiter = RateLimiter(5, 3_600_000L)
```

`routing {` 中 `diagRoute(config.diagWorkerToken)` 改为：

```kotlin
        diagRoute(config.diagWorkerToken, diagReportLimiter)
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.routes.DiagRouteTest"`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

```
feat(server): /diag/report 限频 429（5 次/小时/账号）与限长 413 护栏（S3）
```

---

## Task 5: worker —— W3 模板替换改 python3 渲染（消除 `|` / `&` / `\` 注入面）

**Files:**
- Modify `scripts/diag-worker/lib.sh`（末尾追加 render_template）
- Modify `scripts/diag-worker/run-diagnose.sh`（~7-20）
- Modify `scripts/diag-worker/run-fix.sh`（~7-20）
- Modify `scripts/diag-worker/prompts/diagnose.md`（加 `__CONVERSATION_SUMMARY__` 段）
- Modify `scripts/diag-worker/smoke/stub-claude.sh`（记录 prompt 供断言）
- Modify `scripts/diag-worker/smoke/run-smoke.sh`（注入用例）
- Modify `scripts/diag-worker/README.md`（依赖清单）

注：本 Task 顺带把 `run-fix.sh` 第 10 行的 `suggested=""` 占位改为从 claim 读 `.suggestedFix`（字段由 Task 1 的 server 提供；旧 server 无此字段时 jq 返回 `""`，行为同现状）。run-diagnose.sh 回传三字段在 Task 6。

- [ ] **Step 1: lib.sh 加 render_template**

`lib.sh` 末尾（`wlog` 函数之后）追加：

```bash
# W3：用 python3 读模板 + 环境变量安全替换（用户日志含 | & \ 时 sed 会破坏替换）。
# 占位符固定集合：__GIT_SHA__ __DESCRIPTION__ __CONVERSATION_SUMMARY__ __LOGS__ __CRASH_TRACE__ __ROOT_CAUSE__ __SUGGESTED_FIX__
# 调用前把值放进 TPL_* 环境变量（原样传递，不做任何转义；python str.replace 无元字符问题）。
# 用法: render_template <templateFile>
render_template() {
  python3 - "$1" <<'PYEOF'
import os, sys
text = open(sys.argv[1], encoding='utf-8').read()
for key in ("GIT_SHA", "DESCRIPTION", "CONVERSATION_SUMMARY", "LOGS", "CRASH_TRACE", "ROOT_CAUSE", "SUGGESTED_FIX"):
    text = text.replace("__%s__" % key, os.environ.get("TPL_" + key, ""))
sys.stdout.write(text)
PYEOF
}
```

- [ ] **Step 2: run-diagnose.sh 改用 render_template**

`run-diagnose.sh` 第 7-11 行（`jobId="$1"; claim="$2"` 至 `crash=...`）整段替换为：

```bash
jobId="$1"; claim="$2"
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"

# W3：模板变量经 TPL_* 环境变量传给 python3 渲染（原样，不经 json_escape；
# json_escape 会把换行压成 \n 字面量，仅 sed 时代需要）。
export TPL_GIT_SHA="$gitSha"
export TPL_DESCRIPTION="$(printf '%s' "$claim" | jq -r '.description // ""')"
export TPL_CONVERSATION_SUMMARY="$(printf '%s' "$claim" | jq -r '.conversationSummary // ""')"
export TPL_LOGS="$(printf '%s' "$claim" | jq -r '.bundle.logs // ""')"
export TPL_CRASH_TRACE="$(printf '%s' "$claim" | jq -r '.bundle.crashTrace // ""')"
```

第 20 行 sed 渲染一行替换为：

```bash
prompt="$(render_template "$SCRIPT_DIR/prompts/diagnose.md")"
```

- [ ] **Step 3: run-fix.sh 改用 render_template + suggestedFix 取值**

`run-fix.sh` 第 8-11 行（`gitSha=...` 至 `mode=...`）替换为：

```bash
gitSha="$(printf '%s' "$claim" | jq -r .gitSha)"
# W1：诊断阶段回传的 suggestedFix 经 claim 传入（旧 server 无此字段 → 空串，同现状）
export TPL_ROOT_CAUSE="$(printf '%s' "$claim" | jq -r '.rootCause // ""')"
export TPL_SUGGESTED_FIX="$(printf '%s' "$claim" | jq -r '.suggestedFix // ""')"
mode="$(printf '%s' "$claim" | jq -r '.fixMode // "push"')"
```

（同时删除原第 9 行 `rootCause=...` 与第 10 行 `suggested=""   # 诊断阶段未回传 suggestedFix，留空`。）

第 20 行 sed 渲染一行替换为：

```bash
prompt="$(render_template "$SCRIPT_DIR/prompts/fix.md")"
```

- [ ] **Step 4: diagnose.md 加摘要段**

`prompts/diagnose.md` 全量替换为：

```markdown
You are diagnosing a bug in an Android (Kotlin/Compose) project: PoLang (破浪相册).

Build git SHA: __GIT_SHA__
User-reported problem:
__DESCRIPTION__

Clarified conversation summary from the in-app diagnosis chat (may be empty for old clients):
__CONVERSATION_SUMMARY__

Sanitized app logs (PoLang:* tags):
__LOGS__

Crash trace (if any):
__CRASH_TRACE__

Your task: find the ROOT CAUSE in the source code (checked out at the above SHA, in the current directory). Explore the relevant files. Do NOT modify any file — analysis only.

OUTPUT RULES (critical — follow exactly):
- Reply with ONLY the JSON object below. No prose, no greeting, no explanation.
- No markdown code fences. The first character of your reply MUST be "{" and the last MUST be "}".
- Keep rootCause to one paragraph. If unsure, still output the JSON with your best guess.

{"rootCause": "<one-paragraph root cause>", "suspectFiles": ["<file:line>", ...], "suggestedFix": "<brief fix direction>"}
```

- [ ] **Step 5: stub-claude.sh 记录 prompt**

`smoke/stub-claude.sh` 全量替换为：

```bash
#!/usr/bin/env bash
# 假 claude，供 smoke 验证 poller 胶水。真实 claude 以 `claude -p "<prompt>" ...` 调用，
# 故 $1=-p、$2=prompt。按 prompt 内容吐出固定 JSON；同时把 prompt 落盘供 smoke 断言。
prompt="$2"
printf '%s' "$prompt" > "${DIAG_WORKDIR:-/tmp}/last-prompt.txt"
if printf '%s' "$prompt" | grep -q "Do NOT modify"; then
  # diagnose 分支：输出 .result 内嵌一段含三字段的 JSON
  printf '%s' '{"result":"{\"rootCause\":\"stub: NPE GalleryScreen null uri\",\"suspectFiles\":[\"GalleryScreen.kt\"],\"suggestedFix\":\"null check\"}"}'
else
  # fix 分支：不产生任何文件改动（供 W2 空改动用例）
  printf '%s' '{"result":"{\"changedFiles\":[],\"summary\":\"stub fix\"}"}'
fi
```

- [ ] **Step 6: smoke 加注入用例**

`smoke/run-smoke.sh` 中 `CLAIM=...` 一行替换为（logs 含 `|` `&` `\` `"` 特殊字符 + 带 conversationSummary）：

```bash
CLAIM='{"jobId":1,"phase":"diagnose","description":"crash on open gallery","conversationSummary":"现象: 打开相册崩溃","bundle":{"logs":"PoLang:Gallery boom | sed & break \\ path \"q\"","gitSha":"'"$SHA"'","appVersion":"1.0.29","deviceModel":"X","androidVersion":"14"},"gitSha":"'"$SHA"'"}'
# 注：JSON 内 \\ 解码为单个 \（jq -r 输出 boom | sed & break \ path "q"），勿写成 \ （非法 JSON escape）。
```

在 `echo "ok diagnose glue -> $(cat "$CAPTURE")"` 之后追加：

```bash
# --- 2b) 模板注入安全（W3）：含 | & \ " 的日志原样进入 prompt，不被替换语法破坏 ---
grep -qF 'boom | sed & break \ path "q"' "$DIAG_WORKDIR/last-prompt.txt" \
  || { echo "FAIL template injection; prompt:"; cat "$DIAG_WORKDIR/last-prompt.txt"; exit 1; }
echo "ok template injection safe"

# --- 2c) conversationSummary 进入 diagnose prompt ---
grep -qF '现象: 打开相册崩溃' "$DIAG_WORKDIR/last-prompt.txt" \
  || { echo "FAIL conversationSummary missing in prompt"; cat "$DIAG_WORKDIR/last-prompt.txt"; exit 1; }
echo "ok conversationSummary in diagnose prompt"
```

- [ ] **Step 7: README 补 python3 依赖**

`README.md` 第 12 行（`确认 PATH 上有 claude...jq、curl、git、./gradlew`）改为：

```markdown
3. 确认 PATH 上有 `claude`（Claude Code，GLM 后端）、`jq`、`curl`、`git`、`python3`（模板渲染用，云主机 Ubuntu 自带）、`./gradlew`（修复自检用，可选）。
```

- [ ] **Step 8: 跑 smoke 确认通过**

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: 尾部输出 `SMOKE PASS`，且新增两行 `ok template injection safe` / `ok conversationSummary in diagnose prompt`。

- [ ] **Step 9: Commit**

```
fix(worker): 模板替换改 python3 渲染，消除日志特殊字符注入面（W3）
```

---

## Task 6: worker —— W1 诊断→修复三字段传递链

**Files:**
- Modify `scripts/diag-worker/run-diagnose.sh`（rootCause 解析段 ~26-54）
- Modify `scripts/diag-worker/smoke/run-smoke.sh`（断言三字段回传 + fix prompt 拿到 suggestedFix）

前置：Task 1（server 存 `suggested_fix`、claim 返回）与 Task 5（run-fix.sh 已读 `.suggestedFix`）已完成。

- [ ] **Step 1: 改 smoke 断言（先行失败）**

`smoke/run-smoke.sh` 中 `ok diagnose glue` 断言块之后（即 Task 5 的 2b/2c 之前）插入：

```bash
# --- 2d) W1：三字段（rootCause/suspectFiles/suggestedFix）全部回传 ---
grep -q '"suspectFiles":"GalleryScreen.kt"' "$CAPTURE" \
  || { echo "FAIL suspectFiles missing; captured:"; cat "$CAPTURE"; exit 1; }
grep -q '"suggestedFix":"null check"' "$CAPTURE" \
  || { echo "FAIL suggestedFix missing; captured:"; cat "$CAPTURE"; exit 1; }
echo "ok diagnose three-field report"
```

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: FAIL — `FAIL suspectFiles missing`（当前 run-diagnose.sh 只回传 rootCause）。

- [ ] **Step 2: run-diagnose.sh 解析并回传三字段**

`run-diagnose.sh` 末尾的「成功/失败回传」分支（原第 52-61 行）整段替换为：

```bash
# W1：从同一份 claude 输出抠 suspectFiles / suggestedFix（best-effort；仅 rootCause 成功时才回传）。
# inner 是 .result 文本（形态 a），抠不出 JSON 时退到整段 out（形态 b/c 由 jq 直接兜）。
suspectFiles=""; suggestedFix=""
if [ -n "$rootCause" ] && [ "$rootCause" != "null" ]; then
  json_src="$inner"
  [ -z "$json_src" ] && json_src="$out"
  json_obj="$(printf '%s' "$json_src" | tr '\n' ' ' | sed 's/.*\({.*}\).*/\1/' 2>/dev/null)"
  [ -n "$json_obj" ] && suspectFiles="$(printf '%s' "$json_obj" | jq -r '(.suspectFiles // []) | if type == "array" then join(", ") else . end' 2>/dev/null)"
  [ -n "$json_obj" ] && suggestedFix="$(printf '%s' "$json_obj" | jq -r '.suggestedFix // empty' 2>/dev/null)"
fi

if [ -n "$rootCause" ] && [ "$rootCause" != "null" ]; then
  rc_escaped="$(printf '%s' "$rootCause" | json_escape)"
  sf_escaped="$(printf '%s' "$suspectFiles" | json_escape)"
  fx_escaped="$(printf '%s' "$suggestedFix" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSED\",\"rootCause\":\"$rc_escaped\",\"suspectFiles\":\"$sf_escaped\",\"suggestedFix\":\"$fx_escaped\"}"
else
  # 解析失败：把 claude 的 .result（模型最终文本）+ num_turns + exit code 回传到 workerLog，便于排查
  result_field="$(printf '%s' "$out" | jq -r '.result // empty' 2>/dev/null)"
  nt="$(printf '%s' "$out" | jq -r '.num_turns // empty' 2>/dev/null)"
  raw="$(printf 'claude_exit=%s num_turns=%s | .result[0:600]=%.600s' "$rc" "$nt" "$result_field" | json_escape)"
  report_result "$jobId" "{\"phase\":\"diagnose\",\"status\":\"DIAGNOSE_FAILED\",\"error\":\"no rootCause parsed; $raw\"}"
fi
```

- [ ] **Step 3: smoke 加 fix 侧断言并全量跑通**

`smoke/run-smoke.sh` 在 `echo "ok conversationSummary in diagnose prompt"` 之后追加（fix 胶水：suggestedFix 经 claim 进入 fix prompt；空改动 FIX_FAILED 属 Task 7，此处只断言 prompt 传递与 FIX_FAILED 之外的推进——本 Task 仅断言 prompt）：

```bash
# --- 3) W1：fix 阶段 claim 带 suggestedFix → fix prompt 拿到真实值 ---
CLAIM_FIX='{"jobId":2,"phase":"fix","gitSha":"'"$SHA"'","rootCause":"stub: NPE","suggestedFix":"null check","fixMode":"push"}'
: > "$CAPTURE"
bash "$WD/run-fix.sh" 2 "$CLAIM_FIX" || true
grep -qF 'null check' "$DIAG_WORKDIR/last-prompt.txt" \
  || { echo "FAIL suggestedFix not in fix prompt"; cat "$DIAG_WORKDIR/last-prompt.txt"; exit 1; }
echo "ok suggestedFix in fix prompt"
```

注意：当前 run-fix.sh 在空改动时仍会走 commit/push 路径（smoke 仓无 origin，push 失败 → 回 FIX_FAILED「push failed」，属预期；Task 7 会把它改为「模型未产生修改」）。`: > "$CAPTURE"` 清空后本次 run-fix 的 report_result 不阻断 smoke。

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: `SMOKE PASS`，新增 `ok diagnose three-field report` 与 `ok suggestedFix in fix prompt`。

- [ ] **Step 4: Commit**

```
feat(worker): 诊断回传 suspectFiles/suggestedFix，fix prompt 拿到真实修复方向（W1）
```

---

## Task 7: worker —— W2 修复产出校验（空改动 FIX_FAILED + changedFiles/summary 回传）

**Files:**
- Modify `scripts/diag-worker/run-fix.sh`（claude 完成后 ~25 行处插入校验；各 mode 的 report_result 加 log 字段）
- Modify `scripts/diag-worker/smoke/run-smoke.sh`（空改动用例）

- [ ] **Step 1: 改 smoke 断言（先行失败）**

`smoke/run-smoke.sh` 中 Task 6 加入的 `ok suggestedFix in fix prompt` 之后追加：

```bash
# --- 3b) W2：模型未产生改动 → FIX_FAILED（不产生空 commit/空分支）---
grep -q '"status":"FIX_FAILED"' "$CAPTURE" && grep -q '模型未产生修改' "$CAPTURE" \
  || { echo "FAIL empty-change should be FIX_FAILED; captured:"; cat "$CAPTURE"; exit 1; }
echo "ok fix empty-change -> FIX_FAILED"
```

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: FAIL — 当前空改动走到 push 失败，capture 里是 `"error":"push failed"` 而非「模型未产生修改」。

- [ ] **Step 2: run-fix.sh 加产出校验与 log 解析**

`run-fix.sh` 中，`wlog "job #$jobId claude fix done rc=$? ..."` 一行之后、自检段（`# 自检：跑 server JVM 单测`）之前插入：

```bash
# W2：模型未产生任何文件改动 → 直接 FIX_FAILED，不再空 commit 照推分支
if [ -z "$(git -C "$repo" status --porcelain)" ]; then
  wlog "job #$jobId 模型未产生修改（git status 干净），回 FIX_FAILED"
  report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"error\":\"模型未产生修改\"}"
  exit 0
fi

# W2：模型的 changedFiles / summary 随结果回传（server 写入 worker_log，admin 详情可见）
fix_inner="$(jq -r '.result // empty' "$claude_out" 2>/dev/null | sed 's/```[a-zA-Z]*//g')"
fix_summary="$(printf '%s' "$fix_inner" | jq -r '.summary // empty' 2>/dev/null)"
fix_files="$(printf '%s' "$fix_inner" | jq -r '(.changedFiles // []) | if type == "array" then join(", ") else . end' 2>/dev/null)"
fix_log="$(printf 'summary=%s changedFiles=%s' "$fix_summary" "$fix_files" | json_escape)"
```

各 mode 分支的成功 `report_result` 全部加 `"log"` 字段（server 端 `r.log ?: r.error` 写入 worker_log，见 Task 1）：

push 分支（原第 50 行）改为：

```bash
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested,\"log\":\"$fix_log\"}"
```

pr 分支 PR 成功一行（原第 62 行）改为：

```bash
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested,\"compareUrl\":\"$(printf '%s' "$pr_url" | json_escape)\",\"log\":\"$fix_log\"}"
```

auto 分支合并成功一行（原第 86 行）改为：

```bash
        report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIXED\",\"fixBranch\":\"$DIAG_BASE_BRANCH\",\"tested\":true,\"log\":\"$fix_log\"}"; exit 0
```

auto 降级一行（原第 92 行）改为：

```bash
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIXED_UNVERIFIED\",\"fixBranch\":\"$branch\",\"tested\":$tested,\"log\":\"$fix_log\"}"
```

默认分支一行（原第 96 行）改为：

```bash
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested,\"log\":\"$fix_log\"}"
```

- [ ] **Step 3: 跑 smoke 确认通过**

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: `SMOKE PASS`，新增 `ok fix empty-change -> FIX_FAILED`。

- [ ] **Step 4: Commit**

```
fix(worker): 修复空改动回 FIX_FAILED 不再空推分支，changedFiles/summary 写入 worker_log（W2）
```

---

## Task 8: app —— A5 死代码清理（DiagController）

**Files:**
- Delete `app/src/main/java/com/mamba/picme/features/chat/DiagController.kt`
- Delete `app/src/test/java/com/mamba/picme/features/chat/DiagControllerTest.kt`

背景：`DiagController` / `PendingDiagConfirm` 已无任何生产引用（根因确认已改为气泡内嵌按钮 `DiagConfirmUi`），全仓 grep 仅命中其自身、单测与历史文档。

- [ ] **Step 1: 确认无引用**

Run: `grep -rn "DiagController\|PendingDiagConfirm" app/src --include="*.kt" | grep -v "DiagController.kt\|DiagControllerTest.kt"`
Expected: 无输出。

- [ ] **Step 2: 删除两个文件**

Run:
```bash
rm app/src/main/java/com/mamba/picme/features/chat/DiagController.kt \
   app/src/test/java/com/mamba/picme/features/chat/DiagControllerTest.kt
```

- [ ] **Step 3: 编译 + 全量 app 单测确认无回归**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```
refactor(app): 删除死代码 DiagController 及其单测（A5，气泡内嵌按钮已取代）
```

---

## Task 9: app —— A4 i18n 与文案修复（四语言）

**Files:**
- Modify `app/src/main/res/values/strings.xml`（diag_* 段，~1006-1021）
- Modify `app/src/main/res/values-zh/strings.xml`（~707-724）
- Modify `app/src/main/res/values-zh-rCN/strings.xml`（~1000-1015）
- Modify `app/src/main/res/values-zh-rTW/strings.xml`（~978-993）

内容：① `diag_sheet_title` / `diag_sheet_cancel` 补齐到 en / zh-rCN / zh-rTW（当前仅 values-zh 有，lint MissingTranslation）；② values-zh 的 `diag_root_cause` 删除「请在弹窗选择修复方式」尾巴（与内嵌按钮不符）；③ 新增 `diag_fixing_auto`（auto 模式专属）、`diag_submit_report`（§2 提交按钮）、`diag_poll_timeout` / `diag_job_timed_out`（A2/S1 提示）四个 key 的四语言。

- [ ] **Step 1: values/strings.xml（en）**

`diag_fix_failed` 一行之后追加：

```xml
    <string name="diag_sheet_title">Remote diagnosis · Root cause</string>
    <string name="diag_sheet_cancel">Cancel</string>
    <string name="diag_fixing_auto">🔧 Fixing (auto-merge)…</string>
    <string name="diag_submit_report">Submit diagnosis</string>
    <string name="diag_poll_timeout">⏱ Timed out waiting for a result (30 min). Please start a new diagnosis.</string>
    <string name="diag_job_timed_out">⏱ The diagnosis job timed out on the server. Please start a new one.</string>
```

- [ ] **Step 2: values-zh/strings.xml**

`diag_root_cause` 一行改为（删尾巴）：

```xml
    <string name="diag_root_cause">🔍 **根因分析**\n\n%1$s</string>
```

`diag_fix_failed` 一行之后追加：

```xml
    <string name="diag_fixing_auto">🔧 修复中（自动合并）…</string>
    <string name="diag_submit_report">提交诊断</string>
    <string name="diag_poll_timeout">⏱ 等待结果超时（30 分钟），请重新发起诊断。</string>
    <string name="diag_job_timed_out">⏱ 诊断任务在服务端已超时，请重新发起。</string>
```

- [ ] **Step 3: values-zh-rCN/strings.xml**

`diag_fix_failed` 一行之后追加：

```xml
    <string name="diag_sheet_title">远程诊断 · 根因</string>
    <string name="diag_sheet_cancel">取消</string>
    <string name="diag_fixing_auto">🔧 修复中（自动合并）…</string>
    <string name="diag_submit_report">提交诊断</string>
    <string name="diag_poll_timeout">⏱ 等待结果超时（30 分钟），请重新发起诊断。</string>
    <string name="diag_job_timed_out">⏱ 诊断任务在服务端已超时，请重新发起。</string>
```

- [ ] **Step 4: values-zh-rTW/strings.xml**

`diag_fix_failed` 一行之后追加：

```xml
    <string name="diag_sheet_title">遠端診斷 · 根因</string>
    <string name="diag_sheet_cancel">取消</string>
    <string name="diag_fixing_auto">🔧 修復中（自動合併）…</string>
    <string name="diag_submit_report">提交診斷</string>
    <string name="diag_poll_timeout">⏱ 等待結果超時（30 分鐘），請重新發起診斷。</string>
    <string name="diag_job_timed_out">⏱ 診斷任務在伺服器端已超時，請重新發起。</string>
```

- [ ] **Step 5: 编译确认（资源链接 + lint 不缺翻译）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```
fix(app): diag 四语言文案补齐、删除根因「弹窗」残留尾巴、新增 auto/提交/超时文案（A4）
```

---

## Task 10: app —— DiagClient 契约扩展（conversationSummary + 脱敏 + 截断 + error/updatedAt 解析）

**Files:**
- Modify `app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt`
- Modify `app/src/main/java/com/mamba/picme/core/diag/DiagBundle.kt`（DiagJobStatus）
- Test `app/src/test/java/com/mamba/picme/data/remote/picme/DiagClientTest.kt`

- [ ] **Step 1: 写失败测试**

`DiagClientTest.kt` 末尾追加（import 区追加 `org.junit.Assert.assertFalse`、`org.junit.Assert.assertNull`）：

```kotlin
    @Test
    fun `report body sanitizes description and conversationSummary`() {
        val bundle = DiagBundle(
            logs = "x", crashTrace = null, appVersion = "1",
            gitSha = "s", deviceModel = "m", androidVersion = "14",
        )
        val obj = JSONObject(
            DiagClient.buildReportBody(
                "mail me at a@b.com", bundle,
                "token pl-0123456789abcdef0123456789abcdef leaked",
            )
        )
        assertEquals("mail me at <email>", obj.getString("description"))
        assertEquals("token <token> leaked", obj.getString("conversationSummary"))
    }

    @Test
    fun `report body omits conversationSummary when null or blank`() {
        val bundle = DiagBundle(
            logs = "x", crashTrace = null, appVersion = "1",
            gitSha = "s", deviceModel = "m", androidVersion = "14",
        )
        assertFalse(JSONObject(DiagClient.buildReportBody("d", bundle)).has("conversationSummary"))
        assertFalse(JSONObject(DiagClient.buildReportBody("d", bundle, "  ")).has("conversationSummary"))
    }

    @Test
    fun `report body truncates overlong description and summary`() {
        val bundle = DiagBundle(
            logs = "x", crashTrace = null, appVersion = "1",
            gitSha = "s", deviceModel = "m", androidVersion = "14",
        )
        val obj = JSONObject(DiagClient.buildReportBody("d".repeat(3000), bundle, "s".repeat(5000)))
        assertEquals(2000, obj.getString("description").length)
        assertEquals(4000, obj.getString("conversationSummary").length)
    }

    @Test
    fun `parseJobStatus tolerates TIMED_OUT and reads error and updatedAt`() {
        val st = DiagClient.parseJobStatus(
            """{"jobId":3,"status":"TIMED_OUT","updatedAt":1722440000000,"error":"sweep timeout"}"""
        )
        assertEquals("TIMED_OUT", st.status)
        assertEquals("sweep timeout", st.error)
        assertEquals(1722440000000L, st.updatedAt)
    }

    @Test
    fun `parseJobStatus defaults optional fields for old server responses`() {
        val st = DiagClient.parseJobStatus("""{"jobId":3,"status":"QUEUED"}""")
        assertNull(st.error)
        assertEquals(0L, st.updatedAt)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.DiagClientTest"`
Expected: 编译 FAIL（`parseJobStatus` / 新签名未定义）。

- [ ] **Step 3: DiagJobStatus 加字段**

`DiagBundle.kt` 中 `DiagJobStatus` 改为：

```kotlin
/** server /diag/jobs/{id} 回传的任务状态（手机端展示用）。未知新状态按非终态处理（继续轮询/超时兜底，不 crash）。 */
data class DiagJobStatus(
    val jobId: Int,
    val status: String,
    val rootCause: String?,
    val fixBranch: String?,
    val compareUrl: String?,
    val tested: Boolean,
    val error: String? = null,    // S2：失败原因（workerLog 尾部 ~500 字符）
    val updatedAt: Long = 0L,     // S2：服务端最后更新时间（ms）
)
```

- [ ] **Step 4: DiagClient 改造**

`DiagClient.kt` 全量替换为：

```kotlin
package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.diag.DiagBundle
import com.mamba.picme.core.diag.DiagJobStatus
import com.mamba.picme.core.diag.DiagSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程诊断 HTTP 客户端，镜像 [PoLangAuthClient] 的风格（OkHttp + org.json + X-App-Token）。
 * 与 server 端 DiagRoute 契约一致：POST /diag/report、GET /diag/jobs/{id}、POST /diag/jobs/{id}/confirm。
 * description / conversationSummary 在此统一过 [DiagSanitizer] 并按 server 上限截断（S3 对齐）。
 */
class DiagClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    suspend fun reportDiagnosis(
        token: String,
        description: String,
        bundle: DiagBundle,
        conversationSummary: String? = null,
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/diag/report")
                    .header("X-App-Token", token)
                    .post(buildReportBody(description, bundle, conversationSummary).toRequestBody(jsonMedia))
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                JSONObject(body).getInt("jobId")
            }
        }

    suspend fun fetchDiagStatus(token: String, jobId: Int): Result<DiagJobStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/diag/jobs/$jobId")
                    .header("X-App-Token", token)
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                parseJobStatus(body)
            }
        }

    suspend fun confirmFix(token: String, jobId: Int, mode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("mode", mode).toString()
                val req = Request.Builder()
                    .url("$baseUrl/diag/jobs/$jobId/confirm")
                    .header("X-App-Token", token)
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            }
        }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"

        /** 与 server S3 护栏一致的长度上限（客户端先截断兜底，避免 413）。 */
        private const val MAX_DESCRIPTION_LEN = 2000
        private const val MAX_SUMMARY_LEN = 4000

        /** 构造 /diag/report 请求体（抽出以便单测契约）。description/summary 统一脱敏 + 截断。 */
        fun buildReportBody(description: String, bundle: DiagBundle, conversationSummary: String? = null): String {
            val o = JSONObject()
                .put("description", DiagSanitizer.sanitize(description).take(MAX_DESCRIPTION_LEN))
                .put("bundle", bundle.toJsonObject())
            conversationSummary?.takeIf { it.isNotBlank() }?.let {
                o.put("conversationSummary", DiagSanitizer.sanitize(it).take(MAX_SUMMARY_LEN))
            }
            return o.toString()
        }

        /** 解析 /diag/jobs/{id} 响应（抽出以便单测契约；未知新状态原样保留为字符串，不 crash）。 */
        fun parseJobStatus(body: String): DiagJobStatus {
            val json = JSONObject(body)
            return DiagJobStatus(
                jobId = json.getInt("jobId"),
                status = json.getString("status"),
                rootCause = json.optString("rootCause").takeIf { it.isNotBlank() },
                fixBranch = json.optString("fixBranch").takeIf { it.isNotBlank() },
                compareUrl = json.optString("compareUrl").takeIf { it.isNotBlank() },
                tested = json.optBoolean("tested", false),
                error = json.optString("error").takeIf { it.isNotBlank() },
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.DiagClientTest"`
Expected: 6 个用例全部 PASS（含既有契约用例回归）。

- [ ] **Step 6: Commit**

```
feat(app): DiagClient 支持 conversationSummary、description/summary 脱敏截断、error/updatedAt 解析
```

---

## Task 11: app —— A1 确认按钮绑定 jobId + A2 轮询超时 + TIMED_OUT 提示

**Files:**
- Modify `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（~207-311 诊断段）
- Modify `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（~500 onDiagConfirm 接线）
- Test `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelDiagTest.kt`（新）

- [ ] **Step 1: 写失败测试（新文件）**

新建 `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelDiagTest.kt`：

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.core.diag.DiagJobStatus
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.DiagClient
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import com.mamba.picme.domain.tag.ControlledVocab
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatViewModel] 远程诊断加固单测：
 * - A1：confirmDiagnosis 作用于按钮所在气泡的 jobId（多次诊断不串）
 * - A2：诊断/修复轮询 30 分钟总超时 → 写气泡并退出
 * - S1 配套：轮询到 TIMED_OUT 提示用户重试
 *
 * 测试手法同 [ChatViewModelGuestModeTest]：mockkStatic(Log) + mockkObject(AgentOrchestrator.Companion)。
 * pollDiagnose/pollFix 为 internal（@VisibleForTesting）可直接调用；diagPollTimeoutMs=0 立即超时。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModelDiagTest {

    private val context: Context = mockk(relaxed = true)
    private val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    private val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    private val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)
    private val diagClient: DiagClient = mockk()
    private val orchestrator: AgentOrchestrator = mockk(relaxed = true)

    private val tokenFlow = MutableStateFlow("pl-test-token")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        every { context.applicationContext } returns context
        every { context.getString(com.mamba.picme.R.string.diag_poll_timeout) } returns "POLL_TIMEOUT"
        every { context.getString(com.mamba.picme.R.string.diag_job_timed_out) } returns "JOB_TIMED_OUT"
        every { userSettingsRepository.serverAuthTokenFlow } returns tokenFlow
        every { userSettingsRepository.aiAgentInferencePreferenceFlow } returns
            MutableStateFlow(AiAgentInferencePreference.FORCE_REMOTE)

        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(emptyList())
        coEvery { chatMessageDao.getLastMessageForSession(any()) } returns null
        coEvery { chatMessageDao.getMessageCount(any()) } returns 0
        every { chatSessionDao.getAllSessions() } returns flowOf(emptyList())
        coEvery { chatSessionDao.getSession(any()) } returns null

        mockkObject(AgentOrchestrator.Companion)
        every { AgentOrchestrator.getInstance(any()) } returns orchestrator
        every { orchestrator.getInferencePreference() } returns AiAgentInferencePreference.FORCE_REMOTE
    }

    @After
    fun tearDown() {
        unmockkObject(AgentOrchestrator.Companion)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun newViewModel() = ChatViewModel(
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = chatMessageDao,
            chatSessionDao = chatSessionDao,
            userSettingsRepository = userSettingsRepository,
            mediaSearchEngine = mockk(relaxed = true),
            mediaFeedbackRepository = mockk(relaxed = true),
            mediaRepository = mockk(relaxed = true),
            picMeAuthClient = mockk(relaxed = true),
            diagClient = diagClient,
            getGallerySummaryUseCase = mockk(relaxed = true),
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context),
            personDao = mockk(relaxed = true),
            controlledVocab = ControlledVocab(),
            chatEditStateHolder = ChatEditStateHolder(),
            chatEditProcessor = mockk(relaxed = true),
            chatImageStore = mockk(relaxed = true),
            saveChatEditResultUseCase = mockk(relaxed = true)
        )
    )

    private fun status(s: String, id: Int = 1) = DiagJobStatus(
        jobId = id, status = s, rootCause = "rc", fixBranch = "diag-fix/$id",
        compareUrl = null, tested = true, error = null, updatedAt = 1L,
    )

    // ── A1：确认绑定按钮所在气泡的 jobId ──────────────────────────

    @Test
    fun `confirmDiagnosis acts on the job bound to the clicked bubble`() = runTest {
        val confirmed = mutableListOf<Int>()
        coEvery { diagClient.confirmFix(any(), any(), any()) } answers {
            confirmed += secondArg<Int>()
            Result.success(Unit)
        }
        coEvery { diagClient.fetchDiagStatus(any(), any()) } answers {
            val id = secondArg<Int>()
            Result.success(status(if (id in confirmed) "FIXED" else "DIAGNOSED", id))
        }
        val vm = newViewModel()
        advanceUntilIdle()
        // 两次进行中的诊断：job 7（旧气泡）与 job 8（新气泡）
        vm.trackDiagForTesting("t", 7, "msg7")
        vm.trackDiagForTesting("t", 8, "msg8")

        vm.confirmDiagnosis(7, "push") // 点旧气泡按钮
        advanceUntilIdle()

        assertEquals("确认的是旧气泡的 job 7，而不是最新的 job 8", listOf(7), confirmed)
    }

    // ── A2：轮询 30 分钟总超时 ──────────────────────────────────

    @Test
    fun `pollDiagnose exits with timeout bubble when total timeout elapses`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("QUEUED"))
        val vm = newViewModel()
        vm.diagPollTimeoutMs = 0 // 立即超时：不进入轮询循环
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollDiagnose("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "POLL_TIMEOUT" })
    }

    @Test
    fun `pollFix exits with timeout bubble when total timeout elapses`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("FIX_REQUESTED"))
        val vm = newViewModel()
        vm.diagPollTimeoutMs = 0
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollFix("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "POLL_TIMEOUT" })
    }

    // ── TIMED_OUT 透出（S1 配套）─────────────────────────────────

    @Test
    fun `pollDiagnose shows retry hint on TIMED_OUT`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("TIMED_OUT"))
        val vm = newViewModel()
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollDiagnose("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "JOB_TIMED_OUT" })
    }

    @Test
    fun `pollFix shows retry hint on TIMED_OUT`() = runTest {
        coEvery { diagClient.fetchDiagStatus(any(), any()) } returns Result.success(status("TIMED_OUT"))
        val vm = newViewModel()
        vm.trackDiagForTesting("t", 1, "msg1")

        vm.pollFix("t", 1, "msg1")

        assertTrue(vm.messages.value.any { it.id == "msg1" && it.content == "JOB_TIMED_OUT" })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatViewModelDiagTest"`
Expected: 编译 FAIL（`trackDiagForTesting` / `diagPollTimeoutMs` / `confirmDiagnosis(jobId, mode)` 未定义）。

- [ ] **Step 3: ChatViewModel 诊断段重写**

`ChatViewModel.kt` 第 210-211 行（`private data class ActiveDiag...` + `private var activeDiag...`）替换为：

```kotlin
    private data class ActiveDiag(val token: String, val jobId: Int, val msgId: String)

    /** jobId → 进行中的诊断（支持多次诊断并存；确认按钮绑定各自气泡的 jobId，A1）。 */
    private val activeDiags = mutableMapOf<Int, ActiveDiag>()

    /** 诊断/修复轮询总超时（A2，默认 30 分钟）。 */
    @VisibleForTesting
    internal var diagPollTimeoutMs: Long = 30 * 60_000L

    @VisibleForTesting
    internal fun trackDiagForTesting(token: String, jobId: Int, msgId: String) {
        activeDiags[jobId] = ActiveDiag(token, jobId, msgId)
    }
```

`submitDiagnosis` 整函数替换为（签名加可选 summary，§2；上报成功登记 map 替代单一 activeDiag）：

```kotlin
    /** UI「诊断」入口：把问题描述（+ 可选澄清对话摘要）作为远程诊断上报。 */
    fun submitDiagnosis(description: String, conversationSummary: String? = null) {
        if (description.isBlank()) return
        // 用户气泡（诊断标记）：与普通用户消息同形态，前缀 🔍 表明这是一次诊断请求
        _messages.update { msgs ->
            msgs + ChatMessageUi(
                id = "diag_user_${System.currentTimeMillis()}",
                type = ChatMessageType.USER_TEXT,
                content = "🔍 $description"
            )
        }
        viewModelScope.launch {
            val token = userSettingsRepository.serverAuthTokenFlow.first()
            val msgId = "diag_${System.currentTimeMillis()}"
            if (token.isBlank()) {
                upsertDiagMessage(msgId, context.getString(R.string.diag_login_required))
                return@launch
            }
            val bundle = DiagBundleCollector.collect(
                appVersion = BuildConfig.VERSION_NAME,
                gitSha = BuildConfig.GIT_SHA,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
            )
            upsertDiagMessage(msgId, context.getString(R.string.diag_submitted))
            val jobId = diagClient.reportDiagnosis(token, description, bundle, conversationSummary).getOrElse { e ->
                upsertDiagMessage(msgId, context.getString(R.string.diag_report_failed, e.message ?: ""))
                return@launch
            }
            activeDiags[jobId] = ActiveDiag(token, jobId, msgId)
            pollDiagnose(token, jobId, msgId)
        }
    }
```

`pollDiagnose` 整函数替换为（A2 总超时 + TIMED_OUT + 失败透出 error；改为 internal 供单测）：

```kotlin
    @VisibleForTesting
    internal suspend fun pollDiagnose(token: String, jobId: Int, msgId: String) {
        var delayMs = 2000L
        val startMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive &&
            System.currentTimeMillis() - startMs < diagPollTimeoutMs
        ) {
            delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(15000)
            val st = diagClient.fetchDiagStatus(token, jobId).getOrNull() ?: continue
            when (st.status) {
                "DIAGNOSED" -> {
                    val rc = st.rootCause.orEmpty()
                    upsertDiagMessage(
                        msgId,
                        context.getString(R.string.diag_root_cause, rc),
                        diagConfirm = DiagConfirmUi(jobId, pending = true),
                    )
                    return
                }
                "DIAGNOSE_FAILED" -> {
                    upsertDiagMessage(
                        msgId,
                        context.getString(R.string.diag_diagnose_failed, st.error ?: st.rootCause ?: ""),
                    )
                    activeDiags.remove(jobId)
                    return
                }
                "TIMED_OUT" -> {
                    upsertDiagMessage(msgId, context.getString(R.string.diag_job_timed_out))
                    activeDiags.remove(jobId)
                    return
                }
                // 其余（含未来新增的非终态）：继续轮询，由总超时兜底
            }
        }
        // A2：总超时 → 写气泡提示并退出协程，不再无限空转
        upsertDiagMessage(msgId, context.getString(R.string.diag_poll_timeout))
        activeDiags.remove(jobId)
    }
```

`confirmDiagnosis` 整函数替换为（A1 绑定 jobId + auto 专属文案）：

```kotlin
    /** 根因气泡内嵌按钮选定 mode（push/pr/auto）后调用；作用于按钮所在气泡的 job（A1）。 */
    fun confirmDiagnosis(jobId: Int, mode: String) {
        val ad = activeDiags[jobId] ?: return
        viewModelScope.launch {
            val result = diagClient.confirmFix(ad.token, ad.jobId, mode)
            if (result.isFailure) {
                upsertDiagMessage(ad.msgId, context.getString(R.string.diag_confirm_failed, result.exceptionOrNull()?.message ?: ""))
                return@launch
            }
            upsertDiagMessage(
                ad.msgId,
                context.getString(
                    when (mode) {
                        "pr" -> R.string.diag_fixing_pr
                        "auto" -> R.string.diag_fixing_auto
                        else -> R.string.diag_fixing_push
                    }
                ),
                diagConfirm = DiagConfirmUi(ad.jobId, pending = false),
            )
            pollFix(ad.token, ad.jobId, ad.msgId)
        }
    }
```

`pollFix` 整函数替换为：

```kotlin
    @VisibleForTesting
    internal suspend fun pollFix(token: String, jobId: Int, msgId: String) {
        var delayMs = 3000L
        val startMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive &&
            System.currentTimeMillis() - startMs < diagPollTimeoutMs
        ) {
            delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(20000)
            val st = diagClient.fetchDiagStatus(token, jobId).getOrNull() ?: continue
            when (st.status) {
                "FIXED", "FIXED_UNVERIFIED" -> {
                    val verified = context.getString(
                        if (st.status == "FIXED") R.string.diag_verified_passed else R.string.diag_verified_unverified,
                    )
                    val base = context.getString(R.string.diag_fixed, st.fixBranch ?: "-", verified)
                    val link = (st.compareUrl ?: st.fixBranch)?.let { "\n\n$it" } ?: ""
                    upsertDiagMessage(msgId, base + link)
                    activeDiags.remove(jobId)
                    return
                }
                "FIX_FAILED" -> {
                    upsertDiagMessage(msgId, context.getString(R.string.diag_fix_failed, st.error ?: st.rootCause ?: ""))
                    activeDiags.remove(jobId)
                    return
                }
                "TIMED_OUT" -> {
                    upsertDiagMessage(msgId, context.getString(R.string.diag_job_timed_out))
                    activeDiags.remove(jobId)
                    return
                }
            }
        }
        upsertDiagMessage(msgId, context.getString(R.string.diag_poll_timeout))
        activeDiags.remove(jobId)
    }
```

- [ ] **Step 4: ChatScreen 接线修复（A1）**

`ChatScreen.kt` 第 500 行改为（不再丢弃 jobId）：

```kotlin
                                    onDiagConfirm = { jobId, mode -> viewModel.confirmDiagnosis(jobId, mode) }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatViewModelDiagTest"`
Expected: 5 个用例全部 PASS。

- [ ] **Step 6: 全量 app 单测确认无回归后 Commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

```
fix(app): 诊断确认绑定气泡 jobId、轮询 30 分钟总超时、TIMED_OUT/error 透出（A1/A2）
```

---

## Task 12: app —— A3 崩溃栈链路（落盘 → 收集 → 上报成功删除）

**Files:**
- Create `app/src/main/java/com/mamba/picme/core/diag/CrashTraceStore.kt`
- Modify `app/src/main/java/com/mamba/picme/PoLangApplication.kt`（onCreate，~137-139）
- Modify `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（submitDiagnosis 两处）
- Test `app/src/test/java/com/mamba/picme/core/diag/CrashTraceStoreTest.kt`（新）、`app/src/test/java/com/mamba/picme/core/diag/DiagBundleCollectorTest.kt`（追加）

- [ ] **Step 1: 写失败测试**

新建 `app/src/test/java/com/mamba/picme/core/diag/CrashTraceStoreTest.kt`：

```kotlin
package com.mamba.picme.core.diag

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashTraceStoreTest {

    @Test
    fun `save then read returns the stack trace and delete clears it`() {
        val dir = Files.createTempDirectory("crash-test").toFile()
        CrashTraceStore.save(dir, RuntimeException("boom at GalleryScreen"))

        val trace = CrashTraceStore.read(dir)
        assertTrue("trace persisted: $trace", trace!!.contains("boom at GalleryScreen"))

        CrashTraceStore.delete(dir)
        assertNull(CrashTraceStore.read(dir))
    }

    @Test
    fun `read returns null when no crash file exists`() {
        val dir = Files.createTempDirectory("crash-test-empty").toFile()
        assertNull(CrashTraceStore.read(dir))
    }
}
```

`DiagBundleCollectorTest.kt` 末尾追加（崩溃栈进包并脱敏）：

```kotlin
    @Test
    fun `collect sanitizes provided crash trace`() {
        val bundle = DiagBundleCollector.collect(
            "1.0.29", "abc1234", "Pixel 8", "14",
            crashTrace = "at com.mamba.UserHandler for a@b.com",
        )
        assertTrue(bundle.crashTrace!!.contains("<email>"))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.diag.CrashTraceStoreTest"`
Expected: 编译 FAIL（`CrashTraceStore` 未定义）。

- [ ] **Step 3: 新建 CrashTraceStore**

新建 `app/src/main/java/com/mamba/picme/core/diag/CrashTraceStore.kt`：

```kotlin
package com.mamba.picme.core.diag

import android.content.Context
import java.io.File

/**
 * 崩溃栈落盘（A3）：全局 UncaughtExceptionHandler 把未处理异常栈写入
 * `filesDir/diag/last_crash.txt`；下次诊断上报时随包携带（补上主设计 §6.1 的 crashTrace），
 * 上报成功后删除。目录/文件操作全部 best-effort，绝不影响主流程与既有 handler。
 */
object CrashTraceStore {
    private const val DIR_NAME = "diag"
    private const val FILE_NAME = "last_crash.txt"

    /** 崩溃栈上报长度上限（诊断包是纯文本，控制体量）。 */
    private const val MAX_TRACE_LEN = 8000

    private fun file(dir: File): File = File(File(dir, DIR_NAME), FILE_NAME)

    /** 安装全局 handler（链式调用既有 handler，不吞异常）。在 Application.onCreate 尽早调用。 */
    fun install(context: Context) {
        val dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { save(dir, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 落盘崩溃栈（覆盖写；只保留最近一次）。 */
    fun save(dir: File, throwable: Throwable) {
        val f = file(dir)
        f.parentFile?.mkdirs()
        f.writeText(throwable.stackTraceToString())
    }

    /** 读取崩溃栈（无文件/读失败 → null；截断 ≤ [MAX_TRACE_LEN]）。 */
    fun read(dir: File): String? = runCatching {
        file(dir).takeIf { it.exists() }?.readText()?.take(MAX_TRACE_LEN)
    }.getOrNull()

    /** 删除落盘文件（上报成功后调用）。 */
    fun delete(dir: File) {
        runCatching { file(dir).delete() }
    }
}
```

- [ ] **Step 4: Application 安装 handler**

`PoLangApplication.kt` `onCreate()` 中 `super.onCreate()` 之后（SLF4J 设置之前，尽早覆盖所有后续初始化的崩溃）插入：

```kotlin
        // A3：崩溃栈落盘（随下次远程诊断包上报 crashTrace）
        CrashTraceStore.install(this)
```

import 区追加：

```kotlin
import com.mamba.picme.core.diag.CrashTraceStore
```

- [ ] **Step 5: ChatViewModel 接线（收集 + 成功后删除）**

`ChatViewModel.kt` import 区追加：

```kotlin
import com.mamba.picme.core.diag.CrashTraceStore
```

`submitDiagnosis` 中，`val bundle = DiagBundleCollector.collect(...)` 调用改为带 crashTrace：

```kotlin
            val crashTrace = CrashTraceStore.read(context.filesDir)
            val bundle = DiagBundleCollector.collect(
                appVersion = BuildConfig.VERSION_NAME,
                gitSha = BuildConfig.GIT_SHA,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                crashTrace = crashTrace,
            )
```

`reportDiagnosis` 成功（拿到 jobId）之后、`activeDiags[jobId] = ...` 之前插入：

```kotlin
            CrashTraceStore.delete(context.filesDir) // 上报成功 → 崩溃栈已随包送出，清除落盘文件
```

- [ ] **Step 6: 跑测试确认通过 + 编译**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.diag.*" && ./gradlew :app:assembleDebug`
Expected: 全部 PASS + BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```
feat(app): 崩溃栈落盘并随诊断包上报、成功后清除（A3）
```

---

## Task 13: app —— DiagPrompts（诊断 system prompt 常量 + `[DIAG_READY]` 摘要解析）

**Files:**
- Create `app/src/main/java/com/mamba/picme/features/chat/DiagPrompts.kt`
- Test `app/src/test/java/com/mamba/picme/features/chat/DiagPromptsTest.kt`（新）

- [ ] **Step 1: 写失败测试**

新建 `app/src/test/java/com/mamba/picme/features/chat/DiagPromptsTest.kt`：

```kotlin
package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagPromptsTest {

    @Test
    fun `reply without marker is not ready`() {
        val r = DiagPrompts.parseDiagReply("请问在哪个页面遇到的？")
        assertFalse(r.ready)
        assertEquals("请问在哪个页面遇到的？", r.displayText)
        assertNull(r.summary)
    }

    @Test
    fun `marker splits display text and summary`() {
        val r = DiagPrompts.parseDiagReply("信息够了，可以提交。\n[DIAG_READY]\n问题现象：打开相册崩溃\n复现步骤：必现")
        assertTrue(r.ready)
        assertEquals("信息够了，可以提交。", r.displayText)
        assertEquals("问题现象：打开相册崩溃\n复现步骤：必现", r.summary)
    }

    @Test
    fun `marker without summary degrades to manual submit with null summary`() {
        // 解析失败兜底：ready=true、summary=null，用户仍可手动提交（退化为现状）
        val r = DiagPrompts.parseDiagReply("可以提交了 [DIAG_READY]")
        assertTrue(r.ready)
        assertNull(r.summary)
        assertEquals("可以提交了", r.displayText)
    }

    @Test
    fun `empty display text falls back to summary`() {
        val r = DiagPrompts.parseDiagReply("[DIAG_READY]\n问题现象：崩溃")
        assertTrue(r.ready)
        assertEquals("问题现象：崩溃", r.displayText)
    }

    @Test
    fun `summary is truncated to the server limit`() {
        val r = DiagPrompts.parseDiagReply("[DIAG_READY]\n" + "x".repeat(5000))
        assertEquals(DiagPrompts.MAX_SUMMARY_LEN, r.summary!!.length)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.DiagPromptsTest"`
Expected: 编译 FAIL（`DiagPrompts` 未定义）。

- [ ] **Step 3: 新建 DiagPrompts**

新建 `app/src/main/java/com/mamba/picme/features/chat/DiagPrompts.kt`：

```kotlin
package com.mamba.picme.features.chat

/**
 * 诊断澄清对话的 prompt 与输出契约（spec §2.2）。
 * system prompt 为 app 内置常量；[READY_MARKER] 是客户端可解析的显式收敛信号
 * （显式优于隐式：LLM 只建议，「提交诊断」永远是用户手动动作）。
 */
object DiagPrompts {

    /** LLM 信息收敛后输出的显式标记：客户端据此渲染「提交诊断」按钮并提取摘要。 */
    const val READY_MARKER = "[DIAG_READY]"

    /** 摘要长度兜底（与 server /diag/report 上限一致）。 */
    const val MAX_SUMMARY_LEN = 4000

    /** 诊断对话 system prompt：角色设定 + 产品功能清单 + [READY_MARKER] 输出契约。 */
    val SYSTEM_PROMPT: String = """
        你是 PoLang（破浪相册）App 的诊断助手。用户遇到了 App 使用问题，你的目标是用最少的追问收集到足以定位问题的信息。

        【产品功能清单】（用于问出精准问题）
        - AI 对话（chat）：相册搜索、多轮追问、画图、图片编辑/优化、记忆
        - 相册：浏览、标签（TAG）自动生成与管理、人脸聚类/人物命名、搜索
        - 相机：拍照、实时美颜（磨皮/美白/瘦脸/大眼/唇色/滤镜）
        - 备份恢复：应用数据备份与恢复
        - 设置：账号登录、远程模型配置（官方/自配 Key）、语言切换

        【追问规则】
        - 每次最多问 1-2 个最关键的问题，不要一次盘问一大串。
        - 优先澄清：哪个页面/功能、具体操作步骤、是否必现、什么时候开始、有无报错提示。
        - 如果问题可以通过用户自助操作解决（改设置、清缓存、重新登录、已知问题规避），直接给出建议步骤并请用户验证，不要急着收集上报信息。
        - 用中文、口语化、简短；不要复述用户的话。

        【收敛输出契约】（严格遵守）
        当你判断信息已足够定位问题（或用户明确要求上报）时，在正常回复之后另起一行输出 $READY_MARKER 标记，标记之后按以下固定格式给出结构化摘要（不要在标记之前输出摘要）：
        $READY_MARKER
        问题现象：<一句话>
        复现步骤：<编号步骤>
        影响范围：<页面/功能，是否必现>
        用户已尝试的操作：<或"无">
    """.trimIndent()

    /** [parseDiagReply] 的解析结果。 */
    data class DiagReply(
        val ready: Boolean,       // 是否检测到 [DIAG_READY]
        val displayText: String,  // 气泡展示文本（标记前的内容；为空时兜底摘要/原文）
        val summary: String?,     // 结构化摘要（截断 ≤ [MAX_SUMMARY_LEN]；解析失败为 null → 退化为无摘要上报）
    )

    /**
     * 解析 LLM 回复中的 [READY_MARKER] 与结构化摘要。解析失败不阻断：
     * ready=true 但 summary=null 时用户仍可手动提交（summary 为空退化为现状）。
     */
    fun parseDiagReply(reply: String): DiagReply {
        val idx = reply.indexOf(READY_MARKER)
        if (idx < 0) return DiagReply(ready = false, displayText = reply, summary = null)
        val display = reply.substring(0, idx).trim()
        val raw = reply.substring(idx + READY_MARKER.length).trim()
        val summary = raw.takeIf { it.isNotBlank() }?.take(MAX_SUMMARY_LEN)
        return DiagReply(
            ready = true,
            displayText = display.ifBlank { summary ?: reply.trim() },
            summary = summary,
        )
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.DiagPromptsTest"`
Expected: 5 个用例全部 PASS。

- [ ] **Step 5: Commit**

```
feat(app): 诊断澄清对话 system prompt 与 [DIAG_READY] 摘要解析（§2.2）
```

---

## Task 14: app —— DiagChatSession（诊断流式对话封装）

**Files:**
- Create `app/src/main/java/com/mamba/picme/features/chat/DiagChatSession.kt`

说明：复用 agent-core 的 `OpenAiStreamingChatModel`（经 `RemoteModelFactory.createBuilder` 构建，与 `RemoteReActAgent` 同一套参数/认证约定：**普通**远程 LLM 对话、无 ReAct 工具循环）；历史保存在内存（diag 会话为新建独立会话，进程杀后重建即新对话，符合 §9 YAGNI「会话跨设备/进程恢复不做」）。无网络纯 JVM 单测无法覆盖本类，行为验证放到 Task 17 E2E。

- [ ] **Step 1: 新建 DiagChatSession**

新建 `app/src/main/java/com/mamba/picme/features/chat/DiagChatSession.kt`：

```kotlin
package com.mamba.picme.features.chat

import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessage
import com.mamba.data.message.SystemMessage
import com.mamba.data.message.UserMessage
import com.mamba.model.chat.StreamingChatModel
import com.mamba.model.chat.request.ChatRequest
import com.mamba.model.chat.response.ChatResponse
import com.mamba.model.chat.response.StreamingChatResponseHandler
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 诊断澄清对话会话（spec §2.1）：注入诊断 system prompt 的**普通**远程 LLM 多轮流式对话。
 * 不走 ReAct 工具循环（澄清只需对话）；历史在内存（会话级，进程杀后重开即新对话）。
 *
 * 复用现有流式通道：`RemoteModelFactory.createBuilder(...).buildStreaming()`
 * （与 RemoteReActAgent 同一套 temperature/maxTokens/DeepSeek thinking 禁用/网关认证约定）。
 */
class DiagChatSession(config: RemoteModelConfig) {

    private val model: StreamingChatModel =
        RemoteModelFactory.createBuilder(config, "diag").apply {
            // 官方模型走 PoLang Server 网关：X-App-Token 认证（同 RemoteReActAgent 约定）
            if (config.gatewayToken.isNotBlank()) customHeader("X-App-Token", config.gatewayToken)
        }.buildStreaming()

    private val history = mutableListOf<ChatMessage>(SystemMessage.from(DiagPrompts.SYSTEM_PROMPT))

    /**
     * 发送一轮用户消息，流式返回模型完整回复。
     * [onSnapshot] 携带本轮累计全文快照（非 delta），UI 直接整体替换气泡内容。
     */
    suspend fun chat(userText: String, onSnapshot: (String) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            history += UserMessage.from(userText)
            val accumulated = StringBuilder()
            suspendCancellableCoroutine { cont ->
                model.chat(
                    ChatRequest.builder().messages(history.toList()).build(),
                    object : StreamingChatResponseHandler {
                        override fun onPartialResponse(partialResponse: String) {
                            accumulated.append(partialResponse)
                            onSnapshot(accumulated.toString())
                        }

                        override fun onCompleteResponse(completeResponse: ChatResponse) {
                            val text = completeResponse.aiMessage()?.text() ?: accumulated.toString()
                            history += AiMessage.from(text)
                            cont.resume(Result.success(text))
                        }

                        override fun onError(error: Throwable) {
                            // 失败时把本轮 user 消息移出历史，避免污染后续对话
                            if (history.lastOrNull() is UserMessage) history.removeAt(history.size - 1)
                            cont.resume(Result.failure(error))
                        }
                    },
                )
            }
        }
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```
feat(app): DiagChatSession 诊断流式澄清对话封装（复用 agent-core 流式通道，无 ReAct）
```

---

## Task 15: app —— ChatViewModel 诊断会话模式（toggle 新建会话 + sendDiagMessage + [DIAG_READY] 气泡）

**Files:**
- Modify `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`（诊断段 ~207 后新增块；`loadMessages` ~649-663 回填 overrides）

- [ ] **Step 1: ChatViewModel 加诊断会话状态与入口**

`ChatViewModel.kt` 中，`// ── 远程诊断（chat 触发 → 云主机 Claude Code worker）──` 注释块内、`private val diagClient = dependencies.diagClient` 之后插入：

```kotlin
    // ── 诊断澄清对话（§2：diag toggle → 独立会话多轮 LLM 澄清 → [DIAG_READY] 手动提交）──

    private val _diagMode = MutableStateFlow(false)
    val diagMode: StateFlow<Boolean> = _diagMode.asStateFlow()

    private var diagChatSession: DiagChatSession? = null

    /** 诊断会话首条用户消息（作为上报 description；澄清摘要走 conversationSummary）。 */
    private var diagFirstUserText: String? = null

    /** msgId → 「提交诊断」按钮状态（内存态；Room 消息经 loadMessages 重放时按 id 回填）。 */
    private val diagSubmitOverrides = mutableMapOf<String, DiagSubmitUi>()

    /** 进入诊断模式：自动新建独立会话（上下文纯净，摘要提取干净）。 */
    fun enterDiagMode() {
        if (_diagMode.value) return
        _diagMode.value = true
        diagChatSession = DiagChatSession(effectiveRemoteConfig(selectedModel))
        diagFirstUserText = null
        diagSubmitOverrides.clear()
        newSession()
    }

    fun exitDiagMode() {
        _diagMode.value = false
        diagChatSession = null
    }

    /**
     * 诊断模式下的用户消息：走 [DiagChatSession] 多轮流式澄清对话（而非一次性上报）。
     * LLM 输出 [DIAG_READY] 时，气泡内嵌「提交诊断」按钮（提交永远是用户手动动作，§2.2）。
     */
    fun sendDiagMessage(text: String) {
        if (text.isBlank()) return
        val session = diagChatSession ?: DiagChatSession(effectiveRemoteConfig(selectedModel)).also {
            diagChatSession = it
            _diagMode.value = true
        }
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            try {
                ensureSessionExists(sessionId)
                chatMessageDao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        type = "user_text",
                        content = text,
                        modelUsed = null,
                    )
                )
                chatSessionDao.touchSession(sessionId)
                if (diagFirstUserText == null) diagFirstUserText = text

                _isProcessing.value = true
                val streamingId = "streaming_${System.currentTimeMillis()}"
                _streamingMessage.value = ChatMessageUi(
                    id = streamingId,
                    type = ChatMessageType.AGENT_TEXT,
                    content = STREAMING_THINKING_HINT,
                    modelUsed = currentModelLabel(),
                    isStreaming = true,
                    isThinking = true,
                )
                val result = session.chat(text) { snapshot ->
                    _streamingMessage.update { cur -> cur?.copy(content = snapshot, isThinking = false) }
                }
                _streamingMessage.value = null
                result.fold(
                    onSuccess = { reply ->
                        val parsed = DiagPrompts.parseDiagReply(reply)
                        val entity = ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            type = "agent_text",
                            content = parsed.displayText,
                            modelUsed = currentModelLabel(),
                        )
                        chatMessageDao.insertMessage(entity)
                        chatSessionDao.touchSession(sessionId)
                        if (parsed.ready) {
                            val submit = DiagSubmitUi(
                                description = diagFirstUserText ?: text,
                                summary = parsed.summary,
                            )
                            diagSubmitOverrides[entity.id] = submit
                            _messages.update { msgs ->
                                msgs.map { m -> if (m.id == entity.id) m.copy(diagSubmit = submit) else m }
                            }
                        }
                    },
                    onFailure = { e ->
                        insertAgentMessage(
                            sessionId,
                            context.getString(R.string.chat_inference_error, e.message ?: "unknown"),
                            "error",
                        )
                    },
                )
            } catch (e: Exception) {
                Logger.e(TAG, "sendDiagMessage failed", e)
                _streamingMessage.value = null
            } finally {
                _isProcessing.value = false
            }
        }
    }
```

- [ ] **Step 2: loadMessages 回填 diagSubmit overrides**

`loadMessages()` 中 `_messages.value = entities.map { it.toUiModel() }` 一行改为：

```kotlin
                        _messages.value = entities.map { e ->
                            val ui = e.toUiModel()
                            val submit = diagSubmitOverrides[ui.id]
                            if (submit != null) ui.copy(diagSubmit = submit) else ui
                        }
```

- [ ] **Step 3: 编译确认（UI 接线在 Task 16，本步仅保证编译）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（`DiagSubmitUi` 在 Task 16 加入 `ChatScreen.kt`——**必须先完成 Task 16 Step 1 的数据类定义再编译**，或两步一起改完再编译）。

注意：Task 15 与 Task 16 强耦合（`DiagSubmitUi` 定义在 ChatScreen.kt），实操时连续完成两个 Task 后统一编译、分开 commit 时先提 Task 16 Step 1（数据类）所在文件或合并为一个 commit：
```
feat(app): ChatViewModel 诊断会话模式与 [DIAG_READY] 提交气泡（§2）
```

---

## Task 16: app —— ChatScreen 提交按钮 UI + diag toggle 接线

**Files:**
- Modify `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`（~500, ~828, ~993-1012, ~1132, ~1203, ~1225, ~1846-1850）

- [ ] **Step 1: ChatMessageUi 加字段 + DiagSubmitUi 数据类**

`ChatScreen.kt` 中 `ChatMessageUi` 的 `diagConfirm` 字段之后（~1846）追加：

```kotlin
    /** 诊断根因气泡的内嵌确认动作；非空且 pending=true 时渲染 [推送]/[PR] 按钮。 */
    val diagConfirm: DiagConfirmUi? = null,
    /** 诊断澄清对话 [DIAG_READY] 气泡的内嵌提交动作；非空时渲染「提交诊断」按钮。 */
    val diagSubmit: DiagSubmitUi? = null,
```

`DiagConfirmUi` 定义之后（~1850）追加：

```kotlin
/** 诊断对话「提交诊断」按钮状态。description=诊断会话首条用户消息，summary=LLM 结构化摘要（可空，退化为现状）。 */
data class DiagSubmitUi(val description: String, val summary: String?)
```

- [ ] **Step 2: ChatMessageItem 加 onDiagSubmit 参数与按钮渲染**

`ChatMessageItem` 签名（~825-829）改为：

```kotlin
@Composable
private fun ChatMessageItem(
    message: ChatMessageUi,
    onImageClick: (ChatMessageUi) -> Unit = {},
    onDiagConfirm: (Int, String) -> Unit = { _, _ -> },
    onDiagSubmit: (DiagSubmitUi) -> Unit = {},
) {
```

`message.diagConfirm?.let { dc -> ... }` 块结束之后（~1012 闭合 `}` 后）插入：

```kotlin
            // 诊断澄清对话：[DIAG_READY] 摘要气泡内嵌「提交诊断」按钮（§2：提交永远是用户手动动作）
            message.diagSubmit?.let { ds ->
                Spacer(Modifier.height(10.dp))
                Button(onClick = { onDiagSubmit(ds) }) {
                    Text(stringResource(R.string.diag_submit_report))
                }
            }
```

- [ ] **Step 3: 消息列表接线（确认带 jobId + 提交带 summary）**

消息列表中 `ChatMessageItem(...)` 调用处（~486-501）的尾部两个回调改为：

```kotlin
                                    onDiagConfirm = { jobId, mode -> viewModel.confirmDiagnosis(jobId, mode) },
                                    onDiagSubmit = { ds -> viewModel.submitDiagnosis(ds.description, ds.summary) }
```

- [ ] **Step 4: diag toggle 改为 ViewModel 驱动**

`ChatInputArea` 附近（~1131-1132）：

```kotlin
    // 远程诊断模式 toggle：激活后发送键触发诊断（而非普通消息）
    var diagMode by remember { mutableStateOf(false) }
```

替换为：

```kotlin
    // 诊断澄清对话模式（§2）：状态在 ViewModel（进入时自动新建独立会话）
    val diagMode by viewModel.diagMode.collectAsState()
```

`onToggleDiag = { diagMode = !diagMode }`（~1203）改为：

```kotlin
                    onToggleDiag = { if (diagMode) viewModel.exitDiagMode() else viewModel.enterDiagMode() },
```

发送分支（~1225）`if (diagMode) viewModel.submitDiagnosis(text.trim()) else onSendMessage(text.trim())` 改为：

```kotlin
                                    if (diagMode) viewModel.sendDiagMessage(text.trim()) else onSendMessage(text.trim())
```

- [ ] **Step 5: 编译 + 全量单测**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（无新增失败）。

- [ ] **Step 6: Commit**

```
feat(app): ChatScreen 诊断 toggle 接 ViewModel 会话 + [DIAG_READY]「提交诊断」按钮（§2）
```

---

## Task 17: 最终验证（编译 + 三方测试 + E2E）

**Files:** 无新增（如验证发现问题，回到对应 Task 修复）。

- [ ] **Step 1: server 全量**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL（含全部 diag 新旧用例）。

- [ ] **Step 2: app 全量**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: worker smoke 全量**

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: 输出依次包含以下行并以 `SMOKE PASS` 结尾：

```
ok compare_url
ok gh_auth no-token fails
ok diagnose glue -> ...
ok diagnose three-field report
ok template injection safe
ok conversationSummary in diagnose prompt
ok suggestedFix in fix prompt
ok fix empty-change -> FIX_FAILED
SMOKE PASS
```

- [ ] **Step 4: 真机 E2E（对照 spec §10 验收清单逐条过）**

前置：server 部署到 `api.polang.net`（带新迁移）；worker 在云主机 `bash poll.sh` 常驻；真机登录账号。

1. 诊断对话：chat 页点「诊断」toggle → 自动进入新会话 → 输入「打开相册就闪退」→ LLM 流式追问（哪个页面/必现吗/什么时候开始）→ 回答 2-3 轮 → LLM 输出 `[DIAG_READY]` → 气泡出现「提交诊断」按钮。
2. 提交：点「提交诊断」→ server `diag_job` 行 `conversation_summary` 非空（admin 详情可查）→ 脱敏检查：server 端 description/summary 无邮箱/token/路径。
3. 根因：worker 诊断完成 → 气泡展示根因 + 三按钮（保守修复/修复待审/自动修复）。
4. 修复：选「自动修复」→ 气泡显示「修复中（自动合并）…」→ worker fix prompt 中含 suggestedFix（worker.log 可查）→ 自检过合并 main / 否则留分支 → 气泡展示结果。
5. 加固抽查：连续点两次诊断发起两个 job → 点旧气泡按钮确认的是旧 job；`/diag/report` 连发 6 次第 6 次 429；制造一次崩溃后重新提交诊断，诊断包含 crashTrace 且 `filesDir/diag/last_crash.txt` 已删除。
6. 超时抽查（可选，需等待或临时调小 sweeper 参数）：worker 停掉 → 提交诊断 → 30 分钟后 app 气泡提示超时；server 侧 1 小时后 job 转 TIMED_OUT（admin 可「激活」重跑）。

- [ ] **Step 5: 文档同步（[DOC-SYNC] 红线）**

- `docs/03-TECHNICAL-SPECS/` 中远程诊断相关文档（`2026-07-30-remote-diagnosis-design.md` 对应的实现记录/MNN_LLM_OPERATIONS 或 REMOTE_DIAGNOSIS 类文档，以实际文件为准）补充：多轮澄清对话流程、`[DIAG_READY]` 契约、sweeper 参数（5min 扫 / 15min 回收 / 1h 超时）、S3 护栏阈值（5 次/小时、2000/4000/200KB）、W2 空改动语义。
- `scripts/diag-worker/README.md` 的「文件」表补 `render_template`（lib.sh）与 smoke 新用例说明。
- 根 `AGENTS.md` 若引用诊断链路描述有变化（一次性上报 → 多轮澄清），同步一句。

- [ ] **Step 6: Commit（如有文档/修复变更）**

```
docs(diag): 同步多轮澄清对话与三方加固实现文档（§10 验收）
```

---

## 验收对照（spec §10 → 本计划覆盖点）

| 验收项 | 覆盖 |
|--------|------|
| 诊断模式新建会话 + system prompt + 多轮流式 + `[DIAG_READY]` 按钮 | Task 13/14/15/16 + E2E |
| conversationSummary 脱敏上报、server 存储、fix 阶段可用 | Task 1（列+契约）、Task 5/6（prompt 传递）、Task 10（脱敏截断） |
| 领而不回 15min 可重领；1h 转 TIMED_OUT；app 提示 | Task 3（sweeper）、Task 11（app 提示） |
| suggestedFix 出现在 fix prompt（smoke） | Task 6 |
| fix 无改动回 FIX_FAILED 不空推（smoke） | Task 7 |
| 含 `\|` / `&` 日志不破坏模板（smoke） | Task 5 |
| app 失败气泡展示 error；旧气泡按钮确认对应 job | Task 2（透出）、Task 10（解析）、Task 11（绑定+单测） |
| /diag/report 413 / 429 | Task 4 |
| 崩溃栈携带 + 上报成功删除 | Task 12 |
| 四语言文案；DiagController 删除 | Task 9、Task 8 |
| 编译通过（app + server + smoke） | Task 17 Step 1-3 |
| E2E 全链路 | Task 17 Step 4 |
