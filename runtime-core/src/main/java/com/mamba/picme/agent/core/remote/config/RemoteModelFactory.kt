package com.mamba.picme.agent.core.remote.config

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.params.additionalPropertiesOf
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecorder
import kotlinx.serialization.json.Json

/**
 * 远程模型工厂
 *
 * 统一管理远程推理参数（temperature、maxTokens 等）的创建和约束。
 * 所有远程推理路径共用此工厂，确保参数一致性，避免分散维护。
 *
 * ### 模型参数约束
 * Kimi K2.6 仅支持 temperature=1，其他值会导致 API 返回 400001 错误。
 * 此类约束在此集中管理，新增模型兼容逻辑只需修改此文件。
 */
object RemoteModelFactory {

    /**
     * 远程 LLM 调用记录接收端。由 :app 在 Application 启动时注入（全构建注入）。
     * 为 null 时不录制。
     */
    @Volatile
    var recorder: LlmCallRecorder? = null

    /**
     * 是否记录消息全文（request messages / response text 等）。
     * DEBUG 构建置 true 记录全文；release 构建置 false 只落纯指标，
     * **绝不落消息内容**（隐私红线）。由 :app 注入 recorder 时一并设置。
     */
    @Volatile
    var captureContent: Boolean = true

    /** 默认来源标签（调用方未显式指定 sourceLabel 时使用）。 */
    const val DEFAULT_SOURCE = "remote"

    /**
     * 根据模型 ID 获取合法 temperature 值。
     *
     * 某些模型对 temperature 有特殊约束（如 Kimi K2.6 仅接受 1.0），
     * 此方法将请求值钳制为模型支持的合法值。
     *
     * @param modelId 模型 ID（如 "kimi-k2.6"）
     * @param requested 请求的 temperature 值，null 时使用默认值 0.7
     * @return 钳制后的合法 temperature 值
     */
    fun clampTemperature(modelId: String, requested: Double? = null): Double {
        return if (modelId.contains("kimi-k2.6", ignoreCase = true)) 1.0 else (requested ?: 0.7)
    }

    // ── Koog（:agent-core → Koog 迁移，Phase 3 additive；Phase 5 起为唯一执行路径）─────────────

    /**
     * Koog 执行器组装产物：Phase 4 chat 链路用它构建 [ai.koog.agents.core.agent.AIAgent]。
     *
     * - [executor]：单模型 PromptExecutor（OpenAI 兼容客户端，接自定义 baseUrl）。
     * - [model]：LLM 标识（provider=OpenAI 兼容，id=模型名），供 agent/executor 路由。
     * - [baseParams]：基础推理参数（temperature 钳制 + DeepSeek thinking 禁用）。
     */
    public data class KoogExecutorBundle(
        public val executor: PromptExecutor,
        public val model: LLModel,
        public val baseParams: LLMParams,
    )

    /**
     * 创建 Koog 执行器包（与 [createBuilder] 并行存在，旧 langchain4j 路径不受影响）。
     *
     * - 自定义 baseUrl：[OpenAIClientSettings] 仅传 baseUrl，其余默认（DeepSeek/Kimi/网关通用）。
     * - 网关鉴权 header：[extraHeaders]（如 `X-App-Token` / `X-Device-Id`）非空时，用
     *   [HeaderInjectingHttpClientFactory] 包一层默认 Ktor 工厂——auth 仍由 apiKey 经
     *   `OpenAILLMClient(apiKey, settings, factory)` 标准路径注入（factory.create 的
     *   `authHeaderValue` 由 client 从 apiKey 派生，装饰器原样透传），extraHeaders 合并进
     *   factory.create 的 headers map。空 map 时直接用默认工厂，零额外开销。
     * - DeepSeek `thinking.type=disabled`：经 `additionalProperties` 由 Koog 的
     *   `AdditionalPropertiesFlatteningSerializer` 平铺到请求体顶层（Phase 0 已源码级证实 +
     *   配方测试）。
     * - `clampTemperature`：kimi-k2.6 钳到 1.0，其余 0.7（与旧链路一致）。
     */
    public fun createKoogExecutor(
        config: RemoteModelConfig,
        extraHeaders: Map<String, String> = emptyMap(),
    ): KoogExecutorBundle {
        val effectiveApiKey = config.apiKey.ifEmpty { "gateway-auth" }
        // openAIClient(apiKey, settings) 顶层工厂函数（@file:JvmName facade "OpenAIClientFactory"）
        // 仅在 JVM 变体的 kotlin_module 注册，Android 变体 Kotlin 解析不到该函数/facade 类
        // （同文件的普通类 OpenAILLMClient/OpenAIClientSettings 可解析）。Android 直接构造：
        //
        // ⚠️ 不能用 HttpClientFactoryResolver.resolve()——它经 java.util.ServiceLoader 找
        // KoogHttpClient.Factory provider，而 Koog 1.1.1 的 http-client-ktor-android 变体**未发布**
        // META-INF/services/ai.koog.http.client.KoogHttpClient$Factory（KMP android 发布缺陷），
        // Android runtime 下 ServiceLoader 永远空 → "No KoogHttpClient.Factory provider found"
        //（真机实测 2026-08-07 复现；Ktor 自身的 HttpClientEngineContainer provider 正常在 APK 内）。
        // 显式构造 KtorKoogHttpClient.Factory() 绕过（无参构造内部 new 默认 Ktor HttpClient，已配
        // DefaultRequest/ContentNegotiation/HttpTimeout）。显式构造也更利于 R8：无需为 ServiceLoader
        // provider 加 keep。有网关 header 时包一层 HeaderInjectingHttpClientFactory。
        val baseFactory = KtorKoogHttpClient.Factory()
        val factory = if (extraHeaders.isEmpty()) baseFactory else HeaderInjectingHttpClientFactory(baseFactory, extraHeaders)
        val client = OpenAILLMClient(
            effectiveApiKey,
            OpenAIClientSettings(baseUrl = config.baseUrl),
            factory,
        )
        val executor: PromptExecutor = MultiLLMPromptExecutor(client)
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = config.modelId,
            // 声明能力，否则 Koog 1.1.1 的 capability gate 链会逐个抛错（真机逐个撞出）：
            // 1. OpenAILLMClient.determineParams：capabilities 为空走兜底抛
            //    "Cannot determine proper LLM params"（需 OpenAIEndpoint.Completions）。
            // 2. AbstractOpenAILLMClient.executeStreaming / getResponse 开头
            //    requireCapability(Completion) → "does not support completion"。
            // 3. getResponse 内 tools 非空时 requireCapability(Tools) →
            //    "does not support tools"；消息转换序列化 tool 历史也查 Tools。
            // 🔴 不加 Responses（切 Responses API）、Thinking（与 thinking.type=disabled
            //    冲突）；无需 MultipleChoices/Vision/Audio/Document（chat 只文本+工具）。
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Tools,
                LLMCapability.OpenAIEndpoint.Completions,
            ),
            maxOutputTokens = MAX_TOKENS.toLong(),
        )
        val params = LLMParams(
            temperature = clampTemperature(config.modelId),
            maxTokens = MAX_TOKENS,
            additionalProperties = additionalPropertiesOf(
                "thinking" to mapOf("type" to "disabled")
            ),
        )
        return KoogExecutorBundle(executor, model, params)
    }

    // object 内可直接声明 const val（companion 仅在 class 内需要；standalone object 内
    // 嵌套 companion 非法——曾踩此编译错）。createKoogExecutor 引用此常量。
    private const val MAX_TOKENS: Int = 4096
}

/**
 * 给 Koog HttpClient 工厂注入额外请求 header（picme-server 网关鉴权 `X-App-Token` / `X-Device-Id`）。
 *
 * `OpenAIClientSettings` 无 header 参数；`OpenAILLMClient(apiKey, settings, factory)` 的 apiKey 只
 * 派生 `Authorization`。网关要求的自定义 header 经此装饰器合并进 `KoogHttpClient.Factory.create` 的
 * `headers` 形参（位置参数透传，authHeaderValue 等 7 个其余参数原样转交委托工厂——auth 仍走 apiKey
 * 标准路径，不在此重写）。
 *
 * `headers + extraHeaders`：委托工厂（默认 Ktor）传入的 headers 全保留，extraHeaders 同名键覆盖
 * （本场景 extraHeaders 仅含网关鉴权键，不与默认 headers 冲突）。
 */
private class HeaderInjectingHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val extraHeaders: Map<String, String>,
) : KoogHttpClient.Factory {
    override fun create(
        baseURL: String,
        authHeaderValue: String,
        headers: Map<String, String>,
        queryParams: Map<String, String>,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
        requestTimeoutMs: Long,
        json: Json,
    ): KoogHttpClient = delegate.create(
        baseURL,
        authHeaderValue,
        headers + extraHeaders,
        queryParams,
        connectTimeoutMs,
        socketTimeoutMs,
        requestTimeoutMs,
        json,
    )
}
