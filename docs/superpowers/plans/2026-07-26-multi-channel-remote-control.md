# 多通信通道（飞书 + Telegram）远程控制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在飞书之外新增 Telegram 远程控制通道（单通道选择），release 包不再打包飞书凭据，通道配置迁出到独立的设置二级页。

**Architecture:** 引入轻量 `RemoteChannel` 接口，飞书与 Telegram 各自实现；`RemoteChannelManager` 持有两者，按 `selected_remote_channel` 单通道激活（重连由 manager 统一负责）。`RemoteCommandDispatcher` 去飞书化，面向 `RemoteChannel`。激活决策抽成纯函数 `ChannelActivationResolver`（单测覆盖）。

**Tech Stack:** Kotlin · Jetpack Compose · DataStore · Pengrad `java-telegram-bot-api:5.5.0` · Lark `oapi-sdk:2.5.3` · JUnit4 + mockk（JVM 单测）。

**仓库约定（执行时遵守）:**
- **提交**：仅当用户确认时提交；每个任务的 `git add` 只加该任务触及的文件（当前 main 工作区已有大量 WIP，提交策略执行前与用户确认）。
- **验证门槛**：`./gradlew :app:assembleDebug` + `./gradlew :app:testDebugUnitTest`（项目真门槛；detekt/ktlint 非可靠门，见 memory `polang-quality-gates-reality`）。
- **子代理不可用**：本环境 Agent/Workflow 启动即失败，调研与执行均自行完成。

**关联 spec:** `docs/superpowers/specs/2026-07-26-multi-channel-remote-control-design.md`

---

## File Structure

**新增**
- `app/src/main/java/com/mamba/picme/domain/model/RemoteChannelType.kt` — 通道枚举 + 安全解析。
- `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannel.kt` — 通道接口。
- `app/src/main/java/com/mamba/picme/domain/agent/remote/ChannelActivationResolver.kt` — 激活决策纯函数 + sealed 结果。
- `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannelManager.kt` — 单通道管理器（实现 RemoteChannel）。
- `app/src/main/java/com/mamba/picme/domain/agent/remote/TelegramChannelHandler.kt` — Pengrad 长轮询通道。
- `app/src/main/java/com/mamba/picme/domain/agent/remote/TelegramMessageFilter.kt` — chatId 白名单纯函数。
- `app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelViewModel.kt` — 配置页 VM。
- `app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelScreen.kt` — 配置页 UI。
- 对应 JVM 单测（`app/src/test/...`）。

**修改**
- `app/build.gradle.kts` — buildConfigField 按构建类型拆分 + Pengrad 依赖。
- `gradle/libs.versions.toml` — Pengrad 版本与别名。
- `FeishuChannelHandler.kt` — 实现 RemoteChannel。
- `FeishuPhotoTracker.kt` → `RemotePhotoTracker.kt` — 改名 + 泛化。
- `RemoteCommandDispatcher.kt` — 去飞书化（RemoteChannel + 动态 sessionId）。
- `CameraCaptureActions.kt` — tracker 改名 + 发送走 manager + source tag 跟随激活通道。
- `PoLangApplication.kt` — manager 接线 + 统一激活/重连 + 拍照观察者通道化。
- `UserPreferencesRepository.kt` / `UserSettingsRepository.kt` — 新 DataStore 键。
- `SettingsScreen.kt` — 移除内联 section、主页网格加一级入口、移除飞书 state。
- `SettingsViewModel.kt` — 移除飞书 state（迁到新 VM）。
- `Screen.kt` / `MainActivity.kt` — 新路由 + 接线。
- 三语 `strings.xml`。
- `runtime-core` `AgentOrchestrator.kt` — `processFeishuInput` → `processRemoteImInput`。
- 文档（见 Task 15）。

---

### Task 1: 数据层 — RemoteChannelType 枚举 + DataStore 键 + 仓库方法

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/model/RemoteChannelType.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt:158-162`
- Modify: `app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt:155-162,1049-1084`
- Test: `app/src/test/java/com/mamba/picme/domain/model/RemoteChannelTypeTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteChannelTypeTest {
    @Test fun parses_valid_name() {
        assertEquals(RemoteChannelType.FEISHU, RemoteChannelType.fromStored("FEISHU"))
        assertEquals(RemoteChannelType.TELEGRAM, RemoteChannelType.fromStored("TELEGRAM"))
        assertEquals(RemoteChannelType.NONE, RemoteChannelType.fromStored("NONE"))
    }
    @Test fun invalid_or_null_falls_back_to_default() {
        assertEquals(RemoteChannelType.FEISHU, RemoteChannelType.fromStored("garbage"))
        assertEquals(RemoteChannelType.FEISHU, RemoteChannelType.fromStored(null))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.model.RemoteChannelTypeTest"`
Expected: FAIL（RemoteChannelType 未定义）

- [ ] **Step 3: 实现枚举**

```kotlin
package com.mamba.picme.domain.model

/** 远程控制通道选择（单通道模型：同一时刻仅一个通道连接）。 */
enum class RemoteChannelType {
    FEISHU,
    TELEGRAM,
    NONE;

    companion object {
        /** DataStore 存储值的安全解析；非法/空值回退默认 FEISHU（保 debug 开机自动连）。 */
        fun fromStored(name: String?): RemoteChannelType =
            name?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() } ?: FEISHU
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.model.RemoteChannelTypeTest"`
Expected: PASS

- [ ] **Step 5: 扩展仓库契约（接口）**

在 `UserSettingsRepository.kt` 飞书段（158-162 行）后追加：

```kotlin
    // ── 远程通道选择 + Telegram ───────────────────────────────
    val selectedRemoteChannelFlow: Flow<String>
    suspend fun updateSelectedRemoteChannel(type: String)
    val telegramBotTokenFlow: Flow<String>
    val telegramAllowedChatIdFlow: Flow<String>
    suspend fun updateTelegramConfig(botToken: String, allowedChatId: String)
```
（接口顶部确保已 `import kotlinx.coroutines.flow.Flow`，已存在。）

- [ ] **Step 6: 实现 DataStore 键与读写（UserPreferencesRepository）**

在 `PreferencesKeys`（155-162 行「飞书远程控制」段）后追加键：

```kotlin
        // 远程通道选择 + Telegram
        val SELECTED_REMOTE_CHANNEL = stringPreferencesKey("selected_remote_channel")
        val TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        val TELEGRAM_ALLOWED_CHAT_ID = stringPreferencesKey("telegram_allowed_chat_id")
```

在文件末尾「飞书远程控制」读写段（`updateFeishuAppSecret` 之后、`// ── 服务端邮箱认证` 之前，约 1084 行）追加实现：

```kotlin
    override val selectedRemoteChannelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences -> preferences[PreferencesKeys.SELECTED_REMOTE_CHANNEL] ?: "FEISHU" }

    override suspend fun updateSelectedRemoteChannel(type: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_REMOTE_CHANNEL] = type
        }
    }

    override val telegramBotTokenFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences -> preferences[PreferencesKeys.TELEGRAM_BOT_TOKEN] ?: "" }

    override val telegramAllowedChatIdFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences -> preferences[PreferencesKeys.TELEGRAM_ALLOWED_CHAT_ID] ?: "" }

    override suspend fun updateTelegramConfig(botToken: String, allowedChatId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TELEGRAM_BOT_TOKEN] = botToken
            preferences[PreferencesKeys.TELEGRAM_ALLOWED_CHAT_ID] = allowedChatId
        }
    }
```

- [ ] **Step 7: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/model/RemoteChannelType.kt \
  app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt \
  app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt \
  app/src/test/java/com/mamba/picme/domain/model/RemoteChannelTypeTest.kt
git commit -m "feat(channel): 数据层——RemoteChannelType 枚举 + 通道选择/Telegram DataStore 键"
```

---

### Task 2: Build 配置 — release 飞书凭据隔离

**Files:**
- Modify: `app/build.gradle.kts:86-90,126-150`

- [ ] **Step 1: 默认值改为空串（defaultConfig）**

在 `app/build.gradle.kts` 的 `defaultConfig` 块（86-90 行）中，把飞书两个字段改为空串默认：

```kotlin
        // release 默认空串：用户必须自行配置；debug 在 buildTypes.debug 中覆盖为注入值
        buildConfigField("String", "FEISHU_APP_ID", "\"\"")
        buildConfigField("String", "FEISHU_APP_SECRET", "\"\"")
        buildConfigField("String", "CLOUDFLARE_GATEWAY_TOKEN", "\"${System.getenv("CLOUDFLARE_GATEWAY_TOKEN") ?: ""}\"")
```

- [ ] **Step 2: debug 构建注入真实凭据**

在 `buildTypes.debug` 块（146-149 行）追加注入：

```kotlin
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            // 仅 debug 注入开发者飞书凭据（local.properties / 环境变量）；release 继承空串
            buildConfigField("String", "FEISHU_APP_ID", "\"${feishuAppId}\"")
            buildConfigField("String", "FEISHU_APP_SECRET", "\"${feishuAppSecret}\"")
        }
```

- [ ] **Step 3: 编译 debug 确认 BuildConfig 仍被正确引用**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（`UserPreferencesRepository` 中 `?: BuildConfig.FEISHU_APP_ID` 引用仍合法，debug 下为注入值）。

- [ ] **Step 4: 提交**

```bash
git add app/build.gradle.kts
git commit -m "build(app): 飞书凭据仅 debug 注入，release 默认空串（用户自填）"
```

---

### Task 3: RemoteChannel 接口 + ChannelActivationResolver（纯函数 TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannel.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/agent/remote/ChannelActivationResolver.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/remote/ChannelActivationResolverTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.agent.remote

import com.mamba.picme.domain.model.RemoteChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelActivationResolverTest {
    @Test fun none_type_disconnects_all() {
        assertEquals(
            ChannelActivation.None,
            ChannelActivationResolver.resolve(RemoteChannelType.NONE, "id", "secret", "token", "chat")
        )
    }

    @Test fun feishu_with_both_creds_activates_feishu() {
        val r = ChannelActivationResolver.resolve(RemoteChannelType.FEISHU, "id", "secret", "token", "chat")
        assertTrue(r is ChannelActivation.Feishu)
        assertEquals("id", (r as ChannelActivation.Feishu).appId)
        assertEquals("secret", r.appSecret)
    }

    @Test fun feishu_missing_secret_yields_none() {
        assertEquals(
            ChannelActivation.None,
            ChannelActivationResolver.resolve(RemoteChannelType.FEISHU, "id", "  ", "token", "chat")
        )
    }

    @Test fun telegram_with_token_activates_telegram_even_without_chatid() {
        val r = ChannelActivationResolver.resolve(RemoteChannelType.TELEGRAM, "id", "secret", "token", "")
        assertTrue(r is ChannelActivation.Telegram)
        assertEquals("token", (r as ChannelActivation.Telegram).botToken)
        assertEquals("", r.allowedChatId)
    }

    @Test fun telegram_without_token_yields_none() {
        assertEquals(
            ChannelActivation.None,
            ChannelActivationResolver.resolve(RemoteChannelType.TELEGRAM, "id", "secret", "  ", "chat")
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.remote.ChannelActivationResolverTest"`
Expected: FAIL（类型未定义）

- [ ] **Step 3: 实现 RemoteChannel 接口**

```kotlin
package com.mamba.picme.domain.agent.remote

/**
 * 远程控制通道抽象。
 *
 * - [replyToken] 为通道不透明串：飞书侧 = messageId，Telegram 侧 = chatId。
 *   单通道模型下 token 必来自当前激活通道，各通道自行解释，调度器仅透传。
 * - 重连不放进接口：由 [RemoteChannelManager] 统一负责（重新 activate）。
 */
interface RemoteChannel {
    val channelId: String
    val isConnected: Boolean
    var onMessageReceived: ((text: String, replyToken: String) -> Unit)?
    var onConnectionStateChanged: ((connected: Boolean) -> Unit)?
    fun sendMessage(text: String, replyToken: String)
    fun sendImage(bytes: ByteArray, replyToken: String)
}
```

- [ ] **Step 4: 实现 ChannelActivationResolver**

```kotlin
package com.mamba.picme.domain.agent.remote

import com.mamba.picme.domain.model.RemoteChannelType

/** [RemoteChannelManager.activate] 的纯决策结果。 */
sealed interface ChannelActivation {
    data object None : ChannelActivation
    data class Feishu(val appId: String, val appSecret: String) : ChannelActivation
    data class Telegram(val botToken: String, val allowedChatId: String) : ChannelActivation
}

/** 纯函数：按选择与凭据决定激活哪个通道（凭据缺失则 None）。无副作用，便于单测。 */
object ChannelActivationResolver {
    fun resolve(
        type: RemoteChannelType,
        feishuAppId: String,
        feishuAppSecret: String,
        telegramBotToken: String,
        telegramAllowedChatId: String
    ): ChannelActivation = when (type) {
        RemoteChannelType.NONE -> ChannelActivation.None
        RemoteChannelType.FEISHU ->
            if (feishuAppId.isNotBlank() && feishuAppSecret.isNotBlank()) {
                ChannelActivation.Feishu(feishuAppId, feishuAppSecret)
            } else {
                ChannelActivation.None
            }
        RemoteChannelType.TELEGRAM ->
            if (telegramBotToken.isNotBlank()) {
                ChannelActivation.Telegram(telegramBotToken, telegramAllowedChatId)
            } else {
                ChannelActivation.None
            }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.remote.ChannelActivationResolverTest"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannel.kt \
  app/src/main/java/com/mamba/picme/domain/agent/remote/ChannelActivationResolver.kt \
  app/src/test/java/com/mamba/picme/domain/agent/remote/ChannelActivationResolverTest.kt
git commit -m "feat(channel): RemoteChannel 接口 + ChannelActivationResolver 纯决策（含单测）"
```

---

### Task 4: FeishuChannelHandler 实现 RemoteChannel

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/agent/remote/FeishuChannelHandler.kt`

- [ ] **Step 1: 类声明实现接口 + channelId**

`FeishuChannelHandler.kt` 顶部类声明（41 行）改为：

```kotlin
class FeishuChannelHandler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RemoteChannel {
```

在类体内（`companion object` 之前，约 471 行）新增：

```kotlin
    override val channelId: String = "feishu"
```

- [ ] **Step 2: 现有成员加 override + 参数改名**

将以下成员加 `override`，并把参数名 `messageId`→`replyToken`、`content`→`text`、`imageBytes`→`bytes`（仅签名，内部调用处同步改名）：

```kotlin
    @Volatile
    override var isConnected: Boolean = false
        private set

    override var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null

    override var onMessageReceived: ((text: String, replyToken: String) -> Unit)? = null

    override fun sendMessage(text: String, replyToken: String) { /* 原 sendMessage(content, messageId) 函数体，内部 messageId→replyToken、content→text */ }

    override fun sendImage(bytes: ByteArray, replyToken: String) { /* 原 sendImage(imageBytes, messageId) 函数体 */ }
```

> `handleMessageEvent` 内调用 `onMessageReceived?.invoke(text, messageId)` 保持（`messageId` 即 replyToken，语义不变）。`init / disconnect / reinit / reconnectIfNeeded / sendFile` 保留为具体类方法（不在接口）。

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/remote/FeishuChannelHandler.kt
git commit -m "refactor(channel): FeishuChannelHandler 实现 RemoteChannel 接口"
```

---

### Task 5: 引入 Pengrad 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts:247`（dependencies 段）

- [ ] **Step 1: 版本目录加版本与别名**

`gradle/libs.versions.toml` `[versions]` 段（`oapi = "2.5.3"` 行附近）加：

```toml
telegramBot = "5.5.0"
```

`[libraries]` 段（`oapi-sdk` 行附近）加：

```toml
telegram-bot = { module = "com.github.pengrad:java-telegram-bot-api", version.ref = "telegramBot" }
```

- [ ] **Step 2: app 模块声明依赖**

`app/build.gradle.kts` dependencies 段，`implementation(libs.oapi.sdk)`（247 行）后加：

```kotlin
    implementation(libs.telegram.bot)
```

- [ ] **Step 3: 解析依赖 + 编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（Pengrad 从 Maven Central 解析成功）

- [ ] **Step 4: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(app): 引入 Pengrad java-telegram-bot-api 5.5.0"
```

---

### Task 6: TelegramMessageFilter（白名单纯函数 TDD）+ TelegramChannelHandler

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/agent/remote/TelegramMessageFilter.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/remote/TelegramMessageFilterTest.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/agent/remote/TelegramChannelHandler.kt`

- [ ] **Step 1: 写失败测试（白名单 fail-closed）**

```kotlin
package com.mamba.picme.domain.agent.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMessageFilterTest {
    @Test fun allowed_blank_refuses_all() {
        assertFalse(TelegramMessageFilter.shouldAccept("123", "  "))
        assertFalse(TelegramMessageFilter.shouldAccept("123", ""))
    }
    @Test fun matches_allowed_chat_id() {
        assertTrue(TelegramMessageFilter.shouldAccept("123", "123"))
    }
    @Test fun mismatch_rejected() {
        assertFalse(TelegramMessageFilter.shouldAccept("123", "999"))
    }
    @Test fun null_chat_id_rejected() {
        assertFalse(TelegramMessageFilter.shouldAccept(null, "123"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.remote.TelegramMessageFilterTest"`
Expected: FAIL

- [ ] **Step 3: 实现白名单纯函数**

```kotlin
package com.mamba.picme.domain.agent.remote

/** Telegram chatId 白名单过滤（fail-closed：未配置 allowedChatId 时拒绝全部）。 */
object TelegramMessageFilter {
    fun shouldAccept(chatId: String?, allowedChatId: String): Boolean =
        allowedChatId.isNotBlank() && chatId != null && chatId == allowedChatId
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.remote.TelegramMessageFilterTest"`
Expected: PASS

- [ ] **Step 5: 实现 TelegramChannelHandler（Pengrad 长轮询）**

```kotlin
package com.mamba.picme.domain.agent.remote

import com.mamba.picme.core.common.Logger
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.request.SendMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Telegram 通道处理器：Pengrad 长轮询（getUpdates，库内部管 offset/重试），无需公网 IP。
 *
 * 安全：仅处理来自 [allowedChatId] 的消息（fail-closed，见 [TelegramMessageFilter]）。
 * 生命周期对齐飞书：[connect] 启动、[disconnect] 停止；重连由 [RemoteChannelManager] 重新 activate。
 */
class TelegramChannelHandler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RemoteChannel {

    override val channelId: String = "telegram"

    private var bot: TelegramBot? = null
    @Volatile private var allowedChatId: String = ""

    @Volatile
    override var isConnected: Boolean = false
        private set

    override var onMessageReceived: ((text: String, replyToken: String) -> Unit)? = null
    override var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null

    fun connect(botToken: String, allowedChatId: String) {
        if (botToken.isBlank()) {
            Logger.w(TAG, "Telegram Bot Token 未配置，通道不可用")
            return
        }
        disconnect()
        this.allowedChatId = allowedChatId
        val b = TelegramBot(botToken)
        bot = b
        b.setUpdatesListener(
            { updates ->
                if (!isConnected) {
                    isConnected = true
                    onConnectionStateChanged?.invoke(true)
                    Logger.i(TAG, "Telegram 长轮询已连接")
                }
                for (update: Update in updates) {
                    handleMessage(update)
                }
                UpdatesListener.CONFIRMED_UPDATES_ALL
            },
            { e ->
                Logger.e(TAG, "Telegram 长轮询异常", e)
                if (isConnected) {
                    isConnected = false
                    onConnectionStateChanged?.invoke(false)
                }
            }
        )
    }

    private fun handleMessage(update: Update) {
        val msg = update.message() ?: return
        val text = msg.text() ?: return
        val chatId = msg.chat()?.id()?.toString() ?: return
        if (!TelegramMessageFilter.shouldAccept(chatId, allowedChatId)) {
            Logger.w(TAG, "Telegram 消息被白名单拒绝: chatId=$chatId")
            return
        }
        onMessageReceived?.invoke(text, chatId)
    }

    override fun sendMessage(text: String, replyToken: String) {
        val b = bot ?: run {
            Logger.w(TAG, "发送失败：Telegram 客户端未初始化")
            return
        }
        val chatId = replyToken.toLongOrNull() ?: run {
            Logger.w(TAG, "发送失败：非法 chatId=$replyToken")
            return
        }
        scope.launch {
            val resp = b.execute(SendMessage(chatId, text))
            Logger.i(TAG, "Telegram 发送消息: ok=${resp.isOk}")
        }
    }

    override fun sendImage(bytes: ByteArray, replyToken: String) {
        val b = bot ?: return
        val chatId = replyToken.toLongOrNull() ?: return
        scope.launch {
            val resp = b.execute(SendPhoto(chatId, bytes))
            Logger.i(TAG, "Telegram 发送图片: ok=${resp.isOk}")
        }
    }

    fun disconnect() {
        bot?.let {
            runCatching { it.removeUpdatesListener() }
            runCatching { it.shutdown() }
        }
        bot = null
        if (isConnected) {
            isConnected = false
            onConnectionStateChanged?.invoke(false)
        }
        Logger.i(TAG, "Telegram 已断开")
    }

    companion object {
        private const val TAG = "TelegramHandler"
    }
}
```

- [ ] **Step 6: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/remote/TelegramMessageFilter.kt \
  app/src/main/java/com/mamba/picme/domain/agent/remote/TelegramChannelHandler.kt \
  app/src/test/java/com/mamba/picme/domain/agent/remote/TelegramMessageFilterTest.kt
git commit -m "feat(channel): Telegram 通道（Pengrad 长轮询 + chatId 白名单 fail-closed）"
```

---

### Task 7: RemoteChannelManager（单通道管理器）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannelManager.kt`

- [ ] **Step 1: 实现 manager**

```kotlin
package com.mamba.picme.domain.agent.remote

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.RemoteChannelType

/**
 * 单通道管理器：同一时刻仅一个通道连接。实现 [RemoteChannel] 供调度器透明使用。
 *
 * - [activate] 由 Application 在「选择 / 凭据」变化时调用；先全断开再按决策启动。
 * - [reconnect] 用上次参数重新 activate（网络恢复 / 回前台）。
 * - 发送与回调委托给当前激活通道；无激活时空操作。
 */
class RemoteChannelManager(
    private val feishu: FeishuChannelHandler,
    private val telegram: TelegramChannelHandler
) : RemoteChannel {

    @Volatile private var lastType: RemoteChannelType = RemoteChannelType.NONE
    @Volatile private var lastFeishuAppId: String = ""
    @Volatile private var lastFeishuAppSecret: String = ""
    @Volatile private var lastTelegramToken: String = ""
    @Volatile private var lastTelegramChatId: String = ""

    fun activate(
        type: RemoteChannelType,
        feishuAppId: String,
        feishuAppSecret: String,
        telegramBotToken: String,
        telegramAllowedChatId: String
    ) {
        lastType = type
        lastFeishuAppId = feishuAppId
        lastFeishuAppSecret = feishuAppSecret
        lastTelegramToken = telegramBotToken
        lastTelegramChatId = telegramAllowedChatId

        feishu.disconnect()
        telegram.disconnect()

        when (val decision = ChannelActivationResolver.resolve(
            type, feishuAppId, feishuAppSecret, telegramBotToken, telegramAllowedChatId
        )) {
            ChannelActivation.None ->
                Logger.i(TAG, "activate: 无激活通道（type=$type）")
            is ChannelActivation.Feishu ->
                feishu.init(decision.appId, decision.appSecret)
            is ChannelActivation.Telegram ->
                telegram.connect(decision.botToken, decision.allowedChatId)
        }
    }

    fun reconnect() {
        Logger.i(TAG, "reconnect: 重新 activate 上次选择（type=$lastType）")
        activate(lastType, lastFeishuAppId, lastFeishuAppSecret, lastTelegramToken, lastTelegramChatId)
    }

    override val channelId: String
        get() = when (lastType) {
            RemoteChannelType.FEISHU -> feishu.channelId
            RemoteChannelType.TELEGRAM -> telegram.channelId
            RemoteChannelType.NONE -> ""
        }

    /** 媒体来源标签（拍照观察者据此过滤）。 */
    val activeSourceTag: String
        get() = when (lastType) {
            RemoteChannelType.FEISHU -> "feishu_remote"
            RemoteChannelType.TELEGRAM -> "telegram_remote"
            RemoteChannelType.NONE -> ""
        }

    override val isConnected: Boolean
        get() = when (lastType) {
            RemoteChannelType.FEISHU -> feishu.isConnected
            RemoteChannelType.TELEGRAM -> telegram.isConnected
            RemoteChannelType.NONE -> false
        }

    override var onMessageReceived: ((text: String, replyToken: String) -> Unit)? = null
        set(value) {
            field = value
            feishu.onMessageReceived = value
            telegram.onMessageReceived = value
        }

    override var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null
        set(value) {
            field = value
            feishu.onConnectionStateChanged = value
            telegram.onConnectionStateChanged = value
        }

    private fun activeSender(): RemoteChannel? = when (lastType) {
        RemoteChannelType.FEISHU -> feishu
        RemoteChannelType.TELEGRAM -> telegram
        RemoteChannelType.NONE -> null
    }

    override fun sendMessage(text: String, replyToken: String) {
        activeSender()?.sendMessage(text, replyToken)
    }

    override fun sendImage(bytes: ByteArray, replyToken: String) {
        activeSender()?.sendImage(bytes, replyToken)
    }

    companion object {
        private const val TAG = "RemoteChannelManager"
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannelManager.kt
git commit -m "feat(channel): RemoteChannelManager 单通道管理器（activate/reconnect/委托发送）"
```

---

### Task 8: FeishuPhotoTracker → RemotePhotoTracker（改名 + 泛化）

**Files:**
- Rename: `app/src/main/java/com/mamba/picme/domain/agent/remote/FeishuPhotoTracker.kt` → `RemotePhotoTracker.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt:85-88`
- Modify: `app/src/main/java/com/mamba/picme/features/camera/CameraCaptureActions.kt:25,91-102,112-113,130-143`
- Modify: `app/src/main/java/com/mamba/picme/PoLangApplication.kt:46,483`

- [ ] **Step 1: 重命名文件并改类名/字段名**

`RemotePhotoTracker.kt`（原 `FeishuPhotoTracker.kt`）改为通道无关命名：

```kotlin
package com.mamba.picme.domain.agent.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 远程拍照追踪器：桥接「远程控制拍照命令」与「照片保存完成」事件。
 * 持有 pending [replyToken]（飞书=messageId，Telegram=chatId），照片保存后据此回复。
 * 通道无关，由 [RemoteCommandDispatcher] 标记、[PoLangApplication] 媒体观察者消费。
 */
object RemotePhotoTracker {

    private val _pendingReplyToken = MutableStateFlow<String?>(null)
    val pendingReplyToken: StateFlow<String?> = _pendingReplyToken.asStateFlow()

    fun startCapture(replyToken: String) {
        _pendingReplyToken.value = replyToken
    }

    fun finishCapture() {
        _pendingReplyToken.value = null
    }

    fun consumePendingReplyToken(): String? {
        val token = _pendingReplyToken.value
        _pendingReplyToken.value = null
        return token
    }

    fun hasPendingCapture(): Boolean = _pendingReplyToken.value != null
}
```

删除原 `FeishuPhotoTracker.kt` 文件（或重命名后移除旧名引用）。

- [ ] **Step 2: 更新 RemoteCommandDispatcher 引用**

`RemoteCommandDispatcher.kt:85-88`：

```kotlin
        if (text.contains("拍照") || text.contains("拍张") || text.contains("拍照片")) {
            RemotePhotoTracker.startCapture(messageId)
            Logger.i(tag, "远程拍照追踪已启动: replyToken=$messageId")
        }
```

（该文件其它 `FeishuPhotoTracker` 引用同步替换为 `RemotePhotoTracker`。）

- [ ] **Step 3: 更新 CameraCaptureActions 引用 + 发送走 manager**

`CameraCaptureActions.kt`：
- 25 行 import：`import com.mamba.picme.domain.agent.remote.RemotePhotoTracker`
- 把所有 `FeishuPhotoTracker` 替换为 `RemotePhotoTracker`，`consumePendingMessageId()` → `consumePendingReplyToken()`。
- 91-102 行与 130-143 行的错误通知发送：`app?.feishuChannelHandler?.sendMessage(msg, pendingMessageId)` 改为：

```kotlin
                        app?.remoteChannelManager?.sendMessage(
                            "❌ 拍照失败，请稍后重试",
                            pendingReplyToken
                        )
```

（其中 `pendingReplyToken = RemotePhotoTracker.consumePendingReplyToken()`，变量名同步改。）
- 112-113 行 photoSource 改为跟随激活通道：

```kotlin
        val photoSource = if (RemotePhotoTracker.hasPendingCapture()) {
            app?.remoteChannelManager?.activeSourceTag ?: "remote_capture"
        } else {
            null
        }
        Logger.i(TAG, "开始拍照: hasPendingCapture=${RemotePhotoTracker.hasPendingCapture()}, source=$photoSource")
```

- [ ] **Step 4: 更新 PoLangApplication 引用**

`PoLangApplication.kt:46` import 改为 `RemotePhotoTracker`；483 行 `FeishuPhotoTracker.consumePendingMessageId()` → `RemotePhotoTracker.consumePendingReplyToken()`。（其余 PoLangApplication 改造在 Task 11。）

> 注意：此任务引用了 `app.remoteChannelManager`（Task 11 才在 Application 暴露）与 Task 9 的 dispatcher 改造。**为保持可编译**，本任务与 Task 9、Task 11 在同一编译单元落盘后统一编译（见 Task 11 Step「统一编译」）。若希望每步可编译，可在 Application 中先临时暴露 `val remoteChannelManager: RemoteChannelManager get() = error("wired in Task 11")` 占位，Task 11 再替换为真实 lazy——但本项目选择批量落盘后统一编译（下同）。

- [ ] **Step 5: 提交（与 Task 9、11 合并提交，或单独提交后容忍编译延迟；建议合并）**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/remote/RemotePhotoTracker.kt \
  app/src/main/java/com/mamba/picme/domain/agent/remote/FeishuPhotoTracker.kt \
  app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt \
  app/src/main/java/com/mamba/picme/features/camera/CameraCaptureActions.kt
git rm app/src/main/java/com/mamba/picme/domain/agent/remote/FeishuPhotoTracker.kt 2>/dev/null || true
git commit -m "refactor(channel): FeishuPhotoTracker→RemotePhotoTracker（通道无关）+ 拍照发送走 manager"
```

---

### Task 9: RemoteCommandDispatcher 去飞书化

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt:43-55`

- [ ] **Step 1: 构造参数改为 RemoteChannel + 会话 ID 动态化**

类声明（43-48 行）改为：

```kotlin
class RemoteCommandDispatcher(
    private val channel: RemoteChannel,
    context: Context,
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao
) {
```

将硬编码 `private val feishuSessionId = "feishu"`（54-55 行）删除，改为读取当前激活通道 id（在每次 dispatch 入口取，保证切换通道后取最新）：

```kotlin
    private val tag = "RemoteDispatcher"
    private val appContext = context.applicationContext
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /** 当前会话 ID = 激活通道 id（feishu / telegram），每次 dispatch 动态读取。 */
    private fun sessionId(): String = channel.channelId.ifBlank { "remote" }
```

- [ ] **Step 2: 方法体内 feishuSessionId → sessionId()**

把 `dispatch / ensureFeishuSession / saveUserMessage / saveAgentMessage` 内所有 `feishuSessionId` 替换为 `sessionId()` 调用。`ensureFeishuSession` 内 `ChatSessionEntity(title = "飞书远程控制")` 标题改为按通道：

```kotlin
                    chatSessionDao.insertSession(
                        ChatSessionEntity(
                            sessionId = sessionId(),
                            title = if (sessionId() == "telegram") "Telegram 远程控制" else "飞书远程控制"
                        )
                    )
```

`dispatch` 内对 `channelHandler`（原 `feishuChannelHandler` 参数名）的调用改为 `channel`（构造参数已更名）。函数体内 `channelHandler.sendMessage(...)` → `channel.sendMessage(...)`。

- [ ] **Step 3: 编译确认（与 Task 8/11 统一）**

本任务单独编译会因为 PoLangApplication 仍传 `feishuChannelHandler` 而失败；统一在 Task 11 Step 编译。

- [ ] **Step 4: 提交（合并到 Task 8/11 提交）**

---

### Task 10: runtime-core — processFeishuInput → processRemoteImInput

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt:998`
- Modify: `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt:117`（调用点）

- [ ] **Step 1: 改名公共方法**

`AgentOrchestrator.kt:998`：

```kotlin
    suspend fun processRemoteImInput(
        input: String,
        windowManager: android.view.WindowManager,
        timeoutMs: Long = 120_000L
    ): Result<String> = withContext(Dispatchers.IO) {
        Logger.d(tag, "processRemoteImInput: input='$input', timeout=${timeoutMs}ms")
```

（函数体内对 `processFeishuInput` 的日志字符串同步改名；`getFeishuAgent` / `clearFeishuAgent` 等内部方法名保留，仅公共入口改名。）

- [ ] **Step 2: 更新唯一调用方**

`RemoteCommandDispatcher.kt:117`（dispatch 内 ReAct 路径）：

```kotlin
                        orchestrator.processRemoteImInput(text, wm, TIMEOUT_MS)
```

- [ ] **Step 3: 编译 runtime-core + app**

Run: `./gradlew :runtime-core:compileDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（runtime-core 既有单测若有引用 `processFeishuInput` 同步改名；当前无此引用。）

- [ ] **Step 4: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentOrchestrator.kt \
  app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt
git commit -m "refactor(runtime-core): processFeishuInput→processRemoteImInput（通道无关公共入口）"
```

---

### Task 11: PoLangApplication 接线 manager（统一激活/重连/拍照观察）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/PoLangApplication.kt:45-47,82,93-101,190-229,293-349,464-551`

- [ ] **Step 1: 新增 handler/manager 暴露 + dispatcher 注入 manager**

`PoLangApplication.kt`：
- 45-47 行 import 段追加：

```kotlin
import com.mamba.picme.domain.agent.remote.TelegramChannelHandler
import com.mamba.picme.domain.agent.remote.RemoteChannelManager
import com.mamba.picme.domain.agent.remote.RemotePhotoTracker
import com.mamba.picme.domain.model.RemoteChannelType
import kotlinx.coroutines.flow.map
```
（`FeishuPhotoTracker` import 删除；`FeishuChannelHandler` import 保留。）

- 82 行后新增 telegram handler 与 manager：

```kotlin
    val feishuChannelHandler: FeishuChannelHandler by lazy { FeishuChannelHandler(applicationScope) }

    val telegramChannelHandler: TelegramChannelHandler by lazy { TelegramChannelHandler(applicationScope) }

    val remoteChannelManager: RemoteChannelManager by lazy {
        RemoteChannelManager(feishuChannelHandler, telegramChannelHandler)
    }
```

- 93-101 行 `remoteCommandDispatcher` 构造参数由 `feishuChannelHandler` 改为 `remoteChannelManager`：

```kotlin
    val remoteCommandDispatcher: RemoteCommandDispatcher by lazy {
        val database = AppDatabase.getDatabase(this)
        RemoteCommandDispatcher(
            remoteChannelManager,
            this,
            database.chatMessageDao(),
            database.chatSessionDao()
        )
    }
```

- [ ] **Step 2: onCreate 用 manager 统一激活 + 绑定回调**

把 190-229 行「初始化飞书通道 + 监听飞书配置变化」两段替换为统一的 manager 激活（首发射即激活，后续变化重新激活）：

```kotlin
        // 绑定消息处理回调：远程消息 → RemoteCommandDispatcher（新消息取消旧任务防 ANR）
        remoteChannelManager.onMessageReceived = { text, replyToken ->
            feishuDispatchJob?.cancel()
            feishuDispatchJob = applicationScope.launch {
                remoteCommandDispatcher.dispatch(text, replyToken)
            }
        }

        // 统一监听：通道选择 + 飞书凭据 + Telegram 凭据 → manager.activate
        applicationScope.launch {
            try {
                val repo = container.userPreferencesRepository
                combine(
                    repo.selectedRemoteChannelFlow.map { RemoteChannelType.fromStored(it) },
                    repo.feishuAppIdFlow,
                    repo.feishuAppSecretFlow,
                    repo.telegramBotTokenFlow,
                    repo.telegramAllowedChatIdFlow
                ) { type, fId, fSecret, tToken, tChat -> ChannelSelection(type, fId, fSecret, tToken, tChat) }
                    .collect { sel ->
                        remoteChannelManager.activate(
                            sel.type, sel.feishuAppId, sel.feishuAppSecret,
                            sel.telegramBotToken, sel.telegramChatId
                        )
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "远程通道激活监听失败", e)
            }
        }
```

并在文件底部 `data class SyncConfig` 旁新增：

```kotlin
    private data class ChannelSelection(
        val type: RemoteChannelType,
        val feishuAppId: String,
        val feishuAppSecret: String,
        val telegramBotToken: String,
        val telegramChatId: String
    )
```

- [ ] **Step 3: 网络监听 + ActivityTracker 重连走 manager**

- `registerFeishuNetworkMonitor()`（293-349 行）内 `feishuChannelHandler.reconnectIfNeeded()` → `remoteChannelManager.reconnect()`（两处）。
- `ActivityTracker.onActivityStarted`（568-571 行）`feishuChannelHandler.reconnectIfNeeded()` → `remoteChannelManager.reconnect()`。

- [ ] **Step 4: 拍照观察者通道化（observeFeishuPhotoCapture → observeRemotePhotoCapture）**

把 `observeFeishuPhotoCapture()`（464-551 行）改为按激活通道过滤来源与会话：

```kotlin
    private fun observeRemotePhotoCapture() {
        applicationScope.launch {
            try {
                val chatMessageDao = AppDatabase.getDatabase(this@PoLangApplication).chatMessageDao()
                val chatSessionDao = AppDatabase.getDatabase(this@PoLangApplication).chatSessionDao()

                repository.allMedia.collect { mediaList ->
                    val sourceTag = remoteChannelManager.activeSourceTag
                    if (sourceTag.isBlank()) return@collect
                    val remotePhotos = mediaList.filter { it.source == sourceTag && it.type == MediaType.PHOTO }
                    if (remotePhotos.isEmpty()) return@collect

                    val pendingReplyToken = RemotePhotoTracker.consumePendingReplyToken() ?: return@collect
                    val sessionId = remoteChannelManager.channelId.ifBlank { "remote" }
                    val latestPhoto = remotePhotos.maxByOrNull { it.captureDate } ?: return@collect
                    Logger.i(TAG, "检测到远程拍照结果: uri=${latestPhoto.uri}, session=$sessionId")

                    // 1. 写入聊天记录（agent_image）
                    try {
                        if (chatSessionDao.getSession(sessionId) == null) {
                            chatSessionDao.insertSession(
                                ChatSessionEntity(
                                    sessionId = sessionId,
                                    title = if (sessionId == "telegram") "Telegram 远程控制" else "飞书远程控制"
                                )
                            )
                        }
                        chatMessageDao.insertMessage(
                            ChatMessageEntity(
                                id = UUID.randomUUID().toString(),
                                sessionId = sessionId,
                                type = "agent_image",
                                content = latestPhoto.uri,
                                modelUsed = sourceTag
                            )
                        )
                        chatSessionDao.touchSession(sessionId)
                    } catch (e: Exception) {
                        Logger.e(TAG, "写入远程聊天记录失败", e)
                    }

                    // 2. 经 manager 发图到激活通道
                    try {
                        val uri = android.net.Uri.parse(latestPhoto.uri)
                        val compressedBytes = compressImageForFeishu(uri, 2048, 85)
                        if (compressedBytes != null) {
                            remoteChannelManager.sendImage(compressedBytes, pendingReplyToken)
                            remoteChannelManager.sendMessage("✅ 照片已发送，请查收", pendingReplyToken)
                        } else {
                            val pfd = contentResolver.openFileDescriptor(uri, "r")
                            if (pfd != null) {
                                val imageBytes = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                                pfd.close()
                                remoteChannelManager.sendImage(imageBytes, pendingReplyToken)
                                remoteChannelManager.sendMessage("✅ 照片已发送，请查收", pendingReplyToken)
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "发送照片失败", e)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "远程拍照监听启动失败", e)
            }
        }
    }
```

`onCreate` 末尾 `observeFeishuPhotoCapture()` 调用改为 `observeRemotePhotoCapture()`。

- [ ] **Step 5: 统一编译（Task 8/9/11 合并后首次完整编译）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（无 `FeishuPhotoTracker` / `feishuChannelHandler` 残留引用）

- [ ] **Step 6: 提交（与 Task 8/9 合并）**

```bash
git add app/src/main/java/com/mamba/picme/PoLangApplication.kt
git commit -m "feat(channel): Application 接线 RemoteChannelManager（统一 activate/reconnect + 拍照观察通道化）"
```

---

### Task 12: i18n 字符串（三语同步）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: values/strings.xml 新增 + 改写**

在 `communication_channel` 段（309-311 行附近）追加/改写：

```xml
    <string name="communication_channel_desc">Configure Feishu / Telegram remote control channels.</string>
    <string name="channel_selection">Active Channel</string>
    <string name="channel_none">None</string>
    <string name="channel_feishu">Feishu</string>
    <string name="channel_telegram">Telegram</string>
    <string name="feishu_app_id">App ID</string>
    <string name="feishu_app_secret">App Secret</string>
    <string name="feishu_app_id_placeholder">Feishu App ID</string>
    <string name="feishu_app_secret_placeholder">Feishu App Secret</string>
    <string name="telegram_channel_desc">Connect via Telegram Bot long polling (no public IP needed).</string>
    <string name="telegram_bot_token">Bot Token</string>
    <string name="telegram_bot_token_desc">Create a bot via \@BotFather and paste its token.</string>
    <string name="telegram_bot_token_placeholder">123456:ABC-DEF...</string>
    <string name="telegram_chat_id">Allowed Chat ID</string>
    <string name="telegram_chat_id_desc">Only this chat can send commands (security whitelist).</string>
    <string name="telegram_chat_id_placeholder">e.g. 123456789</string>
    <string name="telegram_security_note">Without an Allowed Chat ID, the bot rejects all messages for safety.</string>
    <string name="channel_status_connected">Connected</string>
    <string name="channel_status_disconnected">Disconnected</string>
    <string name="channel_status_not_configured">Not configured</string>
```

（`communication_channel` 保留；`feishu_channel_desc` 保留。）

- [ ] **Step 2: values-zh-rCN/strings.xml 对应简中**

```xml
    <string name="communication_channel_desc">配置飞书 / Telegram 远程控制通道。</string>
    <string name="channel_selection">当前通道</string>
    <string name="channel_none">不启用</string>
    <string name="channel_feishu">飞书</string>
    <string name="channel_telegram">Telegram</string>
    <string name="feishu_app_id">App ID</string>
    <string name="feishu_app_secret">App Secret</string>
    <string name="feishu_app_id_placeholder">飞书应用的 App ID</string>
    <string name="feishu_app_secret_placeholder">飞书应用的 App Secret</string>
    <string name="telegram_channel_desc">通过 Telegram Bot 长轮询连接（无需公网 IP）。</string>
    <string name="telegram_bot_token">Bot Token</string>
    <string name="telegram_bot_token_desc">在 \@BotFather 创建机器人后粘贴其 token。</string>
    <string name="telegram_bot_token_placeholder">123456:ABC-DEF...</string>
    <string name="telegram_chat_id">允许的 Chat ID</string>
    <string name="telegram_chat_id_desc">仅该聊天可下发指令（安全白名单）。</string>
    <string name="telegram_chat_id_placeholder">如 123456789</string>
    <string name="telegram_security_note">未填写「允许的 Chat ID」时，为安全起见机器人将拒绝所有消息。</string>
    <string name="channel_status_connected">已连接</string>
    <string name="channel_status_disconnected">未连接</string>
    <string name="channel_status_not_configured">未配置</string>
```

- [ ] **Step 3: values-zh-rTW/strings.xml 对应繁中**

```xml
    <string name="communication_channel_desc">設定飛書 / Telegram 遠端控制通道。</string>
    <string name="channel_selection">目前通道</string>
    <string name="channel_none">不啟用</string>
    <string name="channel_feishu">飛書</string>
    <string name="channel_telegram">Telegram</string>
    <string name="feishu_app_id">App ID</string>
    <string name="feishu_app_secret">App Secret</string>
    <string name="feishu_app_id_placeholder">飛書應用的 App ID</string>
    <string name="feishu_app_secret_placeholder">飛書應用的 App Secret</string>
    <string name="telegram_channel_desc">透過 Telegram Bot 長輪詢連線（免公網 IP）。</string>
    <string name="telegram_bot_token">Bot Token</string>
    <string name="telegram_bot_token_desc">在 \@BotFather 建立機器人後貼上其 token。</string>
    <string name="telegram_bot_token_placeholder">123456:ABC-DEF...</string>
    <string name="telegram_chat_id">允許的 Chat ID</string>
    <string name="telegram_chat_id_desc">僅該聊天可下達指令（安全白名單）。</string>
    <string name="telegram_chat_id_placeholder">如 123456789</string>
    <string name="telegram_security_note">未填寫「允許的 Chat ID」時，為安全起見機器人將拒絕所有訊息。</string>
    <string name="channel_status_connected">已連線</string>
    <string name="channel_status_disconnected">未連線</string>
    <string name="channel_status_not_configured">未設定</string>
```

- [ ] **Step 4: 资源编译确认**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "i18n(channel): 新增通道配置页三语字符串 + 泛化 communication_channel_desc"
```

---

### Task 13: CommunicationChannelViewModel + CommunicationChannelScreen

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelViewModel.kt`
- Create: `app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelScreen.kt`

- [ ] **Step 1: 实现 ViewModel**

```kotlin
package com.mamba.picme.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.domain.model.RemoteChannelType
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「通信通道」配置页 VM：通道选择 + 飞书凭据 + Telegram 凭据。直接依赖 [UserSettingsRepository]。 */
class CommunicationChannelViewModel(
    private val repository: UserSettingsRepository
) : ViewModel() {

    val selectedChannel: StateFlow<RemoteChannelType> = repository.selectedRemoteChannelFlow
        .map { RemoteChannelType.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemoteChannelType.FEISHU)

    val feishuAppId: StateFlow<String> = repository.feishuAppIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val feishuAppSecret: StateFlow<String> = repository.feishuAppSecretFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val telegramBotToken: StateFlow<String> = repository.telegramBotTokenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val telegramAllowedChatId: StateFlow<String> = repository.telegramAllowedChatIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun selectChannel(type: RemoteChannelType) {
        viewModelScope.launch { repository.updateSelectedRemoteChannel(type.name) }
    }

    fun setFeishuAppId(appId: String) {
        viewModelScope.launch { repository.updateFeishuAppId(appId) }
    }

    fun setFeishuAppSecret(secret: String) {
        viewModelScope.launch { repository.updateFeishuAppSecret(secret) }
    }

    fun setTelegramConfig(botToken: String, allowedChatId: String) {
        viewModelScope.launch { repository.updateTelegramConfig(botToken, allowedChatId) }
    }

    companion object {
        fun factory(repository: UserSettingsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CommunicationChannelViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return CommunicationChannelViewModel(repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }
}
```

- [ ] **Step 2: 实现 Screen**

```kotlin
package com.mamba.picme.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.model.RemoteChannelType

/** 「通信通道」配置页：单通道选择 + 飞书/Telegram 凭据 + 连接状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunicationChannelScreen(
    viewModel: CommunicationChannelViewModel,
    isConnected: Boolean,
    isConfigured: Boolean,
    onNavigateBack: () -> Unit
) {
    val selected by viewModel.selectedChannel.collectAsState()
    val feishuAppId by viewModel.feishuAppId.collectAsState()
    val feishuAppSecret by viewModel.feishuAppSecret.collectAsState()
    val telegramBotToken by viewModel.telegramBotToken.collectAsState()
    val telegramChatId by viewModel.telegramAllowedChatId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.communication_channel)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // ── 通道选择 ──
            SettingsSection(
                title = stringResource(R.string.channel_selection),
                description = null
            ) {
                ChannelSelectionChips(selected = selected, onSelect = viewModel::selectChannel)
                Text(
                    text = statusText(isConfigured, isConnected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // ── 飞书 ──
            SettingsSection(
                title = stringResource(R.string.channel_feishu),
                description = stringResource(R.string.feishu_channel_desc)
            ) {
                SettingsTextInputRow(
                    title = stringResource(R.string.feishu_app_id),
                    value = feishuAppId,
                    onValueChange = viewModel::setFeishuAppId,
                    placeholder = stringResource(R.string.feishu_app_id_placeholder)
                )
                SettingsTextInputRow(
                    title = stringResource(R.string.feishu_app_secret),
                    value = feishuAppSecret,
                    onValueChange = viewModel::setFeishuAppSecret,
                    placeholder = stringResource(R.string.feishu_app_secret_placeholder),
                    isPassword = true
                )
            }

            // ── Telegram ──
            SettingsSection(
                title = stringResource(R.string.channel_telegram),
                description = stringResource(R.string.telegram_channel_desc)
            ) {
                SettingsTextInputRow(
                    title = stringResource(R.string.telegram_bot_token),
                    value = telegramBotToken,
                    onValueChange = { viewModel.setTelegramConfig(it, telegramChatId) },
                    placeholder = stringResource(R.string.telegram_bot_token_placeholder),
                    isPassword = true
                )
                SettingsTextInputRow(
                    title = stringResource(R.string.telegram_chat_id),
                    value = telegramChatId,
                    onValueChange = { viewModel.setTelegramConfig(telegramBotToken, it) },
                    placeholder = stringResource(R.string.telegram_chat_id_placeholder)
                )
                Text(
                    text = stringResource(R.string.telegram_bot_token_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Text(
                    text = stringResource(R.string.telegram_chat_id_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                Text(
                    text = stringResource(R.string.telegram_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelSelectionChips(
    selected: RemoteChannelType,
    onSelect: (RemoteChannelType) -> Unit
) {
    val options = listOf(
        RemoteChannelType.FEISHU to stringResource(R.string.channel_feishu),
        RemoteChannelType.TELEGRAM to stringResource(R.string.channel_telegram),
        RemoteChannelType.NONE to stringResource(R.string.channel_none)
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (type, label) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(label) }
            )
        }
    }
}

private fun statusText(isConfigured: Boolean, isConnected: Boolean): String = when {
    !isConfigured -> "⚠️ 未配置"
    isConnected -> "✅ 已连接"
    else -> "🔌 未连接"
}
```

> `SettingsSection` 与 `SettingsTextInputRow` 为同包 `internal` 组件（`SettingsBaseComponents.kt`），可直接调用。`SettingsSection(title, description)` 的 `description` 形参允许 `null`（执行时若签名要求非空，传 `""`）。`FlowRow` 需 `androidx.compose.foundation.layout.FlowRow`（Compose foundation 1.5+，项目 BOM 2024.12 已含）。

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelViewModel.kt \
  app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelScreen.kt
git commit -m "feat(channel): 通信通道配置页（VM + Screen，单通道选择 + 双通道凭据 + 状态）"
```

---

### Task 14: 导航 + 主页网格一级入口 + 移除内联 section + SettingsVM 清理

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/navigation/Screen.kt:23`
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt:475-477`
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt:109-389,536-559,986-1033`
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt:267-278,1056-1068`

- [ ] **Step 1: 新增导航路由**

`Screen.kt` 23 行（`MemoryFacts` 之后）加：

```kotlin
    data object CommunicationChannel : Screen("communication_channel")
```

- [ ] **Step 2: MainActivity 注册 composable + 接线**

`MainActivity.kt` `DataPrivacy` composable（475-477 行）后加：

```kotlin
                            composable(Screen.CommunicationChannel.route) {
                                val app = this@MainActivity.applicationContext as com.mamba.picme.PoLangApplication
                                val vm: CommunicationChannelViewModel = viewModel(
                                    factory = CommunicationChannelViewModel.factory(
                                        app.container.userPreferencesRepository
                                    )
                                )
                                val isConnected by app.remoteChannelManager
                                    .let { kotlinx.coroutines.flow.flowOf(it.isConnected) }.collectAsState(initial = false)
                                CommunicationChannelScreen(
                                    viewModel = vm,
                                    isConnected = isConnected,
                                    isConfigured = when (vm.selectedChannel.collectAsState().value) {
                                        com.mamba.picme.domain.model.RemoteChannelType.FEISHU ->
                                            vm.feishuAppId.collectAsState().value.isNotBlank() &&
                                                vm.feishuAppSecret.collectAsState().value.isNotBlank()
                                        com.mamba.picme.domain.model.RemoteChannelType.TELEGRAM ->
                                            vm.telegramBotToken.collectAsState().value.isNotBlank()
                                        com.mamba.picme.domain.model.RemoteChannelType.NONE -> false
                                    },
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
```

> 顶部 import 追加 `com.mamba.picme.features.settings.CommunicationChannelScreen`、`CommunicationChannelViewModel`。`isConnected` 用一次性快照即可（通道状态由 Application 激活驱动；若需实时，后续可把 manager 的连接状态暴露为 Flow——本任务用快照满足「展示当前态」）。若 `kotlinx.coroutines.flow.flowOf(...).collectAsState` 不能反映后续变化，可简化为 `isConnected = app.remoteChannelManager.isConnected`（进入页面时取值）。

- [ ] **Step 3: SettingsScreen 加网格入口 + onNavigateToCommunicationChannel 透传**

- `SettingsScreen(...)` 签名（109-124 行）新增参数：

```kotlin
    onNavigateToCommunicationChannel: () -> Unit = {}
```

- 同样给 `SettingsContent(...)` 签名（316-389 行）加 `onNavigateToCommunicationChannel: () -> Unit = {}`，并在 `SettingsScreen` 的 `SettingsContent(...)` 调用处（309 行附近）透传 `onNavigateToCommunicationChannel = onNavigateToCommunicationChannel`。
- `SettingsMainMenu(...)` / `SettingsCategoryGrid(...)` 签名加 `onNavigateToCommunicationChannel: () -> Unit`，逐层透传。
- `SettingsCategoryGrid`（1012-1033 行）`items` 列表末尾追加一级卡片：

```kotlin
        CategoryGridItem(R.string.communication_channel, R.string.communication_channel_desc, Icons.Rounded.Forum) {
            onNavigateToCommunicationChannel()
        },
```

（顶部 import 追加 `androidx.compose.material.icons.rounded.Forum`。）

- **移除内联 section**：删除 `SettingsContent` 中 `AI_AGENT` 分类下的「通信通道」`SettingsSection`（536-559 行整段）。
- **移除飞书 state**：删除 `SettingsScreen` 中 `val feishuAppId/feishuAppSecret by ...collectAsState()`（169-170 行）、`SettingsContent` 形参 `feishuAppId/feishuAppSecret/onFeishuAppIdChange/onFeishuAppSecretChange`（377-380 行）及其在 `SettingsScreen(...)` 调用处的传参（297-300 行），以及 `@Preview` 中的 `feishuAppId=""` 等实参（1338-1341 行）。

- [ ] **Step 4: SettingsViewModel 移除飞书 state**

`SettingsViewModel.kt` 删除 `feishuAppId/feishuAppSecret` StateFlow（267-278 行）与 `setFeishuAppId/setFeishuAppSecret`（1056-1068 行）。这些 state 已迁至 `CommunicationChannelViewModel`。

- [ ] **Step 5: 两个 SettingsScreen 调用点接线 onNavigateToCommunicationChannel**

`MainActivity.kt` 两处 `SettingsScreen(...)` 调用（364-395、419-450 行）各加：

```kotlin
                                    onNavigateToCommunicationChannel = {
                                        navController.navigate(Screen.CommunicationChannel.route, navOptions { launchSingleTop = true })
                                    }
```

- [ ] **Step 6: 整体编译 + 装 APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（无未使用形参/未解析引用）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/navigation/Screen.kt \
  app/src/main/java/com/mamba/picme/MainActivity.kt \
  app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt \
  app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt
git commit -m "feat(channel): 通信通道设置二级页（主页网格一级入口 + 导航 + 移除内联 section）"
```

---

### Task 15: 文档同步（三层同提交）

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`
- Modify: `runtime-core/AGENTS.md`（若提及 `processFeishuInput`）

- [ ] **Step 1: tech spec 解冻 + 多通道补章节**

`IM_REMOTE_CONTROL_TECH_SPEC.md` 顶部状态块改为：

```markdown
> **版本**: 1.1
> **状态**: 重新激活（多通道）
> **最后更新**: 2026-07-26（从「已冻结」重新激活，新增 Telegram + 单通道选择 + release 凭据隔离）
> **维护者**: RD Agent

> ℹ️ 本线于 2026-07-16 冻结、2026-07-26 重新激活以承载多通道能力。服务端中转方案与本端直连方案并行存在。
```

并在「架构总览」后新增小节：`RemoteChannel` 接口 / `RemoteChannelManager`（单通道 activate/reconnect）/ `TelegramChannelHandler`（Pengrad 长轮询 + chatId 白名单 fail-closed）/ release 凭据隔离（`buildConfigField` 仅 debug 注入）/ 通道选择 DataStore 键。

- [ ] **Step 2: runtime-core AGENTS.md 公共 API 改名**

若 `runtime-core/AGENTS.md` 提及 `processFeishuInput`，改为 `processRemoteImInput` 并注明「通道无关远程 IM 入口」。（grep 确认：`grep -rn "processFeishuInput" runtime-core/*.md docs/`。）

- [ ] **Step 3: 提交**

```bash
git add docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md runtime-core/AGENTS.md
git commit -m "docs(channel): IM 远程控制 tech spec 解冻 + 多通道（飞书+Telegram）/release 凭据隔离"
```

---

### Task 16: 全量验证

- [ ] **Step 1: 全量 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（含新增 `RemoteChannelTypeTest` / `ChannelActivationResolverTest` / `TelegramMessageFilterTest` + 既有用例不回归）

- [ ] **Step 2: debug 全量编译装包**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: release 凭据隔离构建期校验**

Run: `./gradlew :app:assembleRelease -Ppolang.release.plain=true`（plain 模式免混淆加速）
然后确认 release 产物飞书字段为空：

```bash
grep -ao "cli_[a-zA-Z0-9_]*" app/build/outputs/apk/release/polang-release-unsigned.apk | head || echo "✓ release APK 无飞书 AppId 明文"
```
Expected: 无 `cli_...` 明文（开发凭据未打进 release）。

> 人工/设备验证（非门，记录待办）：debug 包开机自动连飞书；切到 Telegram + 填 token/chatId 后能收发；release 包首次进入通道页为空、需用户填写。

- [ ] **Step 4: 收尾提交（如有验证微调）**

仅在前面步骤有遗留改动时提交；否则本任务无代码改动。

---

## Self-Review（计划作者自检，已执行）

1. **Spec 覆盖**：spec 各节均有任务对应——数据模型/凭据隔离（Task 1/2）、通道抽象与 manager（3/7）、Feishu 实现（4）、Telegram+安全（5/6）、调度器去飞书化+processFeishuInput 改名（9/10）、Application 接线+拍照观察通道化（8/11）、配置页+入口（13/14）、i18n（12）、文档（15）、验证（16）。✓
2. **占位符扫描**：无 TBD/TODO；所有代码块均为可直接落盘代码。✓
3. **类型一致性**：`RemoteChannel` 接口（Task 3）= Feishu/Telegram/Manager 实现签名（4/6/7）一致；`ChannelActivation`（3）= manager when 分支（7）一致；`RemotePhotoTracker.consumePendingReplyToken()`（8）= Application/Dispatcher 调用（9/11）一致；`processRemoteImInput`（10）= 调用方（10/Step2）一致。✓
4. **已知交叉编译依赖**：Task 8/9/11 相互引用（`app.remoteChannelManager`、dispatcher 构造参数），已在 Task 8 Step 4 与 Task 11 Step 5 注明「合并后统一编译」。执行时按 8→9→10→11 顺序落盘，Task 11 Step 5 首次完整编译。
