# ADR-009: 本地 LLM 收缩至相机场景

**状态**: 规划中（波次1 待执行）
**日期**: 2026-07-28
**决策**: 用户
**依赖**: ADR-005、ADR-008、ADR-010；review §0.3-D2

---

## 1. 背景

端侧 Qwen3.5-2B（MNN-LLM）与远程模型（DeepSeek 等）能力差距巨大。为"全场景都能本地推理"维护的本地适配（chat/gallery 的本地分支、本地 JSON 协议、L1/L2/L3 策略）持续拖累远程链路收敛，且本地小模型在 chat/相册这类需要 tool_calls、多轮、长上下文的场景上体验远不如远程。

## 2. 决策

**本地 LLM 能力收缩至相机场景。** 其他场景（chat、相册、图编辑等）一律使用远程 LLM，本地模型不再为这些场景适配。相机场景保留本地模型（离线、低延迟、隐私媒体处理）。

## 3. 实现要点（波次1）

- **删 chat 本地分支**：`ChatViewModel` 的 `ChatModelOption.Local` 枚举值、`currentModelLabel()` 的 local 分支、`processAgentInput`（已是死代码）、chat 对 `getLastLocalGenerationMetrics` 的引用。
- **清非相机本地适配**：`features/gallery/components/AiChatPanel.kt`、`features/common/chat/AgentChatComponents.kt` 中对本地模型的分支。
- **本地链路相机化**：`LocalLlmEngine`/`LocalInferencePipeline`/`IntentCache`/`LocalCommandParser`/`LocalPromptBuilder` 标注「相机专用」，理想下沉到相机相关模块或 `runtime-core/local/` 子包，编译期禁止 chat/gallery 引用。
- **`AiAgentUseCase` 收敛**为纯相机 Facade，删 REMOTE/FEISHU 重复分支与 legacy 映射中所有非相机命令。

## 4. 后果

- ✅ 远程 LLM 能力与价值充分释放，不再被本地兼容性拖累。
- ✅ 本地链路复杂度收敛到一个场景，可针对性优化（延迟/内存）。
- ✅ 一并消除 review 中的 P1-2（chat 多入口/重复分支）、部分 P1-6 范围。
- ⚠️ 相机场景外断网即不可用 Agent（可接受：相册/chat 本就依赖远程）。
- ⚠️ 本地模型相关单测需随之归拢到相机域。

## 5. 状态

| 项 | 状态 |
|---|---|
| 决策与 ADR | ✅ 2026-07-28 |
| chat/gallery 本地分支清理 | ⏳ 波次1 |
| 本地链路包结构下沉 | ⏳ 波次1 |

## 6. 相关

- ADR-010（远程/本地链路隔离，本 ADR 的执行载体）、ADR-005、ADR-008
- review §0.3-D2、P1-2、P1-6
