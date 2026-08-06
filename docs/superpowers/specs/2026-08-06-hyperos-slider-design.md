# HyperOS 风格滑杆组件设计 Spec

> **日期**：2026-08-06
> **状态**：待实施
> **范围**：`:app` 模块全部滑杆 UI（11 处）

## 1. 背景与现状

当前 app 内滑杆 UI 碎片化严重：全 app 共 11 处 `Slider` 使用点，分布在 4 套互不相干的散装封装里，样式不统一、维护成本高，且整体观感与主流系统（iOS / HyperOS）差距明显。

### 1.1 现状盘点（11 处）

| 位置 | 文件 | 现状 |
|------|------|------|
| 编辑器（3 处） | `features/editor/components/EditorSlider.kt` | 裸 M3 Slider 封装，默认样式 |
| 编辑器 | `features/editor/panels/AdjustPanel.kt` | 裸 M3 Slider |
| 编辑器 | `features/editor/panels/MarkupPanel.kt` | 裸 M3 Slider（占位 stub 面板，本次只换实现不修功能） |
| 相机美颜 | `features/camera/components/BeautySlider.kt` | 全自绘 thumb/track，最精致，按压放大 1.5× |
| 相机专业模式（4 处） | `features/camera/components/ProModeSlider.kt` | private 复制实现（`ProModeThumb`/`ProModeTrack`） |
| 相机唇色 | `features/camera/components/ColorSelectors.kt` | 唇色滑杆 |
| 证件照边缘 | `features/idphoto/components/EdgePanel.kt`（`EdgeSlider`） | M3 Slider + 自定义 `SliderColors` 传递链 |
| 证件照修复 | `features/idphoto/components/RepairPanel.kt` | 笔刷大小滑块 |

### 1.2 主题环境

- 主题入口 `core/designsystem/Theme.kt`（`PoLangTheme`，M3，支持 Material You 动态取色），配色在 `core/designsystem/Color.kt`（primary 浅色 `0xFF6750A4` / 深色 `0xFFD0BCFF`）。
- 相机、证件照内容区强制深色；设置、编辑器跟随系统主题。因此组件必须深浅色自适应，不能硬编码颜色。

## 2. 设计决策（已与用户确认）

| 决策点 | 结论 |
|--------|------|
| 风格 | **B · 小米 HyperOS 风**：胶囊轨道 + 白圆点描边 thumb + 按压放大动画 |
| 范围 | **全局统一**：抽共享组件 `AppSlider`，全 app 11 处滑杆全部迁移 |
| 强调色 | 跟随 `MaterialTheme.colorScheme.primary`（不用固定 HyperOS 蓝，兼容 Material You 动态色） |

## 3. AppSlider 组件规格

### 3.1 位置与 API

新建 `app/src/main/java/com/mamba/picme/core/designsystem/components/AppSlider.kt`。

API 对齐 M3 `Slider` 的最小必要子集：

```kotlin
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
)
```

- **不提供配色参数**：颜色全部由组件内部从 `MaterialTheme.colorScheme` 取，防止再次碎片化。
- 内部基于 M3 `Slider` 的 `thumb` / `track` slot 自定义实现（保留 M3 的手势、无障碍、步进语义），不裸写 Canvas 手势。

### 3.2 视觉规格（HyperOS 风）

| 元素 | 规格 |
|------|------|
| 轨道 | 高度 6dp，全圆角胶囊（`RoundedCornerShape(50%)`） |
| 已激活轨道 | `colorScheme.primary` |
| 未激活轨道 | `colorScheme.onSurface.copy(alpha = 0.12f)`（深浅色自适应） |
| Thumb | 直径 18dp 白色圆点，2dp `primary` 描边，带轻阴影（`shadow(elevation = 2dp, shape = CircleShape)`） |
| 按压态 | thumb 放大 1.15×（`animateFloatAsState`，约 150ms） |
| 轨道内 stop indicator | 不绘制（HyperOS 无刻度点） |

> 注：相机 `BeautySlider` 现有按压放大为 1.5×，迁移后统一到 1.15×（已与用户确认）。

## 4. 迁移清单

| # | 位置 | 迁移方式 |
|---|------|----------|
| 1 | `EditorSlider.kt` | 保持 `EditorSlider` 公共 API 不变，内部实现换为 `AppSlider` |
| 2 | `AdjustPanel.kt` | 裸 M3 Slider → `AppSlider` |
| 3 | `MarkupPanel.kt` | 裸 M3 Slider → `AppSlider`（面板本身是占位 stub，不修功能） |
| 4 | `BeautySlider.kt` | 保持 `BeautySlider` 签名不变，内部换 `AppSlider`，删除自绘 thumb/track 代码 |
| 5 | `ProModeSlider.kt`（4 处） | 换 `AppSlider`，删除 `ProModeThumb` / `ProModeTrack` |
| 6 | `ColorSelectors.kt` 唇色滑杆 | 换 `AppSlider` |
| 7 | `EdgePanel.kt`（`EdgeSlider`） | 保留私有 `EdgeSlider` 封装（只负责 label 布局），内部换 `AppSlider`，删除 `SliderColors` 传递链 |
| 8 | `RepairPanel.kt` 笔刷滑块 | 换 `AppSlider` |

迁移原则：**只动滑杆视觉与实现，不动任何数值逻辑**（valueRange、步进、回调时序保持原样）。

## 5. 非目标

- 不加震动反馈
- 不改任何滑杆的数值范围/回调/持久化逻辑
- 不动 `LinearProgressIndicator` / `QuotaProgressBar` 等进度条组件
- 不修 `MarkupPanel` 的占位功能问题
- 浅色页面（设置等）当前没有滑杆，不为它们预设新滑杆

## 6. 验证方案

1. `./gradlew :app:compileDebugKotlin` 编译通过
2. `./gradlew :app:testDebugUnitTest` 全量单测通过（本改动纯 UI 无 JVM 单测点，要求不引入回归）
3. `./gradlew :app:detekt` 本改动涉及文件零新增告警
4. 真机（小米 24129PN74C，HyperOS，设备 `51912a5c`）深色场景截图目测：相机美颜、相机专业模式、证件照编辑三个场景各至少一张，确认胶囊轨道 + 白圆点 thumb + 按压放大效果

## 7. 红线核对

| 红线 | 结论 |
|------|------|
| [PRIVACY] | 纯 UI 组件，无网络/媒体数据，无影响 |
| [PERF] | 仅 Composable 视觉替换，无新增每帧计算；动画用 `animateFloatAsState`，无影响 |
| [I18N] | 不新增任何文案，无影响 |
| [DOC-SYNC] | 实施后更新 `app/AGENTS.md`（新增 AppSlider 共享组件说明） |
| [AGENT-FIRST] | 收敛 4 套散装实现为单一共享组件，符合显式/自描述原则 |
