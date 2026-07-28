# 后台「概览」「流量」页系统性增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把后台「概览」「流量」两页从裸数字升级为「一眼看清成本/封禁/模型/增长」的适度增强版，沿用现有 kotlinx.html 服务端渲染 + 内联 SVG 技术栈，不引入前端框架。

**Architecture:** 两页按深度分工——概览=今日环比快照+短趋势+模型 Top；流量=范围/指标切换+健康+构成+明细+异常 Top。新增 `RangeStats` 对所选时间范围做单次扫描、内存扇出全部维度。自然日切分由 UTC 改 UTC+8。

**Tech Stack:** Kotlin + Ktor + Exposed + kotlinx.html；内存 H2 测试（`TestDb`）；JVM 单测为质量门。

**Spec:** `docs/superpowers/specs/2026-07-28-admin-overview-traffic-redesign-design.md`

**质量门命令（每个 task 结尾跑）：** `./gradlew -p server test`（仅 server 模块 JVM 单测；记忆显示这是真门槛）

**DTO 改动策略（降 churn）：** `OverviewRow` / `DayBucket` 不重构，只在末尾**追加带默认值**的字段，使现有位置构造器（`AdminViewsTest` 里 11 参/8 参）继续编译。

---

## Task 1: 自然日切分改 UTC+8

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`（`startOfTodayMs`/`epochDay` 区域，约 106-111 行）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`（`todayStart` 常量，约 21 行）

- [ ] **Step 1: 改测试的 `todayStart` 到 UTC+8（先让断言对新窗口成立）**

`AdminQueriesTest.kt` 顶部常量区，把：
```kotlin
private val day = 86_400_000L
private val now = 1_700_000_000_000L
private val todayStart = now - (now % day)
```
改为：
```kotlin
private val day = 86_400_000L
private val now = 1_700_000_000_000L
private val cn = java.time.ZoneOffset.ofHours(8)
private val todayStart =
    java.time.Instant.ofEpochMilli(now).atZone(cn).toLocalDate().atStartOfDay(cn).toInstant().toEpochMilli()
```
> 种子行的「今日/昨日」由这个 `todayStart` 定义；只要查询侧也用 UTC+8 算同一个窗口，既有断言（callsToday=2 等）继续成立。

- [ ] **Step 2: 改实现侧两个 helper 到 UTC+8**

`AdminQueries.kt`，把这段（约 106-111 行）：
```kotlin
    private fun startOfTodayMs(now: Long): Long = now - (now % DAY_MS)

    private fun epochDay(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
```
替换为：
```kotlin
    private val CN = ZoneOffset.ofHours(8)

    private fun startOfDayMs(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(CN).toLocalDate().atStartOfDay(CN).toInstant().toEpochMilli()

    private fun startOfTodayMs(now: Long): Long = startOfDayMs(now)

    private fun epochDay(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(CN).toLocalDate().toString()
```
> `startOfTodayMs` 名字保留（`overview`/`dailySeries` 调用处不用改），新增 `startOfDayMs` 供 Task 2 算昨日窗口。`ZoneOffset`/`Instant` 已 import（文件 13-14 行）。

- [ ] **Step 3: 跑测试**

Run: `./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest'`
Expected: PASS（所有既有断言对 UTC+8 窗口仍成立）。

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "fix(admin): 概览/流量自然日切分改 UTC+8,修正今日早8点才重置"
```

---

## Task 2: DayBucket.errors + OverviewRow 昨日/错误字段 + overview 环比数据

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`（DTO 区 + `DayAcc` + `overview()` + `dailySeries`）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`（加昨日/错误断言）

- [ ] **Step 1: 加测试断言（先写期望）**

`AdminQueriesTest.kt` 的 `` `overview users detail recent and daily aggregates` `` 测试里，在现有 overview 断言块（`assertEquals(3.5, o.totalCost, 0.000001)` 之后）追加：
```kotlin
        // 新增字段：今日 error + 昨日环比 + 今日/昨日新增设备
        assertEquals(0L, o.errorsToday)
        assertEquals(0L, o.newDevicesToday)
        assertEquals(1L, o.callsYest)       // 昨日 A 一条 ok
        assertEquals(300L, o.tokensYest)
        assertEquals(2.0, o.costYest, 0.000001)
        assertEquals(0L, o.blockedYest)
        assertEquals(0L, o.errorsYest)
        assertEquals(0L, o.newUsersYest)    // A 5 天前注册，不在昨日窗口
        assertEquals(0L, o.newDevicesYest)
```

- [ ] **Step 2: 跑测试确认 FAIL**

Run: `./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest'`
Expected: 编译失败（`errorsToday`/`callsYest` 等字段不存在）。

- [ ] **Step 3: `DayBucket` 追加 `errors`（带默认值）**

`AdminQueries.kt` 的 `DayBucket` data class（约 34-43 行），在最后一个字段 `val bytes: Long,` 之后追加：
```kotlin
    val errors: Long = 0,
```
> 末尾带默认值 → `AdminViewsTest` 里的 8 参构造器继续编译。

- [ ] **Step 4: `DayAcc` 加 errors 并在 `dailySeries` 里计数**

`DayAcc`（约 331-339 行）加一行字段：
```kotlin
    var errors = 0L
```
`dailySeries`（约 184-194 行）的 `forEach` 里，在 `if (status.startsWith("blocked_")) a.blocked += 1L` 之后加：
```kotlin
                if (status == "upstream_error") a.errors += 1L
```
并把构造（约 196 行）：
```kotlin
                DayBucket(day, a.calls, a.blocked, a.promptTokens, a.completionTokens, a.totalTokens, a.cost, a.bytes)
```
改为：
```kotlin
                DayBucket(day, a.calls, a.blocked, a.promptTokens, a.completionTokens, a.totalTokens, a.cost, a.bytes, a.errors)
```

- [ ] **Step 5: `OverviewRow` 追加新字段（全部带默认值）**

`OverviewRow` data class（约 18-32 行），在末尾 `val totalCost: Double,` 之后追加：
```kotlin
    // 新增：今日 error + 今日/昨日新增设备 + 昨日环比
    val errorsToday: Long = 0,
    val newDevicesToday: Long = 0,
    val callsYest: Long = 0,
    val tokensYest: Long = 0,
    val costYest: Double = 0.0,
    val blockedYest: Long = 0,
    val errorsYest: Long = 0,
    val newUsersYest: Long = 0,
    val newDevicesYest: Long = 0,
```
> 末尾带默认值 → `AdminViewsTest` 的 11 参构造器继续编译。

- [ ] **Step 6: `overview()` 算昨日窗口 + error 计数 + 新增设备**

`AdminQueries.kt` 的 `overview(now)`（约 113-159 行）。把「今日」扫描段（约 121-134 行）：
```kotlin
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
```
替换为（单次扫描同时算今日+昨日，保持原「tokens/cost/bytes 跨所有行累加」语义）：
```kotlin
        // 今日 + 昨日（UTC+8 自然日）；tokens/cost/bytes 跨所有行累加（与原逻辑一致）
        val startYest = startToday - DAY_MS
        var callsToday = 0L
        var blockedToday = 0L
        var errorsToday = 0L
        var tokensToday = 0L
        var costToday = 0.0
        var bytesToday = 0L
        var callsYest = 0L
        var blockedYest = 0L
        var errorsYest = 0L
        var tokensYest = 0L
        var costYest = 0.0
        LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq startYest }.forEach { r ->
            val s = r[LlmCallLogs.status]
            val isToday = r[LlmCallLogs.createdAt] >= startToday
            val tokens = r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            val cost = r[LlmCallLogs.costCny]
            if (isToday) {
                if (s == "ok") callsToday += 1
                if (s.startsWith("blocked_")) blockedToday += 1
                if (s == "upstream_error") errorsToday += 1
                tokensToday += tokens
                costToday += cost
                bytesToday += r[LlmCallLogs.respBytes].toLong()
            } else {
                if (s == "ok") callsYest += 1
                if (s.startsWith("blocked_")) blockedYest += 1
                if (s == "upstream_error") errorsYest += 1
                tokensYest += tokens
                costYest += cost
            }
        }
```
然后在 `newToday` 计算之后（约 119 行），追加昨日新增用户 + 今日/昨日新增设备（用差集避免 `less` 导入）：
```kotlin
        val accYestOrTodayUsers = Accounts.selectAll().where { Accounts.createdAt greaterEq startYest }.count()
        val newYest = accYestOrTodayUsers - newToday
        val newDevicesToday = AnonymousDevices.selectAll().where { AnonymousDevices.createdAt greaterEq startToday }.count()
        val newDevicesYest =
            AnonymousDevices.selectAll().where { AnonymousDevices.createdAt greaterEq startYest }.count() - newDevicesToday
```
最后把 `OverviewRow(...)` 构造（约 146-158 行）追加新字段：
```kotlin
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
            errorsToday = errorsToday,
            newDevicesToday = newDevicesToday,
            callsYest = callsYest,
            tokensYest = tokensYest,
            costYest = costYest,
            blockedYest = blockedYest,
            errorsYest = errorsYest,
            newUsersYest = newYest,
            newDevicesYest = newDevicesYest,
        )
```

- [ ] **Step 7: 跑测试确认 PASS**

Run: `./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest'`
Expected: PASS（含新断言）。

- [ ] **Step 8: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "feat(admin): overview 补 error/昨日环比字段,DayBucket 加 errors 计数"
```

---

## Task 3: RangeStats —— 单次扫描聚合（模型/渠道/Top/延迟/合计）

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt`（新增 DTO + `rangeStats()` + `DimAcc` + `maskEmail`）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt`（扩展 `logRow` + 新测试）

- [ ] **Step 1: 扩展测试 helper `logRow` 支持 latencyMs**

`AdminQueriesTest.kt` 的 `logRow`（约 190-218 行），签名加参数并在 insert 里写入：
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
        latencyMs: Int? = null,
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
                it[LlmCallLogs.latencyMs] = latencyMs
                it[LlmCallLogs.createdAt] = createdAt
            }
        }
    }
```

- [ ] **Step 2: 写 `rangeStats` 测试（先期望）**

在 `AdminQueriesTest` 类里新增测试：
```kotlin
    @Test
    fun `rangeStats aggregates dims tops latency and totals`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs, AnonymousDevices)
        account(1, "alice@x.com", todayStart - 5 * day)
        account(2, "bob@x.com", todayStart + 10)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 100, 50, 150, 1.0, 100, "ok", todayStart + 1000, deviceId = "dev-aaaa-bbbb-1234", latencyMs = 200)
        logRow(1, "deepseek-chat", "CLOUDFLARE", 100, 50, 150, 1.0, 100, "ok", todayStart + 2000, deviceId = "dev-aaaa-bbbb-1234", latencyMs = 400)
        logRow(2, "kimi-k2.6", "TOKENHUB", 10, 5, 15, 0.5, 80, "ok", todayStart + 3000, latencyMs = 100)
        logRow(1, "deepseek-chat", "CLOUDFLARE", null, null, null, 0.0, 0, "blocked_quota", todayStart + 4000)
        logRow(1, "glm-5.2", "TOKENHUB", 40, 10, 50, 0.3, 60, "ok", todayStart - day + 500, latencyMs = 1000)

        val r = AdminQueries.rangeStats(7, now)

        assertEquals(7, r.days.size)
        // byModel（仅 ok）：deepseek 2/300/2.0；kimi 1/15/0.5；glm 1/50/0.3
        assertEquals(3, r.byModel.size)
        val dm = r.byModel.first { it.key == "deepseek-chat" }
        assertEquals(2L, dm.calls)
        assertEquals(300L, dm.tokens)
        assertEquals(2.0, dm.cost, 1e-6)
        // byProvider：CLOUDFLARE 2/300/2.0；TOKENHUB 2/65/0.8
        val cf = r.byProvider.first { it.key == "CLOUDFLARE" }
        assertEquals(2L, cf.calls)
        assertEquals(2.0, cf.cost, 1e-6)
        val th = r.byProvider.first { it.key == "TOKENHUB" }
        assertEquals(0.8, th.cost, 1e-6) // kimi 0.5 + glm 0.3
        // topUsers：acct1 ok=3（今日2+昨日1）居前；邮箱掩码保留域名
        assertEquals(1, r.topUsers[0].id)
        assertEquals(3L, r.topUsers[0].calls)
        assertTrue(r.topUsers[0].label.contains("***"))
        assertTrue(r.topUsers[0].label.contains("@x.com"))
        // topDevices：dev-aaaa... 2 次
        assertEquals(2L, r.topDevices[0].calls)
        assertTrue(r.topDevices[0].label.contains("••••"))
        // latency：ok 延迟 [100,200,400,1000] n=4 → p50=200(idx1) p95=400(idx2)
        assertEquals(4, r.latency.count)
        assertEquals(200, r.latency.p50)
        assertEquals(400, r.latency.p95)
        // totals：calls=4（今日3 ok + 昨日1），blocked=1，errors=0
        assertEquals(4L, r.totals.calls)
        assertEquals(1L, r.totals.blocked)
        assertEquals(0L, r.totals.errors)
    }
```

- [ ] **Step 3: 跑测试确认 FAIL**

Run: `./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest'`
Expected: 编译失败（`rangeStats`/`RangeStats` 等不存在）。

- [ ] **Step 4: 加 DTO + `DimAcc` + `maskEmail`**

`AdminQueries.kt` DTO 区（`ChannelUsage` 之后，约 101 行后）追加：
```kotlin
data class DimStat(val key: String, val calls: Long, val tokens: Long, val cost: Double)

data class TopStat(val id: Int, val label: String, val calls: Long, val tokens: Long, val cost: Double)

data class LatencyStats(val count: Int, val p50: Int, val p95: Int)

data class RangeStats(
    val days: List<DayBucket>,
    val byModel: List<DimStat>,
    val byProvider: List<DimStat>,
    val topUsers: List<TopStat>,
    val topDevices: List<TopStat>,
    val latency: LatencyStats,
    val totals: DayBucket,
)
```
在 `AdminQueries` object 内（`DayAcc` 附近）追加私有累加器：
```kotlin
    private class DimAcc {
        var calls = 0L
        var tokens = 0L
        var cost = 0.0
        fun add(c: Long, t: Long, co: Double) {
            calls += c; tokens += t; cost += co
        }
    }

    private fun <K> HashMap<K, DimAcc>.acc(key: K): DimAcc = get(key) ?: DimAcc().also { put(key, it) }

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        return if (at <= 1) email else email.substring(0, 1) + "***" + email.substring(at)
    }
```

- [ ] **Step 5: 实现 `rangeStats(days, now)` 单次扫描**

在 `AdminQueries` object 内（`dailySeries` 之后）追加。顶部 import 区先加 `import org.jetbrains.exposed.sql.inList`（与现有 exposed import 一起）：
```kotlin
    suspend fun rangeStats(days: Int, now: Long): RangeStats = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val startToday = startOfTodayMs(now)
        val since = startToday - (days - 1) * DAY_MS
        val dayAcc = LinkedHashMap<String, DayAcc>()
        for (i in 0 until days) {
            val ds = startToday - (days - 1 - i) * DAY_MS
            dayAcc[epochDay(ds)] = DayAcc()
        }
        val byModel = HashMap<String, DimAcc>()
        val byProvider = HashMap<String, DimAcc>()
        val userAcc = HashMap<Int, DimAcc>()
        val devAcc = HashMap<String, DimAcc>()
        val latencies = ArrayList<Int>()
        LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq since }.forEach { r ->
            val t = r[LlmCallLogs.createdAt]
            val status = r[LlmCallLogs.status]
            val tokens = r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            val cost = r[LlmCallLogs.costCny]
            val bytes = r[LlmCallLogs.respBytes].toLong()
            dayAcc[epochDay(t)]?.let { a ->
                if (status == "ok") a.calls += 1L
                if (status.startsWith("blocked_")) a.blocked += 1L
                if (status == "upstream_error") a.errors += 1L
                a.promptTokens += r[LlmCallLogs.promptTokens]?.toLong() ?: 0L
                a.completionTokens += r[LlmCallLogs.completionTokens]?.toLong() ?: 0L
                a.totalTokens += tokens
                a.cost += cost
                a.bytes += bytes
            }
            if (status == "ok") {
                byModel.acc(r[LlmCallLogs.model]).add(1, tokens, cost)
                byProvider.acc(r[LlmCallLogs.provider]).add(1, tokens, cost)
                userAcc.acc(r[LlmCallLogs.accountId]).add(1, tokens, cost)
                r[LlmCallLogs.deviceId]?.let { devAcc.acc(it).add(1, tokens, cost) }
                r[LlmCallLogs.latencyMs]?.let { latencies.add(it) }
            }
        }
        val dayBuckets = dayAcc.map { (day, a) ->
            DayBucket(day, a.calls, a.blocked, a.promptTokens, a.completionTokens, a.totalTokens, a.cost, a.bytes, a.errors)
        }
        val ta = DayAcc()
        dayAcc.values.forEach { a ->
            ta.calls += a.calls; ta.blocked += a.blocked; ta.errors += a.errors
            ta.promptTokens += a.promptTokens; ta.completionTokens += a.completionTokens
            ta.totalTokens += a.totalTokens; ta.cost += a.cost; ta.bytes += a.bytes
        }
        val totals = DayBucket("合计", ta.calls, ta.blocked, ta.promptTokens, ta.completionTokens, ta.totalTokens, ta.cost, ta.bytes, ta.errors)
        val topUserEntries = userAcc.entries.sortedByDescending { it.value.calls }.take(5)
        val emailById = if (topUserEntries.isEmpty()) emptyMap()
        else Accounts.selectAll().where { Accounts.id inList topUserEntries.map { it.key } }
            .associate { it[Accounts.id] to it[Accounts.email] }
        val topUsers = topUserEntries.map { e ->
            TopStat(e.key, maskEmail(emailById[e.key] ?: "#${e.key}"), e.value.calls, e.value.tokens, e.value.cost)
        }
        val topDevices = devAcc.entries.sortedByDescending { it.value.calls }.take(5)
            .map { e -> TopStat(0, maskDeviceId(e.key), e.value.calls, e.value.tokens, e.value.cost) }
        val lat = if (latencies.isEmpty()) LatencyStats(0, 0, 0)
        else { latencies.sort(); val n = latencies.size; LatencyStats(n, latencies[(0.50 * (n - 1)).toInt()], latencies[(0.95 * (n - 1)).toInt()]) }
        RangeStats(dayBuckets, byModel.map { DimStat(it.key, it.value.calls, it.value.tokens, it.value.cost) },
            byProvider.map { DimStat(it.key, it.value.calls, it.value.tokens, it.value.cost) },
            topUsers, topDevices, lat, totals)
    }
```

- [ ] **Step 6: 跑测试确认 PASS**

Run: `./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminQueriesTest'`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt
git commit -m "feat(admin): RangeStats 单次扫描聚合模型/渠道/Top/延迟分位/合计"
```

---

## Task 4: 概览页重写（环比卡片 + 趋势指标切换 + 模型 Top）+ 路由接线

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`（`overviewPage` 重写 + 新私有组件 + CSS）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`（`/admin` 接 days/metric + `parseDays`/`parseMetric`）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt`（overview 测试改新签名）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt`（overview 断言）

- [ ] **Step 1: 改 AdminViewsTest overview 测试到新签名（先期望）**

`AdminViewsTest.kt` 顶部（class 内最前）加一个 fixture 构造器：
```kotlin
    private fun rangeFixture(days: List<DayBucket> = emptyList(), byModel: List<DimStat> = emptyList()): RangeStats =
        RangeStats(days, byModel, emptyList(), emptyList(), emptyList(), LatencyStats(0, 0, 0),
            DayBucket("合计", 0, 0, 0, 0, 0, 0.0, 0, 0))
```
把 `` `overview page renders stat cards and an svg chart` `` 改为：
```kotlin
    @Test
    fun `overview page renders delta cards trend chart and model share bars`() {
        val ov = OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L, 0L, 0L, 0L, 0.0)
        val range = rangeFixture(
            days = listOf(DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L, 0L)),
            byModel = listOf(DimStat("glm-5.2", 3L, 1000L, 5.0)),
        )
        val html = AdminViews.overviewPage(ov, range, days = 7, metric = "calls")
        assertTrue(html.contains("今日调用"))
        assertTrue(html.contains("累计"))
        assertTrue(html.contains("<svg"))
        assertTrue(html.contains("模型 Top"))
        assertTrue(html.contains("share-bar-fill"))
    }
```
把 `` `overview page renders sub-cent cost with precision not zero` `` 改为：
```kotlin
    @Test
    fun `overview page renders sub-cent cost with precision not zero`() {
        val ov = OverviewRow(1L, 0L, 5L, 7944L, 0.0044, 819L, 0L, 0L, 0L, 0L, 0.0)
        val html = AdminViews.overviewPage(ov, rangeFixture(), days = 7, metric = "calls")
        assertTrue("sub-cent cost must not round to 0.00", html.contains("0.0044"))
    }
```
把 `` `bar charts render compact labels on top of each bar` `` 改为（用 rangeFixture 承载 series，给 traffic 也准备好——traffic 签名 Task 5 才改，这里只拼 overview）：
```kotlin
    @Test
    fun `bar charts render compact labels on top of each bar`() {
        val series = listOf(
            DayBucket("2026-07-10", 1_500_000L, 0L, 800_000L, 700_000L, 1_500_000L, 1_234.56, 4096L, 0L),
            DayBucket("2026-07-11", 1_200L, 0L, 600L, 600L, 1_200L, 0.0044, 2048L, 0L),
            DayBucket("2026-07-12", 5L, 1L, 600L, 634L, 1234L, 1.5, 4096L, 0L),
        )
        val html = AdminViews.overviewPage(
            OverviewRow(2L, 1L, 5L, 1234L, 1.5, 4096L, 1L, 0L, 0L, 0L, 0.0),
            rangeFixture(days = series), days = 7, metric = "calls",
        )
        assertTrue("compact count label for millions", html.contains(">1.5M</text>"))
        assertTrue("compact count label for thousands", html.contains(">1.2k</text>"))
        assertTrue("plain count label", html.contains(">5</text>"))
    }
```
> 该测试原还拼了 `trafficPage(series)`；traffic 签名 Task 5 改，此处先去掉，Task 5 再补 traffic 断言。

- [ ] **Step 2: 跑测试确认 FAIL**

Run: `./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminViewsTest'`
Expected: 编译失败（`overviewPage` 新签名不存在）。

- [ ] **Step 3: AdminViews 加 `import kotlin.math.abs`**

`AdminViews.kt` import 区（约 3-40 行之间）加：
```kotlin
import kotlin.math.abs
```

- [ ] **Step 4: 实现新私有组件**

在 `AdminViews` object 内、`// ── 公共片段 ──` 区（约 856 行）之前，追加一组私有组件：
```kotlin
    private enum class DeltaPolarity { GOOD_ON_UP, BAD_ON_UP }

    private fun FlowContent.statCardDelta(
        label: String,
        todayStr: String,
        today: Double,
        yesterday: Double,
        polarity: DeltaPolarity,
    ) {
        div("card") {
            div("card-label") { +label }
            div("card-value") { +todayStr }
            deltaChip(today, yesterday, polarity)
        }
    }

    private fun FlowContent.deltaChip(today: Double, yesterday: Double, polarity: DeltaPolarity) {
        when {
            yesterday == 0.0 && today > 0.0 -> span("delta delta-new") { +"新增" }
            today == yesterday -> {}
            else -> {
                val pct = abs(((today - yesterday) / yesterday * 100).toInt())
                val up = today > yesterday
                val cls = if (up) {
                    when (polarity) { DeltaPolarity.GOOD_ON_UP -> "delta delta-up-good"; DeltaPolarity.BAD_ON_UP -> "delta delta-up-bad" }
                } else {
                    "delta delta-down"
                }
                span(cls) { +(if (up) "↑" else "↓") + "$pct%" }
            }
        }
    }

    private fun FlowContent.rangeTabs(current: Int, options: List<Int>, basePath: String, metric: String) {
        div("subtabs") {
            options.forEach { d ->
                a("$basePath?days=$d&metric=$metric", classes = if (d == current) "subtab active" else "subtab") { +"${d}天" }
            }
        }
    }

    private fun FlowContent.metricTabs(current: String, options: List<Pair<String, String>>, basePath: String, days: Int) {
        div("subtabs") {
            options.forEach { (v, label) ->
                a("$basePath?days=$days&metric=$v", classes = if (v == current) "subtab active" else "subtab") { +label }
            }
        }
    }

    private fun metricLabel(metric: String): String = when (metric) {
        "tokens" -> "Token"; "cost" -> "成本 ¥"; "bytes" -> "出口字节"; else -> "调用数"
    }

    private fun dayMetricValue(b: DayBucket, metric: String): Double = when (metric) {
        "tokens" -> b.totalTokens.toDouble(); "cost" -> b.cost; "bytes" -> b.bytes.toDouble(); else -> b.calls.toDouble()
    }

    private fun metricFormatter(metric: String): (Double) -> String = when (metric) {
        "cost" -> ::compactCost
        "bytes" -> { v -> formatBytes(v.toLong()) }
        else -> ::compactCount
    }

    private fun dimStatValue(st: DimStat, metric: String): Double = when (metric) {
        "tokens" -> st.tokens.toDouble(); "cost" -> st.cost; else -> st.calls.toDouble()
    }

    private fun dimTotal(items: List<DimStat>, metric: String): Double = items.sumOf { dimStatValue(it, metric) }

    private fun FlowContent.shareBars(items: List<DimStat>, metric: String, total: Double) {
        val denom = if (total > 0.0) total else 1.0
        div("share-list") {
            items.sortedByDescending { dimStatValue(it, metric) }.take(6).forEach { st ->
                val pct = (dimStatValue(st, metric) / denom * 100).toInt().coerceIn(0, 100)
                div("share-row") {
                    span("share-label") { +st.key }
                    div("share-bar") { div("share-bar-fill") { attributes["style"] = "width:$pct%" } }
                    span("share-pct") { +"$pct%" }
                }
            }
        }
    }
```

- [ ] **Step 5: 重写 `overviewPage`**

把 `AdminViews` 内现有 `overviewPage`（约 62-89 行）整段替换为：
```kotlin
    fun overviewPage(ov: OverviewRow, range: RangeStats, days: Int, metric: String): String = createHTML().html {
        adminHead("概览 · PoLang 管理后台")
        body {
            navBar()
            rangeTabs(days, listOf(7, 14), "/admin", metric)
            metricTabs(metric, listOf("calls" to "调用", "cost" to "成本", "tokens" to "Token"), "/admin", days)
            h1 { +"概览" }
            h2 { +"今日（UTC+8 自然日，带环比）" }
            div("cards") {
                statCardDelta("今日调用", ov.callsToday.toString(), ov.callsToday.toDouble(), ov.callsYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日成本 ¥", compactCost(ov.costToday), ov.costToday, ov.costYest, DeltaPolarity.BAD_ON_UP)
                statCardDelta("今日 Token", compactCount(ov.tokensToday.toDouble()), ov.tokensToday.toDouble(), ov.tokensYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日新增用户", ov.newUsersToday.toString(), ov.newUsersToday.toDouble(), ov.newUsersYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日新增设备", ov.newDevicesToday.toString(), ov.newDevicesToday.toDouble(), ov.newDevicesYest.toDouble(), DeltaPolarity.GOOD_ON_UP)
                statCardDelta("今日 blocked", ov.blockedToday.toString(), ov.blockedToday.toDouble(), ov.blockedYest.toDouble(), DeltaPolarity.BAD_ON_UP)
                statCardDelta("今日 error", ov.errorsToday.toString(), ov.errorsToday.toDouble(), ov.errorsYest.toDouble(), DeltaPolarity.BAD_ON_UP)
            }
            h2 { +"累计" }
            div("cards") {
                statCard("总用户数", ov.totalUsers.toString())
                statCard("总设备数", ov.totalDevices.toString())
                statCard("累计调用", compactCount(ov.totalCalls.toDouble()))
                statCard("累计 Token", compactCount(ov.totalTokens.toDouble()))
                statCard("累计成本 ¥", compactCost(ov.totalCost))
            }
            h2 { +"近 $days 天 · ${metricLabel(metric)}" }
            unsafe { raw(svgBars(range.days.map { dayMetricValue(it, metric) }, range.days.map { it.day }, labelFormatter = metricFormatter(metric))) }
            h2 { +"模型 Top（近 $days 天 成本占比）" }
            shareBars(range.byModel, "cost", dimTotal(range.byModel, "cost"))
        }
    }
```

- [ ] **Step 6: CSS 增量（环比 chip + 占比条）**

`adminHead` 内联 CSS（约 1000 行 `@media` 之前）追加：
```css
.delta{display:inline-block;margin-left:6px;font-size:12px;font-weight:600;padding:1px 6px;border-radius:8px;vertical-align:middle}
.delta-up-good{color:#0abf5b;background:#e6f9f0}
.delta-up-bad{color:#e54545;background:#fff2f0}
.delta-down{color:#999;background:#f5f5f5}
.delta-new{color:#999;background:#f5f5f5}
.share-list{display:flex;flex-direction:column;gap:8px;margin-top:8px}
.share-row{display:flex;align-items:center;gap:10px;font-size:13px}
.share-label{width:160px;color:#333;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.share-bar{flex:1;height:10px;background:#f0f2f5;border-radius:6px;overflow:hidden}
.share-bar-fill{height:100%;background:#006eff;border-radius:6px}
.share-pct{width:44px;text-align:right;color:#666;font-variant-numeric:tabular-nums}
```

- [ ] **Step 7: 路由接线 `/admin` + `parseDays`/`parseMetric`**

`AdminRoutes.kt` 的 `get { }`（概览，约 91-97 行）替换为：
```kotlin
        get {
            if (!call.adminGuard(adminToken)) return@get
            val now = System.currentTimeMillis()
            val days = parseDays(call.request.queryParameters["days"], listOf(7, 14), 7)
            val metric = parseMetric(call.request.queryParameters["metric"])
            val ov = AdminQueries.overview(now)
            val range = AdminQueries.rangeStats(days, now)
            call.respondText(AdminViews.overviewPage(ov, range, days, metric), ContentType.Text.Html)
        }
```
在文件末尾（`parseChannelInput` 之后，约 499 行后）追加两个私有顶层函数：
```kotlin
private fun parseDays(raw: String?, allowed: List<Int>, default: Int): Int =
    raw?.toIntOrNull()?.let { if (it in allowed) it else default } ?: default

private fun parseMetric(raw: String?): String =
    if (raw in listOf("calls", "tokens", "cost", "bytes")) raw!! else "calls"
```

- [ ] **Step 8: AdminRoutesTest overview 断言**

`AdminRoutesTest.kt` 第 5 步（r5，约 104-106 行）改为：
```kotlin
        // 5. valid cookie → overview 200
        val r5 = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r5.status)
        val ovHtml = r5.bodyAsText()
        assertTrue(ovHtml.contains("概览"))
        assertTrue(ovHtml.contains("今日调用"))
```

- [ ] **Step 9: 跑 server 全量测试确认 PASS**

Run: `./gradlew -p server test`
Expected: PASS（含 AdminViewsTest / AdminRoutesTest / AdminQueriesTest）。
> 注意：`AdminViewsTest` 的 traffic 测试此刻仍用旧签名 `trafficPage(series)`——它尚未改，应仍编译通过（Task 5 才改 trafficPage）。若 traffic 测试因别的原因失败，先不处理 traffic，确认 overview 相关全绿即可。

- [ ] **Step 10: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt
git commit -m "feat(admin): 概览页重写——环比卡片+趋势指标切换+模型Top,路由接 days/metric"
```

---

## Task 5: 流量页重写（范围/指标切换 + 健康 + 构成 + 明细合计 + 异常 Top）+ 路由接线

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt`（`trafficPage` 重写 + 新私有组件 + CSS）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt`（`/admin/traffic` 接 days/metric）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt`（traffic 测试改新签名）
- Modify: `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt`（traffic 断言 + query 参数测试）

- [ ] **Step 1: 改 AdminViewsTest traffic 测试到新签名（先期望）**

`AdminViewsTest.kt` 的 `` `traffic page renders daily table` `` 改为：
```kotlin
    @Test
    fun `traffic page renders range tabs detail table with total row and top lists`() {
        val range = RangeStats(
            days = listOf(DayBucket("2026-07-12", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L)),
            byModel = listOf(DimStat("deepseek-chat", 1L, 15L, 0.1)),
            byProvider = listOf(DimStat("CLOUDFLARE", 1L, 15L, 0.1)),
            topUsers = listOf(TopStat(1, "a***@x.com", 1L, 15L, 0.1)),
            topDevices = emptyList(),
            latency = LatencyStats(1, 120, 120),
            totals = DayBucket("合计", 1L, 0L, 10L, 5L, 15L, 0.1, 100L, 0L),
        )
        val html = AdminViews.trafficPage(range, days = 30, metric = "calls")
        assertTrue(html.contains("每日明细"))
        assertTrue(html.contains("合计"))
        assertTrue(html.contains("2026-07-12"))
        assertTrue(html.contains("构成"))
        assertTrue(html.contains("异常 Top"))
        assertTrue(html.contains("blocked 率"))
    }
```
把 Task 4 改过的 `` `bar charts render compact labels on top of each bar` `` 末尾再加上 traffic 断言（补回被去掉的部分）——在最后追加：
```kotlin
        val trafficHtml = AdminViews.trafficPage(
            RangeStats(series, emptyList(), emptyList(), emptyList(), emptyList(), LatencyStats(0, 0, 0),
                DayBucket("合计", 0, 0, 0, 0, 0, 0.0, 0, 0)),
            days = 7, metric = "calls",
        )
        assertTrue("compact cost label for thousands", trafficHtml.contains(">1.23k</text>"))
```

- [ ] **Step 2: 跑测试确认 FAIL**

Run: `./gradlew -p server test --tests 'com.mamba.picme.server.admin.AdminViewsTest'`
Expected: 编译失败（`trafficPage` 新签名不存在）。

- [ ] **Step 3: 实现流量页私有组件**

`AdminViews` 内（Task 4 组件之后）追加：
```kotlin
    private fun FlowContent.healthRow(range: RangeStats) {
        val denom = range.totals.calls + range.totals.blocked + range.totals.errors
        val rate = { n: Long -> if (denom > 0) "${(n.toDouble() / denom * 100).toInt()}%" else "—" }
        div("cards health-row") {
            healthItem("blocked 率", rate(range.totals.blocked))
            healthItem("error 率", rate(range.totals.errors))
            healthItem("延迟 p50", if (range.latency.count == 0) "—" else "${range.latency.p50} ms")
            healthItem("延迟 p95", if (range.latency.count == 0) "—" else "${range.latency.p95} ms")
        }
    }

    private fun FlowContent.healthItem(label: String, value: String) {
        div("card health-item") {
            div("card-label") { +label }
            div("card-value") { +value }
        }
    }

    private fun FlowContent.dailyDetailTable(range: RangeStats) {
        val tot = range.totals
        table {
            tr {
                th { +"日期" }; th { +"调用" }; th { +"blocked" }; th { +"error" }; th { +"率" }
                th { +"Token" }; th { +"成本 ¥" }; th { +"出口字节" }
            }
            tr("total-row") {
                td { +"合计" }; td { +tot.calls.toString() }; td { +tot.blocked.toString() }; td { +tot.errors.toString() }
                td { +rateStr(tot.calls, tot.blocked, tot.errors, tot.blocked) }
                td { +compactCount(tot.totalTokens.toDouble()) }; td { +compactCost(tot.cost) }; td { +formatBytes(tot.bytes) }
            }
            range.days.reversed().forEach { b ->
                tr {
                    td { +b.day }; td { +b.calls.toString() }; td { +b.blocked.toString() }; td { +b.errors.toString() }
                    td { +rateStr(b.calls, b.blocked, b.errors, b.blocked) }
                    td { +compactCount(b.totalTokens.toDouble()) }; td { +fmt(b.cost) }; td { +formatBytes(b.bytes) }
                }
            }
        }
    }

    private fun rateStr(calls: Long, blocked: Long, errors: Long, hit: Long): String {
        val denom = calls + blocked + errors
        return if (denom > 0) "${(hit.toDouble() / denom * 100).toInt()}%" else "—"
    }

    private fun FlowContent.topLists(range: RangeStats) {
        div("cards") {
            div("card top-card") {
                div("card-label") { +"用户 Top 5（按调用）" }
                topTable(range.topUsers, "/admin/users")
            }
            div("card top-card") {
                div("card-label") { +"设备 Top 5（按调用）" }
                topTable(range.topDevices, null)
            }
        }
    }

    private fun FlowContent.topTable(items: List<TopStat>, linkBase: String?) {
        table("top-list") {
            tr { th { +"标识" }; th { +"调用" }; th { +"成本 ¥" } }
            if (items.isEmpty()) {
                tr { td { +"—" }; td {}; td {} }
            }
            items.forEach { s ->
                tr {
                    td {
                        if (linkBase != null && s.id > 0) a("$linkBase/${s.id}") { +s.label } else +s.label
                    }
                    td { +s.calls.toString() }
                    td { +fmt(s.cost) }
                }
            }
        }
    }
```

- [ ] **Step 4: 重写 `trafficPage`**

把 `AdminViews` 内现有 `trafficPage`（约 311-344 行）整段替换为：
```kotlin
    fun trafficPage(range: RangeStats, days: Int, metric: String): String = createHTML().html {
        adminHead("流量 · PoLang 管理后台")
        body {
            navBar()
            rangeTabs(days, listOf(7, 14, 30, 90), "/admin/traffic", metric)
            metricTabs(metric, listOf("calls" to "调用", "tokens" to "Token", "cost" to "成本", "bytes" to "字节"), "/admin/traffic", days)
            h1 { +"流量（近 $days 天，UTC+8）· ${metricLabel(metric)}" }
            h2 { +"每日 ${metricLabel(metric)}" }
            unsafe { raw(svgBars(range.days.map { dayMetricValue(it, metric) }, range.days.map { it.day }, labelFormatter = metricFormatter(metric))) }
            h2 { +"健康" }
            healthRow(range)
            val effMetric = if (metric == "bytes") "cost" else metric
            h2 { +"构成（按${metricLabel(effMetric)}）" }
            div("cards") {
                div("card share-card") {
                    div("card-label") { +"by model" }
                    shareBars(range.byModel, effMetric, dimTotal(range.byModel, effMetric))
                }
                div("card share-card") {
                    div("card-label") { +"by provider" }
                    shareBars(range.byProvider, effMetric, dimTotal(range.byProvider, effMetric))
                }
            }
            h2 { +"每日明细" }
            dailyDetailTable(range)
            h2 { +"异常 Top（近 $days 天）" }
            topLists(range)
        }
    }
```

- [ ] **Step 5: CSS 增量（健康行 + 明细合计行 + Top 小表 + share/top 卡片）**

`adminHead` 内联 CSS（Task 4 追加块之后、`@media` 之前）继续追加：
```css
.health-item{min-width:120px}
.health-item .card-value{font-size:18px}
.share-card{flex:1;min-width:280px}
.top-card{flex:1;min-width:280px}
.top-list{font-size:12px;margin-top:8px}
.top-list th,.top-list td{padding:6px 8px}
tr.total-row td{font-weight:700;background:#fafafa}
```

- [ ] **Step 6: 路由接线 `/admin/traffic`**

`AdminRoutes.kt` 的 `get("/traffic")`（约 219-223 行）替换为：
```kotlin
        get("/traffic") {
            if (!call.adminGuard(adminToken)) return@get
            val now = System.currentTimeMillis()
            val days = parseDays(call.request.queryParameters["days"], listOf(7, 14, 30, 90), 30)
            val metric = parseMetric(call.request.queryParameters["metric"])
            call.respondText(AdminViews.trafficPage(AdminQueries.rangeStats(days, now), days, metric), ContentType.Text.Html)
        }
```

- [ ] **Step 7: AdminRoutesTest traffic 断言 + query 参数测试**

第 9 步（r9，约 127-129 行）改为：
```kotlin
        // 9. traffic page
        val r9 = c.get("/admin/traffic") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r9.status)
        assertTrue(r9.bodyAsText().contains("每日明细"))
```
在 `AdminRoutesTest` 类末尾（最后一个 `}` 之前）新增一个测试，覆盖 query 参数与非法值回落：
```kotlin
    @Test
    fun `overview and traffic honor days and metric query params and clamp invalid`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }

        // overview 接受 days=14&metric=cost
        val ov = c.get("/admin?days=14&metric=cost") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, ov.status)
        val ovHtml = ov.bodyAsText()
        assertTrue(ovHtml.contains("近 14 天 · 成本 ¥"))
        assertTrue(ovHtml.contains("subtab active\" href=\"/admin?days=14"))

        // traffic 接受 days=90&metric=tokens
        val tr = c.get("/admin/traffic?days=90&metric=tokens") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, tr.status)
        assertTrue(tr.bodyAsText().contains("近 90 天，UTC+8）· Token"))

        // 非法值回落默认（days=3 / metric=foo），仍 200、不崩
        val bad = c.get("/admin/traffic?days=3&metric=foo") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, bad.status)
        assertTrue(bad.bodyAsText().contains("近 30 天，UTC+8）· 调用数"))
    }
```

- [ ] **Step 8: 跑 server 全量测试确认 PASS**

Run: `./gradlew -p server test`
Expected: PASS（全部 admin 测试 + 新 query 参数测试）。

- [ ] **Step 9: Commit**

```bash
git add server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt
git commit -m "feat(admin): 流量页重写——范围/指标切换+健康+构成+明细合计+异常Top"
```

---

## Task 6: 全量验证 + 清理

**Files:** 无代码改动（仅验证）

- [ ] **Step 1: server 模块全量测试**

Run: `./gradlew -p server test`
Expected: 全绿。

- [ ] **Step 2: 确认 `dailySeries` 仍被引用（保留即可，不删）**

Run: `grep -rn "dailySeries" server/src`
Expected: `AdminQueries.kt`（定义）+ `AdminQueriesTest.kt`（测试）命中。路由已不再调用——属预期，保留该函数与其测试（无编译问题，`RangeStats` 内部为单次扫描而独立实现，刻意不复用以避免双重扫描）。

- [ ] **Step 3: （可选）本地起服务肉眼验证**

Run: `./run-local.sh`（或 `server/run-local.sh`），浏览器登录 `/admin` 与 `/admin/traffic`，点范围/指标标签确认服务端切换重渲染、环比 chip、模型占比条、健康行、合计行、异常 Top 正常。
Expected: 无 500；指标切换/范围切换 URL 带 `?days=`/`?metric=` 且页面随之变化。

- [ ] **Step 4: 收尾 commit（若有清理）**

如 Step 3 发现需微调，单独 commit；否则本任务无新增 commit。

---

## Self-Review（plan 写完后自查记录）

- **Spec 覆盖**：§4 交互机制 → Task 4/5 路由 query 参数；§5 单次扫描 → Task 3；§6.1 时区 → Task 1；§6.2 RangeStats → Task 3；§6.3 环比 → Task 2+4；§7 路由 → Task 4/5；§8 视图 → Task 4/5；§9 CSS → Task 4/5；§10 测试 → 各 Task 内；§11 非目标 → 未做。全覆盖。
- **占位符**：无 TBD/TODO（spec 里 t-digest 是明确延后，非占位）。
- **类型一致性**：`RangeStats`/`DimStat`/`TopStat`/`LatencyStats` 定义(Task 3) 与视图(Task 4/5)、测试一致；`DayBucket` 9 参(含 errors) 与所有构造点一致；`overviewPage(ov, range, days, metric)` / `trafficPage(range, days, metric)` 签名在路由与测试一致；`parseDays`/`parseMetric` 定义(Task 4) 与 traffic 路由(Task 5) 复用一致。
