package com.mamba.picme.domain.agent.capability.optimize

import android.content.Context
import com.mamba.model.chat.request.json.JsonObjectSchema
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.context.PageContext.GalleryContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 一键优化 Capability
 *
 * 支持两种模式：
 * - fast：本地场景分析 + 本地预设，无需网络
 * - smart：云端视觉模型推荐，需用户授权
 *
 * 实际优化逻辑委托给 [AiOptimizeUseCase]，本类仅负责 Agent 命令路由与结果反馈。
 */
class AiOptimizeCapability(
    private val context: Context,
    private val optimizeUseCase: AiOptimizeUseCase
) : Capability {

    companion object {
        private const val TAG = "PicMe:AiOptimizeCapability"
        private const val COMMAND_NAME = "ai_optimize"

        private val moshi: Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    override val name: String = COMMAND_NAME
    override val description: String = "AI 一键优化图片：分析照片场景并自动推荐美颜、滤镜、调节参数"

    override fun supportedCommands(): List<String> = listOf(COMMAND_NAME)

    override fun getCommandDescription(command: String): String = when (command) {
        COMMAND_NAME -> "AI 一键优化图片，参数: image_uri (图片URI), mode (fast|smart, 默认fast)"
        else -> "未知命令"
    }

    override fun isAvailable(): Boolean = true

    override fun activeScenes(): List<SceneManager.Scene> = listOf(
        SceneManager.Scene.GALLERY,
        SceneManager.Scene.CHAT
    )

    override fun getCommandParameterSchema(command: String): JsonObjectSchema {
        return JsonObjectSchema.builder()
            .addStringProperty("image_uri", "待优化图片的本地文件 URI")
            .addEnumProperty("mode", listOf("fast", "smart"))
            .required("image_uri")
            .build()
    }

    private fun resolveImageUri(commandUri: String, pageContext: PageContext?): String {
        if (commandUri.isNotBlank()) return commandUri
        return when (pageContext) {
            is GalleryContext -> pageContext.currentMedia?.uri.orEmpty()
            else -> ""
        }
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val aiOptimizeCommand = command as? AgentCommand.AiOptimize
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_REQUEST,
                    message = "命令类型不匹配"
                )
            )

        val imageUri = resolveImageUri(aiOptimizeCommand.imageUri, pageContext)
        if (imageUri.isBlank()) {
            return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_REQUEST,
                    message = "请先选择要优化的图片"
                )
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val result = when (aiOptimizeCommand.mode.lowercase()) {
                    "smart" -> optimizeUseCase.smartOptimize(imageUri)
                    else -> optimizeUseCase.fastOptimize(imageUri)
                }

                Logger.d(TAG, "AI optimize completed: ${result.scene}, cloud=${result.usedCloud}")

                val resultDto = com.mamba.picme.domain.agent.capability.optimize.OptimizeResultDto(
                    sourceUri = imageUri,
                    scene = result.scene.name,
                    explanation = result.explanation,
                    preset = OptimizePreset(
                        scene = result.scene.name,
                        beauty = com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset(
                            enabled = result.editRecipe.beauty.enabled,
                            smoothing = result.editRecipe.beauty.smoothing,
                            whitening = result.editRecipe.beauty.whitening,
                            slimFace = result.editRecipe.beauty.slimFace,
                            bigEyes = result.editRecipe.beauty.bigEyes,
                            lipColor = result.editRecipe.beauty.lipColor,
                            blush = result.editRecipe.beauty.blush,
                            eyebrow = result.editRecipe.beauty.eyebrow
                        ),
                        filter = com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset(
                            colorFilter = result.editRecipe.colorFilter.name,
                            styleFilter = result.editRecipe.styleFilter.name
                        ),
                        adjustment = com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset(
                            brightness = result.editRecipe.adjustments.brightness,
                            exposure = result.editRecipe.adjustments.exposure,
                            contrast = result.editRecipe.adjustments.contrast,
                            saturation = result.editRecipe.adjustments.saturation,
                            temperature = result.editRecipe.adjustments.temperature,
                            tint = result.editRecipe.adjustments.tint
                        )
                    )
                )
                val resultJson = moshi.adapter(OptimizeResultDto::class.java).toJson(resultDto)

                Result.success(
                    AgentAction.Success(
                        commandId = command.commandId,
                        command = aiOptimizeCommand.copy(
                            imageUri = imageUri,
                            explanation = result.explanation,
                            resultRecipe = resultJson
                        )
                    )
                )
            } catch (e: Exception) {
                Logger.e(TAG, "AI optimize failed", e)
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.INTERNAL_ERROR,
                        message = "AI 优化失败：${e.message ?: "未知错误"}"
                    )
                )
            }
        }
    }
}
