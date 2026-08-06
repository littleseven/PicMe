# HyperOS 风格滑杆（AppSlider）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建共享组件 `AppSlider`（HyperOS 风：胶囊轨道 + 白圆点描边 thumb + 按压 1.15× 放大），并把全 app 11 处滑杆全部迁移到它，消灭 4 套散装实现。

**Architecture:** `AppSlider` 基于 M3 `Slider` 的 `thumb`/`track` slot 自定义视觉（保留 M3 手势/无障碍/步进语义），颜色只从 `MaterialTheme.colorScheme` 取（primary + onSurface 12%），不开放配色参数。各调用点保持外层封装与数值逻辑不变，只换内部滑杆实现。

**Tech Stack:** Kotlin / Jetpack Compose Material3 / Gradle (`:app`)

**Spec:** `docs/superpowers/specs/2026-08-06-hyperos-slider-design.md`

**前置准备（执行前）：** 按 `using-git-worktrees` skill 在 `.worktrees/` 建隔离 worktree + 分支 `feat/hyperos-slider`，**必须从最新 `main` 切**（Room schema 当前 v20，旧 main 构建装机覆盖会触发降级崩溃）。本 spec/plan 文档 mv 进 worktree 提交。主工作区未提交改动不碰。

**注意：** 本任务纯 UI 组件替换，无 JVM 单测点，TDD 不适用；验证手段 = 编译 + 全量单测不回归 + detekt 零新增告警 + 真机截图目测。每个 Task 结束单独 commit。

---

### Task 1: 新建 AppSlider 共享组件

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/designsystem/components/AppSlider.kt`

- [ ] **Step 1: 创建 AppSlider.kt**

完整文件内容：

```kotlin
package com.mamba.picme.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TrackHeight = 6.dp
private val ThumbSize = 18.dp
private const val THUMB_PRESSED_SCALE = 1.15f

/**
 * 全 app 统一滑杆（HyperOS 风）：胶囊轨道 + 白圆点 primary 描边 thumb + 按压放大。
 *
 * API 对齐 M3 [Slider] 的最小必要子集。不提供配色参数——颜色固定从
 * `MaterialTheme.colorScheme` 取（primary / onSurface 12%），深浅色自适应，
 * 防止各页面再次自定义导致样式碎片化。
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (isPressed) THUMB_PRESSED_SCALE else 1f,
        label = "thumbScale"
    )
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Spacer(
                modifier = Modifier
                    .size(ThumbSize)
                    .scale(thumbScale)
                    .shadow(elevation = 2.dp, shape = CircleShape)
                    .background(Color.White, CircleShape)
                    .border(2.dp, activeColor, CircleShape)
            )
        },
        track = { sliderState ->
            val fraction = sliderState.valueRange.run {
                ((value - start) / (endInclusive - start)).coerceIn(0f, 1f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(inactiveColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(activeColor)
                )
            }
        }
    )
}
```

> 说明：`track` slot 参数在 M3 新旧版本中类型不同（`SliderPositions` / `SliderState`），两者都有 `valueRange`，此处只用 `valueRange` + 外层 `value`，与项目现有写法（`CameraBaseComponents.kt`、`ProModeControls.kt`）一致，两个版本都能编译。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/designsystem/components/AppSlider.kt
git commit -m "feat(designsystem): add AppSlider shared component (HyperOS style)"
```

---

### Task 2: 迁移编辑器 3 处滑杆

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/editor/components/EditorSlider.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/components/AdjustPanel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/components/MarkupPanel.kt`

- [ ] **Step 1: EditorSlider.kt — 换内部实现**

`import androidx.compose.material3.Slider` 替换为 `import com.mamba.picme.core.designsystem.components.AppSlider`；文件末尾的：

```kotlin
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
```

改为：

```kotlin
        AppSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
```

`EditorSlider` 公共 API（label/onReset/valueFormatter/compact）不动。

- [ ] **Step 2: AdjustPanel.kt — 裸 M3 Slider → AppSlider**

`import androidx.compose.material3.Slider` 替换为 `import com.mamba.picme.core.designsystem.components.AppSlider`；第 109 行附近：

```kotlin
        Slider(
            value = current.value,
            onValueChange = current.onValueChange,
            valueRange = current.valueRange,
            modifier = Modifier.fillMaxWidth()
        )
```

改为：

```kotlin
        AppSlider(
            value = current.value,
            onValueChange = current.onValueChange,
            valueRange = current.valueRange,
            modifier = Modifier.fillMaxWidth()
        )
```

- [ ] **Step 3: MarkupPanel.kt — 裸 M3 Slider → AppSlider**

`import androidx.compose.material3.Slider` 替换为 `import com.mamba.picme.core.designsystem.components.AppSlider`；第 79 行附近：

```kotlin
        Slider(
            value = 20f,
            onValueChange = { /* stroke width */ },
            valueRange = 5f..100f,
            modifier = Modifier.fillMaxWidth()
        )
```

改为：

```kotlin
        AppSlider(
            value = 20f,
            onValueChange = { /* stroke width */ },
            valueRange = 5f..100f,
            modifier = Modifier.fillMaxWidth()
        )
```

（面板是占位 stub，只换实现，不修功能。）

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/components/
git commit -m "refactor(editor): migrate sliders to AppSlider"
```

---

### Task 3: 迁移相机 3 类滑杆（BeautySlider / ProMode / 唇色）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/camera/components/CameraBaseComponents.kt`（`BeautySlider`，302-419 行）
- Modify: `app/src/main/java/com/mamba/picme/features/camera/components/ProModeControls.kt`（4 处 Slider + `ProModeThumb`/`ProModeTrack`）
- Modify: `app/src/main/java/com/mamba/picme/features/camera/components/ColorSelectors.kt`（唇色滑杆，201-213 行）

- [ ] **Step 1: CameraBaseComponents.kt — BeautySlider 换内部实现**

`BeautySlider` 签名不变。删除函数体内 `val interactionSource = ...` 与 `val isPressed by ...` 两行（318-319），把末尾整个：

```kotlin
        Box(contentAlignment = Alignment.Center) {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = { onValueChange(it) },
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                colors = SliderDefaults.colors(...),
                thumb = { ... 1.5f 放大自绘 thumb ... },
                track = { ... 8.dp 渐变轨道 ... }
            )
        }
```

替换为：

```kotlin
        AppSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
        )
```

上半部分 label 行（icon + label + displayValue 文本）原样保留。按压放大从 1.5× 统一到 1.15×（设计已定）。清理本文件因此不再使用的 import（`SliderDefaults`、`Brush`、可能的 `animateFloatAsState` 等——以编译器 unused 警告为准，只删确实不再使用的）；若 `@OptIn(ExperimentalMaterial3Api::class)` 在该函数上不再必要则一并移除。添加 `import com.mamba.picme.core.designsystem.components.AppSlider`。

- [ ] **Step 2: ProModeControls.kt — 4 处 Slider → AppSlider**

曝光（保留 steps 计算）：

```kotlin
                        AppSlider(
                            value = exposure.toFloat(),
                            valueRange = exposureValueRange,
                            steps = if (exposureRange.last > exposureRange.first) {
                                exposureRange.last - exposureRange.first - 1
                            } else {
                                0
                            },
                            onValueChange = { newValue -> onExposureChange(newValue.toInt()) },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
```

对比度：

```kotlin
                        AppSlider(
                            value = beautySettings.contrast,
                            valueRange = 0f..200f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(contrast = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
```

饱和度：

```kotlin
                        AppSlider(
                            value = beautySettings.saturation,
                            valueRange = 0f..200f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(saturation = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
```

色温：

```kotlin
                        AppSlider(
                            value = beautySettings.temperature,
                            valueRange = 2000f..8000f,
                            onValueChange = { value ->
                                onBeautySettingsChanged(beautySettings.copy(temperature = value))
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        )
```

外层 `ProModeSlider`（label + valueText 行 + `sliderContent` slot）原样保留。

- [ ] **Step 3: ProModeControls.kt — 删除 ProModeThumb / ProModeTrack**

完整删除文件末尾两个私有函数（322-359 行）：`private fun ProModeThumb()` 和 `private fun ProModeTrack(fraction: Float)`。清理因此不再使用的 import（`Brush`、`RoundedCornerShape`、`CircleShape`、`border`、`animateFloatAsState`、`collectIsPressedAsState`、`MutableInteractionSource`、`clip` 等——只删确实不再使用的）。添加 `import com.mamba.picme.core.designsystem.components.AppSlider`。

- [ ] **Step 4: ColorSelectors.kt — 唇色滑杆 → AppSlider**

201-213 行的：

```kotlin
        Slider(
            value = strength.coerceIn(0f, 100f),
            onValueChange = { value ->
                onStrengthChanged(value.coerceIn(0f, 100f))
            },
            valueRange = 0f..100f,
            modifier = Modifier.height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
```

替换为：

```kotlin
        AppSlider(
            value = strength.coerceIn(0f, 100f),
            onValueChange = { value ->
                onStrengthChanged(value.coerceIn(0f, 100f))
            },
            valueRange = 0f..100f,
            modifier = Modifier.height(32.dp)
        )
```

清理不再使用的 import（`SliderDefaults` 等），添加 AppSlider import。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/camera/components/
git commit -m "refactor(camera): migrate beauty/pro-mode/lip sliders to AppSlider"
```

---

### Task 4: 迁移证件照 2 处滑杆

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/idphoto/components/EdgePanel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/idphoto/components/RepairPanel.kt`

- [ ] **Step 1: EdgePanel.kt — 删 SliderColors 传递链 + 换 AppSlider**

a) 删除 `EdgePanel` 内的 `sliderColors` 定义（52-56 行）：

```kotlin
    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color(0xFF3A3A3A)
    )
```

b) 三个 `EdgeSlider(...)` 调用各自删掉 `colors = sliderColors` 参数（含上一行末尾逗号调整）。

c) `EdgeSlider` 签名删除 `colors: SliderColors` 参数；内部：

```kotlin
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onFinished,
        valueRange = valueRange,
        colors = colors,
        modifier = Modifier.fillMaxWidth()
    )
```

替换为：

```kotlin
    AppSlider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onFinished,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth()
    )
```

d) 更新文件头注释：删掉「滑块与文字使用显式深色配色」相关过时表述，改为说明滑杆统一走 AppSlider（文字仍白字，因证件照内容区强制深色）。

e) import 清理：删 `androidx.compose.material3.Slider`、`SliderColors`、`SliderDefaults`，加 `com.mamba.picme.core.designsystem.components.AppSlider`（`Color` 仍被文字使用，保留）。

松手才回调的行为（`onValueChangeFinished` → `commitParams`）必须原样保留。

- [ ] **Step 2: RepairPanel.kt — 笔刷滑块 → AppSlider**

81-91 行的：

```kotlin
        Slider(
            value = state.brushSizePx,
            onValueChange = callbacks.onBrushSizeChange,
            valueRange = 8f..80f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color(0xFF3A3A3A)
            ),
            modifier = Modifier.fillMaxWidth()
        )
```

替换为：

```kotlin
        AppSlider(
            value = state.brushSizePx,
            onValueChange = callbacks.onBrushSizeChange,
            valueRange = 8f..80f,
            modifier = Modifier.fillMaxWidth()
        )
```

上方「实时更新以保证拖动手感」注释保留（行为不变）。import 清理同上（删 Slider/SliderDefaults，加 AppSlider；本文件 `Color` 仍被其他元素使用，保留）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/idphoto/components/
git commit -m "refactor(idphoto): migrate edge/repair sliders to AppSlider"
```

---

### Task 5: 全量验证 + 文档同步

**Files:**
- Modify: `app/AGENTS.md`

- [ ] **Step 1: 确认全 app 无残留散装 Slider**

Run: `grep -rn "material3.Slider\|SliderDefaults" app/src/main/java --include="*.kt"`
Expected: 无输出（或仅剩与本次无关的注释）。若 `AppSlider.kt` 内部的 `import androidx.compose.material3.Slider` 被 grep 命中属正常（它是实现基础）。

- [ ] **Step 2: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（无新增失败；若有失败先确认是否 main 上已存在的存量失败，与本次改动无关则记录并继续）

- [ ] **Step 3: detekt**

Run: `./gradlew :app:detekt`
Expected: 本次改动涉及的 7 个文件零新增告警（存量告警不计）

- [ ] **Step 4: app/AGENTS.md 同步**

在 `app/AGENTS.md` 合适位置（designsystem 或组件约定相关段落；若无现成段落，在文件末尾「组件约定」类小节追加）加一行说明：

```markdown
- **滑杆组件**：全 app 滑杆统一使用 `core/designsystem/components/AppSlider.kt`（HyperOS 风：胶囊轨道 + 白圆点描边 thumb），禁止直接裸用 M3 `Slider` 或自定义配色。
```

- [ ] **Step 5: 装机 + 真机截图目测**

```bash
./gradlew :app:installDebug
adb -s 51912a5c shell monkey -p com.mamba.picme -c android.intent.category.LAUNCHER 1
```

用 ui-driver / adb screencap 截取三个场景各至少一张：① 相机美颜面板（BeautySlider）；② 相机专业模式面板（4 个 AppSlider 含 steps 曝光）；③ 证件照边缘面板。Read 截图确认：胶囊轨道、白圆点 primary 描边 thumb、已激活段 primary 色。

- [ ] **Step 6: Commit**

```bash
git add app/AGENTS.md
git commit -m "docs(app): document AppSlider as the unified slider component"
```

---

## 自查记录（writing-plans 自审）

- **Spec 覆盖**：AppSlider 规格 → Task 1；迁移清单 8 行 → Task 2（editor 3）、Task 3（camera 3 类）、Task 4（idphoto 2）；验证方案 → Task 5（编译在每 Task 内、单测/detekt/真机/AGENTS.md 在 Task 5）。非目标无对应任务（正确）。spec API 未列 `steps`，但 spec 明确「保留步进语义」且曝光滑杆在用 steps，故 AppSlider 含 `steps: Int = 0`，属 spec 内细化。
- **占位符**：无。
- **类型一致性**：`AppSlider(value, onValueChange, modifier, valueRange, steps, onValueChangeFinished)` 在 Task 1 定义，Task 2-4 所有调用点签名一致。
