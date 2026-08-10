# 双端 UI 一致性体系总纲（Cross-Platform UI Parity Master Plan）

> **版本**：1.0
> **创建**：2026-08-09
> **状态**：生效中
> **维护者**：项目开发者
> **定位**：本文是双端 UI 一致性的**顶层架构文件**，统一所有分散的机制、文档和工具，消除碎片化。所有子文档引用回此文件。

---

## 0. 问题诊断：为什么"保持了"还在漂

我们已经建了不少零件——spec、token、skill、gap analysis、方法论文档——但它们**散落在 7+ 个文件中，没有形成强制运转的闭环**。具体断裂点：

| 断裂点 | 现状 | 后果 |
|--------|------|------|
| **~~Spec 不在 AGENTS.md 红线中~~** ✅ 已解决（2026-08-10） | ~~根 AGENTS.md 定义了 [PRIVACY]/[PERF]/[I18N]/[DOC-SYNC]/[AGENT-FIRST] 五条红线，没有 [PARITY]~~ → **`[PARITY]` 已入 `AGENTS.md` §5（L236）**；`specs/README.md` 顶部亦引用本文为总纲 | ✅ AI 工具加载 AGENTS.md 即见 [PARITY] 红线 |
| **Skill 引用未自动触发** | `compose-ui-expert`/`swiftui-expert` 追加了 [PARITY] 段，但 AI 不一定在每次 UI 任务中加载这些 skill | 约束存在但不执行 |
| **Token SSOT 未被消费** | `design-tokens.json` 已建，但 Android 端 1432 处硬编码 `.dp` 全量保留，新代码也不一定引用 token | Token 体系形同虚设 |
| **Spec 只覆盖 2 屏** | 相机/相册已完整，但其他屏（Chat / 设置 / 编辑器 / 证件照）无 spec | 非核心屏继续漂移 |
| **验证无自动化** | 截图比对依赖人工跑 `ui_diff_check`，没有 CI 门禁 | 漂移直到人工检查才被发现 |
| **Hook 只检查 i18n** | `post-edit-check.sh` 检查硬编码字符串和 doc-sync，**不检查硬编码 dp/color** | 硬编码尺寸无拦截 |
| **根 AGENTS.md 无双端章节** | 顶层治理文档没有双端一致性的章节 | 新来的 AI 实例不知道双端流程 |

**一句话根因：零件齐全，但缺少一个将它们强制编排运转的总纲。**

> **2026-08-10 整合审计复核**：本诊断为 2026-08-09 建立时快照。首行（`[PARITY]` 红线）与「根 AGENTS.md 无双端章节」**均已落实**——`[PARITY]` 在 `AGENTS.md` §5:236，双端总纲/方法论已入 §7 文档索引（L270/286）。其余行（token 存量替换 A6-A8、CI 自动截图 A10）仍成立。

---

## 1. 体系架构：五层防线

```
┌─────────────────────────────────────────────────────────┐
│ L5: 红线层 — 根 AGENTS.md 新增 [PARITY] 红线             │
│     触发：每次代码改动                                      │
├─────────────────────────────────────────────────────────┤
│ L4: 流程层 — Vibe Coding → 固化 Spec → iOS 翻译           │
│     载体：specs/README.md + ui-parity-guard skill        │
├─────────────────────────────────────────────────────────┤
│ L3: 规格层 — Per-Screen Spec（specs/screens/*.yaml）      │
│     载体：camera.yaml / gallery-grid.yaml / ...          │
├─────────────────────────────────────────────────────────┤
│ L2: 数据层 — Design Tokens SSOT（design-tokens.json）     │
│     消费：Android Spacing.kt / iOS DesignTokens.swift    │
├─────────────────────────────────────────────────────────┤
│ L1: 验证层 — Hook 拦截 + 截图比对 + Gap Analysis 闭环    │
│     载体：post-edit-check.sh / ui_diff_check / reviews/  │
└─────────────────────────────────────────────────────────┘
```

每一层的职责、载体和执行机制如下。

---

## 2. L5 红线层：[PARITY] 入根 AGENTS.md

**改动**：在根 `AGENTS.md` §5 全局红线表中新增一行：

| 红线 | 定义 | 验证方式 |
|------|------|----------|
| **[PARITY]** | 双端 UI 一致性：信息层级/布局结构/功能默认/文案状态/无障碍语义零容差一致。新页面 Android 定稿后必须固化 spec；iOS 实现必须读 spec 不读 Android 源码 | Spec 完整性检查、截图比对、gap analysis |

**为什么放红线**：只有红线级别的约束才能被所有 AI 工具（kimi-code / Claude Code）在每次代码改动时强制遵守。当前五条红线（PRIVACY/PERF/I18N/DOC-SYNC/AGENT-FIRST）已被根 AGENTS.md 索引，AI 实例开机即加载。[PARITY] 不入红线，就不会被默认遵守。

---

## 3. L4 流程层：Vibe Coding 研发模式

**载体**：`specs/README.md`（已存在，内容完整）。⚠️ 本节为摘要；**完整流程（新页面/老页面/修改/何时 spec）以 `specs/README.md` 为 SSOT，勿在两处重复维护**（2026-08-10 整合审计）。

### 3.1 新页面流程

```
① Android Vibe Coding（自由，无 spec 约束，硬编码可用）
     ↓ UI 定稿
② 固化 Spec（从定稿代码/截图反向提取 specs/screens/<new>.yaml + 新 token）
     ↓
③ iOS 实现（读 spec + tokens + 截图，不读 Android 源码）
     ↓
④ 截图比对 + 真机验收（浅色+深色双跑）
```

### 3.2 老页面改造流程

```
① Android 已有（完成态）
     ↓
② 固化 Spec（从现有代码/截图反向提取——已完成：camera.yaml + gallery-grid.yaml）
     ↓
③ iOS 重做（按 spec 整体重写，不逐项打补丁）
     ↓
④ 截图比对 + 验收
```

### 3.3 后续修改流程（三同步）

```
改 spec → 同步改 Android + iOS 代码 → 同步改 token（如有新值）
```

**禁止只改一端代码不改 spec。禁止通过读对端源码翻译布局。**

### 3.4 何时需要 spec

| 场景 | 需要 spec | 原因 |
|------|-----------|------|
| 自定义布局 / 复杂状态机 / 核心交互页 | ✅ 必须 | 差异风险高 |
| 纯系统控件列表 / 纯文本页 | ❌ 跳过 | 天然无差异 |

---

## 4. L3 规格层：Per-Screen Spec

**载体**：`specs/screens/*.yaml`

### 4.1 当前覆盖

| Spec | 状态 | 行数 | 覆盖度 |
|------|------|------|--------|
| `camera.yaml` | ✅ 完整 | 1374 | gap analysis 全部 P0/P1/P2 |
| `gallery-grid.yaml` | ✅ 完整 | 863 | gap analysis 全部 P0/P1/P2 |
| `chat.yaml` | ✅ 已建（2026-08-09，`ea798114`/`3f315255`） | — | Chat 交互段固化 |
| `settings.yaml` | ✅ 已建（2026-08-09，`8f30981e`） | — | 设置页固化 |
| `editor.yaml` | ❌ 待建 | — | 中优先级 |
| `idphoto.yaml` | ❌ 待建 | — | 中优先级 |

### 4.2 Spec 质量标准

一份合格的 spec 必须：
1. **自包含**——iOS 开发者（GLM）读 spec + tokens + 定稿截图就能写代码，不需要读 Android 源码
2. **到元素粒度**——每个交互元素都有：id / type / anchor / size（引用 token 名）/ 图标 / 颜色 / 行为
3. **状态机完整**——列出所有页面状态（idle / panel_expanded / searching / selection_mode ...）
4. **Back 栈显式**——面板 > 选择 > 页面返回的优先级链
5. **允许差异登记**——平台原生差异（字体/图标/材质/动画曲线）显式登记到 `allowed_differences`
6. **红线规则前置**——如"禁止系统默认 NavigationStack/TabView"等硬约束

### 4.3 platform_differences 台账（2026-08-10 新增契约层）

底层平台差异（权限/API 能力/隐私披露）此前无契约化载体，靠实现时临场处理。现登记为 spec 内 `platform_differences` 节，三个子节：

- `permission`：双端权限模型与状态机映射（如 Android 单次授权 vs iOS Full/Limited/AddOnly/Denied 四态）→ shared 语义对齐点
- `capabilities`：API 能力矩阵（功能 × 端 → 支持 / 替代方案 / 平台独有流程），shared 接口只暴露业务语义
- `privacy_disclosure`：Android（Manifest + Play Data Safety）与 iOS（purpose string + `PrivacyInfo.xcprivacy` + 隐私标签）披露对照

由 `/ios-follow` Stage 2 在契约固化时随 spec 一并产出/更新；设计见 `docs/superpowers/specs/2026-08-10-ios-follow-command-design.md` §2 Stage 2。

---

## 5. L2 数据层：Design Tokens SSOT

**载体**：`shared/src/commonMain/resources/design-tokens.json`

### 5.1 当前状态

| 项 | 状态 | 说明 |
|----|------|------|
| `design-tokens.json` 源文件 | ✅ 已建 | 间距/顶栏/快门/美颜面板/网格/圆角/功能色 |
| Android `Spacing.kt` | ✅ 已建 | `object Spacing { xs/sm/md/lg/xl/xxl }` |
| Android `AppShapes.kt` | ✅ 已建 | `object AppShapes { panel/card/button/small/thumbnail }` |
| Android `Color.kt` 扩展 | ✅ 已建 | `AppColors` object + 功能色 |
| Android `Theme.kt` 扩展 | ✅ 已建 | `MaterialTheme.spacing/appShapes/appColors` |
| iOS `DesignTokens.swift` | ✅ 已建 | 完整映射（在 worktree 中） |
| 存量硬编码替换 | ❌ 未开始 | Android 1432 处 `.dp` 仍硬编码 |

### 5.2 存量替换策略

**不一次性全替**——按"改到哪替到哪"策略：

1. **新代码必须引用 token**——Hook 拦截（见 L1）
2. **改老代码时顺手替换**——修改某个文件时，把该文件的硬编码值替换为 token 引用
3. **高频文件优先**——`CameraPreviewContent.kt`（68 处）、`MediaPager.kt`（80 处）、`BeautyPanel.kt`（15 处）是 ROI 最高的替换目标

### 5.3 Token 变更流程

```
改 design-tokens.json → 同步 Spacing.kt / AppShapes.kt / Color.kt（Android）
                       → 同步 DesignTokens.swift（iOS）
                       → 更新 spec YAML 中的 token 引用（如有结构变化）
```

---

## 6. L1 验证层：自动化拦截 + 人工验收

### 6.1 Hook 拦截（自动化，改代码时触发）

**现状**：`post-edit-check.sh` 只检查 i18n 硬编码字符串。

**需新增**：在 `post-edit-check.sh` 中追加 dp/color 硬编码检测：

```bash
# 当编辑 .kt 文件时，检查是否引入了新的硬编码 .dp / Color(0x...)
# 仅检查被编辑的文件，不全量扫描
# 观察性（exit 0），打 warning 不阻断——但会在终端可见
```

检测规则（警告级，不阻断）：
- `.kt` 文件中出现 `\d+\.dp` 且文件不在 `designsystem/` 目录 → 警告"硬编码 dp，建议引用 MaterialTheme.spacing"
- `.kt` 文件中出现 `Color\(0x` 且文件不在 `designsystem/` 目录 → 警告"硬编码颜色，建议引用 MaterialTheme.colorScheme 或 AppColors"
- `.swift` 文件中出现 `.frame(width: \d+` 且文件不在 `DesignSystem/` 目录 → 警告"硬编码尺寸，建议引用 Spacing token"

### 6.2 截图比对（半自动，验收时触发）

**载体**：`ui_diff_check`（MCP 工具）+ `scripts/screenshot-diff.py`

**流程**：
```
Android 截图（adb screencap）+ iOS 截图（xcrun simctl io screenshot）
     ↓
ui_diff_check 双端比对 / screenshot-diff.py
     ↓
差异 > 阈值 → 修复 → 再比对
     ↓
差异 ≤ 阈值 → 人工真机验收（终态）
```

**容差**：布局结构零容差；尺寸 ±2dp；平台材质项免检。
**强制要求**：每屏验收必须**浅色 + 深色双跑**。

### 6.3 Gap Analysis 闭环（审计驱动）

**载体**：`docs/reviews/<date>-<screen>-ui-gap-analysis.md`

**流程**：
```
iOS 实现完成 → 逐元素对照审计（Android 现状 vs iOS 实现）
     ↓
产出 gap analysis（🔴/🟡/✅ 分级）
     ↓
将 gap 反馈到 spec（spec 漏了的补上）
     ↓
iOS 按 spec 修复 → 再审 → 直到 🔴 = 0
```

**当前 gap analysis**：
- ✅ `docs/reviews/2026-08-08-ios-camera-ui-gap-analysis.md`（相机）
- ✅ `docs/reviews/2026-08-08-ios-gallery-ui-gap-analysis.md`（相册）
- 待产出：Chat / 设置 / 编辑器 / 证件照（Phase 6 推进时产出）

---

## 7. 子文档索引（消除碎片化）

| 层 | 文档 | 定位 |
|----|------|------|
| **总纲** | 本文（`specs/PARITY_MASTER_PLAN.md`） | 顶层架构，统一所有零件 |
| **红线** | 根 `AGENTS.md` §5 | [PARITY] 红线定义 |
| **流程** | `specs/README.md` | Vibe Coding 研发模式（新页面 + 老页面 + 修改） |
| **方法论** | `docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md` | 度量体系/系统栏/Back/无障碍/深色/动效/RTL/键盘 |
| **Spec** | `specs/screens/*.yaml` | 逐屏完整规格 |
| **Token** | `shared/src/commonMain/resources/design-tokens.json` | 尺寸/颜色/圆角唯一事实来源 |
| **Skill** | `skills/ui-parity-guard/SKILL.md` | 5 步硬规则 |
| **编排** | `skills/ios-follow/SKILL.md`（镜像 `.claude/commands/ios-follow.md`） | /ios-follow：Android 完成后 iOS 一键对等跟随（六阶段管线 + 断点续跑） |
| **设计** | `docs/superpowers/specs/2026-08-10-ios-follow-command-design.md` | /ios-follow 设计 SSOT；platform_differences 台账层定义 |
| **Skill** | `skills/compose-ui-expert/SKILL.md` [PARITY] 段 | Android 侧约束 |
| **Skill** | `skills/swiftui-expert/SKILL.md` [PARITY] 段 | iOS 侧约束 |
| **Hook** | `.kimi-code/hooks/post-edit-check.sh` | 硬编码拦截 |
| **Gap** | `docs/reviews/*-ui-gap-analysis.md` | 审计报告 |
| **Android Token** | `core/designsystem/Spacing.kt` / `AppShapes.kt` / `Color.kt` / `Theme.kt` | Token Android 实现 |
| **iOS Token** | `iosApp/PoLang/DesignSystem/DesignTokens.swift` | Token iOS 实现 |

---

## 8. 待执行行动项

| # | 行动 | 优先级 | 工作量 | 依赖 |
|---|------|--------|--------|------|
| **A1** | 根 AGENTS.md §5 新增 [PARITY] 红线 | ✅ 已完成（`AGENTS.md` §5:236，2026-08-09） | 小 | 无 |
| **A2** | `post-edit-check.sh` 追加 dp/color 硬编码检测 | 🔴 P0 | 小 | 无 |
| **A3** | `specs/README.md` 顶部引用本文为总纲 | ✅ 已完成（`specs/README.md` L2） | 极小 | A1 |
| **A4** | `ui-parity-guard` SKILL.md 顶部引用本文为总纲 | 🟡 P1 | 极小 | A1 |
| **A5** | 根 AGENTS.md §7 文档索引新增本文 + spec/README.md | ✅ 已完成（`AGENTS.md` §7 L270/286） | 极小 | A1 |
| **A6** | 存量 token 替换：CameraPreviewContent.kt（68 处） | 🟡 P1 | 中 | 无 |
| **A7** | 存量 token 替换：MediaPager.kt（80 处） | 🟡 P1 | 中 | 无 |
| **A8** | 存量 token 替换：BeautyPanel.kt（15 处） | 🟢 P2 | 小 | 无 |
| **A9** | Chat 屏 spec 产出 | 🟢 P2 | 中 | Phase 6.2 |
| **A10** | 自动截图 diff CI（Android + iOS 双端自动截图 → 比对） | 🟢 P2 | 大 | iOS 模拟器可自动化 |

---

## 9. 一句话总结

> **[PARITY] 是红线，spec 是契约，token 是数据源，skill 是约束层，hook 是拦截层，gap analysis 是审计层。六层各司其职，由本文总纲编排运转——不是靠某一个零件单独保证一致性，而是靠整个体系的强制闭环。**
