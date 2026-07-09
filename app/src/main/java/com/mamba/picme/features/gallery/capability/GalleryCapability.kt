package com.mamba.picme.features.gallery.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.search.QueryBuilder
import com.mamba.picme.agent.core.model.context.MediaAsset
import java.lang.ref.WeakReference

/**
 * Gallery 相册控制 Capability
 *
 * 应用级单例，通过 delegate 模式与页面解耦：
 * - 在 Application.onCreate() 中注册一次，永不注销
 * - GalleryScreen 通过绑定 delegate 提供实际执行逻辑
 * - 页面离开时解绑 delegate，Capability 仍然注册但返回不可用状态
 * - 支持跨页面指令排队：当 Gallery 页面再次激活时自动执行待处理命令
 *
 * 仅在 GALLERY 场景可用
 */
class GalleryCapability : BaseCapability() {

    companion object {
        @Volatile
        private var instance: GalleryCapability? = null

        fun getInstance(): GalleryCapability {
            return instance ?: synchronized(this) {
                instance ?: GalleryCapability().also { instance = it }
            }
        }
    }

    private val tag = "GalleryCapability"

    override val name: String = "gallery"
    override val description: String = "相册操作：查看、删除、分享、搜索、选择照片和视频"

    /**
     * 搜索引擎（由 AppContainer 注入）
     */
    var searchEngine: MediaSearchEngine? = null

    /**
     * 跨维度查询构建器（由 AppContainer 注入）
     */
    var queryBuilder: QueryBuilder? = null

    /**
     * 相册操作委托接口
     *
     * GalleryScreen 实现此接口并绑定到 Capability
     */
    interface Delegate {
        fun onViewMedia(mediaId: String?)
        fun onDeleteMedia(mediaIds: List<String>)
        fun onShareMedia(mediaIds: List<String>)
        fun onSelectMedia(mediaId: String, selected: Boolean)
        fun onSearch(query: String, results: List<MediaAsset>)
        fun onSwitchViewMode(mode: ViewMode)
        fun onFavoriteMedia(mediaId: String, favorite: Boolean)
    }

    /**
     * 当前绑定的委托，null 表示相册页面未激活
     * 使用 WeakReference 防止 Compose 页面泄漏
     */
    private var delegateRef: WeakReference<Delegate>? = null

    /**
     * 绑定委托（由 GalleryScreen 调用）
     */
    fun bindDelegate(delegate: Delegate) {
        this.delegateRef = WeakReference(delegate)
        Logger.i(tag, "Delegate bound")
    }

    /**
     * 解绑委托（由 GalleryScreen onDispose 调用）
     */
    fun unbindDelegate() {
        this.delegateRef = null
        Logger.i(tag, "Delegate unbound")
    }

    override fun isAvailable(): Boolean {
        return delegateRef?.get() != null
    }

    override fun activeScenes(): List<SceneManager.Scene> {
        // Gallery 能力只在相册场景可用
        return listOf(SceneManager.Scene.GALLERY)
    }

    override fun supportedCommands(): List<String> = listOf(
        "view_media",
        "delete_media",
        "share_media",
        "select_media",
        "search_media",
        "switch_view_mode",
        "favorite_media"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "view_media" -> "查看指定照片，参数: media_id (可选，不传则查看当前选中)"
        "delete_media" -> "删除照片，参数: media_ids (数组，不传则删除当前选中)"
        "share_media" -> "分享照片，参数: media_ids (数组，不传则分享当前选中)"
        "select_media" -> "选择/取消选择照片，参数: media_id, selected (true/false)"
        "search_media" -> "搜索照片，参数: query (搜索关键词如'昨天'、'上海'等)"
        "switch_view_mode" -> "切换视图模式，参数: mode (grid/list/timeline)"
        "favorite_media" -> "收藏/取消收藏，参数: media_id, favorite (true/false)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Executing command: ${command::class.simpleName}")

        val d = delegateRef?.get()
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    message = "相册页面未激活，请先切换到相册页面"
                )
            )

        val galleryContext = pageContext as? PageContext.GalleryContext

        return when (command) {
            is AgentCommand.ViewMedia -> {
                handleViewMedia(command, galleryContext, d)
            }

            is AgentCommand.DeleteMedia -> {
                handleDeleteMedia(command, galleryContext, d)
            }

            is AgentCommand.ShareMedia -> {
                handleShareMedia(command, galleryContext, d)
            }

            is AgentCommand.SelectMedia -> {
                d.onSelectMedia(command.mediaId, command.selected)
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            is AgentCommand.SearchMedia -> {
                handleSearchMedia(command, d)
            }

            is AgentCommand.SwitchViewMode -> {
                handleSwitchViewMode(command, d)
            }

            is AgentCommand.FavoriteMedia -> {
                d.onFavoriteMedia(command.mediaId, command.favorite)
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            else -> {
                Logger.w(tag, "Unsupported command: ${command::class.simpleName}")
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "Gallery 不支持此命令"
                    )
                )
            }
        }
    }

    private fun handleViewMedia(
        command: AgentCommand.ViewMedia,
        galleryContext: PageContext.GalleryContext?,
        d: Delegate
    ): Result<AgentAction> {
        val mediaId = command.mediaId ?: galleryContext?.currentMedia?.id?.toString()

        return if (mediaId != null) {
            d.onViewMedia(mediaId)
            Result.success(AgentAction.Success(commandId = command.commandId, command = command))
        } else {
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = "没有指定要查看的照片"
                )
            )
        }
    }

    private fun handleDeleteMedia(
        command: AgentCommand.DeleteMedia,
        galleryContext: PageContext.GalleryContext?,
        d: Delegate
    ): Result<AgentAction> {
        val mediaIds = command.mediaIds.ifEmpty {
            // 如果没有指定 ID，使用当前选中的
            galleryContext?.selectedItems?.map { it.id.toString() } ?: emptyList()
        }

        return if (mediaIds.isNotEmpty()) {
            d.onDeleteMedia(mediaIds)
            Result.success(AgentAction.Success(commandId = command.commandId, command = command))
        } else {
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = "没有指定要删除的照片，请先选择"
                )
            )
        }
    }

    private fun handleShareMedia(
        command: AgentCommand.ShareMedia,
        galleryContext: PageContext.GalleryContext?,
        d: Delegate
    ): Result<AgentAction> {
        val mediaIds = command.mediaIds.ifEmpty {
            galleryContext?.selectedItems?.map { it.id.toString() } ?: emptyList()
        }

        return if (mediaIds.isNotEmpty()) {
            d.onShareMedia(mediaIds)
            Result.success(AgentAction.Success(commandId = command.commandId, command = command))
        } else {
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = "没有指定要分享的照片，请先选择"
                )
            )
        }
    }

    private suspend fun handleSearchMedia(
        command: AgentCommand.SearchMedia,
        d: Delegate
    ): Result<AgentAction> {
        if (command.query.isBlank()) {
            return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = "搜索关键词不能为空"
                )
            )
        }

        val engine = searchEngine
        if (engine != null) {
            return try {
                val result = engine.search(command.query)
                Logger.i(tag, "Search '${command.query}' → ${result.resultCount} results")
                d.onSearch(command.query, result.media)
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = "找到 ${result.resultCount} 张匹配 '${command.query}' 的照片"
                    )
                )
            } catch (e: Exception) {
                Logger.e(tag, "Search failed for '${command.query}'", e)
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.INTERNAL_ERROR,
                        message = "搜索失败：${e.message ?: "未知错误"}"
                    )
                )
            }
        } else {
            // 回退到旧行为（搜索引擎未初始化）
            Logger.w(tag, "Search engine not initialized, falling back to delegate")
            d.onSearch(command.query, emptyList())
            return Result.success(AgentAction.Success(commandId = command.commandId, command = command))
        }
    }

    private fun handleSwitchViewMode(
        command: AgentCommand.SwitchViewMode,
        d: Delegate
    ): Result<AgentAction> {
        val mode = when (command.mode.lowercase()) {
            "grid", "网格" -> ViewMode.GRID
            "list", "列表" -> ViewMode.LIST
            "timeline", "时间线" -> ViewMode.TIMELINE
            else -> ViewMode.GRID
        }
        d.onSwitchViewMode(mode)
        return Result.success(AgentAction.Success(commandId = command.commandId, command = command))
    }

    /**
     * 视图模式
     */
    enum class ViewMode {
        GRID,       // 网格视图
        LIST,       // 列表视图
        TIMELINE    // 时间线视图
    }
}
