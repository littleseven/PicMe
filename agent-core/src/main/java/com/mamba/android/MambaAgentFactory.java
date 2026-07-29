package com.mamba.android;

import com.mamba.model.chat.ChatModel;
import com.mamba.model.chat.StreamingChatModel;
import com.mamba.model.chat.request.ToolChoice;
import com.mamba.model.openai.OpenAiChatModel;
import com.mamba.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Mamba Agent 工厂类：简化 OpenAI 兼容模型的创建与配置。
 *
 * <p>提供 Android 友好的 Builder API，屏蔽 langchain4j 底层复杂度：</p>
 *
 * <pre>{@code
 * ChatModel model = MambaAgentFactory.builder()
 *     .apiKey("sk-xxx")
 *     .baseUrl("https://api.openai.com/v1/")
 *     .model("gpt-4o")
 *     .temperature(0.7)
 *     .build();
 * }</pre>
 *
 * <p>支持自定义 HTTP 请求头（如网关认证）：</p>
 *
 * <pre>{@code
 * ChatModel model = MambaAgentFactory.builder()
 *     .apiKey("sk-xxx")
 *     .customHeader("X-App-Token", "gateway-token")
 *     .build();
 * }</pre>
 *
 * @see OpenAiChatModel
 */
public class MambaAgentFactory {

    private MambaAgentFactory() {
        // utility class
    }

    /**
     * 创建新的 Builder 实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速创建标准 OpenAI 模型实例。
     *
     * @param apiKey API 密钥
     * @param model  模型名称（如 "gpt-4o", "deepseek-v4-flash"）
     * @return 配置好的 ChatModel
     */
    public static ChatModel openAi(String apiKey, String model) {
        return builder()
                .apiKey(apiKey)
                .model(model)
                .build();
    }

    /**
     * 快速创建自定义基址的 OpenAI 兼容模型实例。
     *
     * @param apiKey  API 密钥
     * @param baseUrl 自定义 API 基址（如 "https://api.deepseek.com/v1/"）
     * @param model   模型名称
     * @return 配置好的 ChatModel
     */
    public static ChatModel openAi(String apiKey, String baseUrl, String model) {
        return builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .build();
    }

    /**
     * Builder 配置类。
     */
    public static class Builder {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1/";
        private String model = "gpt-4o";
        private Double temperature = 0.7;
        private Double topP;
        private Integer maxTokens = 1024;
        private Integer maxCompletionTokens;
        private Duration timeout = Duration.ofSeconds(60);
        private Integer maxRetries = 2;
        private Boolean logRequests = false;
        private Boolean logResponses = false;
        private Boolean strictTools = false;
        private Boolean strictJsonSchema = false;
        private Map<String, String> customHeaders;
        private Map<String, String> customQueryParams;
        private Map<String, Object> customParameters;
        private ToolChoice toolChoice = ToolChoice.AUTO;
        private List<com.mamba.model.chat.listener.ChatModelListener> listeners;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(double topP) {
            this.topP = topP;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder maxCompletionTokens(int maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder logRequests(boolean logRequests) {
            this.logRequests = logRequests;
            return this;
        }

        public Builder logResponses(boolean logResponses) {
            this.logResponses = logResponses;
            return this;
        }

        public Builder strictTools(boolean strictTools) {
            this.strictTools = strictTools;
            return this;
        }

        public Builder strictJsonSchema(boolean strictJsonSchema) {
            this.strictJsonSchema = strictJsonSchema;
            return this;
        }

        public Builder customHeader(String key, String value) {
            if (this.customHeaders == null) {
                this.customHeaders = new java.util.HashMap<>();
            }
            this.customHeaders.put(key, value);
            return this;
        }

        public Builder customHeaders(Map<String, String> headers) {
            this.customHeaders = headers;
            return this;
        }

        public Builder customQueryParams(Map<String, String> params) {
            this.customQueryParams = params;
            return this;
        }

        public Builder customParameters(Map<String, Object> params) {
            this.customParameters = params;
            return this;
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public Builder listeners(List<com.mamba.model.chat.listener.ChatModelListener> listeners) {
            this.listeners = listeners;
            return this;
        }

        public Builder listeners(com.mamba.model.chat.listener.ChatModelListener... listeners) {
            if (this.listeners == null) {
                this.listeners = new java.util.ArrayList<>();
            }
            this.listeners.addAll(java.util.Arrays.asList(listeners));
            return this;
        }

        /**
         * 构建 OpenAiChatModel 实例（同步）。
         */
        public ChatModel build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key must not be null or empty");
            }

            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(timeout)
                    .maxRetries(maxRetries)
                    .logRequests(logRequests)
                    .logResponses(logResponses)
                    .strictTools(strictTools)
                    .strictJsonSchema(strictJsonSchema);

            if (topP != null) {
                builder.topP(topP);
            }
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
            if (customHeaders != null && !customHeaders.isEmpty()) {
                builder.customHeaders(customHeaders);
            }
            if (customQueryParams != null && !customQueryParams.isEmpty()) {
                builder.customQueryParams(customQueryParams);
            }
            if (customParameters != null && !customParameters.isEmpty()) {
                builder.customParameters(customParameters);
            }
            if (listeners != null && !listeners.isEmpty()) {
                builder.listeners(listeners);
            }

            return builder.build();
        }

        /**
         * 构建 OpenAiStreamingChatModel 实例（流式 SSE）。
         *
         * <p>与 {@link #build()} 使用相同的配置参数，
         * 但返回支持 SSE 流式响应的 {@link StreamingChatModel}。</p>
         *
         * @return 流式聊天模型实例
         * @throws IllegalArgumentException 如果 API key 为空
         */
        public StreamingChatModel buildStreaming() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key must not be null or empty");
            }

            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(timeout)
                    .logRequests(logRequests)
                    .logResponses(logResponses)
                    .strictTools(strictTools)
                    .strictJsonSchema(strictJsonSchema);

            if (topP != null) {
                builder.topP(topP);
            }
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
            if (customHeaders != null && !customHeaders.isEmpty()) {
                builder.customHeaders(customHeaders);
            }
            if (customQueryParams != null && !customQueryParams.isEmpty()) {
                builder.customQueryParams(customQueryParams);
            }
            if (customParameters != null && !customParameters.isEmpty()) {
                builder.customParameters(customParameters);
            }
            if (listeners != null && !listeners.isEmpty()) {
                builder.listeners(listeners);
            }

            return builder.build();
        }
    }
}
