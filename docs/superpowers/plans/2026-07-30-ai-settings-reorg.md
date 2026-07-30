# AI 设置二级页重组 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将「AI 助手」二级设置页改名为「AI 设置」，删除冗余的头部模型中心入口，并按「默认链路 / 远程链路 / 本地链路 / 语音控制」重组页面（本地与远程配置常驻可见），顺带持久化「自动执行计划」开关并修复既存 I18N 硬编码。

**Architecture:** 纯 `app` 模块 UI/设置层改动。本地/远程两区由「互斥 `when(aiAgentMode)`」改为无条件常驻渲染；顶部「默认链路」单选保留，仅决定 chat 路由。`autoExecutePlans` 仿 `aiAgentL1CacheEnabled` 接入 DataStore→Repository→ViewModel→SettingsScreen（仅持久化，不在 chat 层消费——已知限制，见 spec 3.4）。

**Tech Stack:** Kotlin、Jetpack Compose、DataStore Preferences、MVVM（SettingsViewModel）。

**Spec:** `docs/superpowers/specs/2026-07-30-ai-settings-reorg-design.md`

**测试策略说明：** 本仓库 JVM 单测对 DataStore/Compose UI 改动不可靠（Robolectric SDK36 预存失败，见项目记忆），UI 设置页改动以「编译通过 + 设备手动验证」为真实质量门。每个 Task 以 `./gradlew :app:compileDebugKotlin` 作为编译验证；最终 Task 做全量构建 + 设备验证清单。

---

## File Structure

| 文件 | 责任 | 改动类型 |
|---|---|---|
| `app/src/main/res/values/strings.xml` | 英文/默认字符串 | 改 2 + 新增 6 |
| `app/src/main/res/values-zh/strings.xml` | 中文 | 改 2 + 新增 6 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 简体中文 | 改 2 + 新增 6 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 繁体中文 | 改 2 + 新增 6 |
| `app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt` | DataStore 实现 | 新增 key + flow + updater |
| `app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt` | domain 接口 | 新增 2 个声明 |
| `app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt` | 设置 VM | 新增 StateFlow + setter |
| `app/src/main/java/com/mamba/picme/features/settings/SettingsAiAgent.kt` | AI 设置子组件 | 4 处硬编码 → stringResource |
| `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt` | 设置页主体 | 删冗余行 + 区块重组 + 接线 autoExecutePlans |

---

## Task 1: 字符串资源（改名 + 新增 I18N key）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（`ai_assistant` @ ~689, `ai_assistant_desc` @ ~690）
- Modify: `app/src/main/res/values-zh/strings.xml`（@ ~591-592）
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`（@ ~683-684）
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`（@ ~661-662）

- [ ] **Step 1: 改名 `ai_assistant` / `ai_assistant_desc`（4 套）**

`values/strings.xml`：
```xml
    <string name="ai_assistant">AI Settings</string>
    <string name="ai_assistant_desc">On-device &amp; remote inference, voice control</string>
```
`values-zh/strings.xml` 与 `values-zh-rCN/strings.xml`：
```xml
    <string name="ai_assistant">AI 设置</string>
    <string name="ai_assistant_desc">本地与远程推理链路、语音控制</string>
```
`values-zh-rTW/strings.xml`：
```xml
    <string name="ai_assistant">AI 設定</string>
    <string name="ai_assistant_desc">本地與遠端推理鏈路、語音控制</string>
```

- [ ] **Step 2: 新增 6 个字符串 key（4 套，每套均需添加）**

区块标题 + Task 3 要用的 4 个提取 key。在每套 `strings.xml` 的 `ai_assistant_desc` 附近新增：

`values/strings.xml`：
```xml
    <string name="ai_settings_remote_section">Remote inference</string>
    <string name="ai_settings_local_section">On-device inference</string>
    <string name="ai_agent_current_model">In use</string>
    <string name="remote_model_default_limit_title">Default remote model has usage limits</string>
    <string name="remote_model_default_limit_desc">Add your own model to remove limits</string>
    <string name="provider_custom">Custom</string>
```
`values-zh/strings.xml` 与 `values-zh-rCN/strings.xml`：
```xml
    <string name="ai_settings_remote_section">远程推理</string>
    <string name="ai_settings_local_section">本地推理</string>
    <string name="ai_agent_current_model">当前使用</string>
    <string name="remote_model_default_limit_title">默认远程模型有时长限制</string>
    <string name="remote_model_default_limit_desc">添加自有模型以解除限制</string>
    <string name="provider_custom">自定义</string>
```
`values-zh-rTW/strings.xml`：
```xml
    <string name="ai_settings_remote_section">遠端推理</string>
    <string name="ai_settings_local_section">本地推理</string>
    <string name="ai_agent_current_model">目前使用</string>
    <string name="remote_model_default_limit_title">預設遠端模型有時長限制</string>
    <string name="remote_model_default_limit_desc">新增自有模型以解除限制</string>
    <string name="provider_custom">自訂</string>
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（字符串无 Kotlin 引用问题）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values*/strings.xml
git commit -m "feat(settings): rename AI assistant to AI settings, add i18n keys"
```

---

## Task 2: autoExecutePlans 持久化（数据层 + VM）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt:124-125`
- Modify: `app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt:101, 799-815`
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt:187-192, 998-1003`

- [ ] **Step 1: domain 接口新增声明**

`UserSettingsRepository.kt`，在 `aiAgentL1CacheEnabledFlow` 声明（约 124-125 行）下方添加：
```kotlin
    val autoExecutePlansEnabledFlow: Flow<Boolean>
    suspend fun updateAutoExecutePlansEnabled(enabled: Boolean)
```

- [ ] **Step 2: PreferencesKeys 新增 key**

`UserPreferencesRepository.kt:101`，在 `AI_AGENT_L1_CACHE_ENABLED` 下方添加：
```kotlin
        val AUTO_EXECUTE_PLANS = booleanPreferencesKey("auto_execute_plans")
```

- [ ] **Step 3: Repository 实现 flow + updater**

`UserPreferencesRepository.kt`，在 `updateAiAgentL1CacheEnabled`（约 811-815 行）之后添加：
```kotlin
    override val autoExecutePlansEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_EXECUTE_PLANS] ?: true
        }

    override suspend fun updateAutoExecutePlansEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_EXECUTE_PLANS] = enabled
        }
    }
```

- [ ] **Step 4: SettingsViewModel 新增 StateFlow**

`SettingsViewModel.kt:192`（`aiAgentL1CacheEnabled` 的 stateIn 闭合括号之后）添加：
```kotlin
    val autoExecutePlansEnabled: StateFlow<Boolean> = repository.autoExecutePlansEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )
```

- [ ] **Step 5: SettingsViewModel 新增 setter**

`SettingsViewModel.kt`，在 `setAiAgentL1CacheEnabled`（约 998-1003 行）之后添加：
```kotlin
    fun setAutoExecutePlansEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "Auto execute plans changed: $enabled")
            repository.updateAutoExecutePlansEnabled(enabled)
        }
    }
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt \
        app/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt \
        app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt
git commit -m "feat(settings): persist auto_execute_plans via DataStore"
```

---

## Task 3: SettingsAiAgent.kt 硬编码中文提取

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsAiAgent.kt:219, 271, 277, 340`

- [ ] **Step 1: 替换 4 处硬编码为 stringResource**

`SettingsAiAgent.kt:219`（`AiAgentRemoteModelsSection` 当前使用卡标题）：
```kotlin
                        Text(
                            text = stringResource(R.string.ai_agent_current_model),
                            style = MaterialTheme.typography.bodySmall,
```

`SettingsAiAgent.kt:271`（空配置卡标题）：
```kotlin
                    Text(
                        text = stringResource(R.string.remote_model_default_limit_title),
                        style = MaterialTheme.typography.bodyMedium,
```

`SettingsAiAgent.kt:277`（空配置卡副标题）：
```kotlin
                    Text(
                        text = stringResource(R.string.remote_model_default_limit_desc),
                        style = MaterialTheme.typography.bodySmall,
```

`SettingsAiAgent.kt:340`（provider 兜底名）：
```kotlin
    val providerName = provider?.displayName ?: stringResource(R.string.provider_custom)
```

> 注：第 4 处 `stringResource` 在 `@Composable` 函数 `RemoteModelConfigCard` 顶层，可直接调用（确认该函数标注了 `@Composable`）。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/SettingsAiAgent.kt
git commit -m "refactor(settings): extract hardcoded strings in ai agent section"
```

---

## Task 4: SettingsScreen 重组（删冗余 + 区块重排 + 接线）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`

- [ ] **Step 1: 顶层 collect autoExecutePlans**

`SettingsScreen` 函数体内，在 `val aiAgentL1CacheEnabled by …`（约 168 行）附近添加：
```kotlin
    val autoExecutePlans by viewModel.autoExecutePlansEnabled.collectAsState()
```

- [ ] **Step 2: SettingsContent 调用传入新参数**

`SettingsScreen` 内 `SettingsContent(...)` 调用（约 271-282 行 aiAgent 参数块附近）添加两个参数：
```kotlin
            aiAgentL1CacheEnabled = aiAgentL1CacheEnabled,
            onAiAgentL1CacheEnabledChange = { viewModel.setAiAgentL1CacheEnabled(it) },
            autoExecutePlans = autoExecutePlans,
            onAutoExecutePlansChange = { viewModel.setAutoExecutePlansEnabled(it) },
```

- [ ] **Step 3: SettingsContent 签名新增参数**

`SettingsContent` 函数签名（约 346-347 行 `aiAgentL1CacheEnabled` / `onAiAgentL1CacheEnabledChange` 之后）添加：
```kotlin
    aiAgentL1CacheEnabled: Boolean,
    onAiAgentL1CacheEnabledChange: (Boolean) -> Unit,
    autoExecutePlans: Boolean,
    onAutoExecutePlansChange: (Boolean) -> Unit,
```

- [ ] **Step 4: 整体替换 AI_AGENT 区块（删头部模型中心行 + 两区常驻 + 重排顺序）**

将 `SettingsScreen.kt` 中 `// ── 2. AI 助手 ───` 起到该 `if (category == SettingsCategory.AI_AGENT) { … }` 闭合的整段（当前约 456-567 行）替换为：

```kotlin
            // ── 2. AI 设置 ────────────────────────────────────────
            if (category == SettingsCategory.AI_AGENT) {
                // 2.1 默认链路：单选决定 chat 实际路由 + 全局行为开关
                SettingsSection(
                    title = stringResource(R.string.ai_agent),
                    description = stringResource(R.string.ai_agent_desc)
                ) {
                    DebugOptionRow(
                        title = stringResource(R.string.ai_agent_auto_execute_plans),
                        checked = autoExecutePlans,
                        onCheckedChange = onAutoExecutePlansChange
                    )
                    Text(
                        text = stringResource(R.string.ai_agent_auto_execute_plans_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )

                    AiAgentModeSelection(
                        currentMode = aiAgentMode,
                        onModeSelected = onAiAgentModeChange
                    )
                }

                // 2.2 远程链路（常驻可见）
                SettingsSection(
                    title = stringResource(R.string.ai_settings_remote_section)
                ) {
                    AiAgentRemoteModelsSection(
                        configsJson = aiAgentRemoteModelConfigs,
                        onConfigsChange = onAiAgentRemoteModelConfigsChange,
                        selectedModelId = aiAgentSelectedRemoteModel,
                        onSelectedModelChange = onAiAgentSelectedRemoteModelChange
                    )
                }

                // 2.3 本地链路（常驻可见）
                SettingsSection(
                    title = stringResource(R.string.ai_settings_local_section)
                ) {
                    AiAgentLocalModelSection(
                        currentLocalModel = aiAgentLocalModel,
                        onLocalModelSelected = onAiAgentLocalModelChange,
                        onNavigateToModelManager = onNavigateToModelCenter
                    )

                    InferencePreferenceSelection(
                        currentPreference = aiAgentInferencePreference,
                        onPreferenceSelected = onAiAgentInferencePreferenceChange
                    )

                    OpenClBackendSelection(
                        useOpencl = aiAgentLocalUseOpencl,
                        onToggle = onAiAgentLocalUseOpenclChange
                    )

                    DebugOptionRow(
                        title = stringResource(R.string.ai_agent_l1_cache),
                        checked = aiAgentL1CacheEnabled,
                        onCheckedChange = onAiAgentL1CacheEnabledChange
                    )
                    Text(
                        text = stringResource(R.string.ai_agent_l1_cache_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }

                // 2.4 语音控制（独立第三区）
                SettingsSection(
                    title = stringResource(R.string.voice_control),
                    description = stringResource(R.string.voice_control_desc)
                ) {
                    VoiceCommandModeSelection(
                        currentMode = voiceCommandMode,
                        onModeSelected = onVoiceCommandModeChange
                    )

                    if (voiceCommandMode != VoiceCommandMode.DISABLED) {
                        LocalAsrModelSelection(
                            currentModel = localAsrModel,
                            onModelSelected = onLocalAsrModelChange,
                            onNavigateToModelCenter = onNavigateToModelCenter
                        )

                        LocalKwsModelSelection(
                            currentModel = localKwsModel,
                            onModelSelected = onLocalKwsModelChange,
                            onNavigateToModelCenter = onNavigateToModelCenter
                        )
                    }
                }
            }
```

相对原代码的关键变化：
- 删除头部 `SettingsClickableRow(模型中心)` + 其后 `HorizontalDivider`。
- 删除 `when (aiAgentMode) { OFF/LOCAL/REMOTE/FEISHU -> … }` 互斥分支与 OFF 提示文字。
- 删除 `if (aiAgentMode == AiAgentMode.LOCAL)` 条件包裹——推理偏好/OpenCL 现无条件显示。
- 远程链路置于本地链路之上；两区均无条件渲染。
- 自动执行计划开关从本地 `remember` 改为读 `autoExecutePlans` / `onAutoExecutePlansChange`（接 VM）。

- [ ] **Step 5: Preview 函数补参数**

`SettingsScreenPreview`（约 1360 行起）的 `SettingsContent(...)` 调用，在 `aiAgentL1CacheEnabled` / `onAiAgentL1CacheEnabledChange` 附近补：
```kotlin
            aiAgentL1CacheEnabled = true,
            onAiAgentL1CacheEnabledChange = {},
            autoExecutePlans = true,
            onAutoExecutePlansChange = {},
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt
git commit -m "feat(settings): reorg AI settings page by local/remote link, drop redundant model center entry"
```

---

## Task 5: 全量构建 + 设备验证

**Files:** 无（验证 only）

- [ ] **Step 1: 全量 debug 构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，生成 `app/build/outputs/apk/debug/polang-debug.apk`

- [ ] **Step 2: 安装并按清单手动验证**

Run: `adb install -r app/build/outputs/apk/debug/polang-debug.apk`

验证清单：
1. 设置主页 → 分类卡片标题为「AI 设置」（非「AI 助手」）。
2. 进入 AI 设置 → 顶栏标题「AI 设置」。
3. 页面**无**头部独立的「模型中心」行。
4. 区块自上而下顺序：AI Agent（默认链路）→ 远程推理 → 本地推理 → 语音控制。
5. 反复切换「默认链路」[远程]↔[本地]，**远程推理区与本地推理区始终同时可见**（不再互斥隐藏）。
6. 「自动执行计划」开关：打开/关闭 → 返回设置主页 → 再次进入 AI 设置，开关状态保留（验证持久化）。
7. 远程推理区：可添加 / 选择 / 删除远程模型，选中态「当前使用」文案正确。
8. 本地推理区：本地模型选择、推理偏好、推理后端、L1 缓存均可操作；「管理 ⤓」入口可跳模型中心。
9. 语音控制区：模式切换、ASR/KWS 模型选择正常。
10. 切换系统语言（简中 / 英文 / 繁中）：新文案与 4 个新提取字符串显示正确，**无中文硬编码残留**。

- [ ] **Step 3: 最终 commit（如有验证中发现的微调）**

```bash
git add -A
git commit -m "fix(settings): verification tweaks"
```
（如无微调可跳过。）
