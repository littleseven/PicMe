package com.mamba.picme.agent.core.inference.local.pipeline

import com.mamba.picme.agent.core.local.llm.LlmChatRequest
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.plan.ExecutionPlan
import com.mamba.picme.agent.core.model.plan.PlanStep
import com.mamba.picme.agent.core.inference.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.inference.local.prompt.LocalPromptBuilder
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.MemoryManager
import com.mamba.data.message.UserMessage
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.execution.InferenceResult
import com.mamba.picme.agent.core.runtime.cache.IntentCache
import com.mamba.picme.agent.core.runtime.policy.PrivacyGuard
import com.mamba.picme.agent.core.runtime.policy.PrivacyLevel
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地推理管道
 *
 * 负责使用本地 LLM（MNN-LLM）进行推理，使用自定义 method/params JSON 数组协议。
 * 提供三个层级：
 * - L1: 缓存命中（IntentCache）
 * - L2: 批量命令（本地快速通道）
 * - L3: 计划执行（本地简化版 Plan）
 * - Privacy: 隐私强制路径
 *
 * 与远程管道完全独立，无共享路由逻辑。
 */
class LocalInferencePipeline(
    private val localEngine: LocalLlmEngine,
    private val sceneManager: SceneManager,
    private val capabilityRegistry: CapabilityRegistry,
    private val intentCache: IntentCache,
    private val privacyGuard: PrivacyGuard,
    private val memoryManager: MemoryManager
) {

    private val tag = "LocalInferencePipeline"
    private val promptBuilder = LocalPromptBuilder(sceneManager)

    companion object {
        const val LOCAL_L2_TIMEOUT_MS = 10000L
    }

    /**
     * 处理用户输入（主入口）
     *
     * 路由逻辑：
     * 1. L1 缓存查询
     * 2. 隐私分级检查，RESTRICTED 强制走本地
     * 3. L2 本地快速通道
     * 4. L3 本地 Plan（含延迟指令时）
     * 5. 最终兜底：完整系统 Prompt
     */
    suspend fun processInput(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        Logger.d(tag, "Processing input via local pipeline: '$userInput'")

        // 1. L1 缓存查询
        val cachedCommand = intentCache.match(userInput)
        if (cachedCommand != null) {
            Logger.i(tag, "L1 cache hit for input='$userInput' -> ${cachedCommand::class.simpleName}")
            return InferenceResult.Local(command = cachedCommand)
        }

        // 2. 隐私分级检查
        val privacyLevel = privacyGuard.classify(userInput)
        Logger.d(tag, "Privacy level: $privacyLevel")
        if (privacyLevel == PrivacyLevel.RESTRICTED) {
            Logger.i(tag, "RESTRICTED content detected, routing to local full prompt")
            return routeToLocal(userInput, context)
        }

        // 3. L2 本地快速通道
        Logger.i(tag, "[L2-LOCAL] Trying local fast path")
        val localResult = tryLocalL2First(userInput, context)
        if (localResult != null) {
            Logger.i(tag, "[L2-LOCAL] Fast path hit")
            return localResult
        }

        // 4. 如果指令包含延迟关键词，尝试 L3 本地 Plan
        val hasDelay = listOf("秒后", "延迟", "延时", "稍后", "倒计时")
            .any { userInput.contains(it) }
        if (hasDelay) {
            Logger.i(tag, "[L3-LOCAL] Input has delay keywords, trying local L3 Plan")
            val localPlanResult = tryLocalL3First(userInput, context)
            if (localPlanResult != null) {
                Logger.i(tag, "[L3-LOCAL] Local plan path hit")
                return localPlanResult
            }
        }

        // 5. 最终兜底：完整系统 Prompt
        Logger.i(tag, "[L2-LOCAL] Fast path failed, falling back to full system prompt")
        return routeToLocal(userInput, context)
    }

    /**
     * L2 本地快速通道
     */
    private suspend fun tryLocalL2First(
        userInput: String,
        context: AgentContext
    ): InferenceResult? = withTimeoutOrNull(LOCAL_L2_TIMEOUT_MS) {
        Logger.i(tag, "[L2-LOCAL] Entering local L2 fast path, timeout=${LOCAL_L2_TIMEOUT_MS}ms")
        val startTime = System.currentTimeMillis()
        val result = routeToLocalL2(userInput, context)
        val latency = System.currentTimeMillis() - startTime

        Logger.i(tag, "[L2-LOCAL] routeToLocalL2 returned type=${result::class.simpleName}, latency=${latency}ms")

        when {
            result is InferenceResult.Local && result.command !is AgentCommand.Error -> {
                Logger.i(tag, "[L2-LOCAL] Success, latency=${latency}ms")
                result
            }
            result is InferenceResult.Local && result.command is AgentCommand.Error -> {
                Logger.w(tag, "[L2-LOCAL] Local engine returned error, latency=${latency}ms")
                null
            }
            else -> {
                Logger.w(tag, "[L2-LOCAL] Failed, latency=${latency}ms")
                null
            }
        }
    }

    /**
     * L2 本地推理（简化 Prompt）
     */
    private suspend fun routeToLocalL2(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        val capabilities = capabilityRegistry.getCapabilitiesForCurrentScene()
        val systemPrompt = promptBuilder.buildL2SystemPrompt(capabilities, context)

        // 构建带历史上下文的 messages
        // P1: user 末尾钉一次格式（近端锚点）——小模型在长 system 后易丢格式，最近一条 user 后
        // 再强调"只输出JSON数组"，显著提升遵从率。仅追加到本次推理 messages，不写入 memory。
        val messages = memoryManager.buildContextMessages(context.memorySessionId, systemPrompt, userInput) +
            UserMessage.from("只输出JSON数组，不要任何其他文字。")

        val result = try {
            Result.success(
                localEngine.chat(
                    LlmChatRequest(
                        messages = messages
                    )
                ).aiMessage.text()
            )
        } catch (e: Exception) {
            Result.failure(e)
        }

        return result.fold(
            onSuccess = { response ->
                Logger.i(tag, "[L2-LOCAL] Raw response: ${response.replace("\n", "\\n")}")
                val command = LocalCommandParser.parseLlmResponse(response, context)
                Logger.i(tag, "[L2-LOCAL] Parsed: ${command::class.simpleName}")
                InferenceResult.Local(command = command, responseText = response)
            },
            onFailure = { error ->
                Logger.e(tag, "[L2-LOCAL] Engine failed", error)
                InferenceResult.Local(
                    command = AgentCommand.Error(reason = "本地推理失败：${error.message ?: "未知错误"}")
                )
            }
        )
    }

    /**
     * L3 本地快速通道（简化 Plan）
     */
    private suspend fun tryLocalL3First(
        userInput: String,
        context: AgentContext
    ): InferenceResult? = withTimeoutOrNull(LOCAL_L2_TIMEOUT_MS) {
        Logger.i(tag, "[L3-LOCAL] Entering local L3 fast path, timeout=${LOCAL_L2_TIMEOUT_MS}ms")
        val startTime = System.currentTimeMillis()
        val result = routeToLocalL3(userInput, context)
        val latency = System.currentTimeMillis() - startTime

        Logger.i(tag, "[L3-LOCAL] routeToLocalL3 returned type=${result::class.simpleName}, latency=${latency}ms")

        when {
            result is InferenceResult.Plan && result.plan.steps.isNotEmpty() -> {
                Logger.i(tag, "[L3-LOCAL] Success, latency=${latency}ms")
                result
            }
            else -> {
                Logger.w(tag, "[L3-LOCAL] Failed, latency=${latency}ms")
                null
            }
        }
    }

    /**
     * L3 本地推理（简化版 Plan）
     */
    private suspend fun routeToLocalL3(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        val capabilities = capabilityRegistry.getCapabilitiesForCurrentScene()
        val systemPrompt = promptBuilder.buildL2SystemPrompt(capabilities, context)

        // 构建带历史上下文的 messages
        // P1: user 末尾钉一次格式（近端锚点）——小模型在长 system 后易丢格式，最近一条 user 后
        // 再强调"只输出JSON数组"，显著提升遵从率。仅追加到本次推理 messages，不写入 memory。
        val messages = memoryManager.buildContextMessages(context.memorySessionId, systemPrompt, userInput) +
            UserMessage.from("只输出JSON数组，不要任何其他文字。")

        val result = try {
            Result.success(
                localEngine.chat(
                    LlmChatRequest(
                        messages = messages
                    )
                ).aiMessage.text()
            )
        } catch (e: Exception) {
            Result.failure(e)
        }

        return result.fold(
            onSuccess = { response ->
                Logger.i(tag, "[L3-LOCAL] Raw response: ${response.replace("\n", "\\n")}")

                val commands = try {
                    LocalCommandParser.parseL2BatchResponse(response, context)
                } catch (e: Exception) {
                    Logger.w(tag, "[L3-LOCAL] Failed to parse as batch", e)
                    listOf(LocalCommandParser.parseLlmResponse(response, context))
                }

                Logger.i(tag, "[L3-LOCAL] Parsed ${commands.size} commands")

                if (commands.isNotEmpty() && commands.all { it !is AgentCommand.Error }) {
                    val steps = commands.mapIndexed { index, command ->
                        PlanStep(
                            step = index + 1,
                            action = command,
                            description = AgentCommand.getMethodName(command)
                        )
                    }
                    InferenceResult.Plan(
                        plan = ExecutionPlan(
                            planId = "local_plan_${System.currentTimeMillis()}",
                            description = userInput,
                            steps = steps
                        )
                    )
                } else {
                    Logger.w(tag, "[L3-LOCAL] Parsed commands empty or contains error")
                    InferenceResult.Plan(
                        plan = ExecutionPlan(
                            planId = "local_plan_error",
                            description = "解析失败",
                            steps = emptyList()
                        )
                    )
                }
            },
            onFailure = { error ->
                Logger.e(tag, "[L3-LOCAL] Engine failed", error)
                InferenceResult.Plan(
                    plan = ExecutionPlan(
                        planId = "local_plan_error",
                        description = "推理失败：${error.message ?: "未知错误"}",
                        steps = emptyList()
                    )
                )
            }
        )
    }

    /**
     * 隐私强制/完整系统 Prompt 路径
     */
    private suspend fun routeToLocal(
        userInput: String,
        context: AgentContext
    ): InferenceResult {
        val capabilities = capabilityRegistry.getCapabilitiesForCurrentScene()
        val systemPrompt = promptBuilder.buildSystemPrompt(capabilities, context)

        // 构建带历史上下文的 messages
        // P1: user 末尾钉一次格式（近端锚点）——小模型在长 system 后易丢格式，最近一条 user 后
        // 再强调"只输出JSON数组"，显著提升遵从率。仅追加到本次推理 messages，不写入 memory。
        val messages = memoryManager.buildContextMessages(context.memorySessionId, systemPrompt, userInput) +
            UserMessage.from("只输出JSON数组，不要任何其他文字。")

        val result = try {
            Result.success(
                localEngine.chat(
                    LlmChatRequest(
                        messages = messages
                    )
                ).aiMessage.text()
            )
        } catch (e: Exception) {
            Result.failure(e)
        }

        return result.fold(
            onSuccess = { response ->
                val command = LocalCommandParser.parseLlmResponse(response, context)
                InferenceResult.Local(command = command, responseText = response)
            },
            onFailure = { error ->
                Logger.e(tag, "Local engine failed", error)
                InferenceResult.Local(
                    command = AgentCommand.Error(reason = "本地推理失败：${error.message ?: "未知错误"}")
                )
            }
        )
    }
}
