# 多通信通道（飞书 + Telegram）远程控制 — 设计文档

> **日期**: 2026-07-26
> **状态**: 已批准，待实现
> **维护者**: RD Agent
> **关联**: `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`（本次从「已冻结」重新激活）

## 1. 背景与动机

IM 远程控制线允许用户通过 IM 消息（如「搜索去年夏天小孩的照片」「拍照」）远程驱动 App：设备端跑 ReAct Agent → 应用内 UI 自动化 → 经同一 IM 通道回复文本与照片。当前仅支持飞书。

三个驱动力：

1. **release 凭据隔离**：飞书 `AppId/AppSecret` 目前经 `BuildConfig`（`defaultConfig`）编译进**所有**构建类型，release APK 反编译即可拿到开发者的飞书凭据。需改为 debug 保留、release 不打包、由用户自行输入。
2. **增加 Telegram 通道**：在飞书之外新增 Telegram，作为等价的远程控制入口。
3. **独立配置页**：把当前内联在「设置 → AI 助手」里的通道配置，迁出到独立页面，入口留在设置页。

**冻结线重新激活**：`IM_REMOTE_CONTROL_TECH_SPEC.md` 于 2026-07-16 标记为「已冻结 / 服务端替代方案优先」。经产品决策，本次重新激活该线以承载多通道能力；tech spec 状态随之更新（见 §10）。

## 2. 目标 / 非目标

**目标**
- release 包不打包飞书凭据；debug 包保留现有注入式开发体验（开机自动连）。
- 新增 Telegram 通道，能力与飞书对等（收文本指令 → ReAct → 回复文本 + 照片）。
- 飞书 / Telegram 单通道选择：同一时刻仅一个通道连接。
- 独立「通信通道」配置页，入口在设置页。
- 三语 i18n 同步（EN / zh-rCN / zh-rTW）。

**非目标**
- 多通道同时在线（已选单通道模型）。
- Telegram 群组 / 频道 / 富媒体（语音、视频）、Webhook 模式。
- 服务端中转方案（与本端直连方案并行存在，互不替代）。

## 3. 关键决策

| 决策点 | 选定 | 理由 |
|---|---|---|
| 通道抽象程度 | 单通道选择 + 轻抽象 | 仅 2 通道、单时刻 1 连接，无需注册表；小接口让调度器去飞书化 |
| Telegram 接入 | Pengrad SDK 长轮询 | 与飞书「SDK + 出站 + 自动重连」架构对称；无公网 IP |
| 凭据存储 | 每通道独立 DataStore 键 | 2 通道最简，与现有 `feishu_*` 键风格一致 |
| 默认选中通道 | `FEISHU` | 保 debug 开机自动连；release 凭据为空则不连接 |
| `processFeishuInput` | 改名为通道无关 | 行为本就通用，Telegram 路径不应顶着 Feishu 名字 |
| 配置页 VM | 自治 `CommunicationChannelViewModel` | 不再往已过载的 `SettingsViewModel` 塞；直接依赖 `UserSettingsRepository` |

## 4. 架构

### 4.1 通道接口

引入轻量接口，让调度器与拍照观察者面向「当前激活通道」编程，而非具体飞书：

```kotlin
interface RemoteChannel {
    val channelId: String                                       // "feishu" / "telegram"
    val isConnected: Boolean
    var onMessageReceived: ((text: String, replyToken: String) -> Unit)?
    var onConnectionStateChanged: ((connected: Boolean) -> Unit)?
    fun sendMessage(text: String, replyToken: String)
    fun sendImage(bytes: ByteArray, replyToken: String)
}
```

`replyToken` 是通道不透明串：飞书侧 = messageId，Telegram 侧 = chatId。单通道模型下 token 必来自当前激活通道，各通道自行解释，调度器仅透传。

**重连由 manager 统一负责**（不放进接口）：网络恢复 / 回前台时 `manager.reconnect()` = 用上次的选择与凭据重新 `activate`，两个通道均通过重连重建。接口只保留发送 + 状态 + 回调。

连接生命周期（typed 参数不同）留在具体类上：飞书 `init(appId, appSecret)` / `disconnect()`；Telegram `connect(token, allowedChatId)` / `disconnect()`。

### 4.2 Handler

- **`FeishuChannelHandler`**：加 `: RemoteChannel`。现有 `sendMessage/sendImage/disconnect/reconnectIfNeeded/isConnected/onMessageReceived/onConnectionStateChanged` 已基本对齐，改动极小（`onMessageReceived` 回调签名由 `(text, messageId)` 语义化为 `(text, replyToken)`，类型不变）。
- **`TelegramChannelHandler`**（新增，`domain/agent/remote/`）：Pengrad `Bot(token)`，`setUpdatesListener` 长轮询收消息（库内部管 `getUpdates` 的 offset/timeout/重试），`SendMessage`/`SendPhoto` 发送；`removeListener` + `bot.shutdown()` 停止。`channelId = "telegram"`。
  - **chatId 白名单**：仅处理来自 `allowedChatId` 的消息，其余忽略（见 §7）。

### 4.3 RemoteChannelManager

```kotlin
class RemoteChannelManager(
    private val feishu: FeishuChannelHandler,
    private val telegram: TelegramChannelHandler
) : RemoteChannel
```

- `sendMessage/sendImage/isConnected` 及回调：委托给当前激活通道；无激活通道时安全空操作。
- `activate(type: ChannelType, feishu: FeishuCreds?, telegram: TelegramCreds?)`：先断开两个通道，再按 `type` 用对应凭据启动（凭据缺失则保持断开）。由 `PoLangApplication` 在 `selected_remote_channel` 或凭据变化时调用。manager 记住上次 `activate` 参数。
- `reconnect()`：用上次的选择与凭据重新 `activate`（网络恢复 / 回前台时调用）。
- 暴露 `activeChannelId` 供拍照观察者判断照片来源。

`RemoteChannelManager` 本身实现 `RemoteChannel`，故 `RemoteCommandDispatcher` 持有它即持有「逻辑通道」，发送自动落到激活通道。

### 4.4 调度器解耦

`RemoteCommandDispatcher`：
- 构造参数 `FeishuChannelHandler` → `RemoteChannel`（实际注入 manager）。
- 会话 ID 由硬编码 `"feishu"` → `channel.channelId`（飞书仍为 `"feishu"`，历史聊天记录兼容；Telegram 为 `"telegram"`）。
- `FeishuPhotoTracker` → `RemotePhotoTracker`：仅持有 pending `replyToken`，本就通道无关，泛化 + 改名。
- `observeFeishuPhotoCapture`（在 `PoLangApplication`）→ 按激活通道判断照片来源（`feishu_remote` / `telegram_remote`），经 manager 发图。

### 4.5 processFeishuInput 改名

`runtime-core` 的 `AgentOrchestrator.processFeishuInput(text, wm, timeout)` 行为通道无关（ReAct 循环 + 应用内 UI 自动化）。改名为 `processRemoteImInput`（或等价通道无关名）。唯一调用方为 `RemoteCommandDispatcher`，同步更新。属 runtime-core 公共 API 变更，需在 `runtime-core` 的 AGENTS.md / 文档中注明。

### 4.6 PoLangApplication 接线

- 用 `RemoteChannelManager(feishuChannelHandler, telegramChannelHandler)` 替代直接持有 `feishuChannelHandler` 的对外用法（内部仍 lazy 持有两个具体 handler）。
- 启动：读 `selected_remote_channel` + 各通道凭据 → `manager.activate(...)`；`onMessageReceived` 回调 → `remoteCommandDispatcher.dispatch`（保留现有「新消息取消旧任务」防 ANR 逻辑）。
- 监听 `selected_remote_channel` + 飞书凭据 + Telegram 凭据的组合 flow（`.drop(1)`）→ `manager.activate(...)`。
- 网络恢复 / 回前台的 `reconnectIfNeeded()` 调用改为 `manager.reconnect()`（内部重新 `activate` 上次选择）。

## 5. 数据模型

### 5.1 DataStore 键（`UserPreferencesRepository` / `UserSettingsRepository`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `feishu_app_id` | String | BuildConfig 回退 | 已有 |
| `feishu_app_secret` | String | BuildConfig 回退 | 已有 |
| `selected_remote_channel` | String(enum) | `FEISHU` | 新增；`FEISHU`/`TELEGRAM`/`NONE` |
| `telegram_bot_token` | String | `""` | 新增 |
| `telegram_allowed_chat_id` | String | `""` | 新增；安全白名单 |

新增枚举 `RemoteChannelType { FEISHU, TELEGRAM, NONE }`（`domain.model`），DataStore 存 `name`，读取时 `runCatching { valueOf }` 容错回退默认。

### 5.2 Build 配置（`app/build.gradle.kts`）— release 凭据隔离

`FEISHU_APP_ID/SECRET` 的 `buildConfigField` 从 `defaultConfig` 移至 `buildTypes.debug`，`defaultConfig` 保留空串定义：

```kotlin
defaultConfig {
    // release 默认空串：用户必须自行配置；debug 在 buildTypes.debug 覆盖为注入值
    buildConfigField("String", "FEISHU_APP_ID", "\"\"")
    buildConfigField("String", "FEISHU_APP_SECRET", "\"\"")
}
buildTypes {
    release { /* 继承 defaultConfig 空串 */ }
    debug {
        // 仅 debug 注入开发者飞书凭据（local.properties / 环境变量）
        buildConfigField("String", "FEISHU_APP_ID", "\"${feishuAppId}\"")
        buildConfigField("String", "FEISHU_APP_SECRET", "\"${feishuAppSecret}\"")
    }
}
```

效果：
- `BuildConfig.FEISHU_APP_ID` 恒存在 → `UserPreferencesRepository` 中 `?: BuildConfig.FEISHU_APP_ID` 回退逻辑**无需改动**。
- **debug**：注入真实值，开机自动连（行为不变）。
- **release**：编译进空串 → 回退为空 → 初始化跳过 → 用户须自行配置；反编译拿不到开发者 secret。

## 6. Telegram 通道细节

- **依赖**：`com.github.pengrad:java-telegram-bot-api:5.5.0`（Maven Central）。加入 `gradle/libs.versions.toml`（`telegramBot` 版本号 + `telegram-bot` library 别名）与 `:app` dependencies。
- **连接模型**：出站 HTTP 长轮询，无需公网 IP，与飞书「出站、自动重连」一致（传输层飞书为 WebSocket 推送、Telegram 为长轮询，这是 Telegram 平台限制，非实现差异）。
- **收发对等**：文本指令 → 现有 ReAct 远程控制全流程 → `SendMessage(chatId, text)`；拍照结果 `SendPhoto(chatId, bytes)`。

## 7. 安全（Telegram 必做）

飞书靠 `appId/appSecret` 天然限定仅授权用户可控设备；Telegram bot token 一旦泄露，任何知道 bot 用户名者均可 DM 控制设备。故 `telegram_allowed_chat_id` 为**必填安全约束**：

- `TelegramChannelHandler` 仅处理 `message.chat.id == allowedChatId` 的消息，其余忽略并记日志。
- 配置页对 Chat ID 字段给出明确安全说明（「限制只有该聊天可下发指令」）。
- 未填 Chat ID 时：handler 拒绝处理任何消息并提示用户配置（不「宽松放行」）。

## 8. UI：新配置页 + 导航 + 入口

- **新页面** `CommunicationChannelScreen` + `CommunicationChannelViewModel`（`features/settings/`，自治，直接依赖 `UserSettingsRepository`）：
  1. 通道选择（`FEISHU` / `TELEGRAM` / `NONE`，单选 chips）。
  2. 飞书卡片：App ID / App Secret（复用 `SettingsTextInputRow`，Secret 走密码态）。
  3. Telegram 卡片：Bot Token / Allowed Chat ID + 安全说明。
  4. 当前连接状态（已连接 / 未连接 / 未配置）。
- **入口**：在设置主页分类网格（`SettingsCategoryGrid`）新增**一级卡片**「通信通道」，与 账号 / AI 助手 / 相册 等并列（图标建议 `Icons.Rounded.Forum`）。点击跳转 `CommunicationChannelScreen`。移除「设置 → AI 助手」里现有的内联「通信通道」section；同步从 `SettingsScreen` 参数表移除 `feishuAppId/Secret` 及其回调（不再内联渲染），并新增 `onNavigateToCommunicationChannel` 回调透传到网格卡片。
- **导航**：仿 `DataPrivacy`：新增 `Screen.CommunicationChannel`（`communication_channel` 路由）+ `MainActivity` 一个 `composable(...)` + `onNavigateToCommunicationChannel` 接线；复用 `SETTINGS` 场景。

## 9. i18n（强制，三语同步）

新增字符串（EN / zh-rCN / zh-rTW）：`channel_selection`、`channel_none`、`channel_feishu`、`channel_telegram`、`telegram_channel_desc`、`telegram_bot_token`、`telegram_bot_token_desc`、`telegram_chat_id`、`telegram_chat_id_desc`、`telegram_security_note`、`channel_status_connected`、`channel_status_disconnected`、`channel_status_not_configured`。`communication_channel` / `feishu_channel_desc` 已存在，复用。

**改写既有字符串**：`communication_channel_desc` 现为「配置飞书 IM 集成…」（飞书专用），用作主页网格卡片描述需**泛化为多通道**（如「配置飞书 / Telegram 远程控制通道」），三语同步更新。

**顺手修既有 i18n 违规**：现有「App ID」「App Secret」标题与「飞书应用的 App ID」占位符为硬编码中文，违反 i18n 红线。新页面抽成字符串资源（`feishu_app_id`、`feishu_app_secret` + 各自 placeholder）。

## 10. 文档同步（三层同提交）

- `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`：
  - 顶部状态从「已冻结 / 历史参考」→「重新激活（多通道）」，记录 2026-07-26 解冻决策。
  - 架构图与组件说明补 `TelegramChannelHandler` / `RemoteChannelManager` / `RemoteChannel` 接口。
  - 新增「release 凭据隔离」「Telegram 长轮询 + chatId 白名单」「单通道选择」小节。
- 若 `CAPABILITY_REGISTRY.md` / `AGENT_ARCHITECTURE.md` 提及通道或 `processFeishuInput`，同步改名与新接口。
- `runtime-core/AGENTS.md`：注明 `processFeishuInput → processRemoteImInput` 公共 API 变更。

## 11. 测试（JVM 单测，项目真门槛）

- `RemoteChannelManager.activate(...)` 状态机：选 FEISHU(有凭据) → 飞书启、Telegram 停；选 TELEGRAM → 反之；选 NONE → 全停；凭据缺失 → 对应通道不启；选中态下凭据变化 → 重连。
- `TelegramChannelHandler` chatId 白名单：抽纯函数 `shouldAcceptMessage(chatId, allowed): Boolean`，测 allowed 匹配放行、不匹配忽略、allowed 为空拒绝全部。
- 调度器 `replyToken` 透传：发送调用落到激活通道 `sendMessage` 且 token 一致。
- 会话 ID 取 `channel.channelId`（飞书 `"feishu"` 兼容历史记录）。
- release 凭据隔离为**构建期**验证（release 产物的 `BuildConfig.FEISHU_APP_ID == ""`），非 JVM 单测；在 plan 中列为手动/CI 校验项。

## 12. 文件改动清单

**新增**
- `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannel.kt`（接口）
- `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteChannelManager.kt`
- `app/src/main/java/com/mamba/picme/domain/agent/remote/TelegramChannelHandler.kt`
- `app/src/main/java/com/mamba/picme/domain/model/RemoteChannelType.kt`
- `app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelScreen.kt`
- `app/src/main/java/com/mamba/picme/features/settings/CommunicationChannelViewModel.kt`
- 对应 JVM 单测

**修改**
- `app/build.gradle.kts`（buildConfigField 按构建类型拆分）
- `gradle/libs.versions.toml`（Pengrad 依赖）
- `app/src/main/java/com/mamba/picme/domain/agent/remote/FeishuChannelHandler.kt`（实现接口）
- `app/src/main/java/com/mamba/picme/domain/agent/remote/RemoteCommandDispatcher.kt`（去飞书化）
- `app/src/main/java/com/mamba/picme/domain/agent/remote/FeishuPhotoTracker.kt` → `RemotePhotoTracker.kt`（改名+泛化）
- `app/src/main/java/com/mamba/picme/PoLangApplication.kt`（manager 接线）
- `app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`（新键 + 方法）
- `app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`（新契约）
- `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`（移除内联 section、主页网格加一级「通信通道」卡片 + `onNavigateToCommunicationChannel` 透传）
- `app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt`（移除飞书 state）
- `app/src/main/java/com/mamba/picme/navigation/Screen.kt` + `MainActivity.kt`（新路由）
- `app/src/main/res/values{,-zh-rCN,-zh-rTW}/strings.xml`（新字符串 + 修硬编码）
- `runtime-core`：`AgentOrchestrator.processFeishuInput` → `processRemoteImInput`（+ 调用方）
- 文档（见 §10）

## 13. 范围外

多通道同时在线、Telegram 群组/频道/富媒体、Webhook、服务端中转方案。
