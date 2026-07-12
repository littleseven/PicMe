# PicMe Server (Ktor)

App 后端单体，支撑「推荐拍照 + 图片优化 + LLM 代理」。Monorepo 子工程，**独立 Gradle build，不纳入安卓 `settings.gradle.kts`**。

## 现状（v0.3.0）

| 路由 | 优先级 | 状态 | Auth | 说明 |
|------|--------|------|------|------|
| `GET /healthz` | P0 | ✅ | 无 | 存活探活 |
| `POST /recommend` | P0 | ✅ | X-App-Token | 场景标签 → 参数包（纯规则，规避算法备案） |
| `POST /telemetry` | P0 | ✅ | X-App-Token | 批量匿名遥测 |
| `POST /v1/chat/completions` | P0 | ✅ | X-App-Token | LLM 代理（Cloudflare AI Gateway / TokenHub 按模型自动路由） |
| `POST /chat/completions` | P0 | ✅ | X-App-Token | 旧路径兼容（同上） |
| `GET /assets/{manifest,url}` | P1 | 🚧 | X-App-Token | 腾讯 COS 预签名下发 —— 待实现 |
| `GET /agent/config` | P1 | 🚧 | X-App-Token | 供应商适配参数下发 —— 待实现 |

> **v0.3.0 变更**：
> - 新增 `X-App-Token` 认证（复用 SCF / App BuildConfig 的共享密钥，App 端零改动）
> - 新增 LLM 代理 `/v1/chat/completions`，移植 `infra/tencentscf/index.js` 逻辑（模型路由 + 限流 + 密钥托管）
> - 老客户端继续走 SCF（并行运行），新客户端切换到 `api.polang.net/v1/chat/completions`
>
> **架构定位**：Server 是**配置中心 + LLM 代理 + 分发管道 + 遥测收集**，不做 Agent 编排（ReAct 循环在客户端）。

## 认证

所有非 `/healthz` 路由要求 `X-App-Token` header 匹配 `APP_TOKEN` 环境变量。`APP_TOKEN` 留空 = dev 模式（不校验）。生产环境必须设置，与 SCF 和 App `BuildConfig.TENCENT_SCF_APP_TOKEN` 共用同一个值。

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

## LLM 代理与 SCF 迁移

`/v1/chat/completions` 端口从 `infra/tencentscf/index.js` 移植：
- 按请求 body 的 `model` 字段自动路由到 Cloudflare AI Gateway（DeepSeek）或腾讯 TokenHub
- 支持 `FORCE_PROVIDER` 强制后端
- 内置 per-IP 限流（默认 20 req/min）
- `max_tokens` 上限校验（默认 4096）
- 上游密钥只在服务端 env 持有，App 不接触

**迁移策略**：老客户端继续走 SCF，新客户端将 `baseUrl` 改为 `https://api.polang.net/`。SCF 和服务端可并行运行，无兼容性风险。

架构与决策见 `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`。
