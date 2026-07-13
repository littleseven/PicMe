# AI 网关开关 / 渠道管理设计（Admin AI Gateway Switch）

- **日期**：2026-07-13
- **服务端版本**：0.5.0 → 0.6.0
- **状态**：已与用户对齐，待实施
- **范围**：在 `server/` 管理后台新增「渠道（Channels）」管理，支持运行时切换 AI 上游（TokenHub / CloudFlare 两个网关 + DeepSeek / GLM / Kimi 三个直连供应商），无需重启

---

## 1. 背景与现状

服务端 `LlmProxy`（`server/src/main/kotlin/com/mamba/picme/server/llm/LlmProxy.kt`）当前硬编码两个 provider：

- `CLOUDFLARE`（Cloudflare AI Gateway，DeepSeek）
- `TOKENHUB`（腾讯 TokenHub）

路由方式：

- `MODEL_ROUTES` 映射表按请求的 `model` 字段自动选 provider；
- `FORCE_PROVIDER` env 可全局强制（`cloudflare` / `tokenhub` / 空 = 自动）。

**关键缺口**：

| 诉求 | 现状 | 结论 |
|---|---|---|
| 运行时切换上游 | `forceProvider` 仅在 `AppConfig.load()` 启动时读一次 env，`LlmProxy` 构造后**不可变**；改 `FORCE_PROVIDER` 需重启 | **无运行时开关** |
| 直连大模型供应商 | 仅 2 个网关；无 DeepSeek / GLM(智谱) / Kimi(Moonshot) 直连路径 | **不存在** |
| 后台配置入口 | 管理后台（`/admin`）仅有 概览 / 用户 / 流量 三个**只读**页面，无设置页；DB 无任何运行时可变配置表 | **不存在** |
| 凭据管理 | URL/Token 全在 env，加减供应商需改 env + 重启 | 不灵活 |

→ 本设计：DB 化渠道配置 + 后台 CRUD + 内存 holder 热切换。

## 2. 已锁定决策

| 决策点 | 选择 |
|---|---|
| 开关形态 | **完整渠道管理**：后台可增删/编辑每个渠道（名称 + BaseURL + API Token + 模型映射 + 鉴权方式），并选一个为「当前生效」；凭据存 DB |
| 模型映射 | **透明映射**：每个渠道配 `请求模型名 → 上游模型名` map；App 始终请求 `deepseek-chat` / `kimi-k2.6` 等固定名，切换渠道对 App 完全透明（沿用现有 `MODEL_ALIASES` 思路，按渠道扩展） |
| Token 存储 | **明文存 SQLite，UI 默认掩码**（`••••last4`，点「显示」可看，编辑即覆盖）。与现有 env 明文同等风险 |
| 运行时读取 | **内存 holder + DB 真相**：`ChannelRegistry` 持 `@Volatile active`，启动从 DB 加载，后台改动后 `reload()`；热路径零 DB 查询 |
| 流式 | **保持强制 `stream=false`**（与现状一致；usage 解析依赖完整响应体）。本期非目标 |
| 鉴权方式 | 每渠道配 `auth_style`：`bearer`（`Authorization: Bearer`） \| `cf_aig`（`cf-aig-authorization: Bearer`）。覆盖全部 5 个已知渠道 |
| 失败转移 | **不做**（v1）。生效渠道上游报错即回传 `upstream_error`，不自动切到别的渠道 |
| 路由粒度 | **单一全局生效渠道**。不做按用户/按模型分流 |

## 3. 架构总览

全部改动落在 `server/` 模块，同一二进制。

| 文件 | 动作 | 职责 |
|---|---|---|
| `db/Tables.kt` | 新增 `LlmChannels` | `llm_channel` 表定义 |
| `db/Migrations.kt` | 改 | `SchemaUtils.create` 纳入 `LlmChannels`；首次启动播种 5 个渠道 |
| `llm/ChannelConfig.kt` | 新增 | 渠道运行时配置 data class |
| `llm/ChannelRepository.kt` | 新增 | DB 读写：CRUD + `setActive` + `loadActive` |
| `llm/ChannelRegistry.kt` | 新增 | 内存 holder（`@Volatile active` + `reload()`），热路径读取 |
| `llm/LlmProxy.kt` | 改 | 重构为读 `ChannelRegistry.active`；删除 `LlmProvider` 枚举 / `MODEL_ROUTES` / `MODEL_ALIASES` / `TOKENHUB_MODELS` / `forceProvider` / 两个 `forwardTo*` |
| `llm/LlmRoute.kt` | 改 | `provider` 字段由枚举 `.name` 改为字符串（渠道名） |
| `admin/AdminRoutes.kt` | 改 | 新增 `/admin/channels` 路由组（GET 列表 + POST 增/改/激活/启停/删） |
| `admin/AdminViews.kt` | 改 | 新增渠道页渲染 + 导航「渠道」链接；复用现有 CSS |
| `config/AppConfig.kt` | 改 | `cloudflare*` / `tokenhub*` / `forceProvider` 字段降级为「仅首次播种用」 |
| `Application.kt` | 改 | `LlmProxy` 构造简化；启动调 `ChannelRegistry.reload()` |
| `.env.example` | 改 | 三个 env 标注「仅首次播种用」 |
| `migrations/003_llm_channel.sql` | 新增 | 参考 DDL（运行时由 Exposed 建表，文档一致性） |

**无新增依赖**：`ktor-server-html-builder`、`exposed-*`、`sqlite-jdbc` 均已具备。

## 4. 数据模型 — 新增 `llm_channel` 表

```sql
CREATE TABLE llm_channel (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name            TEXT    NOT NULL,                 -- ≤32 字符；同时写入 llm_call_log.provider（唯一约束见下）
  kind            TEXT    NOT NULL,                 -- 'gateway' | 'direct'
  base_url        TEXT    NOT NULL,                 -- 完整 chat-completions URL
  auth_style      TEXT    NOT NULL DEFAULT 'bearer',-- 'bearer' | 'cf_aig'
  api_token       TEXT    NOT NULL DEFAULT '',      -- 明文；UI 掩码
  model_map_json  TEXT    NOT NULL DEFAULT '{}',    -- {"deepseek-chat":"glm-4.6", ...}
  enabled         INTEGER NOT NULL DEFAULT 1,
  is_active       INTEGER NOT NULL DEFAULT 0,       -- 不变量：≤ 一个为 1
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_llm_channel_name ON llm_channel(name);
```

对应 Exposed 表对象 `LlmChannels : Table("llm_channel")`。

**设计约束**：

- `llm_call_log.provider`（现有 `varchar(32)`）**不改宽度**：现在存渠道 `name`。保存时校验 `name ≤ 32` 字符，避免溢出；现有后台 UI 直接打印 `c.provider` 的代码无需改动。
- **不变量「≤ 一个 `is_active=1`」**由 `ChannelRepository.setActive(id)` 在事务内保证：先 `UPDATE llm_channel SET is_active=0`，再 `SET is_active=1 WHERE id=? AND enabled=1`。
- `model_map_json` 存 JSON 字符串，运行时解析为 `Map<String,String>`；后台编辑用「每行 `请求名=上游名`」文本，保存时转 JSON。
- `account` / `llm_call_log` 等表**不动**；生产 DB 仅新增一张表，由 `SchemaUtils.create` 幂等建表，**零数据迁移风险**。

## 5. 运行时链路（数据流）

```
/v1/chat/completions   （主拦截器已确保 tokenHash 在 call.attributes）
  ├ rate_limit 命中?                        → 429（不 forward）
  ├ checkAndIncrementQuota 失败?            → 403 blocked_quota
  └ proxy.forward(clientIp, body)
       val channel = ChannelRegistry.active()
       ├ channel == null                    → Error(503, no_active_channel,        log=no_active_channel)
       ├ requestedModel 缺失                → Error(400, missing model,            log=bad_request)（同现状）
       ├ channel.modelMap[requestedModel] == null
       │                                    → Error(400, unsupported_model+supported[], log=unsupported_model)
       ├ max_tokens 超 cap                  → Error(400, max_tokens exceeds,        log=bad_request)（同现状）
       ├ channel.apiToken.isBlank()         → Error(500, channel_token_missing,    log=channel_token_missing)
       └ forwardToChannel(channel, body, upstreamModel)
            POST channel.baseUrl
              header by auth_style:
                bearer → Authorization: Bearer ${channel.apiToken}
                cf_aig → cf-aig-authorization: Bearer ${channel.apiToken}
            payload: body 复制 + model=upstreamModel + stream=false
            → Success(status, bytes, model=upstreamModel, provider=channel.name, usage)
              ├ 上游 2xx → UsageRecorder.log(status=ok, provider=channel.name) → 回传 bytes
              └ 上游 4xx/5xx → revertQuota + log(upstream_error) → 回传错误体
```

**实现要点**：

- **`ChannelConfig`**（data class）：`name, kind, baseUrl, authStyle, apiToken, modelMap: Map<String,String>`。
- **`ChannelRegistry`**（object，仿 `AccountService` 模式）：
  - `@Volatile private var active: ChannelConfig? = null`
  - `fun active(): ChannelConfig?` — 热路径读 volatile 引用，**零 DB**（关键：HikariCP `maximumPoolSize=1`，热路径查 DB 会成瓶颈）。
  - `fun reload()` — 从 DB 读 `is_active=1 AND enabled=1` 的渠道 → 写入 `active`。启动时调一次；后台每次写渠道后调一次。
- **`LlmProxy` 重构**：
  - 构造参数收缩为 `(httpClient, maxTokensCap)`。原 `cloudflareUrl/cloudflareAigToken/tokenhubUrl/tokenhubApiToken/forceProvider` 全部移除（迁入 `ChannelConfig`）。
  - `forward()` 改为读 `ChannelRegistry.active()`，按上数据流分支。
  - **删除**：`LlmProvider` 枚举、`MODEL_ROUTES`、`MODEL_ALIASES`、`TOKENHUB_MODELS`、`resolveProvider()`、`resolveUpstreamModel()`、`forwardToCloudflare()`、`forwardToTokenhub()`。统一为 `forwardToChannel(channel, body, upstreamModel)`。
- **`ProxyResult.Success.provider`**：类型 `LlmProvider` → `String`（渠道名）。`LlmRoute.kt` 中 `result.provider.name` → `result.provider`。
- **`ProxyResult.Error` 增 `logStatus: String`**（默认 `"upstream_error"`）：`LlmRoute` 的 Error 分支改用 `result.logStatus` 写 `llm_call_log.status`（原来硬编码 `"upstream_error"`）。新增错误带精确 status：`no_active_channel` / `unsupported_model` / `channel_token_missing`（既有 `missing model` / `max_tokens` 走 `bad_request`）。后台「blocked」统计仍按 `status != 'ok'` 聚合，不受影响；用户详情页能区分错误类型。
- **`Application.kt`**：`LlmProxy(httpClient, config.maxTokensCap)`；在 `Migrations.run()` 之后调 `ChannelRegistry.reload()`。

## 6. 渠道管理后台页面（SSR HTML）

新增 `/admin/channels` + 导航「渠道」链接（`AdminViews.navBar()`）。kotlinx.html 渲染，复用现有 `.card/.btn/table` CSS，新增少量 `<form>` 行内样式。零前端构建、无 CDN。

### 路由

| 方法 路径 | 作用 |
|---|---|
| `GET /admin/channels` | 渠道列表表 + 顶部「新增渠道」表单 |
| `POST /admin/channels` | 新建渠道 |
| `POST /admin/channels/{id}` | 更新渠道（token 空 = 保持原值；非空 = 覆盖） |
| `POST /admin/channels/{id}/activate` | 设为生效 |
| `POST /admin/channels/{id}/toggle` | 启用/停用（停用生效渠道 → 清 `is_active` → 503 直到切换） |
| `POST /admin/channels/{id}/delete` | 删除（生效渠道拒绝删除，提示先切换） |

每次 POST 写 DB 后调 `ChannelRegistry.reload()`。

### 列表表

| 名称 | 类型 | BaseURL | Token | 状态 | 生效 | 操作 |
|---|---|---|---|---|---|---|
| Cloudflare | gateway | `https://gateway.ai.cloudflare.com/...` | `••••a1b2` | 启用 | ✅生效中 | 编辑 / 设为生效 / 停用 / 删除 |
| DeepSeek 直连 | direct | `https://api.deepseek.com/v1/...` | `••••c3d4` | 启用 | — | 编辑 / 设为生效 / 停用 / 删除 |

### 编辑/新增表单字段

- `name`（text，必填，≤32，唯一）
- `kind`（select：gateway / direct）
- `base_url`（text，必填，合法 URL）
- `auth_style`（select：bearer / cf_aig）
- `api_token`（`type=password`；编辑时占位「留空保持不变」）
- `model_map`（textarea，每行 `请求名=上游名`，如 `deepseek-chat=glm-4.6`；保存时解析为 JSON）
- `enabled`（checkbox）

### 交互细节

- **Token 显隐**：列表与表单中 token 默认 `type=password`；一个 `显示` checkbox 行内切 `type=text`（原生 JS，无框架）。
- **model_map 解析**：保存时按行解析 `key=value`，忽略空行与 `#` 注释行；解析失败 → 400 + 错误行号。回显时把 JSON map 还原为每行 `key=value`。
- **CSRF**：沿用现有 admin cookie 的 `SameSite=Lax`（已在 `AdminRoutes.login` 设置），对跨站 POST 提供基本防护；v1 不引入独立 CSRF token（与现有 admin 一致）。

## 7. 首次启动播种与向后兼容

`Migrations.run()` 在 `SchemaUtils.create(LlmChannels, ...)` 之后，若 `llm_channel` 表为空，播种 5 行：

| name | kind | base_url | auth_style | api_token 来源 | model_map | enabled |
|---|---|---|---|---|---|---|
| Cloudflare | gateway | `$CLOUDFLARE_AIG_URL`（env，缺省走 `AppConfig` 默认） | cf_aig | `$CLOUDFLARE_AIG_TOKEN` | `deepseek-chat→deepseek/deepseek-chat`、`deepseek-v4-flash→deepseek/deepseek-chat` | 1 |
| TokenHub | gateway | `$TOKENHUB_URL` | bearer | `$TOKENHUB_API_TOKEN` | **恒等映射**（请求名=上游名），覆盖下方 TokenHub 模型清单全部 ID | 1 |
| DeepSeek 直连 | direct | `https://api.deepseek.com/v1/chat/completions` | bearer | （空） | `deepseek-chat→deepseek-chat`、`deepseek-v4-flash→deepseek-chat` | 0 |
| GLM 直连 | direct | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | bearer | （空） | `deepseek-chat→glm-5.2`、`kimi-k2.6→glm-5.2` | 0 |
| Kimi 直连 | direct | `https://api.moonshot.cn/v1/chat/completions` | bearer | （空） | `kimi-k2.6→kimi-k2.7-code`、`deepseek-chat→kimi-k2.7-code` | 0 |

### TokenHub 模型清单（model_map 恒等映射：`请求名=上游名`）

TokenHub 是多模型聚合网关，已覆盖 DeepSeek / GLM / Kimi / Qwen / MiniMax / Hunyuan 全系。播种时把下列全部模型 ID 写入 TokenHub 渠道的 `model_map`（恒等映射），App 即可按模型名直接请求任意一个；TokenHub 侧的实际启用状态决定是否真可用，未启用的模型请求会回传上游错误，运营者按需在 TokenHub 控制台启用或从 map 删去。

- **运行中**：`deepseek-v4-flash-202605`、`kimi-k2.7-code`、`kimi-k2.6`
- **已停止**（额度用尽，TokenHub 侧停用）：`deepseek-v4-flash`
- **未启用**（TokenHub 侧未开通，启用后即可用）：`hy3`、`kimi-k2.7-code-highspeed`、`glm-5.2`、`minimax-m3`、`hy-role`、`deepseek-v4-pro-202606`、`hy-mt2-pro`、`hy-mt2-lite`、`hy-mt2-plus`、`hunyuan-role-latest`、`deepseek-v4-pro`、`hy3-preview`、`glm-5.1`、`glm-5v-turbo`、`minimax-m2.7`、`glm-5-turbo`、`qwen3.5-flash`、`qwen3.5-plus`、`minimax-m2.5`、`glm-5`、`kimi-k2.5`

> **直连渠道 vs TokenHub 的定位**：TokenHub 已聚合上述全部模型，故直连渠道（DeepSeek/GLM/Kimi 直连）主要用于 ① 绕开 TokenHub 免费额度限制、② 走「GLM Coding Plan」「Kimi Code」等订阅计费档、③ 取 TokenHub 未暴露的模型名（如 DeepSeek 直连提供 `deepseek-chat`，TokenHub 仅有 `deepseek-v4-*`）。三者并存，按需切换。

**生效渠道播种**：若 `FORCE_PROVIDER` env 非空（`cloudflare`→Cloudflare，`tokenhub`→TokenHub）则对应渠道 `is_active=1`；否则第一个 `enabled=1` 渠道（Cloudflare）生效。**升级后行为与现状一致**。

**向后兼容**：

- 首次播种后，`CLOUDFLARE_*` / `TOKENHUB_*` / `FORCE_PROVIDER` env **运行时不再读取**——DB 成为唯一事实源。
- `AppConfig` 保留这些字段仅供电播用；`.env.example` 三个 env 加注：「仅首次启动播种用；之后在后台 `/admin/channels` 管理」。
- 3 个直连渠道默认 `enabled=0` + 空 token：运营者在后台填 token（并按需修正 model_map / base_url——「GLM Coding Plan」「Kimi Code」属订阅计费档，API 端点与标准 OpenAI 兼容端点一致，若有差异在 UI 改）后激活。

## 8. 边界与错误处理

| 场景 | 处理 |
|---|---|
| 无生效渠道（全停用 / 生效渠道被停用） | `forward` → `ProxyResult.Error(503, {"error":"no_active_channel"})` → `LlmRoute` revertQuota（调用未耗上游）→ 503 |
| 请求模型不在生效渠道 `model_map` | `Error(400, {"error":"unsupported_model","active_channel":"...","supported":[...]})` → revertQuota → 400 |
| 生效渠道 `api_token` 为空 | `Error(500, {"error":"channel_token_missing","channel":"..."})` → revertQuota → 500 |
| 上游 4xx/5xx | 透传 status + bytes，`log(upstream_error)`，revertQuota（同现状） |
| 删除生效渠道 | 拒绝 400「先切换到其他渠道再删除」 |
| 停用生效渠道 | 清 `is_active`（无生效）→ 503 直到切换；UI 给警告 |
| `name` 重复 / 超 32 字符 | 保存时校验 → 400 + 消息 |
| `model_map` 解析失败 | 保存时 400 + 错误行号 |
| `base_url` 非法 | 保存时 400 |

**额度一致性**：所有 `ProxyResult.Error` 路径（含新增的 503/400/500）都走 `LlmRoute` 现有 `is Error → revertQuota` 分支，与现状一致，不破坏额度计数语义。

## 9. 测试

`server/src/test`（已有基建）新增/更新：

- **`LlmProxyChannelTest`**（新）：
  - 生效渠道路由：`ChannelRegistry.active()` 命中 → 正确转发；
  - `model_map` 命中 / 未命中（400 unsupported_model + supported 列表）；
  - `auth_style=bearer` → 请求带 `Authorization: Bearer`；`auth_style=cf_aig` → 带 `cf-aig-authorization: Bearer`（用 Ktor `MockEngine` 断言 header）；
  - `api_token` 空 → 500 channel_token_missing；
  - 无生效渠道 → 503 no_active_channel；
  - `max_tokens` 超 cap → 400（同现状）。
- **`ChannelRegistryTest`**（新）：
  - `reload()` 取 `is_active=1 AND enabled=1`；
  - 忽略 `enabled=0` 的渠道；
  - 无生效渠道 → `active() == null`。
- **`ChannelRepositoryTest`**（新，用 `TestDb`）：
  - CRUD；
  - `setActive(id)` 清空其他 `is_active`（≤ 一个不变量）；
  - `setActive` 拒绝 `enabled=0` 的渠道；
  - 删除生效渠道被拒；
  - `name` 唯一约束。
- **`AdminChannelsRoutesTest`**（新）：
  - GET 列表渲染（含 token 掩码、生效徽标）；
  - POST create / update / activate / toggle / delete；
  - 无 cookie → 跳 `/admin/login`；
  - token 在 HTML 中掩码（不出现明文）。
- **保持绿**：`LlmProxyUsageTest`、`AdminRoutesTest`、`AdminViewsTest`、`AdminQueriesTest`、`AppConfigTest`、`LlmCallLogsTest`——更新构造调用与 `provider` 字段类型。

## 10. 部署

- 同一二进制，沿用 `server/deploy.sh`（build → rsync `.new` → 蓝绿切换 → healthz → 失败回滚）。
- `/etc/picme/server.env` 无需新增必填项（`CLOUDFLARE_*` / `TOKENHUB_*` / `FORCE_PROVIDER` 已有，首次播种复用）。
- 新表 `llm_channel` 由 `SchemaUtils.create` 自动建 + 首次播种；**无手动迁移**。
- 重启 systemd `picme-api`；启动后登录 `/admin/channels` 校验 5 个渠道已播种、生效渠道正确。
- 版本号 `0.5.0 → 0.6.0`。

## 11. 边界与风险

- **明文 Token 落 DB**：与现有 env 明文同等风险；DB 文件访问 = env 文件访问。后台 cookie `SameSite=Lax` + `ADMIN_TOKEN` 保护；生产建议 nginx `/admin` IP 白名单或仅 SSH 隧道（沿用既有加固建议）。
- **热路径零 DB**：`ChannelRegistry` volatile 引用，切换瞬间生效；单实例部署无多实例缓存同步问题（YAGNI）。
- **`stream=false` 不变**：usage 解析依赖完整响应体；直连供应商虽支持流式，本期不引入（避免 SSE 代理 + 流式 usage 计量复杂度）。
- **无失败转移**：生效渠道上游故障即 `upstream_error`，需运营者手动切换。如需自动 failover，后续迭代。
- **直连渠道默认值需校对**：DeepSeek/GLM/Kimi 的 base_url 与 model 名为最佳猜测默认（OpenAI 兼容端点）。GLM→`glm-5.2`、Kimi→`kimi-k2.7-code` 已参照 TokenHub 目录对齐到当前代次，但各家直连 API 的确切模型字符串可能不同（如 Moonshot 直连或用 `kimi-k2-0711-preview` 式命名）；运营者首次使用前在后台核对「Coding Plan」「Code」订阅的实际端点与模型名。
- **`FORCE_PROVIDER` 语义变更**：由「运行时强制」降级为「首次播种提示」。升级后若曾依赖运行时改 `FORCE_PROVIDER`+重启切换，现在改为后台 UI 切换（行为等价、更便捷）。
