package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteProtocol

/**
 * LLM 供应商枚举
 *
 * 按供应商维度组织远程模型配置，每个供应商有确定的 baseUrl 和协议。
 * 用户仅需选择供应商 → 选择模型 → 填写 API Key。
 */
enum class LlmProvider(val displayName: String) {
    TOKENHUB("TokenHub"),
    KIMI("Kimi"),
    DEEPSEEK("DeepSeek");

    companion object {
        fun fromModelId(modelId: String): LlmProvider {
            return when {
                modelId == "kimi-for-coding" -> KIMI
                modelId.startsWith("kimi-") || modelId.startsWith("moonshot-") -> TOKENHUB
                modelId.startsWith("deepseek-") -> TOKENHUB
                else -> TOKENHUB
            }
        }
    }
}

/**
 * 供应商元数据
 *
 * 定义每个供应商的静态配置：baseUrl、默认模型列表、协议类型。
 */
data class ProviderMeta(
    val provider: LlmProvider,
    val displayName: String,
    val baseUrl: String,
    val defaultModels: List<String>,
    val protocol: RemoteProtocol = RemoteProtocol.OPENAI
) {
    companion object {
        val ALL = listOf(
            ProviderMeta(
                provider = LlmProvider.TOKENHUB,
                displayName = "TokenHub",
                baseUrl = "https://tokenhub.tencentmaas.com/v1/",
                defaultModels = listOf("kimi-k2.6", "deepseek-v4-flash", "deepseek-v4-pro"),
                protocol = RemoteProtocol.OPENAI
            ),
            ProviderMeta(
                provider = LlmProvider.KIMI,
                displayName = "Kimi 官方",
                baseUrl = "https://api.moonshot.cn/v1/",
                defaultModels = listOf("kimi-k2.6", "kimi-k2.5", "moonshot-v1-8k"),
                protocol = RemoteProtocol.OPENAI
            ),
            ProviderMeta(
                provider = LlmProvider.DEEPSEEK,
                displayName = "DeepSeek 官方",
                baseUrl = "https://api.deepseek.com/",
                defaultModels = listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat"),
                protocol = RemoteProtocol.OPENAI
            )
        )

        fun of(provider: LlmProvider): ProviderMeta {
            return ALL.find { it.provider == provider }
                ?: ALL.first()
        }

        fun ofModel(modelId: String): ProviderMeta {
            val provider = LlmProvider.fromModelId(modelId)
            return of(provider)
        }
    }
}

/**
 * 供应商配置（用户侧）
 *
 * 用户仅需配置：供应商 + 模型 + API Key。
 * baseUrl 和 protocol 由供应商元数据自动推导。
 */
data class ProviderConfig(
    val provider: LlmProvider = LlmProvider.TOKENHUB,
    val modelId: String = "",
    val apiKey: String = ""
) {
    /**
     * 是否已配置
     */
    val isConfigured: Boolean
        get() = modelId.isNotBlank() && apiKey.isNotBlank()

    /**
     * 转换为远程模型配置（供推理引擎使用）
     */
    fun toRemoteModelConfig(): RemoteModelConfig {
        val meta = ProviderMeta.of(provider)
        return RemoteModelConfig(
            modelId = modelId,
            providerId = when (provider) {
                LlmProvider.TOKENHUB -> "tencent-tokenhub"
                LlmProvider.KIMI -> "kimi-official"
                LlmProvider.DEEPSEEK -> "deepseek-official"
            },
            protocol = meta.protocol,
            apiKey = apiKey,
            baseUrl = meta.baseUrl
        )
    }

    companion object {
        /**
         * 从旧版 RemoteModelConfig 迁移（向后兼容）
         */
        fun fromRemoteModelConfig(config: RemoteModelConfig): ProviderConfig {
            val provider = when {
                config.modelId == "kimi-for-coding" -> LlmProvider.KIMI
                config.baseUrl.contains("tokenhub") -> LlmProvider.TOKENHUB
                config.baseUrl.contains("moonshot") -> LlmProvider.KIMI
                config.baseUrl.contains("deepseek") -> LlmProvider.DEEPSEEK
                else -> LlmProvider.TOKENHUB
            }
            return ProviderConfig(
                provider = provider,
                modelId = config.modelId,
                apiKey = config.apiKey
            )
        }
    }
}

/**
 * 供应商配置集合
 */
data class ProviderConfigs(
    val configs: List<ProviderConfig> = emptyList()
) {
    fun getConfig(provider: LlmProvider): ProviderConfig? {
        return configs.find { it.provider == provider }
    }

    fun updateConfig(config: ProviderConfig): ProviderConfigs {
        val updated = configs.filter { it.provider != config.provider } + config
        return copy(configs = updated)
    }

    /**
     * 获取第一个已配置的配置，或默认配置
     */
    fun getActiveConfig(): ProviderConfig {
        return configs.firstOrNull { it.isConfigured }
            ?: ProviderConfig()
    }

    companion object {
        /**
         * 默认配置（覆盖旧版三个预设）
         */
        val DEFAULT = ProviderConfigs(
            configs = listOf(
                ProviderConfig(
                    provider = LlmProvider.TOKENHUB,
                    modelId = "deepseek-v4-flash",
                    apiKey = ""
                ),
                ProviderConfig(
                    provider = LlmProvider.TOKENHUB,
                    modelId = "kimi-k2.6",
                    apiKey = ""
                )
            )
        )

        fun fromJson(json: String): ProviderConfigs {
            return try {
                val configs = mutableListOf<ProviderConfig>()
                val regex = "\\{([^}]*)\\}".toRegex()
                regex.findAll(json).forEach { match ->
                    val obj = match.groupValues[1]
                    val providerStr = extractField(obj, "provider") ?: return@forEach
                    val provider = runCatching { LlmProvider.valueOf(providerStr) }.getOrNull()
                        ?: return@forEach
                    val modelId = extractField(obj, "modelId") ?: ""
                    val apiKey = extractField(obj, "apiKey") ?: ""
                    configs.add(ProviderConfig(provider, modelId, apiKey))
                }
                if (configs.isEmpty()) DEFAULT else ProviderConfigs(configs)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        fun toJson(configs: ProviderConfigs): String {
            val sb = StringBuilder("[")
            configs.configs.forEachIndexed { index, config ->
                if (index > 0) sb.append(",")
                sb.append("{\"provider\":\"${config.provider.name}\",\"modelId\":\"${config.modelId}\",\"apiKey\":\"${config.apiKey}\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        private fun extractField(obj: String, field: String): String? {
            val regex = "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            return regex.find(obj)?.groupValues?.get(1)
        }
    }
}
