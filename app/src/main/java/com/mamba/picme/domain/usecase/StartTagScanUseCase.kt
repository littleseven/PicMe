package com.mamba.picme.domain.usecase

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mamba.picme.service.tag.TagGenerationService

/**
 * 启动、控制、查询 TAG 扫描任务。
 *
 * 所有调度请求都通过 [TagGenerationService] 的 Intent 机制下发，避免生命周期耦合。
 * 状态查询直接读取 Service 暴露的 StateFlow。
 */
class StartTagScanUseCase(
    private val context: Context
) {
    suspend operator fun invoke(
        action: String,
        taskType: String? = null,
        mode: String? = null
    ): StartTagScanResult {
        val normalizedAction = action.lowercase()
        return when (normalizedAction) {
            "start" -> start(taskType, mode)
            "pause" -> pause()
            "resume" -> resume()
            "cancel" -> cancel()
            "query" -> query()
            else -> StartTagScanResult.Error("不支持的 action: $action")
        }
    }

    private fun start(taskType: String?, mode: String?): StartTagScanResult {
        val resolvedType = taskType?.takeIf { it.isNotBlank() } ?: "auto"
        val resolvedMode = mode?.takeIf { it.isNotBlank() } ?: "incremental"

        // 确保 Service 已在前台运行
        ContextCompat.startForegroundService(
            context,
            Intent(context, TagGenerationService::class.java)
        )

        val intent = TagGenerationService.intentStartTagScan(
            context = context,
            taskType = resolvedType.lowercase(),
            mode = resolvedMode.lowercase()
        )
        ContextCompat.startForegroundService(context, intent)

        return StartTagScanResult.Started(
            taskType = resolvedType.lowercase(),
            mode = resolvedMode.lowercase(),
            message = "已启动 ${displayName(resolvedType)} 扫描（${displayMode(resolvedMode)}）"
        )
    }

    private fun pause(): StartTagScanResult {
        ContextCompat.startForegroundService(
            context,
            TagGenerationService.intentPause(context)
        )
        return StartTagScanResult.ControlAck(
            action = "pause",
            message = "扫描已暂停"
        )
    }

    private fun resume(): StartTagScanResult {
        ContextCompat.startForegroundService(
            context,
            TagGenerationService.intentResume(context)
        )
        return StartTagScanResult.ControlAck(
            action = "resume",
            message = "扫描已恢复"
        )
    }

    private fun cancel(): StartTagScanResult {
        ContextCompat.startForegroundService(
            context,
            TagGenerationService.intentCancel(context)
        )
        return StartTagScanResult.ControlAck(
            action = "cancel",
            message = "扫描已取消"
        )
    }

    private fun query(): StartTagScanResult {
        val progress = TagGenerationService.sessionProgress.value
            ?: return StartTagScanResult.Error("当前没有活跃的扫描会话")
        return StartTagScanResult.Status(
            sessionId = progress.sessionId,
            state = progress.state.name,
            currentPass = progress.currentPass?.name,
            currentMediaId = progress.currentMediaId,
            processed = progress.processed,
            total = progress.total,
            pending = progress.pending,
            failed = progress.failed,
            estimatedRemainingMs = progress.estimatedRemainingMs,
            messages = progress.messages.map { ScanMessageDto(it.timestamp, it.level.name, it.text) }
        )
    }

    private fun displayName(taskType: String): String = when (taskType.lowercase()) {
        "auto" -> "默认"
        "face" -> "人脸"
        "scene" -> "场景"
        "activity" -> "活动"
        "objects" -> "物体"
        "tags" -> "标签"
        "summary" -> "摘要"
        else -> taskType
    }

    private fun displayMode(mode: String): String = when (mode.lowercase()) {
        "full" -> "全量"
        else -> "增量"
    }
}

sealed class StartTagScanResult {
    data class Started(
        val taskType: String,
        val mode: String,
        val message: String
    ) : StartTagScanResult()

    data class ControlAck(
        val action: String,
        val message: String
    ) : StartTagScanResult()

    data class Status(
        val sessionId: String,
        val state: String,
        val currentPass: String?,
        val currentMediaId: Long?,
        val processed: Int,
        val total: Int,
        val pending: Int,
        val failed: Int,
        val estimatedRemainingMs: Long?,
        val messages: List<ScanMessageDto>
    ) : StartTagScanResult()

    data class Error(
        val error: String
    ) : StartTagScanResult()
}

data class ScanMessageDto(
    val timestamp: Long,
    val level: String,
    val text: String
)
