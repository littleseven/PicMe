# Phase 6.4：Server 端 iOS 适配点清查

> **性质**：适配点清查（audit），非实现计划
> **产出路径**：本文档
> **上游**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 6.4
> **审计基线**：main 分支 `2c499ae3`（含 Phase 4 全部产物）

---

## 总结

Server 端当前是**纯平台无关的单体服务**——所有 API（认证、LLM 代理、遥测、推荐、问题上报、AI 工程师）对 Android 和 iOS 客户端完全等价，iOS 客户端可以直接消费现有全部端点。**无阻塞项**。

但有 **5 个适配点**值得在 Phase 6 推进期间逐项落地，优先级从高到低：

| # | 适配点 | 优先级 | 改动量 | 阻塞？ |
|---|--------|--------|--------|--------|
| 1 | 设备标识：iOS IDFV → `X-Device-Id` 兼容性 | P1 | 🟢 客户端侧 0 server 改动 | 不阻塞（server 不校验格式） |
| 2 | 平台维度缺失（管理后台 / 日志 / 遥测） | P2 | 🟡 server + DB schema | 不阻塞功能 |
| 3 | `/download` 页 Android-only | P2 | 🟢 客户端侧，server 可选改 | 不阻塞 |
| 4 | Apple Sign In | P3 | 🔴 新端点 + JWT 验证 | 不阻塞（邮箱认证可用） |
| 5 | APNs 推送 | P4 | 🔴 新基础设施 | 不阻塞（当前无推送功能） |

---

## 1. 设备标识（`X-Device-Id`）

### 现状

- **Server**：`Application.kt:117-119` 从 `X-Device-Id` header 取 `deviceId`，仅用于：
  - 匿名访客试用额度（`GuestService.checkAndIncrementQuota(deviceId, ...)`）
  - `llm_call_log.device_id` 列（管理后台设备维度展示）
- **Server 不校验格式**：`deviceId` 是 `varchar(128)`，任意字符串均可
- **Android 客户端**：使用 `Settings.Secure.ANDROID_ID` 或本地生成 UUID

### iOS 适配

- **IDFV（Identifier for Vendor）** 是自然对位：`UIDevice.current.identifierForVendor.uuidString`
- **无需权限请求**（不像 IDFA 需要 App Tracking Transparency 授权）
- **App 卸载重装后 IDFV 会变**（同 vendor 的 app 卸载全卸后才变）——这与 Android `ANDROID_ID` 语义不完全一致，但对「访客试用额度」场景足够（卸载重装获得新额度在两端都存在）
- **Server 侧零改动**：IDFV 是 UUID 字符串，长度 36，远在 `varchar(128)` 内

### 结论

| 维度 | 结论 |
|------|------|
| Server 改动 | **无** |
| iOS 客户端 | 用 `UIDevice.identifierForVendor?.uuidString ?? UUID().uuidString` |
| 风险 | 无 |

---

## 2. 平台维度缺失

### 现状

Server **无法区分请求来自 Android 还是 iOS**：

- `anonymous_device` 表：只有 `device_id`，无 `platform` 列
- `llm_call_log` 表：只有 `device_id`，无 `platform` 列
- `telemetry_event` 表：`type` + `payload_json`（无固定 platform 字段）
- 管理后台：设备列表、用户列表均无平台列
- Auth interceptor：不读取任何 platform header

### 影响

- 管理后台无法按平台筛选用户/设备/流量
- 无法统计 iOS vs Android 的 LLM 用量分布
- 无法针对平台做差异化限流或额度策略

### 建议方案（Phase 6 期间推进）

**最小侵入**：在 `llm_call_log` 和 `anonymous_device` 表加 `platform` 列（`varchar(16)`，`android` / `ios`），客户端通过新 header `X-Platform` 传入：

```
# DB migration 006_platform.sql
ALTER TABLE llm_call_log ADD COLUMN platform TEXT;
ALTER TABLE anonymous_device ADD COLUMN platform TEXT;
```

**Server 改动清单**（~15 文件触及点，改动量 🟡 中等）：

| 文件 | 改动 |
|------|------|
| `db/Tables.kt` | `LlmCallLogs` + `AnonymousDevices` 加 `platform` 列 |
| `db/Migrations.kt` | 新 migration |
| `migrations/006_platform.sql` | DDL |
| `auth/AppTokenAuth.kt` | 新增 `const val PLATFORM_HEADER = "X-Platform"` |
| `Application.kt` | auth interceptor 读 `X-Platform`、存 `AttributeKey` |
| `routes/AuthRoute.kt` | 新增 `PlatformKey` |
| `llm/LlmRoute.kt` | `UsageRecorder.log(...)` 传 `platform` |
| `analytics/UsageRecorder.kt` | `log()` 签名加 `platform` 参数 |
| `auth/GuestService.kt` | `checkAndIncrementQuota` 可选存 platform |
| `admin/AdminQueries.kt` | 查询加平台维度 |
| `admin/AdminViews.kt` | 设备/用户列表展示平台列 |
| `server/AGENTS.md` | 路由清单更新 |

**客户端**（Android + iOS）：
- 所有请求加 `X-Platform: android` / `X-Platform: ios` header
- Android `OpenAiApiClient` / `PoLangAuthClient` / `ClaudeChatClient` / `IssueReportClient` 的 OkHttp interceptor 加一行

### 结论

| 维度 | 结论 |
|------|------|
| 阻塞 iOS 功能 | **否** |
| 优先级 | P2（运营可见性需求驱动） |
| 建议时机 | Phase 5 TestFlight 前（管理后台需能区分 iOS 用户）或 Phase 6 初 |

---

## 3. `/download` 页 Android-only

### 现状

- `DownloadRoute.kt` 渲染的下载页硬编码 Android APK：
  - `CosService.cosKey = "apk/polang-release.apk"`（`CosService.kt:29`）
  - 页面文案「扫码下载 Android APK」「Android 10+」
  - QR 码指向 COS 上的 APK 公开 URL
- `CosService.uploadApk()` 的 contentType 硬编码 `application/vnd.android.package-archive`

### iOS 适配

**iOS 不走服务端下载**——TestFlight / App Store 分发。因此：

1. **`/download` 页加平台检测**（可选优化）：
   - User-Agent 含 `iPhone` / `iPad` / `iPod` → 显示 App Store / TestFlight 链接
   - 否则 → 显示 APK 二维码（现状）
   - 改动量：`DownloadRoute.kt` 加一个 UA 分支 + HTML 条件渲染，~20 行

2. **CosService 扩展**（如未来需 IPA 托管——通常不需要）：
   - 加 `cosKey` 参数化或新增 `ipa/` 前缀
   - 但 iOS 走 TestFlight，几乎不可能走自托管 IPA（除企业证书内部分发）

### 结论

| 维度 | 结论 |
|------|------|
| 阻塞 iOS 功能 | **否**（iOS 不走 `/download`） |
| 优先级 | P2（用户体验：iOS 用户打开 `/download` 不应看到 APK） |
| 建议时机 | TestFlight 前（App Store 链接确定后） |

---

## 4. Apple Sign In

### 现状

- 认证体系为**邮箱验证码**（`/auth/email/send` → `/auth/email/verify` → 返回 token）
- 无 OAuth / 社交登录
- 无 Apple Sign In 端点

### Apple App Store 政策

**Guideline 4.8**：如果 app 提供第三方账号登录（Google / Facebook 等），**必须**同时提供 Apple Sign In。

当前 PoLang 只提供**邮箱登录**（非第三方社交登录），因此 **App Store 不强制 Apple Sign In**。邮箱验证码方案在 iOS 上完全合规。

### 如果未来需要 Apple Sign In

**Server 侧改动**（改动量 🔴 大）：

1. 新端点 `POST /auth/apple`：
   - 接收 iOS 客户端传来的 Apple `identityToken`（JWT）
   - 验证 JWT 签名（Apple 公钥 → JWKS endpoint `https://appleid.apple.com/auth/keys`）
   - 提取 `sub`（Apple 用户唯一 ID）+ `email`
   - 映射到现有 `account` 表（email 关联或新建 `apple_sub` 列）
2. `account` 表加 `apple_sub varchar(64) nullable` 列 + uniqueIndex
3. JWT 验证库（如 `com.auth0:java-jwt` 或 Ktor 的 OAuth 支持）
4. Apple 公钥缓存 + 轮换（JWKS 每 24h 刷新）

**iOS 客户端**：`ASAuthorizationAppleIDProvider` → 拿 `identityToken` → POST 到 `/auth/apple`

### 结论

| 维度 | 结论 |
|------|------|
| App Store 合规性 | **邮箱方案合规，不强制** |
| 阻塞 iOS 功能 | **否** |
| 优先级 | P3（用户体验加分项，非合规必需） |
| 建议时机 | Phase 6.3（设置与账号）或之后按需 |

---

## 5. APNs 推送通知

### 现状

- Server **无任何推送基础设施**：
  - 无 device token 注册端点
  - 无 APNs 客户端 / 证书
  - 无推送发送服务
  - `account` / `anonymous_device` 表无 `push_token` 列
- Android 端同样没有 FCM（项目未使用推送）

### iOS 适配

当前无推送功能需求。如果未来添加（如 TAG 扫描完成通知、Chat 回复通知），需要：

**Server 侧**（改动量 🔴 大——全新基础设施）：

1. 新表 `push_registration`（`account_id`, `platform`, `token`, `created_at`, `active`）
2. 新端点 `POST /push/register`（接收 device token）、`DELETE /push/register`
3. APNs 客户端：
   - 选项 A：Apple 的 HTTP/2 APNs API（需要 `.p8` 令牌或 `.p12` 证书）
   - 选项 B：统一推送服务（如 Firebase Cloud Messaging → APNs 代理，但 Android 也要走 FCM）
4. 推送发送逻辑集成到业务流程（TAG 完成、AI 回复等）
5. 环境变量：`APNS_TEAM_ID` / `APNS_KEY_ID` / `APNS_PRIVATE_KEY` / `APNS_BUNDLE_ID`

### 结论

| 维度 | 结论 |
|------|------|
| 阻塞 iOS 功能 | **否** |
| 优先级 | P4（无推送需求时不做） |
| 建议时机 | 有明确推送场景时（可能是 Phase 7 或更后） |

---

## 6. 其他审计点（无改动需求）

以下领域经审计确认**平台无关，iOS 零适配**：

| 领域 | 审计结论 |
|------|----------|
| **LLM 代理**（`/v1/chat/completions`） | 纯 HTTP 透传，`X-App-Token` 认证。iOS 直接复用，shared commonMain 的 Koog agent 经 `RemoteModelConfig` 消费同一端点 |
| **推荐引擎**（`/recommend`） | 输入 = 场景标签 JSON，输出 = 参数包 JSON。无平台依赖 |
| **遥测**（`/telemetry`） | 事件格式 `{type, payload}` 完全开放。iOS 端可自由定义事件类型 |
| **AI 工程师模式**（`/v1/claude-chat` + SSE） | SSE 长连接 + `app_tool_request` 下行。iOS `URLSession` 原生支持 SSE 流式读取 |
| **问题上报**（`/v1/report-issue`） | `IssueSanitizer` 做平台无关脱敏 |
| **管理后台** | SSR HTML，与客户端平台无关 |
| **账号额度 / 软删除** | 按 `token_hash` 索引，平台无关 |
| **限流**（`RateLimiter`） | per-IP 令牌桶，平台无关 |
| **COS 预签名** | 当前仅用于 APK（Phase 6.4 本项 #3）；如未来 iOS 需资源分发，COS 预签名 API 平台无关 |
| **API base URL** | iOS 客户端硬编码 `https://api.polang.net`（与 Android 一致），shared commonMain `RemoteModelConfig` 已含此默认值 |

---

## 7. 建议执行优先级

```
Phase 5 TestFlight 前
  └── #1 设备标识（IDFV）— 客户端侧，0 server 改动
  └── #2 平台维度（可选，可推迟到 Phase 6 初）
  └── #3 /download 页 UA 分支（可选，App Store 链接确定后）

Phase 6.3（设置与账号）
  └── #4 Apple Sign In（按需，邮箱方案合规）

Phase 7+（推送需求出现时）
  └── #5 APNs 推送基础设施
```

**核心结论**：iOS 客户端可以直接消费 server 端全部现有 API，零阻塞。唯一在 TestFlight 前建议落地的是 `X-Platform` header（管理后台可见性），但即便不做也不影响功能。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-08 | 初版：逐文件审计 server/ 全部 38 个源文件 + 5 个 migration + Android 客户端 API client 层，产出 5 个适配点 + 无改动确认清单 |
