# iOS 相机页双端对齐计划（T7b）

> **For agentic workers:** 主排序计划见 [`2026-08-10-ios-implementation-tasks.md`](2026-08-10-ios-implementation-tasks.md) T7b；差异清单见 [`../../reviews/2026-08-10-ios-android-consistency-gap.md`](../../reviews/2026-08-10-ios-android-consistency-gap.md) §3。本文是相机页对齐的批次拆分。
> **Goal:** 把 iOS 相机页对齐到 Android（契约 `specs/screens/camera.yaml`），按体感 ROI 分批。
> **工作区**：`.worktrees/ios-camera-align`（branch `feat/ios-camera-align`，from main）。主 checkout 被 TAG session 占用，故隔离。
> **验证**：每批 `xcodebuild build`（generic/platform=iOS）编译校验；视觉/真机验证 defer 到设备空闲（避免与 TAG session 撞设备）。
> **基线**：Android `CameraControls.kt:196-228`（快门）、`CameraScreen.kt:1517-1660`（闪屏/反馈）、`specs/screens/camera.yaml`。

---

## B1：启用死 Token + 快门反馈（最高 ROI，极低代价）✅ 本批

`DesignTokens.swift ShutterTokens` 已定义正确值却全是死代码。启用 + 补反馈。

| 项 | 文件 | 改动 | 验收 |
|---|---|---|---|
| 快门外径 62→76 | `ShutterButton.swift:17` | `ShutterTokens.diameter` | grep 无裸 62/52 |
| 快门内径 52→58 | `ShutterButton.swift:21` | `ShutterTokens.innerDiameter` | — |
| 闪屏 白→**黑** | `CameraPreviewView.swift:104` | `Color.black`（Android 黑闪） | — |
| 闪屏 alpha 0.9→0.6 | `CameraPreviewView.swift:105` | `ShutterTokens.flashAlpha` | — |
| 闪屏时长 250ms→80ms | `CameraPreviewView.swift:108` | `ShutterTokens.flashFadeMs/1000` | — |
| 快门 haptic（medium） | `ShutterButton.swift tap()` | `UIImpactFeedbackGenerator` | 对标 Android LONG_PRESS |
| 快门音 | `ShutterButton.swift tap()` | `AudioServicesPlaySystemSound(1108)` | 对标 Android CLICK |

> iOS 已有的 `press-scale`（Android 反而缺）保留，不删——反向推动 Android 补。

## B2：右列 4 枚 no-op 面板（系统性踩空）
比例(RatioSelector 4:3/16:9/FULL→ScaleType) / 网格(CompositionGrid 虚线叠加) / 场景(NONE/NIGHT/MOON→EV) / ProMode(EV/WB/对比度/饱和度/色温 半屏)。先做"面板+最小功能"消除死按钮。需扩展 `ActivePanel` enum + 复刻 primary 组互斥。

## B3：人脸十字星语义对齐
当前联动点击对焦 → 改/增联动人脸检测（跟脸移动），复刻 220/160/320/420ms 多态时序。依赖 `FaceLandmarkService` 输出。

## B4：MAKEUP tab + 5 风格滤镜
唇彩 12 色板+强度、腮红 3 色系+强度（`BeautyPanelView.swift:153` 占位）；5 风格 Shader（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH）落 Metal kernel。

## B5：模式选择器 + 录像（大工程，留后）
VIDEO→AVCaptureMovieFileOutput + 快门录像态(token 已有 recordingInner*)；DOCUMENT→文档检测叠加。同时修 Android 侧 DocumentDetectionOverlay `Color.Unspecified` bug。

## 顺手（每批带上）
- 美颜角标 绿→accent（`CameraPreviewView.swift:319`）
- 面板高 38%→35%（`BeautyPanelView.swift:45`）、滤镜面板 53%→50%（`FilterSelectorView.swift:35`）
- 左列 返回/Reset no-op 接线（`CameraPreviewView.swift:301,307`）

---

## 进度
- 2026-08-10 B1 进行中（自主夜间，编译校验，真机视觉验证 defer）。
