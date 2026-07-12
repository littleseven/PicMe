# PicMe Server (Ktor)

App 后端单体，支撑「推荐拍照 + 图片优化」。Monorepo 子工程，**独立 Gradle build，不纳入安卓 `settings.gradle.kts`**。

## 现状（MVP 进行中）

| 路由 | 状态 | 说明 |
|------|------|------|
| `GET /healthz` | ✅ | 存活探活 |
| `POST /recommend` | ✅ | 场景标签 → 参数包（纯规则，规避算法备案） |
| `POST /telemetry` | ✅ | 批量匿名遥测 |
| `POST /llm/chat` | 🚧 | DeepSeek 经 腾讯 TokenHub / Cloudflare，**限流 100/min、日预算 ¥20** —— 待实现 |
| `GET /assets/{manifest,url}` | 🚧 | 腾讯 COS 预签名下发 —— 待实现 |

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
