# KMP 最佳实践调研 × polang 架构评估（2026-08-10）

> **日期**：2026-08-10
> **状态**：已固化（评估结论生效，行动项待排期）
> **方法**：两路并行网络调研（官方最佳实践 / 生产级项目经验）+ 本地 shared 模块与 iOS 桥接现状核查
> **定位**：回答「polang 的 KMP 路线是否需要修正」——结论：**方向无需修正，有两处红利值得收取（SKIE / AndroidX KMP 存储收编）**

---

## 0. 证据分级说明

| 分级 | 含义 | 本文涉及来源 |
|------|------|-------------|
| 官方文档/公告 | JetBrains / Google 官方发布 | JetBrains Blog（新默认结构 2026-05、CMP 1.8.0、KMP Roadmap 2025-08）、Kotlin Docs、Kotlin Case Studies |
| 实证（生产案例） | 生产环境实际使用并公开数据/教训 | Netflix、McDonald's、Cash App/Block、STRV、Booking.com、Duolingo、Stone、Markaz、Google Workspace、Touchlab |
| 观点性文章 | 咨询/开发者博客分析（无一手生产数据） | Aetherius Solutions、NineTwoThree、kmpship.app、carrion.dev、ProAndroidDev |

---

## 1. 命中项：已符合最佳实践、无需改动的决策

| polang 决策 | 最佳实践对照 | 来源 |
|------------|-------------|------|
| `shared` + `androidApp` + `iosApp` 三分结构 | JetBrains 2026-05 新官方默认结构即此形态（AGP 9 强制 Android 应用入口与 shared 分离） | [JetBrains Blog](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/) |
| 手动组合根（D7）+ 接口注入；expect/actual 仅 6 处 | 官方原话「优先使用接口/工厂等标准语言构造，而非 expect/actual」；DI 场景下 expect/actual 仅用于配置 | [Kotlin Docs: expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html) |
| 相机/美颜/ML 留双端原生，编排下沉 shared | 业界零反例：Netflix（~50% 可共享，UI 原生）、McDonald's（支付/订单共享，UI 原生）、Markaz（100+ 屏全 CMP，相机/QR/支付仍显式原生）。**不存在共享渲染管线/推理引擎的生产案例** | [Kotlin Case Studies](https://kotlinlang.org/case-studies/)、[kmpship.app](https://www.kmpship.app/blog/big-companies-kotlin-multiplatform-2025) |
| XCFramework embed 本地集成 | Touchlab 实证：本地开发最佳路径；CocoaPods 仅在全依赖统一时有价值；SPM 导出仍不完善 | [Touchlab](https://touchlab.co/ios-framework-local-or-remote) |
| Monorepo | STRV 实证：双 repo 拆 shared 是反模式（release-and-consume 阻塞 iOS），monorepo 为默认起点 | [STRV](https://www.strv.com/blog/kotlin-multiplatform-in-production-what-worked-what-didn-t) |
| 双端原生 UI（不切 CMP） | CMP iOS 2025-05 已 Stable（1.8.0，+9MB），但相机/媒体管线是 CMP 最弱场景；官方 `sharedLogic/sharedUI` 拆分只服务 CMP 用户 | [JetBrains Blog: CMP 1.8.0](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/) |
| 测试门槛含 `:shared:assemble` | 与「CI 双端跑 + metadata 编译暴露 iOS 侧问题」的共识一致（本地实证坑位④同款教训） | [KMPShip: KMP Testing](https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025) |

**结构性风险的天然免疫**：生产案例中最大的 KMP 风险是组织层面「iOS 团队跟不上、shared 成黑箱」（Aetherius，观点性但有多个 CTO 匿名佐证）。polang 单人双端 + AI 子代理扮演 iOS 开发者读 shared Kotlin 源码无障碍，该风险不成立。

## 2. 差距与机会（按 ROI 排序）

### P0：引入 SKIE，替代手写桥接层（最高 ROI）

现状：`shared/src/iosMain/.../FlowWatchers.kt`（手写 Flow→回调）+ `ChatAgentBridge`（非 suspend + DTO + try/catch 人工铁律）是无 SKIE 时代的防御性代码。

[SKIE](https://skie.touchlab.co/)（Touchlab，生产级 Stable，KaMPKit 2023 起采用）恰好解决这三件事：

- **Flow → Swift `AsyncSequence`**：`for await` 直接消费，`FlowWatcher` 可退役
- **suspend → `async throws`**：异常经 Swift 类型系统传导，signal 6 人工兜底从纪律变成工具保证
- **sealed → 真 Swift enum**：`ChatStreamEvent` / 相机状态机等 sealed 在 Swift 侧获得 exhaustive `switch`——STRV 生产教训「sealed 直接暴露 Swift 是反模式」，polang 靠 DTO 规避，SKIE 让规避不再必要

- 零侵入（Gradle 插件 `co.touchlab.skie`），与 XCFramework embed + XcodeGen 兼容
- 代价：framework 体积略增；与官方 Swift Export 互斥（Swift Export 仍 Alpha / Kotlin 2.4，2026 年内不考虑切换）
- **落地路径**：spike（chat 链路试点）→ 验证后退役 FlowWatcher、更新 `kmp-ios-interop` skill 铁律表述

### P1：CrashKiOS —— iOS 侧 Kotlin 崩溃符号化

K/N 崩溃栈在 Firebase/系统层面不可读是公认痛点（[firebase-ios-sdk#15512](https://github.com/firebase/firebase-ios-sdk/issues/15512) 等实证）。[CrashKiOS](https://crashkios.touchlab.co/) 成本低，与 polang 结构化日志体系互补。单人项目无第二双眼睛，崩溃可诊断性 = 命。

### P2：存储双轨收编评估（先盘点再决策）

2025 年 AndroidX KMP 化是生态最大变化（Google I/O 2025 公告）：Room 2.8.3 / DataStore 1.1.7 / Lifecycle-ViewModel 2.9.4 / Paging 3.3.6 全部 KMP Stable。polang 两处双轨：

| 双轨点 | 现状 | 收编路径 |
|--------|------|---------|
| chat 记忆 | Android DataStore vs iOS `IosKoogMessageMemoryStore`（NSUserDefaults） | DataStore KMP 统一实现，删 iOS actual |
| TAG 数据库 | iOS Swift 自写 `TagDatabase.swift`（SQLite），完全游离 shared 之外 | 若 Android 侧 TAG 存储为 Room → Room KMP 收编 shared，TAG 领域逻辑（聚类/查询）双端同源 |

⚠️ 存量打标数据昂贵，迁移必须走既有备份恢复机制（`android-tag-data-backup-restore` skill / `docs/05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md`）。**先盘点 Android 侧 TAG 存储实现，再排期，不急。**

### P3：测试加固

- Flow 测试引入 [Turbine](https://github.com/cashapp/turbine)（Cash App 出品；手动 collect 易 flaky）
- 保持「默认写 commonTest、平台行为才写 iosTest/jvmTest」原则（当前 25/5/7 分布已合理）

### 明确不做（调研证伪或不适配）

| 不做 | 理由 |
|------|------|
| ❌ Koin / kotlin-inject | 手动组合根契合 Agent-First「构造函数即文档」；规模未到；Service Locator 运行时解析与显式原则相悖 |
| ❌ Compose Multiplatform 渗透 | 核心场景（相机/相册/美颜）是 CMP 最弱处；NineTwoThree 实测 Skia 渲染与 UIKit 行为差异 |
| ❌ ViewModel 下沉 shared | Lifecycle-ViewModel KMP 虽 Stable（D-KMP/FeedFlow 实证可行），但会冲撞 [PARITY] spec 翻译流程与 iOS `ObservableObject` MV 模式（S10 决策）；spec 状态机已是「逻辑共享」的等价物 |
| ❌ 等 Swift Export | Alpha（Kotlin 2.4）；与 SKIE 互斥；仅支持直接 Xcode 集成 |

## 3. 对现有工作流的反哺

- **`/ios-follow` 设计**（`docs/superpowers/specs/2026-08-10-ios-follow-command-design.md`，已入 main）：SKIE 落地后 Stage 3 平台 actual 的桥接纪律由工具保证，skill 中 signal 6 铁律降级为「SKIE 未覆盖边角」——**SKIE spike 应列为 /ios-follow 实现的前置任务**
- **文档同步点**：SKIE 落地时改 `shared/AGENTS.md`（互操作方案段）+ `kmp-ios-interop` skill；P2 收编启动时走独立 spec → plan 流程

## 4. 行动项

| # | 行动 | 优先级 | 性质 |
|---|------|--------|------|
| K1 | SKIE spike（chat 链路试点：Flow→AsyncSequence、sealed→enum、signal 6 纪律验证） | 🔴 P0 | spike → spec |
| K2 | 引入 CrashKiOS | 🟡 P1 | 小任务 |
| K3 | 盘点 Android 侧 TAG 存储实现，评估 Room KMP 收编 | 🟡 P1 | 只读盘点 → 决策 |
| K4 | DataStore KMP 统一 chat 记忆存储（删 IosKoogMessageMemoryStore） | 🟢 P2 | 随 K3 决策合并排期 |
| K5 | commonTest 引入 Turbine | 🟢 P2 | 小任务 |

## 5. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-10 | 初版：两路调研（官方最佳实践 / 生产级项目经验）+ 本地核查合成；结论「方向不修正，收 SKIE 与 AndroidX KMP 两处红利」 |
| 2026-08-10 | K1 闭环：SKIE 0.10.14 spike GO 合入 main——三件套（suspend→async throws / sealed→enum onEnum / Flow→AsyncSequence）真机实证，GalleryViewModel 首条 FlowWatcher 链路退役；体积 +3.35MB、clean 构建 +4.7%。**追齐期策略**：新链路一律 SKIE 形态、不再新增 FlowWatcher 式桥；存量桥迁移冻结至 iOS 1.0 功能冻结后 |
