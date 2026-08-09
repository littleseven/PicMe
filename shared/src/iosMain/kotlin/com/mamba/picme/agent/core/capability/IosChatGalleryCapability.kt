package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.context.TimeRange
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * iOS Chat 相册能力 —— chat 场景的 8 个相册工具的执行端。
 *
 * 与 Android 三件套（ChatGallerySummaryCapability / ChatSearchCapability /
 * ChatMediaWriteCapability）语义对齐，差异均为诚实降级：
 * - iOS 端暂无标签/人脸索引，关键词搜索只匹配文件名，查不到就返回 0 结果
 *   （不做「返回最新 N 张」的误导兜底，LLM 边界已在 IosChatPrompt 告知）；
 * - delete 经 Photos framework 系统确认窗，无需 Android 11+ IntentSender 授权链路；
 * - view/select/share 为 UI 直通命令：此处只校验参数并返回 [AgentAction.Success]，
 *   实际跳转/选中/分享由 Swift 侧消费 uiActions 完成。
 *
 * [PRIVACY]：TextReply 只含计数与固定文案，不输出路径/GPS/base64；
 * MediaResults 只带媒体 id（与 Android 同口径）。
 */
class IosChatGalleryCapability(
    private val repository: MediaRepository,
    private val bridge: IosMediaRepositoryBridge
) : BaseCapability() {

    override val name: String = "chat_gallery"

    override val description: String = "相册对话操作：盘点、搜索、细化、查看、选择、收藏、删除、分享"

    /** 上一轮搜索命中的资产快照，供 refine_media_search 做 in-set 过滤。 */
    private var lastSearchAssets: List<MediaAsset>? = null

    override fun supportedCommands(): List<String> = SUPPORTED_COMMANDS

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return try {
            when (command) {
                is AgentCommand.GetGallerySummary -> handleSummary(command)
                is AgentCommand.SearchMedia -> handleSearch(command)
                is AgentCommand.RefineMediaSearch -> handleRefine(command)
                is AgentCommand.ViewMedia -> handleView(command)
                is AgentCommand.SelectMedia -> Result.success(AgentAction.Success(command.commandId, command))
                is AgentCommand.FavoriteMedia -> handleFavorite(command)
                is AgentCommand.DeleteMedia -> handleDelete(command)
                is AgentCommand.ShareMedia -> handleShare(command)
                else -> Result.success(
                    AgentAction.Error(
                        command.commandId,
                        AgentErrorCode.METHOD_NOT_FOUND,
                        "不支持的命令：${AgentCommand.getMethodName(command)}"
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INTERNAL_ERROR, "操作失败：${e.message}")
            )
        }
    }

    // ── 盘点 ──────────────────────────────────────────────────────────────

    private suspend fun handleSummary(command: AgentCommand.GetGallerySummary): Result<AgentAction> {
        val all = repository.allMedia.first()
        val photos = all.count { it.type == MediaType.PHOTO }
        val videos = all.count { it.type == MediaType.VIDEO }
        val message = "当前相册共有 ${all.size} 个媒体（$photos 张照片，$videos 个视频）。" +
            "iOS 端暂未开启标签/人脸索引，暂无人物与场景统计。"
        return Result.success(AgentAction.TextReply(command.commandId, message))
    }

    // ── 搜索 / 细化 ─────────────────────────────────────────────────────────

    private suspend fun handleSearch(command: AgentCommand.SearchMedia): Result<AgentAction> {
        val all = repository.allMedia.first()
        val hits = filterAssets(all, command.query, command.intent?.timeRange)
        // 空关键词 = 「看看最近的」，repo 已按 creationDate 降序，截前 N 条
        val results = if (command.query.isBlank()) hits.take(MAX_RECENT_RESULTS) else hits
        lastSearchAssets = results
        return Result.success(
            AgentAction.MediaResults(
                commandId = command.commandId,
                query = command.query,
                mediaIds = results.map { it.id },
                totalCount = results.size,
                isRefinement = false
            )
        )
    }

    private suspend fun handleRefine(command: AgentCommand.RefineMediaSearch): Result<AgentAction> {
        val base = lastSearchAssets
        val isRefinement = base != null
        val source = base ?: repository.allMedia.first()
        val hits = filterAssets(source, command.constraint, command.intent?.timeRange)
        lastSearchAssets = hits
        return Result.success(
            AgentAction.MediaResults(
                commandId = command.commandId,
                query = command.constraint,
                mediaIds = hits.map { it.id },
                totalCount = hits.size,
                isRefinement = isRefinement
            )
        )
    }

    /**
     * 文件名关键词 + 时间范围过滤。
     * 关键词大小写不敏感匹配 [MediaAsset.fileName]；时间范围按 captureDate（毫秒）闭区间。
     */
    private fun filterAssets(base: List<MediaAsset>, keyword: String, timeRange: TimeRange?): List<MediaAsset> {
        var result = base
        if (timeRange != null) {
            result = result.filter { it.captureDate in timeRange.startMs..timeRange.endMs }
        }
        if (keyword.isNotBlank()) {
            result = result.filter { it.fileName.contains(keyword, ignoreCase = true) }
        }
        return result
    }

    // ── UI 直通命令（Swift 消费 uiActions）────────────────────────────────────

    private fun handleView(command: AgentCommand.ViewMedia): Result<AgentAction> {
        if (command.mediaId.isNullOrBlank()) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要查看的媒体")
            )
        }
        return Result.success(AgentAction.Success(command.commandId, command))
    }

    private fun handleShare(command: AgentCommand.ShareMedia): Result<AgentAction> {
        if (command.mediaIds.isEmpty()) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要分享的媒体")
            )
        }
        return Result.success(AgentAction.Success(command.commandId, command))
    }

    // ── 写命令（经 Photos 桥）─────────────────────────────────────────────────

    private suspend fun handleFavorite(command: AgentCommand.FavoriteMedia): Result<AgentAction> {
        val id = command.mediaId.toLongOrNull()
        val asset = id?.let { repository.getMediaById(it) }
            ?: return Result.success(AgentAction.TextReply(command.commandId, "未找到指定媒体（可能已被删除）"))
        val ok = bridge.setFavorite(asset.uri, command.favorite)
        val message = when {
            !ok -> "收藏操作失败"
            command.favorite -> "已收藏"
            else -> "已取消收藏"
        }
        return Result.success(AgentAction.TextReply(command.commandId, message))
    }

    private suspend fun handleDelete(command: AgentCommand.DeleteMedia): Result<AgentAction> {
        val ids = command.mediaIds.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.INVALID_PARAMS, "没有指定要删除的媒体")
            )
        }
        val idSet = ids.toSet()
        val targets = repository.allMedia.first().filter { it.id in idSet }
        if (targets.isEmpty()) {
            return Result.success(AgentAction.TextReply(command.commandId, "没有找到要删除的媒体（可能已被删除）"))
        }
        val ok = bridge.deleteMedia(targets.map { it.uri })
        // 删除后媒体 id 失效，上一轮搜索快照一并作废
        lastSearchAssets = null
        val message = if (ok) {
            "已请求删除 ${targets.size} 个媒体，系统会弹确认窗，确认后才会真正删除"
        } else {
            "删除请求提交失败"
        }
        return Result.success(AgentAction.TextReply(command.commandId, message))
    }

    companion object {
        /** 空关键词搜索（「看看最近的」）的返回上限。 */
        private const val MAX_RECENT_RESULTS = 50

        val SUPPORTED_COMMANDS: List<String> = listOf(
            "get_gallery_summary",
            "search_media",
            "refine_media_search",
            "view_media",
            "select_media",
            "favorite_media",
            "delete_media",
            "share_media"
        )
    }
}
