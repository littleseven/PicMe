package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ClaudeSidStore] 契约单测（Task 8 终审修复）：单槽语义——内存 fake 验证
 * save/load/clear 语义及 (chatSessionId, claudeSid) 成对读写。
 * [PrefsClaudeSidStore] 的 SharedPreferences 实现属 Android 层，不在 JVM 单测范围。
 */
class ClaudeSidStoreTest {

    /** 内存 fake：单槽，模拟持久化介质，验证接口契约。 */
    private class InMemoryClaudeSidStore : ClaudeSidStore {
        private var slot: Pair<String, String>? = null

        override fun load(): Pair<String, String>? = slot

        override fun save(chatSessionId: String, claudeSid: String) {
            slot = chatSessionId to claudeSid
        }

        override fun clear() {
            slot = null
        }
    }

    private val store: ClaudeSidStore = InMemoryClaudeSidStore()

    @Test
    fun `load returns null when nothing saved`() {
        assertNull(store.load())
    }

    @Test
    fun `save then load returns chatSessionId and sid pair`() {
        store.save("session-1", "sid-abc")
        assertEquals("session-1" to "sid-abc", store.load())
    }

    @Test
    fun `save overwrites previous slot`() {
        store.save("session-1", "sid-old")
        store.save("session-2", "sid-new")
        assertEquals("session-2" to "sid-new", store.load())
    }

    @Test
    fun `clear removes saved slot`() {
        store.save("session-1", "sid-abc")
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `clear on empty store is no-op`() {
        store.clear()
        assertNull(store.load())
    }
}
