package com.mamba.picme.server.llm

import io.ktor.client.HttpClient

/**
 * 调用上游 balance API 并缓存结果。Task 11 补全 refresh/cached；此处仅占位以便 adminRoute 签名先稳定。
 */
class ChannelBalanceService(
    val httpClient: HttpClient,
    val timeoutMs: Long = 8_000,
)
