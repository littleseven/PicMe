package com.mamba.picme.agent.core.remote.config

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteModelConfigTest {

    @Test
    fun `picme server default model is deepseek-v4-flash`() {
        // 打底远程模型须与服务端 DeepSeek 直连渠道原生模型名一致，
        // 避免端上发 deepseek-v4-flash-202605 再经服务端 default_model 兜底回落。
        assertEquals("deepseek-v4-flash", RemoteModelConfig.PICME_SERVER_DEFAULT.modelId)
    }
}
