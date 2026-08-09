# iOS 相机/相册 功能深化（Phase 6.6）落地计划

> **性质**：Phase 6.6「功能深化对齐」的相机/相册轨细粒度计划（roadmap 修订十五增补 §6.6 的子计划）。
> **日期**：2026-08-09 · **产出方**：Claude（规划+审查），执行派 kimi-code 实例
> **上游 SSOT**：`specs/screens/camera.yaml`、`specs/screens/gallery-grid.yaml`、`specs/2026-08-09-ios-spec-test-gaps.md`、`docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md`
> **现状基线**：2026-08-09 codebase 全量盘点（Explore agent 产出，见 §1）

---

## 0. 范围与原则

- **范围内**：相机/相册规格书中**不依赖 6.1 TAG/VLM 索引**的 gap 项（见 §3 阻塞清单）。
- **轨归属**：相机段 = **GLM**（Swift/Metal/AVFoundation）；相册段 = **K3**（Swift UI + shared 消费）。两段文件域不交叉，可真并行。
- **对齐 SSOT**：照 `camera.yaml` / `gallery-grid.yaml` 写代码，不读 Android 源码（spec 已固化）。**spec 中的 `🔴 完全缺失` 标记部分已过时**（Phase 5.4 后 flip/zoom/gallery_thumb/shutter_flash/media_pager 顶底栏已存在）——以 §1 现状基线 + `ios-spec-test-gaps.md` 为准。
- **红线**：[PRIVACY] 媒体 100% 端侧（相机帧/相册图不外传）；[PERF] 交互 <100ms、快门 <50ms；[I18N] 三语同步（⚠️ zh-Hant 全局缺失，见 §5 单列）。

---

## 1. 现状基线（2026-08-09 盘点，已对照 spec gap）

### 相机域
| 能力 | 现状 | gap 来源 |
|---|---|---|
| 比例切换（4:3/16:9/FULL） | 按钮在，**点击空操作**，无面板 | camera.yaml §10 |
| 场景（无/夜景/月亮） | 按钮在，**点击空操作**，无面板 | §11 |
| 网格（无/三分线/黄金比例） | 按钮在，**点击空操作**，无面板 + 无 CompositionGrid 渲染 | §12/§6 |
| ProMode（WB/曝光/对比/饱和/色温） | tune 按钮在，**点击空操作** | §13 |
| **面板互斥状态机** | **完全缺失**（spec 标「最严重交互逻辑错误」——面板可同时弹出） | §17 |
| 美颜面板 | FACE/MAKEUP Tab + 4 滑杆**已实现**；MAKEUP = 占位；缺拖拽手柄细节 | §8 |
| 5 款风格滤镜 | **占位 lock 瓦片**（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH） | §9 |
| 唇彩/腮红 makeup | `MakeupPlaceholderContent` | §8 |
| 浮动 FAB（语音/AI Chat） | **未实现** | §14 |
| 录像（VIDEO 模式） | 模式标签可切，**无录像逻辑**；快门无录像态 | §7 |
| 文档（DOCUMENT 模式） | 标签可切，**无检测** | §7/§16 |
| 人脸联动对焦框 | 点击对焦已实现（cyan L-crosshair），**人脸联动未接线** | §5 |
| 拍照链路 | ✅ AVCapturePhotoOutput → 离屏 Metal → PHPhotoLibrary | — |
| flip/zoom 预设/gallery 缩略图/shutter 闪屏 | ✅ 已存在（spec 🔴 标记过时） | — |

### 相册域
| 能力 | 现状 | gap 来源 |
|---|---|---|
| 大图页（media_pager） | ✅ 已有 scale 进出 / 1–4x 缩放 / 顶底栏 / PhotoInfoSheet / share+delete（spec 🔴 标记过时）；⚠️ 顶底栏 identifier 被 ZStack 传播覆盖 | gallery-grid §13–20 / gap §四 |
| 视频播放 | **未实现**（pager 对视频项只显缩略图） | §24 |
| 拖拽多选 | **未实现**（仅长按进选择 + 逐个点） | §7 |
| 相簿列表入口 | `AlbumListView` 存在，**无导航入口接线** | §4 |
| PhotoInfo 完整字段 | 部分（文件名/类型/时间/时长），缺 OCR/Vision/标签/美学评分 | §20 |
| 分组模式 | 仅 none/date；face/person/landscape/location **灰置** | §3 三/分组 |
| 搜索 / TAG 扫描进度 / 图像理解·OCR·人脸叠层 | **未实现（阻塞于 6.1）** | §5/§6/§17 |

### 跨切面（非本轨，但盘点发现）
- **People 页**：仅 `PlaceholderPage`（roadmap 6.6 另立子轨）
- **zh-Hant 繁体**：`Localizable.xcstrings` 无 zh-Hant 译文（落 zh-Hans），ja 仅 39% —— **[I18N] 红线缺口，独立快速修**
- JS 引擎 / 语音 ASR / VLM / 账号体系：均未落地（属 6.1/6.3）

---

## 2. 分轨切片（按 可推进度 × 价值 × 依赖 排序）

### 相机段（GLM 轨）
| 切片 | 内容 | 依赖 | 工作量 | 建议序 |
|---|---|---|---|---|
| **C1** | **相机面板系统**：5-面板互斥状态机 + 比例/场景/网格 3 选择器面板 + CompositionGrid 叠层 + ControlPanel 通用容器 | 无 | 中 | **1（先做）** |
| C2 | ProMode 面板（WB LazyRow + 曝光/对比/饱和/色温滑杆）+ AVCaptureDevice 锁定控制 | C1 容器 | 中高 | 2 |
| C3 | makeup：唇彩（12 色 + blend shader）+ 腮红（3 色系 + shader） | 新 Metal shader | 高 | 3 |
| C4 | 5 款风格滤镜 GLSL→MSL（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH） | shader 翻译 | 高 | 4 |
| C5 | 录像：AVCaptureMovieFileOutput + 快门录像态 + 录像时长 | AVFoundation | 高 | 5 |
| C6 | DOCUMENT 模式：文档边缘检测叠层 | Vision/CV | 高 | 6（低优） |
| C7 | 浮动 FAB：AI Chat FAB（跳聊天，低成本）+ 语音 FAB 占位 | 语音=ASR 延后 | 低 | 与 C1 并行 |
| C8 | 人脸联动对焦框（FaceFocusCrosshair 接线 face 检测） | face 引擎已就绪 | 低 | 与 C1 并行 |

### 相册段（K3 轨）
| 切片 | 内容 | 依赖 | 工作量 | 建议序 |
|---|---|---|---|---|
| **G1** | 视频播放：media_pager 视频项接 AVPlayerViewController | 无 | 中 | **1（先做）** |
| G2 | 拖拽多选 + 选择底栏（share/delete/select_all/cancel） | 无 | 中 | 2 |
| G3 | 相簿列表入口接线（顶栏/缩略图 → AlbumListView） | 无 | 低 | 与 G1 并行 |
| G4 | PhotoInfo 补全（source/location 端侧可得字段；标签/OCR/美学评分留 6.1） | 部分依赖 6.1 | 低 | 3 |

### 阻塞于 6.1（本轨不做，登记）
相册：搜索（§5）/ TAG 扫描进度（§6）/ 图像理解·OCR·人脸叠层（§17）/ face·person·landscape·location 分组（§3 三）。恢复触发点 = 6.1 TAG 落地（补验 B Qwen3-VL-2B 真机通过）。

---

## 3. Tranche C1 详案：相机面板系统（先做）

> **为何先做**：camera.yaml §17 标注「iOS 当前最严重的交互逻辑错误——无互斥，面板可同时弹出」。C1 建立状态机地基，C2–C4 的面板都复用它。GLM 轨主场（Swift 状态机 + AVFoundation 比例切换）。

### 3.1 面板状态机（核心，camera.yaml §17 1:1 落地）

新建 `Features/Camera/State/CameraPanelState.swift`（`@MainActor ObservableObject`）：

- **Primary 互斥组**（同时最多 1）：`beauty` / `filter` / `ratio` / `scene` / `grid`
  - `togglePrimary(_ panel:)`：记 nextVisible → `closeAllPrimary()` → 设该 panel = nextVisible
- **ProMode 独立轨**：`showProPanel` 直接 toggle；渲染条件 = `showProPanel && !isAnyPanelOpen`
- `isAnyPanelOpen` = filter || ratio || scene || grid（**不含 beauty、不含 pro**）
- **dismiss**：点预览空白 → `isProPanelOpen ? togglePro() : closeAllPanels()`；Back → 全关
- beauty 面板与 ProMode 可同时渲染（beauty 不在 isAnyPanelOpen 中）

### 3.2 通用 ControlPanel 容器（camera.yaml §9/§10/§11/§12 复用）

新建 `Features/Camera/Components/ControlPanel.swift`（bottom sheet）：
- `topCornerRadius: 24` / `shadowElevation: 16` / `0.5dp outlineVariant_alpha_25` 边框 / drag handle（36×4, onSurface_alpha_20）/ `panel_height_ratio: 0.5` / `padding_horizontal: 24`
- 进出动画：slide_in_vertically + fade
- 毛玻璃材质（`.ultraThinMaterial`，spec §20 allowed_difference）——圆角值必须 24pt

### 3.3 三个选择器面板（均复用 ControlPanel）

| 面板 | 项 | 行为 |
|---|---|---|
| **RatioSelector**（§10） | 4:3 / 16:9 / 全屏 | 选后 `set_aspect_ratio` + 关面板；接 `CaptureSessionController`（4:3=1280×720 4:3 fit/FULL=fill 裁剪） |
| **SceneSelector**（§11） | 无 / 夜景 / 月亮 | 设 scene 状态；**设备应用**（ISO/曝光预设）放 C2 统一接 AVCaptureDevice，C1 先 UI+状态 |
| **GridSelector**（§12） | 无 / 三分线 / 黄金比例 | 设 grid 状态 → 驱动 CompositionGrid 渲染 |

### 3.4 CompositionGrid 叠层（camera.yaml §6）

新建 `Features/Camera/Overlays/CompositionGrid.swift`（full_screen_overlay，z 在预览之上、控件之下）：
- thirds：2 竖 + 2 横均分；golden：线在 0.618 / 0.382
- `line_style`：white_alpha_50、1pt、dash [10,10]

### 3.5 接线与 a11y

- 4 个右侧按钮（ratio_switch/grid_toggle/scene_mode/filter_button）+ tune → 绑定 `CameraPanelState.toggle*`
- 顶部左 back/reset 补 `accessibilityIdentifier`（camera_back/camera_reset，gap 清单 §二）
- 面板容器 + 各选择项补 identifier（ratio_panel / scene_panel / grid_panel / ratio_4_3 等）

### 任务分解（依赖序）

| # | 任务 | 验证 |
|---|---|---|
| C1-T1 | `CameraPanelState` 状态机 + 单测（互斥、isAnyPanelOpen、pro 渲染条件、dismiss 三路径） | PoLangTests：5 面板互斥/pro 独立/back+空白关闭 全分支 |
| C1-T2 | `ControlPanel` 通用容器（圆角 24/shadow 16/handle/动画/毛玻璃） | Preview 四态；圆角 token 校验 |
| C1-T3 | RatioSelector + 比例设备接线（CaptureSessionController） | 真机切 4:3 留黑边 / FULL 裁剪填充；DebugOverlay 确认 |
| C1-T4 | GridSelector + CompositionGrid 渲染 | 真机三分线/黄金虚线显隐正确；dash 样式 |
| C1-T5 | SceneSelector（UI+状态，设备应用留 C2） | 面板项可选、互斥、关面板 |
| C1-T6 | 5 按钮接线 + a11y identifier + 滤镜面板纳入互斥 | XCUITest：开 ratio 再点 grid → ratio 自动关 |
| C1-T7 | i18n 三语（比例/场景/网格标签）+ CameraSpecUITests 面板互斥用例 | ios-i18n-validator 绿；UITest 互斥覆盖 |

### 红线/验收
- [PERF] 面板进出 <100ms（动画 300ms 内但首帧即时）；比例切换不卡预览
- [PRIVACY] 无新增媒体外传
- 出口：`CameraSpecUITests` 增面板互斥用例全绿；真机 5 面板操作不冲突；三语文案就位

---

## 4. Tranche G1 详案：相册视频播放（K3 轨，与 C1 并行）

- `MediaPagerView`：视频项（`mediaType == .video`）渲染 `AVPlayerViewController`（AVKit，spec §24 allowed_difference）而非静态缩略图
- 控制：原生播放/暂停/进度/全屏；与顶底栏独立显隐
- 视频标记（gallery-grid §7 video_indicator）：缩略图 `play.circle.fill` 已有，补 identifier
- 入口：大图页左右翻页跨图/视频无缝
- 验证：`GallerySpecUITests` 增视频项播放用例；真机视频自动/手动播放、进度拖动

> G2（拖拽多选）/ G3（相簿入口）/ G4（PhotoInfo 补全）作为 G1 后续小切片，逐个 writing-plans 或并入本计划附录。

---

## 5. [I18N] 红线缺口（独立快速修，建议与 C1/G1 并行）

`Localizable.xcstrings`（191 keys）**无 zh-Hant 译文**（落 zh-Hans）、`ja` 仅 74/191（39%）。
- **zh-Hant 补齐**：191 keys 全量繁体翻译（可由 zh-Hans 转换 + 校对；`LanguageManager` 已支持 zh-Hant lproj 路径，补译文即生效）
- **ja**：评估是否纳入支持集（<50% 完整度建议暂从 UI 语言选项移除，避免半成品）
- 执行：独立 worktree + `/ios-i18n-validator` skill；不阻塞 C1/G1

---

## 6. 派发包（kimi-code 实例）

### 相机段（GLM 实例）— 先派 C1
- **worktree**：从 `main` 开 `feat/ios-camera-panels`
- **先读**：`specs/screens/camera.yaml` §6/§10–13/§17；`2026-08-09-ios-spec-test-gaps.md` §一/二；`Features/Camera/Preview/CameraPreviewView.swift`、`Capture/CaptureSessionController.swift`（现状）
- **skill**：`swiftui-expert`（状态机/动画）、`metal-render-expert`（C3/C4 shader 时）、`ios-build-debug`、`ios-i18n-validator`
- **own**：C1 全部；C7/C8 可并行小切片
- **不碰**：`shared/`、`androidApp/`、`Features/Gallery/`、`Features/Chat/`

### 相册段（K3 实例）— 先派 G1
- **worktree**：从 `main` 开 `feat/ios-gallery-video`
- **先读**：`specs/screens/gallery-grid.yaml` §24/§7；`Features/Gallery/MediaPagerView.swift`、`ThumbnailView.swift`
- **own**：G1；G2/G3/G4 后续
- **不碰**：相机域、`*.metal`

### 交接
- 两实例文件域不交叉，真并行；唯一共享面 = 无（C1/G1 不动 shared API）
- 落 main 前各自由对侧模型 review diff（§5 全局纪律）

---

## 7. 风险

| 风险 | 缓解 |
|---|---|
| spec 🔴 标记过时致重复造轮子 | §1 现状基线已逐项对照；执行前再读 gap 清单 + 盘点确认 |
| 比例切换影响 Metal 管线 aspect-fill 裁剪（5.4 已修 Pass 4） | C1-T3 改比例后复测拍照出图非纯色块（bf78759c 同类回归） |
| ControlPanel 毛玻璃 vs Android surface alpha 视觉偏差 | spec §20 允许材质差异，圆角 24pt 必须一致 |
| zh-Hant 翻译质量 | 繁体转换后人工/Android 端 strings 对照样校对 |

---

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-08-09 | 初版：Phase 6.6 相机/相册轨切片计划。基于 codebase 全量盘点（Explore）+ camera/gallery spec + gap 清单。相机分 C1–C8、相册分 G1–G4，阻塞于 6.1 项登记。先做 C1（面板系统）+ G1（视频播放）并行；附 [I18N] zh-Hant 红线缺口独立修。回写 roadmap 修订十五 §6.6 |
