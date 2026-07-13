package com.mamba.picme.server.llm

/**
 * 内存 holder：持有当前生效渠道，热路径零 DB（volatile 读取）。
 * 启动时与后台每次渠道变更后调 [reload]。
 */
object ChannelRegistry {
    @Volatile
    private var active: ChannelConfig? = null

    fun active(): ChannelConfig? = active

    suspend fun reload() {
        active = ChannelRepository.loadActive()
    }

    /** 测试专用：直接注入活跃渠道，绕过 DB。 */
    internal fun setActiveForTesting(config: ChannelConfig?) {
        active = config
    }
}
