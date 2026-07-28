# 后台「概览」「流量」页系统性增强设计

- 日期：2026-07-28
- 范围：`server` 模块后台管理控制台 `/admin`（概览）、`/admin/traffic`（流量）两页
- 约束基调：**适度增强，不过度设计**。沿用现有「kotlinx.html 服务端渲染 + 内联 SVG + 单块内联 CSS、无前端框架/无 CDN」技术栈，不引入 JS 图表库。
- 相关前序 spec：`2026-07-12-admin-backend-design.md`（后台骨架）、`2026-07-25-admin-quota-and-channel-insights-design.md`（渠道洞察，已有 `channelUsage()` 按 provider 聚合）

## 1. 背景与现状诊断

两页现状相对粗糙，具体问题：

**概览页 `overviewPage`（AdminViews.kt:62）**
- 5 张累计卡片 + 6 张今日卡片 + 2 张图（调用数 / 成本），全部裸数字，**无任何环比、无趋势对比**。
- 只统计 `blocked_*`，**看不到 `upstream_error`**，出错调用是盲区。
- 无模型 / 渠道维度，无延迟。

**流量页 `trafficPage`（AdminViews.kt:311）**
- **只有 1 张图**（每日 Token 总量）。明细表里其实有全部指标，但图被写死成 Token，无法切换。
- 固定 30 天，无范围选择；表格无合计行、无 blocked 率。
- 一个硬伤：自然日按 **UTC** 切（`AdminQueries` 的 `startOfTodayMs`/`epochDay`），「今日」在北京时间早 8 点才重置，与北京作息错 8 小时。

**数据基础**：`LlmCallLogs` 表（`accountId/model/provider/promptTokens/completionTokens/totalTokens/costCny/respBytes/status/latencyMs/deviceId/createdAt`，`createdAt` 已有索引）。现有聚合均为内存全扫描，注释明确「trial 规模毫秒级」。无需改 schema、无需加索引。

## 2. 目标

用户确认的四个「一眼看清」目标，全部要有明确归宿：
1. **成本烧钱** —— 成本趋势 + 渠道/模型成本占比 + 日均
2. **滥用与封禁** —— blocked 率 / upstream_error / 异常 Top
3. **模型分布** —— 各 model 的调用 / Token / 成本占比
4. **增长留存** —— 新增用户/设备、活跃、调用增长

## 3. 信息架构：两页按「深度」分工

```
概览 = 「现在怎么样?」一眼快照（今日 + 短趋势 + 构成快照，默认近 7 天）
流量 = 「细看趋势与构成」（范围选择 + 指标切换 + 深度拆分 + 明细，默认近 30 天）
```

不按主题拆页（避免每页都塞半套指标、互相重复）；概览给「快」，流量给「深」。

```
概览（近7天，UTC+8）                      [7天|14天]
┌─────────────────────────────────────────────────────┐
│ 今日（带环比 ↑↓）                                    │
│ [调用↑12%][成本↑8%][Token↑5%][新增用户3][新增设备11] │
│ [blocked↑][error]                                    │
│ 累计 [用户][设备][调用][Token][成本]                 │
│ 趋势  [调用|成本|Token] (指标切换)                   │
│ ▆▆▇▇█▇▆▇                                            │
│ 模型 Top（近N天 成本占比）                           │
│ glm-5.2 ████████████ 62%                            │
└─────────────────────────────────────────────────────┘

流量  [7|14|30|90天]   主图 [调用|Token|成本|字节]
┌─────────────────────────────────────────────────────┐
│ 主图（按所选指标，服务端切换重渲染）                 │
│ ▆▇▇█▇▆▇█▇                                            │
│ 健康: blocked率 / error率 / 延迟 p50 / p95           │
│ 构成（所选范围）                                     │
│   by model    glm-5.2 ████62%  deepseek-v4 ██21%    │
│   by provider CLOUDFLARE ███78% TOKENHUB ██22%      │
│ 每日明细（加合计行） 日期│调用│blocked│error│率│Token│成本│字节│
│ 异常 Top（所选范围） 用户/设备 by 调用/成本          │
└─────────────────────────────────────────────────────┘
```

## 4. 交互机制：全服务端切换，零 JS 状态

「指标切换 / 时间范围」用 **GET query 参数 + 标签链接，服务端整页重渲染**：

- 概览：`/admin?days=7&metric=cost`（默认 `days=7`、`metric=calls`）
- 流量：`/admin/traffic?days=30&metric=tokens`（默认 `days=30`、`metric=calls`）

点标签 = 跳转重渲染。无前端状态、无隐藏 div、无 JS 图表库。被否决方案：
- 隐藏 div + JS 显隐（payload 更重、两份数据、违背 ethos）
- 多系列同图（调用 / Token / ¥ 叠加，单位不一，难读）
- 饼图 / 环形图（标签难排，对内部后台是过度设计）
- 合并单页大仪表盘（与「不过度设计」相悖）

## 5. 聚合策略：一次范围扫描，内存扇出

新增的所有维度（per-day 序列、模型/渠道分布、Top 用户/设备、延迟分位、blocked/error 率）在对所选时间范围的 **单次 `LlmCallLogs` 遍历**内算完，产出 `RangeStats`，避免像现状那样每页多次独立全扫描。

## 6. 数据层变更（AdminQueries.kt）

### 6.1 时区改 UTC+8

替换两个 helper（`fmtTs` 已用 +8，改后一致）：

```kotlin
private val CN = ZoneOffset.ofHours(8)
private fun cnDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(CN).toLocalDate()
private fun startOfDayMs(ms: Long): Long = cnDate(ms).atStartOfDay(CN).toInstant().toEpochMilli()
private fun epochDay(ms: Long): String = cnDate(ms).toString()
```

`startOfTodayMs(now)` = `startOfDayMs(now)`。影响：`overview`/`dailySeries` 的「今日 / 近 N 天」窗口与图表日期标签全部对齐到北京自然日。

### 6.2 RangeStats —— 单次扫描聚合

```kotlin
data class DimStat(val key: String, val calls: Long, val tokens: Long, val cost: Double)
data class TopStat(val id: Int, val label: String, val calls: Long, val tokens: Long, val cost: Double)
data class LatencyStats(val count: Int, val p50: Int, val p95: Int)

data class RangeStats(
    val days: List<DayBucket>,        // 每日序列（DayBucket 增 errors 字段）
    val byModel: List<DimStat>,        // 仅 ok，按 metric 排序在视图层做
    val byProvider: List<DimStat>,
    val topUsers: List<TopStat>,       // 按 calls 倒序，视图取前 5；label=掩码邮箱
    val topDevices: List<TopStat>,     // 按 calls 倒序；label=掩码 deviceId
    val latency: LatencyStats,         // 仅 ok 且 latencyMs 非空的行
    val totals: DayBucket,             // 范围合计，供表头/合计行
)
```

实现：`select` where `createdAt >= since`，单次 `forEach` 同时累加：每日桶（`DayAcc` 增 `errors`）、`byModel`/`byProvider` map、`topUsers`(accountId)/`topDevices`(deviceId) map、把 ok 行的 `latencyMs` 收集进列表。遍历结束后：对 latency 列表升序排序算分位；对 byModel/byProvider/topUsers/topDevices 各取 Top-N。

- `DayBucket` 增字段 `errors: Long`（`status == "upstream_error"` 计数）。
- **blocked/error 率**：`blocked率 = blocked / (calls + blocked + errors)`，`error率 = errors / (calls + blocked + errors)`（分母为 0 → 显示 `—`）。
- **延迟分位（nearest-rank）**：升序后 `p50 = values[floor(0.50*(n-1))]`，`p95 = values[floor(0.95*(n-1))]`；n=0 → 全部 `—`。
- **规模注记**：trial 规模内存聚合 OK。latency 全量收集随范围增长；若日后 volume 上升，改 t-digest 或 DB 侧分位（本期不做，记 TODO）。
- `topUsers` 的 `label` 用现有 `maskToken` 思路的掩码邮箱；`topDevices` 用现有 `maskDeviceId`。`id` 为 accountId（视图里链接到 `/admin/users/{id}`）；设备无详情页，仅展示。

### 6.3 概览环比：OverviewRow 重构

为支持「今日 vs 昨日」环比，重构 DTO：

```kotlin
data class OverviewToday(
    val users: Long, val devices: Long, val calls: Long, val tokens: Long,
    val cost: Double, val bytes: Long, val blocked: Long, val errors: Long,
)
data class OverviewRow(
    val today: OverviewToday,
    val yesterday: OverviewToday,        // 环比基准
    val totalUsers: Long, val totalDevices: Long,
    val totalCalls: Long, val totalTokens: Long, val totalCost: Double,
)
```

`overview(now)`：今日窗口 = `[startOfDayMs(now), +1天)`，昨日窗口 = `[-1天, startOfDayMs(now))`，分别累加；`newUsers/newDevices` 取 `Accounts/AnonymousDevices.createdAt` 落在对应窗口的计数；累计维持原口径（仅 ok）。返回 `OverviewRow`。

> 概览的趋势图 + 模型 Top 复用 `RangeStats(days)`（route 里同时取 `overview()` 与 `rangeStats(days, now)`）。

### 6.4 现有 `channelUsage()`

保留（渠道页仍用全量 by provider）。`RangeStats.byProvider` 是其范围限定版，二者并存、用途不同，不合并。

## 7. 路由层变更（AdminRoutes.kt）

```kotlin
// GET /admin  概览
val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).let { if (it in listOf(7,14)) it else 7 }
val metric = parseMetric(call.request.queryParameters["metric"], default = "calls")
val ov = AdminQueries.overview(now)
val range = AdminQueries.rangeStats(days, now)
call.respondText(AdminViews.overviewPage(ov, range, days, metric), ContentType.Text.Html)

// GET /admin/traffic  流量
val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 30).let { if (it in listOf(7,14,30,90)) it else 30 }
val metric = parseMetric(call.request.queryParameters["metric"], default = "calls")
val range = AdminQueries.rangeStats(days, now)
call.respondText(AdminViews.trafficPage(range, days, metric), ContentType.Text.Html)
```

`parseMetric(raw, default)`：白名单 `{calls, tokens, cost, bytes}`，非法值回落 default。`days` 同样白名单回落（概览 `{7,14}`、流量 `{7,14,30,90}`），杜绝任意入参。

## 8. 视图层变更（AdminViews.kt）

新增小组件，全部复用现有 `statCard` / `svgBars` / `.subtabs` 风格与配色（`#006eff` 蓝 / `#0abf5b` 绿 / `#e54545` 红 / `#1f2d3d` 深蓝 / `#f0f2f5` 底）。

### 8.1 通用新组件

- `metricTabs(current, metricOptionList, basePath, days)` / `rangeTabs(current, dayOptionList, basePath, metric)`：渲染标签链接，追加/替换 `?metric=`、`?days=`，当前项加 `active`。复用 `.subtabs` 样式。
- `statCardDelta(label, today, yesterday, polarity)`：在 `statCard` 基础上加环比 chip。
  - `polarity`：`GOOD_ON_UP`（users/devices/calls/tokens）或 `BAD_ON_UP`（cost/blocked/errors）。
  - `yesterday == 0 && today > 0` → 灰底「新增」chip。
  - `today == yesterday`（含都为 0）→ 不显示 chip。
  - 否则 `pct = round((today-yesterday)/yesterday*100)`（`yesterday==0 && today==0` 已被上条覆盖），显示 `↑`/`↓` + `|pct|%`：**上升**按 polarity 上色（GOOD→绿 `#0abf5b` / BAD→红 `#e54545`），**下降**统一弱化灰 `#999`（下降信号弱化，避免色彩噪音）。
- `shareBars(items: List<DimStat>, metric, total)`：Top-6 横向占比条（label / 进度条 width=`value/total` / 百分比），按所选 `metric` 取值与 total。纯 CSS（flex + 背景宽 %），不用 SVG。
- `healthRow(range: RangeStats)`：四个小项 `blocked率 / error率 / 延迟 p50 / p95`（单位 ms），率用百分比、分位 `—` 兜底。
- 表格 `合计行`：`.total-row`（加粗、浅底），汇总调用/blocked/error/Token/字节、成本求和、率按总计重算。

### 8.2 概览页 `overviewPage(ov, range, days, metric)`

1. `rangeTabs(days, [7,14], "/admin", metric)` + `metricTabs(metric, [calls,cost,tokens], "/admin", days)`（概览只暴露 调用/成本/Token 三档）。
2. 今日卡片（`statCardDelta`）：调用、成本、Token、新增用户、新增设备、blocked、error（7 张，`polarity` 按上表）。
3. 累计卡片（`statCard`）：总用户、总设备、累计调用、累计 Token、累计成本（沿用）。
4. 趋势图：`svgBars(range.days.map{ metric对应字段 })`，formatter 按 metric（calls/tokens→`compactCount`，cost→`compactCost`）。
5. 模型 Top：`shareBars(range.byModel, "cost", range.totals.cost)` —— 概览固定按**成本**占比（呼应「成本烧钱」），不随 metric 切换。

### 8.3 流量页 `trafficPage(range, days, metric)`

1. `rangeTabs(days, [7,14,30,90], "/admin/traffic", metric)` + `metricTabs(metric, [calls,tokens,cost,bytes], "/admin/traffic", days)`。
2. 主图：`svgBars(range.days.map{ metric对应字段 })`，formatter 按 metric（bytes→`formatBytes`）。直接修掉「图写死 Token」。
3. `healthRow(range)`：blocked 率 / error 率 / p50 / p95。
4. 构成：`shareBars(range.byModel, effMetric, range.totals.<effMetric字段>)` + `shareBars(range.byProvider, effMetric, …)`，随所选 metric 切换。`effMetric = if (metric == bytes) cost else metric`（占比按字节意义不大，bytes 时回落 cost）。
5. 每日明细表：`日期 / 调用 / blocked / error / 率 / Token / 成本¥ / 出口字节`，`days.reversed()` 最新在上，顶部加 `合计行`。砍掉单独的 Prompt/Completion 列（并入 Token）以减宽。
6. 异常 Top：两张小表（各前 5）—— 用户（掩码邮箱 + 调用 + 成本，邮箱链接 `/admin/users/{id}`）、设备（掩码 deviceId + 调用 + 成本）。

### 8.4 metric → 字段 / 格式化 映射（SSOT，视图与查询共用）

| metric | DayBucket 字段 | DimStat 取值 | 格式化 |
|--------|----------------|--------------|--------|
| calls | `calls` | `calls` | `compactCount` |
| tokens | `totalTokens` | `tokens` | `compactCount` |
| cost | `cost` | `cost` | `compactCost` |
| bytes | `bytes` | —（分布不按字节） | `formatBytes` |

> `bytes` 时构成区按 cost 兜底（占比按字节意义不大），主图与合计仍按字节。

## 9. 样式（adminHead 内联 CSS 增量）

单块 CSS 仅增量扩展，不重构为设计系统：
- `.delta` 及 `.delta-up-good`/`.delta-up-bad`/`.delta-down`/`.delta-new` chip 配色。
- `.share-row` / `.share-bar` / `.share-bar-fill`（蓝）/ `.share-pct`。
- `.metric-tabs` 复用 `.subtabs`（无需新类，直接用）。
- `.health-row` / `.health-item`。
- `tr.total-row`（加粗、`#fafafa` 底）。
- `.top-list` 小表（紧凑 `th/td`）。

## 10. 测试（沿用 `TestDb` 内存库 + `testApplication`）

**AdminQueries 单测**（新增/扩展 `seed()`：跨多天、多 model、含 `blocked_*`/`upstream_error`/`latencyMs` 行）：
- UTC+8 日桶边界：UTC 16:00 的行落入「下一个」北京自然日。
- `rangeStats`：每日序列、byModel/byProvider、topUsers/topDevices、totals 一致性。
- 延迟 nearest-rank p50/p95（已知列表断言）。
- `overview` 今日/昨日窗口与累计口径正确。

**AdminRoutes 路由测试**：
- `/admin?days=14&metric=cost`、`/admin/traffic?days=90&metric=tokens` 返回 200 且含对应标签 active。
- 非法 `days`/`metric`（如 `?days=3`、`?metric=foo`）回落默认、不报错。

更新现有 `AdminRoutesTest` 中受 DTO 重构（`OverviewRow`）影响的断言。

## 11. 非目标（明确不做，守「不过度设计」）

- 不引入前端框架/构建/CDN/JS 图表库；不加暗色模式/主题系统。
- 表格不加 JS 排序/筛选（仅加合计行）；不加饼/环形图。
- 不做实时 WebSocket、保存仪表盘、用户下钻树。
- 单块内联 CSS 仅增量，不重构成 CSS 设计系统。
- 不改 DB schema、不加索引、不引入 t-digest（仅记 TODO）。

## 12. 改动文件清单

- `server/src/main/kotlin/com/mamba/picme/server/admin/AdminQueries.kt` —— 时区 helper、`RangeStats` 及相关 DTO、`OverviewRow` 重构、`DayBucket` 加 `errors`。
- `server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt` —— `overviewPage`/`trafficPage` 重写 + 新组件 + CSS 增量。
- `server/src/main/kotlin/com/mamba/picme/server/admin/AdminRoutes.kt` —— `/admin`、`/admin/traffic` 接 query 参数。
- `server/src/test/kotlin/com/mamba/picme/server/admin/AdminRoutesTest.kt` —— 参数与断言更新。
- `server/src/test/kotlin/com/mamba/picme/server/admin/AdminQueriesTest.kt` —— 新增 6.x 单测（已存在，扩展）。
- `server/src/test/kotlin/com/mamba/picme/server/admin/AdminViewsTest.kt` —— 概览/流量页渲染断言（已存在，扩展）。

## 13. 风险与回退

- **DTO 重构波及**：`OverviewRow` 仅被概览 route+view+test 使用，影响面可控；实现时 grep 全引用一次性改齐。
- **时区切换语义变化**：「今日」窗口整体后移 8 小时，属预期修正；上线前在 spec/PR 说明，避免运营误读历史对比。
- **范围 90 天性能**：trial 规模无虞；留 t-digest TODO 作 volume 上升后的兜底。
