# KMP 架构契约评估与可行方案（review 稿）

- 日期：2026-08-12
- 状态：**Draft — 待 review**（review 通过后核心内容提升为 ADR-013）
- 触发：用户提出《PoLang 技术选型架构契约（2026-08-12）》6 条 + 3 条元原则
- 评估方法：对照实际代码（非仅 CLAUDE.md 声明）逐条核验 `:shared`/`:engines:*`/`iosApp`

---

## 0. 三条元原则（决策驱动顺序，前者优先）

1. **AI 友好优先**：架构首要目标是让大模型能可靠生成成熟、低复杂度代码；必须考虑 LLM 能力限制与生成代码的成熟度。
2. **降低双端维护成本 / 提升迭代效率 / 保障 UI 一致性**。
3. **用户体验一致性**。

> 下文所有结论以这 3 条为裁决依据。当"Kotlin 优雅 / 抽象完备 / SKIE 魔法"与之冲突时，让步。

---

## 1. 评估结论：契约 ~90% 是对现状的描述，不是待改项

逐条核验（证据为实际代码 `file:line`）：

| 契约点 | 主张 | 现状 | 证据 |
|---|---|---|---|
| P1 KMP 只共享业务逻辑 | commonMain 无 `@Composable`、无平台逻辑 | **符合** | `grep @Composable shared/src/commonMain` = 0；`grep "import (android\|java)\."` = 0；16 个 `actual` 命中全在 KDoc 注释，非声明 |
| P1 不做 CMP | Compose + SwiftUI 各自原生 | **符合** | `settings.gradle.kts` 无 CMP 模块；`iosApp/` 为 Xcode 工程 |
| P2 engines 独立编译 | engines 产 `.so` **和** `.framework`/iOS，KMP 薄接口消费 | **⚠️ 措辞不符（见 A）** | `shared/build.gradle.kts:57-61`：engines 是 `com.android.library`，**仅 Android `.so`** |
| P2 commonMain 可定义 expect 接口 | 扁平、无语法糖 | **部分符合（见 B/C）** | 仅 4 个扁平 expect：`Platform`/`DispatcherProvider`/`AgentIdGenerator`/`KoogHttpClientFactoryProvider` |
| P3 合规各端自理 | commonMain 不碰 storage/permission/crypto | **符合** | commonMain 零平台 import；DataStore/sherpa 在 `androidMain`，iOS 走自有 native |
| P5 模块边界 | shared 三端 + engines + 双端 app | **符合** | 7 模块；`shared/src/` = commonMain/androidMain/iosMain/jvmMain |

**关键事实补充**：
- **ADR 已到 ADR-012**（`docs/02-ARCHITECTURE/ADR/` 实查），下一个 = **ADR-013**。ADR-005/008/009/010 已覆盖本地/远程拆分、隐私、本地 LLM、链路隔离——新 ADR 须**引用**不重复。
- **jvmMain 仅 4 文件**，正好是 4 个 expect 的 jvm actual → jvm target 只为跑 commonTest，非 Ktor 后端（契约 P5 模块图漏列，但属测试 source set，无害）。
- **iosMain 已 16 文件**，iOS actual 层有真实厚度：`IosAgentComposition`（组合根）、`FlowWatchers`+`*Bridge`（Swift 桥接胶水）、`IosKoogMessageMemoryStore`、`IosUnavailableImageInferenceEngine`（**VLM expect 在 iOS 为 unavailable 桩**）。
- commonMain 现状用 **Flow×15 / suspend×107 / sealed×14 / value class×1**；依赖 **SKIE**（`shared/build.gradle.kts:5`）桥接到 Swift。

---

## 2. 契约里需修正的 4 处（A/B/C + 新增 D）

> A/B/C **不是改代码**——是契约措辞要匹配"已选定的架构（SKIE 中介的不对称 native）"。D 是补齐 UI 一致性机制。

### A. engines 双端产物 → 各端 native 自理

- 原文："`:engines:*` 产出 `.so`（Android）和 `.framework`/`.a`（iOS），KMP 模块仅通过薄接口消费。"
- **问题**：engines 是 `com.android.library`，**只产 Android `.so`**；iOS 用 `iosApp/Frameworks/MNN.framework`+Pods+Swift 独立 native，**不消费 `:engines:*`**。
- 证据：`shared/build.gradle.kts:57-61`（"AGP 9 KMP 库插件不支持 externalNativeBuild，须独立 com.android.library 承载 JNI"）。

### B. "无语法糖" → "Swift seam 扁平"（Principle 1 重写）

- 原文："接口设计遵循'简单、扁平、无 Kotlin 语法糖'原则。"
- **评估自纠**：上一轮我主张"有 SKIE 兜底即可"——**在 Principle 1 下被否定**。SKIE 的 `Flow→AsyncSequence`/`sealed→enum`/`suspend→async` 是**隐式魔法变换**，大模型对隐式变换生成的 Swift 成熟度低、易错（memory 中 KMP 互操作踩坑多源于此）。
- **修正立场**：commonMain 内部允许语法糖；**跨 Swift 的 seam 必须扁平**，SKIE 仅兜底、不作 correctness 依据。
- 连带：`UserPreferences.kt:207` 的 `value class ModelCategory` 从 cosmetic 升级为**真问题**（Swift 侧隐身），跨界应改显式 data class。

### C. VlmTagger 双端举例 → 按需

- 原文举例："commonMain 定义 `expect interface AsrEngine`/`VlmTagger`，双端消费。"
- **现状**：唯一 VLM expect `ImageInferenceEngine` 在 iOS 是 unavailable 桩；iOS 打标走自有 native。该 expect 非真双端 seam，不投机。

### D.（新增）UI 一致性机制（Principle 2+3 补齐）

- 契约 P1 说"不共享 UI"但**未说如何保障一致性**。
- **补**：双端 UI 不共享**代码**，但共享一份 **AI 可读的设计规范 SSOT**（design tokens + 组件行为 + 交互流程）。先例：`coordinate-system-standard`（106pt 坐标双端同源）已是该模式的成熟样本。
- 迭代模型：Android 为 pace-setter → `/ios-follow` 一键对等跟随 → `screenshot-diff.py`/`swiftui-expert`/`ios-i18n-validator` 做 parity 校验（链路已存在，非新建）。

---

## 3. 可行方案（按风险/成本排序）

### Stage 0 — 改契约 4 条措辞（零代码风险，纯准确化）

review 用拟稿（可直接改）：

**A（engines）**
> `:engines:*`（`mnn-core`/`agent-native`）为 **Android-only** `com.android.library`，仅产 Android `.so`；iOS 侧 native（`MNN.framework`+Pods+Swift）独立维护，**不消费 `:engines:*`**。两端 native 各自自理，`:shared` KMP 只统一业务逻辑。

**B（Swift seam，Principle 1）**
> **commonMain 内部**（Kotlin↔Kotlin）允许 Flow/sealed/suspend，不强行扁平化。**跨 Swift 的 seam 必须扁平**：iosMain 的 `*Bridge`/`*Dto` 层将 Flow→显式回调、sealed→DTO+enum tag、suspend→显式 async 回调、`value class`→显式 data class。**SKIE 仅作兜底，不作 correctness 依据**。AI 生成的 Swift 只对着扁平 seam 写。

**C（expect 按需）**
> commonMain 可定义扁平 `expect` 接口，**按需引入**。当前唯一 VLM expect `ImageInferenceEngine` 在 iOS 为 unavailable 桩，iOS 打标走自有 native；双端共享 seam 待该能力真正需要跨端时再建。

**D（UI 一致性，Principle 2+3）**
> 双端 UI **不共享代码**（Android=Compose / iOS=SwiftUI），但共享一份 **AI 可读设计规范 SSOT**（tokens+组件行为+交互流程），两端各自原生实现并对照。先例：`coordinate-system-standard`。迭代：Android pace-setter → `/ios-follow` → parity 工具校验。

### Stage 1 — 固化为 ADR-013（低风险）

- 决策"为什么"**以 3 原则开头**（AI 友好优先 > 双端一致性 > 其他），附"不做 CMP"6 条理由。
- 内容 = 修正后的 6 条契约（A/B/C/D + P3 合规 + P5 边界）。
- 引用 ADR-005/008/009/010 不重复；顺手修 CLAUDE.md 的 ADR 引用陈旧（doc-sync 硬规则）。

### Stage 2 — 构建期守卫（最划算，高杠杆）

沿用 `checkNoFullyQualifiedName` 套路，加两个 task：

1. **`checkCommonMainPurity`**：commonMain 出现 `@Composable` / `^[[:space:]]*actual ` 声明 / `^import (android|java)\.` → **build fail**。
2. **`checkIosSeamFlat`**（轻量 grep 级）：iosMain `*Bridge*.kt`/框架公开面出现裸 `Flow<` / `value class` → fail（sealed 暂 warn，靠 DTO 转）。

> 价值：直接防住"一次坏提交悄悄搞挂 iOS 编译"——当前纯度仅 de-facto 成立，未强制。

### Stage 3（可选，建议缓做）— 设计规范 SSOT 整合

把 `coordinate-system-standard` 先例泛化为 UI 设计规范（token+组件+交互），写进 `/ios-follow` + parity 工具对照流程。偏重，等 Stage 0–2 落定。

---

## 4. 明确不做（避坑）

- ❌ 让 `:engines:*` 出 iOS framework——iOS native 已在跑，成本巨大、收益为零。
- ❌ 从 commonMain 清除 Flow/sealed/suspend——会毁掉 Agent runtime（107 suspend）；靠扁平 seam 解决，不靠拆 commonMain。
- ❌ 现在引入 VLM/ASR 双端 expect——iOS 尚未需要（桩 = unavailable），投机。

---

## 5. 待 review 决策点

1. **Stage 0 四条措辞**（尤其 B 的"seam 扁平"、D 的"设计规范 SSOT"）是否认可？需逐条改否？
2. **ADR-013 编号**确认（按目录实查下一个 = 013；你是否有已占用的草稿）？
3. **Stage 2 范围**：`checkIosSeamFlat` 现在做，还是先只做 `checkCommonMainPurity`？
4. **Stage 3**：做不做？何时做？

## 6. 建议执行顺序

Stage 0 → 1 → 2（三者全在低风险面内，把契约从"聊天记录"变成"可机器判定约束 + 决策记录"）；Stage 3 缓做。
