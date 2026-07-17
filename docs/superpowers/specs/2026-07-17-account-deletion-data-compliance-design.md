# 账号删除 + 数据安全合规 设计

- 日期：2026-07-17
- 状态：待实现（spec 已通过用户评审）
- 模块：`:server`（Ktor）、`:app`（Android）、`docs-site/`（官网）
- 相关政策：Google Play 数据安全（Data Safety）、账号删除（Account Deletion）要求

## 1. 背景与目标

App 在本地新增了「邮箱注册 + 账号」功能（邮箱验证码登录、动态 token、LLM 试用额度），导致 Google Play 提审不通过。Google Play 要求：

1. 提供**账号删除功能**（app 内入口或网页流程）
2. app 内**可访问的隐私政策 / 数据说明**
3. Play Console **Data Safety** 声明与实际行为一致
4. Play Console 填写隐私政策 URL，并提供**联系方式**

本设计的目标是补齐合规闭环：新增账号删除能力、在 app 内与官网同时提供准确的数据使用说明与删除方式，并给出 Play Console 配套填写清单。

## 2. 根因（必须修正）

现有官网隐私政策 `docs-site/privacy-policy/index.html` 与 app 实际行为**直接冲突**，这是提审被拒的核心原因之一：

- 第 8 节安全措施中声明「**无账户系统：无需注册登录，不收集身份信息**」，但 app 已上线邮箱注册
- 「联系我们」仅有 GitHub Issues / 项目主页，**无邮箱**
- 全文未提及账号数据（邮箱）、官方远程 LLM 网关（`api.polang.net`）、账号删除入口
- 最后更新日期停留在 2026-06-11

数据安全声明与实际行为不符会被 Google Play 判定为违规，必须在本设计中一并修正。

## 3. 范围

采用「精准合规闭环」方案：

- **Server**：账号软删除（保留期 90 天）+ 删除 API + 过期清理
- **Client**：删除账号入口 + 二次确认对话框 + 独立「数据与隐私」说明页
- **官网隐私政策页**：修正冲突声明、补账号数据 / 删除方式 / 联系邮箱
- **i18n**：三语言同步（EN / zh-rCN / zh-rTW）
- **Play Console 配套**：Data Safety 清单 + 隐私政策 URL + 联系方式（附录，运营操作）

### 不在范围内（YAGNI）

- 不做定时清理调度任务（server 启动时清理 + 频繁重启已足够）
- 不在删除账号时级联清除本地 Room 数据（对话历史 / 媒体反馈 / 位置 / OCR 属设备级、非账号绑定数据；用户可通过系统「清除应用数据」或卸载清除）
- 不新增邮箱验证码二次验证删除（token 鉴权 + 二次确认对话框已足够）
- 不做硬删除（采用软删除 + 保留期，满足反欺诈与误删找回）

### 已确定的关键决策

| 决策 | 选定 |
|------|------|
| 删除策略 | 软删除（`status='deleted'` + `deleted_at`），保留期 **90 天** 后物理清理 |
| 使用说明载体 | app 内独立「数据与隐私」页 + 官网隐私政策页修正 |
| 删除确认流程 | app 内 `AlertDialog` 二次确认（明示不可立即恢复 + 保留期） |
| 删除鉴权 | 复用现有 `X-App-Token`（动态 token，SHA-256 校验） |
| 联系邮箱 | `budao.gs@gmail.com` |

## 4. 端到端数据流

```
设置页「数据与隐私」 / 已登录账号区 → 点「删除账号」
→ AlertDialog 二次确认（账号立即停用 / 数据保留 90 天后彻底删除 / 可同邮箱重新注册为全新账号）
→ PicMeAuthClient.deleteAccount(token) → DELETE /auth/account   (header: X-App-Token: <pl-*>)
→ AppTokenAuth interceptor 校验 token → 注入 tokenHash 到 TokenHashKey
→ AccountService.softDelete(tokenHash)
→ 200 {deleted:true} → 客户端 clearServerAuth() → UI 回到注册态 + Toast「账号已删除」
→ (后台) server 启动时 purgeExpiredDeleted(90d)：deleted_at < now-90d 的行 + 其 llm_call_log 物理删除
```

删除后：`serverAuthToken` 清空，`ChatViewModel` / `CameraScreen` / `AgentChatComponents` 中作为 LLM gateway token 的 `serverAuthToken` 变空，app 自动回退到 guest 模式（`isGuestMode=true`）。这是预期行为。

## 5. Server 端改动（`:server`）

### 5.1 数据库 migration

新增 `server/migrations/006_account_soft_delete.sql`：

```sql
-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumn 自动补列）
ALTER TABLE account ADD COLUMN deleted_at INTEGER;
-- deleted_at 为 NULL 表示账号正常；非 NULL 为软删除时间戳（epoch ms）
```

同步修改 `server/src/main/kotlin/com/mamba/picme/server/db/Tables.kt` 的 `Accounts`：

```kotlin
object Accounts : Table("account") {
    // ... 既有列 ...
    val deletedAt = long("deleted_at").nullable()   // 新增：软删除时间戳；NULL=未删除
    // ...
}
```

运行时由 Exposed `createMissingTablesAndColumn` 自动补列（与 `005_account_token_plain.sql` 同模式，参见 `Migrations.kt`）。

### 5.2 AccountService 扩展

在 `server/src/main/kotlin/com/mamba/picme/server/auth/AccountService.kt` 新增：

- **`softDelete(tokenHash: String): Boolean`**
  - 仅对 `status='active'` 的行生效
  - 置 `status="deleted"`、`deletedAt=now`、`tokenPlain=""`
  - `email` 改写为 `"deleted_${id}__${原email}"`，释放 `uniqueIndex(email)` 约束，使同邮箱可重新注册为全新账号
  - 返回是否命中（false = 该 token_hash 无 active 账号）

- **`purgeExpiredDeleted(retentionMs: Long): Int`**
  - `cutoff = now - retentionMs`
  - 选出 `status='deleted' AND deletedAt < cutoff` 的 `id` 集合
  - 先 `LlmCallLogs.deleteWhere { accountId inList ids }`，再 `Accounts.deleteWhere { Accounts.id inList ids }`
  - 返回清理条数（供启动日志）

### 5.3 删除 API 路由

在 `server/src/main/kotlin/com/mamba/picme/server/routes/AuthRoute.kt` 新增：

```kotlin
fun Route.accountDeletionRoute() {
    delete("/auth/account") {
        val tokenHash = call.attributes[TokenHashKey]   // 由 auth interceptor 注入
        val ok = AccountService.softDelete(tokenHash)
        if (ok) {
            call.respond(mapOf("deleted" to true))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "account_not_found"))
        }
    }
}
```

需 `import io.ktor.server.routing.delete`。

### 5.4 路由与清理注册

在 `server/src/main/kotlin/com/mamba/picme/server/Application.kt`：

- `routing { ... }` 块内、`quotaRoute()` 同级处调用 `accountDeletionRoute()`（受 auth interceptor 保护，`/auth/account` 不在 `publicRoutes`，自动要求有效 `X-App-Token`）
- `main()` 中 `Migrations.run(config)` 之后新增：

```kotlin
runBlocking {
    val n = AccountService.purgeExpiredDeleted(RETENTION_MS)
    logger.info("Purged $n expired deleted accounts (retention=${RETENTION_MS}ms)")
}
```

`RETENTION_MS = 90L * 24 * 60 * 60 * 1000`（90 天），定义为 `AccountService` 常量或 `Application.kt` 顶层私有常量。

## 6. Client 端改动（`:app`）

### 6.1 PicMeAuthClient 新增删除方法

在 `app/src/main/java/com/mamba/picme/data/remote/picme/PicMeAuthClient.kt` 新增：

```kotlin
suspend fun deleteAccount(token: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val req = Request.Builder()
            .url("$baseUrl/auth/account")
            .header("X-App-Token", token)
            .delete()
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw PicMeAuthException(resp.code, errorBody(resp.body?.string()))
        }
    }
}
```

复用现有 `errorBody` / `PicMeAuthException` 模式。

### 6.2 SettingsServerAuth 删除入口 + 确认框

修改 `app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt` 的 `QuotaDisplay`：

- 在「刷新 / 登出」`Row` 中或其下方新增「删除账号」`TextButton`，文字用 `MaterialTheme.colorScheme.error`，与「登出」视觉区分（更重）
- 新增 `showDeleteDialog` 本地状态；点击「删除账号」置 true
- 新增 `AlertDialog`：
  - 标题：`R.string.auth_delete_account_confirm_title`
  - 内容：`R.string.auth_delete_account_confirm_body`（明示账号立即停用、数据保留 90 天后彻底删除、可同邮箱重新注册）
  - 确认按钮（error 色）：`R.string.auth_delete_account_confirm` → 触发删除协程
  - 取消按钮：`R.string.cancel`（或既有取消字符串）
- 删除协程：`authClient.deleteAccount(serverToken)`
  - 进行中：`deleting` 状态置 true，禁用按钮
  - `onSuccess`：`repo.clearServerAuth()`；Toast `auth_delete_account_success`；`showDeleteDialog=false`
  - `onFailure`：按错误码处理（见 §8）；Toast `auth_delete_account_failed`；`showDeleteDialog=false`

### 6.3 新增「数据与隐私」说明页

新增 `app/src/main/java/com/mamba/picme/features/settings/DataPrivacyScreen.kt`（Compose）。结构化内容（标题段落 `R.string.data_privacy_*`）：

1. **收集的账号数据**：邮箱地址。用途：账号注册与登录标识、LLM 试用额度计费（`FREE_LLM_QUOTA`，默认 100 次）
2. **账号数据存储与保留**：服务端（`api.polang.net`）仅保存邮箱与 token 的 SHA-256 哈希，不收集明文密码（验证码登录）。删除账号后数据保留 **90 天**，期满彻底删除
3. **删除账号方式**：app「设置 → 账号 → 删除账号」，或邮件 `budao.gs@gmail.com` 申请
4. **本地处理（不上传）**：照片 / 视频美颜渲染、人脸关键点检测、OCR 文字识别、媒体地理位置、Agent 对话记忆——均在设备本地完成，不上传服务器；可通过系统「清除应用数据」或卸载清除
5. **远程推理（可选）**：登录账号的远程 LLM 对话经 `api.polang.net` 网关代理转发至 LLM 供应商（DeepSeek 等），仅用于本次请求；服务端仅记录调用次数与 Token 用量，**不存储对话内容**
6. **联系方式**：`budao.gs@gmail.com`
7. **「查看完整隐私政策」按钮**：打开 `https://polang.net/privacy-policy/`（Intent ACTION_VIEW）

### 6.4 入口接入

- `SettingsScreen`：新增「数据与隐私」设置项（`R.string.data_privacy_entry`），点击导航到 `DataPrivacyScreen`
- `EmailCodeAuthForm`（`features/common/auth/EmailCodeAuthForm.kt`）：注册表单底部新增「数据与隐私说明」链接（`R.string.data_privacy_entry`），点击导航到同一 `DataPrivacyScreen`

导航方式遵循 `SettingsScreen` 既有导航模式（确认是 NavHost route 还是回调上抛，实现时对齐）。

## 7. 官网隐私政策页改动（`docs-site/privacy-policy/index.html`）

- **修正第 8 节冲突条款**：删除「无账户系统：无需注册登录，不收集身份信息」；改为正向描述账号体系（邮箱注册、验证码登录、仅存邮箱与 token 哈希、不收集明文密码）
- **第 1 节新增「1.4 账号数据」表**：数据类型=邮箱地址 / 用途=注册登录、LLM 试用额度计费 / 处理方式=存于 `api.polang.net`，仅 SHA-256 哈希
- **第 3.3 节「远程模式」补充**：除用户自配 API 外，登录账号可走官方 `api.polang.net` 网关享免费试用额度；服务端仅记录用量，不存储对话内容
- **第 4 节新增「4.4 账号数据保留」**：删除账号后保留 90 天（反欺诈与找回），期满彻底删除（含用量日志 `llm_call_log`）
- **第 6 节「用户权利-删除权」补充**：app「设置 → 删除账号」入口；亦可邮件 `budao.gs@gmail.com` 申请删除
- **第 10 节「联系我们」加邮箱**：`budao.gs@gmail.com`
- **更新「最后更新日期 / 生效日期」**：改为本次发布日期 2026-07-17

样式沿用现有 `highlight-box` / `data-type` / 表格 class，保持视觉一致。

## 8. 错误处理与边界

| 场景 | 处理 |
|------|------|
| 删除时网络失败 / 5xx | Toast `auth_delete_account_failed`，**不清**本地 token（保留登录态供重试） |
| 删除返回 401 / 404（token 已失效或账号不存在） | **也清**本地 token（`clearServerAuth`），避免 UI 卡在登录态；提示账号已不可用 |
| 重复点击「删除账号」 | `deleting` loading 态禁用按钮，防重复请求 |
| 并发重复删除同一账号 | 第二次 `softDelete` 找不到 `active` 行 → 404，幂等 |
| `email` 改写后超长 | `deleted_<id>__<原email>`，列 `varchar(256)` 足够；异常长 email 截断以保唯一 |
| 软删除后旧 token 再次请求 | `validateToken` 因 `status != active` 拒绝 → 401 |
| 软删除后同邮箱重新注册 | `createOrRefresh` 找不到该 email（已改写）→ 新建独立账号 |

## 9. 测试策略

### 9.1 Server JVM 测试（`./gradlew -p server test`）

参考既有 `server/src/test/.../auth/AccountService*Test`、`AdminRoutesTest` 风格，使用 `TestDb`。

- **`AccountServiceSoftDeleteTest`**：软删后 `status='deleted'`、`token_plain` 空、`email` 已改写释放、`deleted_at` 已设置；旧 token `validateToken` 返回 `false`
- **`AccountDeletionRouteTest`**（或扩 `AuthRouteTest`）：`DELETE /auth/account` 带有效 token → 200 `{deleted:true}`；无 token → 401；重复删除 → 404
- **`PurgeExpiredDeletedTest`**：`deleted_at` 过期的行 + 其 `llm_call_log` 被物理删除；未过期（90 天内）的保留；正常账号不受影响
- **复活/重新注册测试**：软删后同邮箱 `createOrRefresh` 创建全新独立账号（不同 id）

### 9.2 Client JVM 测试

参考既有 client 测试风格（`app/src/test/`）。

- **`PicMeAuthClientDeleteAccountTest`**（如存在 mockwebserver 基础设施）：验证请求方法 `DELETE`、URL `$baseUrl/auth/account`、`X-App-Token` header；成功返回 `Result.success`；非 2xx 抛 `PicMeAuthException` 含正确 code/errorType

## 10. i18n（三语言同步）

`values/strings.xml`（EN 默认）、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml` 同步新增：

| key | 用途 |
|-----|------|
| `auth_delete_account` | 删除账号（按钮） |
| `auth_delete_account_confirm_title` | 删除账号？（对话框标题） |
| `auth_delete_account_confirm_body` | 账号立即停用、数据保留 90 天后彻底删除、可同邮箱重新注册（对话框正文，含保留期） |
| `auth_delete_account_confirm` | 删除（确认按钮） |
| `auth_delete_account_success` | 账号已删除（Toast） |
| `auth_delete_account_failed` | 删除失败，请检查网络后重试（Toast） |
| `data_privacy_title` | 数据与隐私（页面标题） |
| `data_privacy_entry` | 数据与隐私（设置入口 / 注册表单链接） |
| `data_privacy_*`（多段） | 说明页各段落文本（账号数据 / 保留 / 删除方式 / 本地处理 / 远程推理 / 联系方式） |
| `data_privacy_view_full_policy` | 查看完整隐私政策（按钮） |

## 11. 附录：Play Console 配套清单（运营操作，非代码）

- **Data Safety section**：
  - 个人信息 → 邮箱地址（可选，用户注册时提供）→ 用途：账号管理、计费与用量 → 用户可请求删除：**是** → 加密：**是**
  - 位置 / 照片 / 人脸 / OCR / 语音：均本地处理不上传 → 对应项选「否，app 不收集此数据」或如实声明「仅本地处理」
- **隐私政策 URL**：`https://polang.net/privacy-policy/`（需确认官网部署后该路径可访问）
- **账号删除**：勾选「app 内提供账号删除入口」
- **联系方式**：`budao.gs@gmail.com`

## 12. 验收标准

1. 登录账号后，设置页账号区可见「删除账号」入口；点击弹出二次确认对话框
2. 确认删除后，server `account` 行 `status='deleted'`、`deleted_at` 已设置、`token_plain` 清空、`email` 已改写；返回 200；客户端 token/email 清空、UI 回到注册态、Toast 成功
3. 删除后旧 token 调 `/auth/quota` 或 `/v1/chat/completions` 返回 401
4. 删除后同邮箱可重新注册为新账号（额度重置）
5. 设置页与注册表单均可进入「数据与隐私」页，内容含邮箱用途、90 天保留、删除方式、联系方式 `budao.gs@gmail.com`、完整政策链接
6. 官网隐私政策页不再含「无账户系统」声明，含账号数据 / 删除方式 / 邮箱联系方式
7. 三语言 strings 全部同步，无硬编码用户可见文本
8. `./gradlew -p server test`、`./gradlew :app:testDebugUnitTest` 全绿；`./gradlew detekt`、`ktlintCheck` 通过
