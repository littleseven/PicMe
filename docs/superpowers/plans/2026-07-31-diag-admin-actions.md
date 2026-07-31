# 诊断任务管理操作（删除 / 废弃 / 激活）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **环境提示**：本仓库当前环境子代理不可用（启动即报模型不存在），故采用 **inline 执行（superpowers:executing-plans）**。

**Goal:** 给 `/admin/diag` 诊断页增加「删除 / 废弃 / 激活」三项管理操作，把页面从只读升级为可管理。

**Architecture:** 复用现有 admin 写操作范式（`POST + adminGuard cookie 鉴权 + JS confirm + 302 回列表`）。新增 `DiagStatus.ARCHIVED` 软删除状态；`DiagService` 加三个写方法（admin 上帝视角，不带 owner 校验）+ 给 worker 回传加状态守卫；`AdminQueries.diagStats` 加 `archived` 计数；`AdminViews` 列表行 + 详情页加按状态显隐的操作按钮；`AdminRoutes` 加三条 POST 路由。无 DB migration（`status` 为 `varchar(24)` 存枚举名）。

**Tech Stack:** Kotlin、Ktor、Exposed（H2/SQLite 测试库）、kotlinx.html 服务端渲染、JUnit4。

**关联 spec：** `docs/superpowers/specs/2026-07-31-diag-admin-actions-design.md`

**前置（执行第一步前）：** 在主仓库 `main` 上新建 worktree（用户已选 worktree 隔离）。执行者用 `superpowers:using-git-worktrees` 建 worktree；若 worktree 基线不含本计划与 spec 的提交（`fc569149` 及本计划提交），需先把这些提交带进 worktree 分支（`git rebase`/`cherry-pick` onto 本地 main，或直接从本地 HEAD 建 worktree）。在 worktree 内用 `./gradlew -p server test` 验证。

---

## 文件结构

| 文件 | 责任 | 动作 |
|------|------|------|
| `server/src/main/kotlin/com/mamba/picme/server/diag/DiagStatus.kt` | 状态枚举 | 加 `ARCHIVED` |
| `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt` | 领域状态机 | 加 `deleteById/archive/activate`；`submitDiagnosis/submitFix` 加状态守卫；新增 import |
| `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt` | admin 只读聚合 | `DiagStats` 加 `archived`；`diagStats()` 加 `ARCHIVED` 分支 |
| `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt` | HTML 视图 | `diagStatusBadge` 加 `ARCHIVED`；列表加「操作」列；详情页加 `actions-bar`；统计卡加「已废弃」；新增 `diagActions` 片段 |
| `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt` | 路由 | 加 `POST /admin/diag/{id}/{delete,archive,activate}`；import `DiagService` |
| `server/src/test/.../diag/DiagServiceTest.kt` | 服务单测 | 加 deleteById/archive/activate/守卫测试 |
| `server/src/test/.../admin/AdminQueriesTest.kt` | 查询单测 | `diagStats` 含 `archived` |
| `server/src/test/.../admin/AdminViewsTest.kt` | 视图单测 | 操作列按状态显隐 / ARCHIVED 徽标 / actions-bar / 统计卡 |
| `server/src/test/.../admin/AdminRoutesTest.kt` | 路由单测 | 三路由 302 + DB 流转 + 无 cookie 跳登录 |
| `docs/superpowers/specs/2026-07-30-diag-admin-dashboard-design.md` | 主设计文档 | 状态机加 `ARCHIVED`；标注二期写操作已落地 |

**全局测试命令：** `./gradlew -p server test`
**单测类定位命令（示例）：** `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest"`

---

### Task 1: `DiagService.deleteById` —— 物理删除

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`（新增 import + 在 `object DiagService` 末尾加方法）
- Test: `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`（加测试）

- [ ] **Step 1: 写失败测试**

在 `DiagServiceTest.kt` 的最后一个 `@Test` 之后、类结束 `}` 之前追加：

```kotlin
@Test
fun `deleteById physically removes the job row`() {
    TestDb.init(DiagJobs)
    val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    runBlocking { DiagService.deleteById(id) }
    val count = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.count() }
    assertEquals(0L, count)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.deleteById*"`
Expected: 编译失败——`unresolved reference: deleteById`。

- [ ] **Step 3: 加 import**

在 `DiagService.kt` 顶部 import 区（`import org.jetbrains.exposed.sql.insert` 附近）加：

```kotlin
import org.jetbrains.exposed.sql.deleteWhere
```

- [ ] **Step 4: 加实现**

在 `DiagService.kt` 的 `object DiagService { ... }` 内、`submitFix` 方法之后（类结束 `}` 之前）加：

```kotlin
/** 管理后台「删除」：物理删除任务记录（不可恢复）。 */
suspend fun deleteById(id: Int) {
    newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        DiagJobs.deleteWhere { DiagJobs.id eq id }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.deleteById*"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt \
        server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt
git commit -m "feat(diag): DiagService.deleteById 物理删除诊断任务"
```

---

### Task 2: `DiagStatus.ARCHIVED` + `DiagService.archive` —— 废弃

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/diag/DiagStatus.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`

- [ ] **Step 1: 写失败测试**

在 `DiagServiceTest.kt` 末尾追加（用 `transaction` 直接种任意态，避免依赖完整流转）：

```kotlin
@Test
fun `archive moves any status to ARCHIVED`() {
    TestDb.init(DiagJobs)
    val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    // QUEUED 态直接废弃
    runBlocking { DiagService.archive(id) }
    var row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
    assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
    // 再种一个 FIXED 态验证任意态可废弃
    val id2 = runBlocking { DiagService.createJob("o2", null, "d2", "{}", "sha2") }
    transaction(Db.instance) {
        DiagJobs.update({ DiagJobs.id eq id2 }) {
            it[DiagJobs.status] = DiagStatus.FIXED.name
            it[DiagJobs.fixBranch] = "diag-fix/x"
        }
    }
    runBlocking { DiagService.archive(id2) }
    row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id2 }.single() }
    assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.archive*"`
Expected: 编译失败——`unresolved reference: ARCHIVED` / `archive`。

- [ ] **Step 3: 加枚举值**

把 `DiagStatus.kt` 的枚举体改为（在 `TIMED_OUT,` 后加一行 `ARCHIVED`）：

```kotlin
enum class DiagStatus {
    QUEUED,            // 已上报，待 worker 诊断
    DIAGNOSED,         // 已出根因，待用户确认
    FIX_REQUESTED,     // 用户已确认 + 选 mode，待 worker 修复
    FIXED,             // 修复完成且自检通过
    FIXED_UNVERIFIED,  // 修复完成但未跑/未通过测试
    DIAGNOSE_FAILED,   // 诊断失败
    FIX_FAILED,        // 修复失败
    TIMED_OUT,         // 超时
    ARCHIVED,          // 管理后台「废弃」：worker 不再领取，记录保留可激活
}
```

- [ ] **Step 4: 加实现**

在 `DiagService.kt` 的 `object DiagService { ... }` 内、`deleteById` 之前（或之后）加：

```kotlin
/** 管理后台「废弃」：标记 ARCHIVED，worker 不再领取；任意源态允许。 */
suspend fun archive(id: Int) {
    val now = Instant.now().toEpochMilli()
    newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        DiagJobs.update({ DiagJobs.id eq id }) {
            it[DiagJobs.status] = DiagStatus.ARCHIVED.name
            it[DiagJobs.updatedAt] = now
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.archive*"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/diag/DiagStatus.kt \
        server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt \
        server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt
git commit -m "feat(diag): 新增 ARCHIVED 状态 + DiagService.archive 废弃"
```

---

### Task 3: `DiagService.activate` —— 激活（重置回 QUEUED）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`

- [ ] **Step 1: 写失败测试**

在 `DiagServiceTest.kt` 末尾追加：

```kotlin
@Test
fun `activate resets ARCHIVED to QUEUED and clears produced fields but keeps createdAt`() {
    TestDb.init(DiagJobs)
    val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    val created = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single()[DiagJobs.createdAt] }
    // 造一个有完整产出的 ARCHIVED 行
    transaction(Db.instance) {
        DiagJobs.update({ DiagJobs.id eq id }) {
            it[DiagJobs.status] = DiagStatus.ARCHIVED.name
            it[DiagJobs.rootCause] = "old rc"
            it[DiagJobs.fixMode] = "push"
            it[DiagJobs.fixBranch] = "diag-fix/old"
            it[DiagJobs.compareUrl] = "https://x/compare"
            it[DiagJobs.workerLog] = "old log"
            it[DiagJobs.tested] = 1
            it[DiagJobs.claimedAt] = 1_700_000_000_000L
        }
    }
    val ok = runBlocking { DiagService.activate(id) }
    assertTrue(ok)
    val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
    assertEquals(DiagStatus.QUEUED.name, row[DiagJobs.status])
    assertNull(row[DiagJobs.rootCause])
    assertNull(row[DiagJobs.fixMode])
    assertNull(row[DiagJobs.fixBranch])
    assertNull(row[DiagJobs.compareUrl])
    assertNull(row[DiagJobs.workerLog])
    assertEquals(0, row[DiagJobs.tested])
    assertNull(row[DiagJobs.claimedAt])
    assertEquals(created, row[DiagJobs.createdAt]) // 创建时间保留不变
}

@Test
fun `activate rejects QUEUED and FIX_REQUESTED`() {
    TestDb.init(DiagJobs)
    val queued = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    assertFalse(runBlocking { DiagService.activate(queued) })
    // FIX_REQUESTED：走完整流程到该态
    val fixReq = runBlocking { DiagService.createJob("o2", null, "d2", "{}", "sha2") }
    runBlocking { DiagService.submitDiagnosis(fixReq, "rc", DiagStatus.DIAGNOSED, null) }
    runBlocking { DiagService.confirmFix(fixReq, "o2", "push") }
    assertFalse(runBlocking { DiagService.activate(fixReq) })
    // 状态未被改
    val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq fixReq }.single() }
    assertEquals(DiagStatus.FIX_REQUESTED.name, row[DiagJobs.status])
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.activate*"`
Expected: 编译失败——`unresolved reference: activate`。

- [ ] **Step 3: 加实现**

在 `DiagService.kt` 的 `object DiagService { ... }` 内加：

```kotlin
/**
 * 管理后台「激活」：把停摆的任务（ARCHIVED / 失败 / 超时 / 已修复 / 待确认 等）
 * 重置为 QUEUED 并清空已有产出，让 worker 从头重跑诊断。
 * 拒绝 QUEUED（本就在队列）与 FIX_REQUESTED（worker 正在修，避免 race）。返回是否转移成功。
 */
suspend fun activate(id: Int): Boolean {
    val now = Instant.now().toEpochMilli()
    return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val row = DiagJobs.selectAll().where { DiagJobs.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction false
        val current = DiagStatus.valueOf(row[DiagJobs.status])
        if (current == DiagStatus.QUEUED || current == DiagStatus.FIX_REQUESTED) {
            return@newSuspendedTransaction false
        }
        DiagJobs.update({ DiagJobs.id eq id }) {
            it[DiagJobs.status] = DiagStatus.QUEUED.name
            it[DiagJobs.rootCause] = null
            it[DiagJobs.fixMode] = null
            it[DiagJobs.fixBranch] = null
            it[DiagJobs.compareUrl] = null
            it[DiagJobs.workerLog] = null
            it[DiagJobs.tested] = 0
            it[DiagJobs.claimedAt] = null
            it[DiagJobs.updatedAt] = now
        }
        true
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.activate*"`
Expected: PASS（两个测试方法均通过）。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt \
        server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt
git commit -m "feat(diag): DiagService.activate 重置停摆任务回 QUEUED"
```

---

### Task 4: `submitDiagnosis` / `submitFix` 状态守卫

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt`（加 `and` import + 改两处 `update` 条件）
- Test: `server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt`

- [ ] **Step 1: 写失败测试**

在 `DiagServiceTest.kt` 末尾追加：

```kotlin
@Test
fun `submitDiagnosis is ignored after the job is archived`() {
    TestDb.init(DiagJobs)
    val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    runBlocking { DiagService.archive(id) } // 先废弃
    runBlocking { DiagService.submitDiagnosis(id, "late rc", DiagStatus.DIAGNOSED, null) }
    val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
    // 守卫挡住：仍是 ARCHIVED，未写入迟到回传
    assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
    assertNull(row[DiagJobs.rootCause])
}

@Test
fun `submitFix is ignored after the job is archived`() {
    TestDb.init(DiagJobs)
    val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    // 走到 FIX_REQUESTED 再废弃，模拟修复阶段迟到回传
    runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
    runBlocking { DiagService.confirmFix(id, "o", "push") }
    runBlocking { DiagService.archive(id) }
    runBlocking { DiagService.submitFix(id, DiagStatus.FIXED, "diag-fix/late", null, true, null) }
    val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
    assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
    assertNull(row[DiagJobs.fixBranch])
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.*archived*"`
Expected: 两个测试 FAIL——状态被覆盖成 `DIAGNOSED` / `FIXED`（守卫尚未加）。

- [ ] **Step 3: 加 import**

在 `DiagService.kt` import 区加：

```kotlin
import org.jetbrains.exposed.sql.and
```

- [ ] **Step 4: 给 `submitDiagnosis` 加守卫**

把 `submitDiagnosis` 内的 `update` 调用由：

```kotlin
DiagJobs.update({ DiagJobs.id eq id }) {
```

改为：

```kotlin
DiagJobs.update({ (DiagJobs.id eq id) and (DiagJobs.status eq DiagStatus.QUEUED.name) }) {
```

（即只把 `DiagJobs.id eq id` 替换为 `(DiagJobs.id eq id) and (DiagJobs.status eq DiagStatus.QUEUED.name)`，方法体其余不变。）

- [ ] **Step 5: 给 `submitFix` 加守卫**

把 `submitFix` 内的 `update` 调用由：

```kotlin
DiagJobs.update({ DiagJobs.id eq id }) {
```

改为：

```kotlin
DiagJobs.update({ (DiagJobs.id eq id) and (DiagJobs.status eq DiagStatus.FIX_REQUESTED.name) }) {
```

- [ ] **Step 6: 跑全量 DiagServiceTest 确认新测通过且未回归**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest"`
Expected: 全部 PASS（含既有 `submitDiagnosis moves QUEUED to DIAGNOSED` / `submitFix moves FIX_REQUESTED to FIXED` 等，证明守卫对正常流程透明）。

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt \
        server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt
git commit -m "fix(diag): worker 回传加状态守卫，废弃后迟到回传不再覆盖"
```

---

### Task 5: `AdminQueries.diagStats` 加 `archived` 计数

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`（`DiagStats` data class + `diagStats()`）
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`

- [ ] **Step 1: 写失败测试**

在 `AdminQueriesTest.kt` 的 `diag stats list detail and worker activity` 测试内，把第 4 行种子之后、`val stats = AdminQueries.diagStats()` 之前插入一行 ARCHIVED 种子；并在断言区追加 `archived` 断言。

即把：

```kotlin
diagJob(4, "DIAGNOSE_FAILED", "desc-fail", "sha4", createdAt = now - 4000, claimedAt = now - 3500, workerLog = "clone failed")

val stats = AdminQueries.diagStats()
assertEquals(4, stats.total)
```

改为：

```kotlin
diagJob(4, "DIAGNOSE_FAILED", "desc-fail", "sha4", createdAt = now - 4000, claimedAt = now - 3500, workerLog = "clone failed")
diagJob(5, "ARCHIVED", "desc-archived", "sha5", createdAt = now - 5000, claimedAt = now - 4500)

val stats = AdminQueries.diagStats()
assertEquals(5, stats.total)
```

并在该测试的断言末尾（`assertEquals(1, stats.failed)` 之后）追加：

```kotlin
assertEquals(1, stats.archived)
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.admin.AdminQueriesTest.diag*stats*"`
Expected: 编译失败——`unresolved reference: archived`。

- [ ] **Step 3: 给 `DiagStats` 加字段（带默认值，向后兼容现有构造点）**

把 `AdminQueries.kt` 的：

```kotlin
data class DiagStats(
    val total: Int,
    val queued: Int,
    val diagnosed: Int,
    val fixRequested: Int,
    val fixed: Int,
    val failed: Int,
)
```

改为（末尾加 `archived`）：

```kotlin
data class DiagStats(
    val total: Int,
    val queued: Int,
    val diagnosed: Int,
    val fixRequested: Int,
    val fixed: Int,
    val failed: Int,
    val archived: Int = 0,
)
```

- [ ] **Step 4: 给 `diagStats()` 加 ARCHIVED 分支**

把 `diagStats()` 内：

```kotlin
var total = 0; var queued = 0; var diagnosed = 0; var fixRequested = 0; var fixed = 0; var failed = 0
DiagJobs.selectAll().forEach { r ->
    total++
    when (r[DiagJobs.status]) {
        DiagStatus.QUEUED.name -> queued++
        DiagStatus.DIAGNOSED.name -> diagnosed++
        DiagStatus.FIX_REQUESTED.name -> fixRequested++
        DiagStatus.FIXED.name, DiagStatus.FIXED_UNVERIFIED.name -> fixed++
        DiagStatus.DIAGNOSE_FAILED.name, DiagStatus.FIX_FAILED.name, DiagStatus.TIMED_OUT.name -> failed++
    }
}
DiagStats(total, queued, diagnosed, fixRequested, fixed, failed)
```

改为：

```kotlin
var total = 0; var queued = 0; var diagnosed = 0; var fixRequested = 0; var fixed = 0; var failed = 0; var archived = 0
DiagJobs.selectAll().forEach { r ->
    total++
    when (r[DiagJobs.status]) {
        DiagStatus.QUEUED.name -> queued++
        DiagStatus.DIAGNOSED.name -> diagnosed++
        DiagStatus.FIX_REQUESTED.name -> fixRequested++
        DiagStatus.FIXED.name, DiagStatus.FIXED_UNVERIFIED.name -> fixed++
        DiagStatus.DIAGNOSE_FAILED.name, DiagStatus.FIX_FAILED.name, DiagStatus.TIMED_OUT.name -> failed++
        DiagStatus.ARCHIVED.name -> archived++
    }
}
DiagStats(total, queued, diagnosed, fixRequested, fixed, failed, archived)
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.admin.AdminQueriesTest.diag*stats*"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "feat(diag): diagStats 统计 ARCHIVED 已废弃计数"
```

---

### Task 6: `AdminViews` 操作按钮 + ARCHIVED 徽标 + 统计卡

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt`

- [ ] **Step 1: 写失败测试**

在 `AdminViewsTest.kt` 末尾（类结束 `}` 之前）追加三个测试：

```kotlin
@Test
fun `diag list page renders per-row action buttons depending on status`() {
    val stats = DiagStats(total = 3, queued = 1, diagnosed = 0, fixRequested = 0, fixed = 0, failed = 0, archived = 1)
    val rows = listOf(
        DiagListRow(1, "QUEUED", "q", "dev••••", "sha1234567890", null, null, false, false, 100L, 100L, null),
        DiagListRow(2, "ARCHIVED", "a", "dev••••", "sha1234567890", null, null, false, false, 100L, 100L, null),
        DiagListRow(3, "TIMED_OUT", "t", "dev••••", "sha1234567890", null, null, false, false, 100L, 100L, null),
    )
    val activity = DiagWorkerActivity(null, 0, null, DiagWorkerHealth.IDLE)
    val html = AdminViews.diagListPage(stats, rows, activity, now = 200_000L, autoSec = 0)

    // 表头有「操作」列
    assertTrue(html.contains("操作"))
    // QUEUED 行：可废弃、可删除，不可激活
    assertTrue(html.contains("/admin/diag/1/archive"))
    assertTrue(html.contains("/admin/diag/1/delete"))
    assertTrue(!html.contains("/admin/diag/1/activate"))
    // ARCHIVED 行：可激活、可删除，不可废弃
    assertTrue(html.contains("/admin/diag/2/activate"))
    assertTrue(html.contains("/admin/diag/2/delete"))
    assertTrue(!html.contains("/admin/diag/2/archive"))
    // TIMED_OUT 行：可废弃、可激活、可删除
    assertTrue(html.contains("/admin/diag/3/archive"))
    assertTrue(html.contains("/admin/diag/3/activate"))
    // ARCHIVED 徽标文案
    assertTrue(html.contains("已废弃"))
    // 统计卡含「已废弃」计数
    assertTrue(html.contains("已废弃"))
}

@Test
fun `diag detail page renders actions bar with archive activate delete`() {
    val d = DiagDetailRow(
        id = 7, status = "DIAGNOSE_FAILED", description = "搜索崩溃", deviceIdMasked = "dev••••",
        bundleJson = """{"logs":"x","gitSha":"sha7","appVersion":"1.0.26"}""", gitSha = "sha7",
        rootCause = null, fixMode = null, fixBranch = null, compareUrl = null,
        tested = false, workerLog = "err", createdAt = 100L, updatedAt = 120L, claimedAt = 110L,
    )
    val html = AdminViews.diagDetailPage(d)
    assertTrue(html.contains("/admin/diag/7/archive"))
    assertTrue(html.contains("/admin/diag/7/activate"))
    assertTrue(html.contains("/admin/diag/7/delete"))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.admin.AdminViewsTest.diag*action*"`
Expected: FAIL——html 不含 `/admin/diag/1/archive` 等（按钮尚未渲染）。

- [ ] **Step 3: 加 `diagActions` 片段**

在 `AdminViews.kt` 内、`private fun FlowContent.diagStatusBadge(...)` 方法之前（或之后均可，置于 diag 片段区），加一个新片段：

```kotlin
private fun FlowContent.diagActions(status: String, id: Int) {
    div("row-actions") {
        if (status != "ARCHIVED") {
            form(action = "/admin/diag/$id/archive", method = FormMethod.post, classes = "inline") {
                attributes["onsubmit"] = "return confirm('确定废弃该诊断任务？\\n\\nworker 将不再处理（仍保留记录，可稍后激活）。')"
                input(type = InputType.submit, classes = "btn-sm") { value = "废弃" }
            }
        }
        if (status != "QUEUED" && status != "FIX_REQUESTED") {
            form(action = "/admin/diag/$id/activate", method = FormMethod.post, classes = "inline") {
                attributes["onsubmit"] = "return confirm('确定重新激活？\\n\\n将清空已有根因/修复并重置为待诊断，重新入队。')"
                input(type = InputType.submit, classes = "btn-sm btn-go") { value = "激活" }
            }
        }
        form(action = "/admin/diag/$id/delete", method = FormMethod.post, classes = "inline") {
            attributes["onsubmit"] = "return confirm('确定删除该诊断任务？\\n\\n记录将被物理删除，不可恢复。')"
            input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
        }
    }
}
```

- [ ] **Step 4: `diagStatusBadge` 加 ARCHIVED**

在 `diagStatusBadge` 的 `when (status)` 内、`"TIMED_OUT" -> ...` 之后加一行：

```kotlin
"ARCHIVED" -> "已废弃" to "badge badge-deleted"
```

- [ ] **Step 5: 列表表头加「操作」列 + 每行加操作单元**

在 `diagListPage` 的列表表头 `tr { ... }` 内，把：

```kotlin
th { +"ID" }; th { +"状态" }; th { +"问题描述" }
th { +"设备 · gitSha" }; th { +"修复" }; th { +"创建" }; th { +"更新" }
```

改为：

```kotlin
th { +"ID" }; th { +"状态" }; th { +"问题描述" }
th { +"设备 · gitSha" }; th { +"修复" }; th { +"创建" }; th { +"更新" }
th(classes = "col-actions") { +"操作" }
```

并在 `rows.forEach { row -> tr { ... } }` 的最后一个 `td { +fmtTs(row.updatedAt) }` 之后、`tr` 闭合之前，加：

```kotlin
td { diagActions(row.status, row.id) }
```

- [ ] **Step 6: 详情页加 actions-bar**

在 `diagDetailPage` 内，把：

```kotlin
h1 {
    +"诊断任务 #${d.id}  "
    diagStatusBadge(d.status)
}
div("cards") {
```

改为（在 h1 与 cards 之间插入 actions-bar）：

```kotlin
h1 {
    +"诊断任务 #${d.id}  "
    diagStatusBadge(d.status)
}
div("actions-bar") {
    diagActions(d.status, d.id)
}
div("cards") {
```

- [ ] **Step 7: 状态分布卡加「已废弃」**

在 `diagListPage` 的状态分布卡内，把：

```kotlin
div("cards") {
    statCard("待诊断", stats.queued.toString())
    statCard("待确认", stats.diagnosed.toString())
    statCard("待修复", stats.fixRequested.toString())
    statCard("已修复", stats.fixed.toString())
    statCard("失败/超时", stats.failed.toString())
}
```

改为：

```kotlin
div("cards") {
    statCard("待诊断", stats.queued.toString())
    statCard("待确认", stats.diagnosed.toString())
    statCard("待修复", stats.fixRequested.toString())
    statCard("已修复", stats.fixed.toString())
    statCard("失败/超时", stats.failed.toString())
    statCard("已废弃", stats.archived.toString())
}
```

- [ ] **Step 8: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.admin.AdminViewsTest"`
Expected: 全部 PASS（含新测试与既有 diag 视图测试）。

- [ ] **Step 9: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt
git commit -m "feat(diag): 列表+详情加 删除/废弃/激活 操作按钮与 ARCHIVED 徽标"
```

---

### Task 7: `AdminRoutes` 三条 POST 路由

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`（加 import + 三条路由）
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt`（加 `workerLog` 参数到 `diagInsert` + 新测试）

- [ ] **Step 1: 写失败测试**

在 `AdminRoutesTest.kt` 的私有 helper `diagInsert(...)` 的参数列表末尾（`deviceId: String? = "dev-aaaa-bbbb-1234",` 之后）加一个参数：

```kotlin
workerLog: String? = null,
```

并在 `diagInsert` 的 `DiagJobs.insert { ... }` 块内、`it[DiagJobs.claimedAt] = claimedAt` 之后加：

```kotlin
it[DiagJobs.workerLog] = workerLog
```

然后在该文件最后一个 `@Test`（`diag list and detail pages ...`）之后、`private fun diagInsert` 之前，追加新测试：

```kotlin
@Test
fun `diag admin actions archive activate delete transition and redirect`() = testApplication {
    TestDb.init(DiagJobs)
    diagInsert(1, "QUEUED", "打开相册闪退", "sha1", 1_700_000_000_000L)
    diagInsert(
        2, "FIXED", "搜索无结果", "sha2", 1_700_000_001_000L,
        fixBranch = "diag-fix/2", rootCause = "NPE X.kt:9", tested = true, claimedAt = 1_700_000_000_500L,
    )
    application { routing { adminRoute(token, cos, balance) } }
    val c = createClient { followRedirects = false }

    // 废弃 job1（QUEUED → ARCHIVED）
    val arch = c.post("/admin/diag/1/archive") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
    assertEquals(HttpStatusCode.Found, arch.status)
    assertEquals("/admin/diag", arch.headers[HttpHeaders.Location])
    assertEquals(
        DiagStatus.ARCHIVED.name,
        transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq 1 }.single()[DiagJobs.status] },
    )

    // 激活 job2（FIXED → QUEUED，清空产出）
    val act = c.post("/admin/diag/2/activate") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
    assertEquals(HttpStatusCode.Found, act.status)
    val row2 = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq 2 }.single() }
    assertEquals(DiagStatus.QUEUED.name, row2[DiagJobs.status])
    assertNull(row2[DiagJobs.fixBranch])

    // 删除 job1（物理删除）
    val del = c.post("/admin/diag/1/delete") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
    assertEquals(HttpStatusCode.Found, del.status)
    assertEquals("/admin/diag", del.headers[HttpHeaders.Location])
    val gone = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq 1 }.count() }
    assertEquals(0L, gone)

    // 无 cookie → 跳登录
    val noauth = c.post("/admin/diag/2/archive")
    assertEquals(HttpStatusCode.Found, noauth.status)
    assertEquals("/admin/login", noauth.headers[HttpHeaders.Location])
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.admin.AdminRoutesTest.diag*admin*actions*"`
Expected: FAIL——`/admin/diag/1/archive` 返回 404 或非 Found（路由尚未注册）。

- [ ] **Step 3: 加 import**

在 `AdminRoutes.kt` import 区（其它 `com.mamba.picme.server.*` import 附近）加：

```kotlin
import com.mamba.picme.server.diag.DiagService
```

- [ ] **Step 4: 加三条路由**

在 `AdminRoutes.kt` 的 `get("/diag/{id}") { ... }` 路由块之后、`get("/settings") { ... }` 之前，插入：

```kotlin
// 诊断任务管理操作（删除 / 废弃 / 激活）：admin cookie 鉴权，302 回列表。
post("/diag/{id}/delete") {
    if (!call.adminGuard(adminToken)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id != null) DiagService.deleteById(id)
    call.respondRedirect("/admin/diag")
}

post("/diag/{id}/archive") {
    if (!call.adminGuard(adminToken)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id != null) DiagService.archive(id)
    call.respondRedirect("/admin/diag")
}

post("/diag/{id}/activate") {
    if (!call.adminGuard(adminToken)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id != null) DiagService.activate(id)
    call.respondRedirect("/admin/diag")
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.admin.AdminRoutesTest"`
Expected: 全部 PASS（含既有 `diag list and detail pages ...`）。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt \
        server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt
git commit -m "feat(diag): /admin/diag 新增 删除/废弃/激活 三条管理路由"
```

---

### Task 8: 文档同步 + 全量验证

**Files:**
- Modify: `docs/superpowers/specs/2026-07-30-diag-admin-dashboard-design.md`

- [ ] **Step 1: 更新主设计文档状态机**

在 `2026-07-30-diag-admin-dashboard-design.md` 第 4 节的状态枚举行：

```
`status` ∈ `DiagStatus`：`QUEUED / DIAGNOSED / FIX_REQUESTED / FIXED / FIXED_UNVERIFIED / DIAGNOSE_FAILED / FIX_FAILED / TIMED_OUT`。
```

末尾追加 `ARCHIVED`：

```
`status` ∈ `DiagStatus`：`QUEUED / DIAGNOSED / FIX_REQUESTED / FIXED / FIXED_UNVERIFIED / DIAGNOSE_FAILED / FIX_FAILED / TIMED_OUT / ARCHIVED`。
```

- [ ] **Step 2: 更新「MVP 只读」决策，标注二期已落地**

把第 2 节决策 4：

```
4. **MVP 只读**：admin 是观测者，不做重试/取消等写操作（留二期），降低风险。
```

改为：

```
4. **只读 → 可管理（二期已于 2026-07-31 落地）**：初期 admin 仅观测；二期已新增「删除 / 废弃 / 激活」管理操作，详见 `docs/superpowers/specs/2026-07-31-diag-admin-actions-design.md`（新增 `ARCHIVED` 状态、`DiagService` 三个写方法、worker 回传状态守卫、列表行 + 详情页操作按钮）。
```

- [ ] **Step 3: 全量编译 + 测试**

Run: `./gradlew -p server test`
Expected: BUILD SUCCESSFUL，全部测试通过，无新增编译错误。

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-07-30-diag-admin-dashboard-design.md
git commit -m "docs(server): 诊断可视化页状态机补 ARCHIVED、标注管理操作二期落地"
```

---

## 自审（writing-plans self-review）

**1. Spec 覆盖**（对照 `2026-07-31-diag-admin-actions-design.md`）：
- 决策 1（激活全场景重置回 QUEUED）→ Task 3；拒绝 QUEUED/FIX_REQUESTED → Task 3 测试覆盖。✓
- 决策 2（ARCHIVED 保留可见+徽标+计数）→ Task 2 枚举、Task 5 计数、Task 6 徽标/统计卡。✓
- 决策 3（列表+详情双入口）→ Task 6 两处 `diagActions` 调用。✓
- 决策 4（worker 回传守卫）→ Task 4。✓
- 决策 5（保留 createdAt）→ Task 3 测试断言 `createdAt` 不变。✓
- 第 5 节测试矩阵：DiagServiceTest / AdminRoutesTest / AdminViewsTest / AdminQueriesTest 全部有对应 task。✓
- 第 6 节文档同步 → Task 8。✓

**2. Placeholder 扫描**：无 TBD/TODO；每个代码步骤含完整可编译代码。✓

**3. 类型一致性**：
- `deleteById(id: Int)` / `archive(id: Int)` 返回 `Unit`，路由忽略返回值；`activate(id: Int): Boolean`，测试用 `assertTrue/assertFalse`。✓
- `DiagStats.archived: Int = 0`，构造点（AdminViewsTest 既有两处）因默认值兼容。✓
- `diagActions(status: String, id: Int)` 定义（Task 6 Step 3）与列表 `diagActions(row.status, row.id)`、详情 `diagActions(d.status, d.id)` 调用签名一致。✓
- `diagInsert` 加 `workerLog` 参数（Task 7 Step 1），既有调用点（全用默认值）不受影响。✓
