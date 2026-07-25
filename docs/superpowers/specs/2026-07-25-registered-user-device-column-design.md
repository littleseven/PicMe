# 注册用户页 Device ID 列设计（Registered User Device ID Column）

- **日期**：2026-07-25
- **服务端版本**：0.8.0 → 0.8.1（建议，发布时定）
- **状态**：已与用户对齐，待实施
- **范围**：在管理后台 `/admin/users` 注册用户页加「Device ID」列，数据源为 `llm_call_log`（调用记录维度）。**跨端改动**：客户端 `:runtime-core` 让注册用户请求也带 `X-Device-Id`；服务端采集 + 存储 + 展示。
- **关联**：延续 `2026-07-25-unregistered-device-admin-page-design.md`（同为 device 维度后台可视化；复用 `maskDeviceId`、`adminGuard` 等既有能力）。

---

## 1. 背景与现状

注册用户页 `/admin/users` 展示 `account` 表（email / token / 状态 / 调用 / Token 用量 / 成本 / 最后活跃），**无 device_id**。

| 环节 | 现状 | 结论 |
|---|---|---|
| 客户端 LLM 请求 | `AgentConfigurator.createRemoteChatModel`（`runtime-core` `:201-210`）是 `if-else` 互斥：注册用户（`gatewayToken` 非空）只带 `X-App-Token`；访客才带 `X-Device-Id` | 注册用户**不发** device_id |
| 服务端采集 | `Application.kt:101` 解析 `X-Device-Id` 存 `DeviceIdKey`，但 `LlmRoute` 只在访客分支读；`UsageRecorder.log` 无 device_id 参数 | 注册路径**不读不存** |
| 存储 | `account` 表无 device_id 字段；`llm_call_log` 表无 device_id 列 | **无任何存储** |

→ 注册用户的 device_id **全链路未采集**。本设计补齐「客户端发送 → 服务端记录 → 后台展示」。

## 2. 已锁定决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 数据源 | **调用记录维度**：`llm_call_log` 加 `device_id` 列 | 覆盖所有调过 LLM 的注册用户；反映真实使用设备；增量采集，无需回填 |
| 客户端发送 | `AgentConfigurator` 把 `X-Device-Id` 从 `else` 提到 `if` 外，注册 + 访客都带 | 注册用户也持有稳定 `deviceId`（独立于 remoteConfig）；鉴权仍走 `X-App-Token`，多一个 header 不影响 |
| 展示口径 | 每用户「最近一次 `ok` 调用的 device_id」，掩码 | 一列单值，简单；复用 `maskDeviceId` |
| 列位置 | 「邮箱」列右侧 | 便于一眼对应用户与设备 |
| 空值 | 历史 / 未调过 LLM / 老日志 → 显示「—」 | 与现有 `API Token` 列空值同形 |

### 非目标（YAGNI）
- **不**做多设备列表（只显示最近一个）
- **不**回填历史 device_id（老日志保持 NULL）
- **不**动 `account` 表

---

## 3. 架构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `runtime-core/.../facade/AgentConfigurator.kt` | 改 | `createRemoteChatModel`：`X-Device-Id` 注入从访客 `else` 提到 `if` 外（注册 + 访客都带） |
| `server/db/Tables.kt` | 改 | `LlmCallLogs` 加 `deviceId` 列定义 |
| `server/db/Migrations.kt` | 改 | `createMissingTablesAndColumns` 补 `LlmCallLogs`（生产幂等 `ALTER TABLE ADD COLUMN`） |
| `server/analytics/UsageRecorder.kt` | 改 | `log` 加 `deviceId: String?` 参数并写入 |
| `server/llm/LlmRoute.kt` | 改 | 注册分支也读 `DeviceIdKey`；三处 `UsageRecorder.log` 调用传 `deviceId` |
| `server/admin/AdminQueries.kt` | 改 | `usersList` 取每用户最近 `ok` 调用 device_id；`UserRow` 加 `deviceIdMasked` |
| `server/admin/AdminViews.kt` | 改 | `usersPage` 在邮箱右侧加「Device ID」列 |
| 测试（见第 9 节） | 新增/追加 | 服务端 + 客户端 |

**服务端零新增依赖、零破坏性 schema 变更（仅加 nullable 列）。客户端改动向后兼容（多一个 header）。**

---

## 4. 数据模型

`llm_call_log` 加一列（nullable，历史与未带 header 的调用为 NULL）：

```sql
ALTER TABLE llm_call_log ADD COLUMN device_id TEXT;  -- 运行时由 SchemaUtils.createMissingTablesAndColumns 幂等补
```

`Tables.kt` 对应：
```kotlin
val deviceId = varchar("device_id", 128).nullable()   // 写入侧:X-Device-Id(访客) 或注册用户的设备 id;历史为 null
```

> 生产 `Migrations.run` 已有 `createMissingTablesAndColumns(Accounts, LlmChannels)`；本设计将其扩展为含 `LlmCallLogs`，启动即补列。

---

## 5. 客户端改动（`:runtime-core`）

`AgentConfigurator.createRemoteChatModel`（`:197-213`）当前：

```kotlin
if (config.gatewayToken.isNotBlank()) {
    builder.customHeader("X-App-Token", config.gatewayToken)
} else {
    // 访客
    val effectiveDeviceId = config.deviceId.ifBlank { deviceId }
    if (effectiveDeviceId.isNotBlank()) builder.customHeader("X-Device-Id", effectiveDeviceId)
}
```

改为：`X-App-Token` 仍按 token 有无注入；`X-Device-Id` **无条件**注入（注册 + 访客都带）：

```kotlin
if (config.gatewayToken.isNotBlank()) {
    builder.customHeader("X-App-Token", config.gatewayToken)
}
val effectiveDeviceId = config.deviceId.ifBlank { deviceId }
if (effectiveDeviceId.isNotBlank()) {
    builder.customHeader("X-Device-Id", effectiveDeviceId)
}
```

- 注册用户请求将同时带 `X-App-Token` + `X-Device-Id`。
- 服务端 `AppTokenAuth` 仍用 `X-App-Token` 鉴权，`X-Device-Id` 仅作采集，不影响鉴权/限流/额度。

---

## 6. 服务端采集

- `UsageRecorder.log` 签名增加 `deviceId: String?`，`insert` 时 `it[LlmCallLogs.deviceId] = deviceId`。
- `LlmRoute`：在 `tokenHash`/`deviceId` 取值后，注册与访客分支都把 `deviceId` 透传给三处 `UsageRecorder.log`（`ok` `:80` / `blocked_quota` `:65` / `upstream_error` `:103`）。注册分支原来不读 `deviceId`，现在读取 `call.attributes.getOrNull(DeviceIdKey)`。

---

## 7. 服务端展示

- `AdminQueries.usersList`：现有两次 `LlmCallLogs` 遍历（`ok` 聚合 + `lastActive`）中，在 `ok` 遍历时记录每用户「最近一条 `ok` 日志的 device_id」（按 `createdAt` 取最新）；`UserRow` 增加 `deviceIdMasked: String`（复用 `maskDeviceId`，空为「—」）。
- `AdminViews.usersPage`：表头在「邮箱」后插「Device ID」；行内在邮箱单元格后渲染掩码 device_id（无 `tok-copy`，纯展示）。

---

## 8. 边界与错误处理

| 场景 | 表现 |
|---|---|
| 历史日志（加列前） | `device_id` NULL → 列显示「—」 |
| 注册用户未调过 LLM | 无日志 → 列显示「—」 |
| 客户端未发版（注册用户还没带 header） | 新日志 `device_id` 仍 NULL → 「—」（APK 发版后开始积累） |

---

## 9. 测试

**服务端（`server/src/test/`）**
- `UsageRecorderTest`：`log` 写入后 `llm_call_log.device_id` 命中传入值；`null` 正常落 NULL。
- `AdminQueriesTest`：`usersList` 返回每用户最近 `ok` device_id（掩码）；多条取最新；空为「—」。
- `AdminViewsTest`：`usersPage` HTML 含「Device ID」列头与掩码值。
- `LlmCallLogsTableTest`（已有）：追加 device_id 列可读写。

**客户端（`:runtime-core`，视测试基建）**
- `AgentConfigurator`：`gatewayToken` 非空时 builder 收到 `X-App-Token` **且** `X-Device-Id`。
- 若 builder 不易注入单测，则编译 + 在 `LlmRoute` 端到端测试中间接验证（注册请求带 `X-Device-Id` → `llm_call_log.device_id` 非空）。

---

## 10. 文档同步

- `runtime-core/AgentConfigurator.kt:204` 注释：由「未注册访客……X-Device-Id」更新为「注册与访客均带 X-Device-Id（注册用于后台展示）」。
- `server/AGENTS.md`：第 4 节客户端认证说明补一句「注册用户请求亦带 `X-Device-Id`，用于后台 device 维度展示」。

---

## 11. 发布时序（跨端）

1. **服务端**：`./server/deploy.sh` 蓝绿上线（加列幂等，不破坏；注册用户 device_id 暂为空）。
2. **客户端**：`:runtime-core` 改动随**下次 APK 发版**（管理后台 `/admin/apk`）生效；发版后注册用户调用开始带 `X-Device-Id`，`llm_call_log.device_id` 开始积累。
3. **后台**：`/admin/users` Device ID 列在服务端上线后即可见，但注册用户行直到 APK 发版 + 用户下一次调用后才有值。

> 用户原指示「完成后直接 ssh 发布」对服务端立即执行；客户端 APK 发版不在本次 ssh 范围，需另行构建上传（提示用户）。
