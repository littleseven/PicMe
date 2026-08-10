# 实例启动包：Phase 5 相册段 + 基建-KMP（K3 实例）

> **模型**：Kimi K3　**harness**：`kimi-code`　**轨**：Kotlin/KMP（+ 相册 Swift UI）
> **上游 SSOT**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` §3.1（并行模型与分工）
> **任务细则**：`docs/superpowers/plans/2026-08-08-ios-app-skeleton.md` Task 0/1/3 + 7–11
> **对侧**：相机段 + 基建-iOS 由一个 **GLM 实例**并行做（见 `2026-08-08-ios-camera-glm-kickoff.md`）

## 你 own 什么
- **基建-KMP**：Task 0（前置核对）、Task 1（shared iOS framework 打包 = SharedKit XCFramework）、Task 3（SharedKit embed + SharedBridge 冒烟）
- **相册段**：Task 7（相册数据通路：`IosMediaRepository` Kotlin iosMain + Swift PHPhotoLibrary 桥）、Task 8（权限状态机）、Task 9（网格页）、Task 10（大图 + 相簿）、Task 11（性能实测）

## 立即可做 / 开工门
- **开工门 = Phase 4 落 main**（产 XCFramework）。Phase 4 由另一 K3 实例收尾中、即将就绪。
- **Task 0 前置核对**：Phase 4 落 main 后核实出口——`AccessState` 存在、shared XCFramework 可构建（`./gradlew :shared:assembleSharedDebugXCFramework`）。
- 落地前可预读：Task 1/7 细则；`MediaRepository`/`AccessState` 的 commonMain 签名（Phase 4.2 已产出，落 main 后在 `shared/src/commonMain/.../domain/repository/`）。

> 两轨起步不同步是设计内的（见 §3.1 依赖图）：GLM 那条轨的 shader 翻译 / Task 6 零依赖可先跑；你这条轨的 Task 1 需 Phase 4 落地。Phase 4 一就绪你即开工。

## 先读（按序）
1. `docs/superpowers/plans/2026-08-07-shared-kmp-extraction.md`（Phase 4——理解 shared 结构 + iosMain actual 模式）
2. `docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md` §2 §4（工程结构 + 相册设计）
3. `docs/superpowers/plans/2026-08-08-ios-app-skeleton.md` Task 0/1/3 + 7–11
4. skill：`kmp-ios-interop`（signal 6 兜底 / Flow→AsyncStream / XCFramework embed hash 重拷）、`swiftui-expert`、`ios-i18n-validator`

## Task 7 的 KMP 缝（你的主场）
- **Kotlin 半（你写）**：`shared/src/iosMain/.../IosMediaRepository.kt`——实现 commonMain `MediaRepository` 接口（Phase 4.2 已定义，id 用 `String`，无 `Uri`）。
- **Swift 半（你也写）**：`iosApp/Platform/SharedMediaRepository.swift`——`PHFetchResult` + `PHCachingImageManager` 取数、`PHPhotoLibraryObserver` 变更推 flow；Kotlin `IosMediaRepository` 经 `SharedBridge` 调到 Swift Photos。
- `SharedBridge/` 统一约定：Kotlin 侧 try/catch 兜底为 Result/字符串（`@Throws` 不导出异常会 **signal 6 崩溃**）。

## 权限状态机（Task 8，本段核心）
`GalleryPermissionStore: ObservableObject` 映射 shared `AccessState` 四态：

| 状态 | iOS 行为 |
|---|---|
| `Full` | 正常网格 |
| `Limited` | 仅显已选 + 常驻「管理可访问照片」（`presentLimitedLibraryPicker`）+ 变更监听刷新——**一等公民非降级** |
| `AddOnly`（iOS 特有） | 引导开权限 |
| `Denied` | 空态 + 跳设置 |

**四态全覆盖 + Preview mock 态单测**（空 / Loading / Limited / 1000 图）。

## 与 GLM 实例的交接点
- **Task 3 embed 冒烟**：你产 XCFramework（Task 1），GLM 产 Xcode 工程（Task 2），合体冒烟。
- **Task 4 AppContainer**：GLM 写 Swift 组合根 wiring，注入你 shared 定义的接口 actual——**接口契约你定，wiring GLM 接**。
- 唯一共享面 = shared XCFramework API；**不碰** `iosApp/Features/Camera/`、`*.metal`、GLM 的基建-iOS 产物。

## 红线
- **[PRIVACY]** 媒体 100% 端侧（相册元数据/缩略图本地处理；文本/聚合摘要可远程，媒体文件不可）
- **[PERF]** 1000+ 缩略图滚动 55–60fps、内存峰值 <500MB（Task 11）
- **[I18N]** 三语从第一天起；键与 Android `strings.xml` 对齐（S5）
- **S5 双端一致**：网格排序 / 分组边界 / 数量与 Android 完全一致（领域逻辑同源的自然验证）

## 验证门
同一照片集双端对照（排序/分组/数量一致）；权限四态单测 + Preview 全覆盖；Task 11 Instruments 达标。

## worktree
从 **main** 开 `refactor/ios-gallery`（Phase 4 落 main 后）。
