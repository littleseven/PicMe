package com.mamba.picme.features.camera

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.features.common.chat.AgentMessage

/**
 * 将 AiAgentCommand 转换为 CommandExecution 消息列表
 *
 * 单命令 → 一条 CommandExecution
 * BatchExecute → 多条 CommandExecution（展开显示每个子命令）
 * TextReply → AgentText（纯文本回复）
 */
internal fun commandToExecutionMessages(command: AiAgentCommand): List<AgentMessage> {
    return when (command) {
        is AiAgentCommand.BatchExecute -> {
            val total = command.commands.size
            command.commands.mapIndexed { index, subCmd ->
                AgentMessage.CommandExecution(
                    commandName = getCommandDisplayName(subCmd),
                    commandIcon = resolveCommandIcon(subCmd),
                    status = AgentMessage.CommandExecution.Status.SUCCESS,
                    detail = getCommandDetail(subCmd),
                    index = index + 1,
                    total = total
                )
            }
        }
        is AiAgentCommand.TextReply -> listOf(
            AgentMessage.AgentText(content = command.message)
        )
        else -> listOf(
            AgentMessage.CommandExecution(
                commandName = getCommandDisplayName(command),
                commandIcon = resolveCommandIcon(command),
                status = AgentMessage.CommandExecution.Status.SUCCESS,
                detail = getCommandDetail(command),
                index = 0,
                total = 1
            )
        )
    }
}

internal fun getCommandDisplayName(command: AiAgentCommand): String = when (command) {
    is AiAgentCommand.AdjustBeauty -> "调整美颜"
    is AiAgentCommand.SwitchFilter -> "切换滤镜"
    is AiAgentCommand.SwitchStyle -> "切换风格"
    is AiAgentCommand.SwitchScene -> "切换场景"
    is AiAgentCommand.SwitchRatio -> "切换画幅"
    is AiAgentCommand.AdjustExposure -> "调整曝光"
    is AiAgentCommand.AdjustZoom -> "调整变焦"
    is AiAgentCommand.FlipCamera -> "翻转摄像头"
    is AiAgentCommand.CapturePhoto -> "拍照"
    is AiAgentCommand.Delay -> "等待"
    is AiAgentCommand.ToggleRecording -> "切换录像"
    is AiAgentCommand.SwitchMode -> "切换模式"
    is AiAgentCommand.NavigateTo -> "页面跳转"
    is AiAgentCommand.GoBack -> "返回"
    is AiAgentCommand.BatchExecute -> "批量执行"
    is AiAgentCommand.TextReply -> "文本回复"
    is AiAgentCommand.SearchMedia -> "搜索照片"
    is AiAgentCommand.ApplyEditRecipe -> "AI 一键优化"
}

/**
 * 将 AiAgentCommand 映射为可视化图标，与 common.chat 中的映射保持一致。
 */
internal fun resolveCommandIcon(command: AiAgentCommand): ImageVector = when (command) {
    is AiAgentCommand.AdjustBeauty -> Icons.Rounded.Face
    is AiAgentCommand.SwitchFilter -> Icons.Rounded.PhotoFilter
    is AiAgentCommand.SwitchStyle -> Icons.Rounded.Style
    is AiAgentCommand.SwitchScene -> Icons.Rounded.Videocam
    is AiAgentCommand.SwitchRatio -> Icons.Rounded.AspectRatio
    is AiAgentCommand.AdjustExposure -> Icons.Rounded.Exposure
    is AiAgentCommand.AdjustZoom -> Icons.Rounded.ZoomIn
    is AiAgentCommand.FlipCamera -> Icons.Rounded.FlipCameraAndroid
    is AiAgentCommand.CapturePhoto -> Icons.Rounded.CameraAlt
    is AiAgentCommand.Delay -> Icons.Rounded.HourglassEmpty
    is AiAgentCommand.ToggleRecording -> Icons.Rounded.Videocam
    is AiAgentCommand.SwitchMode -> Icons.Rounded.Settings
    is AiAgentCommand.NavigateTo -> Icons.AutoMirrored.Rounded.OpenInNew
    is AiAgentCommand.GoBack -> Icons.AutoMirrored.Rounded.ArrowBack
    is AiAgentCommand.BatchExecute -> Icons.AutoMirrored.Rounded.FactCheck
    is AiAgentCommand.TextReply -> Icons.AutoMirrored.Rounded.ShortText
    is AiAgentCommand.SearchMedia -> Icons.Rounded.Search
    is AiAgentCommand.ApplyEditRecipe -> Icons.Rounded.AutoFixHigh
}

internal fun getCommandDetail(command: AiAgentCommand): String = when (command) {
    is AiAgentCommand.AdjustBeauty -> buildString {
        val s = command.settings
        val parts = mutableListOf<String>()
        if (s.smoothing > 0) parts.add("磨皮 ${s.smoothing.toInt()}%")
        if (s.whitening > 0) parts.add("美白 ${s.whitening.toInt()}%")
        if (s.slimFace != 0f) parts.add("瘦脸 ${s.slimFace.toInt()}%")
        if (s.bigEyes > 0) parts.add("大眼 ${s.bigEyes.toInt()}%")
        if (parts.isEmpty()) append("默认参数") else append(parts.joinToString(", "))
    }
    is AiAgentCommand.SwitchFilter -> "滤镜: ${command.filterType.name}"
    is AiAgentCommand.SwitchStyle -> "风格: ${command.styleFilter.name}"
    is AiAgentCommand.SwitchScene -> "场景: ${command.sceneName}"
    is AiAgentCommand.SwitchRatio -> "比例: ${command.ratio}"
    is AiAgentCommand.AdjustExposure -> "曝光: ${command.exposure}"
    is AiAgentCommand.AdjustZoom -> "变焦: ${command.zoomRatio}x"
    is AiAgentCommand.NavigateTo -> "目标: ${command.destination}"
    is AiAgentCommand.Delay -> "延迟: ${command.delayMs}ms"
    else -> ""
}
