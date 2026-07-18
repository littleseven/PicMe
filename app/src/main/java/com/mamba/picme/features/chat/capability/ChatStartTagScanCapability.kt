package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.usecase.StartTagScanResult
import java.lang.ref.WeakReference

/**
 * Chat 场景 TAG 扫描控制 Capability。
 *
 * 职责：把 start_tag_scan 命令暴露给 LLM，并回调给 [Delegate] 执行。
 */
class ChatStartTagScanCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatStartTagScanCapability? = null
        fun getInstance(): ChatStartTagScanCapability =
            instance ?: synchronized(this) {
                instance ?: ChatStartTagScanCapability().also { instance = it }
            }
    }

    private val tag = "ChatStartTagScanCapability"

    override val name: String = "chat_start_tag_scan"
    override val description: String = "在聊天中启动、暂停、恢复、取消或查询 TAG 扫描任务"

    interface Delegate {
        suspend fun onStartTagScan(
            action: String,
            taskType: String?,
            mode: String?
        ): StartTagScanResult
    }

    private var delegateRef: WeakReference<Delegate>? = null

    fun bindDelegate(delegate: Delegate) {
        delegateRef = WeakReference(delegate)
        Logger.i(tag, "Delegate bound")
    }

    fun unbindDelegate() {
        delegateRef = null
        Logger.i(tag, "Delegate unbound")
    }

    override fun isAvailable(): Boolean = delegateRef?.get() != null

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf("start_tag_scan")

    override fun getCommandDescription(command: String): String = when (command) {
        "start_tag_scan" -> "启动/控制/查询 TAG 扫描。参数: action=start|pause|resume|cancel|query, task_type=face|scene|activity|objects|tags|summary|mlkit|auto, mode=full|incremental"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val d = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "TAG 扫描控制暂不可用（聊天页未激活）"
                )
            )

        return try {
            when (command) {
                is AgentCommand.StartTagScan -> {
                    val result = d.onStartTagScan(
                        action = command.action,
                        taskType = command.taskType,
                        mode = command.mode
                    )
                    Result.success(result.toAgentAction(command.commandId))
                }
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatStartTagScanCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Start tag scan failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "扫描控制失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }
}

private fun StartTagScanResult.toAgentAction(commandId: Int): AgentAction {
    return when (this) {
        is StartTagScanResult.Started -> AgentAction.TextReply(
            commandId = commandId,
            message = message
        )
        is StartTagScanResult.ControlAck -> AgentAction.TextReply(
            commandId = commandId,
            message = message
        )
        is StartTagScanResult.Status -> AgentAction.TextReply(
            commandId = commandId,
            message = buildString {
                append("当前扫描状态：${state}")
                if (currentPass != null) append("，阶段：$currentPass")
                append("，进度：${processed}/${total}")
                if (failed > 0) append("，失败：${failed}")
                if (pending > 0) append("，待处理：${pending}")
                if (estimatedRemainingMs != null) append("，预计剩余：${estimatedRemainingMs / 1000}秒")
            }
        )
        is StartTagScanResult.Error -> AgentAction.Error(
            commandId = commandId,
            errorCode = AgentErrorCode.INVALID_PARAMS,
            message = error
        )
    }
}
