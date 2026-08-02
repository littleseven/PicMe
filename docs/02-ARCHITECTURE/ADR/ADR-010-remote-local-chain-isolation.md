# ADR-010: 远程与本地推理链路严格隔离

**状态**: 本地侧已删除（2026-08-02：隔离目标以"本地链路整体移除"方式达成）
**日期**: 2026-07-28
**决策**: 用户
**依赖**: ADR-005、ADR-008、ADR-009、ADR-012；review §0.3-D3

---

> ## ⛔ 状态更新（2026-08-02）：本地链路已整体删除
>
> 本 ADR 的隔离目标最终以最彻底的方式达成：端侧文本 LLM（Qwen3.5-2B）与整条本地链路（`LocalCameraAgent`/`LocalInferencePipeline`/`IntentCache`/`LocalCommandParser`/`LocalPromptBuilder`/`local/llm/` 适配层）已完全移除。链路「隔离」演进为「单一远程链路」：chat 走 `streamChat`，相机走 `processCameraInput`（`RemoteReActAgent` + `CameraToolService` 远程 tool_calls）；共享配置污染与跨域依赖随本地侧删除而根除。
> `LocalLlmEngine` 仅存 `imageInference`（Qwen3-VL-2B 端侧 VLM 打标，TAG Pass3）。下文双链路设计图与实现要点保留作历史记录。

## 1. 背景

ADR-005 做了「协议分离」，但 `AgentOrchestrator` 仍是单一入口同时承载两条链路，导致：

- **三入口分叉**（`processInputWithRouter`/`processUserInput`/`streamChat`），各自带模型加载、L1 缓存、记忆回写，逻辑重叠又细节不一（review P1-2）。
- **共享可变单例配置**：`ChatViewModel` 每条消息 `configure(mode=getAgentMode(),…)`，把飞书临时 `modeOverride` 回写进持久 mode，污染且不自愈（review P0-3）。
- **跨域依赖**：chat（远程）却要关心本地模型加载/IntentCache；相机（本地）却被卷入 remoteConfig 同步。

## 2. 决策

**远程链路与本地链路严格隔离：拆为两个独立引擎，无共享可变单例配置、无交叉依赖。**

```
远程链路（chat/gallery/图编辑/设置）        本地链路（相机，已于 2026-08-02 删除）
  ChatRequest → RemoteChatEngine               CameraInput → LocalCameraAgent
   ├─ RemoteReActAgent (AiServices)             ├─ LocalInferencePipeline
   ├─ ChatToolService                            ├─ LocalLlmEngine (Qwen MNN)
   └─ DataStoreChatMemory                        ├─ IntentCache / LocalCommandParser
   配置：请求级，不走全局单例                     └─ MemoryManager（相机专用）
                                                配置：相机域内
```

## 3. 实现要点（波次1）

- **拆 `AgentOrchestrator`**：远程引擎（如 `RemoteChatEngine`）与本地相机 Agent（`LocalCameraAgent`）各自独立实例；移除"一个 orchestrator 同时服务两条链路"的设定。
- **消除跨域回写**：删除 `configure(mode=getAgentMode())` 这类把栈顶覆盖写进持久 mode 的调用点（`ChatViewModel`、`PoLangApplication` 多处）。模型选择以**请求级参数**传递，不改全局单例。→ 直接解决 P0-3。
- **依赖单向切断**：远程链路不再依赖 `LocalLlmEngine`/`IntentCache`/本地 JSON 协议；本地链路不再依赖 `RemoteModelConfig`/`ChatToolService`。
- **chat 不再每条消息重配 orchestrator**。
- **`modeOverride` 栈退役或限定飞书域**：隔离后 chat 与飞书不共用同一 orchestrator，override 泄漏面消失。
- **收敛入口**：两条链路各自的入口单一化（解决 P1-2 三入口），删 `processUserInput` 重复分支与不可达 `handleInferenceResult`。

## 4. 后果

- ✅ 根因级消除 P0-3（配置污染）、P1-2（多入口/重复分支），并承载 ADR-009（本地收缩）落地。
- ✅ 两条链路可独立演进、独立测试（改善可测性）。
- ✅ 远程链路彻底摆脱本地模型耦合，释放远程能力。
- ⚠️ 是波次中改动最大的一块，需配套回归（编译 + JVM 单测 + 设备冒烟）。
- ⚠️ 飞书远程控制（`processRemoteImInput`）归属需明确（归远程引擎或独立 RPA 引擎）。

## 5. 状态

| 项 | 状态 |
|---|---|
| 决策与 ADR | ✅ 2026-07-28 |
| step1 消除共享配置污染（P0-3 止血：`updateRemoteRuntimeConfig` + 5 处泄漏点改调） | ✅ 已合 main |
| step2 抽出 `RemoteChatEngine`（chat 远程链路：streamChat/processChatReAct/getChatAgent/chatSystemPrompt） | ✅ `treatment/d3-chain-isolation`（be8e3edf） |
| step3 抽出 `LocalModelService`（模型加载服务，相机 Agent + 后台打标 Worker 共用） | ✅ `treatment/d3-chain-isolation`（6d1cd7a5） |
| step4 消费者迁到 `localModelService` 直调 + 删 AgentOrchestrator 委托层（8 个方法） | ✅ 已合 main（a9a5f61c / c1445d1f） |
| step5 抽出 `LocalCameraAgent`（本地相机推理路径：processInputWithRouter/processUserInput + helper）+ 消费者迁移 + 删委托 | ✅ `treatment/d3-step5b-local-camera-agent`（21f76442 / 7181a269） |
| 入口收敛 / 死代码清理（`handleInferenceResult` 等） | ✅ step5a 已清（b78996e0，已合 main） |
| 本地链路整体删除（端侧文本 LLM 移除，相机改远程 tool_calls） | ✅ 2026-08-02 |

## 6. 相关

- ADR-009（本地收缩）、ADR-012（记忆统一，随隔离一并落地）、ADR-005
- review §0.3-D3、P0-3、P1-2、P1-6
