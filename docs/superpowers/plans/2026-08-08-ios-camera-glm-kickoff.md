# 实例启动包：Phase 5 相机段 + 基建-iOS（GLM 实例）

> **模型**：GLM　**harness**：`kimi-code`　**轨**：Swift/Metal
> **上游 SSOT**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` §3.1（并行模型与分工）
> **任务细则**：`docs/superpowers/plans/2026-08-08-ios-app-skeleton.md` Task 2/4/5/6 + 12–19
> **对侧**：相册段 + 基建-KMP 由一个 **K3 实例**并行做（见 `2026-08-08-ios-gallery-k3-kickoff.md`）

## 你 own 什么
- **基建-iOS**：Task 2（Xcode 骨架）、Task 4（DebugOverlay / AppContainer / I18N / Privacy Manifest）、Task 5（CI + ios-dev-loop）、Task 6（引擎产物收编）
- **相机段**：Task 12–19（Metal 直渲 → 美颜宿主 → 美白/磨皮/warp×2/LUT → 人脸 468→106 → 拍照 → 手势）

## 立即可做（零依赖，不等 Phase 4 / 不等对侧）
1. **warp shader 翻译（Task 16，关键路径 + 最高风险）**
   - `engines/beauty-engine/src/main/assets/shaders/warp_gpupixel_thinface.glsl`（瘦脸）
   - `engines/beauty-engine/src/main/assets/shaders/warp_gpupixel_bigeye.glsl`（大眼）
   - spike 标注 2 个 hard 逆变换"须逐行理解"——GLM 强推理主场
2. **其余 shader 翻译**：磨皮 `pass_smoothing.glsl` + `skin.glsl`（Task 14）、LUT `colorgrade.glsl` + `style/*.glsl`（Task 17）
3. **Task 6 引擎产物收编**：MNN.framework / sentencepiece / 美颜 assets / MediaPipe 模型从 `tmp/` 搬正到 `engines/` + `iosApp/Frameworks/`

## 先读（按序）
1. `docs/superpowers/specs/2026-08-08-ios-beauty-metal-spike-design.md`（spike GO + 踩坑）
2. `docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md` §3 §5（基建 + 相机设计）
3. `docs/superpowers/plans/2026-08-08-ios-app-skeleton.md` Task 12–19（逐 Task + 验证步）
4. skill：`metal-render-expert`（GLSL→MSL 纪律）、`coordinate-system-standard`（iOS 小节）、`ios-build-debug`

## GLSL→MSL 翻译纪律（来自 skill + spike 踩坑）
- `const` 局部标量 → `constexpr`（`constant` 是地址空间，非 const）
- uniform 打包 `BeautyUniforms` → `setFragmentBytes`；`uFacePoints[212]` → MTLBuffer
- `uTextureTransform`（SurfaceTexture 矩阵）→ **删除**
- `clamp(x,0,1)` → `saturate`；`texture2D` → `texture.sample`
- 多文件 concat → `device.makeLibrary(source:)`
- 踩坑：`commandQueue` 勿漏初始化；相机显式 `requestAccess`；`AVCaptureConnection.videoOrientation = .portrait`

## 关键参照
| 用途 | 路径 |
|---|---|
| Metal 管线宿主（298 行完整参照） | `tmp/beauty-metal-spike/BeautyMetalSpike/`（`main.mm` + `Shaders.metal`） |
| 人脸 468→106 移植源 + **金样本测试** | `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/adapter/MediaPipe468Adapter.kt` + `MediaPipe468AdapterTest.kt` |
| GLSL 全量源 | `engines/beauty-engine/src/main/assets/shaders/` |

> ⚠️ 计划已修正 spec：**人脸关键点走 MediaPipe Face Landmarker（非 MNN）**；468→106 从上述 Kotlin 文件逐行移植到 Swift，**金样本测试一并移植**作正确性护栏。

## 与 K3 实例的交接点
- **Task 3 embed 冒烟**：需 K3 的 shared XCFramework（Task 1）+ 你自己的 Xcode 工程（Task 2）合体。K3 产出 framework 后经计划文档勾选告知（**不靠实例直连**）。
- **Task 4 AppContainer**：DI 组合根注入 shared actual——接口契约由 K3 的 shared commonMain 定义（`MediaRepository`/`AccessState`/`BeautySettings`），你写 Swift 侧 wiring。
- 唯一共享面 = shared XCFramework API；**不碰** `shared/`、`androidApp/`、相册段（`iosApp/Features/Gallery/`）。

## 红线
- **[PRIVACY]** 媒体 100% 端侧——相机帧/照片**禁止发远程**
- **[PERF]** 快门 <50ms（美颜离屏渲染异步化）、交互 <100ms
- **[I18N]** 三语从第一天起（`Localizable.xcstrings`）
- **S5 双端一致**：美颜参数默认值/滑杆范围/滤镜名与排序 = Android `BeautySettings`

## 验证门
DebugOverlay 实时 FPS（预览 30fps）；warp 滑杆即时可见；468→106 金样本单测绿；双端同场景观感对照。

## 试水（建议第一步）
先翻一个 shader，确认 `kimi-code` + GLM 的 tool-use / 编译反馈链路正常（非降级路径），再深入 Task 16 全量。若 GLM 在 kimi-code 下能力受限，回报用户，相机段可回退 Claude Code session 承接。

## worktree
从 **main** 开 `refactor/ios-camera-track`。
