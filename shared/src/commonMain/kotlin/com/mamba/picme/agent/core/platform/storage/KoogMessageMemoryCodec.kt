package com.mamba.picme.agent.core.platform.storage

import ai.koog.prompt.message.Message
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// ── 编解码（纯函数，无 Android 依赖，便于 JVM 单测）──────────────────────────

/**
 * 用于持久化的 kotlinx Json：encodeDefaults 保证密封类型判别字段稳定写入，
 * ignoreUnknownKeys 保证 Koog 升级新增字段时不破坏旧历史解析。
 */
private val koogMemoryJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val messageListSerializer = ListSerializer(Message.serializer())

/** 把 Koog [Message] 列表编码为 JSON 字符串（持久化用）。 */
internal fun encodeKoogMessages(messages: List<Message>): String =
    koogMemoryJson.encodeToString(messageListSerializer, messages)

/** 把 JSON 字符串解码为 Koog [Message] 列表（加载用）。解析失败抛异常，由调用方兜底为空表。 */
internal fun decodeKoogMessages(raw: String): List<Message> =
    koogMemoryJson.decodeFromString(messageListSerializer, raw)
