package com.mamba.picme.agent.core.remote.config

/**
 * 远程 API 协议类型
 */
enum class RemoteProtocol {
    CLAUDE,
    OPENAI
}

/**
 * 远程模型供应商定义
 */
data class RemoteModelProvider(
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val protocol: RemoteProtocol,
    val models: List<String>,
    val isVisible: Boolean = true
)

/**
 * 远程模型配置数据类
 *
 * 封装单个远程模型的完整配置信息。
 */
data class RemoteModelConfig(
    val modelId: String,
    val providerId: String = "",
    val protocol: RemoteProtocol = RemoteProtocol.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val gatewayToken: String = "",
    val deviceId: String = "",
) {
    /**
     * 是否已配置
     */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && (apiKey.isNotBlank() || gatewayToken.isNotBlank())

    /**
     * 唯一标识键
     */
    val uniqueKey: String
        get() = if (providerId.isNotBlank()) providerId + ":" + modelId else modelId

    companion object {
        /**
         * PoLang Server 默认配置（LLM 代理 + 密钥托管）
         *
         * 客户端通过 X-App-Token header 认证，服务端持有 Cloudflare/TokenHub 密钥。
         * 老客户端仍可走 SCF (https://1412656811-f92agkf1y7.ap-guangzhou.tencentscf.com/)。
         */
        val PICME_SERVER_DEFAULT = RemoteModelConfig(
            modelId = "deepseek-v4-flash",
            protocol = RemoteProtocol.OPENAI,
            baseUrl = "https://api.polang.net/"
        )

        @Deprecated("Use PICME_SERVER_DEFAULT", ReplaceWith("PICME_SERVER_DEFAULT"))
        val TENCENT_SCF_DEFAULT: RemoteModelConfig get() = PICME_SERVER_DEFAULT

        /**
         * 预定义的远程模型供应商列表
         */
        val PROVIDERS = listOf(
            RemoteModelProvider(
                providerId = "tencent-tokenhub",
                displayName = "腾讯云 TokenHub",
                baseUrl = "https://tokenhub.tencentmaas.com/v1/",
                protocol = RemoteProtocol.OPENAI,
                models = listOf("deepseek-v4-flash", "kimi-k2.6","kimi-k2.7-code")
            ),
            RemoteModelProvider(
                providerId = "kimi-official",
                displayName = "Kimi 官方",
                baseUrl = "https://api.moonshot.cn/v1/",
                protocol = RemoteProtocol.OPENAI,
                models = listOf("kimi-k2.6", "kimi-k2.5", "kimi-k2.7")
            ),
            RemoteModelProvider(
                providerId = "deepseek-official",
                displayName = "DeepSeek 官方",
                baseUrl = "https://api.deepseek.com/",
                protocol = RemoteProtocol.OPENAI,
                models = listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat")
            ),
            RemoteModelProvider(
                providerId = "openai-official",
                displayName = "OpenAI 官方",
                baseUrl = "https://api.openai.com/v1/",
                protocol = RemoteProtocol.OPENAI,
                models = listOf("gpt-5", "gpt-5-mini", "gpt-4.1", "gpt-4o")
            ),
            RemoteModelProvider(
                providerId = "anthropic-official",
                displayName = "Anthropic 官方",
                // Anthropic Messages API 路径为 v1/messages（由 Koog AnthropicClientSettings
                // 默认 messagesPath 拼接），baseUrl 不带 /v1 后缀（与 OpenAI 协议不同）。
                baseUrl = "https://api.anthropic.com",
                protocol = RemoteProtocol.CLAUDE,
                models = listOf("claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1")
            )
        )

        /**
         * 所有预定义远程模型
         */
        val ALL_PREDEFINED_MODELS = PROVIDERS
            .flatMap { provider ->
                provider.models.map { modelId ->
                    RemoteModelConfig(
                        modelId = modelId,
                        providerId = provider.providerId,
                        protocol = provider.protocol,
                        baseUrl = provider.baseUrl
                    )
                }
            }

        fun getProvider(providerId: String): RemoteModelProvider? =
            PROVIDERS.find { it.providerId == providerId }

        fun getProviderForModel(modelId: String): RemoteModelProvider? =
            PROVIDERS.find { it.models.contains(modelId) }

        /**
         * 获取默认模型配置
         */
        fun defaultConfig(modelId: String): RemoteModelConfig {
            return ALL_PREDEFINED_MODELS.find { it.modelId == modelId }
                ?: RemoteModelConfig(modelId = modelId)
        }
    }
}

/**
 * 远程模型配置集合
 */
data class RemoteModelConfigs(
    val configs: List<RemoteModelConfig> = emptyList()
) {
    fun getConfig(uniqueKey: String): RemoteModelConfig? {
        return configs.find { it.uniqueKey == uniqueKey }
    }

    fun getConfigByModelId(modelId: String): RemoteModelConfig? {
        val candidates = configs.filter { it.modelId == modelId }
        return candidates.find { it.isConfigured } ?: candidates.firstOrNull()
    }

    fun updateConfig(config: RemoteModelConfig): RemoteModelConfigs {
        val updated = configs.map {
            if (it.uniqueKey == config.uniqueKey) config else it
        }
        return copy(configs = updated)
    }

    fun addConfig(config: RemoteModelConfig): RemoteModelConfigs {
        val existing = configs.find { it.uniqueKey == config.uniqueKey }
        return if (existing != null) {
            val updated = configs.map {
                if (it.uniqueKey == config.uniqueKey) config else it
            }
            copy(configs = updated)
        } else {
            copy(configs = configs + config)
        }
    }

    fun removeConfig(uniqueKey: String): RemoteModelConfigs {
        return copy(configs = configs.filter { it.uniqueKey != uniqueKey })
    }

    fun updateConfig(originalUniqueKey: String, config: RemoteModelConfig): RemoteModelConfigs {
        val updated = configs.map {
            if (it.uniqueKey == originalUniqueKey) config else it
        }
        return copy(configs = updated)
    }

    val configuredModels: List<RemoteModelConfig>
        get() = configs.filter { it.isConfigured }

    companion object {
        fun fromJson(json: String): RemoteModelConfigs {
            return try {
                val configs = mutableListOf<RemoteModelConfig>()
                val regex = "\\{([^}]*)\\}".toRegex()
                regex.findAll(json).forEach { match ->
                    val obj = match.groupValues[1]
                    val modelId = extractField(obj, "modelId") ?: return@forEach
                    val protocolStr = extractField(obj, "protocol")
                    val protocol = protocolStr?.let {
                        runCatching { RemoteProtocol.valueOf(it) }.getOrNull()
                    }
                    val apiKey = extractField(obj, "apiKey") ?: ""
                    val baseUrl = extractField(obj, "baseUrl") ?: ""
                    val gatewayToken = extractField(obj, "gatewayToken") ?: ""
                    val providerId = extractField(obj, "providerId") ?: ""
                    configs.add(
                        RemoteModelConfig(
                            modelId = modelId,
                            providerId = providerId,
                            protocol = protocol ?: RemoteProtocol.OPENAI,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            gatewayToken = gatewayToken
                        )
                    )
                }
                if (configs.isEmpty()) {
                    RemoteModelConfigs()
                } else {
                    RemoteModelConfigs(configs)
                }
            } catch (_: Exception) {
                RemoteModelConfigs()
            }
        }

        fun toJson(configs: RemoteModelConfigs): String {
            val sb = StringBuilder("[")
            configs.configs.forEachIndexed { index, config ->
                if (index > 0) sb.append(",")
                sb.append("{\"modelId\":\"${config.modelId}\",\"providerId\":\"${config.providerId}\",\"protocol\":\"${config.protocol.name}\",\"apiKey\":\"${config.apiKey}\",\"baseUrl\":\"${config.baseUrl}\",\"gatewayToken\":\"${config.gatewayToken}\"}")
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
