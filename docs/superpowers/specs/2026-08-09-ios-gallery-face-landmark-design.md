# iOS 相册大图页人脸关键点交互检测 — 设计规格

- 日期：2026-08-09
- 平台：iOS（Phase 5）
- 对标：Android `fix(android): 相册大图页人脸关键点改用必装 MNN 检测`（commit cf295fe2，已合并 main）
- 状态：设计已确认（方案 A），待写实现计划

## 1. 目标

让 iOS 相册大图页（`MediaPagerView`）支持「点一下标出人脸关键点」的交互式检测，行为对标 Android：调试开关门控、菜单按钮触发、带加载/无脸/错误反馈。复用 iOS 已就绪的检测引擎与 overlay，**保留点位跟随双指缩放**的能力（调试核心价值）。

## 2. 现状（已实现，无需重写）

- **检测引擎**：`GalleryFaceDebug.swift` 的 `StaticFaceDetector.detect(_ image: UIImage) -> Outcome?`——MNN 两阶段（`det_500m` ROI + `2d106det` 关键点）→ 经 `MnnLandmarkAdapter` 归一为统一 106 点（归一化 [0,1]，Y-down），与 Android `FaceDetector.detectPhoto` 同源同模型。已正确处理 UIImage 朝向归一化。
- **Overlay**：`GalleryFaceOverlay`（同文件）——人脸框 + 轮廓 0-32 + 全 106 点 + 关键点标签，`scaledToFit` 映射。
- **当前触发**：仅 `-galleryFace` 启动参数。`ZoomablePagerPage.task(id:)` 在图加载后，若含该参数则自动检测并画到当前图上；overlay 放在子层 ZStack 内（跟随 `scaleEffect`/`offset`，即跟随双指缩放）。
- **占位按钮**：`MediaPagerView.topBar` 的「更多」菜单里有 `Button { } "Face Landmarks" }.disabled(true)`——交互入口已预留，但禁用。
- **调试开关**：`DeveloperSettingsView` 的 `@AppStorage("debug_ui_enabled")`（key 与 Android 一致），嵌套子开关在 debug 开启后显示。设置可达。
- **本地化**：xcstrings 已有 `"Face Landmarks"`；**缺** `landmark_loading` / `landmark_no_face_detected` / `load_failed`（Android 三语已有）。

## 3. 方案（A：overlay 留子层，保留缩放跟随）

触发按钮在父层 `topBar`，图片与 overlay 在子层 `ZoomablePagerPage`。通过把「开关 + 触发」信号传入子层，让拥有图片的子层执行检测并渲染 overlay，从而保留缩放跟随。

### 3.1 组件与状态

**父层 `MediaPagerView`**（新增）：
- `@AppStorage("debug_ui_enabled") private var debugEnabled = false`
- `@State private var showFaceOverlay = false`
- `topBar` 菜单「人脸关键点」按钮：
  - `disabled(!debugEnabled || currentAsset?.type != .photo)`（debug 关或非照片灰置，对标 Android `showLandmarkAction`）
  - 点击：`showFaceOverlay.toggle()`
- 将 `showFaceOverlay: Bool` 与 `debugEnabled: Bool` 传入每个 `ZoomablePagerPage`。
- `index` 变化时不主动清 `showFaceOverlay`（保持开/关状态跨页延续，由子层各自检测/清空）。

**子层 `ZoomablePagerPage`**（改造）：
- 替换 `@State faceOutcome: Outcome?` 为：
  ```swift
  private enum FaceState {
      case idle, loading
      case success(StaticFaceDetector.Outcome)
      case noFace
  }
  @State private var faceState: FaceState = .idle
  ```
  > 只取 `idle/loading/success/noFace` 四态：`StaticFaceDetector.detect` 返回 `Outcome?`，nil 无法区分「无脸」与「失败」，本期统一映射为 `.noFace`（与 Android「无脸/失败都给反馈」语义一致，区别仅是文案粒度）。不设 `.error` 死分支；图未加载时检测不触发，无单独「加载失败」路径。
- 新增入参 `showFaceOverlay: Bool`（debug 门控已在按钮层处理，子层只看开关）。
- 检测触发函数 `detectIfNeeded()`：当 `showFaceOverlay && isActive && image != nil && faceState == .idle` 时，置 `.loading`，`Task.detached` 跑 `StaticFaceDetector.detect(image)`，回主线程按结果置 `.success/.noFace/.error`（仍在本页 `localIdentifier` 时才写入）。
- 两个 `onChange` 调 `detectIfNeeded()`：`of: showFaceOverlay`、`of: isActive`。
- `showFaceOverlay` 为 false 时，overlay 与反馈不渲染（但不清 `faceState`，避免来回切页重复检测；切页靠 `isActive` + 下条）。
- **切页清空**：`onChange(of: localIdentifier)`（或 `.task(id:)` 重建）将 `faceState = .idle`——新图重新检测。
- 渲染：成功态沿用 `GalleryFaceOverlay`（留子层 ZStack，跟随缩放）；新增居中反馈胶囊（loading 转圈 / noFace / error）。
- **移除**：子层 `.task(id:)` 里 `-galleryFace` 自动检测块（交互化后不再需要；无头 `GalleryFaceAutoCheck` CI 路径在 `PoLangApp`，不动）。

### 3.2 数据流

```
用户开 设置→开发者→Debug
  → topBar「人脸关键点」按钮可用
  → 点击 → showFaceOverlay = true（传入当前/活动子页）
  → 子页 onChange(showFaceOverlay) → detectIfNeeded()
  → image(已加载) → StaticFaceDetector.detect（后台线程）
  → faceState = success / noFace
  → 成功：GalleryFaceOverlay 画点（跟随双指缩放）
     否则：反馈胶囊（loading / 未检测到人脸）
  → 切页：新活动子页 detectIfNeeded()（若仍开）；旧子页保留各自状态
  → 再点按钮：showFaceOverlay = false → overlay/反馈消失
```

### 3.3 错误处理

- `StaticFaceDetector.detect` 返回 nil（无脸或失败）→ `.noFace`，显示「未检测到人脸」。不设单独错误态（detect 返回 Optional，无法区分；详见 3.1）。
- 任务取消：`Task.isCancelled` 守卫，避免切页后写入旧页状态。
- 检测失败不阻塞看图（对标 Android：失败仅反馈，不崩）。

### 3.4 本地化（i18N）

新增 2 个 key 到 `PoLang/Resources/Localizable.xcstrings`，三语（EN / zh-Hans / zh-Hant），文案对齐 Android `values*/strings.xml`：
- `landmark_loading` — "Detecting face landmarks…" / "正在检测人脸关键点…" / "正在檢測人臉關鍵點…"
- `landmark_no_face_detected` — "No face detected" / "未检测到人脸" / "未檢測到人臉"

（不引入 `load_failed`：iOS 检测吃已加载 UIImage，无独立解码失败路径。）按钮文案复用已有 `"Face Landmarks"`。

## 4. 不在范围内

- 不做多脸（`StaticFaceDetector` 现取首张脸，对标 Android `numFaces=1`）。
- 不动无头 `GalleryFaceAutoCheck`（CI 自动验收）。
- 不改 `StaticFaceDetector` 检测算法 / `MnnLandmarkAdapter`（已验证正确）。
- 不做 iOS 相机 live overlay（那是 `LandmarkDebugOverlay`，另案）。

## 5. 涉及文件

| 文件 | 改动 |
|------|------|
| `iosApp/PoLang/Features/Gallery/MediaPagerView.swift` | 父层：`@AppStorage` + `showFaceOverlay` + 按钮接线 + 传参；子层：`FaceState` + `detectIfNeeded` + `onChange` 触发 + 反馈视图 + 移除 `-galleryFace` 块 |
| `iosApp/PoLang/Features/Gallery/GalleryFaceDebug.swift` | 可选：抽一个 `GalleryFaceFeedback` 视图（loading / noFace），供子层复用；`GalleryFaceOverlay` 不动 |
| `iosApp/PoLang/Resources/Localizable.xcstrings` | 新增 2 个反馈 key（三语） |

## 6. 验收标准

1. 设置→开发者开启 Debug 后，相册大图页顶栏「更多」→「人脸关键点」可点（debug 关时灰置）。
2. 点击后：含脸照片显示 106 点 + 人脸框（跟随双指缩放）；无脸显示「未检测到人脸」；检测中有 loading。
3. 切换到另一张照片（开关仍开）自动重新检测当前图。
4. 再次点击按钮，overlay 消失。
5. 检测全程不卡 UI（后台线程）、失败不崩、不影响看图翻页缩放。
6. xcstrings 三语齐全，无硬编码用户可见串。
