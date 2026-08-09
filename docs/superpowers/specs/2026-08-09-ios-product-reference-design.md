# iOS 实现参考·产品文档 设计说明（Spec）

> **性质**：brainstorming 产出的设计说明（spec）。定义要产出的「iOS 实现参考产品文档」的目标、结构、模板、核验方法论。审批后经 writing-plans 产出细粒度执行计划，再进入实际抽取与撰写。
>
> **日期**：2026-08-09 · **作者**：Claude（brainstorming）

---

## 1. 目标与背景

### 1.1 要解决的问题

PoLang 正在做 KMP 跨端改造（Phase 5 iOS 骨架已落地，Phase 6 功能对齐进行中）。iOS 实现者需要一份**从产品视角**、**以 Android 现有代码为准**、**结构化**的功能参考文档，知道「Android 上到底有哪些已上线产品功能、每个功能的产品行为是什么、iOS 侧当前状态与落点」。

现有文档不够用：
- `PRODUCT.md` / `docs/01-PRODUCT/FEATURES.md` 内容详尽，但**已落地（✅/🔄）与规划中（📋）混杂**，引用大量 Android 专有实现（MediaPipe / OpenGL ES / Room），且未按「iOS 实现参考」组织。
- `docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` 是**技术改造路线图**（Koog 迁移 / KMP 抽取 / 引擎移植），不是产品功能规格。
- `IOS_ANDROID_UI_PARITY.md` 是 UI 像素对齐方法论，不覆盖产品功能面。

### 1.2 目标

产出一份**单一 Markdown 文档**：以功能域为骨架、统一模板、**逐项以 Android 实际代码核验**的「已落地产品功能规格」，作为 iOS Phase 6+ 功能对齐的产品侧唯一参考。

### 1.3 决策锁定（用户 2026-08-09 确认）

| 决策点 | 结论 |
|--------|------|
| 覆盖范围 | **全量已落地功能**（Android 上 ✅ 已落地 + 🔄 部分落地；📋 规划中仅在能力地图标注，不展开产品规格） |
| 颗粒度 | **产品规格级**：定位 + 入口 + 功能项清单 + 核心状态/流程 + 关键 UX 规则 + 数据模型概要 + 隐私/降级边界 + iOS 落点 |
| 产出形态 | **单一 Markdown 文档**，置于 `docs/01-PRODUCT/` |
| 核验力度 | **全量代码核验**：每个功能项逐一对照 Android 源码确认是否真已上线；与 FEATURES.md/PRODUCT.md 不符处显式记入「文档漂移清单」 |
| 语言 | 中文为主，沿用代码库既有的英文术语（Capability / TAG / Gallery 等） |
| 文件名 | `docs/01-PRODUCT/IOS_PRODUCT_REFERENCE.md` |

---

## 2. 产出物

**唯一交付物**：`docs/01-PRODUCT/IOS_PRODUCT_REFERENCE.md`

- 与 `PRODUCT.md`（目标/路线图）、`FEATURES.md`（交互细节）、`NFR_SPEC.md`（非功能）并列于 `docs/01-PRODUCT/`。
- 定位为「**iOS 实现参考**」：每个功能域带「iOS 落点」子节，标注 iOS 当前状态、消费的 shared 契约、平台替换项。
- 通过相对链接引用既有技术规格（`TAG_GENERATION.md` / `GALLERY_SEARCH.md` / `BEAUTY_ENGINE_TECH_SPEC.md` 等），不复制其内容。

---

## 3. 文档结构（方案 A：功能域骨架 + 跨切面附录）

```
IOS_PRODUCT_REFERENCE.md
├─ 0. 文档说明（目的/使用方式/状态图例/与既有文档关系/代码核验原则）
├─ 1. 产品全景
│   ├─ 1.1 产品命题与形态（Agent 驱动的智能相册）
│   ├─ 1.2 应用骨架与导航拓扑（相册首页默认入口 / 4 页 Pager / FloatingBottomTab / 设置入口 / 全屏横滑）
│   └─ 1.3 功能能力地图（表：功能域 → 功能项 → iOS 状态 → shared 契约）★
├─ 2. 功能域详解（统一 8 子节模板，见 §4）★
│   ├─ 2.1 相册与浏览
│   ├─ 2.2 自然语言搜索
│   ├─ 2.3 图片编辑（静态美颜编辑 / 智能抠图 / 证件照 / AI 一键优化 / 对话式编辑）
│   ├─ 2.4 AI 对话
│   ├─ 2.5 Agent 编排与能力
│   ├─ 2.6 人物记忆与关系图谱
│   ├─ 2.7 自动标签生成（TAG 3-Pass）
│   ├─ 2.8 相机（辅助入口）
│   └─ 2.9 设置与账号
├─ 3. 跨切面契约（附录）
│   ├─ 3.1 导航路由表（Screen.kt 路由全集）
│   ├─ 3.2 Capability → 意图路由表（command → capability，SSOT）
│   ├─ 3.3 数据与持久化概要（Room 表 / DataStore / shared commonMain DTO）
│   ├─ 3.4 隐私红线与端云边界（隐私分级 + 100% 端侧清单）
│   ├─ 3.5 i18n 三语规范
│   ├─ 3.6 设计系统（tokens / 设计规则）
│   └─ 3.7 性能红线（NFR 摘要）
├─ 4. iOS 实现对齐总览（矩阵：功能 → iOS 状态 → shared 契约 → 平台注意 → 对应 Phase）
└─ 5. 附录（文档漂移清单 / 相关文档索引）
```

★ = 核验重点章节。

### 3.1 功能域 → Android 源码映射（核验起点）

| 功能域 | 主要 Android 源码位置 |
|--------|----------------------|
| 相册与浏览 | `features/gallery/`、`features/search/`、`domain/`（媒体） |
| 自然语言搜索 | `features/search/`、`domain/search/`（ExplicitFirstSearchPipeline / QueryParser） |
| 图片编辑 | `features/editor/`、`domain/matting/`、`features/idphoto/`、`domain/agent/capability/ImageEditCapability` |
| AI 对话 | `features/chat/`、`features/common/chat/`、`features/chat/AGENTS.md` |
| Agent 编排与能力 | `domain/agent/capability/`、`:shared/commonMain`（AgentOrchestrator/CapabilityRegistry）、`docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` |
| 人物记忆与关系 | `features/person/`、`domain/person/`、`domain/aesthetic/`、`features/settings/MemoryFactsScreen` |
| 自动标签生成 | `domain/tag/`、`features/gallery/components/TagGenerationControlScreen.kt`、`features/tagviewer/` |
| 相机 | `features/camera/`、`:engines:beauty-engine`、`:engines:mnn-core` |
| 设置与账号 | `features/settings/`、`features/backuprestore/`、`domain/backup/` |

---

## 4. 功能域详解·统一模板（8 子节）

每个功能域（§2.1–2.9）严格套用以下模板，保证体系性与可扫描性：

1. **功能定位** — 该域解决什么、在产品中的角色；**iOS 落点**：当前骨架状态（已有 / 待对齐 / 缺口）、消费的 shared 契约、平台差异提示。
2. **入口与导航** — 用户从哪里到达（顶部栏 / 底部 Tab / 工具栏 / Agent 指令 / 深链），到达后的首屏。
3. **功能项清单** — 逐项列出已落地子能力，每项：`状态标记` + 名称 + 一句话行为 + 关键参数/默认值。状态图例见 §5。**逐项代码核验**。
4. **核心状态与流程** — 关键状态机 / ASCII 流程图（happy path + 主要分支），只画产品可见态，不画内部实现。
5. **关键 UX 规则** — 交互阈值、默认值、跟手性、反馈规范、降级提示等（产品红线相关）。
6. **数据模型概要** — 涉及的实体、持久化表、关键字段、shared DTO（指向 §3.3，不重复全表）。
7. **隐私 / 降级边界** — 端侧 vs 远程、隐私分级、无网络/无权限/模型缺失时的降级路径。
8. **iOS 对齐要点** — 该域对应 iOS Phase、缺口清单、平台替换项（如 Room→SQLDelight、OpenGL→Metal、MediaPipe 同源、Foreground Service→BGTaskScheduler）、App Store 合规注意（如 2.5.2）。

> **体系性来源**：8 子节模板 + 9 域同一骨架 + 状态图例统一。任一 iOS 开发者翻到某域即可按固定结构获取「做什么→怎么到达→有哪些子能力→状态流→UX 红线→数据→隐私→iOS 怎么落」。

---

## 5. 状态图例

| 标记 | 含义 |
|------|------|
| ✅ | 已落地（代码核验通过，main 可用） |
| 🔄 | 部分落地（核心可用，子项未完成） |
| 📋 | 规划中（仅产品意图，**不展开产品规格**，在能力地图列出） |
| ❌ | 已移除（历史能力，标注以防 iOS 误复刻，如端侧文本 LLM、InsightFace） |

iOS 状态独立标记：`iOS 已有` / `iOS 待对齐` / `iOS 缺口`。

---

## 6. 代码核验方法论（全量核验）

### 6.1 每个功能项的核验动作

对 §2.1–2.9 每个功能项：

1. **定位实现**：在 §3.1 映射的源码位置找到对应类/文件/Screen。
2. **确认上线**：核对是否在 main 分支可用的代码路径（非 worktree/实验分支、非被 `if (BuildConfig.DEBUG)` 或 feature flag 关闭）。
3. **抽取产品行为**：从 ViewModel/UseCase/Capability + Screen 读取状态、流程、默认值、UX 规则。
4. **交叉比对**：对照 `FEATURES.md` / `PRODUCT.md` 对应描述。
5. **记录漂移**：任何「文档写了但代码没有」「代码有了但文档没写」「状态标记不符」→ 记入 §5 附录·文档漂移清单（格式：`[域] 文档说 X，代码实为 Y，以代码为准`）。
6. **落笔**：以代码实际行为为准写入对应子节。

### 6.2 验证手段

- 静态阅读为主（Read/Grep/Glob + 并行 subagent 分域深读）。
- 状态机/流程以代码中实际 state/状态枚举/导航动作为准，不臆造。
- 持久化表以 `AppDatabase`（Room）实际 `@Entity` + 版本号为准。
- Capability 注册以 `CapabilityRegistry` 实际 `register(...)` 调用为准（对照 `CAPABILITY_REGISTRY.md`）。
- iOS 状态以 `iosApp/PoLang/Features/` 实际 Swift 文件为准。

### 6.3 不做的事

- 不逐像素复述 UI（属 `IOS_ANDROID_UI_PARITY.md` / FEATURES.md 范畴）。
- 不复制技术规格全文（TAG/搜索/美颜引擎/JS 沙盒各自有 SSOT，本文档给摘要 + 链接）。
- 不规格化 📋 规划中功能。
- 不规格化 server 端实现（仅在设置/账号域引用其 API）。

---

## 7. 范围边界

**In scope**：Android main 分支全部 ✅/🔄 产品功能的产品规格 + iOS 落点 + 跨切面契约附录。

**Out of scope**：
- 📋 规划中功能（仅在能力地图列出名称与意图）。
- server 工程实现细节（`server/`）。
- 单元/仪器测试用例。
- 逐像素 UI 规格与动效曲线。
- Android 构建脚本 / Gradle 配置。

---

## 8. 风险与对策

| 风险 | 对策 |
|------|------|
| 代码与既有文档漂移量大，核验耗时 | 分域并行 subagent；漂移只记录不修文档（除非用户另要求） |
| 文档体量大（9 域 × 8 子节） | 分域撰写、单文件汇总；用模板与图例控制每节密度 |
| 跨功能流程（聊天发图→对话式编辑→结果回渲染）被域切分 | 在相关域「核心状态与流程」互相 §引用 |
| iOS 状态随 Phase 6 推进快速变化 | 每域 iOS 落点标注「截至 2026-08-09」；列为需周期复核项 |
| 规划中功能边界模糊 | 严格用状态图例；📋 项不展开 |

---

## 9. 后续

1. 本 spec 经用户复核通过。
2. 经 **writing-plans** 产出细粒度执行计划：按 9 功能域 + 3 跨切面附录拆分撰写任务，每任务含「源码核验清单 + 撰写产物 + 自检项」。
3. 执行计划批准后进入抽取与撰写（分域并行 subagent 核验 → 汇总为单一文档）。
4. 完成后更新 `docs/01-PRODUCT/` 索引与本文档「变更记录」。
