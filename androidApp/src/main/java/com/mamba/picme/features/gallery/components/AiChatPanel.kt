package com.mamba.picme.features.gallery.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.features.common.chat.AgentMessage
import com.mamba.picme.features.common.chat.AiChatScreen

/**
 * AI Chat Panel - 自然语言图片编辑聊天界面
 * 
 * 功能：
 * 1. 用户通过自然语言描述想要对图片进行的编辑
 * 2. AI Agent 解析指令并执行相应的图片编辑操作
 * 3. 支持美颜调整、滤镜应用、裁剪旋转等编辑命令
 * 
 * 注意：此组件已升级为使用统一的 AiChatScreen 组件
 */
@Composable
fun AiChatPanel(
    currentAsset: MediaAsset,
    onDismiss: () -> Unit,
    onApplyEdit: (Bitmap) -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    var userInput by remember { mutableStateOf("") }
    val messages = remember { mutableListOf<AgentMessage>() }
    var isProcessing by remember { mutableStateOf(false) }

    // 自动打开面板（用于演示）
    if (!isVisible) {
        isVisible = true
    }

    AiChatScreen(
        visible = isVisible,
        messages = messages,
        isProcessing = isProcessing,
        onVisibleChange = { isVisible = it },
        onSendMessage = { input ->
            // Add user message
            messages.add(AgentMessage.UserText(input))
            userInput = ""

            // Simulate AI processing
            isProcessing = true
            processAiCommand(input) { response ->
                isProcessing = false
                messages.add(AgentMessage.AgentText(response))
                
                // 待重构：调用 onApplyEdit 应用编辑
            }
        },
        onCommand = { command ->
            // 待重构：执行命令并应用编辑
        }
    )
}

/**
 * 模拟 AI 命令处理（TODO: 集成真实的 AiAgentUseCase）
 */
private fun processAiCommand(command: String, onResponse: (String) -> Unit) {
    // 这里应该调用 AiAgentUseCase 来处理自然语言命令
    // 由于当前 AiAgentUseCase 是针对相机模式的，需要扩展支持图片编辑场景
    
    // 模拟一些常见命令的响应
    when {
        command.contains("磨皮") || command.contains("美白") || command.contains("美颜") -> {
            onResponse("好的，我已经为您调整了美颜参数，让皮肤更加光滑细腻~")
        }
        command.contains("滤镜") || command.contains("风格") -> {
            onResponse("已为您应用了喜欢的滤镜效果，看看喜欢吗？")
        }
        command.contains("瘦脸") || command.contains("大眼") -> {
            onResponse("面部轮廓已经优化完成，看起来更精致了呢~")
        }
        command.contains("保存") || command.contains("导出") -> {
            onResponse("好的，正在为您保存图片...")
            // 待重构：触发图片保存逻辑
        }
        else -> {
            onResponse("我理解您想编辑这张图片。您可以尝试说：\n• 磨皮美白\n• 应用滤镜\n• 瘦脸大眼\n• 调整亮度对比度\n• 保存修改")
        }
    }
}
