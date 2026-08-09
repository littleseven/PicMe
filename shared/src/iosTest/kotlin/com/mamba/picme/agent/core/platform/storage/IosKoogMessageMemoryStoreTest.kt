package com.mamba.picme.agent.core.platform.storage

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.mamba.picme.agent.core.inference.remote.koog.KoogMessageMemory
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [IosKoogMessageMemoryStore] 的 NSUserDefaults 持久化往返测试（Phase 6.2 T1）。
 *
 * 守护点（与 Android `KoogMessageMemoryStore` 语义对齐）：
 * 1. save→load 往返不丢消息与 tool part 的 id/tool/args；
 * 2. 三不变式在 iOS 落盘链路同样成立（System 不落盘 / 原子裁剪 / 悬空配对剔除）；
 * 3. 损坏数据降级为空表（不阻断聊天）；
 * 4. clear 精确清除指定 session。
 *
 * 每个用例用独立 sessionId，NSUserDefaults 用独立 suite，互不污染。
 */
class IosKoogMessageMemoryStoreTest {

    private val defaults = NSUserDefaults(suiteName = "t1-ios-memory-test")
    private val store = IosKoogMessageMemoryStore(defaults)

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)
    private fun toolCall(id: String, tool: String = "search", args: String = """{"q":"cat"}""") =
        Message.Assistant(listOf(MessagePart.Tool.Call(id, tool, args)), ResponseMetaInfo.Empty)
    private fun toolResult(id: String, tool: String = "search", content: String = "result") =
        Message.User(listOf(MessagePart.Tool.Result(id, tool, content)), RequestMetaInfo.Empty)

    private fun callsOf(message: Message): List<MessagePart.Tool.Call> =
        (message as? Message.Assistant)?.parts?.filterIsInstance<MessagePart.Tool.Call>() ?: emptyList()

    @Test
    fun saveLoadRoundTripPreservesToolParts() = runBlocking {
        val session = "roundtrip"
        val messages = listOf(
            user("找猫"),
            toolCall("call_1", "search_media", """{"q":"cat"}"""),
            toolResult("call_1", "search_media", "[3 photos]"),
            assistant("找到 3 张"),
        )
        store.save(session, messages)
        val loaded = store.load(session)

        assertEquals(4, loaded.size)
        assertTrue(loaded[0] is Message.User)
        assertTrue(loaded[1] is Message.Assistant)
        val call = callsOf(loaded[1]).single()
        assertEquals("call_1", call.id)
        assertEquals("search_media", call.tool)
        assertEquals("""{"q":"cat"}""", call.args)
    }

    @Test
    fun systemMessagesAreNotPersisted() = runBlocking {
        val session = "no-system"
        store.save(session, listOf(Message.System("sys", RequestMetaInfo.Empty), user("u"), assistant("a")))
        val loaded = store.load(session)

        assertEquals(2, loaded.size)
        assertTrue(loaded.none { it is Message.System })
    }

    @Test
    fun danglingToolCallIsDroppedOnLoad() = runBlocking {
        val session = "dangling"
        // 不变式③在 load 路径生效：无配对的 Call 整条剔除，user 文本保留
        store.save(session, listOf(user("u"), toolCall("lone")))
        val loaded = store.load(session)

        assertEquals(1, loaded.size)
        assertTrue(loaded[0] is Message.User)
        assertTrue(loaded.flatMap { callsOf(it) }.isEmpty())
    }

    @Test
    fun overLimitHistoryIsTrimmedToMax() = runBlocking {
        val session = "trim"
        val messages = (1..12).map { user("m$it") }
        store.save(session, messages)
        val loaded = store.load(session)

        assertEquals(KoogMessageMemory.MAX_MESSAGES, loaded.size)
        // 保留最新的 N 条（裁剪从头丢）
        assertEquals("m12", loaded.last().textContent())
    }

    @Test
    fun corruptedDataDegradesToEmptyList() = runBlocking {
        defaults.setObject("not a json at all", forKey = "koog_memory_corrupted")
        val loaded = store.load("corrupted")
        assertTrue(loaded.isEmpty(), "损坏数据应降级为空表，不抛异常")
    }

    @Test
    fun clearRemovesOnlyTargetSession() = runBlocking {
        store.save("keep", listOf(user("keep-me")))
        store.save("wipe", listOf(user("wipe-me")))
        store.clear("wipe")

        assertTrue(store.load("wipe").isEmpty())
        assertEquals(1, store.load("keep").size)
    }
}
