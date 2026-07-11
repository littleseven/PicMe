# 海外服务端部署技术方案（香港节点 + Cloudflare）

> **文档类型**：技术方案 + 部署运维规格
> **针对场景**：PicMe 出海 Google Play，面向海外用户的「推荐拍照 + 图片优化」服务端
> **部署形态**：个人开发者 · 单机单体 · 香港机房 · Cloudflare 边缘加速与防护
> **最后更新**：2026-07-11
> **维护者**：RD Agent（技术实现）
> **关联文档**：`PRODUCT.md`、`docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md`、`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`

---

## 1. 背景与目标

PicMe 是「AI Agent + 相册/图像编辑」技术探索实验场。产品重心已迁移到相册与图片编辑（见 `PRODUCT.md`），其中「AI 一键优化」「智能场景模板」「对话式编辑」需要服务端能力支撑：可更新的推荐规则、模型/素材分发、远程 LLM 网关、匿名遥测。

面向**海外市场（Google Play）**的服务端方案目标：

- **个人开发者可承担**：6 个月含域名预算 ≤ 500 元人民币。
- **免中国大陆备案**：使用香港 region，绕开 ICP 备案与生成式 AI 服务备案等个人开发者资质门槛。
- **隐私优先不变**：敏感数据（人脸/图像/对话原文）端侧处理；服务端只接收匿名标签与参数；重算子默认不上云。
- **可直连海外 LLM**：香港可访问 OpenAI / Gemini / Anthropic（大陆机房不可），这是选择香港而非大陆的硬性原因。
- **稳定可用**：单机部署 + Cloudflare 前置，承担加速、缓存、抗 D 与证书。

> 非目标：高可用多活、GPU 云端图像处理（成本不允许，且与隐私优先冲突）、商业化计费系统。

---

## 2. 设计原则

| 原则 | 含义 |
|------|------|
| **端侧优先** | 人脸检测、图像增强、OCR 全在端侧；服务端不持有原图与人脸生物特征 |
| **上行最小化** | App 仅上送匿名场景标签与必要参数，不送图像/位置/设备指纹可逆信息 |
| **免备案** | 香港机房 + 海外域名，不进入中国大陆备案体系 |
| **单一职责单体** | MVP 阶段一个进程承载所有路由，SQLite 存储，避免微服务复杂度 |
| **边缘卸载** | 静态资源、TLS 终止、抗 D、限流交由 Cloudflare；源站只跑业务逻辑 |
| **成本可预测** | 免费层优先，按量服务设阈值告警，防止流量/LLM 费用失控 |
| **可演进** | 单机起步，预留向「多 region、双市场、拆分服务」的升级路径 |

---

## 3. 总体架构

```
                 ┌──────────────────────────────────────────────────────┐
   海外用户(App) │                   Cloudflare 边缘                       │
   Google Play  │  DNS · TLS(边缘证书) · CDN 缓存 · WAF · DDoS 防护        │
       │        │  Rate Limiting · (可选)Workers · (可选)Access           │
       │ HTTPS  │  R2 对象存储（模型 / 滤镜 / 预设，零下行流量费）          │
       └───────▶│                                                      │
                 └────────────────────┬─────────────────────────────────┘
                                      │ 回源（cloudflared Tunnel，源站不开公网入站端口）
                                      ▼
                 ┌──────────────────────────────────────────────────────┐
                 │        香港轻量服务器 2C2G（阿里云 / 腾讯云）            │
                 │  Caddy（源站 TLS，CF Origin CA 证书，Full Strict）      │
                 │  单体后端（Kotlin/Ktor 或 Go）：                        │
                 │     /recommend   场景标签 → 参数包（规则引擎）          │
                 │     /assets      返回 R2 签名 URL（模型/素材元数据）     │
                 │     /llm         代理 Gemini/OpenAI/Groq（流式透传）    │
                 │     /telemetry   匿名指标 append                        │
                 │  SQLite(WAL) — 规则 / 元数据 / 遥测 / 限流计数          │
                 └──────────┬──────────────────────────┬──────────────────┘
                            │ 签名 URL（下发）          │ 代理（出站）
                            ▼                         ▼
                   Cloudflare R2              Gemini / OpenAI / Groq
                ┌──────────────────┐        （香港可直连；大陆机房被墙）
                │ models/ filters/ │
                │   presets/       │
                └──────────────────┘
```

**三层职责**：
1. **边缘层（Cloudflare）**：世界级 Anycast 网络，做 DNS、TLS、缓存、安全、对象存储。承担「离用户最近」的所有无状态工作。
2. **计算层（香港 VPS）**：唯一的有状态业务逻辑（推荐规则、LLM 代理、遥测）。位于香港，可直连海外 LLM、可被大陆开发者在合规前提下访问调试。
3. **存储层（R2 + SQLite）**：对象存储（大文件、模型）放 R2（全球分发、零下行费）；结构化小数据（规则、元数据、遥测）放源站 SQLite。

---

## 4. 选型决策

### 4.1 为什么是香港（不是大陆、不是欧美）

| 维度 | 大陆机房 | **香港机房** | 新加坡/欧美 |
|------|----------|------------|------------|
| ICP 备案 | 必须（个人非经营可办，但生成式 AI 公众服务仍受限） | **免** | 免 |
| 可直连海外 LLM（OpenAI/Gemini） | ❌ 被墙 + 厂商不支持中国区 | ✅ **支持 HK 区** | ✅ |
| 对国内开发者支付/控制台 | ✅ 人民币/中文 | ✅ **人民币/中文** | ❌ USD/英文为主 |
| 到大陆延迟 | 最低 | 低（华南 ~10–30ms，过 GFW 有抖动） | 高 |
| 到海外用户延迟 | 高（出海差） | 亚洲优、欧美中 | 欧美优、亚洲中 |
| GDPR 充分性 | 否 | 否（需 SCC） | 视地区 |

**结论**：出海为主、开发者在国内、需调海外 LLM → **香港是最优折中**。

### 4.2 云厂商与配置

| 云 | 推荐配置 | 参考价（2026） | 说明 |
|----|----------|--------------|------|
| **阿里云 轻量（香港·国际型）** | 2C2G / 50–60G SSD / 峰值 30M+ | ≈39 元/月 | 档位清晰、性价比略优 |
| **腾讯云 Lighthouse（香港）** | 2C2G / 4M / 50G SSD / 300G 月流量 | ≈50 元/月（年付 510 元 85 折 ≈42 元/月） | 流量包与带宽档灵活 |
| 极致省钱 | 2C1G（阿里 HK ≈28 元/月）或非中国云（Vultr/搬瓦工 HK，~$3–5/月） | — | 低流量 + Cloudflare 前置可胜任 |

> ⚠️ 香港属于「国际型」套餐，**不享受大陆 region 的 38/68/99 元/年 秒杀**。价格以官网实时为准。

### 4.3 域名

- **注册商**：海外注册商（Cloudflare Registrar / Porkbun / Namecheap），`.com` ≈ $10/年。
- **免实名、免备案**（区别于国内注册商）。
- 子域规划：`api.yourdomain.com`（API）、`assets.yourdomain.com`（如需自定义 R2/CDN 域名，可选）、`admin.yourdomain.com`（受 Cloudflare Access 保护的管理面）。

### 4.4 LLM 选型

| 选项 | 说明 | 成本 |
|------|------|------|
| **Gemini Flash**（首选 MVP） | 有免费额度，多模态强，HK 可直连 | 免费层 + 超用便宜 |
| OpenAI gpt-4o-mini | 生态成熟、便宜 | 按 token，极低 |
| Groq（Llama 系列） | 推理快、有免费层 | 免费/低价 |
| DeepSeek | ❌ 仅用于国内市场；数据回流中国，海外用户隐私观感差 | — |

> PicMe 远程推理已用 langchain4j + OpenAI Chat Completions 协议（见 `PRODUCT.md` ADR-005），切换 `baseUrl + apiKey` 即可指向 Gemini/OpenAI/Groq，**零代码改动**。

---

## 5. Cloudflare 在本架构中的作用（详解）

> Cloudflare 是本方案「省钱 + 稳定 + 安全」的核心。源站只是一台 2C2G 小机器；把它放在 Cloudflare 后面，等于免费获得了全球 CDN、企业级抗 D、托管 TLS 与对象存储。下面逐项说明。

### 5.1 能力总览

```
   用户 ──▶ [CF 边缘] ──▶ 源站(HK VPS)
            │
            ├─ DNS（权威解析，Anycast，全球 <10ms）
            ├─ 反向代理（橙云，隐藏源站 IP）
            ├─ TLS 终止（边缘免费证书 + 源站 Origin CA，Full Strict）
            ├─ CDN 缓存（静态与可缓存 API 响应）
            ├─ 安全（WAF 托管规则 / 自定义 / Rate Limiting / Bot 抑制）
            ├─ DDoS 防护（L3/L4/L7，免费、不限量）
            ├─ R2 对象存储（模型/素材，零下行费）
            ├─ Workers（边缘计算，可选：A/B、轻路由、签名 URL 生成）
            └─ Tunnel（源站零入站端口）+ Access（管理面鉴权）
```

### 5.2 逐项说明

| 能力 | 作用 | 在本架构的职责 | 配置要点 | 成本 |
|------|------|--------------|----------|------|
| **DNS** | 权威 DNS，Anycast 全球解析 | 托管 `yourdomain.com` 全部解析；`api` 指向 CF 代理 | 域名注册后改 NS 到 CF；启用 DNSSEC | 免费 |
| **反向代理（Proxy / 橙云）** | CF 夹在用户与源站之间 | **隐藏源站真实 IP**；所有流量经 CF；源站只见 CF 回源 IP | DNS 记录开启「Proxied」(橙云) | 免费 |
| **TLS / SSL** | 证书托管与加解密卸载 | 边缘用 CF 免费证书面向用户；源站用 CF Origin CA 证书 | 模式设 **Full (Strict)**；源站装 Origin CA 证书（15 年免费） | 免费 |
| **CDN 缓存** | 静态资源边缘缓存 | 缓存素材元数据、规则包、版本清单；动态 API 默认回源 | Cache Rules：`/assets/meta*`、`/recommend/rules*` 可缓存；`/llm*`、`/telemetry*` 绕过缓存 | 免费 |
| **WAF** | Web 应用防火墙 | 拦截常见攻击（SQLi、XSS、异常 payload）；自定义规则封恶意路径 | 启用 CF 托管规则集；自定义规则（如仅允许 App UA / 特定路径） | 免费（基础）；Pro 更多 |
| **Rate Limiting** | 请求速率限制 | 保护 `/llm`（防刷 token）、`/recommend`；按 IP/路径限速 | 对 `/llm*` 设「同 IP N 次/分钟」阈值，超限 429 | 免费档 1 条规则；Pro 更多 |
| **DDoS 防护** | 分布式拒绝服务防护 | 小机器扛不住流量洪峰；CF 边缘吸收 | 默认开启，无需配置 | 免费、不限量 |
| **Bot 抑制** | 自动化流量识别 | 抑制爬虫/扫描器对 API 的无效调用 | 启用 Bot Fight Mode（免费） | 免费（进阶付费） |
| **R2 对象存储** | S3 兼容对象存储 | **模型 / 滤镜 / 预设包**托管；通过 CF 边缘全球分发 | 建 bucket；用 `r2.dev` 域或绑定自定义域（经 CF 缓存） | 10 GB + 1M/10M 操作免费；**下行流量 0 元** |
| **Workers** | 边缘 Serverless 计算（V8 isolate） | 可选：在边缘做 A/B 分流、生成 R2 签名 URL、轻量鉴权、缓存改写 | `wrangler` 部署；JS/TS | 10 万 请求/天 免费 |
| **Tunnel（cloudflared）** | 反向隧道 | **源站 VPS 不开任何公网入站端口**，`cloudflared` 主动外连 CF；彻底规避源站被扫描爆破 | 源站装 cloudflared，建立 Tunnel，CF 路由 `api.yourdomain.com` → tunnel | 免费 |
| **Access（Zero Trust）** | 身份鉴权前置 | 保护 `admin.*` 管理面（日志、备份、配置） | 策略：邮箱/OIDC 登录后才可达 | 免费 ≤50 用户 |
| **Analytics / Logs** | 流量与安全可观测 | 看 QPS、缓存命中率、威胁拦截、回源比 | 仪表板；`wrangler tail` 看日志 | 免费（详细日志付费） |

### 5.3 Cloudflare vs 自建对比

| 维度 | 自建（裸 VPS） | Cloudflare 前置 |
|------|---------------|----------------|
| 抗 DDoS | ❌ 小机器秒被打瘫 | ✅ 边缘吸收，不限量免费 |
| TLS 证书 | 需 Caddy/Let's Encrypt 自管 | ✅ 托管 + 源站 Origin CA |
| 全球加速 | ❌ 单香港节点 | ✅ 300+ 边缘节点缓存 |
| 源站 IP 暴露 | ✅ 暴露（易被针对） | ❌ 隐藏（仅见 CF） |
| 对象存储下行费 | OSS/S3 按量收费 | ✅ R2 零下行费 |
| WAF / 限流 | 需自建（fail2ban 等） | ✅ 托管规则 + Rate Limit |
| 成本 | 仅 VPS | VPS + CF（基本免费） |

**结论**：对一台 2C2G 的小机器，Cloudflare 不是「锦上添花」，而是「让它能面向公网稳定服务的必要前置」。

### 5.4 注意事项

1. **源站锁定到 CF**：开橙云后，必须确保源站只接受来自 CF 的回源流量，否则攻击者可绕过 CF 直打源站 IP。两种做法二选一：
   - **Tunnel（推荐）**：源站不开公网入站端口，`cloudflared` 外连，天然隔离；
   - **防火墙白名单**：源站仅放行 [CF 回源 IP 段](https://www.cloudflare.com/ips/)，配合 **Authenticated Origin Pulls**（源站校验 CF 客户端证书）。
2. **缓存与动态内容**：`/llm`（流式）、`/telemetry`（写）绝不能被缓存或压缩中断；务必在 Cache Rules / 配置里对这类路径设置「Bypass cache」并关闭响应缓冲（流式透传）。
3. **上传大小 / 超时**：免费版单请求体上限 100MB；LLM 长流式需关注 100s 响应超时（Workers 有 CPU 时长限制，源站直连则无此问题）。
4. **R2 自定义域**：绑自定义域后经 CF 缓存，模型下载更快；用默认 `*.r2.dev` 也可用但不可控缓存。
5. **合规**：CF 本身是海外服务，经 CF 的访问日志/数据受其隐私政策约束；敏感数据本就端侧处理，影响可控，隐私政策中披露即可。

---

## 6. 数据流（关键路径）

### 6.1 推荐拍照（F1）

```
App(端侧识别场景标签：夜景/人像/逆光/美食...)
   │ POST /recommend  { scene:"night", locale:"zh" }   (不含图像)
   ▼
CF 边缘(限流/WAF) ──缓存命中?──▶ 命中则直接返回 200 + 参数包
   │ 未命中
   ▼
HK 源站 /recommend：查 SQLite 规则表 → 组装参数包(EV/美颜档位/滤镜/比例)
   │
   ▼
App 应用参数，展示推荐；用户采纳/丢弃 → POST /telemetry(匿名)
```

### 6.2 模型 / 素材下载（F2）

```
App ──GET /assets/manifest──▶ CF ──▶ HK 源站(返回清单 JSON, 含 R2 对象 key)
App ──GET R2 签名URL────────▶ CF 边缘(R2) ──▶ 就近吐出模型文件(零下行费)
```

### 6.3 LLM 对话代理（F3）

```
App ──POST /llm/chat (SSE/流式)──▶ CF(关闭缓冲,Bypass cache)
   │
   ▼
HK 源站 /llm：注入系统提示 + 限流 + (可选)缓存查询
   │  出站(香港可直连)
   ▼
Gemini / OpenAI ──流式 token──▶ HK 源站 ──透传──▶ CF ──▶ App
```

### 6.4 遥测（F5）

```
App(匿名化：采纳率/崩溃/性能，无设备可逆指纹)
   │ POST /telemetry (批量, 低频)
   ▼
CF ──▶ HK 源站 append SQLite(缓冲) ──离线聚合──▶ 报表
```

---

## 7. 组件详细设计

### 7.1 后端单体

- **语言/框架**：首选 **Kotlin/Ktor**（与 Android 技术栈一致，推荐规则与数据模型可抽成共享 Kotlin 模块端云复用）；2C2G 资源紧张时退 **Go (Echo/Gin)**（单二进制，~50MB 内存）。
- **路由**：`/recommend`、`/assets`、`/llm`、`/telemetry`、`/healthz`、`/version`。
- **进程管理**：`systemd` 守护，崩溃自启。
- **配置**：环境变量注入（LLM key、R2 凭据、DB 路径），不落盘密钥。

### 7.2 LLM 网关（核心、成本风险点）

| 职责 | 实现 |
|------|------|
| 密钥保管 | key 仅存源站环境变量，**App 不持有**第三方 key |
| 限流 | 每 IP/设备标识：`/llm` N 次/分钟（CF Rate Limit + 源站双重） |
| 缓存 | 对「无上下文的规则型问答」做响应缓存（Hash 入参 → 命中直接回放） |
| 流式透传 | SSE/Chunked 透传，禁用响应缓冲 |
| 降级 | 主 LLM 超时/失败 → 回退本地规则或第二个 LLM |
| 计量 | 记录每次 token 消耗，按日预算硬熔断（见 §11） |

### 7.3 推荐引擎（规则型）

- **形态**：SQLite 规则表 `(scene, locale, condition) → param_pack(JSON)`。非个性化、不做用户画像排序——**规避算法推荐备案**（出海虽不强制，仍保留隐私友好特性）。
- **更新**：服务端改库即生效，App 无需发版。
- **预留**：表结构预留 `model_version`、`ab_group` 字段，为后续 A/B 与小模型评分留口。

### 7.4 模型 / 素材分发（R2）

- 目录：`models/`（检测/增强模型）、`filters/`（滤镜 LUT）、`presets/`（参数预设包）。
- 清单：`/assets/manifest` 返回版本与对象 key；App 据此比对本地版本，增量下载。
- 访问：R2 经 CF 边缘分发；大文件走 R2 而非源站，**保护源站 3–4M 小带宽与流量包**。

### 7.5 遥测

- 匿名：仅采纳率、延迟分桶、崩溃堆栈、版本号；**不含设备 ID 可逆映射、不含图像、不含位置**。
- 写入：源站 SQLite 缓冲表，定时聚合清理。

---

## 8. 域名与 TLS 方案

```
yourdomain.com  ──NS──▶  Cloudflare DNS（托管）
   │
   ├─ api.yourdomain.com   A/AAAA + Proxied(橙云) → Tunnel / 源站
   ├─ assets.yourdomain.com → R2 自定义域(经 CF 缓存)
   └─ admin.yourdomain.com  → 源站管理面 + Cloudflare Access(登录鉴权)
```

**TLS 链路**：
1. 用户 ↔ CF 边缘：CF 免费边缘证书（自动续期）。
2. CF ↔ 源站：**Full (Strict)**，源站部署 CF Origin CA 证书（免费、15 年有效）。
3. 源站反向代理：Caddy（或直连 Ktor/Go TLS）；若用 Tunnel，源站甚至可不配 TLS（Tunnel 加密回源）。

---

## 9. 安全与隐私

| 项 | 措施 |
|----|------|
| 敏感数据 | 人脸/图像/对话原文 **端侧处理**，不上行；服务端零持有 |
| 上行最小化 | 仅匿名场景标签、参数、聚合指标 |
| 源站暴露面 | Tunnel 零入站端口 / 防火墙仅放行 CF IP + Authenticated Origin Pulls |
| 密钥 | LLM/R2 key 仅源站环境变量；App 经自有 `/llm`、`/assets` 取数据，不直连第三方 |
| 传输加密 | 全链路 TLS（CF 边缘 + Origin CA） |
| 备份 | SQLite 每日 cron 导出 → 加密 → 上传 R2（生命周期：保留 7/30 天） |
| GDPR/CCPA | 隐私政策披露数据范围与跨境（HK 非 EU 充分性，涉及欧盟个人数据需 SCC）；提供数据删除/导出入口；Data Safety 表如实填报 |
| 端侧披露 | App 内「数据安全」页声明：人脸/图像不上传 |

> 合规研判为工程视角、非法律意见；上架前以 Google Play 政策与各属地隐私法最新版本为准。

---

## 10. 成本模型（半年，≤ 500 元）

| 项 | 金额（人民币） | 说明 |
|----|----------|------|
| 域名 `.com`（海外注册商） | ~70 | 年付，覆盖半年+ |
| 香港轻量 2C2G（半年） | 234（阿里 39/月）/ 300（腾讯 50/月） | 或 2C1G 省 ~100 |
| Cloudflare（DNS/CDN/WAF/DDoS/TLS/Tunnel） | 0 | 免费档足够 MVP |
| Cloudflare R2（模型/素材） | 0–20 | 10GB + 操作免费；超用极便宜 |
| Google Play 开发者账号（一次性） | ~180（$25） | 终身，另计非半年经常性支出 |
| LLM（Gemini 免费层为主） | 0–50 | 量小可零；超用按 token |
| **半年合计（不含一次性账号）** | **≈ 300–440 元** | 余 ~60–200 缓冲 |

**超用风险与硬熔断**：
- **LLM token**：源站按「日预算」熔断（如每日 \$1 上限），超限 `/llm` 返回降级响应；CF Rate Limit 兜底防刷。
- **R2 存储**：监控 bucket 体积，旧版本模型及时清理。
- **源站流量包**：模型分发走 R2，避免占满 VPS 月流量配额。

---

## 11. 部署与运维

### 11.1 购买与初始化清单

1. 海外注册商购域名（`.com`）。
2. 开通阿里云/腾讯云**香港**轻量服务器（2C2G）。
3. Cloudflare 添加站点、改域名 NS、开启 DNSSEC。
4. 源站装 `cloudflared`，建 Tunnel；CF 侧路由 `api.*` → tunnel。
5. R2 建 bucket，上传模型/素材；生成源站访问凭据。
6. 部署后端单体（systemd），配环境变量。
7. 配 CF：Full(Strict)、Cache Rules、Rate Limit、Bot Fight Mode。
8. 烟囱测试：`curl https://api.yourdomain.com/healthz`。

### 11.2 发布流程

- 单二进制/JAR：本地构建 → `rsync` 到源站 → `systemctl restart`（或蓝绿软链切换）。
- 规则/素材热更：改 SQLite 或上传 R2 新版本 → 更新 manifest，App 下次启动自取。
- 配置：secrets 走 `.env`（不入库），代码与配置分离。

### 11.3 监控与告警

| 指标 | 阈值 | 动作 |
|------|------|------|
| `/healthz` 存活 | 连续 3 次失败 | 告警 + systemd 自启 |
| 源站 CPU/内存 | >85% 持续 5min | 告警（考虑升配） |
| R2 存储 | >9GB | 清理旧版本 |
| LLM 日支出 | >预算 | 熔断 `/llm` 降级 |
| CF 威胁拦截突增 | 异常 | 查看 WAF 日志 |

### 11.4 备份

- SQLite：每日 `sqlite3 .dump` → gzip 加密 → R2 `backups/`，保留 7 份日 + 4 份周。
- 配置/密钥：本地密码管理器保存，不入库不入备份桶。

---

## 12. 容量与扩展

| 触发条件 | 动作 |
|----------|------|
| 单机 CPU 长期 >70% 或 QPS 上升 | 升配到 4C8G；或读多写少场景前置 CF 缓存命中率 |
| SQLite 写入成为瓶颈（高并发遥测） | 迁 PostgreSQL（同机或托管），或遥测改写 Kafka/队列 |
| 模型下载量大、R2 费用上升 | 评估是否需要更高 R2 配额或自建；启用更激进缓存 |
| 需要高可用 | 双香港实例 + CF Load Balancer（付费） |
| 进入多区域 | 新加坡/欧美节点 + CF Geo Steering；LLM 就近选区 |

---

## 13. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 单点宕机 | 服务不可用 | systemd 自启 + CF 缓存兜底（静态/规则可边缘命中）；接受 MVP 单点 |
| 源站被 DDoS | 小机器被打瘫 | CF 边缘吸收 + 源站锁 CF（Tunnel） |
| LLM 费用失控 | 预算超支 | 日预算硬熔断 + 限流 + 响应缓存 |
| GFW 对大陆访问 HK 的抖动 | 若兼服务国内用户不稳 | 出海主场景无此问题；国内走独立大陆 track |
| Cloudflare 账号封禁 | 边缘失效 | 遵守其 ToS；备好域名可切直连回源方案（A 记录灰云 + 自管证书） |
| 合规变化（GDPR/AI Act） | 下架或罚款 | 隐私政策随法规更新；敏感数据端侧处理是最强护城河 |

---

## 14. 演进路线

| 阶段 | 目标 | 关键动作 |
|------|------|---------|
| **MVP（2–4 周）** | 单机香港 + CF + Gemini，跑通 F1/F2/F3 | 本文档方案直接落地 |
| **Phase 2** | 体验与成本优化 | CF Cache/Workers 细化；R2 自定义域；LLM 缓存与多模型路由 |
| **Phase 3** | 多区域 + 双市场 | 国内（大陆 + 备案 + DeepSeek）与海外（HK/SG + 海外 LLM）双 track；App build flavor 区分 |
| **Phase 4（视商业化）** | 高可用与合规升级 | 个体工商户/公司主体、ICP/算法备案、多活、监控体系 |

---

## 15. 附录

### 15.1 交付检查单

- [ ] 海外域名 + CF 托管 DNS + DNSSEC
- [ ] 香港 2C2G 轻量就绪
- [ ] cloudflared Tunnel 打通，源站无公网入站端口
- [ ] CF Full(Strict) + Origin CA 证书
- [ ] R2 bucket + manifest 下发链路通
- [ ] `/recommend` `/assets` `/llm` `/telemetry` `/healthz` 路由就绪
- [ ] LLM key 仅在源站；日预算熔断生效
- [ ] CF Rate Limit（`/llm`）、Bot Fight Mode、Cache Rules 配置
- [ ] SQLite 每日备份上 R2
- [ ] 隐私政策 + Data Safety 表如实填报
- [ ] Google Play 个人账号 + 20 测试者 14 天封闭测试通过

### 15.2 关键参考

- Cloudflare Tunnel：<https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/>
- Cloudflare R2：<https://developers.cloudflare.com/r2/>
- Origin CA 证书：<https://developers.cloudflare.com/ssl/origin-configuration/origin-ca/>
- 阿里云轻量（香港）：<https://www.alibabacloud.com/zh/product/swas/pricing>
- 腾讯云 Lighthouse：<https://cloud.tencent.com/product/lighthouse>
- Google Play 政策：<https://play.google.com/about/developer-content-policy/>

### 15.3 术语

| 术语 | 含义 |
|------|------|
| 橙云 / Proxied | CF DNS 记录开启代理，流量经 CF |
| 灰云 / DNS only | 仅解析，不经 CF（用于切直连回源兜底） |
| Full (Strict) | CF 到源站强制有效证书验证 |
| Authenticated Origin Pulls | 源站反向校验 CF 客户端证书，确保来路是 CF |
| R2 | Cloudflare 的 S3 兼容对象存储，零下行流量费 |
| Tunnel (cloudflared) | 源站主动外连 CF 建立反向隧道，无需公网入站端口 |
