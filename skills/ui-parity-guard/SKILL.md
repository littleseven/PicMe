---
name: ui-parity-guard
description: |
  双端 UI 一致性守卫。实现/修改任何 UI 屏幕前，强制走 spec → token → 截图闭环，
  替代已证伪的"读对端源码翻译"路线。双端共用（Android Compose + iOS SwiftUI）。
version: 1.0.0
created: 2026-08-09
updated: 2026-08-09
maintainer: [RD] 全栈工程师
tags:
  - ui
  - parity
  - design-tokens
  - spec
  - android
  - ios
  - compose
  - swiftui
---

# UI Parity Guard Skill

> **定位**：双端 UI 一致性的强制约束层——让 K3（写 Compose）和 GLM（写 SwiftUI）各自只看共享的 token/spec，不靠"读对端源码脑补"。
> **触发时机**：实现/修改任何屏幕的 UI（布局、元素增减、尺寸/颜色/间距变更）时自动启用。

---

## 🔴 硬规则（RULE）

实现/修改任何 UI 屏幕前，**必须按序执行以下 5 步**：

### 1. 先读 spec

读 `specs/screens/<screen>.yaml`。如果 spec **不存在**，先创建它（参照 camera.yaml / gallery-grid.yaml 格式），再写代码。

**禁止跳过此步直接写 UI 代码。禁止通过读对端源码来"翻译"布局**（此路线已被两轮真机验收证伪）。

### 2. 引用 design tokens

尺寸/颜色/间距/圆角必须引用 token 常量，禁止硬编码数值：

| 平台 | 间距 | 圆角 | 功能色 | 主题色 |
|------|------|------|--------|--------|
| Android | `MaterialTheme.spacing.sm` | `MaterialTheme.appShapes.panel` | `MaterialTheme.appColors.focusRing` | `MaterialTheme.colorScheme.primary` |
| iOS | `Spacing.sm` | `AppShapes.panel` | `AppColors.focusRing` | 系统 `.primary` / `.secondary` |

**Token SSOT**：所有值的唯一事实来源是 `shared/src/commonMain/resources/design-tokens.json`。

### 3. 如果引入新尺寸值

如果 spec / token 中没有你要用的值：

1. 先加到 `design-tokens.json`
2. 同步到 Android（`Spacing.kt` / `AppShapes.kt` / `Color.kt`）和 iOS（`DesignTokens.swift`）
3. 在代码中引用 token 常量

**禁止直接在代码中写 `16.dp` 或 `.frame(width: 48)` 等"巧合性"硬编码。**

### 4. 如果改了布局结构

同步更新 `specs/screens/<screen>.yaml`：
- 增减了元素 → 更新元素树
- 改了 anchor / 尺寸比例 → 更新对应参数
- 改了状态机 → 更新 states 列表
- 新增了平台差异 → 登记到 allowed_differences

### 5. 提交截图（验证闭环）

改完 UI 后，采集截图供 diff 检查：
- **Android**：`adb exec-out screencap -p > tmp/shots/<screen>_<state>.png`
- **iOS**：`xcrun simctl io <device> screenshot tmp/shots/<screen>_<state>.png`

每屏至少采集 2 个状态（idle + 最常见的交互态，如 panel_expanded）。

---

## 一致性分层（什么必须一致）

| 层 | 对齐要求 | 示例 |
|---|---|---|
| 信息层级 | 🔴 零容差 | 每屏有哪些元素、分组、优先级 |
| 布局结构 | 🔴 零容差（归一化后） | 元素相对位置、锚定关系、尺寸比例 |
| 功能与默认 | 🔴 零容差 | 功能集、默认值、排序、状态机 |
| 文案与状态 | 🔴 零容差 | 空态/加载态/权限态（经 i18n） |
| 字体/图标 | 🟢 平台原生 | Roboto vs SF、Material vs SF Symbols |
| 系统交互 | 🟢 平台原生 | 导航返回方式、权限申请流、picker 形态 |
| 材质细节 | 🟢 平台原生 | 涟漪 vs 高亮、阴影风格、动画曲线 |

> **原则**：用户的心智模型和任务流一致；平台的视觉语言各自原生。凡是允许不同的项，必须登记进 spec 的 `allowed_differences`，不允许悄悄不同。

---

## 反模式（已实证）

| 反模式 | 后果 |
|--------|------|
| 读源码脑补布局再翻译 | 两轮返工，真机验收「完全对不上」 |
| px 直接当 dp/pt 用 | 高密度机上整体放大 2~3 倍 |
| 硬编码绝对坐标 | 换机型/换刘海形态即错位 |
| 忽略 safe area | 顶栏被刘海吃、底栏被手势条挡 |
| 用平台默认控件拼装 | 信息层级全面缺失 |
| 不更新 spec 就改 UI | 双端持续漂移，gap analysis 永远清不完 |

---

## 相关文件

- `shared/src/commonMain/resources/design-tokens.json` — Token SSOT
- `specs/screens/*.yaml` — 逐屏规格契约
- `docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md` — 完整方法论
- `docs/reviews/2026-08-08-ios-camera-ui-gap-analysis.md` — 相机差距分析
- `docs/reviews/2026-08-08-ios-gallery-ui-gap-analysis.md` — 相册差距分析
- Android: `core/designsystem/Spacing.kt` / `AppShapes.kt` / `Color.kt`
- iOS: `DesignSystem/DesignTokens.swift`

---

## 版本历史

| 日期 | 变更 |
|------|------|
| 2026-08-09 | 初版：5 步硬规则 + 分层模型 + 反模式清单 |
