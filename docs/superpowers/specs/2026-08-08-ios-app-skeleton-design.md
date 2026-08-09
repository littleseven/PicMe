# Phase 5 iOS App 骨架设计（iosApp）

> **日期**：2026-08-08
> **状态**：已确认（用户逐节审批通过）
> **上游**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` §Phase 5
> **输入**：`2026-08-08-ios-beauty-metal-spike-design.md`（2.4 GO）、`2026-08-07-ios-mnn-spike-design.md`（2.1 有条件 GO，补验 A/C ✅ B 暂缓）、`2026-08-07-ios-spm-quickjs-spike-design.md`（2.2 GO）、`2026-08-07-kmp-koog-spike-design.md`（2.3 GO）、`plans/2026-08-07-shared-kmp-extraction.md`（Phase 4，消费面来源）
> **下游**：细粒度实现计划 `plans/2026-08-XX-ios-app-skeleton.md`（writing-plans 产出，Phase 3 收尾 + Phase 4 完成后执行）

---

## 0. 决策锁定（本设计的全部拍板）

| # | 决策 | 结论 | 依据 |
|---|------|------|------|
| S1 | Swift/shared 边界 | **分模块**：相册 Swift 主导 presentation，相机管线纯 Swift/Metal，Agent 链路薄壳复用 shared | 平台差异度与 shared 依赖度反向匹配；roadmap「双端相册差异各端自治」 |
| S2 | 美颜双端方案 | **方案 A**：iOS Swift/Metal 宿主重写 + GLSL→MSL 翻译；否 C++ GLES 双端（deprecated API + 动 Android 已验证宿主，冲撞零回归红线） | 2.4 spike 实证：成本在宿主不在 shader；Metal 路径已真机验证 |
| S3 | 美颜范围 | **MVP 子集**进 5.4：磨皮/美白/瘦脸/大眼 + LUT 风格滤镜；全量 25 shader 移 Phase 6 | 降低压轴段风险；TestFlight 门不含全量滤镜 |
| S4 | UI 生产方式 | **AI 生成 + 可调试性内建**（取代 roadmap「前几页自己写」学习框架） | 用户决策 2026-08-08；可调试性为验收项 |
| S5 | 最高原则 | **双端体验一致**：交互/文案/默认值/滤镜强度观感对标 Android；差异仅限平台范式并列差异清单 | 用户决策 2026-08-08 |
| S6 | 部署目标 | **iOS 16+** | SwiftUI PhotosPicker/NavigationStack 够用；国行测试机满足 |
| S7 | 签名/分发 | 免费账号开局（7 天重签可接受）；**付费 Developer Program 是 5.5 TestFlight 硬前置** | 用户确认账号现状未确定 |
| S8 | shared 集成 | **手动 embed XCFramework**（debug 日常 ~6s，Release ~4min 一次性）；不走 SPM 远程分发 | 2.3 spike 已验证 embed 路径 |
| S9 | 工程结构 | **呼应 androidApp 分层**（features/di/navigation 一一对应） | 双端肌肉记忆 + AI 跨端移植按名对齐 |
| S10 | Swift 架构 | feature 内 MV（`ObservableObject` + async/await），无 TCA/第三方架构框架 | 学习成本与 AI 生成可靠度最优 |

工期总估 **6–8 周**（roadmap 6–10 周区间内）：5.1 ~1–1.5w，5.2/5.3 ~1.5–2w，5.4 ~3w，5.5 ~0.5w，缓冲 ~1w。S4 使「学习缓冲」权重下降、「调试基建」权重上升。

---

## 1. 目标与范围

**出口门（Phase 5 完成定义）**：TestFlight 内测包可用——相机预览（含 MVP 美颜）+ 拍照 + 相册浏览。

**范围内**：iosApp 工程基建、相册（浏览/权限/性能）、相机管线（采集/美颜/拍照/手势）、调试与闭环验证基建。

**范围外（Phase 6）**：TAG 流水线（6.1）、Chat（6.2，见 §7 前瞻边界）、设置与账号（6.3）、server iOS 适配（6.4）、全量 25 美颜 shader、Qwen3-VL-2B 集成（补验 B 恢复触发点：Phase 5 启动前或 6.1 接入前——按用户此前决策，本 Phase 不阻塞）。

**红线**：[PRIVACY] 媒体 100% 端侧（iOS 同约束）、[PERF] 交互 <100ms / 快门 <50ms、[I18N] 三语同步从第一天起算。

---

## 2. 总体架构与工程结构

### 2.1 目录结构（呼应 androidApp）

```
polang/
├── iosApp/                              # 新建，唯一 Xcode 工程
│   ├── PoLang.xcodeproj                 # 手工维护（不用 XcodeGen/Tuist）
│   ├── PoLang/
│   │   ├── App/                         # ↔ PoLangApplication/MainActivity：@main 入口、生命周期
│   │   │   └── DebugOverlay             # 「状态画屏」调试基建（5.1 第一天做）
│   │   ├── DI/                          # ↔ di/AppContainer.kt：AppContainer.swift 组合根（D7 模式）
│   │   ├── Features/
│   │   │   ├── Main/                    # ↔ features/main/ + navigation/：Tab 骨架
│   │   │   ├── Gallery/                 # ↔ features/gallery/
│   │   │   ├── Camera/                  # ↔ features/camera/（内部 Preview/ Beauty/ Capture/）
│   │   │   ├── Chat/                    # ↔ features/chat/（Phase 6.2 预留空壳）
│   │   │   └── Settings/                # ↔ features/settings/（Phase 6.3 预留空壳）
│   │   ├── Platform/                    # shared 接口的 iOS actual（PHPhotoLibrary、文件、日志、权限）
│   │   └── SharedBridge/                # shared framework 的 Swift 薄包装（Flow→AsyncStream、异常兜底）
│   ├── Frameworks/
│   │   └── MNN.framework                # Phase 2.1 产物（10MB arm64，从 tmp/mnn-ios-spike 搬正）
│   └── PrivacyInfo.xcprivacy            # Privacy Manifest（5.1 第一天建）
├── shared/                              # Phase 4 产物，Gradle 构建 XCFramework
└── engines/                             # MNN/sentencepiece iOS 构建脚本收编处（5.1 从 tmp/ 搬正归档）
```

### 2.2 Android 层 → iOS 去向映射表

| Android 层 | iOS 去向 |
|-----------|----------|
| `domain/`（模型/use case） | **shared commonMain**（不进 iosApp） |
| `data/`（仓储实现） | shared 接口 + `Platform/` 的 Swift actual |
| `di/AppContainer.kt` | `DI/AppContainer.swift`（组合根，构造注入 actual 进 shared） |
| `navigation/` + `features/main/` | `Features/Main/` |
| `features/x/` | `Features/X/`（名称一一对应） |
| `core/`（平台工具） | `Platform/` 或 `SharedBridge/` |
| `runtime-core`（Agent 编排） | **shared commonMain**（Phase 4 消亡后），Swift 不碰编排 |

### 2.3 依赖方向

```
SwiftUI View → ObservableObject（UI 态唯一持有者）
    → shared framework（领域/Agent，Kotlin）
        → 平台接口 actual（Swift 实现，经 AppContainer 构造注入回 Kotlin）
```

- 遵循 Phase 4 D7 组合根模式：shared 不知道任何 iOS 类型；无 `PlatformContext` expect。
- Kotlin 异常不经 `@Throws` 导出会 **signal 6 崩溃**（2.3 spike 坑 1）：所有 shared→Swift 边界在 Kotlin 侧 try/catch 兜底为 Result/字符串，`SharedBridge/` 统一此约定。
- `Camera/` 内部不强行镜像 Android `preview/gl/` 分层（Metal 宿主是新写的），但美颜参数类型名与 shared `BeautySettings`/`FilterType` 对齐。

---

## 3. 基建设计（5.1，~1–1.5 周）

- **Xcode 工程**：手工建工程，两 target（App + 单元测试）。Swift 侧只测 `Platform/` actual；逻辑测试全部在 shared JVM 侧（Phase 4 出口标准）。
- **签名**：免费账号，7 天重签限制开发期可接受；付费账号列为 5.5 硬前置（风险 R1）。
- **shared 集成**：`./gradlew :shared:assembleSharedDebugXCFramework` → embed；Build Phase 脚本按 Gradle 构建 hash 重拷，避免每次 Xcode 编译触发 Kotlin 全量。
- **MNN framework**：搬正到 `iosApp/Frameworks/`；构建脚本收编 `engines/mnn-core/`（`build_lib.sh --ios` 封装 + `OTHER_LDFLAGS=-ObjC` + Metal/UIKit/Foundation 显式链接）。**Phase 5 只集成人脸检测（det_500m）**；落实补验 A 结论：`precision = Precision_High`（默认 Normal/fp16 数值完全错误）+ 显式构造 `backendConfig`（nullptr 解引用即 SIGSEGV）。
- **sentencepiece**：Phase 5 不需要（TAG 属 Phase 6）；`tmp/mnn-ios-spike/spm-ios-build/CMakeLists.txt` 趁基建期收编 `engines/sentencepiece/` 归档，免 Phase 6 再挖 tmp/。
- **Privacy Manifest**：第一天建，先声明已知 required-reason API（FileTimestamp/DiskSpace 等），每 feature 落地时增量补。
- **CI**：`xcodebuild -scheme PoLang -destination 'generic/platform=iOS' build`（无签名 build-only）与 Android CI 并列。
- **I18N**：`Localizable.xcstrings` 三语同步，禁硬编码文案。
- **闭环验证脚本**：新增 `scripts/ios-dev-loop.sh`（对标 `auto-dev-loop.sh`）：`xcodebuild build → simctl install/launch → simctl io screenshot → screenshot-diff 对比基线`。
- **调试基建**：DebugOverlay（权限态/帧计数/FPS/错误文本画屏）第一天做好——spike 实测 iOS 设备日志工具链不可用（`xcrun log stream` 仅本机、devicectl 无截屏、无 libimobiledevice），「状态画屏」是最稳可观测手段；xcode-kotlin 断点已装（命中待手动确认，非阻塞）。

---

## 4. 相册设计（5.2 + 5.3，~1.5–2 周）

### 4.1 平台 actual（`Platform/SharedMediaRepository.swift`）

- 实现 shared `MediaRepository` 接口（Phase 4 Task 4 commonMain 版，id 用 `String`，无 `Uri`）。
- `PHFetchResult` + `PHCachingImageManager` 取数；`PHPhotoLibraryObserver` 变更监听推 flow。
- Android 的 `IntentSender` 删除授权四方法不进接口；iOS 删除走 `PHAssetChangeRequest` 系统确认弹窗（天然免授权逻辑）。

### 4.2 权限状态机（Swift 自写，本模块核心）

`GalleryPermissionStore: ObservableObject`，映射 shared `AccessState` 四态：

| 状态 | iOS 行为 |
|------|----------|
| `Full` | 正常网格 |
| `Limited` | 网格只显示已选照片 + 常驻「管理可访问照片」入口（`presentLimitedLibraryPicker`）+ 变更监听刷新；**一等公民非降级提示**（App Store 加分项） |
| `AddOnly`（iOS 特有） | 只进相机/保存流，相册页引导开权限 |
| `Denied` | 空态 + 跳设置 |

### 4.3 ViewModel 与视图

- `ObservableObject` 订阅数据流；分组逻辑调 shared use case（`GetGroupedMediaUseCase`）；自己只维护 UI 态（选中/滚动/权限呈现）。
- 视图 AI 生成（S4）：`LazyVGrid` 网格（缩略图异步加载 + 取消）、按日分组 section header、大图 `TabView` 分页、相簿列表页。交互对标 Android `MediaGrid.kt`/`MediaPager.kt`，布局代码全新。

### 4.4 可调试性验收项（S4，全 feature 通用）

- **单一状态源**：每 feature 一个 `ObservableObject` 持全部 UI 态（对标 Android `CameraStateMachine`，枚举优于条件）；
- **SwiftUI Preview 全覆盖**：每组件 PreviewProvider + 代表性 mock 态（空/Loading/Limited/1000 图），第一环自验证不依赖真机；
- **`accessibilityIdentifier` 全量标注**：网格 cell/权限按钮/分组头带稳定标识，为 XCUITest / 未来 ui-driver 等价物铺路，不靠图像识别；
- **DebugOverlay**：权限态/照片计数/fetch 耗时画屏。

### 4.5 性能实测（5.3）

真机 1000+ 照片，Instruments 验证：滚动 55–60fps、内存峰值 <500MB、缩略图缓存命中率。不达标先调 `PHCachingImageManager` 预热窗口，不改架构。

### 4.6 验收

同一照片集双端对照：Android/iOS 网格排序、分组边界、数量完全一致（领域逻辑同源的自然验证）。

---

## 5. 相机管线设计（5.4，~3 周；方案 A）

### 5.1 渲染链路（2.4 spike 已验证路径直接搬）

`AVCaptureSession`（720p 起步，YUV bi-planar）→ `CVMetalTextureCache`（Y=R8Unorm + UV=RG8Unorm）→ 美颜 pass 链 → `MTKView` drawable。`tmp/beauty-metal-spike/main.mm`（298 行完整管线）+ `Shaders.metal`（YUV→RGB 基线）直接参照，重写为 Swift。

### 5.2 宿主重写映射表（源：`engines/beauty-engine/.../render/` 19 文件 6185 行）

| Android（Kotlin/GLES） | iOS（Swift/Metal） | 处置 |
|------------------------|--------------------|------|
| `EGLCore`/`WindowSurface`/`PhotoProcessorImpl` | `MTLDevice`/`CAMetalLayer` 生命周期 | 完全重写（Metal 无 context/makeCurrent/swapBuffers） |
| `CameraPreviewRenderer` | spike 路径（AVCaptureSession→CVMetalTextureCache） | 完全重写 |
| `BeautyPreviewView` | `MTKView` 封装 | 完全重写 |
| `ShaderProgram`/`ShaderModuleLoader`/`Framebuffer`/`FramebufferPool`/`GLRenderer`/`BeautyPass` | pipeline state / shader concat / texture pool / render pass | 重写 |
| `BeautyRenderer`（1618 行管线编排） | pass 链结构保留，调用全改 Metal 等价 | 设计保留 |
| 帧同步 framesync | 框架保留，时间戳源改 `CMSampleBuffer.presentationTime` | 设计保留 |
| `StyleEffect`（纯枚举） | shared `FilterType`/`StyleFilter`（Phase 4 已迁 commonMain） | 原样复用 |

### 5.3 shader 移植（MVP 子集，S3）

- **第一批（进 5.4）**：磨皮、美白、瘦脸（`warp_gpupixel_thinface`）、大眼（`warp_gpupixel_bigeye`）+ LUT 风格滤镜——含 3 个 hard warp 中的 2 个（人脸形变是核心体验不能砍）。
- **第二批（移 Phase 6）**：其余补齐至全量 25 个。
- **翻译纪律**：concat 拼接 `device.makeLibrary(source:)`；`const` 局部标量一律 `constexpr`（`constant` 是地址空间）；uniform 打包 `BeautyUniforms` struct 走 `setFragmentBytes`；`uFacePoints[212]` 数组走 MTLBuffer；`uTextureTransform`（SurfaceTexture 矩阵）删除；`clamp(x,0,1)→saturate`、`texture2D→texture.sample`。

### 5.4 人脸关键点

MNN RetinaFace + 106 关键点（Phase 2.1 C++ 产物，`engines/mnn-core` iOS 封装）——warp 滤镜前置依赖，5.4 内联集成（非 Phase 6.1 全量 TAG 栈）。

### 5.5 拍照与手势

- `AVCapturePhotoOutput` 全分辨率捕获 → 同一美颜 pass 链离屏渲染 → `PHPhotoLibrary` 保存（触发 `AddOnly` 权限流，与 §4.2 衔接）。
- [PERF] 快门 <50ms：美颜离屏渲染异步化，不阻塞快门响应；连拍 10 张无掉帧卡顿。
- 手势：点按对焦（`focusPointOfInterest`）、捏合变焦（`videoZoomFactor`）、滑动曝光补偿；DebugOverlay 显示焦距/曝光值。

### 5.6 双端一致（S5）

美颜参数默认值/滑杆范围与 Android `BeautySettings` 完全一致（shared 纯类型天然保证）；滤镜名称/排序一致；同场景主观对照磨皮/美白强度观感。

### 5.7 验收

真机 30fps 预览（DebugOverlay 实时 FPS）；瘦脸/大眼滑杆即时可见；快门连拍 10 张达标；与 Android 同场景观感对照一致。

---

## 6. TestFlight 与出口标准（5.5，~0.5 周）

- **硬前置**：付费 Developer Program（S7，风险 R1）；免费账号下 5.5 只能交付 ad-hoc 真机包。
- **内测范围**：开发者自有设备 + 少量内部测试员；不做外部公测（Phase 6 再评估）。
- **出口检查单**：相机预览 + MVP 美颜 + 拍照 + 相册浏览可用；Privacy Manifest 完整；三语文案无硬编码；CI 双端绿；核心交互 [PERF] 达标。

---

## 7. Chat 前瞻边界（Phase 6.2，本 Phase 不实现，仅锁定边界）

- **薄壳主场**：shared `KoogChatAgent` + `ChatToolService` + JS 引擎无关层直接复用；QuickJS 走 KMP klib，iOS 零编译（2.2/2.3 已验证运行时）。
- **Swift 只写三块 UI**：消息列表 + 流式渲染（Kotlin `Flow`→Swift `AsyncStream`，打字机 SwiftUI Text 动画）；Markdown（`AttributedString(markdown:)` 打底）；输入栏 + 系统 PhotosPicker。
- **图卡契约不变**：JS 沙盒 `Chart.*` 产出 SVG 字符串的契约双端保持一致；iOS 用 **WKWebView 薄壳渲染 SVG**（不重写为 Swift Charts，避免 `ChartJs.kt` 生成逻辑双端漂移）。
- **图片类消息**：取图逻辑 Swift 重写（PHAsset），平台 actual。
- **存储**：`ChatMemoryStore` iOS actual 属 Phase 6，建议 SQLDelight（与 shared D2/D3 序列化决策一致），起步可 JSON 文件。
- **合规预埋**：App Store 2.5.2 风险在案——Chat 留「JS 执行白名单开关」配置位，iOS 可一键降级为纯白名单 handler 调用，不等 6.3 合规分析再改架构。

---

## 8. 风险登记册

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | 付费开发者账号未落实，5.5 TestFlight 阻塞 | 🟡 | 5.1 开工时确认；阻塞则以 ad-hoc 真机包交付 5.5，TestFlight 顺延 |
| R2 | Kotlin/Native ↔ Swift 互操作坑（retain cycle、异常 signal 6、类型映射语义损失） | 🟡 | 2.3 spike 对策已录（try/catch 兜底约定、`SharedBridge/` 统一）；framework 体积监控 |
| R3 | Phase 4 shared 消费面漂移（本设计基于 Phase 4 计划，非成品） | 🟡 | Phase 5 开工时按 shared 实际出口复核 §2.2/§4.1/§5.2 接口签名 |
| R4 | 美颜 warp shader 翻译（2 个 hard）低估 | 🟡 | spike 已定性「须逐行理解」；翻译+联调单列工项，不与其他 shader 混估 |
| R5 | iOS 调试工具链缺失拖慢排障 | 🟡 | DebugOverlay 状态画屏 5.1 固化为基建；xcode-kotlin 断点待手动确认 |
| R6 | Qwen3-VL-2B iOS 未验证（补验 B 暂缓中） | 🟡 | 不阻塞 Phase 5；恢复触发点 Phase 5 启动前或 6.1 接入前，届时同步验证 precision 档位 |
| R7 | 相册 Limited 权限流边界 case（选择器取消/变更监听时序） | 🔵 | §4.2 状态机四态全覆盖 + Preview mock 态验证 |

---

## 9. 校对点（执行前必须复核）

1. ~~**Phase 3 收尾落地后**~~ ✅ **已完成（2026-08-08，`adfcc57a` 落地后复核）**：本文档引用的 10 项路径/脚本/模块（`engines/mnn-core`、`engines/sentencepiece`、`engines/beauty-engine/.../render`、`tmp/mnn-ios-spike/` 两项、`tmp/beauty-metal-spike/`、`scripts/screenshot-diff.py`、`scripts/auto-dev-loop.sh`、`androidApp/.../features/gallery`、`androidApp/.../features/camera`）与 main 终态全部一致；`adfcc57a` 仅同步 Claude/kimi 配置引用，不影响本文档内容。
2. **Phase 4 完成后**：按 shared 实际出口复核 `MediaRepository`/`AccessState`/`GetGroupedMediaUseCase`/`BeautySettings` 的 commonMain 签名（R3）。
3. **补验 B 决策点**：Phase 5 启动前确认 Qwen3-VL-2B 真机补验是否仍暂缓。

---

## 10. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-08 | 初版：逐节 brainstorming 确认（S1–S10 决策锁定）；美颜方案反思后维持方案 A（否 C++ GLES 双端）；UI 生产方式由「自己写」改为「AI 生成 + 可调试内建」（S4）；双端体验一致为最高原则（S5） |
| 2026-08-08 | 校对点 1 完成：Phase 3 收尾（`adfcc57a`）落地后复核 10 项路径/脚本/模块引用全绿；roadmap Phase 5 段补回链 + S4 修订标注（修订十） |
| 2026-08-09 | 执行修订：§5.4 人脸关键点修正为 MediaPipe Face Landmarker + 468→106（原述 MNN RetinaFace 仅检测）；美颜方案 A（Metal 重写宿主 + GLSL→MSL 翻译）落地确认；免费账号 ad-hoc 路径落实（TestFlight 顺延 R1）；双端图标统一 Material Icons Round 同源决策（iOS SF Symbols → Material SVG 矢量资产 46 imageset）；相册缩略图低清根因修复（跳过 `.opportunistic` degraded 帧，`08798780`）；F1 主体框架补全（悬浮导航 + 设置页 + 4 页 Pager） |
