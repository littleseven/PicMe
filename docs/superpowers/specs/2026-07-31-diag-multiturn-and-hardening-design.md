# 诊断多轮澄清对话 + 三方链路加固

> **状态**：⛔ SUPERSEDED（2026-08-01）——诊断模式已合并入 AI 工程师模式，见
> `2026-08-01-ai-engineer-diag-merge-design.md`。本文仅作历史存档。

> **日期**：2026-07-31
> **状态**：已确认，待实现
> **范围**：`app/`（诊断对话模式 + 客户端加固）、`server/`（sweeper/透出/护栏）、`scripts/diag-worker/`（传递链/产出校验/模板安全）
> **关联**：`2026-07-30-remote-diagnosis-design.md`（主设计）；`2026-07-31-diag-three-fix-modes-design.md`（三模式）；本文档源自对当前实现的 review（2026-07-31）

## 1. 背景

远程诊断链路（app 上报 → server 队列 → worker 诊断 → 确认 → 修复）已落地，但存在两类问题：

1. **核心目标未达成**：原始目标是「App 侧以 chat 方式与 LLM 多轮对话定位问题和修复方法」，当前实现是一次性描述上报 + 单轮诊断，任何一端都没有多轮对话机制。用户描述不清时 worker 只能盲猜。
2. **Review 发现的链路缺陷**：任务可永久卡死（无 lease）、诊断建议未传递给修复阶段、修复空改动照推分支、失败原因不透出 app、上报无护栏等。

**已确认决策**：

- worker↔server **保持 HTTP 轮询**，不上 WebSocket（轮询零成本、省下的 ≤60s 相对分钟级 LLM 推理是噪声、bash worker 无 WS 客户端、长连接有 NAT 空闲切断等有状态失败模式）。
- 多轮对话采用**方案 A：App 侧澄清对话**——复用现有流式 chat LLM 做追问澄清，worker 保持单轮。Worker 驱动追问（方案 B）作为二期候选。

## 2. 多轮澄清对话设计（app 侧）

### 2.1 流程

```
用户点「诊断」toggle
   ↓ 自动新建独立会话（保证上下文纯净，摘要提取干净）
DiagChatSession：注入诊断 system prompt 的普通远程 LLM 对话
   ↓ 多轮流式对话（追问：哪个页面/复现步骤/必现吗/什么时候开始…）
   ├─ 分支1：LLM 给出自助修复建议（改设置/清缓存/已知问题规避）
   │         → 用户验证有效 → 会话结束，不进 worker 队列，零成本
   └─ 分支2：信息收敛 → LLM 输出 [DIAG_READY] 标记 + 结构化摘要
             → 气泡内嵌「提交诊断」按钮
             ↓ 用户点击
   POST /diag/report {description, conversationSummary, bundle(脱敏)}
             ↓ 之后完全走现有链路
   轮询 → 根因 → 三按钮确认 → 修复 → 结果回气泡
```

### 2.2 关键决策

1. **system prompt（app 内置常量）**：角色为 PoLang 诊断助手；目标是「用最少的追问收集可定位问题的信息」；内置产品功能清单（chat/相册/标签/相机/美颜/备份恢复）以便问出精准问题；约定收敛时输出 `[DIAG_READY]` 标记 + 固定格式摘要（问题现象 / 复现步骤 / 影响范围 / 用户已尝试的操作）。
2. **摘要由 LLM 生成，不由客户端拼接**：摘要质量高、体量可控（worker prompt 不灌整段对话）；`[DIAG_READY]` 是客户端可解析的显式信号（符合「显式优于隐式」原则）。客户端对摘要做长度截断兜底（≤ 4000 字符，与 server 上限一致）。
3. **「提交诊断」永远是用户手动动作**：LLM 只建议，不自动上报——用户保留对「什么信息发给服务器」的最终控制权（隐私姿态）。
4. **server 契约增量**：`/diag/report` 加可选字段 `conversationSummary`（向后兼容，旧版 app 不报也能用）；`description` 与 `conversationSummary` 都过 `DiagSanitizer`（修掉当前 description 不脱敏的泄露面）。
5. **成本**：澄清对话走用户自己配置的远程 LLM（现有 LLM 网关代理），不进 worker 队列就不产生 GLM 诊断费用。

### 2.3 App 侧组件改动

| 组件 | 路径 | 改动 |
|------|------|------|
| 诊断 system prompt | `app/.../features/chat/DiagPrompts.kt`（新） | 常量：角色设定 + 产品功能清单 + `[DIAG_READY]` 输出契约 |
| 诊断会话控制 | `app/.../features/chat/ChatViewModel.kt`（改） | 进入诊断模式时新建会话；diag 模式消息注入 system prompt 走现有流式通道；解析 `[DIAG_READY]` 提取摘要；「提交诊断」气泡按钮 |
| 摘要提取 | `ChatViewModel` 内私有函数 | 正则/标记解析 `[DIAG_READY]` 后的结构化摘要；截断兜底；解析失败不阻断（用户仍可手动提交，summary 为空退化为现状） |
| 提交按钮 UI | `app/.../features/chat/ChatScreen.kt`（改） | 检测到摘要气泡时渲染「提交诊断」按钮，点击走现有 `submitDiagnosis`（带 summary） |
| 脱敏 | `app/.../core/diag/DiagSanitizer.kt`（改） | description / conversationSummary 纳入脱敏 |
| 上报模型 | `app/.../data/remote/picme/DiagClient.kt`（改） | report 请求体加 `conversationSummary`（可空） |

## 3. Server 加固（4 项）

- **S1 · 任务回收 sweeper**（补 lease + 激活 TIMED_OUT）：`Application.kt` 启动周期协程（每 5 分钟扫一次）：
  - **领取回收**：QUEUED / FIX_REQUESTED 且 `claimedAt` 超 **15 分钟**未更新 → `claimedAt` 置空，任务重新可领。15min > worker 侧 `DIAG_PHASE_TIMEOUT=300s`，正常执行不会误回收，无需心跳。
  - **整体超时**：任意非终态超 **1 小时**未更新 `updatedAt` → 置 `TIMED_OUT`（激活当前定义了但无人设置的死状态）；app 轮询到 TIMED_OUT 提示用户可重试（admin 可「激活」重置 QUEUED 重跑，现有能力）。
- **S2 · 失败原因透出**：`GET /diag/jobs/{id}` 响应填充 `error`（取 `workerLog` 尾部截断 ~500 字符）与 `updatedAt`，补齐契约缺字段。
- **S3 · 上报护栏**：`/diag/report` 挂 `RateLimiter`（每账号 5 次/小时，429）；长度上限 description ≤ 2000、conversationSummary ≤ 4000、logs ≤ 200KB（超限 413）。
- **S4 · 启动自检**：`DIAG_WORKER_TOKEN` 为空时启动日志打 WARN（"diag worker 端点已禁用"），消除静默 401。

## 4. Worker 加固（3 项）

- **W1 · 接通诊断→修复传递链**：`run-diagnose.sh` 解析并回传全部三字段（`rootCause` / `suspectFiles` / `suggestedFix`）；server 新增 `suggested_fix` 列存储；fix 阶段 claim 响应返回它，`prompts/fix.md` 的 `__SUGGESTED_FIX__` 拿到真实值（当前永远为空字符串）。
- **W2 · 修复产出校验**：claude 返回后先查 `git status --porcelain`——**无改动直接回 `FIX_FAILED`（"模型未产生修改"），不再空 commit 照推分支**；模型的 `changedFiles` / `summary` 写入 `worker_log`，admin 详情页可见。
- **W3 · 模板替换改 python3**：弃用 sed 填模板（用户日志含 `|` / `&` / `\` 会破坏替换），改用 `python3` 读模板 + 环境变量安全替换（云主机 Ubuntu 自带 python3），消除注入面。

## 5. App 加固（5 项）

- **A1 · 确认按钮绑定 jobId**：`onDiagConfirm(dc.jobId, mode)` 不再丢弃参数，`confirmDiagnosis` 作用于按钮所在气泡的 job——修掉多次诊断时点旧气泡按钮误确认新 job。
- **A2 · 轮询终止保护**：诊断/修复轮询各加 30 分钟总超时，超时写气泡提示（三语）并退出协程，不再无限空转。
- **A3 · 崩溃栈链路接通**：全局 `UncaughtExceptionHandler` 把未处理异常栈落盘 `filesDir/diag/last_crash.txt`，`DiagBundleCollector` 有则附、上报成功后删除——补上主设计 §6.1 一直未实现的 crashTrace。
- **A4 · i18n 与文案修复**：补齐四语言缺失 key（`diag_sheet_title` / `diag_sheet_cancel`）；删除 `diag_root_cause` 中文版「弹窗」尾巴（与实际内嵌按钮不符）；auto 模式确认后用专属文案「修复中（自动合并）…」。
- **A5 · 死代码清理**：删除无引用的 `DiagController.kt` 及其单测（功能已被气泡内嵌按钮取代）。

## 6. 数据库迁移

`diag_job` 加两列：`conversation_summary TEXT NULL`、`suggested_fix TEXT NULL`。同时把 `DiagJobs` 加入 `Migrations.kt` 的 `createMissingTablesAndColumns`（当前遗漏，不加则新列不会自动迁移）。存量行两列为 NULL，行为同现状，向后兼容。

## 7. 契约变更汇总

| 端点 | 变更 |
|------|------|
| `POST /diag/report` | 请求体加可选 `conversationSummary`；限频 429；超长 413 |
| `GET /diag/jobs/{id}` | 响应补 `error`（失败时）、`updatedAt`；新增可能状态 `TIMED_OUT` |
| `POST /diag/work/jobs/{id}/result`（diagnose） | 回传体加可选 `suggestedFix` |
| `GET /diag/work/jobs`（fix phase） | 响应加 `suggestedFix` |

app 端 `DiagJobStatus` 解析需容忍新状态 `TIMED_OUT`（未知状态按非终态继续轮询/超时兜底，不 crash）。

## 8. 测试

- **Server**（`server/src/test`，in-memory Exposed 模式）：sweeper 领取回收与整体超时两路径、`error` 透出、限频 429、超长 413、`confirmFix(auto)` 回归、新旧 report 报文兼容。
- **App**（纯 JVM 单测）：`DiagSanitizer` 覆盖 description/summary；`ChatViewModel` 确认绑定 jobId、轮询超时、`[DIAG_READY]` 摘要提取（含解析失败兜底）；crash 落盘→收集→清除流程。
- **Worker smoke**（`scripts/diag-worker/smoke/`）：suggestedFix 三字段传递、空改动 → FIX_FAILED、含 `|` / `&` 字符的日志注入模板不损坏 prompt。
- **E2E**：真机走一遍「诊断对话 → 提交 → 根因 → 自动修复」全链路。

## 9. 显式不做（YAGNI）

- diag jobId 进程被杀后恢复轮询（DataStore 持久化）：本期不做，用户重新发起成本低。
- Worker 驱动追问协议（方案 B，`AWAITING_USER` 状态 + 消息表）：二期候选，本文档不预留协议字段。
- diag 会话跨设备恢复：不做。
- admin CSRF / SameSite、`Tables.kt` 注释过期、`MAX_LOG_LINES` 名不副实等 P2 项：记入 backlog，不进本期范围。
- WebSocket（worker↔server 或 app↔server）：不做，理由见 §1 决策。

## 10. 验收标准

- [ ] 诊断模式自动新建会话，注入 system prompt，可多轮流式对话；LLM 输出 `[DIAG_READY]` 后出现「提交诊断」按钮。
- [ ] 提交时 `conversationSummary` 随脱敏诊断包上报，server 存储并在 fix 阶段可用；description/summary 均过 sanitizer（单测）。
- [ ] worker 领而不回传 15 分钟后任务可重领；非终态超 1 小时转 TIMED_OUT，app 轮询到后提示（单测 + E2E）。
- [ ] 诊断的 `suggestedFix` 出现在 fix prompt 中（smoke 验证）；fix 无改动时回 FIX_FAILED 不产生空分支（smoke 验证）。
- [ ] 含 `|` / `&` 的日志不破坏 prompt 模板（smoke 验证）。
- [ ] app 失败气泡展示 server 返回的 `error`；多次诊断时点旧气泡按钮确认的是对应 job（单测）。
- [ ] `/diag/report` 超限 413、超频 429（server 单测）。
- [ ] 崩溃后下次诊断包携带 crashTrace，上报成功后落盘文件删除（单测）。
- [ ] 四语言文案同步；`DiagController.kt` 死代码删除。
- [ ] 编译通过（`:app:assembleDebug` + `gradlew -p server build` + worker smoke）无新增错误。
- [ ] E2E：真机诊断对话 → 提交 → 根因 → 自动修复，全链路跑通。
