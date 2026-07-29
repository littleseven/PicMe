package com.mamba.picme.domain.agent

import com.mamba.picme.agent.core.capability.CapabilityHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [GlobalCapabilityHost] set/clear 竞态守卫单测。
 *
 * 回归场景：Activity recreate 时旧 composition 的 onDispose 晚于新 composition 的
 * set() 执行，无条件 clear 会把新宿主覆盖成空 stub，导致 Compose 注册的 Capability
 * （chat_run_script 等）在本进程内全部不可见（chat 盘点/摘要返回"暂不支持此操作"）。
 */
class GlobalCapabilityHostTest {

    @Test
    fun `clear with stale host does not clobber the current host`() {
        val oldHost = ComposeCapabilityHost()
        val newHost = ComposeCapabilityHost()
        try {
            GlobalCapabilityHost.set(oldHost)
            GlobalCapabilityHost.set(newHost)

            // 旧宿主延迟 dispose：不得影响新宿主
            GlobalCapabilityHost.clear(oldHost)

            assertSame(newHost, GlobalCapabilityHost.get())
            assertSame(newHost, CapabilityHost.get())
        } finally {
            GlobalCapabilityHost.clear(newHost)
        }
    }

    @Test
    fun `clear with current host resets to empty stub`() {
        val host = ComposeCapabilityHost()
        GlobalCapabilityHost.set(host)

        GlobalCapabilityHost.clear(host)

        assertNull(GlobalCapabilityHost.get())
        // CapabilityHost 侧回退为空 stub（返回 null，而非残留旧宿主）
        val current = CapabilityHost.get()
        assertNotSame(host, current)
        assertEquals(null, current?.findForCommand("run_gallery_script"))
    }
}
