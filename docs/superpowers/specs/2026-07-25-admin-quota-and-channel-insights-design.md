# 管理后台：额度重置 / 上限可调 / 概览累计 / 渠道余额 设计

- **日期**：2026-07-25
- **服务端版本**：0.6.4 → 0.6.5（建议，发布时定）
- **状态**：已与用户对齐，待实施
- **范围**：`server/` 管理后台四项运营能力——① 用户/访客额度一键重置（历史保留）；② 调用上限人工可调（全局默认 + 单用户覆盖）；③ 概览页补累计指标（用户/设备/Token）；④ 渠道页补消耗聚合 + 上游余额（缓存 + 手动刷新）。**纯服务端改动**：不动客户端、不新增依赖。

---

## 1. 背景与现状

### 1.1 额度模型（已天然分离「计数」与「历史」）

| 表 / 字段 | 角色 | 说明 |
|---|---|---|
| `account.llm_calls_used` | 活计数器 | 每次 ok 调用 +1，上游失败回滚 -1 |
| `account.llm_calls_limit` | 每账号上限 | 默认 100（schema）/ 1000（env `FREE_LLM_QUOTA`）；注册时写入，**校验时按行读** |
| `anonymous_device.llm_calls_used` | 访客计数器 | 同构；**无行内上限**，用全局 `guestLlmQuota`（env `GUEST_LLM_QUOTA`，默认 100） |
| `llm_call_log` | **历史/分析** | 每次 `/v1/chat/completions`（含 blocked/upstream_error）写一行；与计数器职责分离 |

→ 「重置已用额度、保留历史」= `llm_calls_used = 0`，`llm_call_log` 原封不动。**无需新增历史表**。

### 1.2 四个缺口

| 诉求 | 现状 | 缺口 |
|---|---|---|
| 重置某用户额度 | 仅「重新注册」（`createOrRefresh` 清零）会换 token；后台无入口 | **无重置能力** |
| 调整调用上限 | `freeLlmQuota` / `guestLlmQuota` 是 **env 启动时写死**，以 `Int` 穿透三个路由；每账号上限无后台改入 | **运行时不可调** |
| 概览累计 | 仅有「今日」指标 + `totalUsers` | **缺总设备数、全量 Token/调用/成本** |
| 渠道余额/消耗 | 渠道页仅展示配置（名/Token/模型/启用） | **缺按渠道消耗聚合 + 上游余额** |

### 1.3 已存在的便利条件

- `checkAndIncrementQuota` 已按行读 `account.llm_calls_limit` → **单用户覆盖几乎免费**（只缺一个改值的入口）。
- `llm_call_log.provider` 写的就是渠道 `name` → **按渠道聚合消耗**直接 group by 即可。
- 迁移机制：`Tables.kt` 是事实源，`SchemaUtils.create` + `createMissingTablesAndColumns` 启动幂等加表/列；`migrations/*.sql` 仅作参考 DDL（见 `003_llm_channel.sql` 注释）。

---

## 2. 已锁定决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 上限可调粒度 | **全局默认 + 单用户覆盖** | 设置页调默认（对新注册/访客生效）；用户详情页可单独改某账号 limit |
| 额度运行时载体 | **`server_setting` 表 + `SettingsService` 内存快照** | 持久（重启不丢）；读命中 `@Volatile` 快照，**热路径零 DB 读**（SQLite 单连接安全）；与 `AccountService`/`GuestService` 同为 `object` 风格 |
| 重置范围 | **账号 + 访客设备**（对称） | 用户详情页 + 设备页各一个「重置」按钮，与现有「删除」并列 |
| 重置审计 | **不加**审计表 | YAGNI（研究项目）；计数清零即可，`llm_call_log` 本身即是历史 |
| 概览累计用户口径 | 计 `status in (active, revoked)`，**排除已删除/匿名化** | 已删除账号 email 被改写为 `deleted_<id>__...`，不计入「真实用户」 |
| 渠道消耗口径 | **全量**（与「累计」一致） | 与概览累计同口径；不做时间范围筛选（YAGNI） |
| 渠道余额取法 | **缓存 + 手动刷新** | 页面加载不依赖外部调用；`POST /admin/channels/{id}/refresh-balance` 触发实时拉取（短超时），失败显「—」 |
| 余额持久化 | `llm_channel` 加 `balance_url` / `balance_json` / `balance_checked_at` 三列 | 缓存落库，重启不丢；`balance_url` 空的渠道（Cloudflare/TokenHub/GLM/Kimi）不拉、显「—」 |
| DeepSeek 余额预填 | 启动回填 `https://api.deepseek.com/user/balance` | 仿现有 `backfillDefaultModels()`，给老库现有「DeepSeek 直连」补上 |

### 非目标（YAGNI，本期不做）

- **不**做设置项的版本/审计历史（改了就覆盖）。
- **不**把 `rateLimitPerMin` / `maxTokensCap` / 单价挂进设置页（先只做额度两项；SettingsService 结构可扩展，留后续）。
- **不**做余额定时自动刷新（手动按钮足矣；避免无谓打上游）。
- **不**做渠道消耗的时间范围筛选/图表（流量页已有日趋势）。
- **不**做 admin 操作审计表。
- **不**改客户端、不新增依赖。

---

## 3. 架构总览

全部改动落在 `server/`，同一二进制。

| 文件 | 动作 | 职责 |
|---|---|---|
| `db/Tables.kt` | 改 | 新增 `ServerSettings` 表；`LlmChannels` 加 3 列（balance） |
| `db/Migrations.kt` | 改 | `create(...)` 登记 `ServerSettings`；新增 `seedSettings(config)` + `backfillBalanceUrls()` |
| `config/SettingsService.kt` | **新增** | `object`，`@Volatile` 快照；`load()` / `snapshot()` / `update()` |
| `auth/AccountService.kt` | 改 | 新增 `resetQuota(id)` + `setLimit(id, limit)` |
| `auth/GuestService.kt` | 改 | 新增 `resetQuota(id)` |
| `admin/AdminQueries.kt` | 改 | `OverviewRow` 加累计字段 + `overview()` 补扫描；新增 `channelUsage()` |
| `llm/ChannelBalanceService.kt` | **新增** | `refresh(id)` 调上游 balance API + 解析 + 落库；`cached(id)` 读缓存 |
| `admin/AdminRoutes.kt` | 改 | 新增 6 条路由（见 §6.2 / §7.1 / §9.4）；签名去 `guestLlmQuota` 参、加 `balanceService` 参 |
| `admin/AdminViews.kt` | 改 | 概览累计卡片组、用户详情「重置/改上限」、设备页「重置」、渠道页「消耗/余额」列 + 设置页 |
| `routes/AuthRoute.kt` | 改 | 去 `freeLlmQuota` 参，改读 `SettingsService.snapshot()` |
| `llm/LlmRoute.kt` | 改 | 去 `guestLlmQuota` 参，改读 `SettingsService.snapshot()` |
| `Application.kt` | 改 | `Migrations.run` 后 `SettingsService.load()`；构造 `ChannelBalanceService`；调整路由调用 |
| `migrations/007_server_setting.sql` | **新增** | 参考 DDL |
| `migrations/008_llm_channel_balance.sql` | **新增** | 参考 DDL |
| `server/AGENTS.md` | 改 | 路由清单 + 设置项说明 |

**零新增依赖、零客户端改动。**

---

## 4. 数据模型变更

### 4.1 新表 `server_setting`（key-value，可扩展）

```kotlin
object ServerSettings : Table("server_setting") {
    val key = varchar("key", 48)
    val value = integer("value")           // 当前只需 int；后续非 int 项再加表或改 text
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(key)
}
```

```sql
-- migrations/007_server_setting.sql（参考 DDL）
CREATE TABLE IF NOT EXISTS server_setting (
  key        VARCHAR(48) PRIMARY KEY,
  value      INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
```

初始两行：`free_llm_quota`、`guest_llm_quota`。

### 4.2 `llm_channel` 加 3 列（余额缓存）

```kotlin
// Tables.kt: LlmChannels 内追加
val balanceUrl = varchar("balance_url", 512).default("")      // 空 = 该渠道无余额 API
val balanceJson = text("balance_json").default("")            // 上游响应原文（缓存）
val balanceCheckedAt = long("balance_checked_at").nullable()  // 上次成功刷新时间
```

```sql
-- migrations/008_llm_channel_balance.sql（参考 DDL）
ALTER TABLE llm_channel ADD COLUMN balance_url        VARCHAR(512) DEFAULT '';
ALTER TABLE llm_channel ADD COLUMN balance_json       TEXT         DEFAULT '';
ALTER TABLE llm_channel ADD COLUMN balance_checked_at INTEGER;
```

- 经 `SchemaUtils.createMissingTablesAndColumns(LlmChannels)` 幂等加列（与 `default_model` 同路径）。

### 4.3 `account` / `anonymous_device` — 不改结构

重置只 `UPDATE llm_calls_used = 0`；改上限只 `UPDATE account.llm_calls_limit = ?`。`checkAndIncrementQuota` 已按行读 limit，校验逻辑零改动。

---

## 5. `SettingsService`（额度运行时载体）

```kotlin
object SettingsService {
    data class Snapshot(val freeLlmQuota: Int, val guestLlmQuota: Int)

    @Volatile private var current = Snapshot(freeLlmQuota = 1000, guestLlmQuota = 100)

    fun snapshot(): Snapshot = current          // 热路径：纯内存读，零 DB
    suspend fun load() { /* 从 server_setting 读两行灌入 current */ }
    suspend fun update(free: Int?, guest: Int?): Snapshot {
        // newSuspendedTransaction: UPSERT 命中行 + updatedAt=now；重灌 current 并返回
    }
}
```

- **播种**：`Migrations.seedSettings(config)` 在表为空时写入 `free_llm_quota = config.freeLlmQuota`、`guest_llm_quota = config.guestLlmQuota`（env 降级为「首次默认」，之后 DB 为准）。
- **加载时机**：`main()` 中 `Migrations.run(config)` 之后、`embeddedServer` 之前，`runBlocking { SettingsService.load() }`（与 `ChannelRegistry.reload()` 同位置）。
- **热路径安全**：`LlmRoute` 访客分支读 `SettingsService.snapshot().guestLlmQuota`，不增 SQLite 读（pool=1 不受影响）。

---

## 6. 功能 ① — 额度重置

### 6.1 服务层

```kotlin
// AccountService
suspend fun resetQuota(id: Int): Boolean = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
    val row = Accounts.selectAll().where { Accounts.id eq id }.firstOrNull() ?: return@... false
    Accounts.update({ Accounts.id eq id }) { it[llmCallsUsed] = 0 }
    true
}

suspend fun setLimit(id: Int, limit: Int): Boolean {
    require(limit >= 0) { "limit must be >= 0" }
    // UPDATE account.llm_calls_limit；命中返回 true
    // 注：limit=0 即「禁用」（checkAndIncrementQuota: used(0) >= limit(0) → 恒拦截），等价于 revoke 但不失效 token。
}

// GuestService
suspend fun resetQuota(id: Int) { /* UPDATE anonymous_device SET llm_calls_used=0 WHERE id=? */ }
```

### 6.2 路由

| 方法 | 路径 | 行为 |
|---|---|---|
| `POST` | `/admin/users/{id}/reset-quota` | `AccountService.resetQuota(id)` → 重定向 `/admin/users/{id}` |
| `POST` | `/admin/users/{id}/limit` | 解析 `limit`（非负整数，非法 400）→ `setLimit` → 重定向详情页 |
| `POST` | `/admin/devices/{id}/reset-quota` | `GuestService.resetQuota(id)` → 重定向 `/admin/devices` |

均顶部 `adminGuard(adminToken)`。

### 6.3 UI

- **用户详情页**：`actions-bar` 加「重置已用额度」按钮（confirm 文案提示「历史调用记录保留，仅清零计数器」）；`cards` 区显示「已用 / 上限」；另加一个内联小表单「修改上限」(number input + 提交)。
- **设备页**：操作列「删除」旁加「重置」按钮（confirm）。

---

## 7. 功能 ② — 上限可调（设置页）

### 7.1 设置页路由

| 方法 | 路径 | 行为 |
|---|---|---|
| `GET` | `/admin/settings` | 读 `SettingsService.snapshot()` 渲染表单（free/guest 两数值 + 当前值） |
| `POST` | `/admin/settings` | 解析两 int（>0，非法回 400 带错误提示）→ `SettingsService.update(...)` → 重定向 `/admin/settings?msg=...` |

### 7.2 消费点改读快照（去掉 3 个 Int 参数）

| 文件 | 旧 | 新 |
|---|---|---|
| `AuthRoute.kt:56` | `createOrRefresh(req.email, freeLlmQuota)` | `createOrRefresh(req.email, SettingsService.snapshot().freeLlmQuota)`；签名去 `freeLlmQuota` 参 |
| `LlmRoute.kt:55,95` | `guestLlmQuota` 入参 | `SettingsService.snapshot().guestLlmQuota`；签名去 `guestLlmQuota` 参 |
| `AdminRoutes.kt:110` | `devicesPage(..., guestLlmQuota)` | `devicesPage(..., SettingsService.snapshot().guestLlmQuota)`；签名去该参 |

`Application.kt` 调用处同步去掉传参：`authRoute(emailService)`、`llmRoute(llmProxy, rateLimiter, config.llmPrices)`、`adminRoute(config.adminToken, cosService, balanceService)`。

### 7.3 导航

`AdminViews.navBar()` 在「渠道」与「APK」之间插入「设置」链接 → `/admin/settings`。

---

## 8. 功能 ③ — 概览累计指标

### 8.1 `OverviewRow` 扩展

```kotlin
data class OverviewRow(
    // 今日（原有）
    val newUsersToday, callsToday, tokensToday: Long, val costToday: Double,
    val bytesToday, blockedToday: Long,
    // 累计（新增）
    val totalUsers: Long,       // status in (active, revoked)
    val totalDevices: Long,     // anonymous_device 全量
    val totalCalls: Long,       // llm_call_log status='ok' 全量
    val totalTokens: Long,      // sum(total_tokens) 全量
    val totalCost: Double,      // sum(cost_cny) 全量
)
```

### 8.2 查询

`AdminQueries.overview(now)` 在现有「今日」扫描基础上，**同一次事务**补：
- `totalUsers = Accounts.selectAll().where { status inList listOf("active","revoked") }.count()`
- `totalDevices = AnonymousDevices.selectAll().count()`
- 对 `LlmCallLogs.selectAll()`（全量，非今日切片）遍历累加 calls/tokens/cost（与今日扫描合并到一次全量遍历：遍历时按 `createdAt >= startToday` 分流到「今日」桶，其余只进「累计」桶；避免两次扫表）。

> 注：`LlmDailyCounters` 表存在但**从未被写入**（vestigial），全量只能从 `llm_call_log` 聚合。

### 8.3 UI

`overviewPage` 拆两组 `cards`：
- **累计**：总用户 / 总设备 / 累计调用 / 累计 Token / 累计成本 ¥
- **今日**：今日新增 / 今日调用 / 今日 Token / 今日成本 / 今日出口字节 / 今日 blocked

下方两 SVG 趋势图不变。

---

## 9. 功能 ④ — 渠道消耗 + 余额

### 9.1 消耗聚合

```kotlin
data class ChannelUsage(val provider: String, val calls: Long, val tokens: Long, val cost: Double)

suspend fun channelUsage(): Map<String, ChannelUsage> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
    val m = HashMap<String, ChannelUsage>()
    LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
        val p = r[LlmCallLogs.provider]
        m.merge(p, ChannelUsage(p, 1, r[totalTokens]?.toLong() ?: 0, r[costCny])) { a, b ->
            ChannelUsage(p, a.calls + b.calls, a.tokens + b.tokens, a.cost + b.cost)
        }
    }
    m
}
```

`channelsPage` 渲染时按 `ch.name` 查 map，没有调用记录的渠道显 0。

### 9.2 `ChannelBalanceService`

```kotlin
class ChannelBalanceService(private val httpClient: HttpClient, private val timeoutMs: Long = 8_000) {
    data class Cached(val display: String, val checkedAt: Long?)   // display 如 "¥10.03" / "—" / "(解析失败)"

    suspend fun refresh(channelId: Int): Boolean {
        // 1. 取 balance_url + 明文 token（ChannelRepository 加 balanceConfig(id): Triple<url,token,authStyle>?）
        // 2. balance_url 空 → 返回 false（该渠道不支持）
        // 3. httpClient.get(url) { timeout; header(authStyle→Bearer token) }
        // 4. 2xx → 解析 body → UPDATE balance_json + balance_checked_at；非 2xx/超时/异常 → 不覆盖旧缓存，返回 false
    }

    suspend fun cached(channelId: Int): Cached {
        // 读 balance_json + balance_checked_at；解析 DeepSeek 形态取 total_balance
    }
}
```

**DeepSeek 余额响应解析**（`https://api.deepseek.com/user/balance`，Bearer）：

```json
{ "is_available": true,
  "balance_infos": [{ "currency": "CNY", "total_balance": "10.03", ... }] }
```

取 `balance_infos[0].total_balance` + `currency` → 展示 `¥10.03`（或 `$` / 原值）；`is_available=false` 或缺字段 → 显「—」。解析容错：非法 JSON / 无 `balance_infos` → 显「(解析失败)」并保留原文落库便于排查。

**鉴权头**：复用渠道 `authStyle`（`bearer` → `Authorization: Bearer`；`cf_aig` → `cf-aig-authorization`）。DeepSeek 直连为 bearer。

### 9.3 `ChannelRepository` 增量

```kotlin
// ChannelRow 加：balanceUrl, balanceJson, balanceCheckedAt
// ChannelInput 加：balanceUrl（表单可选）
// 新增 balanceConfig(id): Triple<url, token, authStyle>? （明文 token，仅供 balance 服务）
// create/update 写 balance_url；list/get 读三新列
```

### 9.4 路由

| 方法 | 路径 | 行为 |
|---|---|---|
| `GET` | `/admin/channels` | `list()` + `channelUsage()` + 各渠道 `cached(id)` → 渲染 |
| `POST` | `/admin/channels/{id}/refresh-balance` | `balanceService.refresh(id)` → 重定向 `/admin/channels`（失败不报错，列表显「—」） |

### 9.5 UI（`channelsPage`）

表头加列：`消耗(调用/Token/¥)`、`余额`、操作列加「刷新余额」按钮。
- 余额格：`cached.display` + 下方小字 `checkedAt`（`fmtTs`）；无 balance_url 的渠道显「—」、不渲染刷新按钮。
- 渠道表单（`channelFormPage`）加 `balance_url` 文本框，placeholder `https://api.deepseek.com/user/balance`，标注「留空=不支持余额查询」。

### 9.6 DeepSeek 回填（`Migrations.backfillBalanceUrls()`）

```kotlin
// 仿 backfillDefaultModels()：每版启动跑一次
// 对 name=="DeepSeek 直连" 或 baseUrl 含 "deepseek.com" 且 balance_url 为空的行，补 balance_url
CHANNEL_BALANCE_URL = mapOf("DeepSeek 直连" to "https://api.deepseek.com/user/balance")
```

---

## 10. 错误处理

| 场景 | 响应 | 对齐 |
|---|---|---|
| `limit` 非非负整数 | 400（设置页/详情页回显错误） | `parseChannelInput` 校验风格 |
| 重置/改上限 `id` 不存在 | 静默 no-op → 重定向 | `/admin/users/{id}/revoke` |
| 余额上游超时/非 2xx/异常 | 不覆盖旧缓存，列表显「—」 | 服务端日志 `picme-llm` |
| 余额响应解析失败 | 落原文、显「(解析失败)」 | — |
| `balance_url` 空 | 不发起请求，显「—」 | — |

---

## 11. 测试（`src/test/kotlin/.../`，复用 `TestDb`）

- **`AccountServiceAdminLifecycleTest`**（追加）：`resetQuota` 清零且不改 limit、不改 `llm_call_log`；`setLimit` 命中/校验非法负值抛 `IllegalArgumentException`。
- **`GuestServiceTest`**（追加）：`resetQuota(id)` 清零。
- **`AdminQueriesTest`**（追加）：`overview` 的累计字段（totalUsers 排除 deleted、totalDevices、全量 tokens/cost）；`channelUsage` 按 provider 聚合正确、无调用渠道不在 map。
- **`AdminRoutesTest`**（追加）：
  - `POST /admin/users/{id}/reset-quota`、`/limit`、`/devices/{id}/reset-quota` 未登录跳登录、登录后重定向且 DB 生效
  - `GET/POST /admin/settings` 登录后 round-trip（改值后 `SettingsService.snapshot()` 反映）
  - `POST /admin/channels/{id}/refresh-balance` 登录后重定向（用 stub `ChannelBalanceService` 或 mock 上游）
- **新增 `ChannelBalanceServiceTest`**：DeepSeek 形态响应解析 → `¥10.03`；缺 `balance_infos`/非法 JSON → 「(解析失败)」；`is_available=false` → 「—」。
- **新增 `SettingsServiceTest`**：`load`/`update` round-trip；播种后 `snapshot` 反映 env 初值。

---

## 12. 文档同步（与本实现同原子提交）

- **`server/AGENTS.md`** 第 3 节路由清单补：
  - `GET /admin/settings` / `POST /admin/settings` — 全局额度默认值（ADMIN_TOKEN）
  - `POST /admin/users/{id}/reset-quota` — 清零已用额度（ADMIN_TOKEN）
  - `POST /admin/users/{id}/limit` — 改单用户上限（ADMIN_TOKEN）
  - `POST /admin/devices/{id}/reset-quota` — 清零访客额度（ADMIN_TOKEN）
  - `POST /admin/channels/{id}/refresh-balance` — 刷新上游余额（ADMIN_TOKEN）
  - 第 4/5 节补「额度默认值持久化于 `server_setting` 表，env 仅作首次播种」
- **`docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`**：管理后台章节同步上述能力。
- 参考 DDL：新增 `migrations/007_server_setting.sql`、`migrations/008_llm_channel_balance.sql`。

---

## 13. 风险与注意

- **热路径 DB 读**：`SettingsService` 用 `@Volatile` 快照，`LlmRoute` 读额度**不增 SQLite 读**，不放大 pool=1 锁竞争。
- **写后读一致性**：设置页 `update` 在同一事务内 UPSERT 并重灌快照，立即对后续请求生效。
- **余额外部调用**：仅手动触发、短超时（8s）、失败不覆盖旧缓存；不会拖慢列表页加载（页面只读缓存列）。
- **余额隐私/安全**：用渠道已存明文 token 调上游，密钥不出服务端；与 `LlmProxy` 转发同级，无新增泄露面。
- **全量扫描成本**：`overview` / `channelUsage` 全量扫 `llm_call_log`，当前研究项目规模毫秒级；将来量大可物化到 `llm_daily_counter`（需先补写入路径）。
- **签名简化**：去掉 3 个 Int 参数会触及 `authRoute`/`llmRoute`/`adminRoute` 签名与 `Application.kt` 调用处，注意测试中直接构造路由的地方同步改参。
- **累计用户口径**：排除 deleted；若日后要「历史所有注册」，改一个 `where` 即可。
