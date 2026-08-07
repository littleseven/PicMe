package com.mamba.picme.data.remote.openai

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * OpenAI 兼容 API Retrofit 服务接口
 *
 * Base URL: https://tokenhub.tencentmaas.com/v1/ (或用户自定义)
 *
 * 支持 kimi-k2.6、deepseek-v4-flash 等通过 model 字段区分的模型。
 */
interface OpenAiApiService {

    /**
     * 发送聊天请求到 OpenAI 兼容 API
     *
     * @param request 包含 model、messages 等字段的请求体
     * @return 非流式响应，包含生成的文本内容
     */
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Body request: OpenAiChatRequest
    ): Response<OpenAiChatResponse>
}
