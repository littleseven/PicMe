package com.mamba.picme.agent.core.platform.thread

import kotlin.test.Test
import kotlin.test.assertNotSame

/**
 * JVM/Android 实现特性测试：四个 dispatcher 各自独立线程池，互不相同。
 *
 * 该断言对 iOS actual（全部 `Dispatchers.Default` 单例）不成立，故只放 jvmTest，
 * 不放 commonTest（commonTest 会为所有 target 生成测试）。
 */
class DispatcherProviderDistinctnessTest {
    @Test
    fun providesFourDistinctDispatchers() {
        val provider = DispatcherProvider()
        assertNotSame(provider.dataStoreDispatcher, provider.modelDispatcher)
        assertNotSame(provider.networkDispatcher, provider.orchestratorDispatcher)
        assertNotSame(provider.dataStoreDispatcher, provider.networkDispatcher)
        assertNotSame(provider.modelDispatcher, provider.orchestratorDispatcher)
        provider.shutdown()
    }
}
