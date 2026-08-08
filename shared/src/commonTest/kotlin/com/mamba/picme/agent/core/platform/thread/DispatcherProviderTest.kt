package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class DispatcherProviderTest {
    @Test
    fun providesFourDistinctDispatchers() {
        val provider = DispatcherProvider()
        assertIs<CoroutineDispatcher>(provider.dataStoreDispatcher)
        assertIs<CoroutineDispatcher>(provider.modelDispatcher)
        assertIs<CoroutineDispatcher>(provider.networkDispatcher)
        assertIs<CoroutineDispatcher>(provider.orchestratorDispatcher)
        assertNotSame(provider.dataStoreDispatcher, provider.modelDispatcher)
        assertNotSame(provider.networkDispatcher, provider.orchestratorDispatcher)
        provider.shutdown()
    }
}
