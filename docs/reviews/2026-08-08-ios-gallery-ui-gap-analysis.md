# iOS 相册界面 Android↔iOS 逐元素结构对照

> ⚠️ **2026-08-08 快照（commit `83d70270`）**——相册视图层已于后续大幅重构，本差距清单约 16/26 🔴 项已关闭（含选择模式/人脸感知裁切/顶底栏/信息弹窗/空态/缩放翻页）。**当前验收以 [`2026-08-09-ios-ui-parity-spec.md`](../superpowers/specs/2026-08-09-ios-ui-parity-spec.md) 为合同**，本文仅作历史对照。漂移明细见 [`2026-08-10-ios-kmp-doc-drift-audit.md`](2026-08-10-ios-kmp-doc-drift-audit.md)。

> **日期**：2026-08-08
> **基准（Android）**：`androidApp/` Compose 实现（master `bf97033e`）
> **被审对象（iOS）**：`.worktrees/ios-camera-track/iosApp/PoLang/Features/Gallery/`（SwiftUI，commit `83d70270`）
> **红线依据**：S5 双端体验一致为最高原则
> **判定标准**：✅ 对齐 / 🟡 有差异但不致命 / 🔴 缺失或严重不一致

---

## 1. 逐元素对照总表

### 1.1 网格页（Grid）

| # | 元素 | Android | iOS | 判定 |
|---|------|---------|-----|------|
| 1 | 列数策略 | `GridCells.Adaptive(110.dp)`——自适应填满屏宽，大屏自动多列 | 固定 3 列 `GridItem(.flexible()) × 3` | 🟡 |
| 2 | 列/行间距 | `spacedBy(2.dp)` 水平 + 垂直；`contentPadding = 2.dp` | `spacing: 2` 列间 + 行间 | ✅ |
| 3 | 缩略图宽高比 | `aspectRatio(1f)` 正方形 | `CGSize(200, 200)` 固定正方形 | ✅ |
| 4 | 缩略图圆角 | `RoundedCornerShape(2.dp)` (`ThumbnailCornerRadius`) | 无圆角（`.clipped()` 直角） | 🟡 |
| 5 | 缩略图裁切 | `ContentScale.Crop` + **人脸感知纵向对齐** `faceAwareVerticalAlignment(faceFocusY)` | `scaledToFill()`（=Crop）无对齐 | 🔴 |
| 6 | 视频标记 | `PlayCircle` 图标 32dp 白色 80%，居中 | 无 | 🔴 |
| 7 | 选中态 | 长按进入选择模式：黑色 30% 遮罩 + `CheckCircle`/`RadioButtonUnchecked` 24dp 右上角 + 拖拽多选 | 无选择模式 | 🔴 |
| 8 | 日期分组头 | `MediaGroupHeader`：标题(`titleMedium` 加粗 `primary` 色) + `(N)` 计数(`bodyMedium` `secondary` 色)，整行可点击，padding 16dp/8dp | `Section(header: Text(group.id))`：纯文本，padding 8，背景色。**无计数** | 🔴 |
| 9 | 分组模式 | DATE / FACE / PERSON / LANDSCAPE / LOCATION / NONE 六种（顶栏 Sort 菜单切换） | 仅 DATE 一种（硬编码 `applyGrouping`） | 🔴 |
| 10 | 滚动行为 | `LazyVerticalGrid`，分组头 `GridItemSpan(maxLineSpan)` 作为 sticky-like item（随滚动） | `LazyVGrid(pinnedViews: .sectionHeaders)` 分组头吸附 | 🟡 |
| 11 | 顶栏 | 自建 `AppTopBar`（48dp、17sp 标题、状态栏避让）：标题"相册" + 操作组：模型中心 / 扫描开关 / 搜索 / 分组菜单 / 设置 | 系统 `NavigationStack` 默认大标题栏 + 右上角 "Albums" 文字按钮 | 🔴 |
| 12 | 底部导航 | `FloatingBottomTab`（圆角胶囊悬浮）：相机 / 聊天 / 打标 / 人物 四入口 | 系统 `TabView`（`MainTabView`）：相册 / 相机 两 tab | 🔴 |
| 13 | 搜索 | 顶栏搜索图标 → `SearchTopBar`（内嵌搜索框 + 结果计数），300ms 防抖，VLM 语义搜索 | 无搜索 | 🔴 |
| 14 | TAG 扫描状态 | 顶栏扫描进度指示（`LinearProgressIndicator` + 扫描/暂停切换按钮） | 无 | 🔴 |

### 1.2 大图页（Media Pager / 详情页）

| # | 元素 | Android | iOS | 判定 |
|---|------|---------|-----|------|
| 15 | 打开转场 | `scaleIn` 从缩略图位置缩放展开（0.2f → 1.0f，`TransformOrigin` 来自缩略图 Rect），+ `fadeIn` | 系统 `NavigationLink` push（左滑入） | 🔴 |
| 16 | 关闭转场 | `scaleOut` + `fadeOut`，原路缩回 | 系统 pop（右滑出） | 🔴 |
| 17 | 图片占满比例 | `fillMaxSize` 黑色背景，`ContentScale.Fit` | `TabView.page` 黑色背景，`scaledToFit` | ✅ |
| 18 | 页间距 | `pageSpacing = 16.dp` | 无（系统默认） | 🟡 |
| 19 | 缩放手势 | `ZoomableImage`：双指缩放 1–4x + 平移 + 边界 clamp + 缩放时禁用横滑 | 无缩放 | 🔴 |
| 20 | 视频播放 | `VideoPlayer`：ExoPlayer + `PlayerView` 控制条 | 无视频支持（仅静态图） | 🔴 |
| 21 | 顶部栏 | 动画显隐（点击切换）：返回箭头 + 日期 + 信息图标 + 更多菜单（图像理解 / OCR / 人脸关键点） | 无顶部栏（依赖系统返回） | 🔴 |
| 22 | 底部操作栏 | 动画显隐：发送(分享) / 编辑 / 证件照 / 删除，每项 icon+label | 无底部操作栏 | 🔴 |
| 23 | 点击切换栏 | 单击图片 → `showBarsVisible` toggle | 无 | 🔴 |
| 24 | 长按手势 | 长按 → 进入照片编辑器 + 触觉反馈 | 无 | 🔴 |
| 25 | 照片信息弹窗 | `PhotoInfoDialog`：文件名/类型/日期/时长/来源/位置(可点击跳地图)/美学评分/人脸信息/标签分组(FlowRow)/OCR 文本 | 无 | 🔴 |
| 26 | OCR 叠层 | `OcrResultOverlay`：识别结果弹窗 + 复制/分享 | 无 | 🔴 |
| 27 | 图像理解叠层 | `VisionResultOverlay`：Markdown 渲染 + 复制/分享 | 无 | 🔴 |
| 28 | 人脸关键点叠层 | `FaceLandmarkCanvasOverlay`（debug 模式） | 无 | 🔴 |
| 29 | 底部缩略图条 | 无（Android 也没有底部胶片条） | 无 | ✅ |
| 30 | 分页指示器 | 无（无页码点） | 系统默认 page dots（`indexDisplayMode: .automatic`） | 🟡 |

### 1.3 相簿列表（Album List）

| # | 元素 | Android | iOS | 判定 |
|---|------|---------|-----|------|
| 31 | 是否存在 | **不存在**——Android 无独立相簿列表页，通过顶栏分组菜单实现等价功能 | 有（`AlbumListView`），从顶栏 "Albums" push 进入 | 🟡 |
| 32 | 列表项排版 | N/A | `List`：`HStack { 标题 / Spacer / 计数(.secondary) }`，**无封面图** | 🟡 |
| 33 | 排序 | N/A | 系统相簿在前 + 用户相簿在后（fetch 顺序，无显式排序） | 🟡 |

> **注**：Android 没有独立相簿页。iOS 的 `AlbumListView` 是额外加的，但与 Android 体验不构成"对齐缺失"——它是 iOS-only 功能。真正的对齐目标是：Android 的分组菜单（DATE/FACE/PERSON/LANDSCAPE/LOCATION）在 iOS 上完全缺失。

### 1.4 权限 UI（Permission）

| # | 元素 | Android | iOS | 判定 |
|---|------|---------|-----|------|
| 34 | 权限态数量 | 2 态（有权限 / 无权限） | 5 态（full / limited / addOnly / denied / notDetermined） | 🟡 |
| 35 | 未授权空态 | `GalleryPermissionMessage`：居中图标(`PhotoLibrary` 64dp primary) + 标题(`headlineSmall`) + 描述(`bodyMedium`) + 按钮 | `notDetermined`：仅一个按钮"Authorize Photo Access"；`denied`：文字+按钮；均**无图标无标题** | 🔴 |
| 36 | Denied 态 | 无（Android 权限被拒后回到请求态） | 文字 "Photo Library Unavailable" + "Open Settings" 按钮 | ✅（iOS 独有，合理） |
| 37 | Limited 态 | 无（Android 无 Limited 概念） | 网格 + "Manage Accessible Photos" 按钮 banner | ✅（iOS 独有，合理） |
| 38 | AddOnly 态 | 无 | 仅纯文字 "Add-Only Access Hint"，无引导操作 | 🔴 |

### 1.5 空态/加载态

| # | 元素 | Android | iOS | 判定 |
|---|------|---------|-----|------|
| 39 | 冷启动占位 | `GallerySplashPlaceholder`：名人名言（衬线斜体 20sp），按 locale 取池，`rememberSaveable` 防重建闪烁，顶部 28% 留白 | 无 | 🔴 |
| 40 | 加载指示 | 数据加载中隐式走 SplashPlaceholder（已覆盖） | `isLoading` 状态存在但 **UI 从未消费**——加载中直接渲染空网格 | 🔴 |
| 41 | 空相册 | `EmptyGalleryMessage`：居中文字"未找到任何媒体文件" | 无——空数据直接空白 | 🔴 |
| 42 | 搜索无结果 | `EmptyGalleryMessage("未找到匹配 \"$query\" 的照片")` | 无搜索功能 | N/A |

### 1.6 色彩与材质与字体

| # | 元素 | Android | iOS | 判定 |
|---|------|---------|-----|------|
| 43 | 顶栏背景 | `MaterialTheme.colorScheme.surface`（48dp 自建 `AppTopBar`） | 系统 NavigationStack 默认（系统半透明材质） | 🔴 |
| 44 | 顶栏标题字号 | 17sp Medium（统一 `AppTopBar`） | 系统默认大标题（不可控） | 🟡 |
| 45 | 分组头字体 | `titleMedium` + Bold + `primary` 色 | 系统默认 `.body`，无颜色区分 | 🔴 |
| 46 | 缩略图占位色 | `MaterialTheme.colorScheme.surface`（`ColorPainter`，防深色闪烁） | `Color.gray.opacity(0.2)` | 🟡 |
| 47 | 底部 Tab 材质 | `surface` + `tonalElevation 3dp` + `shadowElevation 6dp`，圆角 28dp 胶囊 | 系统 `TabView` 底栏（系统毛玻璃） | 🔴 |

---

## 2. iOS 缺失元素清单（按优先级排序）

### P0 — 体验断裂（必须补齐）

| 优先级 | 缺失元素 | 影响 | 对应 Android |
|--------|----------|------|-------------|
| P0-1 | **大图页顶部/底部操作栏** | 用户打开大图后无法返回、分享、编辑、删除——核心交互链断裂 | `MediaPager.kt:243-306` `mediaPagerTopControls` / `mediaPagerBottomBar` |
| P0-2 | **大图页缩放手势** | 无法放大看细节 | `MediaPager.kt:344-417` `ZoomableImage` |
| P0-3 | **缩略图人脸感知对齐** | 人像照片被裁到头顶/下巴（"砍头杀"） | `MediaGrid.kt:212,249` `faceAwareVerticalAlignment(faceFocusY)` |
| P0-4 | **冷启动占位页** | Room 查询返回前直接空网格闪烁 | `GalleryPermission.kt:122-184` `GallerySplashPlaceholder` |
| P0-5 | **顶栏操作组** | 无法搜索、切换分组、跳设置/模型中心 | `GalleryTopBar.kt:89-115` |

### P1 — 功能缺失（应补齐）

| 优先级 | 缺失元素 | 影响 | 对应 Android |
|--------|----------|------|-------------|
| P1-1 | **视频标记与播放** | 视频混在网格中无标识，大图页无法播放 | `MediaGrid.kt:254-263` + `MediaPager.kt:189,1518-1548` |
| P1-2 | **选择模式（多选/删除/分享）** | 无法批量操作 | `MediaGrid.kt:265-301` `SelectionOverlay` + `GalleryScreen.kt` 选择态 |
| P1-3 | **拖拽多选** | 无法滑动选择多张 | `MediaGrid.kt:131-157` `detectDragGestures` |
| P1-4 | **照片信息弹窗** | 无法查看 EXIF / 标签 / OCR / 美学评分 | `MediaPager.kt:1062-1384` `PhotoInfoDialog` |
| P1-5 | **分组模式（FACE/PERSON/LANDSCAPE/LOCATION）** | 只有日期分组，无人脸/人物/风景/位置分组 | `GalleryTopBar.kt:140-180` `GroupingMenu` + `GalleryUtils.kt:13-33` |
| P1-6 | **搜索功能** | 无法按标签/语义搜索照片 | `SearchTopBar.kt` + `GalleryScreen.kt:168-187` |
| P1-7 | **日期分组计数** | 分组头不显示照片数量 | `MediaGroupHeader.kt:37` `(N)` |
| P1-8 | **空态/无搜索结果提示** | 无数据时无反馈 | `GalleryPermission.kt:107-114` `EmptyGalleryMessage` |

### P2 — 体验差异（建议补齐）

| 优先级 | 缺失元素 | 影响 | 对应 Android |
|--------|----------|------|-------------|
| P2-1 | **悬浮底部 Tab**（相机/聊天/打标/人物） | iOS 用系统 TabView，入口数量与层级不同 | `GalleryScreen.kt:840-871` `FloatingBottomTab` |
| P2-2 | **大图页打开/关闭缩放转场** | 体验断裂（push 替代缩放展开） | `GalleryScreen.kt:873-894` `scaleIn/scaleOut` |
| P2-3 | **TAG 扫描进度条** | 网格顶部无扫描状态指示 | `GalleryScreen.kt:662-665` `LinearProgressIndicator` |
| P2-4 | **缩略图圆角** | 直角 vs 2dp 圆角 | `MediaGrid.kt:195,230` `ThumbnailCornerRadius = 2.dp` |

---

## 3. iOS 多余元素清单

| 元素 | 位置 | 说明 |
|------|------|------|
| `AlbumListView` | `AlbumListView.swift` | Android 无独立相簿列表页——非多余，但定位需重新考虑（Android 用分组菜单替代） |
| 系统 page dots | `MediaPagerView.swift:21` | `.page(indexDisplayMode: .automatic)` 显示页码点，Android 无此指示器 |
| 系统 NavigationStack 大标题 | `GalleryGridView.swift:20,38` | Android 用自建 48dp `AppTopBar`，大标题风格完全不同 |

---

## 4. 重做建议：逐文件对齐方案

### 4.1 `GalleryGridView.swift` → 对齐 Android `GalleryScreen.kt` + `MediaGrid.kt`

**当前问题**：系统 NavigationStack + 固定 3 列 + 纯文本分组头 + 无顶栏操作组 + 无底部 Tab。

**改动方案**（视图层级从外到内）：

```
ZStack {
  ├─ VStack {
  │   ├─ 自建 TopBar (48dp, surface bg) — 替换 NavigationStack
  │   │   ├─ 左：标题"相册"（17sp Medium）
  │   │   └─ 右：操作组 HStack
  │   │       ├─ 扫描开关 (PlayArrow/Pause)
  │   │       ├─ 搜索 (Search)
  │   │       ├─ 分组菜单 (Sort → Menu: DATE/FACE/PERSON/...)
  │   │       └─ 设置 (Settings)
  │   ├─ if isScanning { LinearProgressIndicator }
  │   └─ ScrollView {
  │       LazyVGrid(columns: Adaptive, spacing: 2, pinnedHeaders) {
  │           Section(header: MediaGroupHeader) {  ← 见 4.2
  │               ForEach(items) { MediaItem }     ← 见 4.3
  │           }
  │       }
  │   }
  ├─ FloatingBottomTab (.bottomBar, overlay)       ← 见 4.6
  └─ if selectedMediaIndex != nil { MediaPagerView } (scale transition)
}
```

关键改动：
1. **去掉 `NavigationStack`**，改自建顶栏（48dp HStack），与 Android `AppTopBar` 一致
2. **列数改为 Adaptive**：用 `GridItem(.adaptive(minimum: 110))` 替代固定 3 列
3. **分组头改造**：见 4.2
4. **补冷启动占位**：见 4.4
5. **补底部 Tab**：见 4.6
6. **`isLoading` 态消费**：加载中显占位页，加载完切网格

### 4.2 日期分组头 → 对齐 `MediaGroupHeader.kt:16-42`

**当前**（`GalleryGridView.swift:54-57`）：
```swift
Section(header: Text(group.id)
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(8).background(.background))
```

**改为**：
```swift
Section(header: GroupHeaderView(title: group.id, count: group.items.count))

// 新建：
struct GroupHeaderView: View {
    let title: String; let count: Int
    var body: some View {
        HStack {
            Text(title)
                .font(.title3.bold())
                .foregroundColor(.primary)      // ← 对齐 primary 色
            Text("(\(count))")
                .font(.body)
                .foregroundColor(.secondary)    // ← 计数 secondary 色
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
    }
}
```

### 4.3 `ThumbnailView.swift` → 对齐 `MediaGrid.kt:202-269` MediaItem

**当前缺失**：圆角、视频标记、选中态、人脸感知对齐。

**改动**：
1. 加圆角：`.clipShape(RoundedRectangle(cornerRadius: 2))`
2. 视频标记：`if asset.type == .video { Image(systemName: "play.circle.fill")... }`
3. 选中态：`if isSelectionMode { ZStack { 黑色 0.3 遮罩 + CheckCircle 右上角 } }`
4. 人脸感知对齐：需要在 `ThumbnailLoader` 中读取 `faceFocusY` 并调整裁切区域（PHImageManager `normalizedCropRect`），或改用 `Image` 的 `.alignment Guide`

### 4.4 冷启动占位 → 对齐 `GalleryPermission.kt:122-184` GallerySplashPlaceholder

在 `GalleryGridView` 的 `isLoading == true` 分支中：
```swift
if vm.isLoading {
    VStack {
        Spacer().frame(height: geo.size.height * 0.28)
        Text("\"\(quote.text)\"").font(.system(.body, design: .serif)).italic()
        Text("— \(quote.author)").font(.system(.subheadline, design: .serif))
    }
}
```
（iOS 无需 `rememberSaveable` 等价，SwiftUI `@State` 天然跨 body 重建稳定）

### 4.5 `MediaPagerView.swift` → 对齐 `MediaPager.kt:135-341`

**当前**：仅 TabView + 全图，无任何控件。

**改动**（视图层级）：
```
ZStack {
  ├─ TabView(.page) { ZoomableImage }          ← 加缩放手势
  ├─ VStack(.top) { TopControls }               ← 动画显隐
  │   ├─ HStack { 返回 + 日期 }        (左)
  │   └─ HStack { Info + 更多菜单 }    (右)
  ├─ VStack(.bottom) { BottomBar }              ← 动画显隐，仅照片
  │   └─ HStack { 分享 / 编辑 / 证件照 / 删除 }
  ├─ if showInfo { PhotoInfoSheet }
  └─ if isZoomed { 隐藏顶/底栏 }
}
```

具体：
1. **缩放手势**：用 `MagnificationGesture` + `DragGesture` 包裹 `Image`，clamp 到 1–4x
2. **顶栏**：自建 48dp 黑色半透明栏（`Color.black.opacity(0.85)`），左返回+日期，右信息+菜单
3. **底栏**：黑色半透明栏，`HStack(spacing: .evenly)` 四个 icon+label 按钮
4. **点击切换栏显隐**：`onTapGesture { showBars.toggle() }`
5. **转场**：如可能，用 `.matchedGeometryEffect` 或自定义 transition 替代 NavigationLink push

### 4.6 悬浮底部 Tab → 对齐 `FloatingBottomTab.kt`

替换 iOS 系统 `TabView`（在 `MainTabView.swift` 中），改为：
```swift
ZStack {
    GalleryGridView(...)    // 或 CameraPreviewView，由状态控制
    VStack {
        Spacer()
        FloatingTabBar(items: [
            (.camera, "相机"), (.chat, "聊天"), (.tag, "打标"), (.people, "人物")
        ])
        .padding(.bottom, 16)
    }
}
```
胶囊容器：`RoundedRectangle(cornerRadius: 28)` + `shadow` + `Material.surface`

### 4.7 `GalleryViewModel.swift` → 对齐 Android 分组模式

**当前**：仅按日分组（`applyGrouping`）。

**改动**：
1. 新增 `groupingMode` 属性（enum: date / face / person / landscape / location / none）
2. `applyGrouping` 按 mode 分支执行不同分组逻辑
3. Android 分组在 `MediaViewModel` 中执行，shared 层提供数据源——iOS 需在 ViewModel 层补齐等价逻辑（或调用 shared 的分组用例，如果已暴露）

---

## 5. 工作区差异说明

`ios-camera-track`（本文审查对象，commit `83d70270`）与 `ios-gallery`（commit `f3b1aeab`）存在增量差异——`ios-gallery` 包含以下修复标记：
- 🟡-3：`onAppear` 多次触发时先取消旧订阅（`GalleryViewModel`）
- 🟡-4：`addOnly` 态复合映射（`GalleryPermissionStore`）
- 🟡-6：Limited banner 用 VStack 防 ScrollView 挤出（`GalleryGridView`）
- 🟡-8：大图高清档加载（`MediaPagerView`）
- 🟡-9：相簿列表后台取数（`AlbumListView`）

这些是**锦上添花的 bug fix**，不改变上述布局/信息层级的根本差距。如果最终落地基于 `ios-gallery` 分支，上述 P0/P1 改动清单仍然全部适用。

---

## 6. 总结

iOS 相册界面目前是一个**功能骨架**：能看网格、能翻大图、有权限四态框架。但与 Android 对比，**信息层级、操作密度、交互完整度全面缺失**：

- 网格页：无顶栏操作组、无搜索、无分组切换、无底部 Tab、分组头无计数、无冷启动占位
- 大图页：无顶/底操作栏、无缩放、无信息弹窗、无 OCR/Vision、无视频播放
- 整体：无人脸感知裁切、无选择模式、无 TAG 扫描状态

**根因**：iOS 由 AI 按 spec 文字实现，spec 描述了数据流和权限态，但未逐像素描述 UI 布局——AI 回退到 SwiftUI 默认控件（NavigationStack / TabView / List），与 Android 自建的紧凑 `AppTopBar` / `FloatingBottomTab` / `MediaPager` 控件体系完全不匹配。

**修复策略**：不建议逐项打补丁，建议以 Android 为基准**整体重写 `GalleryGridView` 和 `MediaPagerView` 的视图层**，自建等效控件，放弃系统默认导航/Tab 模式。
