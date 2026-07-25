package com.mamba.picme.domain.agent.capability

import android.content.Context
import com.mamba.picme.R
import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.ChatEditRecipeBuilder
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.features.chat.ChatEditStateHolder
import com.mamba.picme.features.editor.EditRecipe

/**
 * 对话式图片编辑 Capability。
 *
 * 职责：
 * - 在 CHAT 场景暴露 [edit_image] 工具
 * - 维护会话级编辑状态，支持多轮 delta 调整
 * - 调用 [ChatEditProcessor] 将 [EditRecipe] 渲染为结果图
 *
 * 设计原则：
 * - 依赖通过构造函数显式注入（Context、Processor、StateHolder）
 * - 状态保存在 [ChatEditStateHolder]，按 [AgentContext.memorySessionId] 隔离
 */
class ImageEditCapability(
    private val context: Context,
    private val chatEditProcessor: ChatEditProcessor,
    private val stateHolder: ChatEditStateHolder
) : BaseCapability() {

    private val tag = "ImageEditCapability"

    override val name: String = "image_edit"
    override val description: String = "对话式图片编辑：根据自然语言指令对照片进行美颜、调色、滤镜等编辑"

    override fun activeScenes(): List<SceneManager.Scene> {
        return listOf(SceneManager.Scene.CHAT)
    }

    override fun supportedCommands(): List<String> = listOf("edit_image")

    override fun getCommandDescription(command: String): String = when (command) {
        "edit_image" -> "根据自然语言描述编辑图片，参数: params (结构化编辑意图), image_uri (可选，目标图片 URI)"
        else -> "未知命令"
    }

    /**
     * 识别 LLM 标记的未支持编辑意图。
     *
     * 当 parser 或 LLM 在 [AgentCommand.EditImage.explanation] 中携带 `[unsupported:xxx]`
     * 标记时，直接返回友好文本回复，避免进入渲染流程。
     */
    private fun resolveUnsupportedReason(explanation: String): Int? {
        return when {
            explanation.contains("[unsupported:erase]") -> R.string.chat_edit_unsupported_erase
            explanation.contains("[unsupported:local_beauty]") -> R.string.chat_edit_unsupported_local_beauty
            else -> null
        }
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Executing command: ${command::class.simpleName}")

        val editCommand = command as? AgentCommand.EditImage
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                    message = this.context.getString(R.string.chat_edit_method_not_found)
                )
            )

        editCommand.explanation?.let { explanation ->
            resolveUnsupportedReason(explanation)?.let { messageRes ->
                return Result.success(
                    AgentAction.TextReply(
                        commandId = editCommand.commandId,
                        message = this.context.getString(messageRes)
                    )
                )
            }
        }

        val sessionId = context.memorySessionId
        val currentRecipe = stateHolder.get(sessionId)
            .takeIf { it.sourceUri.isNotBlank() }
            ?: EditRecipe(sourceUri = editCommand.imageUri)

        val targetUri = editCommand.imageUri.takeIf { it.isNotBlank() }
            ?: currentRecipe.sourceUri.takeIf { it.isNotBlank() }
            ?: context.lastUserImageUri

        if (targetUri.isNullOrBlank()) {
            return Result.success(
                AgentAction.Error(
                    commandId = editCommand.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = this.context.getString(R.string.chat_edit_no_image)
                )
            )
        }

        val recipe = ChatEditRecipeBuilder.build(
            currentRecipe.copy(sourceUri = targetUri),
            editCommand
        )

        return try {
            chatEditProcessor.execute(this.context, targetUri, recipe)
                .fold(
                    onSuccess = { outputUri ->
                        stateHolder.update(sessionId, recipe)
                        Result.success(
                            AgentAction.Success(
                                commandId = editCommand.commandId,
                                command = editCommand.copy(imageUri = outputUri)
                            )
                        )
                    },
                    onFailure = { e ->
                        Logger.e(tag, "Chat edit failed", e)
                        Result.success(
                            AgentAction.Error(
                                commandId = editCommand.commandId,
                                errorCode = AgentErrorCode.INTERNAL_ERROR,
                                message = this.context.getString(R.string.chat_edit_render_failed, e.message ?: this.context.getString(R.string.unknown))
                            )
                        )
                    }
                )
        } catch (e: Exception) {
            Logger.e(tag, "Chat edit execution crashed", e)
            Result.success(
                AgentAction.Error(
                    commandId = editCommand.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = this.context.getString(R.string.chat_edit_execution_failed, e.message ?: this.context.getString(R.string.unknown))
                )
            )
        }
    }
}
