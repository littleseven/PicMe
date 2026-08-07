package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Koog 记忆三不变式（纯函数，无 Android 依赖，便于 JVM 单测）。
 *
 * 移植自 langchain4j `DataStoreChatMemory.trimToMaxMessages` 与
 * `DataStoreChatMemoryStore.sanitizeMessages` 的语义，适配 Koog 1.1.1 的 **part-based** 消息模型：
 * - 工具调用：[MessagePart.Tool.Call] 作为 part 嵌在 [Message.Assistant.parts]（ResponsePart 列表）。
 * - 工具结果：[MessagePart.Tool.Result] 作为 part 嵌在 [Message.User.parts]（RequestPart 列表）。
 * - 配对键：Call.id ↔ Result.id（双向）。
 *
 * 三不变式（与旧 langchain4j 链路逐条对齐，避免远端 OpenAI 400 "insufficient tool messages
 * following tool_calls message" / "tool_calls without tool results"）：
 * ① SystemMessage 不落盘（[withoutSystemMessages]）。
 * ② tool_call 块原子裁剪（[trimToMaxMessages]）：含 Call 的 Assistant + 紧随其后的所有含 Result
 *    的 User 视为一个不可拆块；裁剪时整块保留/丢弃，绝不拆散（否则半块 Call 无 Result 致 400）。
 * ③ 双向配对剔除悬空（[sanitizeToolPairing]）：无对应 Result 的 Call、无对应 Call 的 Result
 *    一律剔除（重建父消息的 parts 列表，parts 全空则整条丢弃）。
 *
 * 注：1.1.1 **没有**顶层 `Message.Tool` 类型（那是 master 分支，勿参照）；工具信息全部以 part
 * 形式嵌在 Assistant/User 消息内。持久化胶水见 `KoogMessageMemoryStore`。
 */
public object KoogMessageMemory {

    /** 最大历史消息数（与旧 `RemoteReActAgent.maxMemoryMessages = 10` 对齐）。 */
    public const val MAX_MESSAGES: Int = 10

    // ── 不变式 ①：SystemMessage 不落盘 ─────────────────────────

    /**
     * 剔除所有 [Message.System]。system prompt 每轮运行期新鲜组装，持久化会让旧版本 prompt
     * 永久滞留在老会话（见 langchain4j 期 `DataStoreChatMemory` cache 注释的实测教训）。
     */
    public fun withoutSystemMessages(messages: List<Message>): List<Message> =
        messages.filterNot { message -> message is Message.System }

    // ── 不变式 ③：双向配对剔除悬空 tool part ──────────────────

    /**
     * 双向配对剔除悬空 tool part。
     *
     * - 收集所有 Call.id（来自各 Assistant.parts）与 Result.id（来自各 User.parts）。
     * - [validIds] = Call.ids ∩ Result.ids（同时拥有 Call 与 Result 的 id）。
     * - 重建每个含 tool part 的消息：保留 id ∈ [validIds] 的 tool part + 所有非 tool part（Text 等）；
     *   若重建后 parts 为空（即该消息全是悬空 tool part）则整条丢弃。
     * - 无任何 tool part 时原样返回同一列表。
     */
    public fun sanitizeToolPairing(messages: List<Message>): List<Message> {
        // Call/Result 的 id 在 1.1.1 声明为 String?（极端边界可为 null，正常由 LLM 给出 call_xxx）。
        // null id 互通参与交集运算（与 langchain4j 期"null 视作可配对哨兵"语义一致）。
        val callIds = mutableSetOf<String?>()
        val resultIds = mutableSetOf<String?>()
        for (message in messages) {
            when (message) {
                is Message.Assistant -> message.parts.filterIsInstance<MessagePart.Tool.Call>()
                    .forEach { part -> callIds.add(part.id) }
                is Message.User -> message.parts.filterIsInstance<MessagePart.Tool.Result>()
                    .forEach { part -> resultIds.add(part.id) }
                else -> { /* System 等无 tool part */ }
            }
        }
        if (callIds.isEmpty() && resultIds.isEmpty()) return messages

        val validIds = callIds.intersect(resultIds)
        return messages.mapNotNull { message -> sanitizeMessage(message, validIds) }
    }

    private fun sanitizeMessage(message: Message, validIds: Set<String?>): Message? =
        when (message) {
            is Message.Assistant -> {
                if (!message.hasToolCalls()) {
                    message
                } else {
                    val kept = message.parts.filter { part ->
                        part !is MessagePart.Tool.Call || part.id in validIds
                    }
                    if (kept.isEmpty()) null else message.copy(parts = kept)
                }
            }
            is Message.User -> {
                if (!message.hasToolResults()) {
                    message
                } else {
                    val kept = message.parts.filter { part ->
                        part !is MessagePart.Tool.Result || part.id in validIds
                    }
                    if (kept.isEmpty()) null else message.copy(parts = kept)
                }
            }
            else -> message
        }

    // ── 不变式 ②：tool_call 块原子裁剪 ─────────────────────────

    /**
     * 将消息列表裁剪到最多 [maxMessages] 条（**System 计入预算**，与 langchain4j 原版一致）。
     *
     * - 总数（含 System）≤ [maxMessages] 时原样返回。
     * - System 计 1 个预算位、始终保留在结果最前；非 System 可用预算 = [maxMessages] - systemSize。
     * - 非 System 消息按"tool 块"分组：一个含 Call 的 Assistant + 紧随其后所有含 Result 的 User
     *   合为一个不可拆块；其余消息各自成块。
     * - 从最新块向前累加，整块放入预算；单块就超可用预算的块**丢弃整块并继续看更旧的块**
     *  （`continue` 语义，与原版一致），保证 tool_calls/Result 永不成对被拆散。
     */
    public fun trimToMaxMessages(
        messages: List<Message>,
        maxMessages: Int = MAX_MESSAGES,
    ): List<Message> {
        if (messages.size <= maxMessages) return messages.toList()

        val systems = messages.filterIsInstance<Message.System>()
        val nonSystem = messages.filterNot { message -> message is Message.System }

        val blocks = groupIntoBlocks(nonSystem)
        val available = maxMessages - if (systems.isNotEmpty()) 1 else 0

        val keptBlocks = mutableListOf<List<Message>>()
        var keptCount = 0
        for (block in blocks.asReversed()) {
            if (block.size > available) continue // 单块就超可用预算，丢弃整块，继续看更旧的块
            if (keptCount + block.size <= available) {
                keptBlocks.add(0, block)
                keptCount += block.size
            } else {
                break // 剩余预算装不下，停
            }
        }
        return systems + keptBlocks.flatten()
    }

    /** 把非系统消息切成原子块（含 Call 的 Assistant + 紧随其后的所有含 Result 的 User 合并）。 */
    private fun groupIntoBlocks(nonSystem: List<Message>): List<List<Message>> {
        val blocks = mutableListOf<MutableList<Message>>()
        var index = 0
        while (index < nonSystem.size) {
            val current = nonSystem[index]
            val block = mutableListOf(current)
            index++
            if (current is Message.Assistant && current.hasToolCalls()) {
                while (index < nonSystem.size) {
                    val next = nonSystem[index]
                    if (next is Message.User && next.hasToolResults()) {
                        block.add(next)
                        index++
                    } else {
                        break
                    }
                }
            }
            blocks.add(block)
        }
        return blocks
    }

    // ── part 探测辅助 ──────────────────────────────────────────

    private fun Message.Assistant.hasToolCalls(): Boolean =
        parts.any { part -> part is MessagePart.Tool.Call }

    private fun Message.User.hasToolResults(): Boolean =
        parts.any { part -> part is MessagePart.Tool.Result }
}
