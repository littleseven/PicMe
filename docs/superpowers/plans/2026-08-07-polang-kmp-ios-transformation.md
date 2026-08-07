# PoLang KMP 跨端改造与项目重塑 总体计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **文档性质**：本文件是**总体路线图（roadmap）**。按 writing-plans 技能的 Scope Check 规则，本改造横跨多个独立子系统（Koog 迁移 / 仓库重组 / KMP 抽取 / C++ 引擎移植 / iOS 应用），**每个 Phase 开工前必须单独产出该 Phase 的细粒度执行计划**（存 `docs/superpowers/plans/YYYY-MM-DD-<phase-slug>.md`），本文件不承载逐行代码级任务。

**Goal:** 将 langchain4android 仓库重塑为 `polang` Monorepo：agent-core 迁移至 Koog，业务逻辑抽取为 KMP shared 模块，新增 iOS 原生应用（SwiftUI），Android 端零回归。

**Architecture:** KMP 逻辑共享 + UI 双端纯原生（Android Compose / iOS SwiftUI）。C++ 引擎层（MNN/sentencepiece/QuickJS/美颜）跨端复用。相机与美颜渲染管线各端原生（Android CameraX/EGL，iOS AVFoundation/Metal）。CMP 不作为起点，保留逐屏演进通道。

**Tech Stack:** Kotlin Multiplatform · Koog 1.1.1（Agent 框架，实际接入版本；iOS Tier 1）· Jetpack Compose · SwiftUI · MNN（Metal 后端）· AVFoundation/Metal · Ktor（server）

---

## 1. 决策锁定（选型讨论结论）

| 决策点 | 结论 | 关键依据 |
|--------|------|----------|
| 跨端方案 | **方案 2：KMP 逻辑共享 + UI 双端原生** | 五维收敛：Koog 战略自洽 / 体验天花板 / 学习目标 / 可调试性 / AI 生成成熟度 |
| Agent 框架 | **agent-core（Java, LangChain4j 合并版）→ Koog**（实际接入 1.1.1） | Koog 1.x 稳定、iOS Tier 1、DeepSeek/OpenAI provider 内置、Ktor 集成 |
| iOS Agent 框架 | 不另选型 | Swift 生态无成熟框架（langchain-swift 已归档）；Agent 层走 KMP 共享 |
| iOS UI | **SwiftUI** | 学习价值、体验天花板、相册/相机体验敏感、可调试性 |
| CMP | 不采用为起点 | AI 语料最稀薄、渲染层调试工具缺失、核心屏（相册/相机）恰是 CMP 风险最大处；保留逐屏迁移通道 |
| 双端相册差异 | UI 与权限策略层各端自治 | iOS Limited Access / Android Photo Picker 范式不同，属正确平台设计；shared 层抽象为能力接口 |
| 项目名 | **polang** | langchain4android 已名不副实 |
| 包名 / applicationId | **不动**（`com.mamba.picme`） | 避免商店身份、签名、数据迁移问题 |

## 2. 目标目录结构

```
polang/                          # 原 langchain4android/（git repo 改名）
├── androidApp/                  # 原 app/（Compose UI，Android 应用）
├── iosApp/                      # 新建：Xcode 工程（SwiftUI），Phase 5 落地
├── shared/                      # 新建：KMP 共享模块（核心资产）
│   └── src/
│       ├── commonMain/          #   Agent 编排、能力注册、领域模型、DTO、网络
│       ├── androidMain/         #   Android actual 实现
│       └── iosMain/             #   iOS actual 实现
├── engines/                     # C++/原生引擎层（跨端复用）
│   ├── beauty-engine/           # 原 beauty-engine/（Android AAR + Phase 5 起增 iOS XCFramework 产物）
│   ├── beauty-api/              # 原 beauty-api/
│   ├── mnn-core/                # 原 mnn-core/（MNN 推理封装）
│   └── sentencepiece/           # 原 sentencepiece/
├── server/                      # 不变（独立 Ktor 工程，不纳入 Android settings.gradle）
├── docs/ docs-site/ scripts/ infra/   # 治理与工具，结构不变，内容随改名批量更新
└── input_images/ skills/ tmp/ .worktrees/           # 维持现状
```

关键说明：

- **`:agent-core` 在 Phase 1 中消亡**（被 Koog 依赖替代）；**`:runtime-core` 在 Phase 4 中消亡**（引擎无关逻辑迁入 `shared/`，Android 特有部分沉入 `shared/androidMain` 或 `androidApp/`）。
- **包名 `com.mamba.picme` 全线保留**（含 Android applicationId）。iOS Bundle ID 新建（建议 `com.mamba.picme` 同名，App Store 独立命名空间不冲突）。
- **repo 改名后须更新**：GitHub repo rename（GitHub 自动保留旧名重定向）；`server/` 内「上报问题」功能的 issue 目标仓库配置（现指向 `littleseven/langchain4android`）；docs-site、README、徽章链接。

## 3. Phase 总览

| Phase | 内容 | 前置 | 出口标准 |
|-------|------|------|----------|
| **1** | agent-core → Koog 迁移（Android 侧）✅ **已完成（2026-08-07，merge `614a4fef` 入 main）** | 无 | agent-core 模块删除，AI 功能全链路回归，平台耦合点清单产出 |
| **2** | 技术排雷 Spikes（可与 Phase 1 并行） | 无 | 三个 spike 全绿，任一失败回到选型重新评估 |
| **3** | 项目改名 + 目录重组（纯机械，无行为变更） | Phase 1 | `./gradlew assembleDebug` 通过，安装回归通过 |
| **4** | shared KMP 模块抽取 | Phase 1、3 | Android 零回归，shared JVM 单测覆盖核心逻辑 |
| **5** | iOS App 骨架（含相机管线） | Phase 2、4 | TestFlight 内测版：相机预览 + 拍照 + 相册浏览 |
| **6** | iOS 功能对齐与发布准备 | Phase 5 | TAG/Chat/设置逐页对齐，双端隐私政策就绪 |
| **7** | 演进（CMP 逐屏评估、度量采集） | 持续 | — |

> **顺序理由（2026-08-07 修订）**：
>
> 实际执行以 Koog 迁移（Phase 1）为起点——它不依赖任何其他 Phase，是纯 Android 侧的框架替换，由其他工具正在执行中。Phase 2（iOS 技术 spikes）与 Phase 1 无依赖，可并行，但单人带宽下也可等 Phase 1 完成后再做。Phase 3（改名重组）**必须在 Phase 1 完成后**进行——迁移期间 `agent-core` 和 `runtime-core` 目录结构频繁变动，此时做目录搬迁会制造冲突。Phase 4（KMP 抽取）以 Phase 1 产出的「平台耦合点清单」为输入，同时需要 Phase 3 完成的干净目录结构。Phase 5（iOS App）依赖 Phase 2（spike 验证通过）和 Phase 4（shared 模块可用）。

---

## Phase 1：agent-core → Koog 迁移（Android 侧）✅ 已完成（2026-08-07）

> 独立细粒度计划：`docs/superpowers/plans/YYYY-MM-DD-koog-migration.md`（开工前编写，需先读 `agent-core/AGENTS.md` 与 `agent-core/LANGCHAIN4J_MIGRATION.md` 了解现状）
>
> **执行方**：本会话（kimi）接手 claude 的 worktree 后完成 Phase 4-7
>
> **执行现场（2026-08-07 记录）**：worktree `.worktrees/feat-koog-migration/`，分支 `feat/koog-migration`，已 `--no-ff` 合并回 main（merge commit `614a4fef`）。实际接入 **Koog 1.1.1**（`gradle/libs.versions.toml`：`koog = "1.1.1"`，`ai.koog:koog-agents`）。
>
> **已知坑（执行实测）**：Koog 1.1.1 在 Android 上存在 ServiceLoader 缺陷，需显式构造 `KtorKoogHttpClient.Factory` 绕过（commit `47974b4e`）。DeepSeek `thinking: disabled` 注入配方已经 Phase 0 PoC 验证（commit `d36a00e7`，`DeepSeekThinkingParamsTest`）。迁移期共修复 8 个真机 bug（LLModel capabilities 声明、maxIterations 换算、Koog 1.1.1 丢「文本+tool_calls 同帧」工具调用——自定义 `poLangSingleRunStrategy` 绕过、Activity recreate 后 CapabilityRegistry 死 scope、DataStore 无关写入打断 agent、远程拍照回传漏标/连拍重复）。自定义模型名必须在 `LLModel.capabilities` 显式声明 `Completion, Tools, OpenAIEndpoint.Completions`（🔴 不可加 Responses/Thinking）。

**核心原则**：Koog 依赖先接入 `runtime-core`（替换 `:agent-core` 的 api 依赖），在单一 Android 模块内完成 API 迁移和调试。Phase 4 再将 runtime-core 代码连同 Koog 依赖迁入 `shared/commonMain`——届时 Koog 已是 KMP 兼容库，迁入 commonMain 是干净的机械移动。**不在本 Phase 提前创建 KMP shared 模块**，避免引入 KMP 模块复杂度分散注意力。

- [x] **1.1 映射表与依赖接入**：✅ Koog 1.1.1 接入 `runtime-core`（api 依赖），agent-core API → Koog 逐项映射完成（Phase 1-2，commit `21d3db96`）
- [x] **1.2 运行时迁移**：✅ Chat（`KoogChatAgent`，Phase 4 `ee6065b2`）+ 相机/飞书（`KoogReActAgent` + 自定义 `poLangSingleRunStrategy`，Phase 5 `5a0fa092`）切 Koog；编排层保留自研 ReAct 循环语义，未迁 graph workflow（Koog 内建策略有丢工具调用缺陷，自定义策略更可控）
- [x] **1.3 删除自研兼容层**：✅ DeepSeek 适配收敛为 Koog `additionalProperties` 注入 `thinking.type=disabled` + LLModel capabilities 显式声明；旧 `StreamingSyncChatModel`/`ToolCallCommandParser`/`CapturingChatModelListener` 已删
- [x] **1.4 回归与删除**：✅ 相机 AI 指令、Chat、tool_calls、飞书远程控制（拍照/比例/回传）全链路真机回归；`llm_call_log`/`tool_call_log` 日志链路保持（`TraceIdHolder` + `LlmCallRecord` 迁 Koog listener）；`:agent-core` 模块已删除（Phase 6，`1cbe9353` + `d09fbb77`，310 文件 ~3.4 万行）
- [x] **1.5 平台耦合点审计**：✅ 清单已产出（`docs/superpowers/specs/2026-08-07-runtime-core-platform-coupling-inventory.md`，基于 worktree HEAD `1cbe9353`（Phase 6 删除后状态）逐文件审计：runtime-core 79 文件 + app 热点 56 文件，含 PURE/SEAM/ANDROID_ONLY 判定与 expect 设计）；Phase 4 开工时按 main 现状复核一次防漂移
- [x] **1.6 文档**：✅ 根 AGENTS.md「架构说明」段已更新（Koog 编排、JS Engine、AI 工程师模式等）；`agent-core/LANGCHAIN4J_MIGRATION.md` 随模块删除（自然 superseded）；`docs/02-ARCHITECTURE/` 更新并入 Phase 3 文档批量更新

## Phase 2：技术排雷 Spikes（风险前置，约 2–3 周，可与 Phase 1 并行）

目的：验证 iOS 端技术地基。各 spike 相互独立，可并行。与 Phase 1（Koog 迁移）无依赖关系，带宽允许时可同时进行。

> **2026-08-07 review 修订**：2.1/2.2 spike 报告已产出（`docs/superpowers/specs/2026-08-07-ios-mnn-spike-design.md`、`2026-08-07-ios-spm-quickjs-spike-design.md`），但结论均降级为「**有条件 GO**」——下列补验项通过前 Phase 2 不算全绿，不启动 Phase 5。~~2.3 无细化文档，为阻塞前置。~~
>
> **2026-08-07 晚更新**：2.2 运行时补验 ✅、2.3 已执行且结论 GO（详见各自报告）；2.1 补验 A/C ✅ 完成、补验 B（Qwen3-VL-2B 真机）按用户决策暂缓（恢复触发点：Phase 5 启动前或 Phase 6.1 TAG 接入前）。Phase 2 排雷实质收口，仅余补验 B 一项在案。

- [ ] **2.1 MNN iOS 编译与推理验证** ⚠️ 有条件 GO（报告已产出，🔴 补验项未完成）
  - 内容：MNN 源码编译 iOS arm64 + Metal 后端 → XCFramework；加载最小模型（先用小模型，再验证 Qwen3-VL-2B）跑通一次端到端推理；记录耗时与内存
  - 🔴 **补验 A（Metal 正确性）**：✅ **已完成（2026-08-07 晚）**——走 `ImageProcess::convert` 生产路径后 Metal 输出与 CPU 对比：`Precision_High` cos=1.000000、`Precision_Low` cos≥0.99996 全 9 输出 PASS；**但默认 `Precision_Normal`（fp16）数值完全错误（cos≈-0.5），Metal 使用必须显式锁定 precision 档位**（详见 spike 报告 §4，此坑纳入 Phase 6.1 MetalGuardian 设计）
  - 🔴 **补验 B（Qwen3-VL-2B 真机）**：⏸️ **暂缓（用户决策 2026-08-07）**，恢复触发点：Phase 5 启动前或 Phase 6.1 TAG 接入前。风险仍在案：TAG 核心 VLM 未验证（内存峰值/Metal 算子/首 token），失败预案实际替代路径极少（MLX 不支持 iOS、CoreML LLM 支持有限）；恢复时须同步验证 precision 档位
  - **补验 C（版本核实）**：✅ **已核实（2026-08-07）**——本地 fork `3.5.0-64-g9ad00c85` 已包含 Qwen3-VL 支持（2025/10 合入 + 2026/05 bugfix 均在 HEAD），补验 B 可直接用现有版本；升级 3.6.1 降级为条件触发项（补验 B 失败且指向上游已修 bug 时；升级需双端同升 + Android 回归）
  - 出口：补验 A 通过（✅）、补验 B 暂缓不阻塞（见上）、补验 C 已核实；spike 报告已同步
  - 失败预案：评估 MNN iOS 替代——注意 **MLX 不支持 iOS（Mac-only）、CoreML 对 LLM 支持有限，实际替代路径极少**；若 Qwen3-VL 无法在 iOS 运行，TAG 功能 iOS 端降级方案需重开选型讨论
- [x] **2.2 sentencepiece + QuickJS iOS 编译验证** ✅ GO（编译/符号 + 运行时均已验证，运行时补验 2026-08-07 晚在 Phase 2.3 spike 中完成）
  - 内容：两个 C/C++ 库编译为 XCFramework；Swift 侧最小调用验证（tokenizer 编解码各一次、QuickJS 执行一段脚本）
  - **补验（运行时）**：✅ 已完成——sentencepiece `Encode`→`Decode` 往返一致（"hello world" → 4 token → 完全一致）；QuickJS `evaluate("1+2")` 真机返回 3；**桥接路径结论：ObjC++ 直接链接合并静态库（libtool 合并 sentencepiece + absl → `libspm_ios.a`），无需 cinterop def**（详见 2.3 spike 报告 §7.4）
  - 出口：真机调用成功，API 桥接方式（直接 C interop 还是 Objective-C++ 封装）有结论
- [x] **2.3 KMP + Koog 端到端连通验证** ✅ **已执行（2026-08-07 晚），结论 GO**（报告：`docs/superpowers/specs/2026-08-07-kmp-koog-spike-design.md` §7/§8）
  - 内容：新建最小 KMP 模块（commonMain 一个函数 + 一个 Koog Agent 调用 DeepSeek 的 demo）→ Android app 消费 + 最小 SwiftUI app 消费（XCFramework 集成、xcode-kotlin 调试插件验证断点）
  - **必验项结果**：① **Koog 1.1.1 iOS 真机初始化并调用 DeepSeek 成功**——显式构造 `KtorKoogHttpClient.Factory()` 直通，无 ServiceLoader 坑，实回 "pong"；② **构建耗时**：debug 增量 ~5-6s（AI 迭代循环可行）、Release framework 全量 3m54s（一次性成本）；③ xcode-kotlin 插件已装并就位，断点命中待 Xcode GUI 手动确认（非阻塞）
  - 出口：双端调用成功 ✅；构建耗时记录 ✅；spike 报告已产出 ✅（断点为体验项不阻塞）
  - **注意**：spike 代码为一次性验证产物，不进入主分支；Phase 4 从零建立正式 shared 模块
- [ ] **2.4 美颜引擎 Metal 渲染验证（review 增补）**
  - 内容：美颜引擎（`beauty-engine/src/main/cpp/`）是独立 C++/OpenGLES 渲染管线，Phase 2.1 不覆盖。最小验证：选一个最简单滤镜（如美白）用 Metal shader 在 AVCaptureSession 实时预览渲染、测帧率；评估 GLSL→MSL 逐滤镜迁移工作量（坐标系原点/Y 翻转/色彩空间差异）
  - 出口：单滤镜实时渲染达标；全滤镜迁移工作量评估（预估 1–2 周）写入 Phase 5.4 细化计划
  - 说明：也可降级为 Phase 5.4 内独立工项，但届时才发现 shader 迁移量会冲击 Phase 5 工期
- [ ] **2.5 Spike 总结与 Go/No-Go**
  - 各报告汇总（含补验项结果），确认进入 Phase 5（iOS App）；任一红线失败则回到方案讨论（本文件第 1 节决策需重审）

## Phase 3：项目改名与目录重组（纯机械，约 1 周）

原则：**git mv 保历史，零逻辑变更，构建常绿**。开工前按 `using-git-worktrees` 建隔离工作区与专用分支（如 `refactor/repo-restructure`）。

- [ ] **3.1 目录迁移**
  - `app/` → `androidApp/`；`beauty-engine/`、`beauty-api/`、`mnn-core/`、`sentencepiece/` → `engines/` 下；`runtime-core/` 暂留根级（Phase 4 消亡，现在搬是浪费）
  - `agent-core/` 此时已不存在（Phase 1 已删除）
  - 逐目录 `git mv`，每移一个目录跑一次增量构建
- [ ] **3.2 构建配置更新**
  - `settings.gradle`：`rootProject.name = "polang"`；include 路径更新（`:androidApp`、`:engines:beauty-engine` 等）
  - 各 `build.gradle(.kts)` 内 `project(":app")` 等依赖路径、源码/资源路径引用修正
- [ ] **3.3 工具链与脚本路径批量更新**
  - `scripts/`（ai-gate.sh、auto-dev-loop.sh、impact-analyzer.sh、screenshot-diff.py 等）中的模块路径
  - `.github/` CI 配置、detekt baseline 路径
  - 验证闭环：`./scripts/ai-gate.sh` + `auto-dev-loop.sh` 全绿
- [ ] **3.4 文档批量更新**
  - 根 `AGENTS.md`（模块清单、架构说明）、各模块 `AGENTS.md`、`AI_TOOLS.md`、`PRODUCT.md` 中的项目名与路径
  - `docs/` 内交叉引用链接扫描修复（doc-sync-guardian.sh 辅助）
- [ ] **3.5 GitHub repo rename 与对外链接更新**
  - GitHub repo rename → `polang`；本地 remote URL 更新
  - `server/` 上报问题功能的 issue 目标仓库配置更新（旧名有重定向，但配置应显式更新）
  - docs-site、README 徽章、隐私政策页中的仓库链接
  - **时机说明**：GitHub rename 延后至此 Phase（而非更早），避免 Phase 1–2 期间文档/计划中的路径引用因 repo 改名而失效
- [ ] **3.6 出口验证**
  - `./gradlew assembleDebug` 通过；设备安装 + 核心路径冒烟（相机/相册/Chat/TAG）；不合并回主干前由 review 子 agent 审 diff

## Phase 4：shared KMP 模块抽取（约 3–5 周）

> 独立细粒度计划：`docs/superpowers/plans/2026-08-07-shared-kmp-extraction.md` ✅ **已提前产出（2026-08-07）**——15 个 Task（骨架/平台原语/领域层/beauty 类型/编排核心/Koog 层/ToolService suspend 化/存储 seam/facade/JS/语音/VLM 归位/Android 专有归位/runtime-core 消亡/出口验证），含决策锁定 D1–D9 与降级预案。**执行仍需等 Phase 3 完成**（计划内路径按 Phase 3 后结构书写）。
>
> **输入**：Phase 1.5 产出的「runtime-core 平台耦合点清单」
>
> **耦合点类别（2026-08-07 review 增补，Phase 1.5 清单须按类归组）**：① Room/SQLite（TAG 3-Pass 状态机、`face_embeddings`/`persons` 表 → SQLDelight 或 Room KMP（beta），SQL-first 与 ORM 范式差异大，预估 3–5 天）；② DataStore（→ multiplatform-settings 或 DataStore KMP）；③ **Foreground Service**（`TagGenerationService` → iOS BGTaskScheduler 限约 30 秒，无法等价替代——TAG 全量扫描或需改为「充电+锁屏增量扫」或「手动触发」，属双端功能差异须在 Phase 6 显式标注，预估 3–5 天）；④ ContentResolver/MediaStore（4.2 能力接口已覆盖）；⑤ Handler/Looper（→ 协程 Dispatcher，低成本）；⑥ JNI（MNN/sentencepiece/美颜，Phase 2 spike 已验证桥接路径）

抽取顺序（自底向上，每层 Android 先跑通）：

- [ ] **4.1 shared 骨架**：KMP 模块建立（commonMain/androidMain/iosMain），接入 androidApp 构建；CI 增加 shared JVM 单测
- [ ] **4.2 领域与网络层**：DTO、媒体领域模型、与 server 共享的数据契约；相册访问抽象为**能力接口**——`PhotoLibraryProvider` + `AccessState` 密封枚举（`Full / Limited / Denied / AddOnly(iOS)`），权限流程留各端 UI（对应双端隐私范式差异决策）
- [ ] **4.3 Agent 层**：runtime-core 的 Koog 编排、CapabilityRegistry、PrivacyGuard、MemoryManager 迁入 commonMain；平台依赖（存储、日志、文件、时钟——来自 Phase 1.5 清单）收敛为 expect 接口
- [ ] **4.4 JS 引擎抽象**：`agent/core/js/` 引擎无关层迁 commonMain；QuickJS 绑定留 androidMain，iOS 侧 actual 用 Phase 2.2 验证的桥接
- [ ] **4.5 语音引擎抽象**：`platform/voice/` 接口层（`AsrEngine`、`KeywordSpotterEngine`、`VadDetector`）→ commonMain expect；Android 实现（`SherpaOnnxAsrEngine` 等）→ androidMain actual；iOS actual 留空或 Phase 6 实现
- [ ] **4.6 Android 专有组件归位**：`tool/accessibility/` + `tool/perception/`（无障碍服务、ViewHierarchyExtractor 等）为纯 Android 架构，iOS 无等价物——沉入 `androidApp/`，不进 shared
- [ ] **4.7 runtime-core 消亡**：确认所有可共享代码已迁出，Android 特有残余沉入 androidMain/androidApp；`:runtime-core` 模块删除
- [ ] **4.8 出口**：Android 全功能零回归；shared commonMain 核心逻辑 JVM 单测覆盖（这是 iOS 侧调试成本的对冲——逻辑 bug 在 JVM/Android 侧可复现可调试）

## Phase 5：iOS App 骨架（约 6–10 周，学习曲线集中段；review 修订：原 4–8 周对单人首次 SwiftUI+AVFoundation+Metal 偏紧）

> 独立细粒度计划：`docs/superpowers/plans/YYYY-MM-DD-ios-app-skeleton.md`。本 Phase 是你 iOS 学习的主战场：**前几页 UI 自己写、AI 只答疑**；相机管线压轴，AI 多承担（但 Metal shader 调试需预留弹性）。

- [ ] **5.1 工程与基建**：Xcode 工程、Bundle ID、签名、SPM/依赖、shared XCFramework 集成、xcode-kotlin 调试、基础 CI（xcodebuild）；**初始化 Privacy Manifest（`PrivacyInfo.xcprivacy`）**（review 增补：2024/05 起强制，声明 FileTimestamp/SystemBootTime/DiskSpace 等 API 使用原因，勿等 Phase 6.3）；统一 MNN/sentencepiece/美颜三组件的 XCFramework 构建与 SPM binary target 分发策略
- [ ] **5.2 首批页面（学习区）**：相册网格 + 相簿列表（SwiftUI + Photos framework + shared 领域层）；权限流按 iOS 范式实现（Limited Access 一等公民）
- [ ] **5.3 相册网格性能实测**：1000+ 缩略图滚动帧率/内存达标验证（此前评估为 iOS 端最重 UI 场景）
- [ ] **5.4 相机管线（压轴）**：AVFoundation 采集 → 美颜引擎（C++ 直桥，走 Phase 2.1 产物）→ MTKView 渲染；对焦/变焦/曝光手势；对标 Android [PERF] 红线（交互 <100ms、快门 <50ms）
- [ ] **5.5 TestFlight 内测包**：相机预览 + 拍照 + 相册浏览可用

## Phase 6：iOS 功能对齐与发布准备（持续）

- [ ] **6.1 TAG 流水线**：MNN 推理接入（Phase 2.1 产物），3-Pass 控制页；**设计并实现 iOS MetalGuardian（替代 OpenClGuardian）**（review 修订：iOS 无 OpenCL，非「策略对齐」而是新设计——Metal kernel warmup 超时检测、Metal→CPU 降级含模型卸载重载、MTLDevice 丢失处理、黑名单持久化）；4 模型（RetinaFace/R100/MobileCLIP-S2/Qwen3-VL-2B）顺序加载内存峰值用 Instruments 验证
- [ ] **6.2 Chat 与 AI 指令**：shared Agent 层直接消费；JS 沙盒 handler 对齐
- [ ] **6.3 设置与账号**：server 账号体系接入；隐私政策页（双端权限声明差异：iOS Privacy Manifest、相册用途描述）；🔴 **App Store Guideline 2.5.2 合规分析（review 增补，需在 Chat code-interpreter 上线前完成）**：`JS_ENGINE_TECH_SPEC.md` §2.2 合规矩阵目前仅覆盖 Google Play，需补 App Store 列并出三级结论——① bundled JS（`assets/js/` 内脚本）基本合规；② LLM 会话下发 JS（不持久化、白名单 handler、QuickJS 无 JIT 纯解释）需明确合规分析，2026 年 Apple 正以此拒绝「LLM 生成并执行代码」类 app，iOS 端或需限制为白名单 handler 调用；③ 下载执行远程 JS bundle 为红线不做。如需功能降级，写入双端功能差异清单
- [ ] **6.4 server 端 iOS 适配**：iOS 设备注册、推送证书（APNs）、Apple Sign In 等 server 端配合项；若改动量大则由独立计划承载，本项至少完成适配点清查
- [ ] **6.5 验收对齐**：核心验收测试 iOS 版；结构化日志（llm/tool/js 三层）iOS 落地

## Phase 7：演进（持续）

- [ ] CMP 逐屏评估通道：仅当某低频页面（设置、TAG 控制）双端同步维护成本真实显现时，单屏迁 CMP 验证
- [ ] 度量采集（AGENTS.md §6.2）：AI 生成代码占比、自动修复成功率——iOS 端纳入统计，验证"方案 2 让 ~90% 代码落在 AI 高可靠区"的假设
- [ ] Apple Foundation Models framework 开源后评估：端侧能力补充（与 Koog 编排层不冲突）

---

## 4. 风险登记册

| 风险 | 等级 | 缓解 |
|------|------|------|
| MNN Metal 后端正确性未验证（spike 输出全 0） | 🔴 方案级（2026-08-07 review 新增） | Phase 2.1 补验 A：真实图像 Metal vs CPU 输出一致性对比，通过前不启动 Phase 5 |
| MNN/Qwen3-VL 在 iOS 不可用或性能不达标 | 🔴 方案级 | Phase 2.1 补验 B 真机前置；失败预案注意 MLX 不支持 iOS、CoreML LLM 支持有限，实际替代路径极少 |
| App Store 2.5.2（LLM 生成代码端侧执行）拒审 | 🔴（2026-08-07 review 新增） | Phase 6.3 前完成合规三级分析；iOS 端 code-interpreter 或需限为白名单 handler 调用 |
| Kotlin/Native 构建慢拖垮 AI 迭代循环 | 🟡 偏高（2026-08-07 review 上调） | Phase 2.3 强制实测增量/全量构建耗时（社区有 30+ 分钟报告、KT-78518）；shared 逻辑以 JVM 单测为主验证路径；评估 SPM binary target 预编译加速 |
| 单人带宽：Android 维护与 iOS 开发并行 | 🟡 | Phase 严格串行（Phase 1/2 例外，可并行）；Android 端 Phase 3–4 期间冻结新特性 |
| Koog 1.x API 与实际需求不匹配（如相机 tool_calls 场域、iOS 无 ServiceLoader 的初始化差异） | 🟡 | Phase 1.1 映射表先行，差距早发现；Phase 2.3 真机验证 Koog 1.1.1 iOS 初始化；Koog 是 JetBrains 官方项目可提 issue/贡献 |
| Kotlin/Native ↔ Swift 互操作坑（retain cycle、类型映射语义损失、framework 体积） | 🟡（2026-08-07 review 新增） | Phase 2.3/4 实测互操作边界；framework 体积监控 |
| 美颜引擎 GLSL→MSL shader 迁移量未知 | 🟡（2026-08-07 review 新增） | Phase 2.4 spike 前置评估；或 Phase 5.4 内独立工项 1–2 周 |
| App Store 审核（相册权限用途、AI 功能声明） | 🟡 | Phase 6.3 隐私清单提前准备；Phase 5.1 初始化 Privacy Manifest；Limited Access 友好设计是加分项 |
| server 端 iOS 适配项被延迟发现 | 🟡 | Phase 6.4 显式清查；若改动量大提前拆独立计划 |
| repo 改名打断协作者/外部链接 | 🔵 | GitHub 自动重定向；延后至 Phase 3 执行（3.5） |

## 5. 全局纪律（贯穿所有 Phase）

- 每 Phase 开工前：隔离 worktree + 专用分支（`using-git-worktrees`），writing-plans 产出该 Phase 细粒度计划
- 每 Phase 收尾：review 子 agent（GLM）审 diff；闭环验证（编译→安装→测试→日志）；文档同步（[DOC-SYNC] 红线）
- 红线持续生效：[PRIVACY] 媒体 100% 端侧（iOS 端同样受约束）、[PERF]、[I18N]（iOS 端三语同步从 Phase 5 第一天起算）
- 提交纪律：fix/feat 只落本 Phase 专用分支

## 6. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-07 | 初版：Phase 0→1→2→3→4→5→6 顺序（Spikes 最前） |
| 2026-08-07 | 修订一：按 review 审查意见修正 6 处（Koog 落地位置、voice/accessibility 遗漏、平台耦合点审计、GitHub rename 延后、spike 丢弃声明、server 适配） |
| 2026-08-07 | 修订二：按实际执行现状重排 Phase——Koog 迁移（Phase 1）先行（由其他工具执行中），Spikes 降为 Phase 2（可与 Phase 1 并行），改名重组后移至 Phase 3（等 Koog 迁移稳定后再动目录） |
| 2026-08-07 | 修订三：记录 Koog 实际接入版本 1.1.1（非 1.0）、执行现场（`.worktrees/feat-koog-migration/` + 分支 `feat/koog-migration`）及实测坑（1.1.1 Android ServiceLoader 缺陷需显式构造 `KtorKoogHttpClient.Factory`） |
| 2026-08-07 | 修订四：按 KMP/iOS 细化方案 review 回写——2.1/2.2 spike 结论降级为有条件 GO（Metal 输出全 0 补验、Qwen3-VL-2B 真机补验、运行时调用补验、MNN 版本核实）；2.3 标为阻塞前置并补必验项（Koog 1.1.1 iOS 初始化、构建耗时实测）；新增 Phase 2.4 美颜 Metal 渲染 spike；Phase 4 补耦合点六类清单（Room→SQLDelight、Foreground Service→BGTaskScheduler 为大头）；Phase 5 工期调 6–10 周 + 5.1 增 Privacy Manifest；6.1 改为新设计 MetalGuardian；6.3 增 App Store 2.5.2 合规分析；风险登记册新增 3 项、上调 1 项。同步回写两份 spike 报告（specs/2026-08-07-ios-mnn-spike-design.md、2026-08-07-ios-spm-quickjs-spike-design.md） |
| 2026-08-07 | 修订五：Phase 1 完成合并（merge `614a4fef`，1.1–1.6 全勾）；Phase 1.5 耦合点清单提前产出（specs/2026-08-07-runtime-core-platform-coupling-inventory.md，基于 `1cbe9353` 删除后状态审计）；Phase 4 细粒度计划提前产出（plans/2026-08-07-shared-kmp-extraction.md，15 Task + 决策锁定 D1–D9，执行待 Phase 3） |
