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
 * Chat 场景「媒体写操作」Capability（delete_media / favorite_media / select_media）。
 *
 * 存在意义：JS 经 `capability.dispatch` 在 CHAT 场景下发写命令时，若注册表只找得到
 * GalleryCapability（GALLERY 场景），命令会进跨页队列（等用户打开相册页才执行）——
 * 对 JS 同步语义是错误的。本 Capability 声明 activeScenes=[CHAT] 并注册进 Compose
 * CapabilityHost，使 CHAT 场景 dispatch 命中本类，立即执行。
 *
 * 写操作落点由 [Delegate]（ChatViewModel）实现：删除复用 MediaRepository 的
 * MediaStore 授权流；收藏/选中为 chat 会话级状态（App 尚无持久化收藏路径，
 * 与 GalleryCapability 的 favorite 先例一致）。
 *
 * 注意：share_media 不在本 Capability（ChatToolService 已有通路，避免重复注册冲突）。
 */
class ChatMediaWriteCapability private constructor() : BaseCapability() {

    companion object {
        @Volatile
        private var instance: ChatMediaWriteCapability? = null

        fun getInstance(): ChatMediaWriteCapability =
            instance ?: synchronized(this) {
                instance ?: ChatMediaWriteCapability().also { instance = it }
            }
    }

    private val tag = "ChatMediaWriteCapability"

    override val name: String = "chat_media_write"
    override val description: String =
        "Chat 场景媒体写操作：删除（走系统授权流）/收藏/选中，删除为高风险不可恢复操作"

    interface Delegate {
        /** 删除媒体（内部走 MediaStore 授权流，可能弹系统授权框）；返回结果描述。 */
        suspend fun onDeleteMedia(mediaIds: List<String>): String

        /** 收藏/取消收藏（chat 会话级）；返回结果描述。 */
        suspend fun onFavoriteMedia(mediaId: String, favorite: Boolean): String

        /** 选中/取消选中（chat 会话级）；返回结果描述。 */
        suspend fun onSelectMedia(mediaId: String, selected: Boolean): String
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

    override fun supportedCommands(): List<String> =
        listOf("delete_media", "favorite_media", "select_media")

    override fun getCommandDescription(command: String): String = when (command) {
        "delete_media" -> "删除媒体（不可恢复，需用户确认 + 系统授权）。参数: media_ids (id 列表)"
        "favorite_media" -> "收藏/取消收藏。参数: media_id, favorite (true/false)"
        "select_media" -> "选中/取消选中。参数: media_id, selected (true/false)"
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
                    message = "媒体写操作暂不可用（聊天页未激活）"
                )
            )
        return try {
            when (command) {
                is AgentCommand.DeleteMedia -> {
                    if (command.mediaIds.isEmpty()) {
                        errorResult(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要删除的媒体")
                    } else {
                        textResult(command.commandId, delegate.onDeleteMedia(command.mediaIds))
                    }
                }
                is AgentCommand.FavoriteMedia ->
                    textResult(command.commandId, delegate.onFavoriteMedia(command.mediaId, command.favorite))
                is AgentCommand.SelectMedia ->
                    textResult(command.commandId, delegate.onSelectMedia(command.mediaId, command.selected))
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "ChatMediaWriteCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "Media write failed", e)
            errorResult(command.commandId, AgentErrorCode.INTERNAL_ERROR, "媒体写操作失败：${e.message ?: "未知错误"}")
        }
    }

    private fun textResult(commandId: Int, message: String): Result<AgentAction> =
        Result.success(AgentAction.TextReply(commandId = commandId, message = message))

    private fun errorResult(commandId: Int, errorCode: Int, message: String): Result<AgentAction> =
        Result.success(
            AgentAction.Error(
                commandId = commandId,
                errorCode = errorCode,
                message = message
            )
        )
}
