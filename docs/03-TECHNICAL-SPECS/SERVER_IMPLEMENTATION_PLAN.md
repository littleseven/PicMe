# PicMe 服务端实现方案（Ktor）— Review 版

> **文档状态**：待 Review（未实施）。定稿后据此编码，并重写 `OVERSEAS_SERVER_DEPLOYMENT.md` 为现实版。
> **最后更新**：2026-07-12（按实际事实修正：Monorepo、锐驰型 200M 无限流量、OpenClaw 共存、Nginx 非 宝塔、COS 100G 已购、polang.net DNS-only）
> **维护者**：RD Agent
> **关联**：`PRODUCT.md`、`OVERSEAS_SERVER_DEPLOYMENT.md`、`AI_OPTIMIZATION.md`

---

## 1. 目标与范围

**目标**：在已购的 HK 腾讯轻量服务器上部署一个 **Ktor 后端**，挂 `api.polang.net`，支撑 PicMe App 的「推荐拍照 + 图片优化」服务端能力。

**现状资源（均已就位，不再采购）**：
- **服务器**：腾讯轻量 · 香港·三区 · **锐驰型** · 2C2G / 40G SSD / **200Mbps 峰值 · 无限流量**；公网 IP `43.161.201.142`；实例 `lhins-5u0t1f9f`；到期 2027-01-11。
- **域名**：`polang.net`（Cloudflare 注册，**DNS-only 灰云**，A 记录 → `43.161.201.142`）。
- **前置 Web**：Nginx 1.24（Ubuntu apt 装，**非宝塔**），已在 `polang.net` 托管项目官网 + 隐私声明（过审用），TLS 由 certbot 管。
- **对象存储**：腾讯 COS **100GB 标准存储包**（HK，`ap-hongkong`）。
- **同机另一租户**：OpenClaw（龙虾 AI 助手）——与后端共享 2G 内存，后端须设 `MemoryMax`。

**本轮交付（MVP 骨架）**：
- 5 个路由：`/healthz`、`/recommend`、`/assets`、`/llm`、`/telemetry`
- SQLite（规则/元数据/遥测/计数）
- 腾讯 COS 预签名下发
- LLM 流式代理（OpenAI 兼容协议）
- systemd + Nginx 反代 + certbot 上线

**本轮不做（Out of scope）**：账号体系、计费、GPU 云端图像处理、多 region、CI/CD 流水线、端云共享 Kotlin 模块、正式 WAF/监控仪表盘。

---

## 2. 代码管理：Monorepo（已定）

**决策**：后端放进本仓 `langchain4android/server/`（**Monorepo**）——AI 全栈协作友好，端云同仓便于跨端检索与契约演进。

**落地点与构建边界**：
- 目录：`langchain4android/server/`（自洽的 Ktor Gradle 工程）。
- 构建：`server/` 用**独立的 `settings.gradle.kts`**，**不纳入安卓的 settings.gradle.kts** → `cd server && ./gradlew installDist` 只编译后端，安卓构建完全不依赖 `:server`、也不被拖慢。
- CI（后期）：用 path filter 让 `server/**` 改动才触发后端 build/deploy，不污染安卓流水线。
- 密钥：`server/.env` 不入 git；GitHub Actions（若启用）用独立 secrets。
- **端云共享 Kotlin（红利）**：将来在仓内加 `shared/` 模块，App 与 Server 共同引用——这是 monorepo 相对独立仓的最大优势，DTO/推荐规则可端云同源。

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

## 4. 工程结构（monorepo 子目录 `server/`，自洽 Gradle build）

`server/` 寄居在 `langchain4android/` 下，**用独立 `settings.gradle.kts`、不纳入安卓 `settings.gradle.kts`**——安卓 7 模块的构建完全不依赖它、也不被拖慢：

```
server/   # = langchain4android/server/（rootProject.name = "picme-server"，cd server && ./gradlew ...）
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

**本地构建**（在 `server/` 下）：`./gradlew installDist` → `build/install/picme-server/bin/picme-server`（rootProject.name=`picme-server`）。

**`deploy.sh`**（置于 `server/` 下，一键）：
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
HOST=ubuntu@43.161.201.142            # 也可 ubuntu@api.polang.net
./gradlew clean installDist
rsync -az --delete build/install/picme-server/ "$HOST":~/picme-server/
ssh "$HOST" 'sudo systemctl restart picme-api'
sleep 1
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

1. 在 `langchain4android/server/` 建独立 Ktor Gradle 工程（自带 `settings.gradle.kts`，不纳入安卓构建）+ `Application.kt` + `/healthz`，本地 `cd server && ./gradlew run` 跑通。
2. Exposed + SQLite + migrations + `/recommend`（seed 静态规则）。
3. `/assets` manifest + COS 预签名。
4. `/llm` 流式代理（先接一家）+ 限流 + 日预算。
5. `/telemetry`。
6. `picme-api.service` + `deploy.sh` + Nginx + certbot → 上线 healthz，`curl` 验证。
7. 重写 `OVERSEAS_SERVER_DEPLOYMENT.md`。

---

## 14. 待你拍板的决策点 ⚠️

> 仓库策略已定 **Monorepo**（见 §2），不在此列。

1. **Ktor 版本**：3.x（Kotlin 2.x / JDK 21）OK？
2. **服务引擎**：CIO（省内存，推荐） / Netty？
3. **DB 访问**：Exposed（推荐） / 裸 JDBC？
4. **LLM 默认接哪家**：Gemini（有免费额度）/ OpenAI(gpt-4o-mini) / Groq？
5. **COS 预签名有效期**（默认 1h）；模型下发**先走 COS 直连**确认？
6. **限流/日预算默认值**：`/llm` 20 次/分钟、$1/天——合理吗？
7. **MemoryMax**：systemd 限 400M（给 OpenClaw 留余地）合理吗？

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
