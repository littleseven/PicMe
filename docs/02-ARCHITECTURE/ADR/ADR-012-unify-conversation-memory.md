# ADR-012: 解决双套对话记忆系统

**状态**: 规划中（波次1，随 ADR-010 隔离一并落地）
**日期**: 2026-07-28
**决策**: 用户
**依赖**: ADR-010；review §0.3-D5、P1-3

---

## 1. 背景

当前存在两套互不同步的对话记忆：

- `MemoryManager`（DataStore Preferences JSON，本地路径用，`buildContextMessages`）。
- `DataStoreChatMemory`（langchain4j ChatMemory，远程 ReAct 用，`RemoteReActAgent`）。

问题（review P1-3）：

- `streamChat` 把本轮 `[user, 摘要 reply]` 回写 `MemoryManager`，但 chat ReAct 只读 `DataStoreChatMemory` → **该回写对 chat 是死写**（无消费者），且原注释声称"ReAct 经 buildContextMessages 读历史"与实现不符。
- `MemoryManager.appendConversation` 是 load→改→save 非原子，配合 fire-and-forget `backgroundScope.launch` → 并发追加**丢更新**。
- `RemoteReActAgent.resetSession()` 只清 DataStore、不清内存 `cache` → 重置后仍返回旧历史。

## 2. 决策

**每条隔离链路有且仅有一套对话记忆；消除死写与割裂。** 边界：对话记忆 ≠ 事实记忆 ≠ 人物关系（三者职责写清，避免再次混淆）。

## 3. 实现要点（波次1）

- **chat 记忆单一来源**：统一用 `DataStoreChatMemory`（langchain4j ChatMemory）作为 chat 唯一对话记忆；**删除 `streamChat` 对 `MemoryManager.appendConversation` 的死写回**（`AgentOrchestrator.kt:463-468`）。
- **`MemoryManager` 限定相机**：成为相机本地路径专用（与 ADR-009 一致）；或抽象统一 `ConversationMemory` 接口、两实现各服务一条隔离链路。
- **修数据正确性 bug**：
  - `MemoryManager.appendConversation` 改为 DataStore `edit{}` 内原子 read-modify-write（解决并发丢更新）。
  - `RemoteReActAgent.resetSession` 同步清内存 `cache`（`RemoteReActAgent.kt:313-317,413`）。
- **划清边界**：`MemoryContextProvider`（事实/人物关系快照）仅供 system prompt 被动注入，不是对话记忆；三者职责在 ADR 中固化。

## 4. 后果

- ✅ 消除死写与"两套记忆不一致"的认知负担；多轮对话上下文来源唯一、可预测。
- ✅ 修复两个数据正确性 bug（并发丢更新、重置残留）。
- ✅ 随 ADR-010 隔离自然落地（远程/本地各持自己的记忆实现）。
- ⚠️ 若选择统一 `ConversationMemory` 接口，需小幅重构两实现的共性。

## 5. 状态

| 项 | 状态 |
|---|---|
| 决策与 ADR | ✅ 2026-07-28 |
| 删 chat 对 MemoryManager 死写回 | ⏳ 波次1 |
| appendConversation 原子化 / resetSession 清 cache | ⏳ 波次1 |
| 记忆边界文档化 | ⏳ 波次1 |

## 6. 相关

- ADR-010（链路隔离，本 ADR 的落地载体）、ADR-009
- review §0.3-D5、P1-3
