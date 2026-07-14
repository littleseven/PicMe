package com.mamba.picme.features.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding


import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.launch

/**
 * Agent 消息模型
 */
data class AgentMessage(
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Global Agent Panel 状态
 */
class GlobalAgentPanelState {
    var isVisible by mutableStateOf(false)
    var isExpanded by mutableStateOf(true)
    var messages by mutableStateOf<List<AgentMessage>>(emptyList())
    var isProcessing by mutableStateOf(false)

    fun addMessage(message: AgentMessage) {
        messages = messages + message
    }

    fun toggle() {
        isVisible = !isVisible
        if (isVisible) isExpanded = true
    }

    fun open() {
        isVisible = true
        isExpanded = true
    }

    fun close() {
        isVisible = false
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
    }
}

@Composable
fun rememberGlobalAgentPanelState(): GlobalAgentPanelState {
    return remember { GlobalAgentPanelState() }
}

/**
 * 全局 Agent Panel（简化版，使用单例）
 *
 * 可在任意页面使用，自动使用单例 Orchestrator 和创建 State
 */
@Composable
fun GlobalAgentPanel(
    pageContext: PageContext? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state = rememberGlobalAgentPanelState()
    val orchestrator = remember { AgentOrchestrator.getInstance(context) }
    val sceneManager = remember { SceneManager.getInstance() }
    val currentScene by sceneManager.currentScene.collectAsState()
    val agentScene = remember(currentScene) {
        when (currentScene) {
            SceneManager.Scene.CAMERA -> AgentScene.CAMERA
            SceneManager.Scene.GALLERY -> AgentScene.GALLERY
            else -> AgentScene.CAMERA
        }
    }
    val agentContext = remember(agentScene) { AgentContext(scene = agentScene) }

    GlobalAgentPanel(
        state = state,
        orchestrator = orchestrator,
        agentContext = agentContext,
        pageContext = pageContext,
        modifier = modifier
    )
}

/**
 * 全局 Agent Panel
 *
 * 可在任意页面使用，支持：
 * - 浮动按钮触发
 * - 展开/折叠对话面板
 * - 文本输入和语音输入
 * - 场景感知（自动根据当前页面路由命令）
 */
@Composable
fun GlobalAgentPanel(
    state: GlobalAgentPanelState,
    orchestrator: AgentOrchestrator,
    agentContext: AgentContext,
    pageContext: PageContext? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // 浮动按钮（面板关闭时显示）
    if (!state.isVisible) {
        FloatingActionButton(
            onClick = { state.open() },
            modifier = modifier
                .padding(16.dp)
                .navigationBarsPadding(),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardVoice,
                contentDescription = stringResource(R.string.ai_agent),
                tint = Color.White
            )
        }
    }

    // Agent 面板（展开时显示）
    AnimatedVisibility(
        visible = state.isVisible,
        enter = slideInVertically { height -> height },
        exit = slideOutVertically { height -> height },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .navigationBarsPadding()
        ) {
            // 标题栏
            AgentPanelHeader(
                isExpanded = state.isExpanded,
                onToggleExpand = { state.toggleExpand() },
                onClose = { state.close() }
            )

            // 消息列表（展开时显示）
            AnimatedVisibility(
                visible = state.isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    // 消息列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 300.dp)
                            .padding(horizontal = 16.dp),
                        reverseLayout = true
                    ) {
                        items(state.messages.asReversed()) { message ->
                            AgentMessageItem(message = message)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 输入区域
                    AgentInputArea(
                        isProcessing = state.isProcessing,
                        onSendMessage = { text ->
                            scope.launch {
                                sendMessage(
                                    text = text,
                                    state = state,
                                    orchestrator = orchestrator,
                                    agentContext = agentContext,
                                    pageContext = pageContext
                                )
                            }
                            keyboardController?.hide()
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AgentPanelHeader(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.ai_agent),
            color = Color.White,
            fontSize = 16.sp,
            style = MaterialTheme.typography.titleMedium
        )

        Row {
            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                    contentDescription = if (isExpanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                    tint = Color.White
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun AgentMessageItem(message: AgentMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (message.isFromUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.15f)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.content,
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun AgentInputArea(
    isProcessing: Boolean,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("试试说：去相册/删除这张/切换主题...", color = Color.Gray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (inputText.isNotBlank() && !isProcessing) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Gray
            ),
            enabled = !isProcessing
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = {
                if (inputText.isNotBlank() && !isProcessing) {
                    onSendMessage(inputText)
                    inputText = ""
                }
            },
            enabled = inputText.isNotBlank() && !isProcessing
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = stringResource(R.string.chat_send),
                tint = if (inputText.isNotBlank() && !isProcessing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Gray
                }
            )
        }
    }
}

/**
 * 发送消息并处理响应
 */
private suspend fun sendMessage(
    text: String,
    state: GlobalAgentPanelState,
    orchestrator: AgentOrchestrator,
    agentContext: AgentContext,
    pageContext: PageContext?
) {
    val tag = "GlobalAgentPanel"

    // 添加用户消息
    state.addMessage(AgentMessage(content = text, isFromUser = true))
    state.isProcessing = true

    try {
        val result = orchestrator.processUserInput(
            input = text,
            agentContext = agentContext,
            pageContext = pageContext
        )

        result.fold(
            onSuccess = { action ->
                val responseText = when (action) {
                    is AgentAction.Success -> {
                        val commandName = action.command::class.simpleName ?: "操作"
                        "已执行: $commandName"
                    }
                    is AgentAction.TextReply -> action.message
                    is AgentAction.Error -> "抱歉，${action.message}"
                    is AgentAction.BatchResult -> "已执行批量操作 (${action.results.size} 个)"
                    is AgentAction.MediaResults -> "找到 ${action.totalCount} 张「${action.query}」的照片"
                }
                state.addMessage(AgentMessage(content = responseText, isFromUser = false))
            },
            onFailure = { error ->
                Logger.e(tag, "Failed to process message", error)
                state.addMessage(
                    AgentMessage(
                        content = "处理失败：${error.message ?: "未知错误"}",
                        isFromUser = false
                    )
                )
            }
        )
    } catch (e: Exception) {
        Logger.e(tag, "Exception processing message", e)
        state.addMessage(
            AgentMessage(
                content = "出错了：${e.message ?: "未知错误"}",
                isFromUser = false
            )
        )
    } finally {
        state.isProcessing = false
    }
}
