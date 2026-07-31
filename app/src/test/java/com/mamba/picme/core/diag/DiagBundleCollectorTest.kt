package com.mamba.picme.core.diag

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.LogModule
import com.mamba.picme.domain.model.LogModuleConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagBundleCollectorTest {

    @Before
    fun setUp() {
        Logger.clear()
        // 测试中启用全部模块，确保 Camera 等默认关闭的标签也能记录进 Logger.logs
        Logger.setModuleConfig(LogModuleConfig(enabledModules = LogModule.entries.toSet()))
    }

    @After
    fun tearDown() {
        Logger.setModuleConfig(LogModuleConfig.default())
    }

    @Test
    fun `collect assembles logs version and device info`() {
        Logger.i("Camera", "Preview started")
        Logger.e("Gallery", "boom")

        val bundle = DiagBundleCollector.collect(
            appVersion = "1.0.29",
            gitSha = "abc1234",
            deviceModel = "Pixel 8",
            androidVersion = "14",
        )

        assertEquals("1.0.29", bundle.appVersion)
        assertEquals("abc1234", bundle.gitSha)
        assertEquals("Pixel 8", bundle.deviceModel)
        assertEquals("14", bundle.androidVersion)
        assertTrue("logs contain both entries", bundle.logs.contains("Preview started") && bundle.logs.contains("boom"))
        assertTrue("logs carry PoLang tag", bundle.logs.contains("PoLang:"))
        assertNull(bundle.crashTrace)
    }

    @Test
    fun `collect sanitizes sensitive paths in logs`() {
        Logger.i("Storage", "saved /storage/emulated/0/DCIM/IMG.jpg")

        val bundle = DiagBundleCollector.collect("1.0.29", "abc1234", "Pixel 8", "14")

        assertTrue("media path redacted: ${bundle.logs}", !bundle.logs.contains("/storage/"))
        assertTrue(bundle.logs.contains("<path>"))
    }

    @Test
    fun `collect sanitizes provided crash trace`() {
        val bundle = DiagBundleCollector.collect(
            "1.0.29", "abc1234", "Pixel 8", "14",
            crashTrace = "at com.mamba.UserHandler for a@b.com",
        )
        assertTrue(bundle.crashTrace!!.contains("<email>"))
    }
}
