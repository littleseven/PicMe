# iOS-Follow · chat 页对齐 · 审查报告

- **日期**: 2026-08-12
- **模式**: B（功能追齐）
- **基准端**: Android `features/chat/`（SSOT）
- **契约基线**: `docs/08-UI-SPECS/screens/chat.yaml`
- **分支**: `feat/ios-chat-align`
- **用户裁决**: 全五区深度对齐（A+B1+B2+B3），含反转 spec §11 AI 工程师豁免
- **关联**: 计划 `tmp/ios-follow/chat/follow-plan.md` · 契约 `tmp/ios-follow/chat/contracts.md`

---

## 0. 执行摘要

chat 页五区经三 Explore agent 全量核查后，gap 分化极大：**聊天历史已功能对等**；**横滑卡片**为中度 UI 缺口；**上下文/AI 工程师/JS toolcalls** 三区为**「未实现」而非「漂移」**（周级新功能）。

本轮按 `/ios-follow` 分层交付：**Stage 2 契约为全五区一次固化**（解锁后续所有批次），**Stage 3 实现先交付 Tier A 有界对齐**（本批），B1/B2/B3 各为独立后续批次。

| 区 | gap 量级 | 本轮状态 |
|---|---|---|
| 横滑卡片 | 🟡 中 | Tier A：尺寸 token 化 + 日期标签 ✅；反馈按钮/查看全部 → manifest 微批次 |
| 聊天历史 | 🟢 低 | 已功能对等（JSON 文件 vs Room，纯架构差），无需改动 |
| 上下文 | 🔴 全缺 | 契约固化（§12）✅；实现 → B1 批次 |
| AI 工程师 | 🔴 全缺 | 契约固化（§14）+ 反转 §11 ✅；实现 → B3 批次 |
| JS toolcalls | 🔴 全缺 | 契约固化（§13）✅；实现 → B2 批次 |
| 跨区：DesignTokens 合规 | 🔴 0 引用 | Tier A：尺寸全 token 化 ✅；颜色 appScheme 迁移 → 技术债 |
| 跨区：错误静默丢弃 | 🔴 bug | Tier A：handleUiAction 补全 ✅ |

---

## 1. Stage 2 契约固化（全五区，已完成）

### 1.1 spec 变更 `docs/08-UI-SPECS/screens/chat.yaml`
- **§11 反转**：撤回「AI 工程师模式 iOS Phase 6.3 未实现」「Claude Agent Steps」两条豁免（用户签）。
- **§12 新增** 上下文附件（photo picker + 72dp 暂存缩略图 + 三意图 chip UNDERSTAND/FIND_SIMILAR/EDIT + 隐私 STRICT）。
- **§13 新增** JS 工具调用（manifest 暴露 run_gallery_script/draw_chart + 引擎选型 + chart 卡 + 写确认弹窗 + 沙箱徽章）。
- **§14 新增** AI 工程师模式（smart_toy 胶囊 + 独立会话 + SSE 事件模型 + 步骤列表 + 截断继续 + 交付 push/pr/auto + 白名单）。
- **§15 登记** 媒体轮播 iOS 缺口（计数头已实现；反馈按钮/查看全部/全屏预览待补）。
- **§16 新增** 平台差异台账（权限四态映射 / 能力矩阵 / 隐私披露对照）。

### 1.2 契约登记 `tmp/ios-follow/chat/contracts.md`
- iOS 现消费 7 条契约（经 ChatAgentBridge 手桥）逐条登记。
- 追齐需新增/变更契约分 🟢/🟡/🔴 三级登记，含 3 项设计决策待 B 批次实施时定：
  - **C-B2-1** JS 引擎选型：quickjs-kt（KMP 同源，推荐）vs JavaScriptCore（iOS 原生 fallback）。
  - **C-B2-2** chart surfacing：ChatUiActionDto 加 `chart` kind（携 svg+summary）。
  - **C-B3-1** SSE 客户端落点：ClaudeSseParser 下沉 commonMain + iOS URLSession actual（推荐）。

### 1.3 token 同步（add-only，铁律 1 守）
- `design-tokens.json` 新增 `chatCarousel` / `chatContext` 节 + chatBubble 加 `circularButton*`（JSON 校验通过）。
- `DesignTokens.swift` 新增 `ChatCarouselTokens` / `ChatContextTokens` enum + ChatBubbleTokens 加 `circularButtonSize/IconSize`。
- Android `Spacing.kt` 不动（feature 维度按既有惯例不入通用 spacing；**不改** Android 既有硬编码引用）。

---

## 2. Stage 3 Tier A 实现（本批，已完成）

纯 Swift（无 XCFramework 重建需求，可独立编译），6 文件改动：

### A1 · DesignTokens 尺合规（`ChatView.swift`）
chat 6 文件原对 token 系统 **0 引用** → 顶栏/气泡/输入栏/胶囊/圆形按钮/媒体卡 尺寸全量改为引用 `TopBarTokens` / `ChatBubbleTokens` / `ChatCarouselTokens`。满足 [PARITY] 红线「尺寸/颜色必须引用 DesignTokens.swift」。

### A2a · 媒体卡日期标签（`ChatView.swift` `MediaThumbnail`）
`MediaCardRow` 经 PHAsset 枚举建 `idToDate` 映射；每卡底部加 `yyyy-MM-dd` 日期标签（渐变 scrim 上白字），对齐 spec §9 `card_date_label`。（反馈按钮 👍👎🔄 + 查看全部尾卡 → manifest 微批次，需暴露 `record_feedback`/`more_like_this` + 重建 XCFramework。）

### A3 · handleUiAction 补全（`ChatViewModel.swift`）
原 `text_reply`/`success`/`error` 落 `default: break` **静默丢弃**（错误尤甚）→ 现按 kind 渲染：`text_reply` 追加 agent 文本消息；`error` 追加 agent 气泡（spec：错误无特殊色，同正常气泡）；`success` 静默（无用户可见载荷，对齐 Android）。

### A4 · isDefaultTitle 稳健化（`ChatViewModel.swift`）
默认标题判定增补本地化「Chat」形式，去英文硬编码脆弱性。

---

## 3. Stage 4 验收状态

| 项 | 命令能判 | 结果 |
|---|---|---|
| Swift 语法 | `swiftc -parse`（iPhoneOS SDK） | ✅ 三文件无结构/语法错误（仅 sysroot 警告 + 预存 SharedKit SourceKit 模块解析告警） |
| design-tokens.json | `python json.load` | ✅ 合法，新键 chatCarousel/chatContext/circularButton 在位 |
| commonMain 纯度 | 未触 commonMain 代码 | ✅ 不适用（本批无 Kotlin 改动） |
| SharedKit XCFramework | `JITPACK=true ./gradlew :shared:assembleSharedDebugXCFramework` | ✅ BUILD SUCCESSFUL（3m26s，SKIE name-collision 警告为预存噪声） |
| iOS 编译（xcodebuild） | `xcodebuild build -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO` | ✅ **BUILD SUCCEEDED**（arm64 device，Tier A Swift + SharedKit 资源改动全编译通过；仅预存 GRDB deployment target 警告） |
| 真机截图 / SSIM | 需设备 + 签名 | ⚠️ 待用户真机终验 |

**编译验证结论**：XCFramework 重建 + PoLang app target 全量编译双绿。本批 Tier A 改动（token 化 / 日期标签 / handleUiAction 补全 / isDefaultTitle）经真实 xcodebuild 验证无误。剩余为真机运行时观感（需签名 + 连接设备）。

---

## 4. 待真机终验（判不了，留用户）

按 `/ios-follow` 验收边界，以下需真机/模拟器人工或脚本终验：
- Tier A 改动的**观感**：顶栏/气泡/输入栏尺寸是否与 Android 像素一致（应零变化，纯 token 引用替换）；媒体卡日期标签可读性（浅色/深色）。
- 错误渲染：触发一次工具错误，确认 agent 气泡显示错误文案（不再静默）。
- `appScheme` 颜色迁移未做（见技术债）—— 当前 `Color.accentColor`/`Color(.secondarySystemBackground)` 已自适应明暗，但非 M3 `appScheme` 调色板，色值可能与 Android 略有差。

---

## 5. 技术债清单 / 后续批次

### 本轮未做（跨区）
- **TD-1 颜色 appScheme 迁移**：chat 气泡/胶囊/输入栏颜色 `Color.accentColor`/`Color(.secondarySystemBackground)` → `appScheme(cs).primary/surfaceVariant`（7 个 feature 已用此模式，chat 是唯一例外）。涉及视觉，须真机比对后做。
- **TD-2 全屏图片预览**：tap 媒体卡 → pinch-zoom HorizontalPager（iOS 缺整层，spec §9 interaction）。

### Tier A 残留微批次（需 XCFramework 重建）
- **A2-manifest**：iOS `ChatToolManifest` 加 `record_feedback` + `more_like_this`（子集加法，`ChatToolManifestConsistencyTest` 子集语义保护）→ `MediaCardRow` 加 👍👎🔄 反馈按钮 + 查看全部尾卡（+ gallery 导航回调）。

### B 批次（新功能，契约已固化）
- **B1 上下文附件**：PHPicker + 72dp 暂存 + 三意图 chip + `ChatAgentBridge.sendMessage` 签名扩 + `MemoryContextProvider` iOS actual。隐私红线守。
- **B2 JS toolcalls**：JS 引擎选型（C-B2-1）+ iOS `JsEngine` actual + gallery/dispatch handlers + 写确认 + chart kind（C-B2-2）+ manifest 加 run_gallery_script/draw_chart。
- **B3 AI 工程师**：SSE 客户端落点（C-B3-1）+ ClaudeEvent/Engine/SidStore + ClaudeAgentRenderer 状态机 + 步骤 UI + 截断继续 + 交付按钮 + 白名单。

### 留技术债（不纳入本轮）
- `MemoryCapability`/`PersonRelationCapability` iOS 暴露（remember_*/forget_*/recall_memory CRUD + 持久层）。
- `edit_image`/`ai_optimize` iOS 暴露（依赖 PhotoEditor/AiOptimize iOS 完成度）。
- 聊天历史存储架构统一（JSON→GRDB，功能已对等，不迁）。

---

## 6. 结论

- **整体判定**: 🟡 **PARTIAL — Tier A 已交付且 xcodebuild 编译双绿（XCFramework + app target）；全五区深度对齐为多批次工程，契约层（解锁后续）已一次完成。**
- 🔴 未清零项：B1/B2/B3 三区实现（契约已固化，待独立批次）+ A2 manifest 微批次。
- 本批无破坏性改动；Swift + JSON 资源；Android 侧零改动（铁律 1 守）。
- 已验证：`assembleSharedDebugXCFramework` ✅ · `xcodebuild build` (arm64 device, signing off) ✅。
- 待真机：运行时观感（token 化应像素零变化）、错误渲染触发确认、明暗模式。
- 下一步建议顺序：A2-manifest（快）→ B1（中，无外部阻塞）→ B2（大，引擎选型验证）→ B3（大，SSE 路径）。每批独立 worktree/提交 + 真机验收。
