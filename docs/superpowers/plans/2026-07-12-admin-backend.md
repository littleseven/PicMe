# PoLang 管理后台 Implementation Plan

> **状态**：✅ 已完成（server v0.5.0+ 已落地）
> **实现位置**：`server/src/main/kotlin/com/mamba/picme/server/admin/`
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `server/` 内新增 LLM 用量采集 + 服务端渲染 HTML 管理后台，让运营者可看用户邮箱、token 使用、总流量。

**Architecture:** 新增 `llm_call_log` 调用日志表作为唯一事实源；`LlmProxy` 解析上游 `usage`，`UsageRecorder` 写日志 + 估算成本；Ktor `respondHtml` 渲染 4 个后台页面；固定 `ADMIN_TOKEN` + cookie 认证，主 app-token 拦截器对 `/admin/` 前缀放行。

**Tech Stack:** Ktor 3.0.3 + Exposed 0.55.0 + SQLite + kotlinx.html（`ktor-server-html-builder`）+ JUnit4 + ktor-server-test-host。

**Spec:** `docs/superpowers/specs/2026-07-12-admin-backend-design.md`

---

## File Structure

| 文件 | 责任 |
|---|---|
| `build.gradle.kts` | 加 `ktor-server-html-builder` + test 依赖 |
| `db/Tables.kt` | `LlmCallLogs` 表对象 |
| `db/Migrations.kt` | 纳入 `SchemaUtils.create` |
| `analytics/TokenUsage.kt` | `TokenUsage` 数据类 + 上游 bytes 解析 + 成本计算 |
| `analytics/UsageRecorder.kt` | 写 `llm_call_log` 一行 |
| `auth/AccountService.kt` | 新增 `idForTokenHash()` |
| `config/AppConfig.kt` | `adminToken` + 单价表 |
| `llm/LlmProxy.kt` | `ProxyResult.Success` 带 usage/model/provider |
| `llm/LlmRoute.kt` | 四条出口路径都记日志 |
| `admin/AdminAuth.kt` | cookie 生成/校验 |
| `admin/AdminQueries.kt` | 概览/用户/详情/流量 聚合查询 |
| `admin/AdminViews.kt` | kotlinx.html 渲染 |
| `admin/AdminRoutes.kt` | 登录 + 4 页路由 + cookie 拦截 |
| `Application.kt` | 主拦截器放行 `/admin/`；挂载 admin 路由组；注入 adminToken/prices |
| `.env.example` | `ADMIN_TOKEN`、`LLM_PRICES_JSON` 示例 |
| `migrations/002_llm_call_log.sql` | 参考 DDL（运行时由 Exposed 建表） |
| `src/test/...` | 测试基建 + 用例 |

## Tasks

### Task 1: 构建依赖与测试基建
- [ ] `build.gradle.kts`：`implementation("io.ktor:ktor-server-html-builder:$ktorVersion")`；test 依赖 `junit:junit:4.13.2`、`org.jetbrains.kotlin:kotlin-test`、`io.ktor:ktor-server-test-host:$ktorVersion`、`io.ktor:ktor-client-mock:$ktorVersion`、`org.slf4j:slf4j-simple`（测试日志）。
- [ ] `gradlew -p server test` 能跑（0 用例也 OK），验证依赖可解析。
- [ ] commit。

### Task 2: `llm_call_log` 表 + 迁移
- [ ] `db/Tables.kt` 加 `LlmCallLogs`（见 spec §4 DDL）；`db/Migrations.kt` `SchemaUtils.create(...)` 末尾加 `LlmCallLogs`。
- [ ] `migrations/002_llm_call_log.sql` 写参考 DDL。
- [ ] 测试：临时 SQLite `Db.init(tmpFile)` + `SchemaUtils.create(LlmCallLogs)` 成功，插入/查询一行。
- [ ] commit。

### Task 3: `TokenUsage` 解析 + 成本计算（纯逻辑）
- [ ] `analytics/TokenUsage.kt`：`data class TokenUsage(prompt, completion, total)`；`fun fromUpstreamBytes(bytes): TokenUsage?`；`fun costCny(usage, model, prices): Double`；`Price(inPerMillion, outPerMillion)`；`defaultPrices()`。
- [ ] 测试：正常 JSON / 无 usage / 异常 JSON / 缺字段；成本计算含未知模型=0。
- [ ] commit。

### Task 4: AppConfig 增项
- [ ] `config/AppConfig.kt`：加 `adminToken`、`llmPrices: Map<String, Price>`；`load()` 读 `ADMIN_TOKEN`、`LLM_PRICES_JSON`（JSON 失败走 `defaultPrices()`）。
- [ ] 测试：默认值、JSON 覆盖。
- [ ] commit。

### Task 5: `AccountService.idForTokenHash`
- [ ] 加 `suspend fun idForTokenHash(tokenHash): Int?`。
- [ ] 测试：命中/未命中。
- [ ] commit。

### Task 6: `UsageRecorder.log`
- [ ] `analytics/UsageRecorder.kt`：`suspend fun log(accountId, model, provider, usage, respBytes, status, latencyMs, prices, now)` insert 一行（cost 复用 Task3）。
- [ ] 测试：插入 4 类 status 行字段正确。
- [ ] commit。

### Task 7: LlmProxy 解析 usage + LlmRoute 接线
- [ ] `llm/LlmProxy.kt`：`ProxyResult.Success` 加 `model/provider/usage`；两个 `forwardTo*` 用 `TokenUsage.fromUpstreamBytes(resp.bodyAsBytes())` 填充。
- [ ] `llm/LlmRoute.kt`：四条路径调 `UsageRecorder.log`（需 `accountId = AccountService.idForTokenHash(tokenHash)`；主拦截器已保证 tokenHash 有效，account_id 必非空）。
- [ ] `Application.kt`：把 `config.llmPrices` 传给 llmRoute。
- [ ] 测试：`LlmProxy` 用 `ktor-client-mock` mock 上游带 usage 响应 → forward 返回 usage 正确；route 记一行（testApplication + 内存 SQLite）。
- [ ] commit。

### Task 8: `AdminAuth` cookie
- [ ] `admin/AdminAuth.kt`：`COOKIE_NAME`、`expectedCookieValue(adminToken)=sha256`、`isValid(cookie, adminToken)`（空 token → false）。
- [ ] 测试：正确放行 / 错误拒绝 / 空 token 拒绝。
- [ ] commit。

### Task 9: `AdminQueries` 聚合
- [ ] `admin/AdminQueries.kt`：`overviewToday()`、`dailySeries(days, now)`、`usersList()`、`userDetail(id)`、`recentCalls(id, limit)`；用 Exposed 聚合（sum/count/max + groupBy）；日桶在 Kotlin 按 `epochDay` 分。
- [ ] 测试：临时 SQLite 造数据 → 各查询数值正确。
- [ ] commit。

### Task 10: `AdminViews` 渲染
- [ ] `admin/AdminViews.kt`：`overviewPage / usersPage / userDetailPage / trafficPage / loginPage`（kotlinx.html），内联 SVG 折线/柱图 helper，4 维度全覆盖。
- [ ] 测试：渲染字符串含关键标签/数值（轻量断言）。
- [ ] commit。

### Task 11: `AdminRoutes` + `Application` 接线 + 集成测试
- [ ] `admin/AdminRoutes.kt`：`GET/POST /admin/login`、`/admin/logout`、`/admin`、`/admin/users`、`/admin/users/{id}`、`/admin/traffic`；组内 intercept 校验 cookie（login/logout 放行）；`adminToken` 空 → 全部 503。
- [ ] `Application.kt`：主 app-token 拦截器对 `uri.startsWith("/admin/")` 放行；`routing { adminRoute(config.adminToken) }`。
- [ ] 集成测试：未登录 GET /admin → 302 登录；POST login 正确密码 → 200+cookie；带 cookie GET /admin → 200 含概览。
- [ ] commit。

### Task 12: 收尾
- [ ] `.env.example` 加 `ADMIN_TOKEN=`、`LLM_PRICES_JSON=` 注释；`build.gradle.kts` version `0.4.0 → 0.5.0`。
- [ ] `gradlew -p server clean installDist` 全量构建通过；`gradlew -p server test` 全绿。
- [ ] 手动 E2E：`run-local.sh start` → 注册（dev 验证码看日志）→ 带打一次 `/v1/chat/completions`（mock 上游或真实）→ 登录 `/admin` 看到 1 条调用。
- [ ] 更新 `server/README.md` 管理后台小节；commit。

## Self-Review（写完后执行）
- **Spec coverage**：spec §3 每个文件 → 任务可指；§5 四条路径 → Task7；§6 认证 → Task8/11；§7 四页 → Task9/10/11；§10 测试 → 各任务内。无遗漏。
- **Placeholder**：无 TBD；单价默认值在 Task3 `defaultPrices()` 内具体化。
- **Type consistency**：`TokenUsage(prompt/completion/total)`、`UsageRecorder.Price(inPerMillion/outPerMillion)`、`AdminAuth.COOKIE_NAME="picme_admin"` 全程一致。
