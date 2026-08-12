# 设置页开发者选项收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将设置页里散落的开发/测试性选项收口到「开发者选项」，并对普通用户隐藏该入口（版本号连点 7 次解锁）；Android 先行，iOS 经 `/ios-follow` 跟进。

**Architecture:** 纯 `androidApp` 改动。新增一个 DataStore 布尔偏好 `developer_options_unlocked` + ViewModel StateFlow 控制入口可见性；新增一个可单测的纯逻辑 `DeveloperOptionsUnlockCounter` 处理连点计数/超时归零/解锁判定；在 `SettingsScreen` 的分类渲染块之间搬迁 UI（状态与回调签名不变，只改渲染位置）。不触碰 `:shared/commonMain`（ADR-013 §2.1 纯度）。

**Tech Stack:** Kotlin、Jetpack Compose、DataStore Preferences、JUnit4。设计规范 SSOT：`docs/superpowers/specs/2026-08-12-settings-developer-options-consolidation-design.md`。

**分支：** `feat/settings-developer-options-consolidation`（已创建）。

---

## 文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `androidApp/src/main/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounter.kt` | 连点解锁纯逻辑（计数/超时/判定） | 新建 |
| `androidApp/src/test/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounterTest.kt` | 上述逻辑单测 | 新建 |
| `androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt` | 设置仓库接口 | 改：+2 方法 |
| `androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt` | DataStore 实现 | 改：+key/flow/update |
| `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt` | 设置 VM | 改：+StateFlow/setter |
| `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt` | 设置 UI | 改：版本页脚+解锁、网格门控、分类间搬迁 |
| `androidApp/src/main/res/values/strings.xml` | EN 默认 | 改：+3 串 |
| `androidApp/src/main/res/values-zh-rCN/strings.xml` | 简中 | 改：+3 串 |
| `androidApp/src/main/res/values-zh-rTW/strings.xml` | 繁中 | 改：+3 串 |

---

## Task 1: 连点解锁计数器（TDD 纯逻辑）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounter.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounterTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `androidApp/src/test/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounterTest.kt`：

```kotlin
package com.mamba.picme.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperOptionsUnlockCounterTest {

    @Test
    fun firstTap_returnsCountdownWithRequiredMinusOne() {
        val counter = DeveloperOptionsUnlockCounter(now = { 0L })
        val result = counter.tap()
        assertEquals(
            UnlockTapResult.Countdown(DeveloperOptionsUnlockCounter.REQUIRED_TAPS - 1),
            result
        )
    }

    @Test
    fun tapsBelowThreshold_returnDescendingCountdown() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(now = { time })
        assertEquals(UnlockTapResult.Countdown(6), counter.tap())
        time = 100
        assertEquals(UnlockTapResult.Countdown(5), counter.tap())
        time = 200
        assertEquals(UnlockTapResult.Countdown(4), counter.tap())
    }

    @Test
    fun tapReachingThreshold_returnsUnlocked() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(requiredTaps = 3, now = { time })
        time = 0
        counter.tap()
        time = 100
        counter.tap()
        time = 200
        val result = counter.tap()
        assertTrue(result is UnlockTapResult.Unlocked)
    }

    @Test
    fun resetTimeoutGap_resetsTheCount() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(
            requiredTaps = 5,
            resetTimeoutMs = 4_000L,
            now = { time }
        )
        time = 0
        counter.tap() // count=1
        time = 1_000
        counter.tap() // count=2
        time = 6_000   // gap > 4000 → reset to 1
        val result = counter.tap()
        assertEquals(UnlockTapResult.Countdown(4), result)
    }

    @Test
    fun resetTimeoutBoundary_withinWindowKeepsCount() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(
            requiredTaps = 5,
            resetTimeoutMs = 4_000L,
            now = { time }
        )
        time = 0
        counter.tap() // count=1
        time = 4_000   // boundary, not exceeding (>), keep count
        val result = counter.tap()
        assertEquals(UnlockTapResult.Countdown(3), result)
    }

    @Test
    fun afterUnlock_counterResetsSoNextTapStartsFresh() {
        var time = 0L
        val counter = DeveloperOptionsUnlockCounter(requiredTaps = 2, now = { time })
        time = 0
        counter.tap()
        time = 100
        assertTrue(counter.tap() is UnlockTapResult.Unlocked)
        time = 200
        assertEquals(UnlockTapResult.Countdown(1), counter.tap())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.settings.DeveloperOptionsUnlockCounterTest"`
Expected: 编译失败（`DeveloperOptionsUnlockCounter` 未定义）。

- [ ] **Step 3: 写最小实现**

创建 `androidApp/src/main/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounter.kt`：

```kotlin
package com.mamba.picme.features.settings

/**
 * 开发者选项「版本号连点解锁」计数器（纯逻辑，可单测）。
 *
 * 每次点击调用 [tap]：若距上次点击超过 [resetTimeoutMs] 则先归零；
 * 累计到 [requiredTaps] 次返回 [UnlockTapResult.Unlocked] 并归零；
 * 否则返回 [UnlockTapResult.Countdown] 告知剩余次数。
 */
class DeveloperOptionsUnlockCounter(
    private val requiredTaps: Int = REQUIRED_TAPS,
    private val resetTimeoutMs: Long = RESET_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var count = 0
    private var lastTapMs = 0L

    fun tap(): UnlockTapResult {
        val nowMs = now()
        if (count > 0 && nowMs - lastTapMs > resetTimeoutMs) {
            count = 0
        }
        count++
        lastTapMs = nowMs
        return if (count >= requiredTaps) {
            count = 0
            UnlockTapResult.Unlocked
        } else {
            UnlockTapResult.Countdown(remaining = requiredTaps - count)
        }
    }

    fun reset() {
        count = 0
    }

    companion object {
        const val REQUIRED_TAPS = 7
        const val RESET_TIMEOUT_MS: Long = 4_000L
    }
}

sealed interface UnlockTapResult {
    /** 还差 [remaining] 次点击解锁。 */
    data class Countdown(val remaining: Int) : UnlockTapResult
    /** 已达到阈值，解锁。 */
    data object Unlocked : UnlockTapResult
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.settings.DeveloperOptionsUnlockCounterTest"`
Expected: 6 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounter.kt \
        androidApp/src/test/java/com/mamba/picme/features/settings/DeveloperOptionsUnlockCounterTest.kt
git commit -m "feat(settings): 开发者选项连点解锁计数器(纯逻辑+单测)"
```

---

## Task 2: DataStore 偏好 developer_options_unlocked

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt`

- [ ] **Step 1: 接口加方法**

在 `UserSettingsRepository.kt` 中，紧挨 `debugUiEnabledFlow` / `updateDebugUiEnabled` 声明处（约第 45-46 行），新增：

```kotlin
    val developerOptionsUnlockedFlow: Flow<Boolean>
    suspend fun updateDeveloperOptionsUnlocked(unlocked: Boolean)
```

（`Flow` 已是该文件 import；若未 import 需补 `import kotlinx.coroutines.flow.Flow`——该文件大概率已有，照搬 `debugUiEnabledFlow` 的 import 即可。）

- [ ] **Step 2: 实现加 PreferencesKey**

在 `UserPreferencesRepository.kt` 的 `PreferencesKeys` 里，紧挨 `DEBUG_UI_ENABLED`（第 60 行）新增：

```kotlin
        val DEVELOPER_OPTIONS_UNLOCKED = booleanPreferencesKey("developer_options_unlocked")
```

- [ ] **Step 3: 实现 flow + update**

在 `UserPreferencesRepository.kt` 中，紧挨 `updateDebugUiEnabled`（约第 317-321 行）之后，新增（完全照搬 `debugUiEnabledFlow`/`updateDebugUiEnabled` 写法，仅换 key 名）：

```kotlin
    override val developerOptionsUnlockedFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.DEVELOPER_OPTIONS_UNLOCKED] ?: false
        }

    override suspend fun updateDeveloperOptionsUnlocked(unlocked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEVELOPER_OPTIONS_UNLOCKED] = unlocked
        }
    }
```

- [ ] **Step 4: 编译确认**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt \
        androidApp/src/main/java/com/mamba/picme/data/preferences/UserPreferencesRepository.kt
git commit -m "feat(settings): developer_options_unlocked DataStore 偏好"
```

---

## Task 3: ViewModel 状态 + setter

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt`

- [ ] **Step 1: 加 StateFlow + setter**

在 `SettingsViewModel.kt` 中，紧挨 `debugUiEnabled`（约第 89-94 行）之后新增 StateFlow：

```kotlin
    val developerOptionsUnlocked: StateFlow<Boolean> = repository.developerOptionsUnlockedFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
```

紧挨 `setDebugUiEnabled`（约第 774-784 行）之后新增 setter：

```kotlin
    fun setDeveloperOptionsUnlocked(unlocked: Boolean) {
        viewModelScope.launch {
            repository.updateDeveloperOptionsUnlocked(unlocked)
        }
    }
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt
git commit -m "feat(settings): SettingsViewModel 暴露 developerOptionsUnlocked"
```

---

## Task 4: i18n 字符串（三语同步）

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml`
- Modify: `androidApp/src/main/res/values-zh-rCN/strings.xml`
- Modify: `androidApp/src/main/res/values-zh-rTW/strings.xml`

新增 3 个字符串：`app_version_footer`（版本页脚）、`dev_options_unlock_countdown`（倒计数 Toast）、`dev_options_unlocked_toast`（解锁 Toast）。

- [ ] **Step 1: values/strings.xml（EN 默认）**

在 `developer_options` 相关串附近（约第 748 行 `developer_options` 之后）新增：

```xml
    <string name="app_version_footer">%1$s v%2$s</string>
    <string name="dev_options_unlock_countdown">Tap %1$d more times to enable developer options.</string>
    <string name="dev_options_unlocked_toast">Developer options enabled.</string>
```

- [ ] **Step 2: values-zh-rCN/strings.xml（简中）**

对应位置新增：

```xml
    <string name="app_version_footer">%1$s v%2$s</string>
    <string name="dev_options_unlock_countdown">再点击 %1$d 次进入开发者选项。</string>
    <string name="dev_options_unlocked_toast">已开启开发者选项。</string>
```

- [ ] **Step 3: values-zh-rTW/strings.xml（繁中）**

对应位置新增（繁体用词）：

```xml
    <string name="app_version_footer">%1$s v%2$s</string>
    <string name="dev_options_unlock_countdown">再點擊 %1$d 次進入開發者選項。</string>
    <string name="dev_options_unlocked_toast">已開啟開發者選項。</string>
```

- [ ] **Step 4: 提交**

```bash
git add androidApp/src/main/res/values/strings.xml \
        androidApp/src/main/res/values-zh-rCN/strings.xml \
        androidApp/src/main/res/values-zh-rTW/strings.xml
git commit -m "i18n(settings): 开发者选项解锁相关三语文案"
```

---

## Task 5: 版本页脚 + 解锁 UX + 网格门控

> 本任务给「设置主页」加版本页脚（连点解锁），并把「开发者选项」网格卡片改为仅解锁后可见。涉及 `SettingsScreen.kt` 的 `SettingsScreen` / `SettingsContent` / `SettingsMainMenu` / `SettingsCategoryGrid` 四个函数的参数穿透。

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`

- [ ] **Step 1: SettingsScreen 顶层收集状态并下传**

在 `SettingsScreen`（约第 128-156 行 collectAsState 区块）新增一行收集：

```kotlin
    val developerOptionsUnlocked by viewModel.developerOptionsUnlocked.collectAsState()
```

在 `SettingsContent(...)` 调用（约第 219 行起）的参数列表里新增两个（放在 `onNavigateBack` 附近）：

```kotlin
            developerOptionsUnlocked = developerOptionsUnlocked,
            onUnlockDeveloperOptions = { viewModel.setDeveloperOptionsUnlocked(true) },
```

- [ ] **Step 2: SettingsContent 形参加这两个，并下传到 SettingsMainMenu**

在 `SettingsContent` 参数列表（约第 352 行 `onNavigateBack` 附近）新增：

```kotlin
    developerOptionsUnlocked: Boolean,
    onUnlockDeveloperOptions: () -> Unit,
```

在 `SettingsContent` 内 `SettingsMainMenu(...)` 调用（约第 392-403 行）新增传参：

```kotlin
                    developerOptionsUnlocked = developerOptionsUnlocked,
                    onUnlockDeveloperOptions = onUnlockDeveloperOptions,
```

- [ ] **Step 3: SettingsMainMenu 形参 + 页脚 + 网格门控**

`SettingsMainMenu` 签名（约第 836 行）新增两个形参：

```kotlin
    developerOptionsUnlocked: Boolean,
    onUnlockDeveloperOptions: () -> Unit,
```

在 `SettingsMainMenu` 的 `Column` 内、`SettingsCategoryGrid(...)` 调用之前，先改 `SettingsCategoryGrid` 调用（约第 906 行）传入新参：

```kotlin
        SettingsCategoryGrid(
            onNavigateToCategory = onNavigateToCategory,
            onNavigateToModelCenter = onNavigateToModelCenter,
            onNavigateToDataPrivacy = onNavigateToDataPrivacy,
            onNavigateToCommunicationChannel = onNavigateToCommunicationChannel,
            onNavigateToMemoryFacts = onNavigateToMemoryFacts,
            onNavigateToPeople = onNavigateToPeople,
            developerOptionsUnlocked = developerOptionsUnlocked
        )
```

在 `SettingsMainMenu` 的 `Column` 末尾（`SettingsCategoryGrid(...)` 之后）追加版本页脚：

```kotlin
        SettingsVersionFooter(onUnlock = onUnlockDeveloperOptions)
```

- [ ] **Step 4: SettingsCategoryGrid 门控开发者入口**

`SettingsCategoryGrid` 签名（约第 1017 行）新增形参：

```kotlin
    developerOptionsUnlocked: Boolean,
```

把「开发者选项」那个 `CategoryGridItem`（约第 1042-1044 行）从 `items = listOf(...)` 里**抽出**，改为条件加入。即将原 `val items = listOf(...)` 改为：

```kotlin
    val baseItems = listOf(
        CategoryGridItem(R.string.ai_assistant, R.string.ai_assistant_desc, Icons.Rounded.SmartToy) {
            onNavigateToCategory(SettingsCategory.AI_AGENT)
        },
        CategoryGridItem(R.string.settings_ai_memory, R.string.settings_ai_memory_desc, Icons.Rounded.Psychology, onNavigateToMemoryFacts),
        CategoryGridItem(R.string.people_entry, R.string.people_entry_desc, Icons.Rounded.AccountCircle, onNavigateToPeople),
        CategoryGridItem(R.string.communication_channel, R.string.communication_channel_desc, Icons.Rounded.Forum) {
            onNavigateToCommunicationChannel()
        },
        CategoryGridItem(R.string.gallery_features, R.string.gallery_features_desc, Icons.Rounded.PhotoLibrary) {
            onNavigateToCategory(SettingsCategory.GALLERY)
        },
        CategoryGridItem(R.string.camera_and_beauty, R.string.camera_and_beauty_desc, Icons.Rounded.CameraAlt) {
            onNavigateToCategory(SettingsCategory.CAMERA_BEAUTY)
        },
        CategoryGridItem(R.string.model_center, R.string.model_center_desc, Icons.Rounded.CloudDownload, onNavigateToModelCenter),
        CategoryGridItem(R.string.backup_and_restore, R.string.backup_and_restore_desc, Icons.Rounded.Storage) {
            context.startActivity(BackupRestoreActivity.intent(context))
        },
        CategoryGridItem(R.string.data_privacy_entry, R.string.data_privacy_desc, Icons.Rounded.PrivacyTip, onNavigateToDataPrivacy),
    )
    val devItem = CategoryGridItem(R.string.developer_options, R.string.developer_options_desc, Icons.Rounded.Terminal) {
        onNavigateToCategory(SettingsCategory.DEVELOPER)
    }
    val items = if (developerOptionsUnlocked) baseItems + devItem else baseItems
```

> 注意：原 `listOf` 里把 `developer_options` 放在 `model_center` 与 `backup_and_restore` 之间；现在 `baseItems` 直接去掉它，`devItem` 解锁后追加到末尾。顺序略有变化（开发者选项从中间挪到末尾），符合「解锁后才出现、置于底部」的预期。

- [ ] **Step 5: 新增 SettingsVersionFooter 组合函数**

在 `SettingsScreen.kt` 文件内（`SettingsCategoryCard` 之后、`LogModuleConfigSection` 之前任意位置）新增：

```kotlin
/**
 * 设置主页底部版本页脚：连点 [DeveloperOptionsUnlockCounter.REQUIRED_TAPS] 次解锁开发者选项。
 * 解锁前对普通用户仅显示版本号（良性信息）。
 */
@Composable
private fun SettingsVersionFooter(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val counter = remember { DeveloperOptionsUnlockCounter() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                R.string.app_version_footer,
                stringResource(R.string.app_name),
                BuildConfig.VERSION_NAME
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.clickable {
                when (val result = counter.tap()) {
                    is UnlockTapResult.Countdown -> Toast.makeText(
                        context,
                        context.getString(R.string.dev_options_unlock_countdown, result.remaining),
                        Toast.LENGTH_SHORT
                    ).show()
                    UnlockTapResult.Unlocked -> {
                        onUnlock()
                        Toast.makeText(
                            context,
                            context.getString(R.string.dev_options_unlocked_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}
```

补 import（文件顶部 import 区）：

```kotlin
import android.widget.Toast
```

（`BuildConfig`、`stringResource`、`Toast`、`Modifier.clickable`、`Alignment`、`MaterialTheme`、`Text`、`Column`、`padding`、`fillMaxWidth`、`remember` 大多已在文件内 import；只缺 `Toast` 时补它。）

- [ ] **Step 6: 更新 Preview（避免参数缺失编译错）**

`SettingsScreenPreview`（约第 1253 行）调用 `SettingsContent(...)` 时补两个参数（放在 `onNavigateBack = {}` 附近）：

```kotlin
            developerOptionsUnlocked = false,
            onUnlockDeveloperOptions = {},
```

- [ ] **Step 7: 编译确认**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt
git commit -m "feat(settings): 版本页脚连点解锁开发者选项 + 网格入口门控"
```

---

## Task 6: 把泄漏到用户分类的开发/测试项迁入 DEVELOPER

> 所有被迁移项的状态与回调签名**已存在**于 `SettingsContent` 形参表，仅把渲染块从一个 `if (category == X)` 搬到 `if (category == SettingsCategory.DEVELOPER)` 内。搬移不改任何底层 Repository/ViewModel。

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt`

- [ ] **Step 1: 从 AI_AGENT 删掉「远程模型配置」区块**

删除 `SettingsContent` 内 `category == SettingsCategory.AI_AGENT` 分支里的远程模型 Section（约第 444-454 行整段）：

```kotlin
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
```

AI_AGENT 分支保留：自动执行计划 + AiAgentModeSelection + 语音控制区。

- [ ] **Step 2: 从 CAMERA_BEAUTY 删掉「阶段配置 + 人脸检测高级」区块**

删除 `category == SettingsCategory.CAMERA_BEAUTY` 分支里的（约第 558-604 行，从第一个 `StageConfigSection(` 到 `face_detection_advanced` 的 `SettingsSection {...}` 闭合）：

```kotlin
                StageConfigSection(
                    stage = DetectionStage.ROI,
                    ...
                )

                StageConfigSection(
                    stage = DetectionStage.LANDMARK,
                    ...
                )

                SettingsSection(
                    title = stringResource(R.string.face_detection_advanced),
                    description = stringResource(R.string.settings_face_detection_advanced_desc)
                ) {
                    DebugOptionRow(... face_landmark_mode ...)
                    DebugOptionRow(... adaptive_face_detect_interval ...)
                    if (adaptiveFaceDetectionIntervalEnabled) {
                        FaceDetectProfileSelection(...)
                    }
                }
```

CAMERA_BEAUTY 分支保留：语音入口开关 Section（`voice_entry_enabled`）。

- [ ] **Step 3: 从 GALLERY 删掉「打标模型选择」行**

删除 `category == SettingsCategory.GALLERY` 分支里的 `taggerModelKey` 的 `SettingsClickableRow(...)`（约第 506-524 行整块）。GALLERY 分支保留：标签管理 / 标签查看 / 去重 / OpenCL 加速区。

- [ ] **Step 4: 在 DEVELOPER 分支新增三组迁入区块**

在 `SettingsContent` 的 `category == SettingsCategory.DEVELOPER` 分支内，在「6.1 调试浮层」Section 之后、「6.2 诊断」Section 之前，插入：

```kotlin
                // ── 人脸检测引擎（迁入）：阶段配置 + 关键点 + 自适应间隔 ──
                SettingsSection(
                    title = stringResource(R.string.face_detection_advanced),
                    description = stringResource(R.string.settings_face_detection_advanced_desc)
                ) {
                    StageConfigSection(
                        stage = DetectionStage.ROI,
                        config = roiStageConfig,
                        onModelTypeSelected = onRoiModelTypeSelected,
                        onDevicePreferenceSelected = onRoiDevicePreferenceSelected,
                        onNavigateToModelManager = onNavigateToModelCenter,
                        isModelDownloaded = isModelDownloaded,
                        getModelId = getModelId,
                        downloadModel = downloadModel,
                        downloadStates = downloadStates,
                        allModels = allModels
                    )

                    StageConfigSection(
                        stage = DetectionStage.LANDMARK,
                        config = landmarkStageConfig,
                        onModelTypeSelected = onLandmarkModelTypeSelected,
                        onDevicePreferenceSelected = onLandmarkDevicePreferenceSelected,
                        onNavigateToModelManager = onNavigateToModelCenter,
                        isModelDownloaded = isModelDownloaded,
                        getModelId = getModelId,
                        downloadModel = downloadModel,
                        downloadStates = downloadStates,
                        allModels = allModels
                    )

                    DebugOptionRow(
                        title = stringResource(R.string.face_landmark_mode),
                        checked = faceDetectionLandmarkModeEnabled,
                        onCheckedChange = onFaceDetectionLandmarkModeEnabledChange
                    )
                    DebugOptionRow(
                        title = stringResource(R.string.adaptive_face_detect_interval),
                        checked = adaptiveFaceDetectionIntervalEnabled,
                        onCheckedChange = onAdaptiveFaceDetectionIntervalEnabledChange
                    )
                    if (adaptiveFaceDetectionIntervalEnabled) {
                        FaceDetectProfileSelection(
                            currentProfile = faceDetectIntervalProfile,
                            onProfileSelected = onFaceDetectIntervalProfileSelected
                        )
                    }
                }

                // ── AI 推理链路·高级（迁入）：远程模型配置 ──
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

                // ── 相册打标·高级（迁入）：打标模型选择 ──
                SettingsSection(
                    title = stringResource(R.string.gallery_advanced)
                ) {
                    val taggerAutoLabel = stringResource(R.string.tag_model_auto)
                    SettingsClickableRow(
                        title = stringResource(R.string.tag_model_selector_title),
                        subtitle = when (taggerModelKey) {
                            "florence2_base" -> "Florence-2-Base"
                            "qwen3_vl_2b" -> "Qwen3-VL-2B"
                            else -> taggerAutoLabel
                        },
                        leadingIcon = Icons.AutoMirrored.Rounded.Label,
                        onClick = {
                            val next = when (taggerModelKey) {
                                TaggerModelSelector.AUTO -> "florence2_base"
                                "florence2_base" -> "qwen3_vl_2b"
                                else -> TaggerModelSelector.AUTO
                            }
                            onTaggerModelKeyChange(next)
                        }
                    )
                }
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。（注意：迁移后某些形参可能在新位置仍被消费；若编译器报「parameter never used」属 warning 而非 error，无需处理——`@Suppress` 注解已存在于 `SettingsContent`。）

- [ ] **Step 6: 提交**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/settings/SettingsScreen.kt
git commit -m "feat(settings): 开发/测试项收口到开发者选项(远程模型/检测引擎/打标模型)"
```

---

## Task 7: 质量门与编译验证

**Files:** 无（仅验证）

- [ ] **Step 1: ktlint + detekt**

Run: `./gradlew :androidApp:ktlintCheck :androidApp:detekt`
Expected: BUILD SUCCESSFUL。若有违例按提示修（常见：import 顺序、`it` 隐式参数）。

- [ ] **Step 2: 全量 debug 编译**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 单测全跑（确认计数器 + 无回归）**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: 全绿，含 `DeveloperOptionsUnlockCounterTest` 6 条。

- [ ] **Step 4: 纯度自检（契约 §2.1，确保未误碰 commonMain）**

Run: `git diff main --name-only | grep -E "shared/src/commonMain" || echo "OK: commonMain 未改动"`
Expected: 输出 `OK: commonMain 未改动`。

- [ ] **Step 5: 人工验收要点（写入 PR 描述）**

参照设计文档 §4 验收标准 1-9 逐条人工核验（需设备）：入口隐藏、连点 7 次解锁、持久化、迁移项出现在开发者选项、用户分类不再泄漏、三语、功能未受影响。

---

## Self-Review（writing-plans 自检）

1. **Spec 覆盖**：
   - 解锁机制（spec §3.1）→ Task 1（计数器）+ Task 5（页脚/网格门控）✓
   - 数据层（§3.2）→ Task 2 ✓
   - ViewModel（§3.3）→ Task 3 ✓
   - UI 迁移（§3.4）→ Task 6 ✓
   - DEVELOPER 重排（§3.5）→ Task 6 Step 4 ✓
   - i18n（§3.6）→ Task 4 ✓
   - 验收（§4）→ Task 7 ✓
   - 不做项（§3.7）→ 未生成任务，符合预期 ✓
2. **占位符扫描**：无 TBD/TODO；所有代码块完整。
3. **类型/命名一致性**：`developerOptionsUnlocked`（StateFlow/param）、`onUnlockDeveloperOptions`、`setDeveloperOptionsUnlocked`、`DeveloperOptionsUnlockCounter`、`UnlockTapResult`、`SettingsVersionFooter` 全程一致；DataStore key `developer_options_unlocked` 一致。
