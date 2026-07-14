# PoLang Server (Ktor)

App 后端单体，支撑「推荐拍照 + 图片优化 + LLM 代理」。Monorepo 子工程，**独立 Gradle build，不纳入安卓 `settings.gradle.kts`**。

## 现状（v0.5.0）

| 路由 | 优先级 | 状态 | Auth | 说明 |
|------|--------|------|------|------|
| `GET /healthz` | P0 | ✅ | 无 | 存活探活 |
| `POST /recommend` | P0 | ✅ | X-App-Token | 场景标签 → 参数包（纯规则，规避算法备案） |
| `POST /telemetry` | P0 | ✅ | X-App-Token | 批量匿名遥测 |
| `POST /v1/chat/completions` | P0 | ✅ | X-App-Token | LLM 代理（Cloudflare AI Gateway / TokenHub 按模型自动路由） |
| `POST /chat/completions` | P0 | ✅ | X-App-Token | 旧路径兼容（同上） |
| `GET /assets/{manifest,url}` | P1 | 🚧 | X-App-Token | 腾讯 COS 预签名下发 —— 待实现 |
| `GET /agent/config` | P1 | 🚧 | X-App-Token | 供应商适配参数下发 —— 待实现 |

> **v0.5.0 变更**：
> - 新增管理后台 `/admin`（SSR HTML，固定 `ADMIN_TOKEN` + cookie 认证）：用户邮箱、token 用量、调用/限流/token 成本/出口字节/注册概览
> - LLM 用量采集：每次 `/v1/chat/completions` 写 `llm_call_log`，解析上游 `usage` 记录真实 token + 估算成本
>
> **v0.4.0 变更**：
> - 邮箱注册动态 Token 认证替代静态 X-App-Token：`/auth/email/send` → 验证码 → `/auth/email/verify` 换取 `picme_at_*` token
>
> **v0.3.0 变更**：
> - 新增 LLM 代理 `/v1/chat/completions`，移植 `infra/tencentscf/index.js` 逻辑（模型路由 + 限流 + 密钥托管）
> - 老客户端继续走 SCF（并行运行），新客户端切换到 `api.polang.net/v1/chat/completions`
>
> **架构定位**：Server 是**配置中心 + LLM 代理 + 分发管道 + 遥测收集**，不做 Agent 编排（ReAct 循环在客户端）。

## 认证

客户端 API 走**邮箱注册动态 Token**：App 调 `POST /auth/email/send`（发验证码）→ `POST /auth/email/verify`（校验码换 `picme_at_*` token）；此后所有受保护接口带 `X-App-Token: <picme_at_*>` header，服务端按 `account` 表的 `token_hash`（SHA-256）校验。`/healthz`、`/auth/email/send`、`/auth/email/verify` 三条路径免鉴权。每账户有免费 LLM 试用额度（`FREE_LLM_QUOTA`，默认 100 次），用尽返回 403。

管理后台 `/admin/**` **不走** X-App-Token，用固定 `ADMIN_TOKEN` + cookie 认证（见上「管理后台」小节）。

## 本地开发
```bash
./server/run-local.sh start        # 便捷：后台启动 + 等就绪 + 打印 curl 命令（stop/restart/run/status/logs 见脚本 -h）
# 或裸启动：
./gradlew -p server run            # 起 127.0.0.1:8080（前台）
curl http://127.0.0.1:8080/healthz
```
配置走环境变量（见 `.env.example`）；`run-local.sh` 默认 DB 落 `server/build/picme.db`，不污染源码树。

## 构建
```bash
./gradlew -p server installDist    # → build/install/picme-server/bin/picme-server
```

## 部署
`deploy.sh`（开发机构建 installDist → rsync 到 `~/picme-server.new/` → ssh 触发切换）+ `deploy-switch.sh`（服务器端**蓝绿**：备份现网 → 切换 → `systemctl restart` → healthz 校验 → **失败自动回滚**）。OpenClaw 可直接 `bash ~/deploy-switch.sh` 实现「一句话发布」（指令见 `OPENCLAW_DEPLOY.md`）。systemd `picme-api.service`：`JAVA_OPTS=-Xmx256m` + `MemoryMax=450M`，与 OpenClaw 共享 2G 盒子。

## 管理后台（v0.5.0）

运营者用的 SSR HTML 后台（kotlinx.html，同二进制部署，零前端构建）。访问 `https://api.polang.net/admin`。

| 页面 | 路径 | 内容 |
|------|------|------|
| 登录 | `/admin/login` | 输入 `ADMIN_TOKEN` 登录 |
| 概览 | `/admin` | 今日 stat 卡片（用户/新增/调用/token/成本¥/字节/blocked）+ 近 14 天趋势 |
| 用户 | `/admin/users` | 邮箱 / 状态 / 注册时间 / 累计调用 / 累计 token / 累计成本 / 最后活跃 |
| 用户详情 | `/admin/users/{id}` | 该用户汇总 + 最近 50 条调用明细 |
| 流量 | `/admin/traffic` | 近 30 天每日 调用/blocked/token/成本/字节 |

**用量采集**：每次 `/v1/chat/completions`（含被限流/超额拦截、上游错误）写一条 `llm_call_log`，解析上游 `usage` 记录真实 prompt/completion token，按 `LLM_PRICES_JSON` 单价估算成本（¥）。`account.llm_calls_used` 仍是「额度计数器」，与此处的「分析用量」职责分离。

**认证与加固**：
- `/admin/**` 不走 app-token，用固定 `ADMIN_TOKEN` + cookie（`picme_admin = sha256(ADMIN_TOKEN)`）。
- `ADMIN_TOKEN` 为空 → 后台禁用（全部 503）。
- **强烈建议** nginx 对 `/admin` 加 IP 白名单，或仅走 SSH 隧道访问（公网暴露请务必配 token）。

**配置**（`/etc/picme/server.env`）：`ADMIN_TOKEN`（必填）、`LLM_PRICES_JSON`（可选，覆盖默认单价）。

## LLM 代理与 SCF 迁移

`/v1/chat/completions` 端口从 `infra/tencentscf/index.js` 移植：
- 按请求 body 的 `model` 字段自动路由到 Cloudflare AI Gateway（DeepSeek）或腾讯 TokenHub
- 支持 `FORCE_PROVIDER` 强制后端
- 内置 per-IP 限流（默认 20 req/min）
- `max_tokens` 上限校验（默认 4096）
- 上游密钥只在服务端 env 持有，App 不接触

**迁移策略**：老客户端继续走 SCF，新客户端将 `baseUrl` 改为 `https://api.polang.net/`。SCF 和服务端可并行运行，无兼容性风险。

架构与决策见 `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`。
