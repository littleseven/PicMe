# 美颜引擎 iOS Metal 渲染验证 Spike 报告

> 📜 **历史 Spike 报告**（Phase 2.4 排雷）。结论已沉淀进路线图（shader~1w+宿主~2w）；保留作审计链。归类见 `docs/01-PRODUCT/IOS_DOC_INDEX.md` §2.1。


> **日期**：2026-08-08
> **关联**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 2.4 / Phase 5.4
> **结论**：✅ **GO** — 美白单滤镜 Metal 实时渲染真机达标（30fps 出图、滑杆美白即时可见）。GLSL→MSL 翻译可行；全滤镜机械转换无阻塞，仅 3 个 warp 文件偏硬。**真正成本在 Kotlin 渲染宿主重写（EGL/GLES/SurfaceTexture → Metal/AVFoundation），非 shader 本身**；Phase 5.4 工期建议按 shader 翻译 ~1 周 + 宿主重写 ~2 周 计（计划原估 1–2 周偏紧，只覆盖了 shader 没覆盖宿主重写）。

---

## 1. 验证目标

验证 PoLang beauty-engine（自研 OpenGL ES 美颜引擎）能否在 iOS 上用 Metal 重现：

1. 选最简单滤镜（美白 `whitenSkin`）翻译 GLSL→MSL，确认语法/语义可移植；
2. 搭 `AVCaptureSession → CVMetalTexture → Metal fragment → MTKView` 实时预览管线并测帧率；
3. 评估全滤镜（25 个 shader）+ 渲染宿主（Kotlin）迁移到 iOS Metal 的完整工作量。

这是 Phase 5.4（相机管线）的前置排雷——若届时才发现 shader 迁移量或宿主重写量，会冲击 Phase 5 工期。

---

## 2. 验证环境

| 项 | 值 |
|----|-----|
| 测试设备 | iPhone 15（郭帅的iPhone，iPhone15,4，arm64，iOS 17） |
| Xcode | 16.4（Build 16F6），Metal compiler |
| 工程模式 | ObjC++ 单文件 app（沿用 mnn-spike 模板），`DEVELOPMENT_TEAM=6NPE45262A` |
| 相机输入 | `AVCaptureSession` 720p，`kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange`（YUV bi-planar） |
| 纹理桥 | `CVMetalTextureCacheCreateTextureFromImage`：Y plane → `MTLPixelFormatR8Unorm`，UV plane → `MTLPixelFormatRG8Unorm`（尺寸 ½） |
| 色彩转换 | fragment 内 BT.601 limited range（Y∈[16,235]）YUV→RGB |
| 验证滤镜 | `whitenSkin`（`skin.glsl` L81-112，纯像素算术无采样） |

---

## 3. 验证结果

### 3.1 GLSL → MSL 翻译（美白）

`whitenSkin` 逐行直译，映射规则：`vec3→float3`、`mix`/`dot`/`length` 同名、`clamp(.,0,1)→saturate`、`gl_FragColor→return`、`texture2D→texture.sample`。纯算术无纹理采样、无 GL 状态依赖，是验证 Metal 可移植性的最佳样本。

**踩到 1 个 MSL 陷阱**（编译期报错）：

```
error: automatic variable qualified with an address space
    constant float levelBlack = 0.0258820;   // ❌ GLSL 的 const 直译成 constant
```

GLSL `const` 局部常量不能译成 `constant`——**`constant` 在 MSL 是地址空间（device/constant/thread...）不是 const 语义**。Metal 局部编译期标量用 `constexpr`（或 `const`）。修正后编译通过、零警告。此坑对所有 shader 通用，须写进迁移检查清单。

### 3.2 Metal 实时管线

| 环节 | 实现 | 结论 |
|------|------|------|
| 相机采集 | `AVCaptureSession` + `AVCaptureVideoDataOutput`，bi-planar YUV | ✅ |
| YUV→纹理 | `CVMetalTextureCache`，Y=R8Unorm(plane0) + UV=RG8Unorm(plane1) | ✅ |
| 渲染 | 全屏四边形（`vertex_id` 生成，无 VBO，TriangleStrip）→ fragment（BT.601 YUV→RGB + 美白）→ MTKView drawable | ✅ |
| uniform | `WhitenUniforms{whitening,maskEnabled}` 走 `setFragmentBytes:atIndex:1`，对应 `[[buffer(1)]]` | ✅ |
| Metal 库 | `newDefaultLibraryWithBundle:[NSBundle mainBundle]`，`.metal` 编进 `default.metallib` | ✅ |

### 3.3 真机结果（iPhone 15）

| 指标 | 结果 |
|------|------|
| 编译 | 模拟器 arm64 + 真机 arm64 均 BUILD SUCCEEDED，零 error 零 warning |
| 安装/启动 | 自动签名（profile 自动生成）+ 安装 + 启动成功 |
| 预览出图 | ✅ 实时画面正常 |
| 帧率 | **FPS:30**（相机 30fps 供帧，渲染跟得上；远优于 [PERF] 交互 <100ms / 帧间 ~33ms） |
| 美白功能 | ✅ 滑杆 0→1 画面明显变白发亮（uniform 流通 + whitenSkin 路径功能正确） |
| 方向 | ✅ `AVCaptureConnection.videoOrientation=Portrait` 后竖屏正向 |

### 3.4 调试历程（真机观察工具链受限下逐项定位）

设备日志/截屏工具链不可用（`xcrun log stream` 仅本机、devicectl 无截屏子命令、本机无 libimobiledevice），改用「**把状态画到屏幕**」的诊断法（NSTimer 0.5s 刷新标签：相机授权状态 + 帧计数 + FPS）。按此定位并修复 4 个真机 bug：

1. **`commandQueue` 漏初始化**（重写时丢）→ `commandBuffer` 为 nil → 所有 Metal 指令 no-op → 黑屏。补 `self.commandQueue = [self.device newCommandQueue]` 修复。
2. **相机权限需显式请求**：`AVCaptureDevice.requestAccessForMediaType` 前置，否则经 devicectl 拉起时自动弹窗不可靠，无帧。
3. **画面偏转**：传感器横置，须设 `connection.videoOrientation=Portrait`。
4. （非运行时）ObjC 前置 `@class` 不足以发 `alloc/init` 消息，须完整 `@interface` 前置；`MTLDevice` 无 `newDefaultLibraryWithError:`，用 `newDefaultLibraryWithBundle:error:`。

> 教训：iOS 真机无日志可达时，**把内部状态（权限/帧计数/错误）显式渲染到 UI** 是最稳的可观测手段，写进 Phase 5 iOS 调试 SOP。

---

## 4. 对改造计划的影响

### 4.1 ⚠️ 计划两处描述需修正（事实核对）

| 计划位置 | 原描述 | 实际 | 修正 |
|----------|--------|------|------|
| Phase 2.4 L119 | 美颜引擎「独立 **C++/OpenGLES** 渲染管线（`beauty-engine/src/main/cpp/`）」 | `cpp/` 仅 6 个 MNN 人脸推理文件；**渲染管线宿主是 Kotlin**（`render/` 19 文件 6185 行）+ GLSL shader 是 `assets/shaders/` 文本模块 | 改述为 Kotlin 宿主 + GLSL assets |
| Phase 5.4 L177 | 美颜引擎「**C++ 直桥**，走 Phase 2.1 产物」 | shader 可移植（GLSL→MSL），但**管线宿主无法直桥**——绑定 EGL/GLES/SurfaceTexture，iOS 须用 Swift/Metal/AVFoundation 从零重写 | 改为「shader 移植 + 宿主重写」，非直桥 |

### 4.2 全滤镜迁移难度分级（25 shader，共 1516 行）

shader 分两套体系：**体系 A = MainShader 拼接巨函数**（`ShaderModuleLoader` 按 `MODULE_ORDER` 纯文本 concat 成 1 个 fragment，单 pass）；**体系 B = 独立 Pass shader**（FBO ping-pong 串联）。

| 难度 | 占比 | 代表文件 | 说明 |
|------|------|----------|------|
| **easy** | ~60% | `header/uniforms_2d/warp(vertex侧)/colorgrade/style posterize+crosshatch/makeup vertex+fragment` | 纯算术或单采样，机械替换 |
| **medium** | ~30% | `skin(5x5双边)/pass_smoothing(7x7+4 LUT)/lip(blush polygon mask)/style toon+sketch+emboss(8邻域)/main(分支+ColorMatrix)` | 多采样卷积 / polygon 射线 / 数组循环 |
| **hard** | ~10% | **`warp.glsl` / `warp_gpupixel_thinface.glsl` / `warp_gpupixel_bigeye.glsl`** | 几何反向 UV 形变（瘦脸/大眼）+ `uFacePoints[212]` 动态索引 + 反向映射公式 |

**结论**：真正 hard 的只有 3 个 warp 文件（算法须逐行理解反向映射）；其余 ~90% 是机械转换。MSL 语法转换无阻塞，唯一通用坑是 GLSL `const` 局部标量 → MSL `constexpr`（见 §3.1）。

### 4.3 渲染宿主重写范围（Kotlin，19 文件 6185 行）

| 类别 | 文件 | iOS 处置 |
|------|------|----------|
| **EGL/GLES 上下文** | `EGLCore.kt` / `WindowSurface.kt` / `PhotoProcessorImpl.kt`（Pbuffer 离屏+`glReadPixels`） | **完全重写**（Metal 无 context/makeCurrent/swapBuffers，用 `MTLDevice`+`MTLCommandQueue`；拍照离屏用 `MTLRenderPassDescriptor`+blit `getBytes`） |
| **相机预览纹理输入** | `CameraPreviewRenderer.kt`（`SurfaceTexture`+`samplerExternalOES`+`updateTexImage`+`getTransformMatrix`） | **完全重写**（`AVCaptureVideoDataOutput`+`CVMetalTextureCache`，本次 spike 已验证路径） |
| **View 封装** | `BeautyPreviewView.kt`（`TextureView`/`SurfaceTexture`） | **重写**（→ `MTKView`/`CAMetalLayer`） |
| **GL program/FBO** | `ShaderProgram.kt`/`ShaderModuleLoader.kt`/`Framebuffer.kt`/`GLRenderer.kt`/`BeautyPass.kt` | **重写**（无 program 链接/FBO 概念，用 `makeLibrary`/`makeFunction`/`MTLTexture` render target） |
| **管线编排逻辑** | `BeautyRenderer.kt`（1618 行，多 pass 顺序、uniform 数据准备、`glUniform1fv(uFacePoints,212)`） | **设计可保留，调用全改 Metal 等价** |
| **LUT/网格** | `LutTextureLoader.kt`/`FaceMakeupPass.kt`（106 顶点 `glDrawElements`） | 重写（`MTLTexture` from UIImage / `drawIndexedPrimitives`） |
| **枚举** | `StyleEffect.kt` | 直接移植 |
| **帧同步 framesync** | `FrameSyncManager`/`FrameSyncBridge`（基于 `SurfaceTexture.timestamp`+FrameId） | **框架可设计性保留，时间戳源改 `CMSampleBuffer.presentationTime`** |

> 唯一几乎无需改的只有 `StyleEffect.kt`（纯枚举）。绑定 Android/EGL/GLES/SurfaceTexture 的部分**无法移植，必须从零重写**。

### 4.4 shader 拼接机制 → Metal 决策

`ShaderModuleLoader` 是**纯字符串 concat**（`buildString`+`appendLine`，无 `#include`/`#pragma`/宏），依赖拼接顺序决定符号可见性（前定义后调用，如 `main.glsl` 调 `warp.glsl` 的 `warpCoord`）。Metal 两选一：

- **方案 1（最小改动，推荐 Phase 5.4）**：保留 concat，拼成单个 `.metal` 源串 `device.makeLibrary(source:)`。注意 MSL 函数默认 `static`/符号可见性。
- **方案 2（重构）**：改 `#include` + metallib 函数库。但当前模块非独立完整函数库单元，拆分重构成本大。

### 4.5 uniform → Metal buffer struct

Metal 无 GLSL「按名全局 uniform」，须打包 `struct BeautyUniforms{...}` 放 `MTLBuffer`（或 `setFragmentBytes`）。重点：

- 标量/向量（`uWhitening`/`uSmoothing`/几何中心 vec2×8/调色 float×11/`uCMRow0..3`+`uCMOffset`）→ struct 字段。
- **数组（必须 buffer）**：`uFacePoints[212]`（106 点关键点，warp 核心）、4 组轮廓 `uLipOuter/InnerContourPoints[20]`、`uLeft/RightCheekContourPoints[20]`。注意 `constant` 地址空间 struct 需 `alignas(16)`；`uFacePoints` 动态索引（`getFacePoint(int)`→`uFacePoints[index*2]`）在 Metal `constant` 数组合法。
- `pass_copy.glsl` 的 `mat4 uTextureTransform`（SurfaceTexture 变换矩阵）iOS 上**不复存在**，可删。

### 4.6 工作量重估

| 项 | 计划原估 | 本次评估 | 说明 |
|----|----------|----------|------|
| shader 翻译（25 文件） | 含在 1–2 周 | ~1 周 | 90% 机械，3 个 warp 须算法理解 |
| 渲染宿主重写（Kotlin→Swift/Metal） | **未计入** | ~2 周 | EGL/SurfaceTexture/program 全重写，是真正大头 |
| **合计 Phase 5.4 美颜部分** | 1–2 周 | **~3 周** | 计划偏紧，缺宿主重写 |

---

## 5. Spike 产物

- **Spike 工程源码**：`tmp/beauty-metal-spike/`（`BeautyMetalSpike.xcodeproj` + `Shaders.metal` + `main.mm` + `Info.plist`）— 不入库
- **关键产物**：美白 GLSL→MSL 直译 + 完整 AVCaptureSession/Metal 相机管线（ObjC++ 单文件）

## 6. 结论

**GO**。美白单滤镜 Metal 实时渲染真机达标（30fps 出图、滑杆美白即时可见）；GLSL→MSL 翻译可行且 90% 机械（3 个 warp 偏硬）。**Phase 5.4 的真正成本在 Kotlin 渲染宿主重写（EGL/GLES/SurfaceTexture → Metal/AVFoundation）而非 shader 翻译**，计划 1–2 周估算偏紧，建议按 shader 翻译 ~1 周 + 宿主重写 ~2 周 计（共 ~3 周）。须同步修正计划 L119/L177 两处「C++/直桥」误述。本次踩坑（MSL `const`→`constexpr`、`commandQueue` 初始化、相机显式 `requestAccess`、`videoOrientation`、iOS 无日志可达时状态画屏法）纳入 Phase 5.4 检查清单。
