package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.IosAiOptimizeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS Chat「AI 一键优化」Capability —— `ai_optimize` 命令的 iOS 执行端（2026-08-16 抽卡追齐）。
 *
 * 对齐 Android `AiOptimizeCapability` 的双执行语义：
 * - 本能力跑**固定预设轻量优化**（经 [bridge] → Swift `AiOptimizeService`，端侧场景分析+预设），
 *   产出 [AgentAction.Success]（command 携 explanation + resultRecipe JSON）——
 *   LLM observation 为 ChatToolService 固定串「图片已优化，结果已展示在聊天中」；
 * - 抽卡（gacha）**不在本能力跑**：Swift `ChatViewModel` 收到 Success(AiOptimize) 后
 *   分流到 ChatOptimizeGachaController.draw()（对齐 Android ViewModel 层分流）。
 *
 * 路由：仅声明 `ai_optimize`，与 IosChatGalleryCapability / IosChartCapability 无冲突。
 *
 * [PRIVACY]：全链路端侧（场景分析/渲染/评分），无媒体上传。
 */
class IosAiOptimizeCapability(
    private val bridge: IosAiOptimizeBridge? = null
) : BaseCapability() {

    private val tag = "PoLang:IosAiOptimizeCapability"

    override val name: String = "ios_ai_optimize"
    override val description: String = "AI 一键优化图片：分析照片场景并自动推荐美颜、滤镜、调节参数"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(COMMAND_AI_OPTIMIZE)

    override fun isAvailable(): Boolean = bridge != null

    override fun getCommandDescription(command: String): String = when (command) {
        COMMAND_AI_OPTIMIZE -> "AI 一键优化图片，参数: image_uri (图片URI)"
        else -> super.getCommandDescription(command)
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> = try {
        when (command) {
            is AgentCommand.AiOptimize -> handleAiOptimize(command)
            else -> Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.METHOD_NOT_FOUND,
                    "IosAiOptimizeCapability 不支持此命令"
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(tag, "ai_optimize failed", e)
        Result.success(
            AgentAction.Error(
                command.commandId,
                AgentErrorCode.INTERNAL_ERROR,
                "AI 优化失败：${e.message ?: "未知错误"}"
            )
        )
    }

    private suspend fun handleAiOptimize(command: AgentCommand.AiOptimize): Result<AgentAction> {
        val optimizer = bridge
            ?: return Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    "AI 优化暂不可用（优化桥未注入）"
                )
            )
        if (command.imageUri.isBlank()) {
            // 对齐 Android：无目标图 → INVALID_REQUEST（LLM 会转述让用户选图）。
            // 注意：Android 侧还有 GalleryContext.currentMedia 兜底，iOS chat 无页面上下文，
            // 空 URI 兜底由 Swift ChatViewModel 的 lastUserImageUri 承担（gacha 分流侧）。
            return Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.INVALID_REQUEST,
                    "请先选择要优化的图片"
                )
            )
        }
        val outcome = awaitOptimize(optimizer, command.imageUri)
        return if (outcome.ok) {
            Result.success(
                AgentAction.Success(
                    commandId = command.commandId,
                    command = command.copy(
                        imageUri = command.imageUri,
                        explanation = outcome.explanation,
                        resultRecipe = outcome.resultRecipeJson
                    )
                )
            )
        } else {
            Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.INTERNAL_ERROR,
                    outcome.explanation
                )
            )
        }
    }

    /**
     * completion 回调转 suspend（对齐 IosChartCapability.awaitRender 范式）。
     * 异常绝不逃逸出 Kotlin 边界（signal 6 铁律）——桥异常时回退错误 observation。
     */
    private suspend fun awaitOptimize(
        bridge: IosAiOptimizeBridge,
        imageUri: String
    ): OptimizeOutcome = suspendCancellableCoroutine { cont ->
        try {
            bridge.optimizeFixed(
                imageUri,
                onResult = { explanation, resultRecipeJson ->
                    if (cont.isActive) cont.resume(OptimizeOutcome(ok = true, explanation, resultRecipeJson))
                },
                onError = { message ->
                    if (cont.isActive) cont.resume(OptimizeOutcome(ok = false, explanation = message, resultRecipeJson = ""))
                }
            )
        } catch (t: Throwable) {
            Logger.e(tag, "optimizeFixed bridge threw", t)
            if (cont.isActive) {
                cont.resume(OptimizeOutcome(ok = false, explanation = "AI 优化失败：桥异常", resultRecipeJson = ""))
            }
        }
    }

    private data class OptimizeOutcome(
        val ok: Boolean,
        val explanation: String,
        val resultRecipeJson: String
    )

    companion object {
        private const val COMMAND_AI_OPTIMIZE = "ai_optimize"
    }
}
