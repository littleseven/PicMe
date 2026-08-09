# iOS 规格书驱动 UITest Gap 清单

> **创建时间**：2026-08-09
> **关联文件**：`specs/screens/camera.yaml`、`specs/screens/gallery-grid.yaml`
> **测试文件**：`iosApp/PoLangUITests/CameraSpecUITests.swift`、`GallerySpecUITests.swift`

本文件记录两类 gap：
1. **功能未实现**——规格书有定义但 iOS 代码尚未落地的验收点
2. **功能有但缺标识符**——功能存在但无 `accessibilityIdentifier`（或标识符挂容器导致子元素被覆盖），XCUITest 无法可靠验证

---

## 一、camera.yaml — 功能未实现

| 规格节 | 验收点 | iOS 状态 | 说明 |
|--------|--------|----------|------|
| §8 makeup_tab_content | 唇彩选择器（LipColorSelector） | ❌ 占位 | `MakeupPlaceholderContent` 仅显示 "Makeup (Phase 6)" 文字 |
| §8 makeup_tab_content | 腮红色系选择器 + 强度滑杆 | ❌ 占位 | 同上 |
| §9 style_filters | 5 款风格滤镜（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH） | ❌ 占位 | `StyleFilterPlaceholder` 显示锁标记，点击无效 |
| §10 ratio_panel | 比例选择器面板（4:3 / 16:9 / 全屏） | ❌ 未实现 | ratio 按钮（`mat_aspect_ratio`）存在但点击空操作 |
| §11 scene_panel | 场景选择器面板（无/夜景/月亮） | ❌ 未实现 | scene 按钮（`mat_landscape`）存在但点击空操作 |
| §12 grid_panel | 网格选择器面板（无/三分线/黄金比例） | ❌ 未实现 | grid 按钮（`mat_grid_on`）存在但点击空操作 |
| §6 composition_grid | 构图网格叠加（三分线/黄金比例虚线） | ❌ 未实现 | 无渲染逻辑 |
| §13 pro_mode_panel | ProMode 面板（白平衡/曝光/对比度/饱和度/色温） | ❌ 未实现 | pro 按钮（`mat_tune`）存在但点击空操作 |
| §14 floating_actions | 语音控制 FAB + AI Chat FAB | ❌ 未实现 | 无 FAB 组件 |
| §7 mode_selector | VIDEO 模式录像功能 | ❌ 未实现 | 模式标签可点击切换，但无录像逻辑 |
| §7 mode_selector | DOCUMENT 模式文档检测 | ❌ 未实现 | 同上 |
| §7 shutter_button | 录像态（红色内填 + 28dp 白色方块） | ❌ 未实现 | ShutterButton 仅支持拍照 |
| §5 focus_ring | 人脸检测联动对焦框 | ⚠️ 部分 | FocusCrosshairView 已实现点击对焦；人脸联动显示（FaceFocusCrosshair）未接线 |

---

## 二、camera.yaml — 功能有但缺标识符

| 规格节 | 控件 | 当前可测性 | 说明 |
|--------|------|-----------|------|
| §3 top_left_controls | 返回按钮（back_button） | ⚠️ 仅 label | `Button { MatIcon("chevron.left") }` 无 `accessibilityIdentifier`，可通过 MatIcon label `mat_arrow_back` 查询但不稳定 |
| §3 top_left_controls | 重置按钮（reset_button） | ⚠️ 仅 label | 同上，label `mat_refresh` |
| §3 top_right_controls | 比例按钮（ratio_switch） | ⚠️ 仅 label | label `mat_aspect_ratio`，点击空操作 |
| §3 top_right_controls | 网格按钮（grid_toggle） | ⚠️ 仅 label | label `mat_grid_on`，点击空操作 |
| §3 top_right_controls | 场景按钮（scene_mode） | ⚠️ 仅 label | label `mat_landscape`，点击空操作 |
| §3 top_right_controls | ProMode 按钮（pro_mode） | ⚠️ 仅 label | label `mat_tune`，点击空操作 |
| §5 focus_ring | 对焦十字星（FocusCrosshairView） | ❌ 不可测 | 无 `accessibilityIdentifier`，出现/消失无法通过 XCUITest 验证 |
| §7 mode_selector | 模式标签（VIDEO/PHOTO/DOCUMENT） | ⚠️ 仅 label | `Text(mode.rawValue)` + `onTapGesture`，无 `accessibilityIdentifier`；可通过文本 "照片"/"视频"/"文档" 查询但非 i18n 安全 |
| §7 mode_selector | i18n 标签对齐 | ❌ 偏差 | spec 要求 "VIDEO"/"PHOTO"/"DOCUMENT"（三语），iOS 硬编码中文 |
| §8 beauty_panel | 面板容器 | ❌ 不可测 | `BeautyPanelView` 无 `accessibilityIdentifier`，仅能通过内部滑杆间接验证 |
| §8 face_tab_sliders | 滑杆行（磨皮/美白/瘦脸/大眼） | ⚠️ 仅类型 | 滑杆无 `accessibilityIdentifier`，仅能通过 `app.sliders` count 验证数量 |
| §8 tab_bar | FACE Tab / MAKEUP Tab | ⚠️ 仅 label | `TabIconButton` 无 `accessibilityIdentifier`，通过 MatIcon label 查询 |
| §7 zoom_presets | 变焦预设按钮（0.6x/1x/2x/3.2x） | ⚠️ 仅 label | 父 HStack 的 `accessibilityIdentifier("camera_zoom_bar")` 传播到子 Button；Button 的 label（Text 内容）仍可查询但 identifier 被覆盖 |
| §17 panel_state_machine | 面板互斥逻辑 | ❌ 不可测 | 无法验证多面板不能同时弹出（需打开 ratio/scene/grid 面板，而这些面板未实现） |

---

## 三、gallery-grid.yaml — 功能未实现

| 规格节 | 验收点 | iOS 状态 | 说明 |
|--------|--------|----------|------|
| §5 search_top_bar | 搜索激活态顶栏 | ❌ 未实现 | `topbar_search` 按钮灰置，点击无效 |
| §6 scan_progress | TAG 扫描进度条 | ❌ 未实现 | `topbar_scan` 按钮灰置 |
| §7 thumbnail_item.video_indicator | 视频播放标记 | ⚠️ 部分 | `ThumbnailView` 有 `play.circle.fill` 图标，但无标识符 |
| §7 thumbnail_item.drag_multi_select | 拖拽多选 | ❌ 未实现 | 仅支持长按进入选择 + 逐个点击 |
| §8 splash_placeholder | 冷启动名言占位页 | ⚠️ 已实现 | `SplashPlaceholder` 存在且有 `gallery_splash` 标识符 |
| §10 search_no_result | 搜索无结果提示 | ❌ 未实现 | 依赖搜索功能 |
| §13-17 media_pager | 缩放手势（ZoomableImage） | ⚠️ 已实现 | `ZoomablePagerPage` 支持 pinch zoom 1-4x，但无法通过 XCUITest 验证缩放效果 |
| §17 top_controls | 人脸关键点叠层 | ✅ 已实现（debug 门控） | `MediaPagerView.swift:123-318`，`GalleryFaceOverlay`/`GalleryFaceFeedback`（`9cb910e1`/`8d4c40ec`） |
| §17 top_controls | 图像理解 / OCR 叠层 | ❌ 未实现 | `pager_more` 菜单项灰置（Phase 6） |
| §19 long_press | 长按打开编辑器 | ❌ 未实现 | 长按用于选择模式，不打开编辑器 |
| §20 photo_info_dialog | 照片信息弹窗（完整字段） | ⚠️ 部分 | `PhotoInfoSheet` 显示文件名/类型/时间/时长，缺 OCR/Vision/标签/美学评分 |
| §24 video_player | 视频播放 | ❌ 未实现 | `ZoomablePagerPage` 对视频项仅显示缩略图 |
| 分组模式 | FACE/PERSON/LANDSCAPE/LOCATION 分组 | ❌ 未实现 | 分组菜单中灰置 |
| §4 top_bar | 相簿列表入口 | ⚠️ 已实现 | `AlbumListView` 存在但无导航入口连接 |

---

## 四、gallery-grid.yaml — 功能有但缺标识符

| 规格节 | 控件 | 当前可测性 | 说明 |
|--------|--------|-----------|------|
| §7 group_header | 分组头标题 + 计数 | ⚠️ 仅 label | `GroupHeaderView` 有 `accessibilityIdentifier("group_\(title)")`，但标题来自数据（如 "2026-08-09"），非稳定标识 |
| §7 thumbnail_item | 缩略图项 | ⚠️ 仅 label | cell 有 `accessibilityIdentifier("cell_\(uri)")`，但 URI 为 PHAsset UUID，不可预测 |
| §13 media_pager | 大图页顶栏日期标签 | ❌ 不可测 | `Text(formattedDate(...))` 无 `accessibilityIdentifier` |
| §17 top_controls | 大图页所有顶栏/底栏按钮 | 🔴 标识符被覆盖 | **media_pager ZStack 的 `accessibilityIdentifier` 沿子树传播，覆盖了 pager_back / pager_info / pager_more / pager_share / pager_delete 等所有子元素标识符**。实测 a11y 树中所有按钮 identifier 均为 `media_pager`，仅 label（"返回"/"简介"/"发送"等）保留。UITest 通过系统 label 双语兜底查询。修复：将 `.accessibilityIdentifier("media_pager")` 从 ZStack 移到内部叶子或改用 `.accessibilityElement(children: .contain)` |
| §17 top_controls | 顶栏返回按钮日期 | ❌ 不可测 | 同上 |

---

## 五、建议补充的 accessibilityIdentifier（下一轮 App 改动时添加）

以下标识符添加后可显著提升 UITest 覆盖率（按优先级排序）：

1. **media_pager 标识符修复** — 将 `.accessibilityIdentifier("media_pager")` 从 `MediaPagerView` 的根 ZStack 移到内部叶子（最高优先级，传播覆盖了 5+ 子按钮标识符）
2. **camera_mode_photo / camera_mode_video / camera_mode_document** — 模式选择器标签
3. **beauty_panel** — 美颜面板容器
4. **beauty_slider_smoothing / beauty_slider_whitening / beauty_slider_slim_face / beauty_slider_big_eyes** — 美颜滑杆行
5. **beauty_tab_face / beauty_tab_makeup** — 美颜面板 Tab 按钮
6. **focus_ring** — 对焦十字星（对焦手势验证用）
7. **camera_back / camera_reset** — 顶部左侧按钮
8. **gallery_cell_first** — 网格首个缩略图稳定标识符（测试入口锚点）
