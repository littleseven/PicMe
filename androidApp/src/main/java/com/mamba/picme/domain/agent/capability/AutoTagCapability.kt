package com.mamba.picme.domain.agent.capability

import android.content.Context
import com.mamba.picme.R
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.domain.tag.scan.ScanQueuePolicy
import com.mamba.picme.domain.tag.scan.ScanSessionState

/**
 * 自动 Tag 生成 Capability
 *
 * 将标签系统作为 Agent 可编排的 Capability 暴露出来。
 * 支持 Agent 通过 Tool Calling 触发全量标签扫描和查询特定照片的标签。
 *
 * 所有扫描统一委托给 [TagScanOrchestrator]，确保进度/统计与 UI 控制页同源。
 */
class AutoTagCapability(
    private val context: Context,
    private val orchestrator: TagScanOrchestrator
) : Capability {

    companion object {
        private const val TAG = "AutoTagCapability"
    }

    override val name = "auto_tag"
    override val description = context.getString(R.string.auto_tag_capability_description)

    override fun supportedCommands(): List<String> = listOf(
        "scan_all_tags",
        "get_photo_tags",
        "get_tag_progress",
        "cancel_tag_scan"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "scan_all_tags" -> context.getString(R.string.auto_tag_cmd_scan_all_tags)
        "get_photo_tags" -> context.getString(R.string.auto_tag_cmd_get_photo_tags)
        "get_tag_progress" -> context.getString(R.string.auto_tag_cmd_get_tag_progress)
        "cancel_tag_scan" -> context.getString(R.string.auto_tag_cmd_cancel_tag_scan)
        else -> context.getString(R.string.auto_tag_cmd_fallback, command)
    }

    override fun isAvailable(): Boolean = true

    override fun activeScenes(): List<SceneManager.Scene> =
        listOf(SceneManager.Scene.GALLERY)

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val methodName = AgentCommand.getMethodName(command)
        Logger.d(TAG, "Executing command: $methodName")

        return if (methodName == "unknown" && command is AgentCommand.Unknown) {
            // 从 raw 文本解析命令名
            val cmdId = AgentIdGenerator.nextId()
            when {
                command.raw.contains("scan_all_tags") -> handleScanAll(cmdId)
                command.raw.contains("get_photo_tags") -> handleGetPhotoTags(cmdId, command.raw)
                command.raw.contains("get_tag_progress") -> handleGetProgress(cmdId)
                command.raw.contains("cancel_tag_scan") -> handleCancelScan(cmdId)
                else -> Result.success(
                    AgentAction.Error(
                        cmdId,
                        AgentErrorCode.METHOD_NOT_FOUND,
                        this.context.getString(R.string.auto_tag_unknown_command)
                    )
                )
            }
        } else {
            Result.success(
                AgentAction.Error(
                    AgentIdGenerator.nextId(),
                    AgentErrorCode.METHOD_NOT_FOUND,
                    this.context.getString(R.string.auto_tag_unsupported_command, methodName)
                )
            )
        }
    }

    private suspend fun handleScanAll(cmdId: Int): Result<AgentAction> {
        Logger.i(TAG, "Starting full tag scan")
        orchestrator.scheduleAutoScan(ScanQueuePolicy())
        return Result.success(
            AgentAction.Success(
                cmdId,
                AgentCommand.TextReply(cmdId, context.getString(R.string.auto_tag_scan_started))
            )
        )
    }

    private suspend fun handleGetPhotoTags(cmdId: Int, raw: String): Result<AgentAction> {
        // 尝试从 raw 中提取 photoId: "get_photo_tags:123"
        val photoId = raw.substringAfter(":").trim().toLongOrNull()
        if (photoId == null) {
            return Result.success(
                AgentAction.Error(
                    cmdId,
                    AgentErrorCode.INVALID_PARAMS,
                    context.getString(R.string.auto_tag_missing_photo_id)
                )
            )
        }

        val db = AppDatabase.getDatabase(context)
        val entity = db.mediaDao().getMediaById(photoId)
        if (entity == null) {
            return Result.success(
                AgentAction.Error(
                    cmdId,
                    AgentErrorCode.INVALID_REQUEST,
                    context.getString(R.string.auto_tag_photo_not_found, photoId)
                )
            )
        }

        val labels = entity.labels ?: "{}"
        return Result.success(AgentAction.Success(cmdId, AgentCommand.TextReply(cmdId, labels)))
    }

    private suspend fun handleGetProgress(cmdId: Int): Result<AgentAction> {
        val progress = orchestrator.progress.value
        val scanning = progress?.state in setOf(
            ScanSessionState.RUNNING,
            ScanSessionState.PAUSING,
            ScanSessionState.CANCELLING
        )
        val message = if (scanning) {
            context.getString(
                R.string.auto_tag_scanning_progress,
                progress?.processed ?: 0,
                progress?.total ?: 0
            )
        } else {
            context.getString(R.string.auto_tag_not_scanning)
        }
        return Result.success(AgentAction.Success(cmdId, AgentCommand.TextReply(cmdId, message)))
    }

    private suspend fun handleCancelScan(cmdId: Int): Result<AgentAction> {
        orchestrator.cancel()
        return Result.success(
            AgentAction.Success(
                cmdId,
                AgentCommand.TextReply(cmdId, context.getString(R.string.auto_tag_cancelled))
            )
        )
    }
}
