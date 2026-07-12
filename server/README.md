# PicMe Server (Ktor)

App 后端单体，支撑「推荐拍照 + 图片优化」。Monorepo 子工程，**独立 Gradle build，不纳入安卓 `settings.gradle.kts`**。

## 现状（MVP 进行中）

| 路由 | 优先级 | 状态 | 说明 |
|------|--------|------|------|
| `GET /healthz` | P0 | ✅ | 存活探活 |
| `POST /recommend` | P0 | ✅ | 场景标签 → 参数包（纯规则，规避算法备案） |
| `POST /telemetry` | P0 | ✅ | 批量匿名遥测 |
| `GET /assets/{manifest,url}` | P1 | 🚧 | 腾讯 COS 预签名下发 —— 待实现 |
| `GET /agent/config` | P1 | 🚧 | 供应商适配参数下发（prompt/模型选择/ReAct 策略）—— 待实现 |
| `POST /llm/chat` | P2 | 🚧 | DeepSeek 经 TokenHub，**混合模式：仅 Keyless 用户走 Server，BYOK 直连** —— 待实现 |

> **架构定位**：Server 是配置中心 + 分发管道 + 遥测收集，不做 Agent 编排（ReAct 循环在客户端）。
> `/llm` 降为 P2：MVP 不含，待 BYOK 比例和 keyless 需求量验证后再实施。

## 本地开发
```bash
./gradlew -p server run            # 起 127.0.0.1:8080
curl http://127.0.0.1:8080/healthz
```
配置走环境变量（见 `.env.example`），DB 本地建为 `picme.db`（SQLite，SchemaUtils 自动建表）。

## 构建
```bash
./gradlew -p server installDist    # → build/install/picme-server/bin/picme-server
```

## 部署
`deploy.sh`（构建 → rsync → systemctl restart → curl healthz）+ `picme-api.service`（systemd，`MemoryMax=400M`，与 OpenClaw 共享 2G 盒子）。

架构与决策见 `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`。
