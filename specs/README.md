# Per-Screen UI 研发流程

> **适用**：PoLang 项目所有新页面的 UI 开发（双端：Android Compose + iOS SwiftUI）
> **前置依赖**：`design-tokens.json`（Token SSOT）、`skills/ui-parity-guard/SKILL.md`（硬规则）
> **角色**：个人开发者，Android 为主栈，无设计稿，Spec 替代设计稿

---

## 核心原则

> **Spec 先行，双端并行收口。Spec 替代设计稿，也替代"读 Android 源码翻译"。**

以前的模式是 Android 代码即"设计稿"，iOS 去读代码翻译——两轮真机验收证伪。新模式是 spec 是"设计稿"，两端各自从 spec 落地，代码不是参照物。

---

## 流程（5 步）

```
① 想清楚要什么（脑子，5 分钟）
     ↓
② 写 spec（specs/screens/<new>.yaml，10-15 分钟）
     ↓
③ Android 实现（spec → Compose）         ③' iOS 实现（spec → SwiftUI）
     ↓                                       ↓ 可与 ③ 并行
④ 截图比对 + 真机验收
     ↓
⑤ 合并，spec 归档为该屏 SSOT
```

### ① 想清楚要什么

不画图，只回答 4 个问题：

- 这页有哪些**信息**？（元素列表、分组、优先级）
- 有没有关键**尺寸**直觉？（面板高度比例、图标大小、网格列数）
- **系统栏**怎么处理？（沉浸 / 正常，状态栏色）
- 有没有**新组件**需要新 token？

### ② 写 spec

参照 `specs/screens/camera.yaml` / `gallery-grid.yaml` 格式，填 5 个块：

```yaml
# specs/screens/<new-page>.yaml
system_bars:          # 状态栏显隐 + 内容色 / Home 指示器处理
back_stack:           # Back 优先级链（面板 > 选择 > 退出）
elements:             # 元素树：anchor + size（引用 token 名）
  root:
    top_bar: { height: topBar.height, ... }
    content: { ... }
states:               # idle / panel_expanded / ...
allowed_differences:  # 允许的平台原生差异
```

**尺寸引用 `design-tokens.json` 的 token 名**（如 `spacing.lg`、`topBar.height`），不写裸数值。如果需要新 token，先加到 `design-tokens.json`，再在 spec 中引用。

大多数新页 80% 的值是复用已有 token，只需要加少量新 token。

### ③ 双端实现（可并行）

**Android**：读 spec → 写 Compose。尺寸引用 `MaterialTheme.spacing.xxx` / `MaterialTheme.appShapes.xxx`。和你之前写 Android 没区别——唯一区别是尺寸来自 spec 而非临场决定。

**iOS**：读**同一个 spec** → 写 SwiftUI。尺寸引用 `Spacing.xxx` / `AppShapes.xxx`。**不读 Android 代码**——spec 已经包含所有需要的信息。

可附带一张 Android 截图（`tmp/ui-reference/`）作为**视觉参照**（给 AI 看"长什么样"），但**代码参照是 spec 不是源码**。

### ④ 验收

- Android 截图 + iOS 截图（`adb screencap` / `xcrun simctl io screenshot`）
- 比对：信息层级对不对、布局结构像不像、尺寸比例是否一致
- 容差：布局结构零容差；尺寸 ±2dp；平台材质项（图标形状/字体/涟漪）免检
- 详见 `IOS_ANDROID_UI_PARITY.md` §5

### ⑤ 归档

spec 留在 `specs/screens/` 里，是该屏的**永久 SSOT**。以后改 UI 先改 spec，再改两端代码。

---

## Spec 不是不可变的契约

写 Android 时调 UI 发现要改尺寸 → **同步改 spec 和 design-tokens.json**。

spec 是"当前事实的记录"。关键是：改的时候改三处（spec + Android + tokens），而不是只改 Android 代码。这样 iOS 永远能跟上。

---

## 什么时候需要写 spec

| 场景 | 需要 spec？ | 原因 |
|------|------------|------|
| 自定义布局（面板、悬浮控件、自定义网格） | ✅ 必须 | 双端差异风险高 |
| 复杂状态机（多面板 / 选择模式 / 权限态） | ✅ 必须 | 状态不对齐 = 体验断裂 |
| 相机 / 编辑器 / 美颜等核心交互页 | ✅ 必须 | 体验敏感，信息层级零容差 |
| 纯系统控件列表（设置页、关于页） | ❌ 跳过 | 双端用各自原生控件，天然无差异 |
| 纯文本 / Web 内容页 | ❌ 跳过 | 无自定义布局 |

---

## 成本

| 环节 | 耗时 | 省掉了什么 |
|------|------|-----------|
| 写 spec | 10-15 分钟 | — |
| 不写 spec 的后果 | — | iOS 翻译返工（2 天+）、gap analysis 审计 + 修复、双端漂移调试 |

对个人开发者，前置 15 分钟换掉后端数天返工，净赚。

---

## 相关文件

- `shared/src/commonMain/resources/design-tokens.json` — Token SSOT
- `specs/screens/camera.yaml` / `gallery-grid.yaml` — spec 示例
- `skills/ui-parity-guard/SKILL.md` — 5 步硬规则
- `docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md` — 完整方法论
