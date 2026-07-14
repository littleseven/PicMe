# Agent UI 层设计：远程 LLM 编排可视化

> **状态**: 已批准  
> **作者**: Claude  
> **日期**: 2026-05-29  
> **关联文档**:
> - 远程 LLM 编排设计（已被 ADR-005/006 取代，详见 `docs/02-ARCHITECTURE/ADR/`）
> - [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md)

---

## 1. 概述

本文档定义 PoLang 远程 LLM 混合编排架构的 UI 层设计。将后端 `ExecutionPlan` 的执行流程可视化，以**内联聊天消息**的形式嵌入现有 `AiAgentPanel` 中。

**核心原则**:
- 保持现有聊天界面不变
- 计划执行流程以特殊消息类型嵌入
- 支持自动执行（无需每步确认）

---

## 2. 设计目标

| 目标 | 优先级 | 说明 |
|------|--------|------|
| 计划预览展示 | P0 | PREVIEW 模式下展示 ExecutionPlan 步骤列表 |
| 实时进度展示 | P0 | AUTO 模式下展示执行进度和步骤状态 |
| 执行结果报告 | P0 | 完成后展示执行摘要 |
| 自动执行设置 | P0 | 用户可设置默认自动执行，无需确认 |
| 暂停/跳过/取消 | P1 | 执行中提供控制按钮 |
| 步骤修改 | P2 | 允许用户在执行前调整计划 |

---

## 3. 组件架构

### 3.1 消息类型密封类

```kotlin
sealed class AgentMessage {
    data class UserText(val text: String) : AgentMessage()
    data class AgentText(val text: String) : AgentMessage()
    data class PlanPreview(val plan: ExecutionPlan) : AgentMessage()
    data class PlanProgress(val plan: ExecutionPlan, val state: ExecutionState) : AgentMessage()
    data class PlanResult(val result: ExecutionResult) : AgentMessage()
}
```

### 3.2 核心 UI 组件

```
AiAgentPanel（现有）
    └── LazyColumn（消息列表）
        ├── UserTextBubble（现有）
        ├── AgentTextBubble（现有）
        ├── PlanPreviewBubble（新增）
        ├── PlanProgressBubble（新增）
        └── PlanResultBubble（新增）
```

---

## 4. 组件详解

### 4.1 PlanPreviewBubble（计划预览）

**触发**: `InferenceResult.Plan` 且 `interactionMode == PREVIEW`

**UI 结构**:
```
┌─────────────────────────────────────────┐
│ 🤖 Agent                                │
│                                         │
│ 📋 执行计划（5步）                        │
│ ┌─────────────────────────────────────┐ │
│ │ 1. 切换人像模式                       │ │
│ │ 2. 磨皮调至 60                       │ │
│ │ 3. 拍照 1/3                         │ │
│ │ 4. 拍照 2/3                         │ │
│ │ 5. 拍照 3/3                         │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ [开始执行] [修改] [取消]                 │
└─────────────────────────────────────────┘
```

**行为**:
- 用户点击「开始执行」→ 触发 `onConfirmPlan(plan)` → 消息变为 PlanProgressBubble
- 用户点击「修改」→ 展开可编辑步骤列表（P2）
- 用户点击「取消」→ 插入 AgentText("已取消执行")

### 4.2 PlanProgressBubble（执行进度）

**触发**: `ExecutionEngine.stateFlow` 变为 `Running`

**UI 结构**:
```
┌─────────────────────────────────────────┐
│ 🤖 Agent                                │
│                                         │
│ ▓▓▓▓▓▓▓▓░░░░ 3/5 完成                   │
│                                         │
│ ✅ 切换人像模式                          │
│ ✅ 磨皮调至 60                          │
│ 🔄 拍照 2/3...                         │
│ ⏸  拍照 3/3（待执行）                   │
│                                         │
│ [暂停] [跳过当前]                       │
└─────────────────────────────────────────┘
```

**状态图标**:
| 状态 | 图标 |
|------|------|
| 已完成 | ✅ |
| 执行中 | 🔄 |
| 待执行 | ⏸ |
| 已跳过 | ⏭ |
| 失败 | ❌ |

**行为**:
- 实时监听 `ExecutionEngine.stateFlow`
- 每步完成后更新步骤状态
- 用户点击「暂停」→ `executionEngine.pause()`
- 用户点击「跳过当前」→ 跳过当前步骤（需扩展 ExecutionEngine 支持）

### 4.3 PlanResultBubble（执行结果）

**触发**: `ExecutionEngine.stateFlow` 变为 `Completed`

**UI 结构**:
```
┌─────────────────────────────────────────┐
│ 🤖 Agent                                │
│                                         │
│ ✅ 计划执行完成（5/5 成功）               │
│                                         │
│ 📊 执行摘要                              │
│ • 成功: 5 步                            │
│ • 跳过: 0 步                            │
│ • 失败: 0 步                            │
│                                         │
│ [查看详情]                              │
└─────────────────────────────────────────┘
```

**失败状态**:
```
┌─────────────────────────────────────────┐
│ 🤖 Agent                                │
│                                         │
│ ⚠️ 计划执行完成（3/5 成功）              │
│                                         │
│ ❌ 拍照 3/3: 相机被占用                 │
│                                         │
│ [重试失败步骤] [放弃]                    │
└─────────────────────────────────────────┘
```

---

## 5. 自动执行设置

### 5.1 用户设置

```kotlin
// 存储在 DataStore 中
data class AgentSettings(
    val autoExecutePlans: Boolean = true,  // 默认自动执行
    val showPlanPreview: Boolean = false,   // 是否总是展示预览
)
```

### 5.2 执行逻辑

```
收到 InferenceResult.Plan
    │
    ├─ autoExecutePlans == true ──→ 直接开始执行（插入 PlanProgressBubble）
    │
    └─ autoExecutePlans == false ──→ 展示 PlanPreviewBubble，等待用户确认
```

**设置入口**: SettingsScreen → Agent 设置 → "自动执行多步骤计划"

---

## 6. 数据流

```
用户输入
    │
    ▼
AgentOrchestrator.processUserInput()
    │
    ▼
InferenceRouter → InferenceResult.Plan(plan)
    │
    ▼
ViewModel:
  1. 判断 autoExecutePlans
  2. true → 直接启动 ExecutionEngine
  3. false → 插入 PlanPreviewBubble 到消息列表
    │
    ▼
用户点击"开始执行" / autoExecute 直接触发
    │
    ▼
ExecutionEngine.execute(plan)
    │
    ▼
PlanPreviewBubble → PlanProgressBubble（替换消息）
    │
    ▼
监听 stateFlow → 更新进度 UI
    │
    ▼
执行完成 → PlanProgressBubble → PlanResultBubble（替换消息）
```

---

## 7. ViewModel 集成

```kotlin
class AgentPanelViewModel(
    private val orchestrator: AgentOrchestrator,
    private val executionEngine: ExecutionEngine
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = _messages.asStateFlow()

    val executionState: StateFlow<ExecutionState> = executionEngine.stateFlow

    fun sendUserInput(text: String) {
        viewModelScope.launch {
            // 添加用户消息
            _messages.value += AgentMessage.UserText(text)

            // 调用编排器
            val result = orchestrator.processUserInput(text)

            when (result) {
                is InferenceResult.Local -> {
                    // 单命令，直接执行成功
                    _messages.value += AgentMessage.AgentText("已执行: ${result.command}")
                }
                is InferenceResult.Batch -> {
                    // 批量命令，自动执行
                    _messages.value += AgentMessage.AgentText("正在执行 ${result.commands.size} 个操作...")
                }
                is InferenceResult.Plan -> {
                    handlePlanResult(result.plan)
                }
                is InferenceResult.Chat -> {
                    _messages.value += AgentMessage.AgentText(result.message)
                }
            }
        }
    }

    private fun handlePlanResult(plan: ExecutionPlan) {
        val settings = loadAgentSettings()
        if (settings.autoExecutePlans && plan.interactionMode != InteractionMode.PREVIEW) {
            // 自动执行
            _messages.value += AgentMessage.PlanProgress(plan, ExecutionState.Running(plan.steps.size, 0))
            viewModelScope.launch {
                executionEngine.execute(plan)
            }
        } else {
            // 展示预览，等待用户确认
            _messages.value += AgentMessage.PlanPreview(plan)
        }
    }

    fun onConfirmPlan(plan: ExecutionPlan) {
        viewModelScope.launch {
            // 将 PlanPreview 替换为 PlanProgress
            replaceLastMessage(AgentMessage.PlanProgress(plan, ExecutionState.Running(plan.steps.size, 0)))
            executionEngine.execute(plan)
        }
    }

    fun onPauseExecution() = executionEngine.pause()
    fun onResumeExecution() = executionEngine.resume()
    fun onCancelExecution() = executionEngine.cancel()

    private fun replaceLastMessage(newMessage: AgentMessage) {
        _messages.value = _messages.value.dropLast(1) + newMessage
    }
}
```

---

## 8. 文件结构

### 新增文件

| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/picme/features/camera/agent/PlanPreviewBubble.kt` | 计划预览消息气泡 |
| `app/src/main/java/com/picme/features/camera/agent/PlanProgressBubble.kt` | 执行进度消息气泡 |
| `app/src/main/java/com/picme/features/camera/agent/PlanResultBubble.kt` | 执行结果消息气泡 |
| `app/src/main/java/com/picme/features/camera/agent/AgentMessage.kt` | AgentMessage 密封类 |

### 修改文件

| 文件 | 修改内容 |
|------|----------|
| `AiAgentPanel.kt` | 消息列表支持新消息类型，集成 ViewModel |
| `CameraAgentIntegration.kt` | 传递 ExecutionEngine 到 ViewModel |

---

## 9. 实施计划

### Phase 1: 数据模型（0.5 天）
- 创建 `AgentMessage` 密封类
- 修改 ViewModel 支持新消息类型

### Phase 2: PlanPreviewBubble（1 天）
- 实现计划预览 UI
- 添加确认/修改/取消按钮
- 测试 PREVIEW 模式流程

### Phase 3: PlanProgressBubble（1 天）
- 实现进度条和步骤状态列表
- 监听 ExecutionEngine.stateFlow
- 添加暂停/跳过按钮

### Phase 4: PlanResultBubble（0.5 天）
- 实现执行结果摘要
- 成功/失败状态展示
- 重试失败步骤按钮（P2）

### Phase 5: 自动执行设置（0.5 天）
- 添加 DataStore 设置项
- SettingsScreen 添加 Agent 设置入口
- 集成到执行逻辑

### Phase 6: 集成测试（0.5 天）
- 测试完整执行流程
- 验证状态切换正确性
- 测试自动执行设置

---

*本文档遵循 PoLang 三层文档体系，实现时需同步更新模块 AGENTS.md。*
