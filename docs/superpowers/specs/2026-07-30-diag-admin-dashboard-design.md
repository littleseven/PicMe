# 远程诊断管理后台可视化（/admin/diag）

> **日期**：2026-07-30
> **状态**：已确认，实施中
> **范围**：`server/`（admin 后台新增只读观测页）
> **关联**：`docs/superpowers/specs/2026-07-30-remote-diagnosis-design.md`（远程诊断主设计）

## 1. 背景与目标

远程诊断链路（chat 上报 → server 队列/状态机 → 云主机 worker 诊断/修复）已实现并合并 main。目前缺乏一个**全局观测入口**来了解：

- **问题上报**：谁、在什么设备/版本/gitSha 上、报了什么问题、当前状态。
- **worker 诊断**：诊断进展、根因、诊断成功/失败、worker 是否在线。
- **修复**：修复分支、compare URL、是否自检、修复成功/失败。

手机端 chat 只能看到本人 job，worker 是 egress-only 无法被外部访问，因此**唯一能聚合全局状态的位置是 server**。server 已有 admin 后台（`/admin/**`，cookie 认证，kotlinx.html + 内联 CSS + SVG 图表，统一导航）。本页就在此体系内新增一个只读观测页。

## 2. 设计决策（已与用户确认）

1. **落点**：server admin 后台新页 `/admin/diag`（列表）+ `/admin/diag/{id}`（详情）。复用现有 admin 设计系统/cookie 认证/DB 直读。
2. **worker 健康**：从任务活动**推断**（零新基建，不引入心跳端点/表）。用最近领任务时间 + 等待任务滞留时长推断在线/疑似离线/空闲。
3. **刷新**：手动刷新 + 可选自动轮询（`?auto=30|60`，`setInterval` 重载）。无 SSE/WebSocket，零新依赖。
4. **只读 → 可管理（二期已于 2026-07-31 落地）**：初期 admin 仅观测；二期已新增「删除 / 废弃 / 激活」管理操作，详见 `docs/superpowers/specs/2026-07-31-diag-admin-actions-design.md`（新增 `ARCHIVED` 状态、`DiagService` 三个写方法、worker 回传状态守卫、列表行 + 详情页操作按钮）。
5. **文案**：沿用现有 admin 页惯例——中文内联（server 端内部工具，不进 app 的三语 `values/strings.xml`；现有 7 个 admin 页均为中文内联）。

## 3. 架构与落点

```
/admin/diag            ── 列表页（adminGuard cookie 鉴权）
  ├─ worker 健康推断条（最近领任务 / 等待任务数 / 推断状态）
  ├─ 刷新控件（手动 + 30s/60s 自动轮询开关）
  ├─ 状态分布统计卡（QUEUED / DIAGNOSED / FIX_REQUESTED / 已修复 / 失败·超时）
  └─ 任务列表表格（id / 状态徽章 / 描述预览 / 设备·gitSha / 修复分支或 compare 链接 / 创建·更新时间）→ 点行进详情

/admin/diag/{id}       ── 详情页
  ├─ 状态卡片（状态 / 设备 / gitSha / 修复方式 / 创建·更新时间）
  ├─ 问题描述
  ├─ 根因分析（pre-wrap 渲染）
  ├─ 修复交付（分支 / compare URL / tested）
  ├─ 诊断包（已脱敏：app 版本 / 设备型号 / 安卓版本 / gitSha / 日志 / 崩溃栈，pre-wrap）
  ├─ worker 日志（失败诊断文本，仅失败时）
  └─ 时间线（创建 → worker 领取 → 最后更新）
```

不改 `Application.kt` 拦截器：`/admin/**` 前缀已对 app-token 拦截器放行，`adminGuard` 接管 cookie 认证。

## 4. 数据（`diag_job` 表，已存在，无需 migration）

列：`id, owner_token_hash, device_id, description, bundle_json, git_sha, status, root_cause, fix_mode, fix_branch, compare_url, tested, worker_log, created_at, updated_at, claimed_at`

`status` ∈ `DiagStatus`：`QUEUED / DIAGNOSED / FIX_REQUESTED / FIXED / FIXED_UNVERIFIED / DIAGNOSE_FAILED / FIX_FAILED / TIMED_OUT / ARCHIVED`。

## 5. worker 健康推断（纯函数，可单测）

```
DIAG_STALE_MS = 5 分钟（≈ worker poll 间隔 60s 的 5 个周期）

inferDiagWorkerHealth(now, lastClaimAt, pendingCount, oldestPendingCreatedAt):
  if pendingCount == 0          → IDLE        （无等待任务，worker 没事做；无法判断存活，标注「空闲」）
  oldestAge   = now - oldestPendingCreatedAt（无则 ∞）
  lastClaimAge = now - lastClaimAt            （无则 ∞）
  if oldestAge > 5min && lastClaimAge > 5min → LIKELY_OFFLINE （有任务等待但近期未被领取）
  else                                       → ONLINE        （近期已领取，正在处理或即将处理）
```

判定信号说明：`claimed_at` 是 worker 唯一可观测的「我来过」痕迹（只有 worker 写）；`updated_at` 会被用户确认也写，故不单独用作 worker 活跃信号。纯函数与 DB 解耦，便于单测。

## 6. 组件拆分

| 组件 | 路径 | 职责 |
|------|------|------|
| DTO + 查询 | `server/.../admin/AdminQueries.kt`（改） | 新增 `DiagStats / DiagListRow / DiagDetailRow / DiagWorkerActivity / DiagWorkerHealth`；`diagStats() / diagList(limit) / diagDetail(id) / diagWorkerActivity(now)`；纯函数 `inferDiagWorkerHealth(...)`。`deviceId` 沿用 `maskDeviceId` 脱敏 |
| 视图 | `server/.../admin/AdminViews.kt`（改） | `diagListPage(...) / diagDetailPage(...)`；导航加「诊断」链接；`diagStatusBadge / diagHealthBar / diagRefreshControl / renderBundle / diagTimeline` 片段；`relTime` 相对时间；CSS（diag 状态徽章色 / 健康条 / pre-wrap 日志块 / 时间线）追加到内联 `<style>` |
| 路由 | `server/.../admin/AdminRoutes.kt`（改） | `get("/diag")` + `get("/diag/{id}")`，`adminGuard` 鉴权；`parseAutoRefresh` 白名单解析 `auto` query（0/30/60） |

bundle（`bundle_json`）解析用 `appJson`，失败容错返回空；视图只读展示，不回写。

## 7. 隐私与可见性

- `bundle_json`：app 端已由 `DiagSanitizer` 脱敏（media 路径/token/邮箱/GPS/人物名），纯文本无媒体字节，admin 可见。
- `root_cause` / `worker_log`：Claude Code 对**公开源码仓库**的输出（含 `file:line`、源码路径），无用户媒体；admin 可见。`worker_log` 仅失败时含截断的模型输出用于排查。
- `device_id`：沿用 `maskDeviceId` 脱敏；`owner_token_hash` 不展示（无观测价值）。
- `compare_url` / `fix_branch`：渲染为 GitHub compare 链接（用户浏览器开 PR 用）。

## 8. 测试（server JVM，纯 JVM 无浏览器）

- **视图**（`AdminViewsTest`）：喂 fixture DTO，断言列表页含状态徽章/统计卡/健康条、详情页含根因/修复/诊断包/时间线。
- **路由**（`AdminRoutesTest`）：种 `diag_job` 行 → cookie 鉴权访问 `/admin/diag` 与 `/admin/diag/{id}`，断言状态码 + 关键内容；无 cookie → 跳登录；未知 id → 404。
- **推断逻辑**（`AdminQueriesTest`）：纯函数 `inferDiagWorkerHealth` 的 IDLE / ONLINE / LIKELY_OFFLINE 三分支 + 边界（从未领取、刚领取、滞留超阈值）。

## 9. 验收标准

- [ ] 导航出现「诊断」入口；`/admin/diag` 列表页展示 worker 健康条 + 状态统计 + 任务表。
- [ ] `/admin/diag/{id}` 详情页展示描述/根因/修复/脱敏诊断包/worker 日志/时间线。
- [ ] 手动刷新可用；`?auto=30|60` 自动轮询生效，停止后回到手动。
- [ ] worker 健康推断三态正确（单测覆盖）。
- [ ] 无 cookie 跳登录；未知 id 404。
- [ ] `gradlew -p server test` 通过，无新增编译错误。
