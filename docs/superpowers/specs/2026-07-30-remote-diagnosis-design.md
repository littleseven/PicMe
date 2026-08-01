# 远程诊断（云主机 Claude Code worker）

> **状态**：⛔ SUPERSEDED（2026-08-01）——诊断模式已合并入 AI 工程师模式，见
> `2026-08-01-ai-engineer-diag-merge-design.md`。本文仅作历史存档。

> **日期**：2026-07-30
> **状态**：已确认，待实施
> **范围**：`app/`（手机端诊断采集与 chat 触发）、`server/`（会合点队列与状态机）、`scripts/diag-worker/`（云主机 worker，egress-only）
> **演进（2026-07-31）**：多轮澄清对话（app 侧 LLM 追问 + `[DIAG_READY]` 手动提交）与三方加固（sweeper 回收/TIMED_OUT、error 透出、限频限长、suggestedFix 传递、修复空改动防护、python3 模板渲染、确认绑定 jobId、轮询超时、崩溃栈链路）见 `2026-07-31-diag-multiturn-and-hardening-design.md`，已实现。

## 1. 背景与问题

app 出问题时，缺乏「用自然语言描述 → 远程自动定位 → 修复」的闭环。现有一台 Kimi 199 会员赠送的云主机（实测为腾讯云 CVM，root，Ubuntu，~7.5G/40G），上面已配置 Claude Code + GLM 推理、git + SSH。

云主机网络实测画像（2026-07-30 探针）决定了架构形态：

- **出口公网 IP 池化轮换**（三次探测 3 个不同腾讯云 IP）→ 不能 DNS 绑定、不能按 IP 加白名单。
- **入站不可达**（私网 + NAT + 无云控制台）→ 只能当 **egress-only worker**，不能被外部主动访问。
- **出站白名单较宽**：`api.polang.net`、`github.com`、`api.github.com`、`www.baidu.com` 均可达；仅个别 IP-echo 目标被挡。

因此唯一可行的形态：**会合点用 worker 够得着的 `api.polang.net`（项目已有 Ktor 后端），worker 常驻出站轮询拉取任务**。手机本就与该后端通信，零新增网络通道。

计费现实：流量不单独计费（折叠进固定日费）；云主机常驻 0.6%/天（Kimi 额度）；推理 token 走 GLM 按量另付（便宜、可控）。**poll 不调 LLM = 免费**，故 poll 频率与成本无关，真正成本旋钮是单次诊断/修复的推理 token。

## 2. 设计目标

- **自然语言诊断闭环**：chat 里描述问题 → 自动定位根因 → 用户确认 → 修复交付。
- **两段式 + 人工闸门**：先回「根因分析」供确认，确认后才修复，避免盲改。
- **egress-only 适配**：worker 不需被入站访问，靠轮询 + git/gh 出站完成全链路。
- **隐私红线不破**：诊断包纯文本，绝不含图片/视频；media 路径等强制脱敏（ADR-008）。
- **成本可控**：限 Claude Code 迭代轮数 + 裁剪上下文 + 每阶段超时。

## 3. 架构

```
┌──────────── 手机 app ────────────┐  ┌──── api.polang.net (Ktor) ────┐  ┌── 云主机 worker ──┐
│ chat 输入栏「诊断」入口          │  │ DiagRoute（新）              │  │ Claude Code+GLM   │
│ DiagBundleCollector 收集纯文本包 │  │  POST /diag/report (AppToken)│  │ 常驻 poller       │
│ DiagSanitizer 脱敏               │─►│  GET  /diag/jobs/{id}        │◄─│ GET /diag/work/jobs (WorkerToken)
│ DiagClient 上报/拉结果/确认      │  │  POST /diag/jobs/{id}/confirm│  │ clone @gitSha     │
│ 根因回 chat → 用户确认选 push/pr │  │  ── worker 口（独立鉴权）──  │  │ claude -p 诊断    │
│                                  │  │  GET  /diag/work/jobs        │  │ claude -p 修复    │
│                                  │  │  POST /diag/work/jobs/{id}/result │ │ push diag-fix分支 │
└──────────────────────────────────┘  │ diag_jobs 表 + 状态机        │  │ gh pr create（可选）│
                                       └──────────────────────────────┘  └───────────────────┘
```

复用现有基建：`PoLangAuthClient`（`X-App-Token` + `https://api.polang.net`）、Ktor 认证拦截器、Exposed DB + `migrations/`、`Routing.xxxRoute()` 模式、`RateLimiter`。

## 4. 数据流与状态机

### 4.1 端到端流程

1. 用户在 chat 描述问题 → 点「诊断」（或 `/diag`）→ 当前输入作为自然语言描述，附自动采集的脱敏诊断包 → `POST /diag/report` → 入队（QUEUED）。
2. worker 常驻 poll，取到 QUEUED job → `git clone` 仓库 @ 报告的 gitSha → Claude Code **只诊断不改码** → 回传根因 → DIAGNOSED。
3. 根因回到同一段 chat 给用户看 → 用户确认。
4. 用户选交付方式（`push` 到 `diag-fix/<jobId>` 分支 / `pr` 开 PR）→ `POST /confirm` → FIX_REQUESTED。
5. worker 取到 FIX_REQUESTED → 同 workdir `git checkout -b diag-fix/<jobId>` → Claude Code 按确认的根因做最小修复 → 资源允许跑 JVM 单测 → commit → `git push` 分支 → FIXED。**全程纯 git/SSH，不用 gh**；pr 模式仅额外拼接 compare URL 回传。
6. 结果（分支名 / compare URL(pr 模式) / 是否自检通过）回 chat。push 模式：用户本地 fast-forward / cherry-pick 合入；pr 模式：用户点 compare URL 在浏览器开 PR 后合并。合入后重编装机。

### 4.2 任务状态机

```
QUEUED ──诊断成功──► DIAGNOSED ──用户确认+选mode──► FIX_REQUESTED ──修复成功──► FIXED
   │                    │                                │
   └─诊断失败► DIAGNOSE_FAILED                  修复失败/超时 ► FIX_FAILED
                  任意阶段超时(~1h) → 标超时，chat 通知用户
```

终态：`FIXED` / `DIAGNOSE_FAILED` / `FIX_FAILED` / 超时。失败与超时可由用户在 chat 重试（重新入队或重跑当前阶段）。

## 5. API 契约（`server/.../routes/DiagRoute.kt`）

手机侧（走现有 `X-App-Token` 拦截器，自动鉴权）：

| 方法 | 路径 | Body | 响应 |
|------|------|------|------|
| POST | `/diag/report` | `{description, bundle:{logs, crashTrace?, appVersion, gitSha, deviceModel, androidVersion}}` | `{jobId, status:"QUEUED"}` |
| GET | `/diag/jobs/{id}` | — | `{jobId, status, rootCause?, fixBranch?, prUrl?, tested?, error?, updatedAt}` |
| POST | `/diag/jobs/{id}/confirm` | `{mode:"push"\|"pr"}` | `{status:"FIX_REQUESTED"}` |

worker 侧（独立鉴权 `X-Diag-Worker-Token`，**不按 IP 白名单**——出口 IP 池化）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/diag/work/jobs` | 原子领取一个任务（置 `claimed_at`；QUEUED→诊断；FIX_REQUESTED→修复），无则 204。返回 `{jobId, phase, description, bundle, gitSha, rootCause?(修复阶段), fixMode?(修复阶段)}`。MVP 单 worker；领而不回传超 lease（如 15min）则可重领，避免卡死 |
| POST | `/diag/work/jobs/{id}/result` | 回传：诊断 `{phase:"diagnose", status:"DIAGNOSED"\|"DIAGNOSE_FAILED", rootCause?, error?}`；修复 `{phase:"fix", status:"FIXED"\|"FIXED_UNVERIFIED"\|"FIX_FAILED", fixBranch, compareUrl?(仅 pr 模式，字符串拼接非 API), tested, error?}` |

> 鉴权落地：`Application.kt` 全局拦截器目前对非 publicRoutes 且无 `X-App-Token` 的请求返回 401。新增 `/diag/work/**` 前缀加入拦截器**跳过集合**，由 `DiagRoute` 内部校验 `X-Diag-Worker-Token`（值来自 `AppConfig.diagWorkerToken`，环境变量注入，无静态默认）。

## 6. 组件拆分

### 6.1 手机端

| 组件 | 路径 | 职责 |
|------|------|------|
| `DiagClient` | `app/.../data/remote/picme/DiagClient.kt`（新） | 复用 `PoLangAuthClient` 的 baseUrl/OkHttp 风格，实现 `reportDiagnosis / fetchDiagStatus / confirmFix`，`X-App-Token` 鉴权 |
| `DiagBundleCollector` | `app/.../core/diag/DiagBundleCollector.kt`（新） | 采集 `PoLang:*` 日志环形缓冲、最近崩溃栈、版本/gitSha/设备信息，组装纯文本包 |
| `DiagSanitizer` | `app/.../core/diag/DiagSanitizer.kt`（新） | 脱敏：media 路径→`<media-id>`、绝对路径、token、邮箱、GPS、人物名→`<person-id>` |
| `Logger` | `app/.../core/common/Logger.kt`（改） | 增加内存环形 appender（最近 ~1000 行 `PoLang:*`），供 collector 读取 |
| chat 触发与状态 | `app/.../features/chat/ChatViewModel.kt`（改）+ 输入栏 UI | 「诊断」入口发起 + 轮询 `fetchDiagStatus` 展示根因/结果 + 确认选 mode |
| 崩溃落盘 | 全局 `Thread.UncaughtExceptionHandler`（新/改） | 未处理异常栈写文件，collector 有则附（MVP：仅当存在） |

### 6.2 服务端

| 组件 | 路径 | 职责 |
|------|------|------|
| `DiagRoute` | `server/.../routes/DiagRoute.kt`（新） | 5 个端点；`/diag/work/**` 校验 worker token；其余走 AppToken |
| `DiagJobs` 表 | `server/.../db/Tables.kt`（改）+ `migrations/`（新） | `id, account_id, device_id, description, bundle_json, status, root_cause, fix_mode, fix_branch, pr_url, tested, worker_log, created_at, updated_at, claimed_at` |
| `AppConfig` | `server/.../config/AppConfig.kt`（改） | 新增 `diagWorkerToken`（env 注入） |
| `Application` | `server/.../Application.kt`（改） | 拦截器跳过 `/diag/work/**`；`routing { diagRoute(...) }` |
| worker token 常量 | `server/.../auth/AppTokenAuth.kt`（改） | 新增 `DIAG_WORKER_TOKEN_HEADER = "X-Diag-Worker-Token"` |

### 6.3 云主机 worker（egress-only，纳入版本管理部署到主机）

| 组件 | 路径 | 职责 |
|------|------|------|
| poller | `scripts/diag-worker/poll.sh`（新） | 常驻循环：`GET /diag/work/jobs` → 按 phase 调用对应流程 → `POST /result`；systemd/tmux 保活；poll 间隔 60–120s |
| 诊断流程 | `scripts/diag-worker/run-diagnose.sh` + `prompts/diagnose.md` | clone @gitSha → `claude -p` 用 diagnose prompt（禁改码，输出结构化根因）→ 回传 |
| 修复流程 | `scripts/diag-worker/run-fix.sh` + `prompts/fix.md` | 同 workdir `checkout -b diag-fix/<jobId>` → `claude -p` 用 fix prompt（最小修复）→ 跑 `./gradlew :app:testDebugUnitTest`（资源允许）→ commit → `git push` 分支 →（pr 模式）拼接 compare URL → 回传。**纯 git，不用 gh** |
| 凭证 | 主机环境 | git/SSH 已配（公开仓库 clone + push）。**不用 gh**：pr 模式只推分支 + 拼接 compare URL，用户浏览器开 PR |

**Claude Code 调用模板（要点）**：
- 诊断 prompt：注入 `description` + 脱敏 `logs` + `crashTrace` + `gitSha`；要求定位到 `file:line`、给根因解释与修复方向；**明确禁止修改任何文件**；输出 JSON `{rootCause, suspectFiles:[], suggestedFix}`。
- 修复 prompt：注入确认后的 `rootCause`；要求在 `diag-fix/<jobId>` 分支做**最小**修改；输出改了哪些文件 + 是否跑了测试。
- 成本护栏：`--max-turns` 限定迭代；每阶段 wall-clock 超时；上下文靠裁剪后的日志/崩溃栈（不灌全仓）。

## 7. 边界与异常

- **worker 连不上 GLM / Claude Code 崩**：回 `*_FAILED` + 错误日志 → chat 透出 → 可重试。
- **clone 失败**（gitSha 不存在 / 网络抖动）：`DIAGNOSE_FAILED` 带明确原因。
- **测试 OOM/爆盘**（6G 可用内存/19G 盘跑 NDK 全量风险高）：修复仍推，但标记 `FIXED_UNVERIFIED`（`tested=false`），chat 警告用户。
- **worker 掉线**（云主机被删/重启）：任务滞留 QUEUED/FIX_REQUESTED；worker 恢复后补跑；任一阶段超 ~1h → 标超时通知。
- **bundle 过大**：服务端限长，手机端截断环形缓冲（日志 ≤ ~1000 行，见 6.1）。
- **重复/滥用上报**：`RateLimiter` 限每账号/设备频率。
- **pr 模式**：仅推分支 + 回传 compare URL（字符串拼接，非 GitHub API），用户浏览器开 PR；无 gh / 无 token 依赖。

## 8. 红线与规范

- **[PRIVACY/ADR-008]**：诊断包**纯文本**，**绝不含图片/视频文件字节**；`DiagSanitizer` 强制 redact media 路径/token/邮箱/GPS/人物名；`PrivacyGuard` 把诊断归类为 METADATA（可远程）。worker 只 clone **源码仓库**，不经手任何用户媒体。
- **[I18N]**：chat「诊断」入口、根因/确认/结果展示等所有新增文案必须进 `values/strings.xml`、`values-zh-rCN/`、`values-zh-rTW/`（本项目三语）。
- **[PERF]**：日志环形缓冲读取在 IO 线程；chat 状态轮询退避（如 5s→15s→30s），避免空转。
- **[AGENT-FIRST]**：worker 凭证（worker token、git/gh）只存云主机环境，不入仓库；状态用枚举（`DiagStatus`）不用布尔组合。
- **[SECURITY]**：worker token 静态共享密钥，HTTPS 传输，env 注入无静态默认；手机端 token 仅留在认证 header，不进诊断包（脱敏兜底）。

## 9. 测试

- **服务端**：`DiagRoute` 端点 + 状态机转移的 JVM 测试（复用 `server/src/test/...TestDb` in-memory Exposed 模式）；worker token 鉴权正反例。
- **手机端**：`DiagSanitizer` redact 正确性（media 路径/token/邮箱/GPS/人物名各一组用例）、`DiagBundleCollector` 组装与截断——均为纯 JVM 单测，无 Android 依赖。
- **worker**：poller 胶水（领任务/clone/branch/push/解析 result）用 stub 的「假 Claude Code」脚本测；真实 Claude Code 调用走人工 E2E。
- **E2E 验收**：真机 chat 描述一个已知小问题 → 服务器入队 → worker 诊断 → 确认 → 修复到 `diag-fix/*` 分支或 PR，全链路跑通。

## 10. 成本控制（设计约束）

- poll 不调 LLM → 免费；云主机常驻固定 0.6%/天（Kimi 额度）。
- 推理走 GLM 按量计费。护栏：Claude Code `--max-turns`、每阶段超时、日志/崩溃栈裁剪、按 gitSha 只 clone 必要范围。
- `RateLimiter` 限上报频率，防失控烧额度。

## 11. MVP 范围

**纳入**：chat「诊断」触发 + 文本诊断包（日志+版本+崩溃栈）→ 诊断 → 用户确认 → 修复到 `diag-fix/<jobId>` 分支或 PR；服务端队列+状态机；worker 常驻 poll。

**二期再做**：崩溃自动捕获上报（无描述也诊断）、脱敏后 DB 聚合元数据、Kimi Scheduled Tasks 定时唤醒省日费、ChatDiagnoseCapability 意图路由（替代手动入口）。

## 12. 验收标准

- [ ] chat 输入栏有「诊断」入口，发起后附带脱敏诊断包 `POST /diag/report` 成功入队。
- [ ] `DiagSanitizer` 单测覆盖 media 路径/token/邮箱/GPS/人物名 redact，纯文本无媒体字节。
- [ ] 服务端 5 个端点 + 状态机 JVM 测试通过（含 worker token 鉴权正反例）。
- [ ] worker 常驻 poller 能领 QUEUED 任务，clone @gitSha，回传结构化根因，任务转 DIAGNOSED。
- [ ] 用户在 chat 确认 + 选 push/pr 后，任务转 FIX_REQUESTED；worker 用**纯 git** 修复并推 `diag-fix/<jobId>` 分支（pr 模式额外回传 compare URL），转 FIXED/FIXED_UNVERIFIED；全程不用 gh。
- [ ] 根因、确认、结果（分支/compare URL/是否自检）均回到同一段 chat 对话展示。
- [ ] 三语文案同步；编译通过（`:app:assembleDebug` + `gradlew -p server build`）无新增错误。
- [ ] E2E：真机描述一个已知小问题，全链路跑通到 `diag-fix/*` 分支或 PR。
