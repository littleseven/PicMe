# PicMe 服务端实现方案（Ktor）— Review 版

> **文档状态**：待 Review（未实施）。定稿后据此编码，并重写 `OVERSEAS_SERVER_DEPLOYMENT.md` 为现实版。
> **最后更新**：2026-07-11
> **维护者**：RD Agent
> **关联**：`PRODUCT.md`、`OVERSEAS_SERVER_DEPLOYMENT.md`、`AI_OPTIMIZATION.md`

---

## 1. 目标与范围

**目标**：在 HK 腾讯轻量（`43.161.201.142`，Nginx 1.24 已就绪，TLS 已通）上部署一个 **Ktor 后端**，挂 `api.polang.net`，支撑 PicMe App 的「推荐拍照 + 图片优化」服务端能力。

**本轮交付（MVP 骨架）**：
- 5 个路由：`/healthz`、`/recommend`、`/assets`、`/llm`、`/telemetry`
- SQLite（规则/元数据/遥测/计数）
- 腾讯 COS 预签名下发
- LLM 流式代理（OpenAI 兼容协议）
- systemd + Nginx 反代 + certbot 上线

**本轮不做（Out of scope）**：账号体系、计费、GPU 云端图像处理、多 region、CI/CD 流水线、端云共享 Kotlin 模块、正式 WAF/监控仪表盘。

---

## 2. 代码管理与仓库策略 ⬅️ 待定

| 维度 | A. 独立仓库 `picme-server`（**推荐**） | B. Monorepo `langchain4android/server/` |
|------|--------------------------------------|------------------------------------------|
| 生命周期 | Server（部署到盒子）与 App（APK→Play）分离，互不干扰 | 同仓，`./gradlew build` 会带上整个安卓工程 |
| 仓库可见性 | 可灵活设私有（含部署/基础设施细节），App 仓继续公开 | 绑死：App 仓公开则 Server 代码也公开 |
| CI/部署 | Server 自有 Actions（build+deploy），不被 App 提交触发 | 需用 path filter 隔离，否则互相触发 |
| 密钥/部署隔离 | 干净（独立仓 + 独立 Actions secrets） | 同仓，需小心 path/secret 隔离 |
| 端云共享 Kotlin 类型 | 需发布构件（JitPack/Maven）或 Git submodule，多一步 | 同一 Gradle 构建直接 `shared/` 模块引用，最简 |
| App 仓体积 | 不增加 | 当前 App 仓已 7 模块（app/beauty-api/beauty-engine/runtime-core/agent-core/mnn-core/sentencepiece），再加 server 更臃肿 |

**推荐：A（独立仓库 `picme-server`）**。理由：
1. App 仓已很大且多模块、且已公开；Server 生命周期/可见性/部署都不同。
2. Solo 开发者跨仓协调成本极低，独立仓更清爽。
3. Server 仓可设私有，部署配置/基础设施细节不暴露。

**唯一选 B 的场景**：你确定很快要做端云 Kotlin 类型共享（DTO/推荐规则），且接受 App 仓继续公开包含 Server 代码。

> 契约一致性（即便独立仓）：把「API 契约/OpenAPI」维护在一个公开处（如 Server 仓的 `openapi.yaml` 或 wiki），App 与 Server 各自按契约实现；将来真要类型共享再上 JitPack。

> 下文工程结构以 **A（独立仓）** 为例；若选 B，把 `server/` 内容放进 `langchain4android/server/` 即可，其余不变。

---

## 3. 技术栈（⚠️ 含待确认）

| 项 | 选型 | 备注 |
|----|------|------|
| 语言/框架 | **Kotlin 2.x + Ktor 3.x** | 需 JDK 17+（建议 21） |
| 服务引擎 | **CIO**（纯 Kotlin 协程，省内存）或 Netty（默认稳） | 2G 盒子倾向 CIO |
| 构建 | Gradle 8.x Kotlin DSL | |
| HTTP 客户端 | Ktor HttpClient（CIO） | 用于 LLM 流式代理 |
| 序列化 | kotlinx.serialization | JSON DTO |
| DB | **SQLite + Exposed(Jetbrains) + HikariCP** | 或裸 JDBC |
| 对象存储 | 腾讯 COS Java SDK（`cos-java-sdk`） | 生成预签名 URL |
| LLM 协议 | **OpenAI Chat Completions**（`/chat/completions`，stream=true） | baseUrl 可切 Gemini/OpenAI/Groq/DeepSeek |
| 日志 | Logback | `logback.xml` + 滚动 |
| 进程 | systemd | `picme-api.service` |
| 反代/TLS | Nginx 1.24（已存在）+ certbot | `api.polang.net` |

---

## 4. 工程结构（独立仓 `picme-server`）

```
picme-server/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml          # 版本目录
├── src/main/
│   ├── kotlin/com/mamba/picme/server/
│   │   ├── Application.kt             # 入口 + 插件装配（Routing/ContentNegotiation/CallLogging/...）
│   │   ├── config/AppConfig.kt        # 读 env / HOCON
│   │   ├── routes/
│   │   │   ├── HealthzRoute.kt
│   │   │   ├── RecommendRoute.kt
│   │   │   ├── AssetsRoute.kt
│   │   │   ├── LlmRoute.kt
│   │   │   └── TelemetryRoute.kt
│   │   ├── recommend/RuleEngine.kt    # 规则查询 + 组装参数
│   │   ├── cos/CosSigner.kt           # 预签名 URL
│   │   ├── llm/OpenAiProxy.kt         # 流式透传
│   │   ├── ratelimit/RateLimiter.kt   # 内存计数（初版）+ 日预算
│   │   └── db/{Database.kt, Tables.kt, Migrations.kt}
│   └── resources/
│       ├── application.conf           # Ktor HOCON（端口、模块）
│       └── logback.xml
├── migrations/
│   ├── 001_init.sql                   # 建表
│   └── seed_rules.sql                 # 初始推荐规则
├── .env.example
├── deploy.sh
├── picme-api.service                  # systemd unit
└── README.md
```

---

## 5. API 契约（5 路由）

所有路由前缀经 Nginx 反代到 `https://api.polang.net/`。

| 方法 | 路径 | 请求 | 响应 | 缓存/限流 |
|------|------|------|------|----------|
| GET | `/healthz` | — | `{status:"ok", version, time}` | 不缓存 |
| POST | `/recommend` | `{scene, locale, clientVersion?}` | `{params:{...}, ruleVersion}` | 可缓存；不限流 |
| GET | `/assets/manifest` | `?since=<ver>` | `{models:[{key,kind,version,size,md5}]}` | 可缓存 |
| GET | `/assets/url` | `?key=<modelKey>` | `{url, expiresAt}` | COS 预签名；短缓存 |
| POST | `/llm/chat` | `{messages:[{role,content}], model?, stream:true}` | **SSE**（`data: {token}` 流） | **限流 + 日预算** |
| POST | `/telemetry` | `{events:[{type, payload}]}` | `{accepted:n}` | 不缓存；批量 |

> 字段细节（推荐参数 schema、LLM 消息格式）在实施时按 OpenAI/产品约定补全，并写进 `openapi.yaml`。

---

## 6. 数据模型（SQLite）

```sql
-- 推荐规则（改库即热更）
CREATE TABLE rule (
  id INTEGER PRIMARY KEY,
  scene TEXT NOT NULL,          -- night/portrait/food/...
  locale TEXT NOT NULL,         -- zh/en/...
  condition_json TEXT,          -- 附加条件
  params_json TEXT NOT NULL,    -- 推荐参数包
  version INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1
);

-- 模型/素材清单
CREATE TABLE asset (
  key TEXT PRIMARY KEY,         -- models/landmark@v2
  kind TEXT NOT NULL,           -- model/filter/preset
  version INTEGER NOT NULL,
  size INTEGER, md5 TEXT,
  cos_bucket TEXT, cos_key TEXT,
  created_at INTEGER NOT NULL
);

-- 匿名遥测
CREATE TABLE telemetry_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  payload_json TEXT,
  created_at INTEGER NOT NULL
);

-- LLM 日预算熔断
CREATE TABLE llm_daily_counter (
  day TEXT PRIMARY KEY,         -- '2026-07-11'
  tokens INTEGER NOT NULL DEFAULT 0,
  cost_usd REAL NOT NULL DEFAULT 0,
  blocked INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_rule_scene ON rule(scene, locale, enabled);
CREATE INDEX idx_telemetry_time ON telemetry_event(created_at);
```

---

## 7. 关键组件设计

- **RuleEngine（recommend）**：按 `(scene, locale, enabled)` 查 `rule` → 取最新 `params_json` 返回。**非个性化、纯规则**（规避算法备案，隐私友好）。
- **CosSigner（assets）**：读 `asset` 表得 `(cos_bucket, cos_key)` → 用 COS SDK 生成预签名 GET URL（默认 1h 过期 ⚠️）。
- **OpenAiProxy（llm）**：`HttpClient` POST `${LLM_BASE_URL}/chat/completions`（`stream=true`）→ 逐 chunk 透传 SSE；前置限流 + 日预算检查；**密钥只在服务端**。超预算返回降级提示。
- **Telemetry**：批量 insert，异步落库；仅匿名指标（采纳率/延迟分桶/崩溃/版本）。
- **RateLimiter**：内存令牌桶（初版，按 IP）；`/llm` 日预算读 `llm_daily_counter`。

---

## 8. 配置与密钥

`server/.env`（**不入 git**，`.env.example` 提供模板）：
```
LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
LLM_API_KEY=...
LLM_MODEL=gemini-2.0-flash
COS_SECRET_ID=...
COS_SECRET_KEY=...
COS_REGION=ap-hongkong
COS_BUCKET=picme-assets-xxxx
LLM_DAILY_BUDGET_USD=1.0
RATE_LIMIT_PER_MIN=20
COS_PRESIGN_TTL_MIN=60
```
- 本地 dev：`.env`（dotenv）/ `export`。
- 服务器：systemd `EnvironmentFile=/etc/picme/server.env`。
- `application.conf`：`ktor.deployment.port=8080`、`host=127.0.0.1`（仅本地监听）。

---

## 9. 部署

**本地构建**：`./gradlew :installDist` → `build/install/picme-server/bin/picme-server`。

**`deploy.sh`**（一键）：
```bash
./gradlew clean installDist
rsync -az --delete build/install/picme-server/ ubuntu@api-host:~/picme-server/
ssh ubuntu@api.host 'sudo systemctl restart picme-api'
curl -fsS https://api.polang.net/healthz
```

**`picme-api.service`**（systemd）：
```ini
[Unit]
Description=PicMe API (Ktor)
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/picme-server
EnvironmentFile=/etc/picme/server.env
ExecStart=/home/ubuntu/picme-server/bin/picme-server
Restart=always
RestartSec=3
# 内存上限保护（2G 共享盒子）
MemoryMax=400M

[Install]
WantedBy=multi-user.target
```

**COS 初始化**：建私有读 bucket（HK，`ap-hongkong`）；上传模型到 `models/...`；为服务端建子账号 + 最小签名权限（只允许对该 bucket 的 GetObject 预签名）。

**Nginx + 证书**：见附录 A。

---

## 10. 安全

- 后端**只监听 `127.0.0.1:8080`**，仅 Nginx 暴露；不直接对公网。
- `ufw`：仅放 22/80/443；SSH 改端口 + 密钥 + 禁密码登录；装 fail2ban。
- 密钥只服务端持有；**App 不持有 LLM/COS 密钥**（经自家网关）。
- `/llm` 限流 + 日预算熔断；`/recommend` 限流兜底。
- systemmd `MemoryMax=400M` 防后端把 2G 盒子（与 OpenClaw 共享）拖垮。
- 请求体上限（Nginx `client_max_body_size 20m`）。
- 隐私：图像/人脸不上行；隐私政策已部署，需补 LLM/数据跨境声明。

---

## 11. 监控与备份（轻量）

- **存活**：UptimeRobot 免费层 ping `/healthz`，挂了邮件提醒。
- **日志**：systemd journal + logback 滚动；`journalctl -u picme-api`。
- **指标**：日志埋点 + `llm_daily_counter`（token/花费/是否熔断）。
- **备份**：每日 cron `sqlite3 picme.db '.dump' | gzip` → 本机轮转 7 天；异地零成本备份加密推 GitHub 私有仓 Release。COS 存的是模型（非用户数据），无需备份。

---

## 12. 文档更新

实施后**重写** `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md`：
- 删除旧版「CF 代理 + R2 + 通用 VPS」假设。
- 替换为现实版：**Nginx(非宝塔) + COS + DNS-only + Ktor + 三租户共存（官网/隐私 + OpenClaw + 后端）**。
- 收录本方案的 Nginx/systemd/deploy.sh/COS 配置。

---

## 13. 实施顺序

1. 建仓（`picme-server` 或 monorepo `server/`）+ Gradle 工程 + `Application.kt` + `/healthz`，本地跑通。
2. Exposed + SQLite + migrations + `/recommend`（seed 静态规则）。
3. `/assets` manifest + COS 预签名。
4. `/llm` 流式代理（先接一家）+ 限流 + 日预算。
5. `/telemetry`。
6. `picme-api.service` + `deploy.sh` + Nginx + certbot → 上线 healthz，`curl` 验证。
7. 重写 `OVERSEAS_SERVER_DEPLOYMENT.md`。

---

## 14. 待你拍板的决策点 ⚠️

1. **仓库策略**：A 独立仓 `picme-server`（推荐） / B monorepo？
2. **Ktor 版本**：3.x（Kotlin 2.x / JDK 21）OK？
3. **服务引擎**：CIO（省内存，推荐） / Netty？
4. **DB 访问**：Exposed（推荐） / 裸 JDBC？
5. **LLM 默认接哪家**：Gemini（有免费额度）/ OpenAI(gpt-4o-mini) / Groq？
6. **COS 预签名有效期**（默认 1h）；模型下发**先走 COS 直连**确认？
7. **限流/日预算默认值**：`/llm` 20 次/分钟、$1/天——合理吗？
8. **MemoryMax**：systemd 限 400M（给 OpenClaw 留余地）合理吗？

---

## 附录 A：`api.polang.net` 上线清单（可照抄）

**A.1 DNS（Cloudflare）**：加 A 记录
- Type `A`，Name `api`，IPv4 `43.161.201.142`，Proxy **DNS only（灰云）**，TTL Auto。

**A.2 Nginx server 块**：新建 `/etc/nginx/sites-available/api.polang.net`
```nginx
server {
    listen 80;
    listen [::]:80;
    server_name api.polang.net;
    client_max_body_size 20m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # LLM 流式(SSE)必需
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
    }
}
```
启用并签证书：
```bash
sudo ln -s /etc/nginx/sites-available/api.polang.net /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d api.polang.net   # 自动签 Let's Encrypt + 加 443 + 强制 HTTPS
```

**A.3 启动后端 + 验证**
```bash
sudo systemctl enable --now picme-api
curl -I https://api.polang.net/healthz     # 期望 HTTP 200
```

**A.4 排错**
- 502：后端没起 / 端口不对 → `systemctl status picme-api`、`ss -tlnp | grep 8080`。
- 证书失败：DNS 未生效 → `dig api.polang.net +short` 应返回 IP。
- 仍打到官网：Nginx 没识别 server 块 → 确认软链 + reload。
