package com.mamba.picme.agent.core.remote.config

import com.mamba.android.MambaAgentFactory
import com.mamba.picme.agent.core.inference.remote.log.CapturingChatModelListener
import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecorder
import java.time.Duration

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
     * 远程 LLM 调用记录接收端。由 :app 在 Application 启动时注入（仅 DEBUG 构建）。
     * 为 null 时不录制（release 构建保持 null → 生产零痕迹）。
     */
    @Volatile
    var recorder: LlmCallRecorder? = null

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

    /**
     * 创建 MambaAgentFactory Builder。
     *
     * 设置所有公共远程推理参数，调用方可链式追加额外配置：
     * ```
     * val factory = RemoteModelFactory.createBuilder(config)
     *     .customHeader("X-App-Token", token)
     *     .listeners(myListener)
     *     .build()
     * ```
     *
     * @param config 远程模型配置
     * @return MambaAgentFactory Builder，可继续追加配置后调用 [MambaAgentFactory.Builder.build]
     */
    fun createBuilder(
        config: RemoteModelConfig,
        sourceLabel: String = DEFAULT_SOURCE
    ): MambaAgentFactory.Builder {
        val effectiveApiKey = config.apiKey.ifEmpty { "gateway-auth" }
        val builder = MambaAgentFactory.builder()
            .apiKey(effectiveApiKey)
            .baseUrl(config.baseUrl)
            .model(config.modelId)
            .temperature(clampTemperature(config.modelId))
            .maxTokens(2048)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(2)
        // 注入调用记录 listener（仅当 app 侧提供了 recorder，且仅在 DEBUG 构建中注入）。
        // 与调用方后续追加的 listener 累加共存（Builder.listeners(varargs) 为累加语义）。
        val rec = recorder
        if (rec != null) {
            builder.listeners(CapturingChatModelListener(sourceLabel, rec))
        }
        return builder
    }
}
