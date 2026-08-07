package com.mamba.picme.core.identity

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DeviceIdProvider] 单测：访客试用额度的稳定设备标识来源。
 *
 * 覆盖 design spec §4.3 / client plan §5：
 * - ANDROID_ID 有效 → 直接返回
 * - ANDROID_ID 缺失/已知坏值 → 回退 DataStore 持久化的 UUID，且跨实例稳定
 */
@RunWith(RobolectricTestRunner::class)
class DeviceIdProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearAndroidId() {
        // 每个测试默认把 ANDROID_ID 置空，需要有效值的用例自行覆盖
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "")
    }

    @Test
    fun `ANDROID_ID present is returned as-is`() = runBlocking {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "abc123def456")
        val provider = DeviceIdProvider(context)
        assertEquals("abc123def456", provider.get())
    }

    @Test
    fun `blank ANDROID_ID falls back to uuid-prefixed value`() = runBlocking {
        val provider = DeviceIdProvider(context)
        val id = provider.get()
        assertTrue("回退值应以 uuid- 为前缀，实际: $id", id.startsWith("uuid-"))
    }

    @Test
    fun `known-bad ANDROID_ID falls back to uuid`() = runBlocking {
        // 9774d56d682e549c 是 Android 2.2 已知的坏 ANDROID_ID
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "9774d56d682e549c")
        val provider = DeviceIdProvider(context)
        assertTrue(provider.get().startsWith("uuid-"))
    }

    @Test
    fun `fallback id is stable across calls on same provider`() = runBlocking {
        val provider = DeviceIdProvider(context)
        val first = provider.get()
        val second = provider.get()
        assertEquals("同一 provider 多次调用应返回相同 id", first, second)
    }

    @Test
    fun `fallback id survives a new provider instance`() = runBlocking {
        // 第一实例生成并持久化 UUID，第二实例（同 DataStore）应读回同一值
        val first = DeviceIdProvider(context).get()
        val second = DeviceIdProvider(context).get()
        assertEquals(first, second)
    }
}
