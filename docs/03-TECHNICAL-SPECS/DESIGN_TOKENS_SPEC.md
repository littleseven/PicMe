# PoLang Design Tokens 规范（双端还原 SSOT）

> **工作流（2026-08-15 起，codegen 版）**：`design-tokens.json` 是唯一 SSOT；双端镜像**全部由 `scripts/gen-design-tokens.py` 生成，禁止手改**。改 token = 改 JSON → 重跑生成器 → CI（`ai-gate.sh`）跑 `--check` 门禁（生成物与 JSON 不一致即 fail）。可视化预览：生成器输出 `build/design-tokens/ardot-variables.json`，经 Ardot MCP `apply_variables` 推入画布当「token 活体预览」（预览层，非 SSOT）。
>
> 配套文件：
> - SSOT：`shared/src/commonMain/resources/design-tokens.json`（v2.0.0）
> - 生成器：`scripts/gen-design-tokens.py`（含 `--check` 校验模式）
> - Android 生成物：`androidApp/.../core/designsystem/`（`Spacing` / `AppShapes` / `Color` / `Typography` / `DesignTokens.kt` 组件级 token）
> - Android 手写保留：`Theme.kt`（主题装配逻辑；引用 Color.kt 生成值，色值随 SSOT 自动同步）
> - iOS 生成物：`iosApp/PoLang/DesignSystem/DesignTokens.swift`
> - 规范约束：`androidApp/.../core/designsystem/AGENTS.md`（[TOKENS] / [TOKENS-SOURCE] / [SHAPE] / [EASING]）

## 1. 目的

从 Android 现有代码中**抽象提取**核心页面的 UI 风格 token，作为 iOS 移植的视觉基准，确保双端一致、提升还原度。

提取方式：10 个并行 agent 逐页通读 `androidApp` 源码，产出带 `file:line` 的 token 表 + 硬编码值清单。覆盖页面：Gallery / Search / Camera / Editor / Chat / Person+Memory / Tag / ID-Photo / Settings(+Model Center) / App Shell。

本文档 = 抽象结果 + iOS gap 分析 + 漂移记录 + 内容色板附录。

---

## 2. SSOT v1.0.0 → v2.0.0 变更

**保留**（已有，已校验）：`spacing` `radius` `icon` `topBar` `shutter` `beautyPanel` `grid` `pager` `color` `modelCenter`

**新增 — 全局基础（iOS 之前完全缺失）：**

| 组 | 用途 | 关键值 |
|---|---|---|
| `typography` | M3 baseline 字号阶梯（仅 bodyLarge 定制） | 15 个 role，各含 size/lineHeight/weight/letterSpacing |
| `colorScheme` | M3 baseline light/dark 语义色 | 29×2 = 58 个 ARGB hex（primary…surfaceContainerHighest） |
| `alpha` | 透明度语义阶梯（派生色 `.copy(alpha=N)`） | 16 档：scrimModal(0.7) … ghost(0.2) |
| `statusColor` | 语义状态色（不随主题） | success #4CAF50 / warning #FF9800 / error #E53935 / info #2196F3 |
| `motion` | 动效时长 + 缓动 | fast150 / medium300 / slow400 / blink500 / pulse1200，禁用线性 |
| `elevation` | 阴影/tonal 阶梯 | none0 / low1 / medium2 / high4 / floating6 / sheet16 |

**新增 — 共享组件：**

| 组 | 用途 |
|---|---|
| `appSlider` | 全 app 统一滑杆（camera/editor/beauty 共享）：胶囊轨道 6 + 白圆点 thumb 18 + primary 2dp 描边 + 按压 1.15× + 150ms |
| `bottomTab` | 悬浮胶囊导航（FloatingBottomTab）：**cornerRadius=28（v1 漏配，已补）** + shadow6 + tonal3 + icon24 |
| `bottomSheet` | 相机/编辑器共享面板外壳：corner24 + shadow16 + surface@0.95 + 渐变 scrim + dragHandle 36×4 |
| `chip` | FilterChip/AssistChip 几何：height36 + unselected surfaceVariant@0.5 |
| `badge` | 标签徽章：tag radius6 + primary@0.12 + dot6；required #E53935 Bold |

**新增 — 页面级（紧凑，仅取可复用/差异化 token）：** `camera` / `chatBubble` / `settings` / `editor`

**修正：** `shutter` 补全录制态（inner 28dp/4r）、`beautyPanel` 补全 heightRatio 边界、`topBar` 补 titleFontWeight。

**v2.1.0（2026-08-15，相机顶部工具栏改版）：**
- `camera` 组收编原 iOS 手写 CameraTokens：`topToolBar*`（padding/radius/spacing）、`panelCornerRadius`、`inlineFilterPanelHeight`、`panelBackground`(#F21C1A1F)、`cameraAccentOn`、`toolBarUnselectedBg`、`modeSwitcherSpacing`、`zoomBar*`/`zoomCapsule*`；删除死 token（`bottomActionButton*`、各底部面板 heightRatio——面板已改顶部内联）。
- 美颜面板高度唯一 SSOT = `beautyPanel.heightRatio`（0.35 → **0.40**，容下磨皮/美白/瘦脸/大眼 4 行 + Tab 栏）；iOS 由 `CameraTokens.beautyPanelHeightRatio`（手写）切换到 `BeautyPanelTokens.heightRatio`（生成）。
- 生成器新增 `RAW_SWIFT_VALUES` 直出表：JSON 中 `"@xxx"` 占位字符串（语义引用，classify=skip）可经该表为 Swift 生成原始属性行（如 `cameraAccent = Color.accentColor`——系统动态色不可冻结为 hex）；Android 侧不生成（对应语义走 colorScheme 角色，如相机 accent=primary）。`SWIFT_CG_FLOAT_KEYS` 增补 `beautyPanel.heightRatio`（iOS `ControlPanel(heightRatio:)` 形参为 CGFloat）。

---

## 3. ⚠️ Android 代码缺陷（动态取色掩盖，iOS 须规避）

> Android 12+ 默认 `dynamicColor=true`，`LightColorScheme`/`DarkColorScheme` 静态值被 Material You 覆盖，故以下 bug 在真机上不易察觉。iOS 无动态取色，**必须用 SSOT 已修正的标准值**。

1. **`Color.kt:21` `ErrorLight = Color(0xB32610)`** — 6 位 int 被当作 ARGB → `0x00B32610` → **alpha=00 全透明**。SSOT 已用 M3 标准 `#B3261E`。
2. **`Theme.kt:64-65` Dark scheme** — `tertiaryContainer = TertiaryDark`、`onTertiaryContainer = TertiaryDark`（应为 `TertiaryContainerDark` #633B48 / `OnTertiaryContainerDark` #FFD8E4）。SSOT 已修正。
3. **`Theme.kt` 未显式设置** `surfaceVariant` / `outline` / `outlineVariant` / `surfaceContainer*` — 走 M3 默认。SSOT 已补全 M3 baseline 值（iOS 直接用，不再猜）。

---

## 4. 🔴 Token 消费漂移（Android 代码大量绕过自己的 token）

抽取发现：Android 代码**普遍未引用** `Spacing` / `AppShapes` / SSOT，而是在各页面内联硬编码。这是还原度的最大隐患——**iOS 必须以 SSOT + 本规范为准，而不是照抄 Android 内联值**（内联值之间偶有 1dp 不一致）。

典型漂移：
- **相机**快门 `76.dp` 直接硬编码，未引用 `shutter.diameter`；对焦环 `#00E5FF` 硬编码（虽等价 `color.focusRing`）。
- **设置**全屏绕过 `Spacing`/`AppShapes`：每个 `12.dp`/`16.dp`/`RoundedCornerShape(12.dp)` 都是内联。
- **App Shell 导航**过渡 `tween(400)` **未指定 easing**（`motion` 规范要求 FastOutSlowIn）。
- **Search** 存在 i18N 违规：硬编码中文 `未找到匹配…`/`搜索…`/`搜索图标`（违反 [I18N] 红线，须走 `strings.xml`/`Localizable`）。
- **相机 doc overlay** 用了原始 px（疑似 bug，非 dp）。

> 建议（非本次范围）：后续可逐步把 Android 内联值替换为 `MaterialTheme.spacing.*` / `MaterialTheme.appShapes.*` 引用，消除碎片化。

---

## 5. iOS 应用指南（关键 gap）

### 5.1 字号（最高优先级）
iOS 无 M3。`AppTypography.bodyLarge.font` 等 15 个 role 直接产出 SwiftUI `Font`，`.lineSpacing`/`.tracking` 用 `lineHeight`/`letterSpacing`。内联字重覆盖（模型卡名 SemiBold、必备徽章 Bold）见 `AppTypography.WeightOverride`。

### 5.2 配色（最高优先级）
iOS 无 Material You 动态取色。用 `AppColorScheme.light`/`.dark` 作为语义色基准，按 `AppSettings.colorScheme`（system/light/dark）切换。派生色用 `AppAlpha`：例 次要文本 `scheme.onSurface.opacity(AppAlpha.secondary)`，占位 `…placeholder`，模态遮罩 `Color.black.opacity(AppAlpha.scrimModal)`。

### 5.3 强制深色场景
相机 / 证件照 / Tag 对话框 / Chat overlay 是「黑底白字」overlay 词汇：用 `Color.white` + `AppAlpha`、`Color.black` + `AppAlpha`、`AppColors.panelBackground`(#CC000000)，**不走** colorScheme（参考 `bottomSheet.gradientStops`）。

### 5.4 主题切换入口
`iosApp/PoLang/App/PoLangApp.swift` 的 `AppSettings`（themeMode/appLanguage，`@AppStorage`）→ `.preferredColorScheme`。已就绪，配合 `AppColorScheme` 使用。

---

## 6. 内容色板附录（产品内容数据，未入 token JSON）

以下为**静态内容色**（非设计 token），iOS 须照搬 hex 以保证一致。各为已知有序集合。

### 6.1 标注笔触色（markup，7 色）— `MarkupPanel.kt:52-58`
| 名 | hex |
|---|---|
| red | `#FF3B30` |
| orange | `#FF9500` |
| yellow | `#FFCC00` |
| green | `#34C759` |
| blue | `#0A84FF` |
| white | `#FFFFFF` |
| black | `#000000` |

### 6.2 腮红家族（blush，3 色）— `ColorSelectors.kt:49-51`
| 名 | hex |
|---|---|
| pink | `#FF8DAA` |
| orange | `#FFA85C` |
| plum | `#9B3D6A` |

### 6.3 唇色盘（lip，12 色）— `ColorSelectors.kt:119-130`
`#D4757D` `#C43343` `#FF7F50` `#E0527C` `#FF6B9D` `#9B2335` `#FFA07A` `#CD5C5C` `#DC143C` `#FFB6C1` `#B22222` `#FF1493`

### 6.4 证件照底色预设（idphoto，产品数据）
`#438EDB`（蓝）/ `#D9001B`（红）/ `#FFFFFF`（白）

---

## 7. 页面级还原要点（iOS 待实现页）

### Camera（强制深色）
对焦环 `AppColors.focusRing` + `CameraTokens.focusRingDiameter=100`/stroke3/corner20/cross16；快门 `ShutterTokens`（照片态 76 外环/58 内核，录像态 28dp 4r 方）；控制按钮 48/24、idle `Black@0.5`/active primary；模式 tab 13sp（选中 Bold primary / 未选 `White@0.6`，**无 pill**）；三个共享底部面板用 `BottomSheetTokens`；语音唤醒脉冲 1200ms（alpha 0.3→1.0）。

### Chat（双形态）
**ChatScreen**（M3 浅色，DeepSeek 白卡输入）：input corner24 + shadow4 + Black@0.08 ambient / @0.12 spot。**AiChatScreen**（黑底 overlay）：panel Black/corner20、bubble DarkGray@0.85(#2D2D2D)。气泡不对称 20/20/4（user tail bottomStart，agent tail bottomEnd）；bubbleMaxWidth 360 / imageMaxWidth 240 / pad 16h-12v / 文本 14sp-20lh；胶囊按钮 corner16（active primary@0.12 / inactive surfaceVariant@0.5）；overlay alpha 阶 0.03→0.9。状态色用 `StatusColor`。

### Gallery / Search（主入口）
网格 `GridTokens`（110/2/2 方形 Crop）；`TopBarTokens`（48/17sp/36btn/22icon）；`BottomTabTokens`（28 corner 悬浮）；相册预览进场 fade300+scale400 FastOutSlowIn（0.2↔1.0）；search debounce 300ms。SearchField 自建：corner24 / surfaceVariant@0.7 / 14sp / clear 20dp。**Search 须先修 i18N 违规。**

### Editor
顶栏复用 `TopBarTokens`；底部工具栏 FilterChip（无显式高，M3 默认 ~32）；滑杆用 `AppSliderTokens`；滤镜项 72 宽/64 thumb/10sp label/选中描边3-渐变；gacha 卡 84 thumb/corner8/inner6；棋盘格 16 cell (#E6E6E6/#BDBDBD)；裁剪浮动按钮 44/`Black@0.45`/CircleShape/White icon。

### Settings（+Model Center）
`SettingsTokens`：行 56（无副标题）/64（有副标题）、section `surfaceContainerHighest`、chevron 20@0.6；hero 头像 48/`primaryContainer`/CircleShape；分类卡 icon 28 primary。Model Center 标签色以 `ModelCenterTokens` 固定 hex 为准（**注意 Android `getTagColor` 用主题角色与 JSON 分歧——iOS 统一用 token**）。

### Person / Memory（最干净）
全色 token 化，零硬编码 hex；**无关系图谱**（用 FilterChip FlowRow 分组 + OutlinedTextField）；人物卡 16 corner/`surfaceContainerLow`/1 elevation/双列 12 间距/封面 1:1；关系 chip 8 corner（self `primaryContainer` / other `surfaceContainerHighest`）/12sp。

### ID Photo（强制深色）
根 `#101010`；预览 220dp×动态/corner4/白底；色样 40 circle/选中环 3-未选 1；未选 chip `#2A2A2A`；边缘面板 `AppSliderTokens`；背景预设见 §6.4。

### Tag
photo-info tag chip `primary@0.2`/corner6/12sp/White/8h-4v；section header 14sp Bold `White@0.8`；dialog `#2D2D2D`/corner20/scrim `Black@0.7`；成功 `#4CAF50`；警告条 `#FFF3CD`/`#856404`；**无分类配色**（person/scene/object 共用一套）。

---

## 8. 双端同步检查清单

新增/改动 UI 时：
- [ ] 只改 `design-tokens.json`（SSOT），然后跑 `python3 scripts/gen-design-tokens.py` 重新生成双端镜像
- [ ] **禁止手改生成物**（`DesignTokens.swift` / `Spacing.kt` / `AppShapes.kt` / `Color.kt` / `Typography.kt` / `DesignTokens.kt`——文件头有 GENERATED 标记）；`ai-gate.sh` 的 `--check` 门禁会拦截
- [ ] 尺寸只引用 `Spacing`/`IconSize`/`*Tokens`，禁内联 dp
- [ ] 圆角只引用 `AppRadius`/`AppShapes`
- [ ] 颜色：随主题→`AppColorScheme`+`AppAlpha`；固定功能→`AppColors`/`StatusColor`；内容色→§6 附录
- [ ] 动效用 `AppMotion`，禁线性
- [ ] 字体用 `AppTypography` role
- [ ] （可选）改完色值/尺寸后跑 Ardot 同步预览：agent 读 `build/design-tokens/ardot-variables.json` 调 `apply_variables`，`capture_screenshot` 留档
- [ ] 用户可见文本三语同步（Android `values`/`values-zh-rCN`/`values-zh-rTW`；iOS `Localizable`/`*.xcstrings`，注意 iOS 当前缺 zh-Hant）

---

_提取日期：2026-08-09 · SSOT v2.0.0 · 10 页全量抽取_
