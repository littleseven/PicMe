# iOS 相机界面 Android↔iOS 逐元素结构对照

> ⚠️ **2026-08-08 快照（commit `83d70270`）**——相机视图层已于 2026-08-09 大幅重构（B1 批），本差距清单约 18/25 P0-P2 项已关闭。**当前验收以 [`2026-08-09-ios-ui-parity-spec.md`](../superpowers/specs/2026-08-09-ios-ui-parity-spec.md) 为合同**，本文仅作历史对照。漂移明细见 [`2026-08-10-ios-kmp-doc-drift-audit.md`](2026-08-10-ios-kmp-doc-drift-audit.md)。

> **产出日期**：2026-08-08  
> **基准**：Android `androidApp/` 相机 Compose 实现（当前 `main` 分支）  
> **被审对象**：iOS `.worktrees/ios-camera-track/iosApp/PoLang/Features/Camera/`  
> **目的**：用户实测反馈「iOS UI 样式完全跟 Android 端对不上」，按 Android 原貌逐元素审清 iOS 缺失/错位项，供后续重做  

---

## 0. 整体结构鸟瞰

### Android 相机页面（`CameraPreviewContent.kt`）垂直层级

```
┌──────────────────────────────────────────────┐
│  [左上] Back/Reset/Debug     [右上] 功能按钮列  │ ← TopStart + TopEnd
│                                ┌─────────────┤
│                                │ Beauty(开关) │
│                                │ Ratio        │
│                                │ Grid         │
│                                │ Scene        │
│                                │ Filter       │
│                                │ ProMode      │
│  ┌─────────────────────────┐  └─────────────┤
│  │                         │                 │
│  │      预览区（全屏）       │  [右下浮层]      │ ← BottomEnd FABs
│  │   网格 / 对焦十字星       │  Voice FAB      │
│  │                         │  AI Chat FAB    │
│  └─────────────────────────┘                 │
│                                              │
│  ┌──────────────────────────────────────┐   │ ← BottomCenter
│  │ [0.6x] [1x] [2x] [3.2x]              │   │
│  │      VIDEO  PHOTO  DOCUMENT          │   │
│  │  🖼️      ⚪(76dp快门)      🔄         │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  [底部弹出面板: 美颜/滤镜/比例/场景/网格/Pro]  │
└──────────────────────────────────────────────┘
```

### iOS 相机页面（`CameraPreviewView.swift`）垂直层级

```
┌──────────────────────────────────────────────┐
│  (空)                          (空)           │ ← 无顶部控件
│                                              │
│  ┌─────────────────────────┐                │
│  │                         │                │
│  │   MetalView 预览        │                │
│  │   对焦框(60pt黄色方框)   │                │
│  │                         │                │
│  └─────────────────────────┘                │
│                                              │
│  [滤镜横滑条 (仅展开时)]                      │
│  [美颜面板 (仅展开时, 4条滑杆)]               │
│                                              │
│  😊(美颜)    ⚪(72pt快门)    📷(滤镜)         │ ← 底部 HStack
└──────────────────────────────────────────────┘
```

**核心差异**：iOS 只有「底部一行三按钮 + 两个弹出面板」；Android 有完整的顶部双侧控件列 + 底部三行控件 + 浮动 FAB + 多种弹出面板。

---

## 1. 逐元素对照表

### 1.1 整体布局结构

| 维度 | Android | iOS | 判定 |
|------|---------|-----|------|
| **预览区** | 全屏 `Box(fillMaxSize)` 黑底，预览居中。比例可切（4:3=FILL_CENTER / FULL=FIT_CENTER）`CameraScreen.kt:629-637` | 全屏 `MetalViewRepresentable`，ZStack 底对齐 `CameraPreviewView.swift:48-49` | 🟡 预览全屏一致，但 iOS 无比例切换能力 |
| **背景色** | `Color.Black` `CameraPreviewContent.kt:109` | 无显式背景色（依赖 MetalView 默认清屏色） | 🟡 可接受 |
| **沉浸式** | 隐藏系统栏 `WindowInsetsControllerCompat.hide(systemBars)` `CameraScreen.kt:342` | 无系统栏隐藏代码 | 🔴 iOS 未实现沉浸式全屏 |
| **顶部区域** | 左侧 Back/Reset/Debug 按钮 + 右侧 6 个功能按钮，`statusBarsPadding` 避让 `CameraControlButtons.kt:54-176` | **完全空白** | 🔴 顶部双侧控件整体缺失 |
| **底部区域** | 3 行：变焦预设 + 模式选择 + 缩略图/快门/翻转 `CameraControls.kt:61-95` | 1 行：美颜/快门/滤镜 `CameraPreviewView.swift:69-100` | 🔴 底部控件行严重缺失（见 1.4） |
| **底部弹出面板** | 6 种面板：美颜/滤镜/比例/场景/网格/ProMode，统一 bottom-sheet 动画 `CameraPreviewContent.kt:177-557` | 2 种面板：美颜/滤镜 `CameraPreviewView.swift:59-67` | 🔴 缺 4 种面板 |
| **浮动 FAB** | 右下角竖排：语音控制 + AI Chat `CameraPreviewContent.kt:564-655` | **无** | 🔴 FAB 组整体缺失 |
| **面板互斥逻辑** | 同时只能开一个 Primary Panel（Filter/Ratio/Scene/Grid 互斥）+ 美颜面板独立 + ProMode 独立 `CameraScreenModels.kt:284-362` | 美颜和滤镜可同时展开（无互斥逻辑） | 🔴 交互逻辑差异 |
| **空白处点击关闭面板** | 有，点击预览区空白处关闭面板 `CameraPreviewContent.kt:111-120` | 无 | 🔴 缺失 |

### 1.2 右侧功能按钮列（Android CameraRightControls → iOS 完全缺失）

| 元素 | Android | iOS | 判定 |
|------|---------|-----|------|
| **美颜入口** | `BeautyEntryButton`：48dp FilledIconButton，`AutoFixHigh` 图标，面板开=primary底/黑字；美颜启用=primary 25%透明底；未启用=黑50%透明底。启用时右上角 8dp primary 小圆点标记 `CameraControlButtons.kt:180-222` | 底部 HStack 左侧 SF Symbol `face.smiling` 22pt 白色 44x44 `CameraPreviewView.swift:70-77` | 🔴 位置（右侧竖列→底部左侧）、样式（Material 填充图标→裸 SF Symbol）、状态指示（启用标记→无）全错位 |
| **比例选择** | `CropFree/Crop169/CropSquare/AspectRatio` 图标按钮，48dp，点击弹出 RatioSelector（4:3/16:9/FULL 三个按钮）`CameraControlButtons.kt:135-145`, `CameraBaseComponents.kt:123-147` | **不存在** | 🔴 完全缺失 |
| **网格选择** | `GridOn` 图标按钮，48dp，点击弹出 GridSelector（无/三分线/黄金比例）`CameraControlButtons.kt:146-151`, `CameraBaseComponents.kt:198-226` | **不存在** | 🔴 完全缺失 |
| **场景选择** | `Landscape` 图标按钮，48dp，点击弹出 SceneSelector（无/夜景/月亮）`CameraControlButtons.kt:155-160`, `CameraBaseComponents.kt:167-195` | **不存在** | 🔴 完全缺失 |
| **滤镜选择** | `FilterBAndW` 图标按钮，48dp，点击弹出 UnifiedFilterSelector（14 款 5 列网格）`CameraControlButtons.kt:161-166`, `FilterSelector.kt:50-125` | 底部 HStack 右侧 SF Symbol `camera.filters` 22pt 白色 44x44 `CameraPreviewView.swift:91-99` | 🔴 位置错位 + 弹出面板布局不同（见 1.3） |
| **Pro Mode** | `Tune` 图标按钮，48dp，点击弹出 ProModeControls（白平衡/曝光/对比度/饱和度/色温）`CameraControlButtons.kt:170-175`, `ProModeControls.kt:47-244` | **不存在** | 🔴 完全缺失 |

### 1.3 滤镜选择器

| 维度 | Android `UnifiedFilterSelector` (`FilterSelector.kt:50-125`) | iOS `FilterSelectorView` (`FilterSelectorView.swift:8-27`) | 判定 |
|------|---------|-----|------|
| **布局** | 5 列 `LazyVerticalGrid`，固定高度 280dp | 横向 `ScrollView(.horizontal)` | 🔴 网格→横滑条，布局方向完全不同 |
| **滤镜数量** | 14 款：9 色调（NONE/LEICA_CLASSIC/LEICA_VIBRANT/LEICA_BW/FILM_GOLD/FILM_FUJI/VINTAGE/COOL/WARM）+ 5 风格（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH）`FilterSelector.kt:62-77` | 9 款：仅色调滤镜（StyleFilter 在 `FilterSelectorView.swift:5` 注释明确「移 Phase 6」） | 🔴 缺 5 款风格滤镜 |
| **缩略图尺寸** | 圆形，`itemWidth = (screenWidth - 20dp) / 5`，image = `itemWidth * 0.72` `FilterSelector.kt:58-59,169` | 圆形 56pt `FilterSelectorView.swift:55` | 🟡 尺寸算法不同但视觉接近 |
| **选中态边框** | 2.5dp 渐变描边（`primary` → `onSurface`）`FilterSelector.kt:184-186` | 2.5pt 实色 `accentColor` 描边 `FilterSelectorView.swift:67-69` | 🟡 实色 vs 渐变，风格差异可接受 |
| **选中态遮罩** | primary 色 25% 透明遮罩 + Check 图标（primary 色）`FilterSelector.kt:232-243` | accentColor 20% 透明圆 + checkmark SF Symbol `FilterSelectorView.swift:73-78` | 🟡 风格差异可接受 |
| **选中态缩放** | 1.08x scale 动画 `FilterSelector.kt:168` | 1.08x scaleEffect 动画 `FilterSelectorView.swift:81` | ✅ 一致 |
| **名称样式** | 10sp，选中 Bold + primary 色，未选中 Medium + onSurface 85% `FilterSelector.kt:246-256` | 10pt，选中 bold，未选中 regular `FilterSelectorView.swift:84-87` | 🟡 颜色处理差异可接受 |

### 1.4 快门与底部拍照控件

| 元素 | Android | iOS | 判定 |
|------|---------|-----|------|
| **变焦预设条** | 胶囊形：`RoundedCornerShape(20dp)` + 黑40%透明底 + 12dp 内边距。按钮含 0.6x（设备支持时）/1x/2x/3.2x（设备支持时），32dp 圆形，选中=白底黑字 `CameraControls.kt:99-158` | **不存在** | 🔴 完全缺失 |
| **模式选择器** | 横排文字：VIDEO / PHOTO / DOCUMENT，当前模式=primary 色 Bold 13sp，其他=白60%透明 13sp `CameraControls.kt:161-193` | **不存在** | 🔴 完全缺失 |
| **相册缩略图** | 48dp 圆形，DarkGray 底，Coil AsyncImage 显示最近一张媒体 `CameraControls.kt:231-254` | **不存在** | 🔴 完全缺失（用户无法从相机直达相册） |
| **快门按钮** | 76dp 外框，4dp 白色 CircleShape 边框，6dp padding，内填白色（拍照）/红色（录像），录像态=28dp 白色圆角方块。500ms 防抖 `CameraControls.kt:196-228` | 72pt 外圆，4pt 白色描边，58pt 内填白色，按下 scale 0.85 动画 `ShutterButton.swift:14-33` | 🟡 尺寸接近（76 vs 72）；🔴 iOS 无录像态（红底/方块）、无防抖 |
| **翻转摄像头** | 48dp 圆形，白20%透明底，`Cameraswitch` 图标白色 `CameraControls.kt:257-267` | **不存在** | 🔴 完全缺失（用户无法前后摄切换） |
| **快门闪屏动画** | `Animatable(0f)` 黑色覆盖层，拍照时闪一下 `CameraScreen.kt:484` | **不存在** | 🔴 缺失 |
| **底部行间距** | Column `spacedBy(20dp)`，`bottom=20dp`, `navigationBarsPadding` `CameraControls.kt:62-67` | VStack `spacing: 12`，`padding(.horizontal, 24)`, `padding(.bottom, 24)` `CameraPreviewView.swift:58,101-102` | 🟡 间距差异 |

### 1.5 美颜面板

| 维度 | Android `BeautyPanel` (`BeautyPanel.kt:55-157`) | iOS `BeautyPanelView` (`BeautyPanelView.swift:4-34`) | 判定 |
|------|---------|-----|------|
| **面板高度** | `screenHeight * 0.35`（35%屏高），`heightIn(max = panelMaxHeight)` `BeautyPanel.kt:48,62-63` | 内容自适应高度（无固定高度约束） | 🔴 缺高度约束 |
| **背景** | 底部渐变（transparent→black55%→black82%）+ Surface 95% alpha，`RoundedCornerShape(topStart=24, topEnd=24)`，shadowElevation 16dp，0.5dp outlineVariant 边框 `BeautyPanel.kt:72-97` | `.ultraThinMaterial` + `RoundedCornerShape(cornerRadius: 16)` `BeautyPanelView.swift:31-32` | 🟡 材质差异（毛玻璃 vs 渐变+Surface），风格差异可接受；但圆角值不同（24 vs 16） |
| **拖拽手柄** | 36x4dp 胶囊形顶部居中 `BeautyPanel.kt:99-106` | **不存在** | 🔴 缺失 |
| **Tab 切换** | 底部 Tab 栏：FACE（`FaceRetouchingNatural` 图标）/ MAKEUP（`ColorLens` 图标），选中=primary 12% 透明底 `BeautyPanel.kt:122-153` | **不存在** | 🔴 无 Tab 切换，只有扁平 4 条滑杆 |
| **FACE Tab 滑杆** | 4 条：磨皮(0-100)/美白(0-100)/瘦脸(-50~50)/大眼(0-100)，每条=图标+标签+数值显示(`--`或数字)+`AppSlider` `BeautyPanel.kt:160-196` | 4 条：Smoothing(0-100)/Whitening(0-100)/Slim Face(-50~50)/Big Eyes(0-100)，仅标签+系统 `Slider`，无图标无数值 `BeautyPanelView.swift:9-28` | 🔴 滑杆范围一致(✅)，但缺图标/数值显示/重置交互 |
| **数值显示** | 滑杆右侧显示当前值（非零=primary 色数字，零=`--`灰色） `CameraBaseComponents.kt:338-347` | **不存在** | 🔴 缺失 |
| **图标** | 每条滑杆左侧 Material 图标（Face/AutoFixHigh/FaceRetouchingNatural/Visibility），非零=primary 色，零=灰色 `CameraBaseComponents.kt:319-328` | **不存在** | 🔴 缺失 |
| **点击图标重置** | 点击图标+标签行重置该项为 0 `CameraBaseComponents.kt:317` | **不存在** | 🔴 缺失 |
| **MAKEUP Tab** | 唇彩（12 色调色板+强度滑杆）、腮红（3 色系选择+强度滑杆）、眉毛（强度滑杆）`BeautyPanel.kt:198-239`, `ColorSelectors.kt:38-209` | **不存在** | 🔴 整个 MAKEUP Tab 缺失 |
| **唇彩调色板** | `LipColorSelector`：12 色 `LazyHorizontalGrid`(2 行)，选中=3dp primary 边框，+ 强度滑杆(0-100) `ColorSelectors.kt:110-209` | **不存在** | 🔴 缺失 |
| **腮红色系** | `BlushColorFamilySelector`：3 色系(粉/橙/梅)，圆点+标签胶囊 `ColorSelectors.kt:38-108` | **不存在** | 🔴 缺失 |
| **面板展开/收起** | `AnimatedVisibility` + `slideInVertically` + `fadeIn` `CameraPreviewContent.kt:196-207` | `withAnimation` + `.move(.bottom).combined(with: .opacity)` transition `CameraPreviewView.swift:63-66` | ✅ 动画方向一致（底部滑入） |
| **美颜启用/禁用开关** | `BeautyEntryButton` 支持两种状态：面板开关（长按）+ 美颜启用/禁用（点击），启用时显示角标 `CameraControlButtons.kt:180-222` | 美颜面板展开即调节参数，无总开关 | 🔴 缺独立的美颜总开关 |

### 1.6 手势与对焦层

| 元素 | Android | iOS | 判定 |
|------|---------|-----|------|
| **对焦框样式** | `FaceFocusCrosshair`：100dp 外框，4 角 L 型标记（20dp 拐角长度），primary 青色 `#00E5FF`，中心 16dp 十字 + 3dp 中心点。弹簧动画 `CameraOverlays.kt:397-516` | `FocusRingView`：60pt 方形，黄色 `RoundedRectangle(cornerRadius: 6)` 2pt 描边，缩放动画 1.4→1.0 `CameraGesturesView.swift:91-104` | 🔴 尺寸(100dp vs 60pt)、颜色(青 vs 黄)、形状(L 型角 vs 圆角方框)、动画(弹簧 vs ease-out)全不同 |
| **对焦框持续时间** | 由 `focusAlpha` 控制（人脸检测联动），弹簧动画淡出 `CameraOverlays.kt:406-415` | 0.2s 出现 + 1.2s 后 0.3s 淡出 `CameraGesturesView.swift:77-87` | 🟡 时序差异可接受 |
| **变焦交互** | 底部变焦预设按钮（0.6x/1x/2x/3.2x 点击切换） `CameraControls.kt:99-138` | 捏合手势 `MagnificationGesture`，无预设按钮 `CameraGesturesView.swift:32-42` | 🔴 变焦方式完全不同（按钮 vs 捏合），iOS 无可见变焦倍数 |
| **曝光补偿** | ProMode 面板中的 EV 滑杆 `ProModeControls.kt:163-182` | 垂直拖拽手势，拖拽距离映射到 [-2, +2] `CameraGesturesView.swift:45-53` | 🔴 交互方式不同（滑杆 vs 手势），且 iOS 无可见曝光 UI（无太阳图标/无刻度条/无数值） |
| **构图网格** | `CompositionGrid`：三分线/黄金比例，白色 50% 透明虚线 `CameraOverlays.kt:293-382` | **不存在** | 🔴 完全缺失 |
| **人脸检测可视化** | `FaceFocusCrosshair` 联动人脸检测，实时跟踪人脸位置 `CameraOverlays.kt:70-78` | **不存在**（人脸检测数据仅喂给渲染管线，不展示 UI） | 🔴 缺失 |

### 1.7 色彩与材质

| 维度 | Android | iOS | 判定 |
|------|---------|-----|------|
| **主题** | `PoLangForcedDarkTheme` 强制深色 scheme `CameraScreen.kt:370` | 无显式深色主题设置 | 🟡 可接受 |
| **主色调** | Material `colorScheme.primary`（teal/cyan 系） | SwiftUI `.accentColor`（默认蓝色） | 🟡 不同平台默认色，视觉差异 |
| **面板背景** | `surface.copy(alpha = 0.95)` + 底部渐变遮罩 `BeautyPanel.kt:77-97` | `.ultraThinMaterial`（毛玻璃） `BeautyPanelView.swift:31` | 🟡 材质不同，风格差异可接受 |
| **图标风格** | Material Icons（`Icons.Rounded.*`），24dp | SF Symbols，22pt | ✅ 平台原生图标差异可接受 |

### 1.8 Tab / 导航结构

| 维度 | Android | iOS | 判定 |
|------|---------|-----|------|
| **页面组织** | `HorizontalPager` 4 页全常驻：Camera(0) / Gallery(1) / Chat(2) / People(3) `MainPagerHost.kt:29-33,85-156` | `TabView` 2 个 tab：Gallery / Camera `MainTabView.swift:7-12` | 🔴 iOS 缺 Chat 页和 People 页 |
| **切换方式** | 横滑 Pager，拖动跟手 + 物理吸附 `MainPagerHost.kt:85-89` | 系统标准 TabView 底部 tab bar 点击切换 | 🔴 交互模式不同（横滑 vs tab bar） |
| **默认页** | Camera 是第 0 页（最左） `MainPagerHost.kt:29` | Gallery 是第 1 个 tab，Camera 是第 2 个 `MainTabView.swift:8-11` | 🔴 默认页不同 |
| **tab bar** | 无系统 tab bar，全手势横滑 | 底部标准 tab bar（photo.on.rectangle + camera） `MainTabView.swift:9,11` | 🔴 iOS 多了 tab bar 占底部空间 |
| **沉浸式** | 全屏 Pager 无 tab bar `MainPagerHost.kt:89` | tab bar 常驻 | 🔴 布局层级差异 |
| **页面常驻** | `beyondViewportPageCount = 3`，4 页全保活 `MainPagerHost.kt:87` | 标准 TabView 行为 | 🟡 状态保留差异 |

---

## 2. iOS 缺失的 Android 元素清单（按优先级）

### P0 — 核心交互缺失（用户可见功能性断裂）

| # | 缺失元素 | Android 参照 | 影响 |
|---|---------|-------------|------|
| 1 | **翻转摄像头按钮** | `CameraControls.kt:257-267` | 用户无法前后摄切换 |
| 2 | **相册缩略图入口** | `CameraControls.kt:231-254` | 用户无法从相机直达相册 |
| 3 | **变焦预设按钮条** | `CameraControls.kt:99-138` | 无快捷变焦（虽有捏合，但无倍数预设） |
| 4 | **快门录像态** | `CameraControls.kt:196-228` | 无 VIDEO 模式支持 |
| 5 | **美颜 Tab 切换（FACE/MAKEUP）** | `BeautyPanel.kt:50-53,122-153` | 缺整个 MAKEUP 功能（唇彩/腮红/眉毛） |
| 6 | **美颜滑杆图标 + 数值 + 重置** | `CameraBaseComponents.kt:294-358` | 滑杆无视觉反馈（不知当前值/不可一键重置） |
| 7 | **右侧功能按钮列** | `CameraControlButtons.kt:100-177` | 缺比例/网格/场景/ProMode 入口 |
| 8 | **比例选择器** | `CameraBaseComponents.kt:123-147` | 无法切换 4:3/16:9/FULL |
| 9 | **沉浸式全屏** | `CameraScreen.kt:333-354` | 状态栏/导航栏未隐藏 |

### P1 — 重要体验缺失

| # | 缺失元素 | Android 参照 | 影响 |
|---|---------|-------------|------|
| 10 | **对焦框样式对齐** | `CameraOverlays.kt:397-516` | 尺寸/颜色/形状全不对 |
| 11 | **风格滤镜（5 款）** | `FilterSelector.kt:69-77` | 缺 TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH |
| 12 | **滤镜面板布局** | `FilterSelector.kt:80-125` | iOS 横滑条 vs Android 5 列网格 |
| 13 | **构图网格叠加** | `CameraOverlays.kt:293-382` | 无三分线/黄金比例辅助线 |
| 14 | **ProMode 面板** | `ProModeControls.kt:47-244` | 缺白平衡/曝光/对比度/饱和度/色温调节 |
| 15 | **面板互斥逻辑** | `CameraScreenModels.kt:284-362` | 多面板可同时弹出造成遮挡 |
| 16 | **空白处点击关闭面板** | `CameraPreviewContent.kt:111-120` | 无法快速关闭面板 |
| 17 | **美颜拖拽手柄** | `BeautyPanel.kt:99-106` | 面板顶部无视觉拖拽指示 |
| 18 | **快门闪屏动画** | `CameraScreen.kt:484` | 拍照无全屏闪白反馈 |
| 19 | **美颜总开关** | `CameraControlButtons.kt:180-222` | 无一键开关美颜（只能逐项调零） |

### P2 — 辅助功能缺失

| # | 缺失元素 | Android 参照 | 影响 |
|---|---------|-------------|------|
| 20 | **AI Chat FAB** | `CameraPreviewContent.kt:642-654` | 缺 AI 语音助手入口 |
| 21 | **语音控制 FAB** | `CameraPreviewContent.kt:612-639` | 缺语音命令入口 |
| 22 | **场景预设（夜景/月亮）** | `CameraBaseComponents.kt:167-195` | 缺场景模式 |
| 23 | **左侧 Back/Reset/Debug** | `CameraControlButtons.kt:44-97` | 缺返回/重置/调试（Debug 可选） |
| 24 | **模式选择器（VIDEO/PHOTO/DOCUMENT）** | `CameraControls.kt:161-193` | 缺文档扫描模式 |
| 25 | **唤醒词指示器** | `CameraPreviewContent.kt:126-129` | 缺语音唤醒状态提示 |

---

## 3. iOS 多出来的 Android 没有的元素清单

| # | iOS 独有元素 | 位置 | 说明 | 建议 |
|---|-------------|------|------|------|
| 1 | **垂直拖拽曝光补偿手势** | `CameraGesturesView.swift:45-53` | Android 用 ProMode 滑杆做 EV，无拖拽手势 | 🟡 可保留作为 iOS 原生增强，但需增加可见 EV UI（太阳图标+刻度条+数值），否则用户不知道在调什么 |
| 2 | **捏合变焦手势** | `CameraGesturesView.swift:32-42` | Android 仅用预设按钮变焦，无捏合手势 | 🟡 可保留，但需同时加上 Android 的预设按钮条 |
| 3 | **底部 tab bar** | `MainTabView.swift:7-12` | Android 用横滑 Pager 无 tab bar | 🔴 应移除，改为 Android 风格横滑 Pager |
| 4 | **裸 SF Symbol 按钮（无背景容器）** | `CameraPreviewView.swift:70-99` | 美颜/滤镜入口是裸图标；Android 是 FilledIconButton（48dp 填充容器） | 🔴 应改为与 Android 一致的填充圆形按钮 |

---

## 4. 重做建议：逐文件对齐方案

### 4.1 `CameraPreviewView.swift` — 整体布局重构（最大改动）

**现状**：只有底部一行三按钮 + 两个弹出面板。  
**目标**：对齐 Android 的全屏 Box + 顶部双侧控件 + 底部三行控件 + FAB + 多面板。

**改动要点**：

1. **外层 ZStack 改为全屏 Box 布局**：
   - `.ignoresSafeArea()` 实现沉浸式全屏
   - `StatusBar` / `HomeIndicator` 隐藏（对应 Android `WindowInsetsControllerCompat.hide(systemBars)`）

2. **新增顶部左侧控件区**（对应 Android `CameraLeftControls`）：
   - ZStack `.overlay(alignment: .topLeading)` 或 `VStack(alignment: .leading)` 在 `.topLeading`
   - 返回按钮（`chevron.left` SF Symbol，48pt 圆形半透明底）
   - Debug 按钮（条件显示）

3. **新增顶部右侧控件列**（对应 Android `CameraRightControls`）：
   - ZStack `.overlay(alignment: .topTrailing)` 或 `VStack` 在 `.topTrailing`
   - 从上到下：美颜入口 / 比例 / 网格 / 场景 / 滤镜 / ProMode
   - 每个按钮 = 48pt 圆形 `FilledIconButton`（半透明黑底 + 白色 SF Symbol）
   - 选中态 = accentColor 底 + 黑色图标
   - 美颜入口需要总开关角标

4. **底部控件区重构**（对应 Android `CameraBottomControls`）：
   - VStack 底部从上到下：
     - 变焦预设条（0.6x/1x/2x/3.2x 胶囊形）
     - 模式选择器（VIDEO/PHOTO/DOCUMENT 文字横排）
     - HStack：相册缩略图 | 快门按钮(76pt) | 翻转摄像头按钮

5. **新增右下角 FAB 组**（对应 Android `CameraFloatingActionButtons`）：
   - `.overlay(alignment: .bottomTrailing)`
   - 竖排：语音控制 FAB + AI Chat FAB

6. **面板管理逻辑**：
   - 引入面板互斥状态机（同时只能开一个 Primary Panel）
   - 预览区空白处点击 = 关闭所有面板

7. **快门闪屏**：
   - 拍照时 ZStack 全屏黑色 opacity 0→1→0 动画

### 4.2 `BeautyPanelView.swift` — 从 4 条裸滑杆 → 完整双 Tab 面板

**现状**：4 条无图标无数值的系统 `Slider`，flat 布局。  
**目标**：对齐 Android `BeautyPanel` 的双 Tab + 带图标/数值/重置的滑杆 + MAKEUP 功能。

**改动要点**：

1. **面板容器**：
   - 固定高度 = `UIScreen.main.bounds.height * 0.35`
   - 顶部圆角 24pt（仅 top）+ 拖拽手柄（36x4pt 胶囊）
   - 背景：底部渐变遮罩 + 面板本体

2. **底部 Tab 栏**（`TabView` 或自定义 `segmentedControl`）：
   - FACE Tab：`face.smiling` 图标
   - MAKEUP Tab：`paintpalette` 图标

3. **FACE Tab 内容**（4 条滑杆，每条结构如下）：
   ```
   HStack {
     Button(reset) { HStack { icon; label } }    // 点击重置
     Spacer()
     Text(value ?? "--")                          // 数值显示
   }
   AppSlider(value, range)
   ```

4. **MAKEUP Tab 内容**：
   - **唇彩选择器**：12 色调色板（2 行 LazyVGrid）+ 强度滑杆
   - **腮红色系**：3 色系胶囊选择（粉/橙/梅）
   - **腮红滑杆**：0-100
   - **眉毛滑杆**：0-100

### 4.3 `FilterSelectorView.swift` — 从横滑条 → 5 列网格 + 风格滤镜

**现状**：横向 ScrollView，仅 9 款色调滤镜。  
**目标**：对齐 Android `UnifiedFilterSelector` 的 5 列网格 + 14 款滤镜。

**改动要点**：

1. **布局**：`ScrollView(.vertical)` + `LazyVGrid(columns: 5)`，固定高度 280pt
2. **新增风格滤镜**：TOON / SKETCH / POSTERIZE / EMBOSS / CROSSHATCH（需新增 StyleFilter 枚举 + shader 实现）
3. **选中态**：渐变描边（可用 `AngularGradient` 近似）+ 25% 透明遮罩 + checkmark

### 4.4 `ShutterButton.swift` — 尺寸微调 + 录像态 + 闪屏

**现状**：72pt 外圆，无录像态。  
**目标**：76pt + 录录态 + 防抖。

**改动要点**：
1. 外径 72→76pt
2. 新增 `isRecording` 状态：内圆变红 + 中心 28pt 白色圆角方块
3. 新增 500ms 防抖（`lastClickTime` 模式）

### 4.5 `CameraGesturesView.swift` — 对焦框重做

**现状**：60pt 黄色圆角方框。  
**目标**：100pt 青色 L 型角标记 + 中心十字。

**改动要点**：
1. **FocusRingView 重写**：
   - 尺寸 60→100pt
   - 颜色 黄→青色 `Color(red: 0, green: 0.9, blue: 1)`（近似 #00E5FF）
   - 形状 圆角方框→4 角 L 型线段（每角 20pt 拐角长度）
   - 新增中心 16pt 十字 + 3pt 中心点
   - 动画 ease-out→spring

2. **保留**捏合变焦 + 垂直拖拽曝光（作为 iOS 原生增强），但需同时添加可见 UI：
   - 变焦：在底部添加预设按钮条
   - 曝光：添加太阳图标 + 刻度条 + 数值显示

### 4.6 `MainTabView.swift` — 从 TabView → 横滑 Pager

**现状**：系统 `TabView`（Gallery + Camera）。  
**目标**：对齐 Android 的 `HorizontalPager`（Camera + Gallery + Chat + People）。

**改动要点**：
1. 替换 `TabView` → 自定义横滑 Pager（`UIPageViewController` wrapper 或第三方库）
2. 页面顺序：Camera(0) / Gallery(1) / Chat(2) / People(3)
3. 默认页 = Camera
4. 移除底部 tab bar
5. 全屏 `.ignoresSafeArea()`
6. Chat / People 页后续 Phase 再做，先加 Camera + Gallery 两页横滑

---

## 5. 速查矩阵：iOS 文件 × 需改程度

| iOS 文件 | 改动程度 | 核心改动 |
|----------|---------|---------|
| `CameraPreviewView.swift` | 🔴 **大改** | 整体布局重构：加顶部双侧控件 + 底部三行 + FAB + 沉浸式 |
| `BeautyPanelView.swift` | 🔴 **大改** | 从 4 条裸滑杆 → 双 Tab 面板 + 图标/数值/重置 + MAKEUP |
| `FilterSelectorView.swift` | 🟡 **中改** | 横滑条 → 5 列网格 + 新增 5 款风格滤镜 |
| `ShutterButton.swift` | 🟡 **小改** | 尺寸 72→76 + 录像态 + 防抖 |
| `CameraGesturesView.swift` | 🟡 **中改** | 对焦框样式重做（尺寸/颜色/形状） |
| `MainTabView.swift` | 🔴 **大改** | TabView → 横滑 Pager + 页面重组 |
| `FilterColorMatrix.swift` | ✅ 不改 | 矩阵值已逐值照抄 Android |
| `BeautyRenderer.swift` | ✅ 不改 | 渲染管线不在本次 UI 对齐范围 |
| `BeautyUniforms.swift` | ✅ 不改 | shader uniform 结构不变 |
| `CaptureSessionController.swift` | ✅ 不改 | 相机会话控制不在本次范围 |
| `PhotoCaptureController.swift` | ✅ 不改 | 拍照逻辑不在本次范围 |
