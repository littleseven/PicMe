# 未注册设备管理后台页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 server 管理后台新增 `/admin/devices` 页面,按 device 维度展示 `anonymous_device` 表中的未注册访客设备,支持单条删除,并在用户页与设备页顶部加共享二级 Tab。

**Architecture:** 纯服务端 SSR 改动。复用 `anonymous_device` 表(不改结构、不迁移)。按「领域服务 → 查询层 → 视图层 → 路由装配 → 文档 → 发布」分 6 个 Task,每个 Task 自带 TDD 单测、可独立 commit。`adminRoute` 用默认参数 `guestLlmQuota: Int = 100` 避免破坏现有测试调用。发布走 `server/deploy.sh`(构建 → rsync → ssh 蓝绿切换 + healthz + 自动回滚)。

**Tech Stack:** Kotlin 2.0.21 + Ktor 3.0.3 + Exposed 0.55.0 + SQLite + kotlinx.html(SSR)+ JUnit4。

**Spec:** `docs/superpowers/specs/2026-07-25-unregistered-device-admin-page-design.md`

**关键既约事实(已核对源码,实现时直接用):**
- `anonymous_device` 表字段:`id / device_id / llm_calls_used / created_at / last_seen_at`(见 `db/Tables.kt:81-92`),已纳入生产 `Migrations.run` 的 `SchemaUtils.create`(`db/Migrations.kt:18`)。
- `GuestService`(`auth/GuestService.kt`)已有 `deleteByDeviceId(deviceId)`,本计划新增 `deleteById(id)`。
- `AdminQueries`(`admin/AdminQueries.kt`)是 `object`,查询方法形如 `suspend fun xxx() = newSuspendedTransaction(Dispatchers.IO, Db.instance) { ... }`;掩码私有函数 `maskToken` 在 `:260`。
- `AdminViews`(`admin/AdminViews.kt`)用 `createHTML().html { adminHead(...); body { navBar(); ... } }`;`navBar()` 在 `:848`;`adminHead` 内联 CSS 在 `:694`;`fmtTs(ms)` 在 `:944`;复制 JS 模式见 `usersPage` 的 `tokCopy`(`:147`)。
- `adminRoute(adminToken, cosService)`(`admin/AdminRoutes.kt:38`),在 `Application.kt:140` 挂载;测试里调用形如 `adminRoute(token, cos)`(`AdminRoutesTest.kt:63`)。
- 额度全局上限:`config.guestLlmQuota`(`config/AppConfig.kt:16`,env `GUEST_LLM_QUOTA` 默认 100)。
- 测试基建:`TestDb.init(vararg tables)`(`util/TestDb.kt`),`AdminQueriesTest` 用 `runBlocking` + `newSuspendedTransaction` seed;`AdminRoutesTest` 用 `testApplication` + `adminRoute(...)` + `cookie(...)`;`AdminViewsTest` 直接调 `AdminViews.xxxPage(...)` 断言 `html.contains(...)`。
- 全量测试:`./gradlew -p server test`(本仓库根目录跑)。
- 发布:`./server/deploy.sh`(默认 `DEPLOY_HOST=ubuntu@43.161.201.142`,内部 `clean installDist` → rsync → `ssh bash ~/deploy-switch.sh` 蓝绿)。

---

## Task 1: GuestService.deleteById(按数据库 id 删除访客)

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt`(在 `deleteByDeviceId` 后追加)
- Test: `server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt`(追加一个 `@Test`)

- [ ] **Step 1: 写失败测试**

在 `GuestServiceTest.kt` 末尾(`class GuestServiceTest { ... }` 内最后一个 `@Test` 之后)追加:

```kotlin
    @Test
    fun `deleteById removes the row and ignores unknown id`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.checkAndIncrementQuota("dev-x", limit) // 插入 id=1, used=1
        GuestService.deleteById(1)
        assertEquals(0L, transaction { AnonymousDevices.selectAll().count() })
        // unknown id 无副作用、不抛异常
        GuestService.deleteById(999)
        assertEquals(0L, transaction { AnonymousDevices.selectAll().count() })
    }
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.auth.GuestServiceTest.deleteById*'
```
Expected: 编译失败 / `Unresolved reference: deleteById`。

- [ ] **Step 3: 实现 deleteById**

在 `GuestService.kt` 的 `deleteByDeviceId` 函数后追加:

```kotlin
    /** 按 database id 删除访客记录（管理后台用;与 [deleteByDeviceId] 并列）。 */
    suspend fun deleteById(id: Int) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.deleteWhere { with(SqlExpressionBuilder) { AnonymousDevices.id eq id } }
        }
    }
```

- [ ] **Step 4: 跑测试,预期 PASS**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.auth.GuestServiceTest'
```
Expected: PASS(含新增用例与原有 5 个用例)。

- [ ] **Step 5: Commit**

```bash
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android add server/src/main/kotlin/com/mamba/picme/server/auth/GuestService.kt server/src/test/kotlin/com/mamba/picme/server/auth/GuestServiceTest.kt
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android commit -m "feat(server): GuestService.deleteById 按主键删除访客记录"
```

---

## Task 2: AdminQueries — DeviceRow + devicesList + deviceRawId + maskDeviceId

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`(DTO 区加 `DeviceRow`;`AdminQueries` 内加 3 个方法 + 1 个私有掩码)
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`(追加 2 个 `@Test` + 1 个 seed 辅助)

- [ ] **Step 1: 写失败测试**

1a. 在 `AdminQueriesTest.kt` 顶部 import 段追加(其余 import 已存在):

```kotlin
import com.mamba.picme.server.db.AnonymousDevices
```

1b. 在 `class AdminQueriesTest { ... }` 内(最后一个 `@Test` 之后、`private suspend fun account(...)` 之前)追加两个测试:

```kotlin
    @Test
    fun `devicesList orders by lastSeenAt desc masks deviceId and limits`() = runBlocking {
        TestDb.init(AnonymousDevices)
        device(1, "abcdef1234567890", 1, 1_000L, 1_000L)
        device(2, "zzzzzz0000001111", 5, 2_000L, 5_000L)
        val rows = AdminQueries.devicesList(100)
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].id)              // lastSeenAt 5000 在前
        assertEquals(1, rows[1].id)
        assertEquals("abcdef••••7890", rows.first { it.id == 1 }.deviceIdMasked)
        assertEquals(5, rows[0].llmCallsUsed)
        assertEquals(1_000L, rows[1].createdAt)
        // limit 截断
        assertEquals(1, AdminQueries.devicesList(1).size)
    }

    @Test
    fun `deviceRawId hits and misses`() = runBlocking {
        TestDb.init(AnonymousDevices)
        device(1, "dev-full-id-xyz", 1, 1L, 1L)
        assertEquals("dev-full-id-xyz", AdminQueries.deviceRawId(1))
        assertNull(AdminQueries.deviceRawId(999))
    }
```

1c. 在文件末尾(`private suspend fun logRow(...)` 之后)追加 seed 辅助:

```kotlin
    private suspend fun device(id: Int, deviceId: String, used: Int, createdAt: Long, lastSeenAt: Long) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = id
                it[AnonymousDevices.deviceId] = deviceId
                it[AnonymousDevices.llmCallsUsed] = used
                it[AnonymousDevices.createdAt] = createdAt
                it[AnonymousDevices.lastSeenAt] = lastSeenAt
            }
        }
    }
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest.devicesList*' --tests 'com.mamba.picme.server.admin.AdminQueriesTest.deviceRawId*'
```
Expected: `Unresolved reference: DeviceRow / devicesList / deviceRawId`。

- [ ] **Step 3: 实现**

3a. 在 `AdminQueries.kt` 顶部 import 段追加:

```kotlin
import com.mamba.picme.server.db.AnonymousDevices
```

> 注:`.limit(n)` 是 Exposed `SizedIterable` 接口方法（`recentCalls` 一直这么用），无需额外 import。

3b. 在 DTO 区(如 `data class CallRow(...)` 之后)追加:

```kotlin
data class DeviceRow(
    val id: Int,
    val deviceIdMasked: String,
    val llmCallsUsed: Int,
    val createdAt: Long,
    val lastSeenAt: Long,
)
```

3c. 在 `object AdminQueries { ... }` 内(如 `recentCalls` 之后)追加:

```kotlin
    suspend fun devicesList(limit: Int = 1000): List<DeviceRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.selectAll()
                .orderBy(AnonymousDevices.lastSeenAt to SortOrder.DESC)
                .limit(limit)
                .map { r ->
                    DeviceRow(
                        id = r[AnonymousDevices.id],
                        deviceIdMasked = maskDeviceId(r[AnonymousDevices.deviceId]),
                        llmCallsUsed = r[AnonymousDevices.llmCallsUsed],
                        createdAt = r[AnonymousDevices.createdAt],
                        lastSeenAt = r[AnonymousDevices.lastSeenAt],
                    )
                }
        }

    suspend fun deviceRawId(id: Int): String? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AnonymousDevices.selectAll().where { AnonymousDevices.id eq id }
            .firstOrNull()?.get(AnonymousDevices.deviceId)
    }
```

3d. 在 `object AdminQueries { ... }` 内、`maskToken` 旁追加私有掩码函数:

```kotlin
    /** device_id 掩码:前 6 + •••• + 后 4;长度 ≤ 10 时只露后 4。与 [maskToken] 同形。 */
    private fun maskDeviceId(deviceId: String): String = when {
        deviceId.length <= 10 -> "••••" + deviceId.takeLast(4)
        else -> deviceId.take(6) + "••••" + deviceId.takeLast(4)
    }
```

- [ ] **Step 4: 跑测试,预期 PASS**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest'
```
Expected: PASS(含原有用例与两个新用例)。

- [ ] **Step 5: Commit**

```bash
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android commit -m "feat(server): AdminQueries 设备列表/原始 id 查询 + DeviceRow"
```

---

## Task 3: AdminViews — userTabs 片段 + devicesPage + 在 usersPage 挂 Tab + CSS

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`(加 `userTabs`、`devicesPage`;改 `usersPage` 签名与函数体;`adminHead` 的 `<style>` 追加子 Tab 样式)
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt`(追加 1 个 `@Test`;更新现有 `users page` 测试调用)

- [ ] **Step 1: 写失败测试**

1a. 在 `AdminViewsTest.kt` 的 `users page lists emails...` 测试里,把 `AdminViews.usersPage(rows)` 改为 `AdminViews.usersPage(rows, devicesCount = 0L)`,并在该测试末尾追加一行断言:

```kotlin
        assertTrue(html.contains("未注册设备"))   // 二级 Tab 出现
```

(即把第 24 行 `val html = AdminViews.usersPage(rows)` 改为 `val html = AdminViews.usersPage(rows, devicesCount = 0L)`。)

1b. 在 `class AdminViewsTest { ... }` 末尾追加新测试:

```kotlin
    @Test
    fun `devices page lists masked ids quota and delete action`() {
        val rows = listOf(
            DeviceRow(1, "abcdef••••7890", 5, 1_700_000_000_000L, 1_700_000_001_000L),
            DeviceRow(2, "zzzzzz••••1111", 100, 1_700_000_000_000L, 1_700_000_002_000L),
        )
        val html = AdminViews.devicesPage(rows, usersCount = 3L, guestLimit = 100)
        assertTrue(html.contains("未注册设备"))
        assertTrue(html.contains("注册用户 (3)"))           // 二级 Tab 计数
        assertTrue(html.contains("未注册设备 (2)"))          // 二级 Tab 计数
        assertTrue(html.contains("abcdef••••7890"))
        assertTrue(html.contains("devCopy(1, this)"))
        assertTrue(html.contains("/admin/devices/1/delete"))
        assertTrue(html.contains("5 / 100"))
        assertTrue(html.contains("100 / 100"))              // 超额行
        assertTrue(html.contains("btn-danger"))             // 删除按钮
    }
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminViewsTest'
```
Expected:`usersPage(rows, devicesCount = ...)` 参数不匹配;`Unresolved reference: devicesPage`。

- [ ] **Step 3: 实现**

3a. 在 `AdminViews.kt` 的 `usersPage` 函数签名改为:

```kotlin
    fun usersPage(rows: List<UserRow>, devicesCount: Long): String = createHTML().html {
```

3b. 在 `usersPage` 函数体内,把 `navBar()` 之后、`h1 { +"用户（${rows.size}）" }` 之前,插入一行:

```kotlin
            userTabs(rows.size.toLong(), devicesCount, "/admin/users")
```

3c. 在 `usersPage` 函数之后、`userDetailPage` 之前,新增 `devicesPage`:

```kotlin
    fun devicesPage(rows: List<DeviceRow>, usersCount: Long, guestLimit: Int): String = createHTML().html {
        adminHead("未注册设备 · PoLang 管理后台")
        body {
            navBar()
            userTabs(usersCount, rows.size.toLong(), "/admin/devices")
            h1 { +"未注册设备（${rows.size}）" }
            p("meta") { +"按最后活跃倒序;数据量大时仅展示最近 1000 条" }
            if (rows.isEmpty()) {
                div("card apk-empty") {
                    div("apk-empty-text") { +"暂无未注册设备" }
                }
            } else {
                table {
                    tr {
                        th { +"ID" }
                        th { +"Device ID" }
                        th { +"额度（已用 / 上限）" }
                        th { +"首次出现" }
                        th { +"最后活跃" }
                        th(classes = "col-actions") { +"操作" }
                    }
                    rows.forEach { d ->
                        tr {
                            td { +d.id.toString() }
                            td {
                                span("tok") { +d.deviceIdMasked }
                                +" "
                                button(type = ButtonType.button, classes = "btn-sm tok-copy") {
                                    attributes["onclick"] = "devCopy(${d.id}, this)"
                                    +"复制"
                                }
                            }
                            td {
                                val text = "${d.llmCallsUsed} / $guestLimit"
                                if (d.llmCallsUsed >= guestLimit) span("err") { +text } else +text
                            }
                            td { +fmtTs(d.createdAt) }
                            td { +fmtTs(d.lastSeenAt) }
                            td {
                                form(action = "/admin/devices/${d.id}/delete", method = FormMethod.post, classes = "inline") {
                                    attributes["onsubmit"] = "return confirm('确定删除该设备记录？\\n\\n将清除其访客用量计数,操作不可恢复。')"
                                    input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
                                }
                            }
                        }
                    }
                }
                script {
                    unsafe {
                        raw(
                            """function devCopy(id,btn){fetch('/admin/devices/'+id+'/raw',{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(d){return navigator.clipboard.writeText(d.device_id)}).then(function(){var o=btn.textContent;btn.textContent='✓';setTimeout(function(){btn.textContent=o},1200)}).catch(function(){btn.textContent='失败';setTimeout(function(){btn.textContent='复制'},1200)})}""",
                        )
                    }
                }
            }
        }
    }
```

3d. 在 `navBar()` 片段(约 `:848`)之后新增 `userTabs` 片段:

```kotlin
    private fun FlowContent.userTabs(usersCount: Long, devicesCount: Long, currentPath: String) {
        div("subtabs") {
            a("/admin/users", classes = if (currentPath == "/admin/users") "subtab active" else "subtab") {
                +"注册用户 ($usersCount)"
            }
            a("/admin/devices", classes = if (currentPath == "/admin/devices") "subtab active" else "subtab") {
                +"未注册设备 ($devicesCount)"
            }
        }
    }
```

3e. 在 `adminHead` 的 `<style>`(`unsafe { raw(""" ... """) }`)CSS 末尾、`@media` 规则之前,追加子 Tab 样式(整段拼到现有 CSS 字符串里,新增这几行):

```css
.subtabs{display:flex;gap:4px;max-width:1200px;margin:16px auto 0;padding:0 24px;border-bottom:1px solid #e5e5e5}
.subtab{color:#666;text-decoration:none;padding:10px 16px;font-size:14px;border-bottom:2px solid transparent;margin-bottom:-1px;transition:all .2s}
.subtab:hover{color:#006eff}
.subtab.active{color:#006eff;border-bottom-color:#006eff;font-weight:500}
```

> 落点提示:`adminHead` 里 CSS 是一个 `trimIndent()` 字符串,把上面 4 行直接粘到 `.btn-upload:disabled{...}` 那行之后、`@media (max-width:640px){` 之前即可。

- [ ] **Step 4: 跑测试,预期 PASS**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminViewsTest'
```
Expected: PASS(含更新后的 `users page` 用例与新 `devices page` 用例)。

- [ ] **Step 5: Commit**

```bash
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android add server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android commit -m "feat(server): AdminViews 未注册设备页 + 用户/设备二级 Tab"
```

---

## Task 4: AdminRoutes — 挂载 3 个设备路由 + adminRoute 默认参数 + usersCount/devicesCount + Application.kt 传参

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`(加 `usersCount` / `devicesCount`)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`(签名加默认参数;改 `/users` handler;加 `/devices`、`/devices/{id}/raw`、`/devices/{id}/delete`)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/Application.kt:140`(传 `config.guestLlmQuota`)
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt`(import + 追加 1 个端到端 `@Test`)

- [ ] **Step 1: 写失败测试**

1a. 在 `AdminRoutesTest.kt` 顶部 import 段追加:

```kotlin
import com.mamba.picme.server.db.AnonymousDevices
```

1b. 在 `class AdminRoutesTest { ... }` 末尾(最后一个 `@Test` 之后)追加端到端测试:

```kotlin
    @Test
    fun `devices page lists anonymous devices raw and delete by id`() = testApplication {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1
                it[Accounts.email] = "a@x.com"
                it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            AnonymousDevices.insert {
                it[AnonymousDevices.id] = 5
                it[AnonymousDevices.deviceId] = "abcdef1234567890"
                it[AnonymousDevices.llmCallsUsed] = 7
                it[AnonymousDevices.createdAt] = 1_700_000_000_000L
                it[AnonymousDevices.lastSeenAt] = 1_700_000_001_000L
            }
        }
        application { routing { adminRoute(token, cos, 100) } }
        val c = createClient { followRedirects = false }

        // 列表
        val list = c.get("/admin/devices") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, list.status)
        val html = list.bodyAsText()
        assertTrue(html.contains("未注册设备"))
        assertTrue(html.contains("注册用户 (1)"))        // 二级 Tab 计数
        assertTrue(html.contains("未注册设备 (1)"))       // 二级 Tab 计数
        assertTrue(html.contains("abcdef••••7890"))       // 掩码
        assertTrue(html.contains("7 / 100"))              // 额度
        assertTrue(html.contains("/admin/devices/5/delete"))

        // raw 返回完整 device_id（cookie 鉴权）
        val raw = c.get("/admin/devices/5/raw") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, raw.status)
        assertTrue(raw.bodyAsText().contains("\"device_id\":\"abcdef1234567890\""))

        // 未知 id → 404
        val nf = c.get("/admin/devices/999/raw") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.NotFound, nf.status)

        // 删除 → 重定向回列表
        val del = c.post("/admin/devices/5/delete") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.Found, del.status)
        assertEquals("/admin/devices", del.headers[HttpHeaders.Location])
    }
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminRoutesTest.devices*'
```
Expected: 编译失败——`adminRoute(token, cos, 100)` 的三参签名要等 Step 3c 才加。若已过编译,则 GET `/admin/devices` 返回 404、断言 200 失败。

- [ ] **Step 3: 实现**

3a. 在 `AdminQueries.kt` 的 `object AdminQueries { ... }` 内(如 `devicesList` 旁)追加两个计数:

```kotlin
    suspend fun usersCount(): Long = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        Accounts.selectAll().count()
    }

    suspend fun devicesCount(): Long = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AnonymousDevices.selectAll().count()
    }
```

3b. 在 `AdminRoutes.kt` 顶部 import 段追加:

```kotlin
import com.mamba.picme.server.auth.GuestService
```

3c. 把 `adminRoute` 函数签名(`:38`)改为(新增带默认值的第三参):

```kotlin
fun Route.adminRoute(adminToken: String, cosService: CosService, guestLlmQuota: Int = 100) {
```

3d. 把现有 `get("/users") { ... }`(`:96-99`)替换为(多查 `devicesCount` 传入):

```kotlin
        get("/users") {
            if (!call.adminGuard(adminToken)) return@get
            val rows = AdminQueries.usersList()
            call.respondText(
                AdminViews.usersPage(rows, AdminQueries.devicesCount()),
                ContentType.Text.Html,
            )
        }
```

3e. 在 `get("/users") { ... }` 之后、`get("/users/{id}") { ... }` 之前,新增三个路由:

```kotlin
        get("/devices") {
            if (!call.adminGuard(adminToken)) return@get
            val rows = AdminQueries.devicesList()
            call.respondText(
                AdminViews.devicesPage(rows, AdminQueries.usersCount(), guestLlmQuota),
                ContentType.Text.Html,
            )
        }

        // 供设备列表「复制」按钮调用:返回完整 device_id(cookie 鉴权;不进 HTML)。
        get("/devices/{id}/raw") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val deviceId = AdminQueries.deviceRawId(id)
            if (deviceId == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val body = buildJsonObject { put("device_id", deviceId) }.toString()
            call.respondText(body, ContentType.Application.Json)
        }

        post("/devices/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) GuestService.deleteById(id)
            call.respondRedirect("/admin/devices")
        }
```

3f. 在 `Application.kt:140` 把:

```kotlin
        adminRoute(config.adminToken, cosService)
```

改为:

```kotlin
        adminRoute(config.adminToken, cosService, config.guestLlmQuota)
```

- [ ] **Step 4: 跑全量测试,预期 PASS(含现有用例不破)**

```bash
./gradlew -p server test
```
Expected: 全绿。重点确认:`AdminRoutesTest` 现有两个用例(`full admin auth...`、`disabled admin token...`)因默认参数 `guestLlmQuota = 100` 仍编译/通过;新 `devices page...` 用例通过。

- [ ] **Step 5: Commit**

```bash
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt server/src/main/kotlin/com/mamba/picme/server/Application.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android commit -m "feat(server): 挂载 /admin/devices 列表/复制/删除路由"
```

---

## Task 5: 文档同步

**Files:**
- Modify: `server/AGENTS.md`(第 3 节路由清单表)
- Modify: `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`(管理后台路由章节)

- [ ] **Step 1: 更新 server/AGENTS.md 路由清单**

在 `server/AGENTS.md` 第 3 节「路由清单」表中,定位到 `GET | /admin/** | P1 | ✅ | ADMIN_TOKEN | 管理后台 SSR` 这一行,在其**下面**插入三行:

```markdown
| GET | `/admin/devices` | P1 | ✅ | ADMIN_TOKEN | 未注册设备列表(anonymous_device) |
| GET | `/admin/devices/{id}/raw` | P1 | ✅ | ADMIN_TOKEN | 设备 id 复制(返回完整 device_id) |
| POST | `/admin/devices/{id}/delete` | P1 | ✅ | ADMIN_TOKEN | 删除单条设备访客记录 |
```

- [ ] **Step 2: 更新 SERVER_IMPLEMENTATION_PLAN.md(若含管理后台路由表)**

```bash
grep -n "/admin/users\|/admin/traffic\|管理后台" docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md
```

在命中「管理后台」路由清单的位置,于 `/admin/users` 相关行之后补一行(文本,供粘贴):

```markdown
- `GET /admin/devices` / `GET /admin/devices/{id}/raw` / `POST /admin/devices/{id}/delete` — 未注册设备(anonymous_device)列表、id 复制、单条删除
```

若 grep 无命中(该文档不含管理后台路由清单),跳过本步并在 commit 信息中注明"SERVER_IMPLEMENTATION_PLAN 无管理后台路由章节,未改动"。

- [ ] **Step 3: Commit**

```bash
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android add server/AGENTS.md docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md
git -C /Users/guoshuai/AndroidStudioProjects/langchain4android commit -m "docs(server): 同步未注册设备后台路由清单"
```

---

## Task 6: 本地全量验证 + SSH 发布

**Files:** 无(执行 + 部署)

> 用户已授权"完成后直接通过 ssh 发布"。`deploy.sh` 内部 `clean installDist` → rsync → `ssh bash ~/deploy-switch.sh`;`deploy-switch.sh` 做蓝绿备份 + `systemctl restart` + `healthz`(最长 30s)+ 失败自动回滚。

- [ ] **Step 1: 本地全量测试再确认**

```bash
./gradlew -p server test
```
Expected: BUILD SUCCESS,全部用例通过(含 Task 1–4 新增)。

- [ ] **Step 2: 一键 SSH 发布**

```bash
./server/deploy.sh
```
Expected(逐段):
- `>> 构建 installDist` → `BUILD SUCCESSFUL`
- `>> 同步 deploy-switch.sh ...` → scp 成功
- `>> 上传 artifact → ...:~/picme-server.new/` → rsync 成功
- `>> 触发蓝绿切换(restart + healthz + 失败回滚)` → 远端输出 `✅ 发布成功。回滚备份保留:~/picme-server.prev`,退出码 0

> 若 `DEPLOY_HOST` 需要覆盖(非默认 `ubuntu@43.161.201.142`):`DEPLOY_HOST=ubuntu@api.polang.net ./server/deploy.sh`
> 若 ssh 因 key/网络失败:停下,排查连通性后重跑;**不要**手动跳过 `deploy-switch.sh` 的 healthz 校验。

- [ ] **Step 3: 线上冒烟验证**

```bash
# 健康检查(经 nginx/域名)
curl -fsS https://api.polang.net/healthz && echo
```
Expected: `ok`(或现有 healthz 文本),退出码 0。

- [ ] **Step 4: 人工核对后台页面**

浏览器登录 `https://api.polang.net/admin` → 进入「用户」页 → 顶部应见二级 Tab `〔注册用户 (N)〕〔未注册设备 (M)〕`;点「未注册设备」→ 列表展示 device_id(掩码)+「已用 / 100」+ 复制/删除按钮;删除一条后列表刷新、对应行消失。

- [ ] **Step 5(如 Step 4 异常): 查看线上日志**

```bash
ssh ubuntu@43.161.201.142 'journalctl -u picme-api -n 80 --no-pager'
```

---

## 完成判据

- `./gradlew -p server test` 全绿。
- `./server/deploy.sh` 输出 `✅ 发布成功`,`https://api.polang.net/healthz` 返回 ok。
- 后台 `/admin/devices` 可见未注册设备,复制/删除工作正常;`/admin/users` 顶部出现二级 Tab 且显示两边计数。
