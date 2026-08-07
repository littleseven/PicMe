package com.mamba.picme.agent.core.platform.storage

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.mamba.picme.agent.core.inference.remote.koog.KoogMessageMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Koog 记忆层编解码往返测试（[encodeKoogMessages] / [decodeKoogMessages]）。
 *
 * 守护：
 * 1. Koog `Message`（`@Serializable` 密封接口，1.1.1 part-based）经 kotlinx-JSON 多态编解码能完整往返，
 *    不丢 Call/Result 的 id/tool/args/output 等字段——这是"双向配对"不变式在落盘后仍成立的前提。
 * 2. 往返后再跑 [KoogMessageMemory.sanitizeToolPairing] 行为不变。
 * 3. 解析非法 JSON 抛异常（store 层据此兜底为空表）。
 *
 * 纯 JVM、无 Android/DataStore 依赖（编解码是顶层纯函数）。
 */
class KoogMessageMemoryCodecTest {

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
    private fun toolCall(id: String, tool: String = "search", args: String = """{"q":"cat"}""") =
        Message.Assistant(
            listOf(MessagePart.Tool.Call(id, tool, args)),
            ResponseMetaInfo.Empty,
        )
    private fun toolResult(id: String, tool: String = "search", content: String = "result") =
        Message.User(
            listOf(MessagePart.Tool.Result(id, tool, content)),
            RequestMetaInfo.Empty,
        )

    /** 从一条消息里取首个 Tool.Call part（若存在）。 */
    private fun firstCall(message: Message): MessagePart.Tool.Call? =
        (message as? Message.Assistant)?.parts?.filterIsInstance<MessagePart.Tool.Call>()?.firstOrNull()

    /** 从一条消息里取首个 Tool.Result part（若存在）。 */
    private fun firstResult(message: Message): MessagePart.Tool.Result? =
        (message as? Message.User)?.parts?.filterIsInstance<MessagePart.Tool.Result>()?.firstOrNull()

    @Test
    fun `空列表往返`() {
        assertEquals(emptyList<Message>(), decodeKoogMessages(encodeKoogMessages(emptyList())))
    }

    @Test
    fun `混合消息列表往返保留类型与字段`() {
        val messages = listOf(
            user("找猫"),
            toolCall("call_1", "search_images", """{"q":"cat"}"""),
            toolResult("call_1", "search_images", "[3 photos]"),
            assistant("找到 3 张"),
        )
        val roundTripped = decodeKoogMessages(encodeKoogMessages(messages))

        assertEquals(messages.size, roundTripped.size)
        // 外层消息类型保持
        assertTrue(roundTripped[0] is Message.User)
        assertTrue(roundTripped[1] is Message.Assistant)
        assertTrue(roundTripped[2] is Message.User)
        assertTrue(roundTripped[3] is Message.Assistant)
        // Call 的 id/tool/args 不丢（配对依赖 id）
        val call = firstCall(roundTripped[1])
        assertNotNull(call)
        assertEquals("call_1", call!!.id)
        assertEquals("search_images", call.tool)
        assertEquals("""{"q":"cat"}""", call.args)
        // Result 的 id/tool/output 不丢
        val result = firstResult(roundTripped[2])
        assertNotNull(result)
        assertEquals("call_1", result!!.id)
        assertEquals("search_images", result.tool)
        assertEquals("[3 photos]", result.output)
    }

    @Test
    fun `往返后 sanitizeToolPairing 行为不变`() {
        val messages = listOf(
            user("u"),
            toolCall("paired"),
            toolCall("lone"), // 悬空：无对应 Result
            toolResult("paired"),
        )
        val encoded = encodeKoogMessages(messages)
        val decoded = decodeKoogMessages(encoded)
        val sanitized = KoogMessageMemory.sanitizeToolPairing(decoded)

        val callIds = sanitized.flatMap { message ->
            (message as? Message.Assistant)?.parts
                ?.filterIsInstance<MessagePart.Tool.Call>()
                ?.map { part -> part.id }
                ?: emptyList()
        }
        val resultIds = sanitized.flatMap { message ->
            (message as? Message.User)?.parts
                ?.filterIsInstance<MessagePart.Tool.Result>()
                ?.map { part -> part.id }
                ?: emptyList()
        }
        assertEquals(listOf("paired"), callIds)
        assertEquals(listOf("paired"), resultIds)
        // user 消息不受影响
        assertTrue(sanitized.any { it is Message.User })
    }

    @Test
    fun `JSON 含多态判别字段保证跨版本可识别`() {
        val raw = encodeKoogMessages(listOf(toolCall("c1"), toolResult("c1")))
        // kotlinx 密封接口序列化会写入 "type" 判别键
        assertTrue("应含多态判别键", raw.contains("\"type\""))
        assertFalse("不应是空数组", raw == "[]")
    }

    @Test(expected = Exception::class)
    fun `decode 非法 JSON 抛异常（store 层兜底为空表）`() {
        decodeKoogMessages("not a json")
    }

    @Test
    fun `ignoreUnknownKeys - 多一个未知字段仍可解析`() {
        // 模拟 Koog 升级新增字段后，旧客户端解析新历史不崩。
        // 不依赖具体字段名：在数组首个对象开括号后注入未知键。
        val raw = encodeKoogMessages(listOf(user("u"))).replaceFirst("[{", "[{\"futureField\":42,")
        assertTrue("注入点应存在（数组+对象开头）", raw.contains("futureField"))
        val decoded = decodeKoogMessages(raw)
        assertEquals(1, decoded.size)
        assertEquals("u", decoded[0].textContent())
    }

    private fun assertNotNull(value: Any?) {
        assertTrue("不应为 null", value != null)
    }
}
