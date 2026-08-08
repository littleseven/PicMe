package com.mamba.picme.agent.core.remote.config

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient

actual fun createKoogHttpClientFactory(extraHeaders: Map<String, String>): KoogHttpClient.Factory {
    val baseFactory = KtorKoogHttpClient.Factory()
    return if (extraHeaders.isEmpty()) baseFactory else HeaderInjectingHttpClientFactory(baseFactory, extraHeaders)
}
