package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Koog 记忆三不变式单测（移植自 langchain4j `DataStoreChatMemory.trimToMaxMessages`
 * 与 `DataStoreChatMemoryStore.sanitizeMessages` 的语义，适配 Koog 1.1.1 part-based 消息模型）。
 *
 * 1.1.1 关键：工具调用是 [MessagePart.Tool.Call]（嵌在 Assistant.parts），
 * 工具结果是 [MessagePart.Tool.Result]（嵌在 User.parts），按 id 配对。
 *
 * 纯 JVM、无 Android 依赖。
 */
class KoogMessageMemoryTest {

    // ── fixtures（对齐真实 Koog 会话形状：Call 在 Assistant、Result 在 User）──

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
    private fun system(text: String) = Message.System(text, RequestMetaInfo.Empty)

    /** 一个只含单个 Tool.Call part 的 Assistant（模拟 LLM 发起工具调用）。 */
    private fun toolCall(id: String, tool: String = "t", args: String = "{}") =
        Message.Assistant(
            listOf(MessagePart.Tool.Call(id, tool, args)),
            ResponseMetaInfo.Empty,
        )

    /** 一个只含单个 Tool.Result part 的 User（模拟工具结果回灌）。 */
    private fun toolResult(id: String, tool: String = "t", content: String = "ok") =
        Message.User(
            listOf(MessagePart.Tool.Result(id, tool, content)),
            RequestMetaInfo.Empty,
        )

    // ── part 探测辅助 ──────────────────────────────────────────

    private fun contentOf(message: Message): String = message.textContent()

    private fun callIdsOf(message: Message): List<String?> =
        (message as? Message.Assistant)?.parts
            ?.filterIsInstance<MessagePart.Tool.Call>()
            ?.map { part -> part.id }
            ?: emptyList()

    private fun resultIdsOf(message: Message): List<String?> =
        (message as? Message.User)?.parts
            ?.filterIsInstance<MessagePart.Tool.Result>()
            ?.map { part -> part.id }
            ?: emptyList()

    private fun allCallIds(messages: List<Message>): List<String?> =
        messages.flatMap { message -> callIdsOf(message) }

    private fun allResultIds(messages: List<Message>): List<String?> =
        messages.flatMap { message -> resultIdsOf(message) }

    // ── 不变式 ①：SystemMessage 不落盘 ───────────────────────

    @Test
    fun `withoutSystemMessages 过滤 System 保留其余`() {
        val messages = listOf(
            system("SYS"),
            user("u1"),
            assistant("a1"),
            toolCall("c1"),
            toolResult("c1"),
        )
        val filtered = KoogMessageMemory.withoutSystemMessages(messages)
        assertEquals(4, filtered.size)
        assertFalse(filtered.any { it is Message.System })
        assertEquals("u1", contentOf(filtered.first()))
    }

    @Test
    fun `withoutSystemMessages 全 System 返回空`() {
        assertEquals(
            emptyList<Message>(),
            KoogMessageMemory.withoutSystemMessages(listOf(system("s1"), system("s2")))
        )
    }

    // ── 不变式 ③：双向配对剔除悬空 tool_call ─────────────────

    @Test
    fun `sanitizeToolPairing 配对的 Call 与 Result 都保留`() {
        val messages = listOf(
            user("u"),
            toolCall("c1"),
            toolResult("c1"),
            assistant("a"),
        )
        val sanitized = KoogMessageMemory.sanitizeToolPairing(messages)
        assertEquals(4, sanitized.size)
        assertEquals(listOf("c1"), allCallIds(sanitized))
        assertEquals(listOf("c1"), allResultIds(sanitized))
    }

    @Test
    fun `sanitizeToolPairing 剔除无 Result 的悬空 Call`() {
        val messages = listOf(
            user("u"),
            toolCall("dangling"),
            assistant("a"),
        )
        val sanitized = KoogMessageMemory.sanitizeToolPairing(messages)
        assertEquals(listOf(user("u"), assistant("a")), sanitized)
        assertTrue(allCallIds(sanitized).isEmpty())
    }

    @Test
    fun `sanitizeToolPairing 剔除无 Call 的悬空 Result`() {
        val messages = listOf(
            assistant("a"),
            toolResult("orphan"),
        )
        val sanitized = KoogMessageMemory.sanitizeToolPairing(messages)
        assertEquals(listOf(assistant("a")), sanitized)
        assertTrue(allResultIds(sanitized).isEmpty())
    }

    @Test
    fun `sanitizeToolPairing 多个 Call 仅保留有配对的`() {
        val messages = listOf(
            toolCall("paired"),
            toolCall("lone"),
            toolResult("paired"),
        )
        val sanitized = KoogMessageMemory.sanitizeToolPairing(messages)
        // "lone" 的 Call 被整条丢弃；"paired" 的 Call+Result 保留
        assertEquals(listOf("paired"), allCallIds(sanitized))
        assertEquals(listOf("paired"), allResultIds(sanitized))
        assertEquals(2, sanitized.size)
    }

    @Test
    fun `sanitizeToolPairing Assistant 同时含 Text 与悬空 Call 时保留 Text`() {
        // Assistant.parts = [Text, Call(dangling)]：剔除 Call 后保留 Text，整条不丢
        val mixed = Message.Assistant(
            listOf(
                MessagePart.Text("思考中"),
                MessagePart.Tool.Call("dangling", "t", "{}"),
            ),
            ResponseMetaInfo.Empty,
        )
        val sanitized = KoogMessageMemory.sanitizeToolPairing(listOf(mixed))
        assertEquals(1, sanitized.size)
        assertTrue(sanitized[0] is Message.Assistant)
        assertTrue(allCallIds(sanitized).isEmpty())
        assertEquals("思考中", contentOf(sanitized[0]))
    }

    @Test
    fun `sanitizeToolPairing 非 tool 消息原样保留`() {
        val messages = listOf(user("u1"), assistant("a1"), user("u2"))
        assertEquals(messages, KoogMessageMemory.sanitizeToolPairing(messages))
    }

    @Test
    fun `sanitizeToolPairing 空列表无副作用`() {
        assertEquals(emptyList<Message>(), KoogMessageMemory.sanitizeToolPairing(emptyList()))
    }

    // ── 不变式 ②：tool_call 块原子裁剪 ───────────────────────

    @Test
    fun `trimToMaxMessages 不超限时原样返回`() {
        val messages = listOf(user("u"), assistant("a"))
        assertEquals(messages, KoogMessageMemory.trimToMaxMessages(messages, maxMessages = 10))
    }

    @Test
    fun `trimToMaxMessages 无 tool 时保留最近 N 条`() {
        val messages = (1..8).map { user("u$it") }
        val trimmed = KoogMessageMemory.trimToMaxMessages(messages, maxMessages = 3)
        assertEquals(listOf(user("u6"), user("u7"), user("u8")), trimmed)
    }

    @Test
    fun `trimToMaxMessages tool 块不被拆散`() {
        // 8 条非系统消息，其中 [toolCall(c1), toolResult(c1)] 是一个 2 条的块
        val messages = listOf(
            user("u1"), assistant("a1"), user("u2"),
            toolCall("c1"), toolResult("c1"),
            assistant("a2"), user("u3"), assistant("a3"),
        )
        val trimmed = KoogMessageMemory.trimToMaxMessages(messages, maxMessages = 5)
        // 期望保留最近 5 条且 tool 块完整：[Call(c1), Result(c1), A2, U3, A3]
        assertEquals(5, trimmed.size)
        val callIdx = trimmed.indexOfFirst { callIdsOf(it).isNotEmpty() }
        val resultIdx = trimmed.indexOfFirst { resultIdsOf(it).isNotEmpty() }
        assertTrue("Call 必须保留", callIdx >= 0)
        assertTrue("Result 必须保留", resultIdx >= 0)
        assertEquals("Call 与 Result 必须相邻（同块）", callIdx + 1, resultIdx)
        assertEquals(listOf("a2", "u3", "a3"), trimmed.drop(2).map { message -> contentOf(message) })
    }

    @Test
    fun `trimToMaxMessages 超预算的大块被丢弃但更旧的小块仍可保留`() {
        // trim 的"块大小"按消息条数计（非 part 数）。构造真正的 3 条消息块：
        //   [Assistant(c1,c2), User(result c1), User(result c2)] = 3 条 > 预算 2 → 整块丢弃。
        // 最新块被丢后，更旧的 old1+old2（各 1 条）仍可填满预算 2（验证 continue 而非 break 语义）。
        val twoCallAssistant = Message.Assistant(
            listOf(
                MessagePart.Tool.Call("c1", "t", "{}"),
                MessagePart.Tool.Call("c2", "t", "{}"),
            ),
            ResponseMetaInfo.Empty,
        )
        val messages = listOf(
            user("old1"),
            user("old2"),
            twoCallAssistant,
            toolResult("c1"),
            toolResult("c2"),
        )
        val trimmed = KoogMessageMemory.trimToMaxMessages(messages, maxMessages = 2)
        // 3 条 tool 块 > available 2 → 丢弃；old1+old2 填满预算 2
        assertEquals(listOf(user("old1"), user("old2")), trimmed)
        assertTrue(allCallIds(trimmed).isEmpty())
        assertTrue(allResultIds(trimmed).isEmpty())
    }

    @Test
    fun `trimToMaxMessages 单个 tool 块超出整体预算时整块丢弃`() {
        // tool 块（Call+Result，2 条）+ 后续无 result 的悬空 Call；预算 2
        val messages = listOf(
            user("old"),
            toolCall("c1"), toolResult("c1"),
            user("new1"),
            user("new2"),
        )
        val trimmed = KoogMessageMemory.trimToMaxMessages(messages, maxMessages = 2)
        // 从最新往回：new2(1), new1(2) 已满，tool 块进不来 → 保留 [new1, new2]
        assertEquals(listOf(user("new1"), user("new2")), trimmed)
        assertTrue(allCallIds(trimmed).isEmpty())
        assertTrue(allResultIds(trimmed).isEmpty())
    }

    @Test
    fun `trimToMaxMessages System 计入预算且保留在最前`() {
        val messages = listOf(
            system("SYS"),
            user("u1"), user("u2"), user("u3"), user("u4"),
        )
        val trimmed = KoogMessageMemory.trimToMaxMessages(messages, maxMessages = 2)
        // System 占 1 预算位，非系统可用预算 = 1 → 保留 SYS + 最近 1 条 user
        assertEquals(2, trimmed.size)
        assertTrue(trimmed.first() is Message.System)
        assertEquals("u4", contentOf(trimmed.last()))
    }

    @Test
    fun `trimToMaxMessages 默认 maxMessages=10`() {
        val messages = (1..12).map { user("u$it") }
        val trimmed = KoogMessageMemory.trimToMaxMessages(messages)
        assertEquals(10, trimmed.size)
        assertEquals("u3", contentOf(trimmed.first())) // 保留 u3..u12
    }
}
