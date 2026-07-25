# 未注册设备管理后台页设计（Unregistered Device Admin Page）

- **日期**：2026-07-25
- **服务端版本**：0.6.3 → 0.6.4（建议，发布时定）
- **状态**：已与用户对齐，待实施
- **范围**：在 `server/` 管理后台新增「未注册设备」Tab 页，按 device 维度展示 `anonymous_device` 表中的访客记录，支持单条删除。**纯服务端 SSR 改动**：不动客户端、不改表结构、不新增依赖。

---

## 1. 背景与现状

### 1.1 未注册用户是谁

客户端未做邮箱注册、通过 `X-Device-Id` 请求头以**访客身份**调用 LLM 的设备。服务端在首次访客调用时向 `anonymous_device` 表插入一行，按 `device_id` 计量试用额度（全局上限 `GUEST_LLM_QUOTA`，默认 100）。这些行是「未注册用户」在服务端的**唯一可观测痕迹**。

### 1.2 为什么后台看不到

| 诉求 | 现状 | 结论 |
|---|---|---|
| 注册用户列表 | `/admin/users` 查 `account` 表（email / token / 额度） | 现成 |
| 未注册（访客）设备 | `anonymous_device` 表有数据，但**后台无任何页面展示**；且 `GuestService` 注释明确「访客调用不写 `llm_call_log`」 | **完全不可见** |

→ 管理员当前只能看到注册用户，看不到数量可能更多的未注册访客设备。本设计补上这一页。

---

## 2. 已锁定决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 入口形式 | **Tab 切换**：`/admin/users` 与 `/admin/devices` 顶部共享二级 Tab，各自独立 SSR 路由 | 贴合「在用户列表页里的子页面」原意；独立 URL 可刷新/分享；与现有「每页独立路由 + navBar 片段」风格一致，无客户端状态 |
| 字段范围 | 复用 `anonymous_device` 现有 4 字段，**不扩展采集** | YAGNI；纯服务端改动；现有字段已能回答「有多少未注册设备、活跃度、额度使用」 |
| 操作能力 | 查看 + **单条删除** | 底层 `GuestService.deleteByDeviceId` 已存在，补一个 `deleteById` 即可；与 `user` 列表已有删除操作一致；能应对用户清数据诉求 / 测试脏数据 / 合规请求 |
| device_id 展示 | **掩码**（前 6…后 4）+ 复制按钮 | device_id 非密钥但是设备隐私标识；与 token 列的脱敏 + 复制体验保持一致 |
| 额度展示 | 「已用 / 全局上限」 | 上限不在行内，读 `config.guestLlmQuota`，随 env 实时生效 |
| 删除标识 | 路由用**数据库自增 `id`**，不把 device_id 字符串塞进 URL | 与 `/admin/users/{id}` 一致；避免 URL 编码与泄露 |

### 非目标（YAGNI，本期不做）

- **不**扩展采集 app 版本 / 平台 / 机型（跨端改造，留待后续）
- **不**做调用明细详情页（访客不写 `llm_call_log`，无明细可看）
- **不**做批量清理过期设备 / 搜索 / 分页（当前规模「全量 + limit 兜底」足够；量大时再加）

---

## 3. 架构总览

全部改动落在 `server/`，同一二进制。

| 文件 | 动作 | 职责 |
|---|---|---|
| `admin/AdminRoutes.kt` | 改 | 新增 `GET /admin/devices`、`POST /admin/devices/{id}/delete`、`GET /admin/devices/{id}/raw`；`adminRoute(...)` 签名增加 `guestLlmQuota: Int` |
| `admin/AdminQueries.kt` | 改 | 新增 `DeviceRow` DTO + `devicesList(limit)` + `deviceRawId(id)` |
| `admin/AdminViews.kt` | 改 | 新增 `devicesPage(rows, guestLimit)`；新增共享片段 `userTabs(currentPath)`，并在 `usersPage` 顶部挂上 |
| `auth/GuestService.kt` | 改 | 新增 `deleteById(id: Int)`，与现有 `deleteByDeviceId(deviceId)` 并列（后者保留，供客户端删除路由用） |
| `Application.kt` | 改 | `adminRoute(config.adminToken, cosService)` → 补传 `config.guestLlmQuota` |
| `server/AGENTS.md` | 改 | 路由清单补 3 条新路由 |
| `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md` | 改 | 管理后台章节补未注册设备页（若存在该章节） |

**零新增依赖、零表结构变更、零客户端改动。**

---

## 4. 数据模型 — 复用 `anonymous_device`，不改表

```sql
-- 现有表，本设计只读 + 按 id 删除，不改结构
CREATE TABLE anonymous_device (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id      TEXT NOT NULL UNIQUE,
  llm_calls_used INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,   -- 首次访客调用
  last_seen_at   INTEGER NOT NULL    -- 最近一次访客调用
);
```

- **额度上限不在行内**：访客上限是全局 `config.guestLlmQuota`（env `GUEST_LLM_QUOTA`，默认 100），页面展示「`llm_calls_used` / `guestLlmQuota`」。
- **写入时机不变**：行仍由 `GuestService.checkAndIncrementQuota` 在首次访客调用时插入；本设计只新增读 + 删。

---

## 5. 路由设计（`AdminRoutes.kt`）

均挂在 `route("/admin")` 下，顶部统一 `adminGuard(adminToken)`（与现有页面一致：空 token → 503；cookie 无效 → 跳登录）。

| 方法 | 路径 | 行为 |
|---|---|---|
| `GET` | `/admin/devices` | `AdminQueries.devicesList(1000)` → `AdminViews.devicesPage(rows, guestLlmQuota)` |
| `GET` | `/admin/devices/{id}/raw` | 返回 `{ "device_id": "<完整>" }` JSON，供列表「复制」按钮调用（cookie 鉴权，不进 HTML） |
| `POST` | `/admin/devices/{id}/delete` | `GuestService.deleteById(id)` → 重定向回 `/admin/devices` |

- `adminRoute(adminToken, cosService, guestLlmQuota: Int)` 签名增加第三参；`Application.kt:140` 调用处补传 `config.guestLlmQuota`。
- 参数解析：`id = call.parameters["id"]?.toIntOrNull()`，非法 → 400（与 `/admin/users/{id}` 同形）。

---

## 6. 页面设计（`AdminViews.kt`）

### 6.1 共享二级 Tab 片段 `userTabs(currentPath)`

`/admin/users` 与 `/admin/devices` 顶部各渲染一组二级 Tab：

```
〔注册用户 (N)〕  〔未注册设备 (M)〕
```

- 链接分别指向 `/admin/users`、`/admin/devices`；
- `currentPath` 命中者加 `active` 类（复用现有 navBar 的点亮思路，无需额外 JS）；
- `(N)` / `(M)` 分别为注册用户数、未注册设备数，由各页面自行计算后传入。
- 主导航 `navBar` **不变**（「用户」仍指向 `/admin/users`）；二级 Tab 是「用户模块」内部导航。`usersPage` 也需在标题上方挂同一片段，使两个页面视觉对称。

### 6.2 `devicesPage(rows: List<DeviceRow>, guestLimit: Int)`

- **顶部统计行**：`未注册设备（共 M 条，仅展示最近 ${rows.size} 条，按最后活跃倒序）`。
- **表头**：`ID | Device ID | 额度（已用 / 上限） | 首次出现 | 最后活跃 | 操作`
- **行渲染**：
  - `ID`：数据库 `id`
  - `Device ID`：掩码 `前6…后4` + 「复制」按钮，`onclick="devCopy(id, this)"`（打 `/admin/devices/{id}/raw`，复用现有 `tokCopy` JS 模式）
  - `额度`：`{used} / {guestLimit}`；`used >= guestLimit` 时整格标红（`span("err")`）
  - `首次出现` / `最后活跃`：`fmtTs(...)`（UTC，与现有页面一致）
  - `操作`：删除按钮，`onsubmit="return confirm('确定删除该设备记录？\\n将清除其访客用量计数，操作不可恢复。')"`
- **复制 JS**：与 `usersPage` 的 `tokCopy` 同形，仅改 URL 前缀与字段名。
- **空态**：无设备时显示 `暂无未注册设备`。

### 6.3 DTO（`AdminQueries.kt`）

```kotlin
data class DeviceRow(
    val id: Int,
    val deviceIdMasked: String,   // 前6…后4；空短串兜底，同 maskToken 风格
    val llmCallsUsed: Int,
    val createdAt: Long,
    val lastSeenAt: Long,
)
```

---

## 7. 错误处理

| 场景 | 响应 | 对齐 |
|---|---|---|
| `id` 非数字 | 400 `bad request` | `/admin/users/{id}` |
| 设备不存在（`/raw` 取不到） | 404 `not found` | `/admin/users/{id}/token` |
| 删除异常 | 忽略异常 → 重定向回 `/admin/devices` | `/admin/channels/{id}/delete` |

---

## 8. 测试（`src/test/kotlin/.../admin/` 与 `.../auth/`）

复用现有 `TestDb` 基建。

- **`AdminQueriesTest`**
  - `devicesList` 按 `lastSeenAt DESC` 排序、`limit` 截断生效
  - `deviceRawId(id)` 命中 / 不命中（null）
- **`AdminRoutesTest`**
  - `GET /admin/devices`：未登录 → 重定向 `/admin/login`；登录后 → 200，HTML 含「未注册设备」标题
  - `GET /admin/devices/{id}/raw`：登录后返回 JSON `{device_id}`；不存在 → 404
  - `POST /admin/devices/{id}/delete`：登录后重定向 `/admin/devices`，且对应行被删
- **`GuestServiceTest`**（已有文件，追加）
  - `deleteById(id)` 删除正确行、id 不存在时无副作用

---

## 9. 文档同步（与本实现同原子提交）

- `server/AGENTS.md` 第 3 节路由清单补：
  - `GET /admin/devices` — 未注册设备列表（ADMIN_TOKEN）
  - `GET /admin/devices/{id}/raw` — 设备 id 复制（ADMIN_TOKEN）
  - `POST /admin/devices/{id}/delete` — 删除设备记录（ADMIN_TOKEN）
- `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`：若含管理后台章节，补未注册设备页一行。

---

## 10. 风险与注意

- **device_id 隐私**：属设备标识，管理员可见；已掩码 + 复制，与 token 列同级处理，未额外暴露。
- **删除语义**：仅清 `anonymous_device` 行（用量计数），不涉及其他表（访客无 `llm_call_log`，无关联明细）。
- **额度上限随配置变**：`guestLlmQuota` 每次请求从 `config` 实时传入页面，无缓存陈旧问题。
- **规模兜底**：`devicesList(1000)` 全量 + limit；当前访客量远未达此量级，若将来超限，页面顶部已有「仅展示最近 N 条」提示，再考虑分页。
