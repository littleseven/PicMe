# 语音入口收敛至相机页 + KWS 模型可选化 — 设计文档

> **日期**：2026-08-02
> **状态**：已实现（2026-08-02，编译与单测通过）
> **背景**：语音能力在 App 中为非刚需。悬浮语音入口仅保留在相机页；相机设置新增开关（默认关闭）控制入口显隐；KWS 唤醒模型从默认下载集拆出，列为可选、按需下载。ASR 仍为聊天必需（独立 Chat 页有语音输入与按住说话）。

## 1. 现状摘要

| 项 | 现状 | 位置 |
|---|---|---|
| 相机页语音控制 FAB | `RecordVoiceOver` 图标，常显；点击切换 `VoiceCommandMode.DISABLED ↔ WAKE_WORD` | `app/src/main/java/com/mamba/picme/features/camera/CameraPreviewContent.kt:607-627`，接线 `CameraScreen.kt:790-817` |
| 相机页 AI 对话 FAB | `KeyboardVoice` 图标，常显 | `CameraPreviewContent.kt:643` 附近 |
| 相册页悬浮 AI 入口 | `KeyboardVoice` FAB，仅 `debugUiEnabled && PHOTO` 可见，触发内嵌 `AiChatScreen` | `app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt:356-374` |
| `GlobalAgentPanel` 悬浮球 | 唯一使用方 `CameraAgentPanelV2`（`CameraAgentIntegration.kt:59`）**未被任何页面调用**，属未接线代码 | `app/src/main/java/com/mamba/picme/features/agent/GlobalAgentPanel.kt` |
| 语音功能门控 | `VoiceCommandMode` 枚举（DISABLED/PUSH_TO_TALK/WAKE_WORD，默认 DISABLED），DataStore 持久化 | `app/src/main/java/com/mamba/picme/domain/model/VoiceCommandMode.kt`；`UserPreferencesRepository.kt:858-877` |
| 语音设置 UI | 设置页「AI Agent」分类下「语音控制」区块：模式选择 + ASR/KWS 模型选择 | `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt:511-534` |
| KWS 模型分组 | 在 `CHAT_MODEL_IDS` / `CHAT_REQUIRED_MODEL_IDS` 中，随聊天模型一起提醒/下载 | `LlmModelDownloadManager.kt:1387-1391`；`SettingsViewModel.kt:68-72` |
| 模型市场 tag | KWS 与 ASR 的 tags 均含 `"recommended"` | `app/src/main/res/raw/llm_models.json:17,35` |

## 2. 设计

### 2.1 新增「语音控制入口」开关（默认关闭）

- **持久化**：`UserPreferencesRepository` 新增 `VOICE_ENTRY_ENABLED = booleanPreferencesKey("voice_entry_enabled")`，新增 `voiceEntryEnabledFlow: Flow<Boolean>`（默认 `false`）与 `updateVoiceEntryEnabled(enabled: Boolean)`。模式与现有 `showCameraInfoInPreview`（`UserPreferencesRepository.kt:329-345`）完全一致。
- **ViewModel**：`SettingsViewModel` 新增 `voiceEntryEnabled: StateFlow<Boolean>` 与 `setVoiceEntryEnabled(Boolean)`。
- **UI**：设置页「相机与美颜」（`SettingsCategory.CAMERA_BEAUTY`）分类下新增 `SettingsSection`（标题：语音控制），内含一个 `DebugOptionRow` 开关（「语音控制入口」）。位置：`SettingsScreen.kt:594-643` 区块内。
- **i18n**：`values/strings.xml`（英文，默认）+ `values-zh` + `values-zh-rCN` + `values-zh-rTW` 四份资源同步新增文案（遵循 [I18N] 红线）。

### 2.2 相机页语音 FAB 受开关门控

- `CameraScreen` 收集 `voiceEntryEnabledFlow`，传入 `CameraPreviewContent`；仅当开关开启时渲染 `RecordVoiceOver` FAB（`CameraPreviewContent.kt:607` 处加条件参数，如 `showVoiceEntry: Boolean`）。
- **监听收敛**：开关关闭且 `voiceCommandMode == WAKE_WORD` 时，停止唤醒监听。`CameraScreen.kt:853` 的 `LaunchedEffect` 条件由 `isActivePage && voiceCommandMode == WAKE_WORD` 改为 `isActivePage && voiceCommandMode == WAKE_WORD && voiceEntryEnabled`，避免入口隐藏后仍在后台监听。
- 相机页 AI 对话 FAB（`KeyboardVoice`）保留不变。

### 2.3 移除非相机页悬浮入口

- 删除 `MediaPager.kt:356-374` 的 debug 悬浮 FAB，以及仅由该 FAB 触发的内嵌 `AiChatScreen` 面板代码块（`showAiChatPanel` 状态、`pagerMessages`/`pagerIsProcessing` 及 `onSendMessage` 逻辑，行 356 起至该 `if (debugUiEnabled ...)` 块结束）。删除后清理仅服务于该面板的 import 与变量；`voiceCoordinator` 等仍被其他逻辑使用的保留。
- `GlobalAgentPanel` / `CameraAgentPanelV2` 为未接线代码，本次**不改动**（最小变更原则）。

### 2.4 KWS 模型拆为可选、按需下载

- `LlmModelDownloadManager.kt`：KWS（`sherpa-onnx-kws-zipformer-wenetspeech`）从 `CHAT_MODEL_IDS` 移除（注释中说明拆出原因，不新增无消费方的常量）。`RECOMMENDED_MODEL_IDS` 相应不再包含 KWS（KWS 不再 WiFi 静默预下载、不再出现在「推荐」分类）。
- 联动：在「AI Agent → 语音控制」选择 `WAKE_WORD` 时自动开启 `voice_entry_enabled`（`SettingsViewModel.setVoiceCommandMode`），避免"已选唤醒词但入口隐藏不监听"的互斥状态。
- `SettingsViewModel.kt:68-72`：`CHAT_REQUIRED_MODEL_IDS` 移除 KWS，仅保留 LLM + ASR；同步更新注释。
- `llm_models.json`：KWS 的 tags 中 `"recommended"` 移除，保留 `"chat"` 分类 tag 使其仍在聊天分类可见，可手动下载。ASR 不变。
- **按需下载入口**：复用设置页「语音控制」区块现有的 `LocalKwsModelSelection`（`SettingsScreen.kt:528`，仅 `voiceCommandMode != DISABLED` 时显示），用户在选唤醒词模式时可在此选择/下载 KWS。本次不新增自动弹窗逻辑。

## 3. 影响面与兼容

- **存量用户**：已开启 `WAKE_WORD` 的用户升级后，`voice_entry_enabled` 默认 `false` → 相机页语音 FAB 隐藏、唤醒监听停止；需在设置中重新开启入口。属预期行为（语音非刚需，默认收敛）。
- **已下载 KWS 的用户**：模型文件保留在本地，仅不再默认提醒/预下载，无数据迁移。
- **聊天页语音输入/按住说话**：依赖 ASR，不受影响。

## 4. 验证

- `./gradlew :app:compileDebugKotlin` 编译通过。
- 手动验证：
  1. 默认（开关关）：相机页无语音 FAB；相册页（含 debug）无悬浮 AI 入口；唤醒监听不启动。
  2. 开关开：相机页出现语音 FAB，点击可启用/停用语音控制。
  3. 模型市场：KWS 不在「推荐」分类，仍在「聊天」分类可手动下载；聊天页下载提醒仅含 LLM + ASR。
- 三语文案检查。

## 5. 明确不做（YAGNI）

- 不删除 `GlobalAgentPanel` / `CameraAgentPanelV2` 未接线代码。
- 不改动 `VoiceCommandMode` 三态枚举及「AI Agent → 语音控制」设置区块结构。
- 不为 KWS 新增启用唤醒时的自动下载弹窗（复用设置页现有下载 UI）。
- 不改动聊天输入栏内的语音按钮（非悬浮入口）。
