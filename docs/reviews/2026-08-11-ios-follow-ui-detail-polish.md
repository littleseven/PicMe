# iOS UI 细节精修审查报告（person + editor，spec 对齐 47 项）

- **日期**：2026-08-11
- **分支**：`fix/ios-ui-detail-polish`（worktree `.worktrees/ios-ui-detail-polish`）
- **契约**：`docs/08-UI-SPECS/screens/person.yaml` / `docs/08-UI-SPECS/screens/editor.yaml` / `design-tokens.json`
- **起因**：用户反馈 ios-follow 同步结果「UI 细节不够」。explore 子 agent 做 spec↔iOS 对照产出 47 项差距清单（结构 11 / 尺寸 10 / 颜色形状 i18n 26），本轮全量修复。
- **模型分工（铁律 4）**：GLM coder ×2 实现（人物 25 项 / 编辑器 22 项）→ **K3 主会话亲审全部 diff 并修正**（交叉审查）。

## 两条系统性根因（已修）

1. **颜色 token 未消费**：Person/Editor 两屏大量 `Color.white`/`white.opacity(N)` 硬编码 → 全部改 M3 语义色（`primaryContainer`/`onPrimaryContainer`/`surfaceVariant@0.5`/`onSurfaceVariant`/`onSurface`/`outline`/`primary`）。
2. **chip 几何系统性偏移**：EditorChip 高 28→36（去掉 `-8`）；人物详情 is_self toggle 与关系 chip 组高→36；chip 形状统一 `PersonTokens.relationChipRadius=8`（token 早已定义未使用）。

## K3 审查修正（GLM 交付之外的增量）

| # | 修正 | 理由 |
|---|------|------|
| R1 | **语义色锁定暗色档**：人物/编辑器视图的 `s` 从 `appScheme(cs)`（主题驱动）改为 `AppColorScheme.dark` 固定 | 🔴 关键发现：`MainTabView.swift:120` `Color.black.ignoresSafeArea()` shell **恒黑**，编辑器画布（`PhotoEditorScreen.swift:39`）也恒黑。浅色主题下 `appScheme` 会返回深色文字（onSurface=#1C1B1F）压黑底不可见。spec 引用的就是 M3 dark 值 |
| R2 | `appScheme` 重声明冲突：删 `TagScanComponents.swift:15` 私有副本，复用 DesignSystem 共享 helper | 构建错误修复 + 去重（语义一致） |
| R3 | `AdjustPanel` 误删 `maxWidth: .infinity`，补回 | GLM 改 maxHeight 时丢失 |
| R4 | 清扫 spec 外残留硬编码白（同一系统性问题）：人物详情自定义称呼输入框文字/描边/辅助文案、名称行内编辑、chip 组标题（家庭/社会）、列表 info 图标 → `s.onSurface`/`s.outline`/`s.onSurfaceVariant` | 浅色场景一致性（spec 未逐条列但同根因） |
| R5 | `Localizable.xcstrings` 格式回滚重放：python json.dump 全量重排（1004 行格式churn）→ 恢复 HEAD 后按文件原有 compact 风格插入 10 个 key（+80/−0） | diff 可审性 |
| R6 | worktree 缺二进制依赖：`MNN.framework`（gitignored，10MB）从主 checkout 拷贝；`SharedKit.xcframework` 经 `:shared:assembleSharedDebugXCFramework` 重建 | 构建环境修复 |

## 改动清单（9 文件）

| 文件 | 内容 |
|------|------|
| `DesignSystem/DesignTokens.swift` | 新增共享 `appScheme(_:)` helper（internal） |
| `Features/Person/PersonView.swift` | 列表 13 项 + R1/R4：卡片弱投影、info 图标 20、计数 padding 4、NameEditor 图标 18/框 28、topBar 高 48、relation chip 形状 r=8 + 语义色、名字/计数/编辑框颜色 token 化、chip 字重 regular |
| `Features/Person/PersonInfoView.swift` | 详情 12 项 + R1/R4：自定义称呼 label（新 key）、section v:12 间距、reset a11y 语义、chip/toggle 高 36 + 语义色、cover picker 网格 padding h16/b24 + 标题 titleLarge（去 NavigationView 改自定义 header） |
| `Features/Editor/EditorPanels.swift` | EditorChip 高 36 + 语义色；FilterPanel 重写 14 项（9 色+5 风格、互斥、占位渐变缩略图、选中 overlay/边框渐变/label 色）；MarkupPanel 图标/spaceEvenly/top12/swatch 边框；AdjustPanel maxHeight 220（R3 补 maxWidth）；新增 BeautyPanel 6 参数滑杆（B1：可调+存档，渲染 no-op） |
| `Features/Editor/PhotoEditorScreen.swift` | topBar 图标 22、remove_background/rotate 图标修正、spinner tint primary、textInputDialog 对齐 spec（AppShapes.panel + 语义色）、两处裸英文→语义 i18n key；删 BeautyNotice |
| `Features/Editor/RecipeModels.swift` | 新增 `StyleFilter` 枚举（NONE+5）；`EditRecipe.styleFilter` 字段；BeautySettings 扩 6 参数 + `Param` 枚举 |
| `Features/Editor/RecipeApplier.swift` | `filterStyle` 渲染链（crop→adjust→filterColor→**filterStyle**→markup）：TOON≈CIComicEffect、SKETCH≈CILineOverlay、POSTERIZE≈CIColorPosterize(8)、EMBOSS≈CIEdges+PhotoEffectMono、CROSSHATCH≈CILineScreen，均带 `?? image` fallback |
| `Features/TagScan/TagScanComponents.swift` | 删私有 `appScheme`（R2 去重） |
| `Resources/Localizable.xcstrings` | +10 key 三语：editor_feature_unavailable / beauty_preview_unavailable / style_toon / style_sketch / style_posterize / style_emboss / style_crosshatch / Lip Color / Blush / Custom label |

## spec 变更

- `docs/08-UI-SPECS/screens/editor.yaml` §14 新增 **B7_style_filter_approx_render**（severity: acceptable）：style 滤镜 CI 近似渲染登记，观感对齐留真机终验，逐像素对齐再评估 Metal kernel/LUT。

## ✅ 自动通过

- xcodebuild device 构建：**BUILD SUCCEEDED（generic/platform=iOS，0 error）**
- xcstrings JSON 校验通过，10 key 三语齐全（398 keys）
- spec 对照 47 项全部落地或登记（B7）
- 构建重试记录：① SharedKit XCFramework 缺失→Gradle 重建 ② appScheme 重声明→去重（R2）③ MNN.framework 缺失→拷贝

## ⚠️ 待真机终验（留用户）

- style 滤镜 CI 近似渲染观感（B7 登记）；EMBOSS/CROSSHATCH 与 Android GPU shader 差距可能较大
- BeautyPanel 滑杆手感与参数存档（渲染 no-op 属 B1 约定）
- 人物页数据态观感：本机 iOS 相册为空（无聚类数据），空态/顶栏可截，数据态需先导入测试图 + 跑 TAG 扫描
- 双端截图 SSIM 比对（浅色+深色）依赖数据态，顺延

## 📋 技术债 / 备注

- 滤镜缩略图为语义占位渐变（spec 允许），TODO 真实缩略图（assets filters/*.jpg）
- `EditorTokens.filterSelectedBorderWidth` 在 DesignTokens 中存在两处同名定义（375/424，分属不同 enum），未动，登记
- worktree `project.pbxproj` 为 xcodegen 重生成 UUID churn，提交前建议还原（project.yml 未变）
- 编辑器内两处 `Color(.systemBackground)` 面板底色（85/190 行附近）在浅色主题下与锁黑画布混搭属既有状态，本轮不动
