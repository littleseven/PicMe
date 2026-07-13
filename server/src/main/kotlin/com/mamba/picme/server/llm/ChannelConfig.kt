package com.mamba.picme.server.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 渠道鉴权方式：决定上游请求的 auth header。 */
enum class AuthStyle { BEARER, CF_AIG }

/**
 * 渠道运行时配置（从 llm_channel 行加载到内存，热路径读取）。
 * @param modelMap 请求模型名 → 上游模型名（透明映射）。
 */
data class ChannelConfig(
    val id: Int,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val authStyle: AuthStyle,
    val apiToken: String,
    val modelMap: Map<String, String>,
)

private val mapJson = Json { ignoreUnknownKeys = true }

/** 把 DB 里的 model_map_json 解析为 Map；空/非法 → 空 map。 */
fun parseModelMap(json: String): Map<String, String> {
    if (json.isBlank()) return emptyMap()
    return try {
        mapJson.parseToJsonElement(json).jsonObject.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: ""
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

/** 把 Map 序列化为 model_map_json。 */
fun serializeModelMap(map: Map<String, String>): String {
    val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
    return mapJson.encodeToString(JsonObject.serializer(), obj)
}

/**
 * 把后台 textarea 的「每行 请求名=上游名」文本解析为 Map。
 * 忽略空行与 # 注释行；非法行抛 IllegalArgumentException（含行号）。
 */
fun parseModelMapLines(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val eq = line.indexOf('=')
        if (eq <= 0 || eq == line.length - 1) {
            throw IllegalArgumentException("第 ${i + 1} 行格式错误，应为 请求名=上游名：$raw")
        }
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        if (key.isEmpty() || value.isEmpty()) {
            throw IllegalArgumentException("第 ${i + 1} 行键或值为空：$raw")
        }
        result[key] = value
    }
    return result
}

/** 把 Map 渲染回 textarea 文本（每行 请求名=上游名）。 */
fun renderModelMapLines(map: Map<String, String>): String =
    map.entries.joinToString("\n") { "${it.key}=${it.value}" }
