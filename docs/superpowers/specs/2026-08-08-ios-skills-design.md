# iOS Skill 体系补充设计

> **日期**：2026-08-08
> **状态**：已确认（用户审批：精选 iOS 集 + kmp-ios-interop 单独成 skill）
> **上游**：`docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md`（Phase 5 iosApp 骨架）、`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`
> **输入**：现有 21 个 Android skill（`skills/` SSOT + `.claude/commands/` 镜像）、`scripts/check-skill-sync.sh`、`skills/TEMPLATE.md`、`.claude/CLAUDE.md`、`.kimi/AGENTS.md`
> **下游**：细粒度实现计划（writing-plans 产出）

---

## 0. 背景与目标

现有 skill 体系（21 个）全部围绕 Android 端建设。Phase 5 将启动 iosApp 工程（Swift / SwiftUI / Metal / KMP），需补充 iOS 向 skill，使 Claude Code / kimi / OpenCode 三端在 iOS 开发时拥有与 Android 对等的专家能力。

**出口定义**：7 个 iOS 专属 skill 落地（SSOT + 镜像），`./scripts/check-skill-sync.sh` 0 漂移，索引文档（`.claude/CLAUDE.md` / `.kimi/AGENTS.md`）更新。

**红线继承**：`[PRIVACY]` 媒体 100% 端侧、`[PERF]` 交互 <100ms / 快门 <50ms、`[I18N]` 三语从第一天起算 —— iOS 同约束。

---

## 1. 决策锁定

| # | 决策 | 结论 | 依据 |
|---|------|------|------|
| D1 | 结构 | **精选 iOS 集**（非 1:1 镜像、非双端合并） | YAGNI；跟 Phase 5 节奏；避免无意义薄 skill（Metal 无 EGL 状态机等） |
| D2 | 数量 | **7 个** iOS 专属 skill | 覆盖 Phase 5 全部 iOS 技术面，defer 非急需项 |
| D3 | kmp-ios-interop | **单独成 skill**（不并入 ios-build-debug） | Kotlin/Native ↔ Swift 互操作是跨切面反复出现的 R2 级痛点，值得独立专家能力 |
| D4 | 命名 | 平台专属用 `ios-` 前缀对齐 Android 兄弟；领域专家按技术命名 | 可发现性 + 与现有命名一致 |
| D5 | 跨平台 skill | coordinate-system-standard 原地加 iOS 小节；perf/ui-driver defer | 坐标逻辑平台无关；Instruments/XCUITest 差异大且非 Phase 5 急需 |
| D6 | 文件规范 | 严格遵循 `skills/TEMPLATE.md` | 一致性；`check-skill-sync.sh` 可校验 |

---

## 2. 新增 7 个 iOS skill 明细

### 2.1 `ios-build-debug` ↔ android-build-debug

- **定位**：iOS 工程编译、模拟器/真机安装、日志与调试标准化流程。
- **触发**：编译 iOS、simctl 安装、排查 xcodebuild 构建问题、真机调试时。
- **核心内容**：
  - xcodebuild 分层编译（swiftc 语法 → compile → build），`-scheme PoLang -destination 'generic/platform=iOS'`
  - simctl：boot / install / launch / uninstall、`simctl io screenshot`
  - **DebugOverlay 状态画屏**：真机日志工具链不可用（`xcrun log stream` 仅本机、devicectl 无截屏、无 libimobiledevice）→ 权限态/帧计数/FPS/错误文本画屏是最稳可观测手段
  - 签名：免费账号 7 天重签、ad-hoc 真机包、付费 Developer Program（TestFlight 硬前置）
  - Swift 错误速查 + 联动 error-healer
- **项目路径**：`iosApp/`、`PoLang.xcodeproj`、`PrivacyInfo.xcprivacy`、`Frameworks/MNN.framework`

### 2.2 `ios-dev-loop` ↔ dev-loop

- **定位**：iOS 编译 → 安装 → 截屏 → 对比闭环。
- **核心内容**：`scripts/ios-dev-loop.sh`：xcodebuild build → simctl install/launch → `simctl io screenshot` → `screenshot-diff.py` 对比基线（对标 `auto-dev-loop.sh`）。

### 2.3 `metal-render-expert` ↔ av-gl-expert + egl-state-machine

- **定位**：Metal / MSL 渲染管线诊断（美颜相机宿主）。
- **触发**：Metal 黑屏 / shader / 渲染管线问题、GLSL→MSL 翻译、美颜 pass 链调试。
- **核心内容**：
  - Metal 生命周期：`MTLDevice` / `MTKView` / `CAMetalLayer`（**Metal 无 EGL context / makeCurrent / swapBuffers**）
  - `CVMetalTextureCache`：YUV bi-planar（Y=R8Unorm + UV=RG8Unorm）
  - **GLSL→MSL 翻译纪律**：`const` 局部标量 → `constexpr`（`constant` 是地址空间）、uniform 打包 `BeautyUniforms` 走 `setFragmentBytes`、`uFacePoints[212]` 走 MTLBuffer、`uTextureTransform`（SurfaceTexture 矩阵）删除、`clamp(x,0,1)` → `saturate`、`texture2D` → `texture.sample`、`device.makeLibrary(source:)` concat
  - 错误检查：MTLCompileError、`makeLibrary` 错误日志
  - 帧同步：时间戳源改 `CMSampleBuffer.presentationTime`
  - 参照：`tmp/beauty-metal-spike/main.mm`（298 行完整管线）+ `Shaders.metal`
- **相关**：coordinate-system-standard（106pt）、mnn-ios-integration（人脸检测前置）

### 2.4 `swiftui-expert` ↔ compose-ui-expert

- **定位**：SwiftUI 布局 / 状态 / 重组 / Preview 诊断，双端视觉对标。
- **核心内容**：
  - 单一状态源：feature 内一个 `ObservableObject` 持全部 UI 态（枚举优于条件）
  - **SwiftUI Preview 全覆盖**：PreviewProvider + 代表性 mock 态（空 / Loading / Limited / 1000 图）
  - **`accessibilityIdentifier` 全量标注**：为 XCUITest / 未来 ios-ui-driver 铺路，不靠图像识别
  - 陷阱：闭包捕获、`@State` 误用、body 重组、LazyVGrid key 缺失
  - HyperOS 视觉规范双端对标（大圆角 / 毛玻璃 / Primary `#00E5FF`）
- **相关**：compose-ui-expert（Android 对照）

### 2.5 `mnn-ios-integration` ↔ mnn-integration + mnn-landmark-diagnosis

- **定位**：MNN.framework iOS 构建 / embed 与人脸检测推理诊断。
- **核心内容**：
  - 构建：`build_lib.sh --ios` 产出 arm64 `MNN.framework`，搬正 `iosApp/Frameworks/`
  - embed：`OTHER_LDFLAGS=-ObjC` + Metal / UIKit / Foundation 显式链接
  - RetinaFace + 106 关键点（Phase 5 仅 det_500m）
  - **补验 A 关键坑**：`precision = Precision_High`（默认 Normal/fp16 数值完全错误）；显式构造 `backendConfig`（nullptr 解引用即 SIGSEGV）
  - 与 mnn-landmark-diagnosis 的 106pt 解析 / 映射逻辑联动（坐标体系同源）
  - Qwen3-VL-2B iOS（补验 B 暂缓）→ 标注 Phase 6 触发
- **相关**：mnn-landmark-diagnosis、coordinate-system-standard

### 2.6 `kmp-ios-interop`（新增，无 Android 对标）

- **定位**：Kotlin/Native ↔ Swift 互操作铁律与 shared framework 集成。
- **触发**：shared framework 集成、Kotlin ↔ Swift 边界、XCFramework embed、Flow → Swift、signal 6 崩溃时。
- **核心内容**：
  - **`@Throws` 不导出异常会 signal 6 崩溃**：所有 shared → Swift 边界在 Kotlin 侧 try/catch 兜底为 Result / 字符串；`SharedBridge/` 统一此约定
  - Flow → Swift `AsyncStream` 转换（打字机动画等）
  - XCFramework embed：`./gradlew :shared:assembleSharedDebugXCFramework` → Build Phase 脚本按 Gradle 构建 hash 重拷（避免每次 Xcode 编译触发 Kotlin 全量）；Release ~4min 一次性
  - 组合根 D7 模式：shared 不知任何 iOS 类型；无 `PlatformContext` expect；`AppContainer.swift` 构造注入 actual 进 shared
  - framework 体积监控；retain cycle 防范
- **相关**：ios-build-debug、doc-sync-guardian

### 2.7 `ios-i18n-validator` ↔ i18n-validator

- **定位**：iOS 三语（EN / zh-CN / zh-TW）同步与文案规范。
- **核心内容**：
  - `Localizable.xcstrings` 三语同步，禁硬编码文案
  - 键命名规范；与 Android `strings.xml` 键对齐（S5 双端一致）
  - SwiftUI Text 自动本地化、复数 / 格式化
- **相关**：i18n-validator（Android）

---

## 3. 跨平台 skill 处置

- **coordinate-system-standard**：原地加 iOS 小节（仅注 Metal / CVMetalTexture 输入差异；坐标逻辑同源）。
- **perf-optimizer**：本轮 defer；`ios-build-debug` 内放 Instruments（fps / 内存）基础指引；Phase 5.3 触发时评估独立 `ios-perf`。
- **ui-driver**：本轮 defer；`swiftui-expert` 内强调 `accessibilityIdentifier` 铺路；Phase 5/6 写 XCUITest 时加 `ios-ui-driver`。

---

## 4. 命名规则

- 平台专属 build / tooling / i18n：`ios-` 前缀，与 Android 兄弟对齐（`ios-build-debug` ↔ `android-build-debug`；`ios-dev-loop` ↔ `dev-loop`；`ios-i18n-validator` ↔ `i18n-validator`）。
- 领域专家按技术命名：`metal-render-expert`、`swiftui-expert`、`mnn-ios-integration`、`kmp-ios-interop`。

---

## 5. 文件规范与同步

- **SSOT**：`skills/<name>/SKILL.md`，遵循 `skills/TEMPLATE.md`（frontmatter: `name` / `description` / `version: 1.0.0` / `created: 2026-08-08` / `updated: 2026-08-08` / `maintainer: [RD] 全栈工程师` / `tags`；正文 < 500 行；代码块 > 30 行入 `reference.md`）。
- **镜像**：`.claude/commands/<name>.md`（去 frontmatter）。
- `.kimi/skills` 软链自动覆盖 kimi / OpenCode。
- **收口校验**：`./scripts/check-skill-sync.sh` → 0 漂移（SSOT 21 → 28，镜像 21 → 28）。

---

## 6. 索引文档更新

- `.claude/CLAUDE.md`：新增「📱 iOS 开发」分组（7 条），命令数 21 → 28；"最近整理"注记加 iOS 条目（2026-08-08）。
- `.kimi/AGENTS.md`：项目速览「类型」补 iOS 维度。

---

## 7. 明确不做（YAGNI，后续触发再加）

- `ios-ui-driver`（XCUITest）→ Phase 5/6 写 UI 测试时。
- `ios-perf`（Instruments 专项）→ Phase 5.3 性能实测时。
- `mnn-llm-ios`（Qwen3-VL-2B）→ 补验 B 恢复 / Phase 6.1。
- `onnx-ios` → 视 Phase 6 是否需要。

---

## 8. 风险

| # | 风险 | 缓解 |
|---|------|------|
| R1 | iOS spike 产物在 `tmp/`（beauty-metal-spike、mnn-ios-spike）随清理可能丢失 | skill 内固化关键结论（翻译纪律、补验 A 坑），不依赖 tmp/ 文件常驻 |
| R2 | skill 内容基于 Phase 4/5 计划（非成品），接口签名可能漂移 | 按 shared / iosApp 实际出口落地后复核 skill 引用（与 app-skeleton spec §9 校对点 2 一致） |
| R3 | Metal 翻译纪律随实际 shader 移植积累新坑 | metal-render-expert 设版本历史，移植期增量补 |

---

## 9. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-08 | 初版：brainstorming 确认（D1–D6）；7 skill 明细；kmp-ios-interop 单独成 skill（用户确认） |
