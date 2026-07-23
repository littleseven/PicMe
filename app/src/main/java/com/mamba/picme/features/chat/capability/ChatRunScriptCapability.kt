package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import java.lang.ref.WeakReference

/**
 * Chat 场景「执行 JS 脚本」Capability。
 *
 * 职责：在 CHAT 场景暴露 `run_gallery_script`，把命令回调给 [Delegate]（ChatViewModel），
 * 由其在端侧 Rhino 沙箱执行（gallery.summary 等只读 handler），结果字符串回传给远程 LLM 做总结。
 */
class ChatRunScriptCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatRunScriptCapability? = null

        fun getInstance(): ChatRunScriptCapability =
            instance ?: synchronized(this) {
                instance ?: ChatRunScriptCapability().also { instance = it }
            }
    }

    private val tag = "ChatRunScriptCapability"

    override val name: String = "chat_run_script"
    override val description: String =
        "在端侧沙箱执行 JS（相册盘点/统计，只读）。脚本可调 bridge.call('gallery.summary') 取相册聚合统计并在 JS 内做组合计算"

    interface Delegate {
        /** 在端侧沙箱执行 [code]，返回结果（JSON 文本，会作为 observation 回传远程 LLM）。 */
        suspend fun onRunScript(code: String): String
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

    override fun supportedCommands(): List<String> = listOf("run_gallery_script")

    override fun getCommandDescription(command: String): String = when (command) {
        "run_gallery_script" -> "执行 JS 脚本（端侧沙箱）。参数: code (string, JS 源码)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val delegate = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "脚本执行暂不可用（聊天页未激活）"
                )
            )
        return try {
            when (command) {
                is AgentCommand.ExecuteScript -> {
                    val result = delegate.onRunScript(command.code)
                    Result.success(
                        AgentAction.TextReply(
                            commandId = command.commandId,
                            message = result
                        )
                    )
                }
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatRunScriptCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Run script failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "脚本执行失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }
}
