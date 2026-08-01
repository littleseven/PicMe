package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ClaudeSidStore] 契约单测（Task 8）：内存 fake 验证 save/load/clear 语义与按 sessionId 隔离。
 * [PrefsClaudeSidStore] 的 SharedPreferences 实现属 Android 层，不在 JVM 单测范围。
 */
class ClaudeSidStoreTest {

    /** 内存 fake：用 Map 模拟持久化介质，验证接口契约。 */
    private class InMemoryClaudeSidStore : ClaudeSidStore {
        private val data = mutableMapOf<String, String>()

        override fun load(sessionId: String): String? = data[sessionId]

        override fun save(sessionId: String, sid: String) {
            data[sessionId] = sid
        }

        override fun clear(sessionId: String) {
            data.remove(sessionId)
        }
    }

    private val store: ClaudeSidStore = InMemoryClaudeSidStore()

    @Test
    fun `load returns null when nothing saved`() {
        assertNull(store.load("session-1"))
    }

    @Test
    fun `save then load returns sid`() {
        store.save("session-1", "sid-abc")
        assertEquals("sid-abc", store.load("session-1"))
    }

    @Test
    fun `save overwrites previous sid`() {
        store.save("session-1", "sid-old")
        store.save("session-1", "sid-new")
        assertEquals("sid-new", store.load("session-1"))
    }

    @Test
    fun `clear removes saved sid`() {
        store.save("session-1", "sid-abc")
        store.clear("session-1")
        assertNull(store.load("session-1"))
    }

    @Test
    fun `clear on unknown session is no-op`() {
        store.clear("no-such-session")
        assertNull(store.load("no-such-session"))
    }

    @Test
    fun `sids are isolated per sessionId`() {
        store.save("session-1", "sid-1")
        store.save("session-2", "sid-2")

        assertEquals("sid-1", store.load("session-1"))
        assertEquals("sid-2", store.load("session-2"))

        store.clear("session-1")
        assertNull(store.load("session-1"))
        assertEquals("sid-2", store.load("session-2"))
    }
}
