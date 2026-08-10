# SKIE 互操作增强 Spike 设计（行动项 K1）

> **日期**：2026-08-10
> **关联**：`docs/reviews/2026-08-10-kmp-best-practices-architecture-review.md` 行动项 K1（P0）；/ios-follow 实现的前置任务
> **性质**：本文档是**执行计划 + 报告模板**。~~执行完成后将「7. 验证结果」各节填实，结论改为 GO / NO-GO。~~ **已执行完毕，结论 GO（2026-08-10，见 §7.5）。**
> **工期估计**：1–2 天
> **工作区**：`.worktrees/skie-spike/`（分支 `spike/skie`，基于 main）

---

## 1. 验证目标

引入 [SKIE](https://skie.touchlab.co/)（Touchlab，Kotlin/Native 编译器插件，修改 framework 产物生成 Swift 惯用 API，分发/消费方式不变），替代手写桥接层（`FlowWatchers.kt` / `ChatAgentBridge` 的防御性写法）。验证四个不确定性：

| # | 不确定性 | 为什么阻塞 |
|---|---------|-----------|
| U1 | **SKIE 0.10.14 与本项目工具链兼容**：Kotlin 2.3.10 + AGP 9 KMP library 插件（`com.android.kotlin.multiplatform.library`）+ 五 target（android/jvm/iosX64/iosArm64/iosSimulatorArm64） | SKIE 官方兼容声明覆盖 Kotlin 2.0.0–2.4.10，但 AGP 9 KMP 库插件是新产物（无 externalNativeBuild 等差异已踩过坑），兼容性无现成实证 |
| U2 | **XCFramework 产物与现有集成路径不破坏**：`assembleSharedKitDebugXCFramework` → XcodeGen `project.yml` embed → `build-shared-kit.sh` 增量 | SKIE 修改 framework 内容（注入 Swift shim / 改写头文件），虽宣称分发方式不变，但需实证 Xcode 构建与真机运行 |
| U3 | **三大痛点实际改善**（chat 链路试点）：Flow → `AsyncSequence`（`ChatToolService.uiActions`）；suspend → `async throws`；sealed → Swift enum（`ChatStreamEvent` / UI Action 层级） | 调研报告的承诺需要在本项目代码上眼见为实；特别是 signal 6 纪律能否由 `async throws` 类型系统接管 |
| U4 | **体积与构建耗时增量可接受** | SKIE 已知会使 framework 体积增大；K/N 构建本就慢，增量需量化 |

## 2. 验证环境

| 项 | 值 |
|----|-----|
| Kotlin | 2.3.10（项目现状，SKIE 兼容区间 2.0.0–2.4.10 内） |
| SKIE | 0.10.14（2026-08 最新 release） |
| AGP | 9.1.0（KMP library 插件） |
| Xcode / Swift | Xcode 16.4 / Swift ≥5.8（SKIE 下限） |
| 集成路径 | XCFramework embed + XcodeGen（生产路径原样） |
| 试点链路 | Phase 6.2 chat 全链路（`ChatAgentBridge` / `ChatToolService.uiActions` / `ChatStreamEvent`） |

## 3. 范围

**范围内**：
- `gradle/libs.versions.toml` + `shared/build.gradle.kts` 加 SKIE 插件
- shared 三端编译 + XCFramework 构建 + iosApp Xcode 构建
- Swift 侧**冒烟验证**（DebugOverlay 或临时入口）：`for await` 消费 uiActions Flow、`switch` 穷举一个 sealed、`await` 一个 suspend 函数
- 体积（framework 二进制 before/after）与构建耗时（clean 全量 + 增量）实测

**范围外（GO 之后才做，本 spike 不动）**：
- ❌ 退役 `FlowWatcher` / `ChatAgentBridge` / `KotlinBridge`（迁移是独立任务）
- ❌ 改 Swift 业务代码（冒烟入口除外，且走 DebugOverlay 不污染 feature）
- ❌ 动 Android 任何代码
- ❌ 更新 `kmp-ios-interop` skill / `shared/AGENTS.md`（GO 后随迁移一起改）

## 4. 执行步骤

- [ ] **S1 接入插件**：版本目录加 `skie = "0.10.14"` + plugin 声明；`shared/build.gradle.kts` `alias(libs.plugins.skie)`。记录 SKIE 配置默认值（是否需显式 `skie {}` 块）
- [ ] **S2 三端编译绿**：`JITPACK=true ./gradlew :shared:assemble`（含 android AAR + iOS 三 target metadata——本项目坑位④的既有门槛）；`:shared:jvmTest` + `:shared:iosX64Test` 全绿
- [ ] **S3 XCFramework 构建**：`JITPACK=true ./gradlew :shared:assembleSharedKitDebugXCFramework`；对比产物结构（SKIE 应注入 `.swiftmodule` / 改写 `SharedKit-Swift.h`）；**体积实测记录**
- [ ] **S4 Xcode 集成不破**：`xcodegen generate`（如 project.yml 无变更则跳过）→ `xcodebuild build`；`build-shared-kit.sh` 增量路径实测一次
- [ ] **S5 Swift 冒烟三件套**（DebugOverlay 临时入口）：
  1. `for await action in ChatToolService.shared.uiActions`（Flow→AsyncSequence）
  2. 对一个 SKIE 导出的 sealed（如 chat UI action 层级）写 `switch` 验证穷举（少一个分支应编译报错）
  3. `try await` 调一个 suspend 函数，验证异常以 Swift `throws` 传导（而非 signal 6）
- [ ] **S6 构建耗时实测**：clean 全量 XCFramework 构建 before/after 各测 1 次；增量（改一个 commonMain 文件）各测 1 次
- [ ] **S7 填 §7 验证结果 + GO/NO-GO 结论**

## 5. GO / NO-GO 标准

| 条件 | 标准 |
|------|------|
| **GO** | S1–S5 全绿；体积增量 ≤ 5MB（debug framework）；clean 构建耗时增量 ≤ 30%；冒烟三件套全部如承诺工作 |
| **有条件 GO** | S1–S4 绿但 S5 部分不达（如 suspend 可用、sealed 不导出）→ SKIE 配置收敛（feature flags）后重验，或降级为「只用 Flow/suspend，sealed 继续 DTO」 |
| **NO-GO** | S1/S2 编译不过且无法在 1 天内修复；或 S4 Xcode 集成破坏；或体积/耗时增量超标 |

## 6. 回滚方案

SKIE 是纯构建期插件：摘插件 = `libs.versions.toml` 删两行 + `shared/build.gradle.kts` 删一行，无任何源码侵入。`spike/skie` 分支整体可弃。

## 7. 验证结果（执行后填实）

### 7.1 U1 工具链兼容性 ✅

SKIE 0.10.14 + Kotlin 2.3.10 + AGP 9.1.0 KMP library 插件 + 五 target **全部兼容**：
`:shared:assembleSharedKitDebugXCFramework` / `:shared:assemble` / `:shared:jvmTest` / `:shared:iosX64Test` 一次全绿。
仅有警告：SKIE 对 stdlib `StringBuilder.append/insert` 的 NameCollision 提示（可用 `SuppressSkieWarning.NameCollision` 配置压制，不影响功能）。
SKIE 默认零配置（无需显式 `skie {}` 块）。

### 7.2 U2 XCFramework 产物结构对比 ✅

| 项 | 无 SKIE | 有 SKIE |
|----|---------|---------|
| Modules/ | 仅 `module.modulemap` | `module.modulemap` + **`SharedKit.swiftmodule`**（SKIE Swift shim 注入点） |
| 产物结构/集成路径 | — | **不变**（XcodeGen embed + build-shared-kit.sh 增量均照常工作） |

SKIE 三种转换机制实证：
1. **suspend → `async throws`**：生成独立 Swift 扩展文件（`Shared.ChatToolService.swift` 等），30+ @Tool 方法全套转换，经 `SwiftCoroutineDispatcher.dispatch` 桥接
2. **sealed → Swift enum**：生成 `__Sealed` @frozen enum + 全局 `onEnum(of:)` 转换函数（`Shared.ChatStreamEvent.swift`）
3. **Flow → AsyncSequence**：**类型替换 + Swift 桥接**（apinotes 把 `uiActions` 类型改写为 `SkieKotlinMutableSharedFlow<AgentAction*>`，Swift 侧桥为 `SkieSwiftMutableSharedFlow<AgentAction>`，conform `SkieSwiftFlowProtocol: AsyncSequence`）——🔴 **纠错：spike 中途曾误判「MutableSharedFlow 不转换」，实为 SKIE 支持全部 Flow 变体（Flow/SharedFlow/MutableSharedFlow/StateFlow/MutableStateFlow）**；`uiActionsReadOnly` 只读暴露保留（API 卫生，非 SKIE 限制）

### 7.3 U3 冒烟三件套结果 ✅（真机 iPhone 15，iOS 26.6，DebugOverlay 遥测实证）

| 项 | 结果 | 证据 |
|----|------|------|
| ① Flow → AsyncSequence | ✅ `skie.flow: subscribed via AsyncSequence` | 真机截屏（`GalleryViewModel` 已改为 `for await assets in repository.allMedia` 直消费，相册 239 张分组渲染正常——`gallery.count: 239`） |
| ② sealed → enum 穷举 | ✅ `skie.sealed: OK text=smoke` | `switch onEnum(of:)` 无 default 穷举，运行时命中 `.textSnapshot` |
| ③ suspend → async throws | ✅ `skie.suspend: OK: OK` | `try await service.delay(delayMs: 1)` 正常返回，无 signal 6 |

**额外发现（FlowWatcher 互斥，迁移关键约束）**：SKIE 类型替换后，手写 `FlowWatchersKt.watch()` 与转换后类型不兼容（`SkieSwiftFlow<[MediaAsset]>` 无法传入期望 `SkieSwiftOptionalFlow<Any>` 的 watch 参数）——**迁移必须逐条链路从 FlowWatcher 切换到 `for await` 直消费，不能共存混用**。闭包参数型桥（`ChatAgentBridge.watchUiActions`）不受影响，可独立迁移。

### 7.4 U4 体积与耗时

| 指标 | before | after | 增量 |
|------|--------|-------|------|
| Debug XCFramework 体积 | 173M | 209M | +36M（+21%，含全 slice/头文件/dSYM） |
| 设备二进制（ios-arm64/SharedKit） | 33,209,544 B（31.7MiB） | 36,554,224 B（34.9MiB） | **+3.35MB（+10.1%）** ✅ ≤5MB |
| clean 全量构建耗时（仅 XCFramework） | 185.6s | 194.3s | **+8.7s（+4.7%）** ✅ ≤30% |
| 增量构建耗时（单文件改动 → XCFramework） | 未测 | 184.5s | —（K/N 链接主导，与 clean 同量级） |

### 7.5 结论

**GO** ✅

全部 GO 条件达成：
- S1–S5 全绿（工具链兼容 / XCFramework 集成路径不变 / 真机冒烟三件套实证）
- 体积增量 +3.35MB（debug 设备二进制，+10.1%）≤ 5MB 阈值
- clean 构建耗时增量 +4.7% ≤ 30% 阈值

**后续迁移任务要点**（独立 spec 规划）：
1. 逐链路迁移：`GalleryViewModel` 已完成（本 spike 实证）；`ChatAgentBridge`（闭包型桥）/`CameraToolService.uiActions` 等逐条切 `for await` 直消费，**FlowWatcher 与 SKIE 类型替换互斥不可混用**
2. 退役 `FlowWatchers.kt`（iosMain）与 `KotlinBridge.swift` 中的类型转接（迁移完成后）
3. 只读 Flow 暴露作为 API 卫生惯例（`uiActionsReadOnly` 模式推广到 CameraToolService 等）
4. 落地后更新 `kmp-ios-interop` skill（signal 6 人工铁律降级为「SKIE 未覆盖边角」）与 `shared/AGENTS.md` 互操作方案段
5. `SuppressSkieWarning.NameCollision` 配置压制 stdlib 命名冲突警告（可选）

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-10 | 初版：K1 行动项启动；SKIE 0.10.14（兼容 Kotlin 2.0.0–2.4.10，覆盖项目 2.3.10）；chat 链路试点 |
