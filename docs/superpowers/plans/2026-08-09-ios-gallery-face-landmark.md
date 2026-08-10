# iOS 相册大图页人脸关键点交互检测 — 实现计划

> 📜 **历史实现计划**。功能已实现（debug 门控，`9cb910e1`/`8d4c40ec`），现行事实见 `IOS_PRODUCT_REFERENCE.md` §2.1。归类见 `docs/01-PRODUCT/IOS_DOC_INDEX.md` §2.3。


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 iOS 相册大图页（`MediaPagerView`）支持「点一下标出人脸关键点」的交互式检测，调试门控、带反馈、点位跟随缩放，对标 Android（commit cf295fe2）。

**Architecture:** 方案 A——触发按钮在父层 `topBar`，检测与 overlay 留在拥有图片的子层 `ZoomablePagerPage`（保留双指缩放跟随）。父层持 `showFaceOverlay` 开关 + 读 `debug_ui_enabled` 门控；子层用 `FaceState` 状态机在「激活+开关开」时跑现有 `StaticFaceDetector.detect`，渲染 `GalleryFaceOverlay`（成功）或 `GalleryFaceFeedback`（loading/noFace）。

**Tech Stack:** SwiftUI（iOS 16 部署目标）、MNN（`StaticFaceDetector`，已就绪）、xcstrings 本地化。

**规格：** `docs/superpowers/specs/2026-08-09-ios-gallery-face-landmark-design.md`

---

## 前置说明（重要）

1. **依赖 WIP 文件**：`GalleryFaceDebug.swift`（提供 `StaticFaceDetector` / `GalleryFaceOverlay`）及其 MNN 适配器（`MnnLandmarkAdapter.swift`、`MnnFaceDetectorBridge.*` 等）目前是工作区**未提交**的 iOS WIP。本功能直接依赖它们。Task 2 会把 `GalleryFaceDebug.swift` 纳入提交；其余共享 MNN 适配器文件若构建报缺失，需一并 `git add` 纳入本特性分支（它们本就是该 iOS 人脸检测工作的一部分）。
2. **构建命令**（Intel 开发机，MNN.framework 仅 arm64，模拟器会链接失败，故用 `generic/platform=iOS` 绿构建）：
   ```
   xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
     -destination 'generic/platform=iOS' -configuration Debug \
     CODE_SIGNING_ALLOWED=NO build
   ```
3. **无单元测试**：SwiftUI 视图接线/交互无法纯 JVM 单测；验证 = 构建通过 + 真机手测。检测算法正确性已由现有无头 `GalleryFaceAutoCheck`（`-galleryFace`）覆盖。每个 Task 以「构建通过」为门槛。

## 文件结构

| 文件 | 责任 | 改动 |
|------|------|------|
| `iosApp/PoLang/Resources/Localizable.xcstrings` | 本地化 | 新增 `landmark_loading`、`landmark_no_face_detected`（en/zh-Hans/ja） |
| `iosApp/PoLang/Features/Gallery/GalleryFaceDebug.swift` | 检测 + 可视化 | 新增 `GalleryFaceFeedback` 视图；`StaticFaceDetector`/`GalleryFaceOverlay` 不动 |
| `iosApp/PoLang/Features/Gallery/MediaPagerView.swift` | 大图页 | 父层：门控+开关+按钮+传参；子层：`FaceState` 状态机+触发+反馈，移除 `-galleryFace` 自动块 |

---

### Task 1: 新增本地化串

**Files:**
- Modify: `iosApp/PoLang/Resources/Localizable.xcstrings`（在 `"Face Landmarks"` 条目之后插入两条）

iOS 语种集合 = en / zh-Hans / ja（无 zh-Hant，以现有条目为准）。

- [ ] **Step 1: 插入两条新串**

在 `"Face Landmarks": { ... },` 条目结束后（其闭合 `},` 之后、下一个 key 之前）插入：

```json
    "landmark_loading": {
      "extractionState": "manual",
      "localizations": {
        "en": {
          "stringUnit": { "state": "translated", "value": "Detecting face landmarks…" }
        },
        "zh-Hans": {
          "stringUnit": { "state": "translated", "value": "正在检测人脸关键点…" }
        },
        "ja": {
          "stringUnit": { "state": "translated", "value": "顔ランドマークを検出中…" }
        }
      }
    },
    "landmark_no_face_detected": {
      "extractionState": "manual",
      "localizations": {
        "en": {
          "stringUnit": { "state": "translated", "value": "No face detected" }
        },
        "zh-Hans": {
          "stringUnit": { "state": "translated", "value": "未检测到人脸" }
        },
        "ja": {
          "stringUnit": { "state": "translated", "value": "顔が検出されませんでした" }
        }
      }
    },
```

- [ ] **Step 2: 校验 JSON 合法**

Run: `python3 -m json.tool iosApp/PoLang/Resources/Localizable.xcstrings > /dev/null && echo OK`
Expected: `OK`（xcstrings 是标准 JSON）

- [ ] **Step 3: 构建验证（串被编译进 catalog）**

Run（见前置说明的构建命令）:
```
xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -3
```
Expected: `BUILD SUCCEEDED`

- [ ] **Step 4: 提交**

```bash
git add iosApp/PoLang/Resources/Localizable.xcstrings
git commit -m "feat(ios): 相册人脸关键点反馈本地化串（en/zh-Hans/ja）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 新增 GalleryFaceFeedback 视图

**Files:**
- Modify: `iosApp/PoLang/Features/Gallery/GalleryFaceDebug.swift`（文件末尾追加；该文件目前未纳入 git，本 Task 一并 `git add`）

- [ ] **Step 1: 在文件末尾追加反馈视图**

在 `GalleryFaceDebug.swift` 末尾（`GalleryFaceOverlay` 结构体之后）追加：

```swift

/// 关键点检测的加载/无脸反馈（居中半透明胶囊），让「点击后人脸关键点」有可见响应，
/// 不再像旧版那样检测无结果时静默空白。对齐 Android FaceLandmarkFeedback。
struct GalleryFaceFeedback: View {
    enum Phase { case loading, noFace }
    let phase: Phase

    var body: some View {
        VStack(spacing: 8) {
            if phase == .loading {
                ProgressView().tint(.white)
            }
            Text(phase == .loading
                 ? String(localized: "landmark_loading")
                 : String(localized: "landmark_no_face_detected"))
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 18)
        .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
    }
}
```

- [ ] **Step 2: 构建验证**

Run（前置构建命令，下略）:
```
xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -3
```
Expected: `BUILD SUCCEEDED`。若报 `GalleryFaceDebug.swift` 或其 MNN 适配器缺失/未纳入 target，把对应共享文件 `git add`（见前置说明 1）。

- [ ] **Step 3: 提交（纳入 GalleryFaceDebug.swift 这个依赖基础）**

```bash
git add iosApp/PoLang/Features/Gallery/GalleryFaceDebug.swift
git commit -m "feat(ios): GalleryFaceFeedback 反馈视图（loading/noFace）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 父层 MediaPagerView 接线（门控 + 开关 + 按钮 + 传参）

**Files:**
- Modify: `iosApp/PoLang/Features/Gallery/MediaPagerView.swift`
  - 父 struct `MediaPagerView`：加两个属性（约 20 行附近）
  - `topBar` 的 Menu「Face Landmarks」按钮（约 105 行）
  - `ZoomablePagerPage(...)` 调用（约 39-45 行）

- [ ] **Step 1: 父 struct 增加门控与开关属性**

在 `MediaPagerView` 的 `@State private var showDeleteConfirm = false`（约第 20 行）之后追加两行：

```swift
    @AppStorage("debug_ui_enabled") private var debugEnabled = false
    @State private var showFaceOverlay = false
```

- [ ] **Step 2: 把禁用按钮改成可交互（门控）**

将 topBar Menu 内（约 105 行）：

```swift
                Button {} label: { Text(String(localized: "Face Landmarks")) }.disabled(true)
```

替换为：

```swift
                Button { showFaceOverlay.toggle() } label: {
                    Text(String(localized: "Face Landmarks"))
                }
                .disabled(!debugEnabled || currentAsset?.type == .video)
```

> 语义：debug 关或当前为视频时灰置（对标 Android `showLandmarkAction = debugUiEnabled && type==PHOTO`）；点击切换 `showFaceOverlay`。

- [ ] **Step 3: 把开关传入子页**

将 body 中 `ZoomablePagerPage(...)` 调用（约 39-45 行），在 `isActive: i == index,` 之后增加一行参数：

```swift
                    ZoomablePagerPage(
                        localIdentifier: asset.uri,
                        isActive: i == index,
                        showFaceOverlay: showFaceOverlay,
                        onTap: { withAnimation(.easeInOut(duration: 0.2)) { barsVisible.toggle() } },
                        onZoomChange: { zoomed in
                            if zoomed { isZoomed = true } else if i == index { isZoomed = false }
                        })
```

> 注意：子页签名在 Task 4 才增加 `showFaceOverlay`，本 Task 后构建会报「参数不存在」——属预期，Task 4 完成后即通过。**故本 Task 不单独构建**，直接进 Task 4。

- [ ] **Step 4: 本 Task 不单独提交**（与 Task 4 合并提交，避免中间不可编译态入 git）

---

### Task 4: 子层 ZoomablePagerPage 状态机 + 触发 + 反馈

**Files:**
- Modify: `iosApp/PoLang/Features/Gallery/MediaPagerView.swift` 的 `ZoomablePagerPage`（约 198-262 行）

- [ ] **Step 1: 增加 showFaceOverlay 入参**

将子 struct 属性块（约 198-211 行）：

```swift
private struct ZoomablePagerPage: View {
    let localIdentifier: String
    let isActive: Bool
    let onTap: () -> Void
    let onZoomChange: (Bool) -> Void

    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    /// 🔴 -galleryFace：静态 MNN 人脸检测诊断。存检测结果，画到图上裁决检测/remap 是否正确
    ///（与 live 相机 buffer 朝向解耦：静态点落脸→检测正确，live 偏在相机管线）。
    @State private var faceOutcome: StaticFaceDetector.Outcome?
```

替换为：

```swift
private struct ZoomablePagerPage: View {
    let localIdentifier: String
    let isActive: Bool
    let showFaceOverlay: Bool
    let onTap: () -> Void
    let onZoomChange: (Bool) -> Void

    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    /// 人脸关键点检测状态机：idle→loading→success(画点)/noFace(反馈)。
    /// 对标 Android FaceLandmarkDetectionState；成功态 overlay 留在本 ZStack 跟随缩放。
    private enum FaceState {
        case idle, loading
        case success(StaticFaceDetector.Outcome)
        case noFace
    }
    @State private var faceState: FaceState = .idle
```

- [ ] **Step 2: 改 overlay 渲染（faceOutcome → faceState）**

将 body 中 ZStack 内（约 218-223 行）：

```swift
                    ZStack {
                        Image(uiImage: image).resizable().scaledToFit()
                        if let face = faceOutcome {
                            GalleryFaceOverlay(points: face.points, imageSize: face.imageSize)
                        }
                    }
```

替换为：

```swift
                    ZStack {
                        Image(uiImage: image).resizable().scaledToFit()
                        if showFaceOverlay {
                            if case .success(let outcome) = faceState {
                                GalleryFaceOverlay(points: outcome.points, imageSize: outcome.imageSize)
                            } else if case .loading = faceState {
                                GalleryFaceFeedback(phase: .loading)
                            } else if case .noFace = faceState {
                                GalleryFaceFeedback(phase: .noFace)
                            }
                        }
                    }
```

> idle 态三个 `if case` 均不命中 → 不渲染（非当前页或未触发时干净）。

- [ ] **Step 3: 用交互触发替换 `-galleryFace` 自动块**

将 `.task(id: localIdentifier) { ... }`（约 241-261 行，含 `-galleryFace` 自动检测）整段：

```swift
        .task(id: localIdentifier) {
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 1600, height: 1600),
                highQuality: true)  // 大图要高清档（🟡-8）
            // 🔴 -galleryFace：对该静态图跑 MNN 人脸检测（后台线程），结果画到图上
            if let img = image, ProcessInfo.processInfo.arguments.contains("-galleryFace") {
                let outcome = await Task.detached(priority: .userInitiated) {
                    StaticFaceDetector.detect(img)
                }.value
                // 仅当仍在同一页（localIdentifier 未变）时更新
                if Task.isCancelled == false {
                    faceOutcome = outcome
                    if outcome != nil {
                        print("[PoLang] gallery.face: detected \(outcome?.points.count ?? 0) pts")
                    } else {
                        print("[PoLang] gallery.face: no face / detect failed")
                    }
                }
            }
        }
```

替换为：

```swift
        .task(id: localIdentifier) {
            faceState = .idle  // 切页重置：新图重新走 idle→loading→...
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 1600, height: 1600),
                highQuality: true)
            detectIfNeeded()  // 图就绪后，若仍「激活+开关开」则触发检测
        }
        .onChange(of: showFaceOverlay) { _ in detectIfNeeded() }  // 点按钮开关时
        .onChange(of: isActive) { active in if active { detectIfNeeded() } }  // 翻到本页时
```

- [ ] **Step 4: 增加 detectIfNeeded() 方法**

在 `ZoomablePagerPage` 内（`private func magnify` 等私有方法附近，约 264 行前）追加：

```swift
    /// 满足「开关开 + 当前页 + 图已加载 + idle」时触发一次 MNN 检测；否则空操作。
    private func detectIfNeeded() {
        guard showFaceOverlay, isActive, let image else { return }
        guard case .idle = faceState else { return }  // 已在检测/已有结果则不重复
        faceState = .loading
        let snapshot = image
        Task.detached(priority: .userInitiated) {
            let outcome = StaticFaceDetector.detect(snapshot)
            await MainActor.run {
                guard showFaceOverlay, isActive else { return }  // 仍开且仍是当前页
                if let outcome {
                    faceState = .success(outcome)
                } else {
                    faceState = .noFace
                }
            }
        }
    }
```

> `StaticFaceDetector.detect` 为同步重计算，放 `Task.detached` 离主线程；结果回 `MainActor.run` 写状态。`.task(id:)` 在切页时自动取消，配合 guard 防止写旧页。

- [ ] **Step 5: 构建验证**

Run:
```
xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -5
```
Expected: `BUILD SUCCEEDED`。常见错：`FaceState` 各 `case` 拼写、`outcome.points`/`imageSize` 字段名（须与 `StaticFaceDetector.Outcome` 一致）、`MediaAsset.type == .video`。按报错修。

- [ ] **Step 6: 提交（Task 3 + Task 4 合并）**

```bash
git add iosApp/PoLang/Features/Gallery/MediaPagerView.swift
git commit -m "feat(ios): 相册大图页人脸关键点交互检测（调试门控+反馈+缩放跟随）

- 父层：debug_ui_enabled 门控「人脸关键点」按钮，切换 showFaceOverlay
- 子层：FaceState 状态机（idle/loading/success/noFace），激活+开关开时检测
- 复用 StaticFaceDetector(MNN) + GalleryFaceOverlay；新增 GalleryFaceFeedback
- 移除子层 -galleryFace 自动块（交互化；无头 GalleryFaceAutoCheck 不动）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 真机手测验收

**Files:** 无（验证）

> 需连接 iOS 真机（Intel 开发机只能 device 构建/安装）。设备 UDID 见 `adb`/`xcrun devicectl` 或 Xcode。

- [ ] **Step 1: 安装到真机**

Run（替换 `<UDID>`；或用 ios-build-debug skill）:
```
xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
  -destination 'id=<UDID>' -configuration Debug build
```
（安装/启动走 ios-build-debug skill 标准流程）

- [ ] **Step 2: 手测验收清单（对标 spec §6）**

1. 设置→开发者→开启 `Debug`。
2. 相册点开一张**正面有脸**的照片进大图页。
3. 顶栏「更多」→「人脸关键点」：可点（debug 关时灰置）。
4. 点击后：先 loading 胶囊，随后脸上出现 106 点 + 人脸框；**双指放大，点位跟随放大**。
5. 换一张**无人脸**的照片（开关仍开）：显示「未检测到人脸」。
6. 横滑切到另一张有脸照片：自动重新检测当前图并画点。
7. 再点「人脸关键点」：overlay 消失。
8. 全程翻页/缩放流畅，无卡顿、无崩溃。

- [ ] **Step 3: 通过则收尾**

构建绿 + 手测全过 → 功能完成。若手测发现问题，回到对应 Task 修复后重测。

---

## Self-Review（写完后自查结果）

- **Spec 覆盖**：§3.1 父层门控/开关/传参 → Task 3；子层状态机/触发/反馈/移除 launch-arg → Task 4；§3.3 错误处理（nil→noFace、Task 取消守卫）→ Task 4 Step 4 guard；§3.4 i18n → Task 1；§6 验收 → Task 5。✓
- **占位扫描**：无 TBD/TODO；每步含具体代码或命令。✓
- **类型一致**：`FaceState`（idle/loading/success(Outcome)/noFace）四 Task 一致；`GalleryFaceFeedback.Phase`（loading/noFace）一致；`Outcome.points`/`imageSize` 与现有 `StaticFaceDetector.Outcome` 字段一致（与现有 `GalleryFaceOverlay(points:imageSize:)` 调用同源）。✓
- **已知风险**：Task 3 Step 3 引入子页新参数后、Task 4 完成前不可编译——已在 Task 3 Step 3/4 注明不单独构建/提交，合并于 Task 4。✓
