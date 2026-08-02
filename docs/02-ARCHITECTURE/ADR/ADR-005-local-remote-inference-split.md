# ADR-005: 本地/远程推理协议分离与产品重心迁移

**状态**: 已实施 (Implemented) —— 本地链路部分已于 2026-08-02 被超越（见下方状态更新）  
**日期**: 2026-06-15  
**更新日期**: 2026-06-18；2026-08-02（状态更新）  
**决策**: RD  
**依赖**: ADR-003（坐标系统管理 — 图片编辑复用美颜管线）

---

> ## ⛔ 状态更新（2026-08-02）：本地链路已整体删除
>
> 本 ADR 确立的「本地/远程双链路」已演进为**文本推理全远程**：端侧文本 LLM（Qwen3.5-2B）及整条本地链路（`LocalInferencePipeline`/`LocalCameraAgent`/`LocalCommandParser`/`LocalPromptBuilder`/`local/llm/` 适配层、`IntentCache`/`L1CacheSettings`、`AiAgentMode.LOCAL`、`AiAgentInferencePreference`、chat `ChatModelOption.Local`、qwen3_5_2b 模型下载条目与设置 UI）已完全移除。
> 相机 AI 指令从「本地收缩」（ADR-009）进一步演进为**远程 tool_calls**：`AgentOrchestrator.processCameraInput` → `RemoteReActAgent` + `CameraToolService`（相机场域 @Tool 工具集：capture/adjust_beauty/switch_filter/adjust_zoom/flip_camera 等）→ `ToolCallCommandParser` → `CapabilityRegistry.dispatch`。
> `LocalLlmEngine` 仅保留 `imageInference`（Qwen3-VL-2B 端侧 VLM 打标，TAG Pass3）。
> **下文中所有描述本地链路的"当前"设计均为历史记录**，保留用于理解决策脉络，不再代表现状。

## 1. 背景与问题陈述

### 1.1 当前架构的核心矛盾

```
┌─────────────────────────────────────────────────────────────────┐
│                     InferenceRouter                             │
│  ┌──────────────┐  统一路由  ┌──────────────┐                    │
│  │  LocalEngine  │◄─────────►│  RemoteOrch  │                    │
│  └──────────────┘  共享      └──────────────┘                    │
│                     协议       ┌──────────────────┐              │
│                     与        │ToolCallingChatLM   │              │
│                     Prompt    │ (包装层+Prompt注入)│              │
│                                └──────────────────┘              │
│                     共享       ┌──────────────────┐              │
│                     Prompt    │  PromptBuilder    │              │
│                     系统      │  (统一两套推理)   │              │
│                                └──────────────────┘              │
└─────────────────────────────────────────────────────────────────┘
```

**核心问题**：

1. **协议耦合**：`InferenceRouter` 同时承载本地和远程推理的路由逻辑，统一的 `PromptBuilder` 不得不为两套模型生成通用的 Prompt，导致双方都做了妥协——本地模型被注入过量 tool_calls 修饰符，远程模型被限制为简单的 JSON 数组格式。
2. **包装层膨胀**：`ToolCallingChatLanguageModel` 通过字符串注入将 OpenAI tool_calls 协议伪装成 Prompt 附加到 system message 中，再由 `ToolCallingOutputParser` 做多格式（OpenAI tools / ReAct / `` 标签 / 正则）的复杂解析——本质上是在用提示词工程模拟函数调用。
3. **能力差距未充分释放**：远程模型（DeepSeek/Kimi）原生支持 OpenAI Chat Completions 协议（含 `tool_calls`、流式输出、多轮对话、system prompt 等），但当前架构将它们限制在与本地模型相同的 Prompt + JSON 数组输出格式中，远未发挥远程模型的真正能力。
4. **产品重心漂移**：项目初期以相机 AI 为核心，但端侧小模型在相机关联场景（实时预览美颜调节、滤镜切换）中体验受限，而相册场景（图片编辑、OCR、分类搜索）更能发挥 AI 的价值。

### 1.2 架构复杂度数据

| 指标 | 值 |
|------|-----|
| `InferenceRouter` 行数 | ~600 行（LOCAL/REMOTE/L3/L2/Cache 逻辑交织） |
| `PromptBuilder` 行数 | ~500 行（同时服务两种模型） |
| `ToolCallingOutputParser` 行数 | ~570 行（5 种解析策略） |
| 本地推理实际需要的自定义协议 | ~20 行简单的 JSON 数组格式 |
| 关联测试维护成本 | 高（每次协议调整需同步更新多个测试用例） |

---

## 2. 四个决策

### 决策 1: 本地/远程推理协议分离

#### 目标架构

```
┌─────────────────────────────┐    ┌──────────────────────────────────┐
│      本地推理链路              │    │      远程推理链路                  │
│                              │    │                                  │
│  Custom Protocol:            │    │  标准 OpenAI Chat Completions API │
│  ┌─ System Prompt ─────────┐ │    │  ┌─ Chat Completions 请求 ─────┐ │
│  │ 能力列表 + 场景上下文     │ │    │  │  POST /v1/chat/completions  │ │
│  └─────────────────────────┘ │    │  │  {model, messages, tools}    │ │
│  ┌─ User Input ────────────┐ │    │  └───────────────────────────────┘ │
│  │ "调高美颜到70"           │ │    │  ┌─ 标准响应解析 ───────────────┐ │
│  └─────────────────────────┘ │    │  │  choices[0].message 支持:     │ │
│  ┌─ Output ────────────────┐ │    │  │  ├─ content (闲聊回复)        │ │
│  │ JSON 数组:              │ │    │  │  └─ tool_calls (命令)         │ │
│  │ [{"method":"set_beauty",│ │    │  └───────────────────────────────┘ │
│  │   "args":{...}}]        │ │    └──────────────────────────────────┘
│  └─────────────────────────┘ │
└─────────────────────────────┘
```

#### 关键差异

| 维度 | 本地推理 | 远程推理 |
|------|---------|---------|
| **协议** | 自定义 JSON 数组 | 标准 OpenAI Chat Completions API 格式 |
| **协议覆盖范围** | 仅 JSON 数组命令 | system/user/assistant 消息、tool_calls、流式、多轮对话 |
| **Library** | 无第三方依赖 | LangChain4j 作为 SDK 接入层（可替换） |
| **Prompt** | 精简、结构化、约束严格 | 自然语言 + Tool Schema |
| **输出解析** | 简单 JSON 数组解析 | 标准 JSON 反序列化（按 `choices[0].message` 结构解析） |
| **约束方式** | JSON 数组格式 Prompt 约束 | OpenAI 原生协议约束 |
| **聊天/闲聊** | 通过 text_reply 命令兜底 | 原生支持 |
| **Strategy** | L1 Cache / L2 Batch | L3 Plan / L4 ReAct Chat |

#### 移除的耦合组件

- [x] `InferenceRouter` — 统一路由拆分为两条独立链路
- [x] `ToolCallingChatLanguageModel` — Prompt 注入模拟函数调用
- [x] `ToolCallingOutputParser` — 多格式兼容的复杂解析器（移除 Open AI / ReAct / Tag / Regex 四种解析分支）
- [x] `ToolPromptBuilder` — 将 Tool schema 转为 Prompt 字符串的工具
- [x] `AdaptiveStrategySelector` — L1/L2/L3/L4 策略选择器（本地不再需要策略分级）
- [x] `IntentCache` L1 缓存（移入共享缓存模块 `runtime/cache/`，本地/远程共用）

---

### 决策 2: 代码清理

基于决策 1 的协议分离，执行以下清理：

#### 2.1 冗余文件清理

| 文件 | 清理方式 | 说明 |
|------|---------|------|
| `InferenceRouter.kt` | 删除 | 拆分为两条独立链路 |
| `ToolCallingChatLanguageModel.kt` | 删除 | 远程直接使用 langchain4j 原生 OpenAI 客户端 |
| `ToolCallingOutputParser.kt` | 删除 | 远程使用标准 OpenAI 响应格式，SDK 原生反序列化 |
| `ToolCallingConfig.kt` | 删除 | 不再需要 tool_calling 模式切换 |
| `ToolPromptBuilder.kt` | 删除 | 远程使用 ToolSpecifications，本地使用简单能力列表 |
| `ToolCallingMode.kt` | 删除 | OPENAI_TOOLS / REACT 枚举不再需要 |
| `ToolProvider.kt` | 简化 | 远程场景使用 langchain4j 按 OpenAI 协议生成 Tool Schema |
| `PromptBuilder.kt` | 简化 | 拆分为 LocalPromptBuilder 和 RemotePromptBuilder |

#### 2.2 类/接口简化

| 类 | 变更 |
|----|------|
| `AgentConfigurator` | 移除 `InferenceRouter` 创建逻辑；直接提供 `LocalLlmEngine` 和 `RemoteOrchestrator` 实例 |
| `AgentOrchestrator` | 删除 `routeToLocalL3()`、`tryLocalL3First()`、`fallbackToRemote()`、`handleInferenceResult()` 等桥接方法 |

#### 2.3 设置项清理

| 设置项 | 操作 | 说明 |
|--------|------|------|
| `AiAgentInferencePreference` | 保留但简化（2026-08-02 已随端侧文本 LLM 删除） | AUTO/FORCE_LOCAL/FORCE_REMOTE 三态 → 仅保留 FORCE_LOCAL/FORCE_REMOTE |
| `AiAgentPrivacyLevel` | 保留 | 隐私红线逻辑不变 |

---

### 决策 3: 废弃测试删除

**原则**：架构重构期间，编译不过的测试直接删除，不在此阶段修复。

#### 需要删除的测试文件

| 测试文件 | 原因 |
|---------|------|
| 涉及 `InferenceRouter` 的测试 | 该类被删除，测试无意义 |
| 涉及 `ToolCallingOutputParser` 的测试 | 该类被删除 |
| 涉及 `ToolCallingChatLanguageModel` 的测试 | 该类被删除 |
| 涉及 `AdaptiveStrategySelector` 的测试 | 该类被删除 |
| 涉及旧 `PromptBuilder` 统一 Prompt 的测试 | PromptBuilder 拆分为两个 |
| 相机场景驱动的测试（大部分） | 产品重心迁移，相机 AI 测试降级 |

#### 保留的测试

| 测试 | 保留理由 |
|------|---------|
| `LocalLlmEngine` 相关测试 | 本地推理链路仍需验证 |
| `RemoteOrchestrator` 相关测试 | 远程推理链路需验证 |
| 美颜引擎 `BeautyRenderer` 测试 | 美颜引擎独立于 AI 架构 |
| 基础 Capability 注册/分发测试 | Capability 系统不变 |
| 相册相关 `GalleryCapability` 测试 | 产品重心迁移，相册为核心场景 |

---

### 决策 4: 产品重心迁移 — 从相机到相册与图片编辑

#### 重心转移说明

```
2026-06 之前             2026-06 之后
┌───────────┐            ┌─────────────┐
│  相机主导  │     →      │ 相册+编辑主导  │
│           │            │              │
│ Agent ↔   │            │  Agent ↔     │
│ 实时预览   │            │  相册浏览      │
│  美颜调节  │            │  图片编辑      │
│  滤镜切换  │            │  智能美颜      │
│  语音拍照  │            │  OCR 文字识别  │
│           │            │  智能搜索/分类  │
│  相册为辅  │            │  批量处理      │
└───────────┘            │  云端能力接入   │
                          └──────────────┘
```

#### 核心原因

1. **AI 在相册场景的不可替代性**：端侧 AI 在相机实时预览中的价值（语音命令代替手动操作）对端侧小模型要求极高，而相册场景（自动识别、智能分类、图片美颜建议、OCR 提取）是 AI 天然的优势场景。
2. **延迟容忍度不同**：相机实时交互要求 <100ms 响应，端侧小模型难以稳定满足；相册场景允许 1-3s 处理延迟，远程模型可充分发挥能力。
3. **编辑场景与现有资产复用**：`beauty-engine` 的美颜 Shader 管线可直接复用于相册图片编辑（通过 `PhotoProcessorImpl.kt` 的离屏渲染），无需额外投入。
4. **用户体验完整性**：用户拍照后的编辑/管理/分享流程是自然延续，AI Agent 在此流程中可提供持续价值。

#### 产品优先级映射

| 优先级 | 场景 | AI 介入方式 | 推理引擎 |
|--------|------|------------|---------|
| P0 | 图片美颜编辑（磨皮/美白/美型） | 语音/文字命令调节 | 本地/远程 |
| P0 | 相册智能搜索（日期/地点/内容） | 自然语言查询 | 远程 |
| P0 | 批量处理（美颜/滤镜/压缩） | 多图选择 + 指令 | 远程 |
| P1 | OCR 文字识别 | 自动检测/语音触发 | 本地/远程 |
| P1 | 智能相册分类（人像/风景/美食） | 自动 + 手动 | 远程 |
| P2 | 相机 AI（语音拍照/实时调节） | 语音/文字命令 | 本地 |
| P3 | 相机聊天 | 文字对话 | 远程 |

#### 导航结构变更

```
旧结构:  相机 → (Agent) → 美颜 | 滤镜 | 拍照 → 相册 → 查看
                                                  → 编辑（有限）
新结构:  相册 → (Agent) → 智能搜索 | 批量编辑 → 单图编辑
        相机 → (Agent) → 拍照（简化）→ 相册（作为入口）
```

---

## 3. 技术实现

### 3.1 本地推理链路设计

```
User Input
    │
    ▼
┌──────────────────────────────────────┐
│       LocalInferencePipeline         │
│                                      │
│  1. L1 Cache Hit? → 直接返回命令     │
│  2. L2 Batch:                        │
│     a. BuildLocalPrompt(capabilities) │
│     b. LocalLlmEngine.chat(request)  │
│     c. JSON数组解析 → AgentCommand[] │
│     d. 缓存学习                       │
│                                      │
│  协议: 自定义 JSON 数组               │
│  [{"method":"set_beauty",             │
│    "args":{"smooth":70}}]             │
└──────────────────────────────────────┘
```

### 3.2 远程推理链路设计

```
User Input
    │
    ▼
┌──────────────────────────────────────┐
│     RemoteInferencePipeline          │
│                                      │
│  1. Build ChatRequest:               │
│     - SystemMessage + ToolSchema     │
│     - UserMessage                    │
│  2. LangChain4j ChatLanguageModel    │
│     → ToolExecutionRequest[]         │
│  3. ExecuteTool(requests)            │
│     → AgentCommand[]                 │
│  4. 支持 L3 Plan / L4 ReAct Chat    │
│                                      │
│  协议: 标准 OpenAI Chat Completions     │
│  {                                      │
│    "choices": [{                       │
│      "message": {                      │
│        "role": "assistant",            │
│        "content": null,                │
│        "tool_calls": [{                │
│          "id": "call_xxx",             │
│          "function": {                 │
│            "name": "set_beauty",       │
│            "arguments": "{"smooth":70}"│
│          }                               │
│        }]                                │
│      }                                   │
│    }]                                    │
│  }                                       │
└──────────────────────────────────────┘
```

### 3.3 Prompt 系统拆分

```kotlin
// 本地 Prompt — 精简、结构化
class LocalPromptBuilder {
    fun buildSystemPrompt(capabilities: List<Capability>, context: AgentContext): String = """
        你是 PoLang AI 助手，运行在端侧设备。
        请根据用户输入，输出 JSON 数组格式的命令。

        可用命令：
        ${capabilities.joinToString("\n") { "- ${it.name}: ${it.description}" }}

        输出格式：
        [{"method":"命令名称","args":{"参数名":值}}]
        如果无法理解，输出：{"method":"text_reply","args":{"text":"回复内容"}}
    """.trimIndent()
}

// 远程 Prompt — 标准 OpenAI 协议格式
// 通过 langchain4j 构建 ChatRequest，SDK 自动序列化为 OpenAI Chat Completions 请求体
class RemotePromptBuilder {
    fun buildChatRequest(
        systemPrompt: String,
        userInput: String,
        capabilities: List<Capability>
    ): ChatRequest {
        val toolSpecs = capabilities.map { cap ->
            ToolSpecification.builder()
                .name(cap.name)
                .description(cap.description)
                .build()
        }
        return ChatRequest.builder()
            .messages(
                SystemMessage(systemPrompt),
                UserMessage(userInput)
            )
            .toolSpecifications(toolSpecs)
            .build()
        // langchain4j 自动序列化为:
        // POST /v1/chat/completions
        // {"model":"...","messages":[...],"tools":[...]}
    }
}
```

### 3.4 入口调度

```kotlin
class AgentOrchestrator {
    fun dispatch(input: String): Result<AgentAction> {
        return when (configurator.getAgentMode()) {
            AiAgentMode.LOCAL -> localPipeline.process(input)
            AiAgentMode.REMOTE -> remotePipeline.process(input)
            AiAgentMode.OFF -> Result.failure(AgentDisabledException())
        }
    }
}
```

---

## 4. 迁移计划

### Phase 1: 协议分离 + 代码清理（1 周）

| 任务 | 输出 | 验收标准 |
|------|------|---------|
| 创建 `LocalInferencePipeline` | `agent-core/.../local/LocalInferencePipeline.kt` | 本地推理可独立运行 |
| 创建 `RemoteInferencePipeline` | `agent-core/.../remote/RemoteInferencePipeline.kt` | 远程推理可独立运行 |
| 删除 `InferenceRouter` 等冗余文件 | 清理清单中所有文件 | 编译通过 |
| 拆分 `PromptBuilder` | `LocalPromptBuilder` + `RemotePromptBuilder` | 两端 Prompt 互不干扰 |
| 删除不可编译的测试 | 删除清单中所有测试 | 测试命令无编译错误 |
| 设置项简化 | `AiAgentInferencePreference` 三态 → 双态 | 设置页 UI 正常工作 |

### Phase 2: 图片编辑 AI 集成（2 周）

| 任务 | 输出 | 验收标准 |
|------|------|---------|
| `EditImageCapability` 实现 | 支持美颜参数调节 | AI 可控制编辑页美颜 |
| `GallerySearchCapability` 实现 | 自然语言相册搜索 | "找昨天的照片"可工作 |
| 编辑页 Agent Chat 集成 | `MediaPager` 内 Chat 面板可用 | 编辑时 AI 助手可对话 |
| 照片编辑管线复用 | 复用 `PhotoProcessorImpl` | 预览/编辑美颜一致 |

### Phase 3: 相机场景精简（1 周）

| 任务 | 输出 | 验收标准 |
|------|------|---------|
| 相机 AI 功能降级为 P2 | 移除冗余的相机 Agent 命令 | 基础拍照功能不受影响 |
| 相机→相册导航优化 | 拍照后自动进入相册编辑 | 拍照→编辑流程流畅 |
| 导航结构调整 | 应用首页为相册 | 启动后进入相册 |

---

## 5. 后果分析

### 正面影响

- ✅ **协议清晰**：本地用自定义 JSON 数组（高效、可控），远程用标准 OpenAI Chat Completions API 格式（完整、标准），各取所长
- ✅ **代码量显著减少**：实际减少 ~1,500 行冗余代码（`InferenceRouter` ~600 + `ToolCallingOutputParser` ~570 + `ToolCallingChatLanguageModel` ~200 + `ToolPromptBuilder` ~100 + 配置类 ~100）。后续 2026-06 清理中额外移除 ~1,100 行（SherpaMnnAsrEngine + sherpa.mnn 包 + MnnAsrClient 等），累计 ~2,600 行
- ✅ **测试维护大幅降低**：删除不可编译测试后，测试套件聚焦于真正的业务逻辑
- ✅ **远程模型能力充分释放**：原生支持 tool_calls、流式输出、多轮对话、显式 system prompt，完整 OpenAI 协议兼容
- ✅ **产品定位更清晰**：相册 + 图片编辑是 AI 最能创造价值的场景
- ✅ **美颜引擎资产最大化复用**：`PhotoProcessorImpl` 的 GPU 离屏渲染管线直接服务图片编辑
- ✅ **本地模型负担显著降低**：不再需要为本地小模型注入复杂的 tool_calls Prompt，JSON 数组解析也更简单

### 负面影响

- ⚠️ **相机实时 AI 体验暂时降级**：P2 优先级意味着相机 Agent 功能（语音拍照、实时调节）短期不会有新投入
- ⚠️ **端到端测试覆盖率短期下降**：删除测试后需重新建立相册场景的测试覆盖
- ⚠️ **用户习惯迁移成本**：现有用户习惯了相机 AI 交互，需引导至相册场景

### 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 协议分离导致本地模型输出不稳定 | 低 | 高 | JSON 数组格式 Prompt 约束 + 离线回归验证（GBNF Grammar 已尝试后放弃） |
| 删除测试遗漏了仍然有效的用例 | 中 | 中 | 逐文件确认，保留 Capability + 美颜引擎测试 |
| 用户对照片编辑 AI 期望过高 | 中 | 低 | 分阶段交付，MVP 仅支持基本美颜命令 |
| 远程模型延迟影响相册流畅度 | 中 | 中 | 本地优先策略：基础编辑本地推理，复杂查询走远程 |

---

## 6. 状态

| 阶段 | 状态 | 日期 |
|------|------|------|
| Phase 1: 协议分离 + 代码清理 | ✅ 已完成 | 2026-06-18 |
| Phase 2: 图片编辑 AI 集成 | ✅ 已完成 | 2026-06 |
| Phase 3: 相机场景精简 | ⏳ 待开始 | - |

---

## 7. 相关文档

- `docs/01-PRODUCT/FEATURES.md` — 交互规范
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — Agent 运行时架构
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — 远程推理架构详细设计
- `agent-core/AGENTS.md` — Agent Core 模块规范
- `ADR-001` — 美颜引擎分层架构（图片编辑复用基础）
- `ADR-003` — 坐标系统管理（图片编辑关键点定位基础）
- `ADR-006` — 命令系统包隔离
