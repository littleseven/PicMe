# iOS Follow 验收报告 — editor（2026-08-10）

> `/ios-follow editor` Mode B（功能追齐）· 用户决策「分阶段落地」。
> 分支 `feat/editor` @ `.worktrees/editor`（base = main HEAD `bfa72823`）。

## 0. 执行摘要

Android editor（~3,240 行，5 类编辑 + 对话式编辑）已在 main；iOS editor 此前**全量缺失**。本轮按用户「分阶段落地」决策，落地**无需美颜引擎的可用 lite 子集**（CROP/ADJUST/FILTER 9 色/MARKUP + Recipe/EditHistory 模型 + 非破坏性保存），并将依赖尚不存在的 iOS 子系统的能力（静态图美颜渲染、抠图、AI 优化、5 风格滤镜、对话式编辑、配方持久化）登记为技术债。

**关键修正**：初判以为编辑器依赖的 `FilterType`/`StyleFilter`/`BeautySettings` 在 Android-only `beauty-api` 模块（非 KMP），实测它们**在 `:shared/commonMain/.../beauty/api/`** 经 XCFramework 可消费。但代码库既有先例是**本地重声明**（`FilterColorMatrix.swift` 已有本地 `FilterType` 含 colorMatrix），本轮遵循先例复用该本地类型，未新增首条跨边界 SharedKit 导入（更低风险、更一致）。

## 1. 交付物

### Stage 2 契约（✅）
- `specs/screens/editor.yaml` — 16 节完整 spec（结构/状态机/5 面板到元素粒度/渲染管线/Recipe 模型/allowed_differences/platform_differences/i18n）
- `tmp/ios-follow/editor/contracts.md` — Swift 模型签名 + shared 消费清单
- `platform_differences` 台账（permission/capabilities/privacy_disclosure）— spec §15
- design-tokens：`EditorTokens`/`AppSliderTokens`/`ChipTokens` **双端早已就位**，本轮零 token 同步工作

### Stage 3 实现（✅ 编译绿）
新增 8 个文件 / **1,397 行 Swift**（`iosApp/PoLang/Features/Editor/` + `DesignSystem/AppSlider.swift`）：

| 文件 | LOC | 职责 |
|------|-----|------|
| RecipeModels.swift | 207 | EditRecipe/CropRecipe/AdjustmentRecipe+Param/MarkupAction/AspectRatio/EditorTab/BeautySettings(local)/EditHistory 依赖 |
| EditHistory.swift | 62 | 撤销/重做栈（Kotlin EditHistory.kt 忠实移植） |
| RecipeApplier.swift | 196 | CoreImage 管线：crop→adjust→filterColor→markup |
| PhotoEditorViewModel.swift | 168 | 状态机 + ThumbnailLoader 加载 + 200ms debounce 渲染 + PhotoSaver 保存 |
| PhotoEditorScreen.swift | 319 | 顶栏/可缩放预览/面板槽/5 tab 条/标记输入弹窗 |
| EditorPanels.swift | 243 | EditorChip + EditorBottomBar + Crop/Adjust/Filter/Markup 面板 |
| MarkupDrawingCanvas.swift | 131 | MARKUP 手绘/马赛克/文字覆盖层（归一化坐标） |
| AppSlider.swift | 71 | 全 app 统一滑杆（AppSliderTokens，此前只有 token 无组件） |

既有文件改动：`MediaPagerView.swift`（启用 Edit 按钮 → `.fullScreenCover` 进编辑页）；`Localizable.xcstrings`（+22 三语键）；`project.pbxproj`（xcodegen 重生成）。

## 2. 三栏验收

### ✅ 自动通过
| 项 | 证据 |
|----|------|
| iOS 编译 | `BUILD SUCCEEDED`（`generic/platform=iOS`，device）×2（xcstrings 改动前后各一） |
| 真机安装+启动 | iPhone 15（郭帅的iPhone）`devicectl install` + `process launch` 成功，无崩溃报告 |
| shared 回归 | `shared/` 零变更（git diff main..HEAD + 工作区均空）→ `:shared:jvmTest` N/A，无回归面 |
| i18n 三语 | xcstrings +22 键（en/zh-Hans/zh-Hant），简繁含用词差异（构图/調節/色溫/重設 等） |
| 红线 | PRIVACY：RecipeApplier 全程 Core Graphics/CoreImage 端侧，图片绝不上传 ✓ |

### ⚠️ 待真机终验（命令判不了，留用户）
- **编辑器屏实际 UI 渲染未截图验证**：受 iOS 截屏限制（idevicescreenshot 在 iOS 26 不可用；devicectl 无 screenshot 子命令；编辑页未接 in-app drawable 捕获），且需人工点按 Gallery→照片→Edit 才能到达编辑页。命令只验证了「app 含编辑器代码链接后启动无崩溃」，未验证编辑页 UI 本身。
- 各滤镜/调节的**观感对齐**（CIFilter 映射为近似值，非 Android GPU shader 精确对齐）。
- 标记手绘**跟手度**、预览缩放/长按对比手感。
- 性能体感（200ms debounce 预览在真机的实际延迟）。
- DEFER 按钮的 toast（去背景/AI 优化）触发表现。

### 📋 技术债清单（本轮 DEFER，登记供后续 follow）
| ID | 项 | 阻塞依赖 |
|----|----|---------|
| TD1 | BEAUTY 渲染（仅提示页，参数存档但不渲染） | iOS 静态图人脸感知 GPU 管线（PhotoProcessor actual）= 独立 follow |
| TD2 | 5 风格滤镜（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH） | Metal kernel（CoreImage 无直接等价） |
| TD3 | 抠图/去背景 + 透明棋盘格 + 证件照抽卡 | iOS matting(U2Net) 模型 |
| TD4 | AI 一键优化 | 远程推理协调 |
| TD5 | 对话式 ImageEditScreen / edit_image capability | 完整管线 + chat 集成 |
| TD6 | 配方 Room→GRDB 持久化（本轮内存态，重编辑不可恢复） | GRDB schema + PhotoEditRecipe 迁移 |
| TD7 | mosaic 真像素化/模糊（本轮降级为半透明粗线） | CoreImage 采样或 Metal |
| TD8 | FilterPanel 滤镜缩略图（本轮占位圆+文字） | assets filters/*.jpg 跨端拷贝或动态生成 |
| TD9 | 编辑器图标 MatIcon 资源化（本轮 SF Symbols） | mat_* SVG 字形补齐 + MaterialIconMap |
| TD10 | EditHistory 滑杆 spam（每 tick 入栈，.undo 逐值回退） | 改为 drag-end 提交 |
| TD11 | Adjust CIFilter 精确对齐 Android shader 数值 | 逐参数标定 |

## 3. Gap 分析（对抗式审查）

> 审查与实现交叉（铁律 4）。详见下「审查发现」节（reviewer 子代理独立对抗审查结果 + 修复状态）。

### 自查 🔴/🟡（实现者视角，诚实披露）
- 🟡 **TD10 EditHistory 滑杆 spam**：`updateRecipe` 每次调用入历史栈，滑杆拖动产生大量条目（30 条上限会驱逐早期历史）。功能可用但 undo 粒度差。
- 🟡 **Adjust 精度**：brightness/contrast/saturation/exposure/temperature/tint 的 CIFilter 映射为近似比例，非 Android GPU shader 的精确数值，同参数双端效果可能不一致（需真机观感核对）。
- 🟡 **CIColorMatrix intensity**：`filterColor` 的 `intensity` 参数本轮未做逐像素插值（>0 即全效果），与 Android 的强度渐变不一致。
- 🟡 **markup 烘焙 y 坐标**：CGContext 原点左下、CGImage 顶部对齐，doodle/text 坐标已做 `(1-y)` 翻转，但未真机验证标记位置与预览 overlay 完全重合（烘焙 vs overlay 双绘可能有微小偏差）。
- 🟡 **xcstrings 全文重排**：json.dump 重写了整个 xcstrings（5742 行），diff 巨大但内容正确（Xcode 会再归一化）。

### 审查发现（reviewer 子代理对抗审查，已闭环）

reviewer 独立通读 8 文件 + FilterColorMatrix/ThumbnailLoader/PhotoSaver，对每个结论先尝试反驳再确认。

**🔴 真实 bug —— 全部已修（重构建绿）**

| # | 问题 | 修复 |
|---|------|------|
| #1 | **保存用 ≤2048 预览缩略图渲染**，12MP 照片被永久降到 ~2048px（ThumbnailLoader aspectFill 还会裁剪）；返回的 savedUri 是源 id 非新副本 | `ThumbnailLoader.fullResolution`（PHImageManagerMaximumSize + aspectFit）重取全分辨率原图 → 全分辨率 CIImage 渲染 → 存相册 |
| #2 | **滑杆每 tick 入历史栈**，拖亮度 0→50 入栈数十条；undo 逐微增回退 = Adjust tab undo 失效 | dirty flag + 渲染完成时去抖入栈一次（离散/连续变更统一去抖提交） |
| #3 | **每次渲染 new CIContext**（昂贵、缓存失效），编辑器热路径 churn | 复用 `RecipeApplier.context`（共享单例） |

**🟡 parity/UX —— 高价值项已修**

| # | 问题 | 修复 |
|---|------|------|
| #4 | **烘焙文字比预览 overlay 低 ~0.2·fontSize**（textPosition 是基线，`py-fontSize/2` 偏低） | CTLineGetTypographicBounds 取 ascent/descent，`baselineY = py - (ascent-descent)/2` 居中 |
| #5 | **旋转按钮图标与行为相反**（数学产生 CW，图标却是 rotate.left/CCW） | 图标改 `rotate.right` 匹配 CW 行为 |
| #6 | **load 不可取消/重入**，切图时 stale render 可能覆盖新状态 | loadTask 可取消 + `renderGen` 代际守卫，load 起点取消 renderTask |
| #7 | **拖拽全程盖 spinner**（每 tick 置 isProcessing + 200ms debounce） | 实时编辑不再置 isProcessing，预览更新即反馈（PERF <100ms 交互反馈红线改善） |
| #9 | **自引用 dead extension** `Color(uiColor:)`（iOS 15+ 原生） | 删除 |

**🟡 未本轮处理（登记）**
- #8 **aspectCrop 在 90°/270° 旋转后比例语义可能与 Android 不一致**：reviewer 指出「不一定错，需对一例（3:4 图旋 90 点 4:3）双端对照 extent」。本轮未做 Android trace，登记 TD-verify。

**✅ reviewer 悲观验证为正确（无需改）**：CIColorMatrix bias `/255` 映射、4 行矩阵映射、doodle y-flip `(1-y)*h`、EditHistory 逻辑（push 后 `index-=1` 为 harmless dead code）、i18n 键齐、markup/zoom 对齐（resetZoom 兜底）、PHPhotoLibrary 主线程安全、无 force-unwrap、Task 无永久 retain cycle、filterIntensity 忽略在 lite 范围内诚实。

## 4. 偏离 spec 记录（实现决策）

| spec 设想 | 实际实现 | 原因 |
|-----------|---------|------|
| BEAUTY 复用相机 BeautyPanel（滑杆可用+提示） | BEAUTY tab = 纯提示页 | 相机 BeautyPanel 依赖相机专属类型；渲染 DEFER 下假可用滑杆无意义 |
| FILTER 9 色 + 5 风格 | 仅 9 色调 | 5 风格需 Metal kernel（CoreImage 无直接等价），TD2 登记 |
| 滤镜缩略图（assets filters/*.jpg） | 占位圆 + 文字 | 跨端拷贝缩略图资产未做，TD8 |
| MatIcon 图标 | SF Symbols | 编辑器图标无 mat_* SVG 资源，TD9 |
| mosaic PIXEL/BLUR | 半透明粗线降级 | 真像素化需采样，TD7 |

## 5. 断点续跑锚点

- `tmp/ios-follow/editor/state.json`（各阶段状态）
- `tmp/ios-follow/editor/follow-plan.md`、`contracts.md`、`specs/screens/editor.yaml`
- 续跑 `/ios-follow editor` 从第一个非 done 阶段续（本轮 Stage 0-4 done，Stage 5 进行中＝本报告 + 审查闭环）。
