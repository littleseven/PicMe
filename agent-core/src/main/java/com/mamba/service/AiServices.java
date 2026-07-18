package com.mamba.service;

import static com.mamba.internal.ValidationUtils.ensureNotNull;

import com.mamba.data.message.AiMessage;
import com.mamba.data.message.ChatMessage;
import com.mamba.data.message.SystemMessage;
import com.mamba.data.message.ToolExecutionResultMessage;
import com.mamba.data.message.UserMessage;
import com.mamba.memory.ChatMemory;
import com.mamba.model.chat.ChatModel;
import com.mamba.model.chat.request.ChatRequest;
import com.mamba.model.chat.request.DefaultChatRequestParameters;
import com.mamba.model.chat.request.ToolChoice;
import com.mamba.model.chat.response.ChatResponse;
import com.mamba.tool.ToolExecutionRequest;
import com.mamba.tool.ToolSpecification;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Android-Style AiServices：显式注入的 AI 服务代理构建器。
 *
 * <p>模仿 langchain4j 的 AiServices 模式，但完全避免 SPI/ServiceLoader，
 * 所有依赖（ChatModel、ChatMemory、Tools）均通过 Builder 显式注入。</p>
 *
 * <p>核心设计原则：</p>
 * <ul>
 *   <li><b>显式优于隐式</b>：所有依赖通过构造函数/Builder 注入，无自动发现</li>
 *   <li><b>无 SPI</b>：不使用 ServiceLoader、META-INF/services 等机制</li>
 *   <li><b>Android 兼容</b>：使用 Java 标准反射，避免 Android 不支持的 JVM 特性</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * PoLangAssistant assistant = AiServices.builder(PoLangAssistant.class)
 *     .chatModel(chatModel)
 *     .chatMemory(chatMemory)
 *     .tools(new PoLangToolService(windowManager))
 *     .systemMessageProvider(sessionId -> SystemMessage.from("你是 PoLang 助手..."))
 *     .build();
 *
 * String result = assistant.chat("打开相机并拍照");
 * }</pre>
 *
 * @param <T> 服务接口类型
 * @see AiServices.Builder
 */
public class AiServices<T> {

    private static final Logger log = LoggerFactory.getLogger(AiServices.class);

    private final Class<T> assistantClass;

    private AiServices(Class<T> assistantClass) {
        this.assistantClass = ensureNotNull(assistantClass, "assistantClass");
    }

    /**
     * 创建新的 AiServices Builder。
     *
     * @param assistantClass 服务接口类（如 PoLangAssistant.class）
     * @param <T>            接口类型
     * @return AiServices 实例（用于链式调用 builder()）
     */
    public static <T> AiServices<T> builder(Class<T> assistantClass) {
        return new AiServices<>(assistantClass);
    }

    /**
     * 获取配置好的 Builder。
     *
     * @return Builder 实例
     */
    public Builder<T> builder() {
        return new Builder<>(assistantClass);
    }

    /**
     * AiServices 构建器。
     *
     * <p>所有依赖必须显式注入，不支持自动发现。</p>
     */
    public static class Builder<T> {

        private final Class<T> assistantClass;
        private ChatModel chatModel;
        private ChatMemory chatMemory;
        private List<Object> tools = new ArrayList<>();
        private SystemMessageProvider systemMessageProvider;
        private List<ToolSpecification> toolSpecifications;
        private ToolChoice toolChoice = ToolChoice.AUTO;
        private int maxIterations = 30;

        private Builder(Class<T> assistantClass) {
            this.assistantClass = assistantClass;
        }

        /**
         * 设置 ChatModel（LLM 客户端）。
         *
         * @param chatModel 聊天模型
         * @return this
         */
        public Builder<T> chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * 设置 ChatMemory（对话历史管理）。
         *
         * @param chatMemory 聊天内存
         * @return this
         */
        public Builder<T> chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        /**
         * 添加一个工具实例。
         * <p>工具对象的方法应有 @Tool 注解。</p>
         *
         * @param tool 工具实例
         * @return this
         */
        public Builder<T> tools(Object... tool) {
            for (Object t : tool) {
                if (t != null) {
                    this.tools.add(t);
                }
            }
            return this;
        }

        /**
         * 添加多个工具实例。
         *
         * @param tools 工具实例列表
         * @return this
         */
        public Builder<T> tools(List<Object> tools) {
            if (tools != null) {
                for (Object t : tools) {
                    if (t != null) {
                        this.tools.add(t);
                    }
                }
            }
            return this;
        }

        /**
         * 设置 SystemMessage 提供者（用于每个 session 初始化）。
         *
         * @param provider SystemMessage 提供者
         * @return this
         */
        public Builder<T> systemMessageProvider(SystemMessageProvider provider) {
            this.systemMessageProvider = provider;
            return this;
        }

        /**
         * 显式设置 ToolSpecification 列表（绕过自动提取）。
         * <p>如果未设置，将从 tools 对象通过反射自动提取。</p>
         *
         * @param toolSpecifications 工具规格列表
         * @return this
         */
        public Builder<T> toolSpecifications(List<ToolSpecification> toolSpecifications) {
            this.toolSpecifications = toolSpecifications;
            return this;
        }

        /**
         * 设置工具选择策略。
         *
         * @param toolChoice 工具选择策略（AUTO/REQUIRED/NONE）
         * @return this
         */
        public Builder<T> toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        /**
         * 设置最大工具调用迭代次数。
         *
         * @param maxIterations 最大迭代次数（默认 30）
         * @return this
         */
        public Builder<T> maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * 构建 AI 服务代理。
         *
         * @return 代理实例
         * @throws IllegalArgumentException 如果缺少必需的依赖
         */
        public T build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel is required");
            }

            // 如果没有显式提供 toolSpecs，尝试从 tools 提取
            List<ToolSpecification> specs = toolSpecifications;
            if (specs == null && !tools.isEmpty()) {
                specs = extractToolSpecifications(tools);
            }

            if (specs == null) {
                specs = List.of();
            }

            @SuppressWarnings("unchecked")
            T proxy = (T) Proxy.newProxyInstance(
                    assistantClass.getClassLoader(),
                    new Class<?>[]{assistantClass},
                    new AssistantInvocationHandler(
                            chatModel,
                            chatMemory,
                            tools,
                            specs,
                            systemMessageProvider,
                            toolChoice,
                            maxIterations
                    )
            );

            return proxy;
        }

        /**
         * 从工具实例列表中提取 ToolSpecification。
         * <p>使用 Android 兼容的反射，扫描 @Tool 注解方法。</p>
         */
        private List<ToolSpecification> extractToolSpecifications(List<Object> tools) {
            List<ToolSpecification> result = new ArrayList<>();
            for (Object tool : tools) {
                // 使用 ToolSpecificationExtractor 从工具实例提取
                // 这里委托给外部 extractor，避免在 AiServices 中引入过多反射逻辑
                result.addAll(extractFromTool(tool));
            }
            return result;
        }

        /**
         * 从单个工具实例提取 ToolSpecification。
         * <p>通过反射扫描 @Tool 注解的方法。</p>
         */
        private List<ToolSpecification> extractFromTool(Object tool) {
            // 委托给 ToolSpecificationExtractor 工具类
            // 由于 mamba-agent 模块不依赖 agent-core 的 Kotlin extractor，
            // 这里提供一个基于 Java 反射的简化版本
            return com.mamba.service.internal.ToolSpecExtractor.extract(tool);
        }
    }

    /**
     * SystemMessage 提供者接口。
     */
    @FunctionalInterface
    public interface SystemMessageProvider {
        SystemMessage provide(Object memoryId);
    }

    /**
     * 代理调用处理器：实现核心的 ReAct 循环逻辑。
     *
     * <p>当用户调用接口方法（如 chat(String)）时，此处理器：
     * <ol>
     *   <li>初始化 ChatMemory（添加 SystemMessage）</li>
     *   <li>添加 UserMessage</li>
     *   <li>调用 LLM（传入 toolSpecifications）</li>
     *   <li>如果 LLM 返回 tool calls，自动执行工具并继续循环</li>
     *   <li>返回最终结果给用户</li>
     * </ol>
     */
    private static class AssistantInvocationHandler implements InvocationHandler {

        private final ChatModel chatModel;
        private final ChatMemory chatMemory;
        private final List<Object> tools;
        private final List<ToolSpecification> toolSpecifications;
        private final SystemMessageProvider systemMessageProvider;
        private final ToolChoice toolChoice;
        private final int maxIterations;

        AssistantInvocationHandler(
                ChatModel chatModel,
                ChatMemory chatMemory,
                List<Object> tools,
                List<ToolSpecification> toolSpecifications,
                SystemMessageProvider systemMessageProvider,
                ToolChoice toolChoice,
                int maxIterations) {
            this.chatModel = chatModel;
            this.chatMemory = chatMemory;
            this.tools = tools;
            this.toolSpecifications = toolSpecifications;
            this.systemMessageProvider = systemMessageProvider;
            this.toolChoice = toolChoice;
            this.maxIterations = maxIterations;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 只处理声明在接口中的方法（非 Object 方法）
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }

            String methodName = method.getName();

            // 支持 chat(String) 和 chat(String, String) 变体
            if ("chat".equals(methodName) && args != null && args.length >= 1 && args[0] instanceof String) {
                String userMessage = (String) args[0];
                return executeChat(userMessage);
            }

            // 默认：不支持的方法抛出异常
            throw new UnsupportedOperationException(
                    "Method not supported by AiServices proxy: " + methodName);
        }

        /**
         * 执行聊天对话（包含自动工具调用循环）。
         */
        private String executeChat(String userMessage) {
            // 初始化 ChatMemory（如果配置了）
            if (chatMemory != null && systemMessageProvider != null) {
                boolean hasSystem = chatMemory.messages().stream()
                        .anyMatch(m -> m instanceof SystemMessage);
                if (!hasSystem) {
                    chatMemory.add(systemMessageProvider.provide(chatMemory.id()));
                }
                chatMemory.add(UserMessage.from(userMessage));
            }

            int iteration = 0;
            List<ChatMessage> messages = buildMessages(userMessage);

            while (iteration < maxIterations) {
                iteration++;
                log.debug("AiServices iteration #{}: calling LLM with {} tools",
                        iteration, toolSpecifications.size());

                // 构建请求
                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(toolSpecifications)
                        .parameters(
                                DefaultChatRequestParameters.builder()
                                        .toolChoice(toolChoice)
                                        .build()
                        )
                        .build();

                // [DEBUG] 打印即将发送的消息类型和数量
                for (ChatMessage msg : messages) {
                    if (msg instanceof ToolExecutionResultMessage) {
                        ToolExecutionResultMessage tm = (ToolExecutionResultMessage) msg;
                        log.warn("AiServices REQ MSG: type=TOOL_RESULT, toolCallId='{}', toolName='{}', text='{}'",
                                tm.id(), tm.toolName(), tm.text());
                    } else if (msg instanceof AiMessage && ((AiMessage)msg).hasToolExecutionRequests()) {
                        AiMessage am = (AiMessage) msg;
                        for (ToolExecutionRequest ter : am.toolExecutionRequests()) {
                            log.warn("AiServices REQ MSG: type=ASSISTANT_TOOL_CALL, id='{}', name='{}'",
                                    ter.id(), ter.name());
                        }
                    } else if (msg instanceof AiMessage) {
                        log.warn("AiServices REQ MSG: type=ASSISTANT_TEXT, text='{}'",
                                ((AiMessage) msg).text());
                    }
                }

                // 调用 LLM
                ChatResponse response = chatModel.chat(request);
                AiMessage aiMessage = response.aiMessage();

                // [DEBUG] 打印 LLM 响应中的 tool_calls ID
                if (aiMessage.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest ter : aiMessage.toolExecutionRequests()) {
                        log.warn("AiServices LLM RESP: tool_call id='{}', name='{}', args='{}'",
                                ter.id() != null ? ter.id() : "<NULL>",
                                ter.name(),
                                ter.arguments());
                    }
                } else {
                    log.warn("AiServices LLM RESP: text='{}'", aiMessage.text());
                }

                // 添加 AI 消息到 memory
                if (chatMemory != null) {
                    chatMemory.add(aiMessage);
                }

                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();

                // 没有工具调用，直接返回结果
                if (toolRequests == null || toolRequests.isEmpty()) {
                    String text = aiMessage.text();
                    log.debug("AiServices: no tool calls, returning text");
                    return text != null ? text : "任务完成";
                }

                // 处理工具调用
                log.debug("AiServices: processing {} tool call(s)", toolRequests.size());
                for (ToolExecutionRequest toolRequest : toolRequests) {
                    String toolResult = executeTool(toolRequest);

                    // [DEBUG] 检查 tool call ID 是否为空
                    String toolCallId = toolRequest.id();
                    log.warn("AiServices: tool call result - id='{}', name='{}', args='{}'",
                            toolCallId != null ? toolCallId : "<NULL>",
                            toolRequest.name(),
                            toolRequest.arguments());

                    ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.builder()
                            .id(toolCallId)
                            .toolName(toolRequest.name())
                            .text(toolResult)
                            .build();

                    if (chatMemory != null) {
                        chatMemory.add(resultMessage);
                    }

                    messages.add(resultMessage);
                }

                // 更新消息列表用于下一轮
                messages = buildMessagesFromMemory();
            }

            log.warn("AiServices: reached max iterations {}", maxIterations);
            return "Reached max iterations, task terminated";
        }

        /**
         * 构建初始消息列表。
         */
        private List<ChatMessage> buildMessages(String userMessage) {
            if (chatMemory != null) {
                return new ArrayList<>(chatMemory.messages());
            }
            // 无 memory 模式：单轮对话
            List<ChatMessage> messages = new ArrayList<>();
            if (systemMessageProvider != null) {
                messages.add(systemMessageProvider.provide("default"));
            }
            messages.add(UserMessage.from(userMessage));
            return messages;
        }

        /**
         * 从 ChatMemory 构建消息列表。
         */
        private List<ChatMessage> buildMessagesFromMemory() {
            if (chatMemory != null) {
                return new ArrayList<>(chatMemory.messages());
            }
            return new ArrayList<>();
        }

        /**
         * 执行工具调用。
         * <p>通过反射查找匹配的工具方法并调用。</p>
         */
        private String executeTool(ToolExecutionRequest request) {
            String toolName = request.name();
            String arguments = request.arguments();

            for (Object tool : tools) {
                String result = tryInvokeTool(tool, toolName, arguments);
                if (result != null) {
                    return result;
                }
            }

            return "Error: Tool not found: " + toolName;
        }

        /**
         * 尝试在工具实例上调用指定方法。
         *
         * @return 工具执行结果，如果未找到匹配方法则返回 null
         */
        private String tryInvokeTool(Object tool, String toolName, String arguments) {
            // 优先使用 callTool 方法（PoLangToolService 等已有此约定）
            try {
                Method callToolMethod = tool.getClass().getMethod("callTool", String.class, String.class);
                Object result = callToolMethod.invoke(tool, toolName, arguments);
                if (result instanceof String) {
                    return (String) result;
                }
            } catch (NoSuchMethodException e) {
                // 没有 callTool 方法，尝试直接匹配 @Tool 注解的方法名
                return tryInvokeByMethodName(tool, toolName, arguments);
            } catch (Exception e) {
                log.error("Tool execution error: {}", toolName, e);
                return "Error: " + e.getMessage();
            }
            return null;
        }

        /**
         * 通过方法名直接匹配并调用 @Tool 注解方法。
         */
        private String tryInvokeByMethodName(Object tool, String toolName, String arguments) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                com.mamba.tool.Tool toolAnnotation = method.getAnnotation(com.mamba.tool.Tool.class);
                if (toolAnnotation != null) {
                    String annotatedName = toolAnnotation.name();
                    if (annotatedName.isEmpty()) {
                        annotatedName = method.getName();
                    }
                    if (annotatedName.equals(toolName)) {
                        try {
                            // 简化：假设无参数或参数通过 JSON 解析
                            // 实际项目中应使用更完善的参数解析
                            Object result = method.invoke(tool);
                            if (result instanceof String) {
                                return (String) result;
                            }
                            return result != null ? result.toString() : "Success";
                        } catch (Exception e) {
                            log.error("Direct tool invocation error: {}", toolName, e);
                            return "Error: " + e.getMessage();
                        }
                    }
                }
            }
            return null;
        }
    }
}
