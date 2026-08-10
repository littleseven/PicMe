# SKIE 互操作增强 Spike 设计（行动项 K1）

> **日期**：2026-08-10
> **关联**：`docs/reviews/2026-08-10-kmp-best-practices-architecture-review.md` 行动项 K1（P0）；/ios-follow 实现的前置任务
> **性质**：本文档是**执行计划 + 报告模板**。执行完成后将「7. 验证结果」各节填实，结论改为 GO / NO-GO。
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

### 7.1 U1 工具链兼容性

（待填：SKIE 版本确认、AGP 9 KMP 插件兼容情况、遇到的问题与解法）

### 7.2 U2 XCFramework 产物结构对比

（待填：before/after 产物结构 diff、SharedKit-Swift.h 变化摘要）

### 7.3 U3 冒烟三件套结果

（待填：Flow→AsyncSequence / sealed→enum / suspend→async throws 各自截图或代码实证）

### 7.4 U4 体积与耗时

| 指标 | before | after | 增量 |
|------|--------|-------|------|
| Debug XCFramework 体积 | （待填） | （待填） | （待填） |
| clean 全量构建耗时 | （待填） | （待填） | （待填） |
| 增量构建耗时 | （待填） | （待填） | （待填） |

### 7.5 结论

（待填：GO / 有条件 GO / NO-GO + 依据）

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-10 | 初版：K1 行动项启动；SKIE 0.10.14（兼容 Kotlin 2.0.0–2.4.10，覆盖项目 2.3.10）；chat 链路试点 |
