package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * 全平台契约测试：只断言类型与 shutdown 可用。
 *
 * 「四 dispatcher 互异」不在此断言——iOS actual 全部落到 `Dispatchers.Default` 单例，
 * 互异性是 android/jvm actual 的实现特性，见 jvmTest 的 [DispatcherProviderDistinctnessTest]。
 */
class DispatcherProviderTest {
    @Test
    fun providesFourDispatchersAndShutdown() {
        val provider = DispatcherProvider()
        assertIs<CoroutineDispatcher>(provider.dataStoreDispatcher)
        assertIs<CoroutineDispatcher>(provider.modelDispatcher)
        assertIs<CoroutineDispatcher>(provider.networkDispatcher)
        assertIs<CoroutineDispatcher>(provider.orchestratorDispatcher)
        provider.shutdown()
    }
}
