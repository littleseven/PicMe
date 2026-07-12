# PicMe 管理后台设计（Admin Backend）

- **日期**：2026-07-12
- **服务端版本**：0.4.0 → 0.5.0
- **状态**：已与用户对齐，待实施
- **范围**：在 `server/` 模块内新增「LLM 用量采集 + 管理后台」，单一二进制部署，覆盖用户邮箱、token 使用、总流量三类诉求

---

## 1. 背景与现状

服务端（Ktor 3.0.3 + SQLite/Exposed + JRE17 + systemd + nginx，公网 `https://api.polang.net`）已具备邮箱注册能力（验证码 → 下发 `picme_at_*` token）。用户希望有一个管理后台，查看：

1. 用户邮箱
2. token 使用
3. 总流量情况

**现状摸底（关键缺口）**：

| 诉求 | 现状 | 结论 |
|---|---|---|
| 用户邮箱 | `account` 表有 `email / status / created_at` | 现成 |
| token 使用 | 上游 LLM 响应的 `usage`（prompt/completion/total tokens）**从未解析**，`LlmProxy` 只透传原始字节；仅有 `account.llm_calls_used` 这个**调用次数**计数器 | 真实 token 消耗量**不存在** |
| 总流量 | `llm_daily_counter` 表（tokens/cost/blocked）是**死表**——只建表、无任何写入；亦无请求数/字节数统计 | **几乎不存在** |

→ 本设计天然分两半：**先补数据采集，再做展示**。

## 2. 已锁定决策

| 决策点 | 选择 |
|---|---|
| Token 口径 | 调用次数 **+** 真实 token 数（需新增「解析上游 usage 入库」采集） |
| 总流量维度 | ① 调用数 & 限流数 ② token 总量 & 估算成本(¥) ③ 网络出口字节 ④ 用户/注册概览（全要） |
| 后台形态 | Ktor **服务端渲染 HTML**（同二进制部署，零前端构建） |
| 管理员认证 | **固定 `ADMIN_TOKEN`**（env 配置，独立 cookie，不混入邮箱注册） |
| 数据存储 | **方案 A**：单一调用日志表 `llm_call_log` 作为唯一事实源 |

## 3. 架构总览

全部改动落在 `server/` 模块，同一二进制。

| 文件 | 动作 | 职责 |
|---|---|---|
| `db/Tables.kt` | 新增 `LlmCallLogs` | 调用日志表定义 |
| `db/Migrations.kt` | 改 | 把 `LlmCallLogs` 纳入 `SchemaUtils.create`（幂等建表） |
| `analytics/UsageRecorder.kt` | 新增 | 写日志行 + 估算成本；反查 `account_id` |
| `auth/AccountService.kt` | 改 | 新增 `idForTokenHash(hash): Int?` |
| `llm/LlmProxy.kt` | 改 | `ProxyResult.Success` 携带解析出的 `usage`、`respBytes.size`、`model`、`provider` |
| `llm/LlmRoute.kt` | 改 | 在 success / blocked_quota / blocked_rate / upstream_error 四条路径都调 `UsageRecorder` |
| `config/AppConfig.kt` | 改 | 加 `adminToken`、模型单价表（env 可覆盖） |
| `admin/AdminAuth.kt` | 新增 | cookie 校验（`picme_admin = sha256(ADMIN_TOKEN)`，HttpOnly） |
| `admin/AdminRoutes.kt` | 新增 | 登录 + 概览/用户/详情/流量 4 个路由 |
| `admin/AdminViews.kt` | 新增 | kotlinx.html 渲染函数（含内联 SVG 趋势图） |
| `Application.kt` | 改 | 主 app-token 拦截器对 `/admin/` 前缀放行；挂载 admin 路由组（组内自带 cookie 拦截） |
| `build.gradle.kts` | 改 | 加 `ktor-server-html-builder:3.0.3`；新增 test 依赖 |

**新增依赖**：`io.ktor:ktor-server-html-builder:3.0.3`（其传递依赖 `org.jetbrains.kotlinx:kotlinx-html-jvm` 已满足，无需单独声明）。

## 4. 数据模型 — 只新增一张表，不动 `account`

```sql
CREATE TABLE llm_call_log (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id        INTEGER NOT NULL,          -- 解析自 token_hash → account.id
  model             TEXT NOT NULL,             -- 请求里的 model 字段
  provider          TEXT NOT NULL,             -- CLOUDFLARE | TOKENHUB
  prompt_tokens     INTEGER,                   -- 上游 usage；失败/拦截为 NULL
  completion_tokens INTEGER,
  total_tokens      INTEGER,
  cost_cny          REAL    NOT NULL DEFAULT 0,-- 估算：单价 × tokens
  resp_bytes        INTEGER NOT NULL DEFAULT 0,-- 上游响应字节（拦截 = 0）
  status            TEXT    NOT NULL,          -- ok | upstream_error | blocked_quota | blocked_rate
  latency_ms        INTEGER,                   -- 上游耗时（可选）
  created_at        INTEGER NOT NULL
);
CREATE INDEX idx_log_account ON llm_call_log(account_id, created_at);
CREATE INDEX idx_log_time    ON llm_call_log(created_at);
```

对应 Exposed 表对象 `LlmCallLogs : Table("llm_call_log")`。

**设计约束**：

- `account` 表**不改**：`llm_calls_used` / `llm_calls_limit` 继续是「**额度计数器**」（含失败 revert 逻辑），与「**分析用量**」职责分离，避免双写一致性 bug。
- 死表 `llm_daily_counter` 暂不动（保留原状）；日聚合直接从 `llm_call_log` `GROUP BY date(created_at, 'unixepoch')` 计算。
- token 刷新（`createOrRefresh` 更新 `token_hash`）后 `account_id` 不变 → 历史归属始终正确。
- 生产 DB 只新增一张表，由 `SchemaUtils.create` 建表（幂等），**零数据迁移风险**。

> `migrations/001_init.sql` 是「参考建表脚本」（运行时由 Exposed 建表），为文档一致性可把上述 DDL 追加为 `002_llm_call_log.sql`，但不作为运行时依赖。

## 5. 采集链路（数据流）

```
/v1/chat/completions   （主 app-token 拦截器已确保 tokenHash 在 call.attributes）
  ├ rate_limit 命中?        → UsageRecorder.log(status=blocked_rate)            → 429
  ├ checkAndIncrementQuota 失败? → UsageRecorder.log(status=blocked_quota)      → 403
  └ proxy.forward(clientIp, body)
       ├ Success(usage, respBytes, model, provider)
       │    ├ 上游 2xx → UsageRecorder.log(status=ok, usage, cost)             → 回传 bytes
       │    └ 上游 4xx/5xx → AccountService.revertQuota(hash)
       │                   + UsageRecorder.log(status=upstream_error, usage=null) → 回传错误体
       └ Error（本地配置/参数错）→ revertQuota + log(upstream_error)             → 回传错误
```

**实现要点**：

- **usage 解析**：`LlmProxy` 在 `forwardToCloudflare` / `forwardToTokenhub` 拿到上游 `resp` 后，把 `bodyAsBytes()` 解析一次为 JSON，取 `usage { prompt_tokens, completion_tokens, total_tokens }`。错误响应通常无 `usage` → 记为 null。`stream` 已被强制为 `false`，正常响应必带 `usage`。
- **`ProxyResult.Success` 扩展**：新增字段 `usage: TokenUsage?`、`respBytes: ByteArray`（原即有）、`model: String`、`provider: LlmProvider`。`TokenUsage` 为 `(prompt, completion, total)` 三元组。
- **成本估算**：`UsageRecorder` 按 `AppConfig` 单价表（¥ / 1M tokens，input / output 分开）计算 `cost_cny`。
- **account_id 反查**：新增 `AccountService.idForTokenHash(hash)`，写日志前解析一次（auth 拦截器已保证 tokenHash 有效）。
- **写入时机**：在 `llmRoute` 内，每条出口路径（成功/两类拦截/上游错误）都写一行；blocked 路径 `resp_bytes=0`、`usage=null`。

## 6. 管理员认证

- env 新增 `ADMIN_TOKEN`（强随机串，必填；空则后台禁用并打日志告警）。
- **登录流程**：
  - `GET /admin/login` → 密码表单。
  - `POST /admin/login` → 校验 password == `ADMIN_TOKEN` → 设 cookie `picme_admin = sha256(ADMIN_TOKEN)`（`HttpOnly`、`Path=/admin`）→ 302 跳 `/admin`；失败回 401。
  - `POST /admin/logout` / `GET /admin/logout` → 清 cookie → 跳登录。
- **拦截**：admin 路由组内 route-level intercept，对除 `/admin/login`、`/admin/logout` 外的所有 `/admin/**` 校验 cookie == `sha256(ADMIN_TOKEN)`，不符 → 跳 `/admin/login`。
- **与主 auth 解耦**：主 app-token 拦截器（`Application.module` 内）对 `/admin/` 前缀**放行**（在 `publicRoutes` 旁加前缀判断），由 admin 组自己的 cookie 拦截接管，两套认证互不污染。
- **暴露加固（强烈建议，spec 列为可选）**：`api.polang.net` 是公网。默认仅 token 保护；建议二选一：
  - nginx `location /admin { allow <管理员IP>; deny all; proxy_pass ...; }`，或
  - 仅通过 SSH 隧道访问（`ssh -L 8080:127.0.0.1:8080 ubuntu@43.161.201.142` 后开 `http://127.0.0.1:8080/admin`）。

## 7. 后台页面（SSR HTML，零前端构建）

kotlinx.html 渲染，内联 SVG 趋势图（无 CDN、离线可用）。4 个视图：

1. **概览 `GET /admin`**
   - 今日 stat 卡片：总用户数 / 今日新增 / 今日调用 / 今日 token 总量 / 今日估算成本(¥) / 今日出口字节 / 今日 blocked 数。
   - 近 14 天双折线（SVG）：调用数 vs token 成本。

2. **用户列表 `GET /admin/users`**
   - 表：email / 状态 / 注册时间 / 累计调用（log 计 `status=ok`）/ 累计 token / 累计成本 / 最后活跃。
   - 行链接到 `/admin/users/{id}`。

3. **用户详情 `GET /admin/users/{id}`**
   - 该用户累计汇总 + 最近 N（默认 50）条 `llm_call_log` 明细 + 近 14 天个人趋势。

4. **流量 `GET /admin/traffic`**
   - 按天表 + 柱图（SVG）：调用 / blocked / prompt+completion token / 成本(¥) / 出口字节，近 30 天。

**四个流量维度映射**：① 调用&限流 → status 维度计数；② token&成本 → `*_tokens` + `cost_cny`；③ 出口字节 → `resp_bytes`；④ 用户/注册概览 → `account` 表 + log 的活跃聚合。全覆盖。

## 8. 配置（AppConfig 增项）

```kotlin
val adminToken: String,            // ADMIN_TOKEN，空则后台禁用
// 模型单价：¥ / 1M tokens（input / output），env 可覆盖；默认值在代码常量
// 以 env 形如 LLM_PRICE_DEEPSEEK_CHAT_IN=1.0 / ...=2.0 读取，缺失走内置默认
```

- `adminToken = env("ADMIN_TOKEN", "")`。
- 单价表：内置默认（DeepSeek-chat / deepseek-v4-flash / kimi-k2.6 / kimi-k2.7-code 各一组 in/out 单价），允许通过 `LLM_PRICE_*` env 覆盖。缺模型走 0 成本（仅 token 计数）。
- `/etc/picme/server.env` 增 `ADMIN_TOKEN=...`（部署时填）。

## 9. 部署

- 同一二进制，沿用现有 `server/deploy.sh`（build → rsync `.new` → 蓝绿切换 → healthz → 失败回滚）。
- `/etc/picme/server.env` 增 `ADMIN_TOKEN`，可选 `LLM_PRICE_*`。
- nginx（可选加固）`/admin` IP 白名单。
- 重启 systemd `picme-api`；新表由 `SchemaUtils` 自动建。
- 版本号 `0.4.0 → 0.5.0`。

## 10. 测试（新引入测试基建）

`server/src/test` 当前不存在，本设计**首次引入**服务端单测：

- **新增 test 依赖**（`build.gradle.kts`）：`junit:junit:4.13.2`（或 kotlin.test）、`io.ktor:ktor-server-test-host:3.0.3`、内存/临时文件 SQLite（复用 `org.xerial:sqlite-jdbc`，DB 指向 `:memory:` 或临时文件）。
- **单测覆盖**：
  - usage 解析：正常响应 / 无 usage / 异常 JSON / 缺字段 → `TokenUsage?` 正确。
  - 成本计算：单价 × tokens 正确，未知模型 → 0。
  - `UsageRecorder.log`：四类 status 各写一行，字段正确。
  - `AccountService.idForTokenHash`：命中 / 未命中。
  - admin cookie 校验：正确 cookie 放行 / 错误 cookie 跳登录 / 空白名单。
  - 聚合 SQL（临时 SQLite）：日聚合、用户累计数值正确。
- **端到端**：`run-local.sh` 起服务 → 邮箱注册 → 打一次 `/v1/chat/completions`（mock 或真实上游）→ 登录 `/admin` 看到 1 条调用、token 数、成本。

## 11. 边界与风险

- `stream=false` 已强制 → 正常响应必带 `usage`；上游 4xx/5xx / 超时 → `usage=null`，记 `upstream_error`，额度按现有逻辑 revert。
- 成本为**估算**（单价可配、随上游调价漂移），后台标注「估算」。
- SQLite 单进程 insert，试用量级无锁竞争；量级显著增大后再考虑日聚合物化表（YAGNI，本期不做）。
- 后台可见邮箱 + 用量，属运营者数据，无额外 PII 外泄（邮箱本已存储）。
- `ADMIN_TOKEN` 为空时后台路由返回 503/禁用页，并 ERROR 日志告警，避免无保护公网暴露。
