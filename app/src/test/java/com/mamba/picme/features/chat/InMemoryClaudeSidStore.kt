package com.mamba.picme.features.chat

/**
 * 内存 fake：单槽 [ClaudeSidStore]，模拟持久化介质。
 * save/load/clear 语义——单槽读写、覆盖、清除。
 */
class InMemoryClaudeSidStore : ClaudeSidStore {
    private var slot: Pair<String, String>? = null

    override fun load(): Pair<String, String>? = slot

    override fun save(chatSessionId: String, claudeSid: String) {
        slot = chatSessionId to claudeSid
    }

    override fun clear() {
        slot = null
    }
}
