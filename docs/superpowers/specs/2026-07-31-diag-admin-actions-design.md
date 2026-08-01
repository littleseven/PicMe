# 诊断任务管理操作（删除 / 废弃 / 激活）

> **状态**：⛔ SUPERSEDED（2026-08-01）——诊断模式（含 /admin/diag 页）已随诊断链路整体移除，能力合并入 AI 工程师模式，见 `2026-08-01-ai-engineer-diag-merge-design.md`。本文仅作历史存档。

> **日期**：2026-07-31
> **状态**：已确认，待实现
> **范围**：`server/`（admin 诊断页 `/admin/diag` 由只读升级为可管理）
> **关联**：`docs/superpowers/specs/2026-07-30-diag-admin-dashboard-design.md`（诊断可视化页主设计，第 2 节「MVP 只读」所述二期写操作即本设计）

## 1. 背景与目标

`/admin/diag` 列表页 + 详情页已上线（只读观测）。实际运维中，admin 需要对任务做主动处置：

- **删除**：清理无效/重复/测试任务，物理移除记录。
- **废弃**：明知无需处理（如重复上报、已过时、非问题），标记后 worker 不再领取，但记录保留、可追溯、可恢复。
- **激活**：对停摆的任务（已废弃、诊断失败、超时、已修复但想重来等）重新入队，让 worker 从头重跑诊断。

本设计把诊断页从「只读观测」扩展为「可管理」，复用现有 admin 写操作范式（`POST + adminGuard cookie 鉴权 + JS confirm + 302 回列表`）。

## 2. 已确认决策

1. **激活语义＝全场景重置回 `QUEUED`**：把 `ARCHIVED / DIAGNOSE_FAILED / FIX_FAILED / TIMED_OUT / DIAGNOSED / FIXED / FIXED_UNVERIFIED` 的任务统一重置为 `QUEUED`，清空旧根因/修复产出，让 worker 从头重跑诊断。拒绝 `QUEUED`（本就在队列）与 `FIX_REQUESTED`（worker 正在修，改它有 race）。
2. **废弃＝新增 `ARCHIVED` 状态**：废弃任务保留在列表、带「已废弃」灰色徽标、可被「激活」恢复（软删除语义）。状态分布卡新增「已废弃」计数。
3. **操作入口＝列表行 + 详情页都放**：列表每行新增「操作」列，详情页顶部新增 `actions-bar`，两处都按状态显隐「废弃 / 激活 / 删除」。
4. **worker 回传加状态守卫（取舍 A）**：`submitDiagnosis` 仅当 `status==QUEUED` 才接受；`submitFix` 仅当 `status==FIX_REQUESTED` 才接受。挡掉废弃后/迟到的回传覆盖，向后兼容（正常流程回传时 status 本就是这两个），顺带修正「迟到/重复回传无条件覆盖」的潜在隐患。
5. **激活时保留原 `created_at`（取舍 A）**：「创建时间」语义不变（仍是首次上报时间）；重激活的老任务因 `claimNextJob` 的 `createdAt ASC` 排序自然靠前 → 优先重处理积压。
6. **i18n**：server admin 页是中文内联内部工具，不进 app 三语 `values/strings.xml`（主设计第 2 节决策 5 已明确），不受 i18n 红线约束。

## 3. 状态机变更

`DiagStatus` 枚举新增 `ARCHIVED`。无需 DB migration——`status` 是 `varchar(24)` 存 `DiagStatus.name` 字符串。

```
任何状态 ──废弃──▶ ARCHIVED            （worker 不领：claimNextJob 只领 QUEUED / FIX_REQUESTED）
ARCHIVED / DIAGNOSE_FAILED / FIX_FAILED / TIMED_OUT
  / DIAGNOSED / FIXED / FIXED_UNVERIFIED ──激活──▶ QUEUED（清空旧产出，重入队）
任何状态 ──删除──▶ 物理删除
```

`claimNextJob` 不变（`ARCHIVED` 天然不在领取集合）。`diagWorkerActivity` 的 `pendingCount` 只数 `QUEUED / FIX_REQUESTED`，`ARCHIVED` 不算 pending。

## 4. 组件拆分

| 组件 | 路径 | 职责 |
|------|------|------|
| 领域服务 | `server/.../diag/DiagService.kt`（改） | 新增 `deleteById(id)` / `archive(id)` / `activate(id): Boolean`；给 `submitDiagnosis` / `submitFix` 加状态守卫 |
| DTO + 查询 | `server/.../admin/AdminQueries.kt`（改） | `DiagStats` 加 `archived: Int`；`diagStats()` 的 `when` 加 `ARCHIVED` 分支 |
| 视图 | `server/.../admin/AdminViews.kt`（改） | `diagStatusBadge` 加 `ARCHIVED`；列表表头加「操作」列 + 每行 `row-actions`；详情页 `actions-bar`；状态分布卡加「已废弃」 |
| 路由 | `server/.../admin/AdminRoutes.kt`（改） | `POST /admin/diag/{id}/{delete,archive,activate}`，`adminGuard` 鉴权 + 解析 id + 调服务 + redirect |

### 4.1 `DiagService` 新增方法（admin 上帝视角，不带 owner 校验）

- `deleteById(id)`：`DiagJobs.deleteWhere { id eq id }`，物理删除。
- `archive(id)`：`UPDATE … status=ARCHIVED, updatedAt=now`，任意源态允许。
- `activate(id): Boolean`：事务内读当前 status；仅当 `status ∉ {QUEUED, FIX_REQUESTED}` 时，置
  `status=QUEUED` + 清空 `rootCause / fixMode / fixBranch / compareUrl / workerLog = null`、`tested=0`、`claimedAt=null`、`updatedAt=now`；返回是否转移成功。`createdAt` 保留不变。

### 4.2 worker 回传守卫

- `submitDiagnosis`：`update where { (id eq id) and (status eq QUEUED) }`，看 `updateCount`；为 0 表示已被废弃/状态已变，忽略回传。
- `submitFix`：`update where { (id eq id) and (status eq FIX_REQUESTED) }`，同上。
- `submitDiagnosis / submitFix` 签名不变（仍按原 `require` 校验入参 status 合法性），仅在 `update` 条件上加状态约束。

### 4.3 视图显隐规则

列表行与详情页 `actions-bar` 的按钮显隐（按 `status`）：

| 按钮 | 样式 | 显示条件 | confirm 文案 |
|------|------|----------|--------------|
| 废弃 | `btn-sm`（灰） | `status != ARCHIVED` | 「确定废弃该诊断任务？worker 将不再处理（仍保留记录，可稍后激活）。」 |
| 激活 | `btn-sm btn-go`（绿） | `status ∈ {ARCHIVED, DIAGNOSE_FAILED, FIX_FAILED, TIMED_OUT, DIAGNOSED, FIXED, FIXED_UNVERIFIED}` | 「确定重新激活？将清空已有根因/修复并重置为待诊断，重新入队。」 |
| 删除 | `btn-sm btn-danger`（红） | 恒显 | 「确定删除该诊断任务？记录将被物理删除，不可恢复。」 |

均为 `<form method=post>` + `onsubmit="return confirm(...)"`，action 指向对应路由。

### 4.4 状态分布卡

`diagListPage` 的状态分布卡由 5 张（待诊断/待确认/待修复/已修复/失败·超时）增至 6 张，末尾追加「已废弃（stats.archived）」。

## 5. 测试（server JVM，沿用 `TestDb` + `runBlocking` 范式）

- **`DiagServiceTest`**：
  - `deleteById` 物理删除（查不到行）。
  - `archive`：`QUEUED` / `FIX_REQUESTED` / `FIXED` 等态 → `ARCHIVED`。
  - `activate`：`ARCHIVED` / `DIAGNOSE_FAILED` / `TIMED_OUT` / `FIXED` → `QUEUED` 且 `rootCause/fixBranch/...` 清空、`tested=false`、`claimedAt=null`、`createdAt` 不变；`QUEUED` / `FIX_REQUESTED` 返回 `false` 且不改状态。
  - 回传守卫：先把任务置 `ARCHIVED`，再 `submitDiagnosis(DIAGNOSED)` / `submitFix(FIXED)` → 状态仍是 `ARCHIVED`（被忽略）。
- **`AdminRoutesTest`**：种 `diag_job` 行 → 带 cookie POST 三个路由 → 断言 302 回 `/admin/diag` + DB 状态正确流转；无 cookie → 跳登录；非法 id → 静默 redirect（沿用现有 delete 范式）。
- **`AdminViewsTest`**：列表页对 `ARCHIVED` 行显「激活/删除」不显「废弃」、对 `QUEUED` 行显「废弃/删除」不显「激活」；`ARCHIVED` 徽标文案「已废弃」；详情页含 `actions-bar`；统计卡含「已废弃」计数。
- **`AdminQueriesTest`**：`diagStats` 对含 `ARCHIVED` 行的数据返回正确的 `archived` 计数。

## 6. 文档同步（与代码原子提交）

- 更新 `docs/superpowers/specs/2026-07-30-diag-admin-dashboard-design.md`：第 4 节状态机加 `ARCHIVED`；第 2 节「MVP 只读」标注二期写操作已落地，并补「管理操作」小节指向本设计。
- 本设计文档（`2026-07-31-diag-admin-actions-design.md`）随实现一并提交。

## 7. 不做的（YAGNI）

- 不加批量操作、不加操作审计表、不为 `ARCHIVED` 单独建索引/迁移、不加 SSE/WebSocket 实时刷新。
- 废弃任务的 `compare_url` / `fix_branch` 在列表/详情仍按现状渲染，保留可追溯。

## 8. 验收标准

- [ ] 列表每行「操作」列与详情页 `actions-bar` 出现「废弃/激活/删除」三按钮，且按状态正确显隐。
- [ ] 废弃后任务状态变 `ARCHIVED`，列表仍可见且带「已废弃」徽标，worker 不再领取（单测覆盖）。
- [ ] 激活后任务重置为 `QUEUED` 且旧根因/修复字段清空、`claimedAt` 清空、`createdAt` 不变（单测覆盖）。
- [ ] 删除后任务物理消失（单测覆盖）。
- [ ] worker 回传守卫生效：`ARCHIVED` 后的迟到回传被忽略（单测覆盖）。
- [ ] 状态分布卡新增「已废弃」计数且数值正确。
- [ ] 无 cookie 三路由均跳登录。
- [ ] `gradlew -p server test` 通过，无新增编译错误。
