package com.mamba.picme.features.camera

import android.content.Context
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
import com.mamba.picme.R
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.features.common.chat.AgentMessage

/**
 * 将 AiAgentCommand 转换为 CommandExecution 消息列表
 *
 * 单命令 → 一条 CommandExecution
 * BatchExecute → 多条 CommandExecution（展开显示每个子命令）
 * TextReply → AgentText（纯文本回复）
 */
internal fun commandToExecutionMessages(context: Context, command: AiAgentCommand): List<AgentMessage> {
    return when (command) {
        is AiAgentCommand.BatchExecute -> {
            val total = command.commands.size
            command.commands.mapIndexed { index, subCmd ->
                AgentMessage.CommandExecution(
                    commandName = getCommandDisplayName(context, subCmd),
                    commandIcon = resolveCommandIcon(subCmd),
                    status = AgentMessage.CommandExecution.Status.SUCCESS,
                    detail = getCommandDetail(context, subCmd),
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
                commandName = getCommandDisplayName(context, command),
                commandIcon = resolveCommandIcon(command),
                status = AgentMessage.CommandExecution.Status.SUCCESS,
                detail = getCommandDetail(context, command),
                index = 0,
                total = 1
            )
        )
    }
}

internal fun getCommandDisplayName(context: Context, command: AiAgentCommand): String = when (command) {
    is AiAgentCommand.AdjustBeauty -> context.getString(R.string.camera_cmd_adjust_beauty)
    is AiAgentCommand.SwitchFilter -> context.getString(R.string.camera_cmd_switch_filter)
    is AiAgentCommand.SwitchStyle -> context.getString(R.string.camera_cmd_switch_style)
    is AiAgentCommand.SwitchScene -> context.getString(R.string.camera_cmd_switch_scene)
    is AiAgentCommand.SwitchRatio -> context.getString(R.string.camera_cmd_switch_ratio)
    is AiAgentCommand.AdjustExposure -> context.getString(R.string.camera_cmd_adjust_exposure)
    is AiAgentCommand.AdjustZoom -> context.getString(R.string.camera_cmd_adjust_zoom)
    is AiAgentCommand.FlipCamera -> context.getString(R.string.flip_camera)
    is AiAgentCommand.CapturePhoto -> context.getString(R.string.camera_cmd_capture_photo)
    is AiAgentCommand.Delay -> context.getString(R.string.camera_cmd_delay)
    is AiAgentCommand.ToggleRecording -> context.getString(R.string.camera_cmd_toggle_recording)
    is AiAgentCommand.SwitchMode -> context.getString(R.string.camera_cmd_switch_mode)
    is AiAgentCommand.NavigateTo -> context.getString(R.string.camera_cmd_navigate)
    is AiAgentCommand.GoBack -> context.getString(R.string.back)
    is AiAgentCommand.BatchExecute -> context.getString(R.string.camera_cmd_batch_execute)
    is AiAgentCommand.TextReply -> context.getString(R.string.camera_cmd_text_reply)
    is AiAgentCommand.SearchMedia -> context.getString(R.string.search_photos)
    is AiAgentCommand.ApplyEditRecipe -> context.getString(R.string.ai_optimize)
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

internal fun getCommandDetail(context: Context, command: AiAgentCommand): String = when (command) {
    is AiAgentCommand.AdjustBeauty -> buildString {
        val s = command.settings
        val parts = mutableListOf<String>()
        if (s.smoothing > 0) parts.add(context.getString(R.string.camera_cmd_detail_smoothing, s.smoothing.toInt()))
        if (s.whitening > 0) parts.add(context.getString(R.string.camera_cmd_detail_whitening, s.whitening.toInt()))
        if (s.slimFace != 0f) parts.add(context.getString(R.string.camera_cmd_detail_slim_face, s.slimFace.toInt()))
        if (s.bigEyes > 0) parts.add(context.getString(R.string.camera_cmd_detail_big_eyes, s.bigEyes.toInt()))
        if (parts.isEmpty()) append(context.getString(R.string.camera_cmd_default_params)) else append(parts.joinToString(", "))
    }
    is AiAgentCommand.SwitchFilter -> context.getString(R.string.camera_cmd_detail_filter, command.filterType.name)
    is AiAgentCommand.SwitchStyle -> context.getString(R.string.camera_cmd_detail_style, command.styleFilter.name)
    is AiAgentCommand.SwitchScene -> context.getString(R.string.camera_cmd_detail_scene, command.sceneName)
    is AiAgentCommand.SwitchRatio -> context.getString(R.string.camera_cmd_detail_ratio, command.ratio)
    is AiAgentCommand.AdjustExposure -> context.getString(R.string.camera_cmd_detail_exposure, command.exposure)
    is AiAgentCommand.AdjustZoom -> context.getString(R.string.camera_cmd_detail_zoom, command.zoomRatio)
    is AiAgentCommand.NavigateTo -> context.getString(R.string.camera_cmd_detail_destination, command.destination)
    is AiAgentCommand.Delay -> context.getString(R.string.camera_cmd_detail_delay, command.delayMs)
    else -> ""
}
