# 注册用户页 Device ID 列 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `/admin/users` 注册用户页加「Device ID」列,数据源为 `llm_call_log.device_id`(调用记录维度);客户端让注册用户请求也带 `X-Device-Id`。

**Architecture:** 跨端。服务端:`llm_call_log` 加 nullable `device_id` 列 → `UsageRecorder` 写入 → `LlmRoute` 透传 → `AdminQueries.usersList` 取每用户最近 → `usersPage` 渲染。客户端 `:runtime-core` `AgentConfigurator` 把 `X-Device-Id` 从访客 `else` 提到 `if` 外(注册 + 访客都带)。`UsageRecorder.log` 用默认参数 `deviceId: String? = null` 避免破坏现有调用。

**Tech Stack:** server: Kotlin + Ktor + Exposed + SQLite + JUnit4;runtime-core: Kotlin (jvm library) + langchain4j builder + JUnit4。

**Spec:** `docs/superpowers/specs/2026-07-25-registered-user-device-column-design.md`

**关键既约事实(已核对源码):**
- `LlmCallLogs`(`server/db/Tables.kt:97-116`)字段:id/accountId/model/provider/promptTokens/completionTokens/totalTokens/costCny/respBytes/status/latencyMs/createdAt。
- `Migrations.run`(`db/Migrations.kt:21`)已有 `SchemaUtils.createMissingTablesAndColumns(Accounts, LlmChannels)`,本计划扩展加 `LlmCallLogs`(幂等 `ALTER TABLE ADD COLUMN`)。
- `UsageRecorder.log`(`analytics/UsageRecorder.kt`)现有签名 9 参(accountId..prices)+ `now` 默认;两处现有测试不传 `now`。
- `LlmRoute`(`llm/LlmRoute.kt:41`)顶部已 `val deviceId = call.attributes.getOrNull(DeviceIdKey)`,注册 + 访客分支均可用;三处 `UsageRecorder.log` 调用在 `:65` / `:80` / `:103`。
- `AdminQueries.usersList`(`admin/AdminQueries.kt:132`)两次遍历 `LlmCallLogs`(ok 聚合 + lastActive);`UserRow` 10 字段;`maskDeviceId` 已存在(上需求加)。
- `AgentConfigurator.createRemoteChatModel`(`runtime-core/.../facade/AgentConfigurator.kt:197-213`):`X-App-Token` 与 `X-Device-Id` 在 `if/else` 互斥;`deviceId` 是成员属性(独立持有)。
- 测试基建:`TestDb.init(*tables)`;`UsageRecorderTest`/`LlmCallLogsTest`/`AdminQueriesTest`/`AdminViewsTest` 用 JUnit4;runtime-core `src/test/java` 有 JUnit 测试但无 `AgentConfiguratorTest`(builder 不易断言 header → 客户端走编译 + 服务端间接验证)。

---

## Task 1: server 表 + 迁移(llm_call_log.device_id)

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt`(`LlmCallLogs` 加列)
- Modify: `server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt:21`(`createMissingTablesAndColumns` 补 `LlmCallLogs`)
- Test: `server/src/test/kotlin/com/mamba/picme/server/db/LlmCallLogsTest.kt`(追加 1 个 `@Test`)

- [ ] **Step 1: 写失败测试**

在 `LlmCallLogsTest.kt` 的 `class LlmCallLogsTest { ... }` 末尾(`}` 之前)追加:

```kotlin
    @Test
    fun `device_id column is nullable and round-trips`() {
        TestDb.init(LlmCallLogs)
        transaction(Db.instance) {
            LlmCallLogs.insert {
                it[accountId] = 3
                it[model] = "m"
                it[provider] = "P"
                it[status] = "ok"
                it[LlmCallLogs.deviceId] = "device-aaaa-bbbb-1234"
                it[createdAt] = 1L
            }
            assertEquals("device-aaaa-bbbb-1234", LlmCallLogs.selectAll().single()[LlmCallLogs.deviceId])
        }
    }
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.db.LlmCallLogsTest.device_id*'
```
Expected: 编译失败 `Unresolved reference: deviceId`(`LlmCallLogs.deviceId` 列未定义)。

- [ ] **Step 3: 实现**

3a. `Tables.kt` 的 `object LlmCallLogs` 内,在 `val latencyMs = ...` 行之后、`val createdAt = ...` 之前插入:

```kotlin
    val deviceId = varchar("device_id", 128).nullable()
```

3b. `Migrations.kt:21` 把:

```kotlin
            SchemaUtils.createMissingTablesAndColumns(Accounts, LlmChannels)
```

改为:

```kotlin
            SchemaUtils.createMissingTablesAndColumns(Accounts, LlmChannels, LlmCallLogs)
```

- [ ] **Step 4: 跑测试,预期 PASS**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.db.LlmCallLogsTest'
```
Expected: PASS(3 个用例)。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt server/src/main/kotlin/com/mamba/picme/server/db/Migrations.kt server/src/test/kotlin/com/mamba/picme/server/db/LlmCallLogsTest.kt
git commit -m "feat(server): llm_call_log 加 device_id 列 + 幂等迁移"
```

---

## Task 2: UsageRecorder.log 写 device_id

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/analytics/UsageRecorder.kt`(签名加默认参数 + 写入)
- Test: `server/src/test/kotlin/com/mamba/picme/server/analytics/UsageRecorderTest.kt`(追加 1 个 `@Test`;现有两个用例因默认参数零改动)

- [ ] **Step 1: 写失败测试**

在 `UsageRecorderTest.kt` 的 `class UsageRecorderTest { ... }` 末尾追加:

```kotlin
    @Test
    fun `log writes device id when provided and leaves null when absent`() = runBlocking {
        TestDb.init(LlmCallLogs)
        UsageRecorder.log(
            accountId = 1,
            model = "m",
            provider = "P",
            usage = null,
            respBytes = 0,
            status = "ok",
            latencyMs = null,
            prices = emptyMap(),
            deviceId = "device-aaaa-bbbb-1234",
            now = 1L,
        )
        val row = transaction(Db.instance) { LlmCallLogs.selectAll().single() }
        assertEquals("device-aaaa-bbbb-1234", row[LlmCallLogs.deviceId])

        // 默认 null(现有调用不传 deviceId)
        TestDb.init(LlmCallLogs) // 新临时库
        UsageRecorder.log(
            accountId = 1, model = "m", provider = "P", usage = null,
            respBytes = 0, status = "ok", latencyMs = null, prices = emptyMap(), now = 2L,
        )
        val row2 = transaction(Db.instance) { LlmCallLogs.selectAll().single() }
        assertEquals(null, row2[LlmCallLogs.deviceId])
    }
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.analytics.UsageRecorderTest.log writes device*'
```
Expected: 编译失败 `No value passed for parameter 'deviceId'` 不应出现(默认参数);实际因 `UsageRecorder.log` 还没 `deviceId` 参数 → 编译失败 `Too many arguments`。

- [ ] **Step 3: 实现**

`UsageRecorder.kt` 把 `log` 签名(在 `prices` 之后、`now` 之前插入 `deviceId`)与 `insert` 体内加一行。改后函数签名段:

```kotlin
    suspend fun log(
        accountId: Int,
        model: String,
        provider: String,
        usage: TokenUsage?,
        respBytes: Int,
        status: String,
        latencyMs: Int?,
        prices: Map<String, Price>,
        deviceId: String? = null,
        now: Long = Instant.now().toEpochMilli(),
    ) {
        val cost = costCny(usage, model, prices)
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = accountId
                it[LlmCallLogs.model] = model
                it[LlmCallLogs.provider] = provider
                it[LlmCallLogs.promptTokens] = usage?.prompt
                it[LlmCallLogs.completionTokens] = usage?.completion
                it[LlmCallLogs.totalTokens] = usage?.total
                it[LlmCallLogs.costCny] = cost
                it[LlmCallLogs.respBytes] = respBytes
                it[LlmCallLogs.status] = status
                it[LlmCallLogs.latencyMs] = latencyMs
                it[LlmCallLogs.deviceId] = deviceId
                it[LlmCallLogs.createdAt] = now
            }
        }
    }
```

- [ ] **Step 4: 跑测试,预期 PASS**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.analytics.UsageRecorderTest'
```
Expected: PASS(3 个用例;现有两个不传 `deviceId` 走默认 `null`)。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/analytics/UsageRecorder.kt server/src/test/kotlin/com/mamba/picme/server/analytics/UsageRecorderTest.kt
git commit -m "feat(server): UsageRecorder.log 记录 device_id(默认 null)"
```

---

## Task 3: LlmRoute 三处 log 透传 deviceId

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt`(`:65` / `:80` / `:103` 三处调用加 `deviceId = deviceId`)

> `LlmRoute` 顶部 `:41` 已 `val deviceId = call.attributes.getOrNull(DeviceIdKey)`,注册分支现在也读它(注册用户带 `X-Device-Id` 后非空,未发版客户端仍 null)。

- [ ] **Step 1: 改三处调用**

3a. `:65` blocked_quota 行,把:

```kotlin
                    UsageRecorder.log(it, requestedModel, "", null, 0, "blocked_quota", null, prices)
```

改为(末尾加命名参数):

```kotlin
                    UsageRecorder.log(it, requestedModel, "", null, 0, "blocked_quota", null, prices, deviceId = deviceId)
```

3b. `:80` ok 块的 `UsageRecorder.log(...)`,在 `prices = prices,` 之后、右括号之前加一行:

```kotlin
                            deviceId = deviceId,
```

(即整段为:
```kotlin
                        UsageRecorder.log(
                            accountId = it,
                            model = result.model,
                            provider = result.provider,
                            usage = result.usage,
                            respBytes = result.bytes.size,
                            status = "ok",
                            latencyMs = latencyMs,
                            prices = prices,
                            deviceId = deviceId,
                        )
```
)

3c. `:103` upstream_error 行,把:

```kotlin
                        UsageRecorder.log(it, requestedModel, "", null, 0, result.logStatus, null, prices)
```

改为:

```kotlin
                        UsageRecorder.log(it, requestedModel, "", null, 0, result.logStatus, null, prices, deviceId = deviceId)
```

- [ ] **Step 2: 编译 + 全量 test,预期 PASS**

```bash
./gradlew -p server test
```
Expected: BUILD SUCCESSFUL。`LlmRoute` 改动为机械传参,靠编译 + 已有 `UsageRecorderTest`(写入)+ 后续 `AdminQueriesTest`(读取)覆盖。

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/llm/LlmRoute.kt
git commit -m "feat(server): LlmRoute 透传 device_id 到调用日志"
```

---

## Task 4: AdminQueries.usersList 取最近 device_id + UserRow 加字段

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`(`UserRow` 加字段;`usersList` 取最近 device_id;`logRow` seed 辅助加可选 deviceId)
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt`(现有 `UserRow(...)` 构造补第 11 个值,保证本 Task 可编译 —— Task 5 才加列断言)
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`(扩展 `logRow` + 追加 device_id 用例)

- [ ] **Step 1: 写失败测试**

1a. `AdminQueriesTest.kt` 的 `logRow` 辅助签名加可选 `deviceId: String? = null`,并在 `insert` 内 `it[LlmCallLogs.createdAt] = createdAt` 之后加一行 `it[LlmCallLogs.deviceId] = deviceId`。改后:

```kotlin
    private suspend fun logRow(
        accountId: Int,
        model: String,
        provider: String,
        prompt: Int?,
        completion: Int?,
        total: Int?,
        cost: Double,
        bytes: Int,
        status: String,
        createdAt: Long,
        deviceId: String? = null,
    ) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = accountId
                it[LlmCallLogs.model] = model
                it[LlmCallLogs.provider] = provider
                it[LlmCallLogs.promptTokens] = prompt
                it[LlmCallLogs.completionTokens] = completion
                it[LlmCallLogs.totalTokens] = total
                it[LlmCallLogs.costCny] = cost
                it[LlmCallLogs.respBytes] = bytes
                it[LlmCallLogs.status] = status
                it[LlmCallLogs.deviceId] = deviceId
                it[LlmCallLogs.createdAt] = createdAt
            }
        }
    }
```

1b. 在 `class AdminQueriesTest { ... }` 末尾(`private suspend fun account` 之前)追加用例:

```kotlin
    @Test
    fun `usersList picks latest non-null device_id per user`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        account(1, "a@x.com", todayStart - day)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 10, 5, 15, 0.1, 100, "ok", todayStart - day + 100, deviceId = "device-old-1234567890")
        logRow(1, "deepseek-chat", "CLOUDFLARE", 10, 5, 15, 0.1, 100, "ok", todayStart + 500, deviceId = "device-aaaa-bbbb-1234")
        logRow(1, "deepseek-chat", "CLOUDFLARE", 10, 5, 15, 0.1, 100, "ok", todayStart + 900, deviceId = null) // 最近但 null → 回退到上一条非空
        val users = AdminQueries.usersList()
        assertEquals(1, users.size)
        assertEquals("device••••1234", users[0].deviceIdMasked)
    }
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest.usersList picks latest*'
```
Expected: 编译失败 `Unresolved reference: deviceIdMasked`(`UserRow` 无该字段)。

- [ ] **Step 3: 实现**

3a. `AdminQueries.kt` 的 `data class UserRow(...)` 在末尾 `val hasToken: Boolean,` 之后加:

```kotlin
    val deviceIdMasked: String,
```

3b. `usersList` 内,在已有的 ok 聚合遍历里增加「每用户最近非空 device_id」记录。把现有 ok 遍历段:

```kotlin
        val calls = HashMap<Int, Long>()
        val tokens = HashMap<Int, Long>()
        val cost = HashMap<Int, Double>()
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            val id = r[LlmCallLogs.accountId]
            calls[id] = (calls[id] ?: 0L) + 1
            tokens[id] = (tokens[id] ?: 0L) + (r[LlmCallLogs.totalTokens]?.toLong() ?: 0L)
            cost[id] = (cost[id] ?: 0.0) + r[LlmCallLogs.costCny]
        }
```

改为(增加 `lastDevTime` / `lastDeviceId` 两个 map):

```kotlin
        val calls = HashMap<Int, Long>()
        val tokens = HashMap<Int, Long>()
        val cost = HashMap<Int, Double>()
        val lastDevTime = HashMap<Int, Long>()
        val lastDeviceId = HashMap<Int, String>()
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            val id = r[LlmCallLogs.accountId]
            calls[id] = (calls[id] ?: 0L) + 1
            tokens[id] = (tokens[id] ?: 0L) + (r[LlmCallLogs.totalTokens]?.toLong() ?: 0L)
            cost[id] = (cost[id] ?: 0.0) + r[LlmCallLogs.costCny]
            val dev = r[LlmCallLogs.deviceId]
            if (dev != null) {
                val t = r[LlmCallLogs.createdAt]
                if (lastDevTime[id]?.let { t > it } != false) {
                    lastDevTime[id] = t
                    lastDeviceId[id] = dev
                }
            }
        }
```

3c. 同函数末尾 `Accounts.selectAll().orderBy(...).map { a -> UserRow(...) }` 的 `UserRow(...)` 构造,在 `hasToken = ...` 之后追加:

```kotlin
                    deviceIdMasked = lastDeviceId[id]?.let { maskDeviceId(it) } ?: "—",
```

3d. 更新 `AdminViewsTest.kt` 现有 `UserRow(...)` 构造(两处,`:21-22`),各在末尾加 `, "—"`:

```kotlin
            UserRow(1, "a@x.com", "active", 0L, 3L, 100L, 0.5, null, "picm••••wxyz", true, "device••••1234"),
            UserRow(2, "b@x.com", "active", 0L, 0L, 0L, 0.0, null, "—", false, "—"),
```

(第一个给非空掩码便于 Task 5 断言;第二个无 device 给「—」。)

- [ ] **Step 4: 跑测试,预期 PASS**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest'
```
Expected: PASS(含新 `usersList picks latest...` 用例;`maskDeviceId("device-aaaa-bbbb-1234")` = `device` + `••••` + `1234` = `device••••1234`)。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt
git commit -m "feat(server): usersList 取每用户最近 device_id + UserRow 加字段"
```

---

## Task 5: AdminViews usersPage 加 Device ID 列

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`(表头 + 行)
- Test: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt`(现有 `users page` 测试加断言)

- [ ] **Step 1: 写失败测试**

`AdminViewsTest.kt` 的 `users page lists emails...` 测试内,在 `assertTrue(html.contains("未注册设备"))` 之前加:

```kotlin
        assertTrue(html.contains("Device ID"))
        assertTrue(html.contains("device••••1234"))
```

- [ ] **Step 2: 跑测试,预期 FAIL**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminViewsTest.users page*'
```
Expected: FAIL(`html` 不含 `Device ID`)。

- [ ] **Step 3: 实现**

3a. `AdminViews.kt` 的 `usersPage` 表头,在 `th { +"邮箱" }` 之后、`th { +"API Token" }` 之前插:

```kotlin
                    th { +"Device ID" }
```

3b. 同函数表体,在邮箱单元格 `td { a("/admin/users/${u.id}") { +u.email } }` 之后、API Token 的 `td { ... }` 之前插:

```kotlin
                    td { +u.deviceIdMasked }
```

- [ ] **Step 4: 跑测试,预期 PASS**

```bash
./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminViewsTest'
```
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt
git commit -m "feat(server): usersPage 加 Device ID 列(邮箱右侧)"
```

---

## Task 6: 客户端 AgentConfigurator 注册也带 X-Device-Id

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt:201-210`

> builder(langchain4j)不易在单测中断言 header,故本 Task 走编译 + 现有 runtime-core 测试不破 + 服务端端到端间接验证(注册请求带 header → `llm_call_log.device_id` 非空)。

- [ ] **Step 1: 改 createRemoteChatModel**

把 `:201-210`:

```kotlin
        if (config.gatewayToken.isNotBlank()) {
            builder.customHeader("X-App-Token", config.gatewayToken)
        } else {
            // 未注册访客：无账号 token 时改用设备级试用额度（X-Device-Id）。
            // 优先用 config.deviceId；若被 fallback 覆盖为空，回退到独立持有的 [deviceId]。
            val effectiveDeviceId = config.deviceId.ifBlank { deviceId }
            if (effectiveDeviceId.isNotBlank()) {
                builder.customHeader("X-Device-Id", effectiveDeviceId)
            }
        }
```

改为(`X-App-Token` 仍按 token 有无;`X-Device-Id` 无条件):

```kotlin
        if (config.gatewayToken.isNotBlank()) {
            builder.customHeader("X-App-Token", config.gatewayToken)
        }
        // 注册与访客均带 X-Device-Id：访客用于设备级试用额度；注册用户用于后台 device 维度展示。
        // 优先用 config.deviceId；若被 fallback 覆盖为空，回退到独立持有的 [deviceId]。
        val effectiveDeviceId = config.deviceId.ifBlank { deviceId }
        if (effectiveDeviceId.isNotBlank()) {
            builder.customHeader("X-Device-Id", effectiveDeviceId)
        }
```

- [ ] **Step 2: 编译 + 跑 runtime-core 现有测试,预期不破**

```bash
./gradlew :runtime-core:test
```
Expected: BUILD SUCCESSFUL(若该模块 test 因环境跑不起来,降级 `./gradlew :runtime-core:compileKotlin` 仅验证编译)。

- [ ] **Step 3: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt
git commit -m "feat(runtime-core): 注册用户 LLM 请求也带 X-Device-Id"
```

---

## Task 7: 文档同步

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt`(注释已在 Task 6 一并更新)
- Modify: `server/AGENTS.md`(第 4.1 节客户端认证补一句)

- [ ] **Step 1: 更新 server/AGENTS.md 第 4.1 节**

定位 `### 4.1 客户端认证` 下面的流程说明,在 `App → 后续请求带 X-App-Token: <picme_at_*>` 行之后补一行:

```markdown
- 注册用户请求亦带 `X-Device-Id`,用于管理后台 device 维度展示(访客则用 X-Device-Id 记设备级试用额度)
```

- [ ] **Step 2: Commit**

```bash
git add server/AGENTS.md
git commit -m "docs(server): 注明注册用户请求亦带 X-Device-Id"
```

---

## Task 8: 本地全量验证 + SSH 发布(服务端)

**Files:** 无(执行 + 部署)

> 服务端可立即 ssh 发布;客户端 `:runtime-core` 改动随下次 APK 发版才让注册用户真正带 header(见 spec 第 11 节时序)。

- [ ] **Step 1: 服务端全量 test**

```bash
./gradlew -p server test
```
Expected: BUILD SUCCESSFUL,全绿。

- [ ] **Step 2: 一键 SSH 发布(服务端)**

```bash
./server/deploy.sh
```
Expected: 末尾 `>> ✅ 发布成功。回滚备份保留：/home/ubuntu/picme-server.prev`,退出码 0。

- [ ] **Step 3: 线上冒烟**

```bash
curl -fsS https://api.polang.net/healthz && echo
```
Expected: `{"status":"ok",...}`。

- [ ] **Step 4: 人工核对 + 提示客户端发版**

浏览器登录 `/admin` → 用户页应见「Device ID」列(邮箱右);**注册用户行此时多为「—」**,因为客户端 APK 还没发版(注册用户调用还没带 `X-Device-Id`)。需另行构建并上传新 APK(`/admin/apk`),发版后注册用户下次调用即开始填充 device_id。

> 客户端 APK 发版不在本次 ssh 范围;如需我构建上传,告诉我。

---

## 完成判据

- `./gradlew -p server test` 全绿;`:runtime-core:test`(或 `compileKotlin`)通过。
- `./server/deploy.sh` 发布成功,`healthz` ok。
- `/admin/users` 出现「Device ID」列(历史/未发版期间注册用户显示「—」,符合预期)。
- 客户端 APK 发版后,注册用户调用日志 `device_id` 开始非空,列出现掩码值。
